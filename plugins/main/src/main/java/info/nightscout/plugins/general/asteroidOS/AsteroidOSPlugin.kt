package info.nightscout.plugins.general.asteroidOS

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.android.HasAndroidInjector
import info.nightscout.core.graph.OverviewData
import info.nightscout.core.utils.fabric.FabricPrivacy
import info.nightscout.database.entities.GlucoseValue
import info.nightscout.interfaces.Constants
import info.nightscout.interfaces.GlucoseUnit
import info.nightscout.interfaces.aps.Loop
import info.nightscout.interfaces.iob.GlucoseStatus
import info.nightscout.interfaces.iob.GlucoseStatusProvider
import info.nightscout.interfaces.iob.InMemoryGlucoseValue
import info.nightscout.interfaces.iob.IobCobCalculator
import info.nightscout.interfaces.plugin.ActivePlugin
import info.nightscout.interfaces.plugin.PluginBase
import info.nightscout.interfaces.plugin.PluginDescription
import info.nightscout.interfaces.plugin.PluginType
import info.nightscout.interfaces.profile.Profile
import info.nightscout.interfaces.profile.ProfileFunction
import info.nightscout.interfaces.utils.TrendCalculator
import info.nightscout.plugins.R
import info.nightscout.rx.AapsSchedulers
import info.nightscout.rx.bus.RxBus
import info.nightscout.rx.events.EventAutosensCalculationFinished
import info.nightscout.rx.events.EventLoopUpdateGui
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import info.nightscout.shared.interfaces.ResourceHelper
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

// Here, we send over BG data in a binary format to AsteroidOS smartwatches.
// The data is encoded as binary to reduce size; since transmissions eventually
// go over Bluetooth LE, it is important to not let the data become too large
// (BLE GATT characteristics typically transmit quite slowly).
//
// For the format spec, see the bg-data-binary-format-spec.txt file.

// NOTE: In this plugin, we use WEAR as the logging tag.
// "WEAR" is understood as a general "wearable" category,
// not specifically as being for WearOS.

// Constants for sending data to the sync app through Intent broadcast.
private const val ASTEROIDOS_SYNC_APP_PACKAGE_NAME = "org.asteroidos.sync"
private const val ASTEROIDOS_SYNC_APP_RECEIVER_CLASS = "org.asteroidos.sync.services.ExternalAppMessageReceiver"
private const val INTENT_ACTION_ASTEROIDOS_EXTAPPMESSAGE_PUSH = "org.asteroidos.sync.connectivity.extappmessage.PUSH"

private const val BG_DATA_FORMAT_VERSION_NUMBER = 1

// Flags for the flag byte in messages for the watchface.
private const val FLAG_UNIT_IS_MG_DL                   = (1 shl 0)
private const val FLAG_BG_VALUE_IS_VALID               = (1 shl 1)
private const val FLAG_BG_STATUS_PRESENT               = (1 shl 2)
private const val FLAG_LAST_LOOP_RUN_TIMESTAMP_PRESENT = (1 shl 3)
private const val FLAG_MUST_CLEAR_ALL_DATA             = (1 shl 4)

// TODO: This should be set to 24 hours. It would then be up to the watchface to pick
// the last N hours out of that if so desired. But currently, this is not possible, because
// AsteroidOS watchfaces have no way of adding their specific settings UI pages. This means
// that it is currently not possible to set the desired timespan on the watch.
private const val BG_TIME_SERIES_TIMESPAN = (60000 * 60 * 5.5).toLong()

@Singleton
class AsteroidOSPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy,
    private val context: Context,
    private val rxBus: RxBus,
    private val loop: Loop,
    private val overviewData: OverviewData,
    private val iobCobCalculator: IobCobCalculator,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val trendCalculator: TrendCalculator,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(AsteroidOSFragment::class.java.name)
        .pluginName(R.string.asteroidos_name)
        .pluginIcon(info.nightscout.core.main.R.drawable.ic_asteroidos)
        .shortName(R.string.asteroidos_shortname)
        .description(R.string.description_asteroidos),
    aapsLogger, rh, injector
) {
    private val disposable = CompositeDisposable()
    private var initialFullBGDataSent = false

    override fun onStart() {
        super.onStart()

        aapsLogger.debug(LTag.WEAR, "Clearing watchface at startup")
        clearWatchface()
        // Not sending the BG data just yet, because the necessary components
        // like the IOB calculator might not be available at this time.

        disposable += rxBus
            .toObservable(EventLoopUpdateGui::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ sendCurrentBGData() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAutosensCalculationFinished::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ sendCurrentBGData() }, fabricPrivacy::logException)
    }

    override fun onStop() {
        disposable.clear()
        aapsLogger.debug(LTag.WEAR, "Clearing watchface before stopping plugin")
        clearWatchface()
        super.onStop()
    }

    fun sendCurrentBGData() {
        val bgDataContext = BGDataContext(
            currentTime = System.currentTimeMillis(),
            profile = profileFunction.getProfile(),
            unit = profileFunction.getUnits(),
            lastBG = overviewData.lastBg(iobCobCalculator.ads),
            isActualBg = overviewData.isActualBg(iobCobCalculator.ads),
            glucoseStatus = glucoseStatusProvider.glucoseStatusData,
            trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)
        )

        val flags = (
            (if (bgDataContext.unit == GlucoseUnit.MGDL) FLAG_UNIT_IS_MG_DL else 0) or
                (if (bgDataContext.isActualBg) FLAG_BG_VALUE_IS_VALID else 0) or
                (if (bgDataContext.lastBG != null) FLAG_BG_STATUS_PRESENT else 0) or
                (if (loop.lastRun != null) FLAG_LAST_LOOP_RUN_TIMESTAMP_PRESENT else 0)
            ).toByte()

        val messageBytes = mutableListOf(BG_DATA_FORMAT_VERSION_NUMBER.toByte(), flags)
            .addBasalRate(bgDataContext)
            .addBGStatus(bgDataContext)
            .addBGTimeSeries(bgDataContext)
            .addBasalTimeSeries(bgDataContext)
            .addBaseBasalTimeSeries(bgDataContext)
            .addIOB()
            .addCOB()
            .addLastLoopRun()
            .toByteArray()

        sendDataToSyncApp(messageBytes)
    }

    private fun Int.toPosLong() = toLong() and 0xFFFFFFFFL

    private fun MutableList<Byte>.addNumeric(f: Float): MutableList<Byte> {
        val floatBits = f.toBits().toPosLong()

        return this.apply {
            add(((floatBits and 0x000000FFL) ushr 0).toByte())
            add(((floatBits and 0x0000FF00L) ushr 8).toByte())
            add(((floatBits and 0x00FF0000L) ushr 16).toByte())
            add(((floatBits and 0xFF000000L) ushr 24).toByte())
        }
    }

    private fun MutableList<Byte>.addNumeric(d: Double) =
        addNumeric(d.toFloat())

    private fun MutableList<Byte>.addNumeric(s: Short): MutableList<Byte> {
        return this.apply {
            add(((s.toInt() ushr 0) and 0xFF).toByte())
            add(((s.toInt() ushr 8) and 0xFF).toByte())
        }
    }

    private fun MutableList<Byte>.addNumeric(l: Long): MutableList<Byte> {
        return this.apply {
            add(((l ushr 0) and 0xFF).toByte())
            add(((l ushr 8) and 0xFF).toByte())
            add(((l ushr 16) and 0xFF).toByte())
            add(((l ushr 24) and 0xFF).toByte())
            add(((l ushr 32) and 0xFF).toByte())
            add(((l ushr 40) and 0xFF).toByte())
            add(((l ushr 48) and 0xFF).toByte())
            add(((l ushr 56) and 0xFF).toByte())
        }
    }

    private class BGDataContext(
        val currentTime: Long,
        val profile: Profile?,
        val unit: GlucoseUnit,
        val lastBG: InMemoryGlucoseValue?,
        val isActualBg: Boolean,
        val glucoseStatus: GlucoseStatus?,
        val trendArrow: GlucoseValue.TrendArrow?
    )

    private fun MutableList<Byte>.addBasalRate(bgDataContext: BGDataContext): MutableList<Byte> {
        val profile = bgDataContext.profile ?: return this

        val currentTime = bgDataContext.currentTime
        val currentTempBasal = iobCobCalculator.getTempBasalIncludingConvertedExtended(currentTime)
        val profileBasalRate = profile.getBasal(currentTime)

        val (curBasalRate, curTbrPercentage) =
            if (currentTempBasal != null) {
                if (currentTempBasal.isAbsolute) {
                    Pair(
                        currentTempBasal.rate,
                        (currentTempBasal.rate / profileBasalRate * 100).toInt()
                    )
                } else {
                    Pair(
                        profileBasalRate * currentTempBasal.rate / 100,
                        currentTempBasal.rate.toInt()
                    )
                }
            } else {
                Pair(profileBasalRate, 100)
            }

        return this.apply {
            addNumeric(profileBasalRate)
            addNumeric(curBasalRate)
            addNumeric(curTbrPercentage.toShort())
        }
    }

    private fun MutableList<Byte>.addBGStatus(bgDataContext: BGDataContext): MutableList<Byte> {
        return this.apply {
            if (bgDataContext.lastBG != null) {
                addNumeric(bgDataContext.lastBG.let {
                    when (bgDataContext.unit) {
                        GlucoseUnit.MGDL -> it.recalculated.roundToInt().toDouble()
                        GlucoseUnit.MMOL -> it.recalculated * Constants.MGDL_TO_MMOLL
                    }
                })

                addNumeric(bgDataContext.glucoseStatus?.delta?.toFloat() ?: Float.NaN)

                addNumeric(bgDataContext.lastBG.timestamp / 1000L)

                add(
                    when (bgDataContext.trendArrow) {
                        null,
                        GlucoseValue.TrendArrow.NONE      -> 0
                        GlucoseValue.TrendArrow.TRIPLE_UP -> 1
                        GlucoseValue.TrendArrow.DOUBLE_UP -> 2
                        GlucoseValue.TrendArrow.SINGLE_UP -> 3
                        GlucoseValue.TrendArrow.FORTY_FIVE_UP -> 4
                        GlucoseValue.TrendArrow.FLAT -> 5
                        GlucoseValue.TrendArrow.FORTY_FIVE_DOWN -> 6
                        GlucoseValue.TrendArrow.SINGLE_DOWN -> 7
                        GlucoseValue.TrendArrow.DOUBLE_DOWN -> 8
                        GlucoseValue.TrendArrow.TRIPLE_DOWN -> 9
                    }.toByte()
                )
            }
        }
    }

    private fun MutableList<Byte>.addBGTimeSeries(bgDataContext: BGDataContext): MutableList<Byte> {
        val startTime = bgDataContext.currentTime - BG_TIME_SERIES_TIMESPAN
        val sourceBGValues = iobCobCalculator.ads.getBucketedDataTableCopy()?.let { bucketedGlucoseValues ->
            bucketedGlucoseValues.filter { it.timestamp >= startTime }.sortedBy { it.timestamp }
        } ?: listOf()

        val numDataPoints = sourceBGValues.size
        add(((numDataPoints ushr 0) and 0xFF).toByte())
        add(((numDataPoints ushr 8) and 0xFF).toByte())

        if (sourceBGValues.isNotEmpty()) {
            val maxBgValue = if (overviewData.bgReadingsArray.isEmpty()) {
                val defaultValue = if (bgDataContext.unit == GlucoseUnit.MGDL) 180.0 else 10.0
                aapsLogger.debug(LTag.WEAR, "Using default max BG value $defaultValue for normalizing BG data")
                defaultValue
            } else {
                aapsLogger.debug(LTag.WEAR, "Using max BG value ${overviewData.maxBgValue} for normalizing BG data")
                overviewData.maxBgValue
            }

            sourceBGValues.forEach { glucoseValue ->
                // Normalize the BG values to the integer 0-32767 range.
                val normalizedTimestamp = ((glucoseValue.timestamp - startTime).toDouble() * 32767.0 / BG_TIME_SERIES_TIMESPAN).roundToInt().coerceIn(0, 32767)
                val normalizedValue = (glucoseValue.recalculated * 32767.0 / maxBgValue).roundToInt().coerceIn(0, 65535)

                addNumeric(normalizedTimestamp.toShort())
                addNumeric(normalizedValue.toShort())
            }
        }

        return this
    }

    private fun MutableList<Byte>.addBasalTimeSeries(bgDataContext: BGDataContext): MutableList<Byte> {
        return this.apply {
            // TODO
            addNumeric(0.toShort())
        }
    }

    private fun MutableList<Byte>.addBaseBasalTimeSeries(bgDataContext: BGDataContext): MutableList<Byte> {
        return this.apply {
            // TODO
            addNumeric(0.toShort())
        }
    }

    private fun MutableList<Byte>.addIOB(): MutableList<Byte> {
        val basalIOB = overviewData.basalIob(iobCobCalculator).basaliob
        val bolusIOB = overviewData.bolusIob(iobCobCalculator).iob

        return this.apply {
            addNumeric(basalIOB)
            addNumeric(bolusIOB)
        }
    }

    private fun MutableList<Byte>.addCOB(): MutableList<Byte> {
        val cobInfo = overviewData.cobInfo(iobCobCalculator)
        val currentCarbs = cobInfo.displayCob?.toInt() ?: 0
        val futureCarbs = cobInfo.futureCarbs.toInt()

        return this.apply {
            addNumeric(currentCarbs.toShort())
            addNumeric(futureCarbs.toShort())
        }
    }

    private fun MutableList<Byte>.addLastLoopRun(): MutableList<Byte> {
        loop.lastRun?.let {
            addNumeric(it.lastAPSRun / 1000L)
        }
        return this
    }

    private fun clearWatchface() {
        sendDataToSyncApp(byteArrayOf(BG_DATA_FORMAT_VERSION_NUMBER.toByte(), FLAG_MUST_CLEAR_ALL_DATA.toByte()))
    }

    private fun sendDataToSyncApp(messageBody: ByteArray) {
        // The AsteroidOS sync app expects three Extra fields in the intent:
        //
        // - "sender" : identifies the sender of the intent
        // - "destination" : identifies the destination inside the smartwatch
        //   (this is used by the watch for routing the message to a destination
        //   D-Bus object)
        // - "payload" : the actual message payload

        aapsLogger.debug(LTag.WEAR, "Sending BG data to AsteroidOS with ${messageBody.size} byte(s)")

        val intent = Intent(INTENT_ACTION_ASTEROIDOS_EXTAPPMESSAGE_PUSH)
        intent.flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES
        intent.putExtra("sender", "AndroidAPS")
        intent.putExtra("destination", "BGDataReceiver")
        intent.putExtra("payload", messageBody)

        intent.component = ComponentName(ASTEROIDOS_SYNC_APP_PACKAGE_NAME, ASTEROIDOS_SYNC_APP_RECEIVER_CLASS)
        context.sendBroadcast(intent)
    }
}
