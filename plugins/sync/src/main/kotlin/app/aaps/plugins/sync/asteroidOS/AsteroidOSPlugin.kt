package app.aaps.plugins.sync.asteroidOS

import android.content.Context
import android.content.Intent
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventAppInitialized
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventTempBasalChange
import app.aaps.core.interfaces.rx.events.EventTreatmentChange
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewIobCob
import app.aaps.core.interfaces.rx.events.EventRunningModeChange
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.round
import app.aaps.plugins.sync.R
import javax.inject.Inject
import javax.inject.Singleton
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import kotlin.jvm.java
import kotlin.math.roundToInt
import kotlin.reflect.KMutableProperty1

// This plugin sends over BG data in a binary format to AsteroidOS smartwatches.
// The data is encoded as binary to reduce size; since transmissions eventually
// go over Bluetooth LE, it is important to not let the data become too large
// (BLE GATT characteristics typically transmit quite slowly).
//
// For the format spec, see the bg-data-binary-format-spec.md file.

// NOTE: This plugin uses WEAR as the logging tag. "WEAR" is understood as a
// general "wearable" category, not specifically as being for WearOS.

// Constants for sending data to the sync app through Intent broadcast.
private const val SYNC_APP_PACKAGE_NAME = "nodomain.freeyourgadget.gadgetbridge"
private const val INTENT_ACTION_EXTAPPMESSAGE_PUSH = "sync.connectivity.extappmessage.PUSH"

// The time series timespan is given in hours. It defines the timespan
// shown on the time series graph on the watchface. This quantity is
// stored in the AndroidAPS SP.
// (Min and Max constants are made internal to allow the fragment
// code to access it.)
internal const val MIN_ASTEROIDOS_TIME_SERIES_TIMESPAN = 1
internal const val MAX_ASTEROIDOS_TIME_SERIES_TIMESPAN = 6
private const val DEFAULT_ASTEROIDOS_TIME_SERIES_TIMESPAN = 2
private const val ASTEROIDOS_TIME_SERIES_TIMESPAN_KEY =
    "asteroidos-time-series-timespan-key"

private val DEFAULT_TIME_SERIES_VISUALIZATION_TYPE = TimeSeriesVisualizationType.DOTS
private const val ASTEROIDOS_TIME_SERIES_VISUALIZATION_TYPE_KEY =
    "asteroidos-time-series-visualization-key"

// Constants and enums for the BG data binary format.

internal const val UNKNOWN_INTEGER_QUANTITY = 0xFFFF

private const val UNKNOWN_TIMESTAMP = 0L

private const val BG_DATA_FORMAT_VERSION_NUMBER = 1

private enum class InitialByteFlags(val value: Int) {
    UNIT_IS_MG_DL(1 shl 7)
}

private enum class AOSLoopState(val index: Int) {
    UNKNOWN(0),
    DISABLED(1),
    DISCONNECTED(2),
    PAUSED(3),
    LGS(4),
    CLOSED(5),
    OPEN(6),
}

// NOTE: This mirrors the TrendArrow enum, except that it contains an
// index value that is explicitly set to the integer representations of
// the AOSTrendArrow items. While relying on the ordinal of TrendArrow
// might work, it is not guaranteed to be stable (if the order of
// TrendArrow items is later changed for example). Having a dedicated
// and explicit index value eliminates such possibilities.
private enum class AOSTrendArrow(val index: Int) {
    NONE(0),
    TRIPLE_UP(1),
    DOUBLE_UP(2),
    SINGLE_UP(3),
    FORTY_FIVE_UP(4),
    FLAT(5),
    FORTY_FIVE_DOWN(6),
    SINGLE_DOWN(7),
    DOUBLE_DOWN(8),
    TRIPLE_DOWN(9),
}

private enum class BlockID(val index: Int) {
    BASAL_RATE(1),
    IOB_COB(2),
    LOOP_STATUS(3),
    BG_STATUS(4),
    LOW_HIGH_BG_THRESHOLD(5),
}

enum class TimeSeriesVisualizationType(val index: Int) {
    DOTS(0),
    LINES(1);

    companion object {
        private val values = TimeSeriesVisualizationType.entries.toTypedArray()

        val validIntRange: IntRange = 0..<values.size

        fun fromInt(intValue: Int): TimeSeriesVisualizationType {
            return if (intValue in 0..<values.size)
                values[intValue]
            else
                throw IndexOutOfBoundsException(
                    "Attempted to convert invalid integer $intValue to " +
                    "TimeSeriesVisualizationType; valid int range: validIntRange"
                )
        }
    }
}

private fun Int.coerceAndWarn(aapsLogger: AAPSLogger, min: Int, max: Int, name: String): Int {
    val corrected = this.coerceIn(min, max)
    if (corrected != this) {
        aapsLogger.warn(
            LTag.WEAR,
            "$name value $this out of range [$min, $max], corrected to $corrected"
        )
    }
    return corrected
}

@Singleton
class AsteroidOSPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy,
    private val context: Context,
    private val rxBus: RxBus,
    private val loop: Loop,
    private val sp: SP,
    private val preferences: Preferences,
    private val overviewData: OverviewData,
    private var lastBgData: LastBgData,
    private val iobCobCalculator: IobCobCalculator,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val trendCalculator: TrendCalculator,
    private val profileFunction: ProfileFunction,
    private val processedTbrEbData: ProcessedTbrEbData
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.SYNC)
        .fragmentClass(AsteroidOSFragment::class.java.name)
        .pluginName(R.string.asteroidos_name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_asteroidos)
        .shortName(R.string.asteroidos_shortname)
        .description(R.string.description_asteroidos),
    aapsLogger, rh
) {
    private val disposable = CompositeDisposable()

    // If this is false, then sendBGData() will always send
    // out a full update, regardless of any other factors.
    private var initialFullBGDataSent = false

    // Helper structure to detect changes. This is needed to
    // minimize data transmissions and skip redundant ones.
    private class PreviousValues(
        var unit: GlucoseUnit? = null,
        var profileBasalRateAsShort: Short? = null,
        var tbrRateAsShort: Short? = null,
        var basalIOBAsShort: Short? = null,
        var bolusIOBAsShort: Short? = null,
        var currentCarbAsShort: Short? = null,
        var futureCarbsAsShort: Short? = null,
        var loopState: AOSLoopState? = null,
        var lastLoopRunTimestamp: Long? = null,
        var aosTrendArrow: AOSTrendArrow? = null,
        var currentBGAsShort: Short? = null,
        var bgDeltaAsShort: Short? = null,
        var bgStatusTimestamp: Long? = null,
        var maxBgValue: Double? = null,
    )
    private var previousValues = PreviousValues()

    // An RxJava scheduler used for sendBGData() and some clearWatchface() invocations.
    // It is set up such that it never executes more than one task at the same time.
    private var serialScheduler: Scheduler? = null

    // Time series timespan property. See the documentation at the beginning
    // of this file for information about what this timespan is for.
    private var _timeSeriesTimespan: Int = DEFAULT_ASTEROIDOS_TIME_SERIES_TIMESPAN
    var timeSeriesTimespan: Int
        set(value) {
            _timeSeriesTimespan = value.coerceAndWarn(
                aapsLogger,
                MIN_ASTEROIDOS_TIME_SERIES_TIMESPAN,
                MAX_ASTEROIDOS_TIME_SERIES_TIMESPAN,
                "timeSeriesTimespan"
            )
            sp.edit(commit = false) {
                putInt(ASTEROIDOS_TIME_SERIES_TIMESPAN_KEY, _timeSeriesTimespan)
            }
        }
        get() = _timeSeriesTimespan

    // Time series visualization property.
    private var _timeSeriesVisualizationType = TimeSeriesVisualizationType.DOTS
    var timeSeriesVisualizationType: TimeSeriesVisualizationType
        set(value) {
            _timeSeriesVisualizationType = value
            sp.edit(commit = false) {
                putInt(ASTEROIDOS_TIME_SERIES_VISUALIZATION_TYPE_KEY, _timeSeriesVisualizationType.index)
            }
        }
        get() = _timeSeriesVisualizationType

    override fun onStart() {
        // Note that no BG data is sent inside this function.
        // That's because required components like the IOB
        // calculator might not be available at this time.

        super.onStart()

        // Get preferences from the SP.

        _timeSeriesTimespan = sp.getInt(
            ASTEROIDOS_TIME_SERIES_TIMESPAN_KEY,
            DEFAULT_ASTEROIDOS_TIME_SERIES_TIMESPAN
        ).coerceAndWarn(aapsLogger,
            MIN_ASTEROIDOS_TIME_SERIES_TIMESPAN,
            MAX_ASTEROIDOS_TIME_SERIES_TIMESPAN,
            "timeSeriesTimespan"
        )
        _timeSeriesVisualizationType = TimeSeriesVisualizationType.fromInt(
            sp.getInt(
                ASTEROIDOS_TIME_SERIES_VISUALIZATION_TYPE_KEY,
                DEFAULT_TIME_SERIES_VISUALIZATION_TYPE.index
            ).coerceAndWarn(
                aapsLogger,
                TimeSeriesVisualizationType.validIntRange.first,
                TimeSeriesVisualizationType.validIntRange.last,
                "timeSeriesVisualizationType"
            )
        )

        // Wipe any previous state on the watchface that might still
        // be present - for example, in case AndroidAPS crashed.
        // Since at this point, the serialScheduler (which is normally what
        // schedules tasks that send out BG data) is not up and running yet,
        // there can be no concurrent IO activity, so calling this function
        // here directly is actually safe.
        clearWatchface()

        // Set up the Rx Scheduler that reuses an IO scheduler worker.
        // Workers all queue tasks, meaning that a worker always executes
        // exactly one task at the same time. By making the custom scheduler
        // always return the same worker in createWorker(), this effectively
        // prevents event handling executed the onNext subscribe() callbacks
        // from ever run in parallel. This is important, because the logic
        // in sendBGData() is _not_ thread safe. At most, one sendBGData()
        // call may happen at the same time. Directly using IO schedulers
        // would risk multiple parallel sendBGData() invocations.

        val rxWorker = aapsSchedulers.io.createWorker()
        disposable += rxWorker

        val newSerialScheduler = object : Scheduler() {
            override fun createWorker() = rxWorker
        }
        serialScheduler = newSerialScheduler

        // Subscribe to the necessary events to keep the watchface contents
        // up to date. sendBGData() is called always; that function itself
        // checks the type of the event and acts accordingly.

        for (eventClass in listOf(
            /*
            // EventLoopUpdateGui : For when the loop status is changed to something that is "online" (open / closed loop, LGS)
            EventLoopUpdateGui::class,
            // EventRunningModeChange : For when the loop status is changed to something that is "offline" (disabled / suspended loop)
            EventRunningModeChange::class,
            // EventRefreshOverview : For when the loop status is changed in some fringe cases
            // TODO: It is unclear why this is needed. One specific case was observed where the other loop related events
            // failed to communicate a change. That case was the following state transition:
            // Closed Loop -> Loop Disabled -> Pump Disconnected -> reconnect -> Loop Disabled -> resume loop -> Closed Loop
            // The last transition to Closed Loop is not communicated by these other events - only this one.
            EventRefreshOverview::class,
            // EventPreferenceChange : For when preferences are changed that affect BG display (mg/dL vs. mmol/L units for example)
            EventPreferenceChange::class,*/
            // EventAppInitialized : For full update on startup
            EventAppInitialized::class,
            // EventTempBasalChange : For when TBRs are set / canceled
            EventTempBasalChange::class,
            // EventTreatmentChange : For when carbs are entered / boluses delivered
            EventTreatmentChange::class,
            // EventAutosensCalculationFinished and EventUpdateOverviewIobCob : For when IOB / COB are recalculated
            EventAutosensCalculationFinished::class,
            EventUpdateOverviewIobCob::class,
            // EventRunningModeChange : For when the loop mode is changed (open / closed / disabled / suspended / etc.)
            EventRunningModeChange::class,
            // EventBucketedDataCreated : For when new bucketed BG data is available
            // (using bucketed data since that one is subject to smoothing and extrapolations)
            EventBucketedDataCreated::class,
            // EventNewBG : Used for tracking maxBgValue changes. unlike other events,
            // this one is not directly associated with any data block. Also, other
            // events may coincide with maxBgValue changes, but this one is added
            // since it is the most reliable one to use for that purpose.
            EventNewBG::class,
        )) {
            disposable += rxBus
                .toObservable(eventClass.java)
                .observeOn(newSerialScheduler)
                .subscribe(
                    { event -> aapsLogger.debug(LTag.WEAR, "Got event ${eventClass.simpleName ?: "<unknown>"}"); sendBGData(event) },
                    fabricPrivacy::logException
                )
        }
    }

    override fun onStop() {
        // Wipe the watchface contents to ensure no stale state is left on it.
        // Do this using the serial scheduler to ensure clearWatchface() does
        // not run concurrently to any in-flight sendBGData() calls that
        // the worker behind the serial scheduler might have in its queue.
        serialScheduler?.let { scheduler ->
            Single.just(Unit)
                .observeOn(scheduler)
                .blockingSubscribe {
                    aapsLogger.debug(LTag.WEAR, "Clearing watchface before stopping plugin")
                    clearWatchface()
                }
        }

        serialScheduler = null
        // Any queued tasks in the serialScheduler's worker will
        // be disposed of by this, since the worker behind that
        // scheduler itself gets disposed of here.
        disposable.clear()

        super.onStop()
    }

    fun sendFullBGData() {
        // Provoke a full dataset transmission by pretending that the app just got initialized.
        sendBGData(EventAppInitialized())
    }

    private fun sendBGData(event: Event) {
        var success = false

        // If true, actually send out the message. This flag is present since
        // there are checks in some places here to avoid sending out messages
        // when the values did not actually change.
        var doSendMessage = false

        try {
            ////// Prerequisites //////

            val currentTime = System.currentTimeMillis()
            val profile = profileFunction.getProfile() ?: let {
                aapsLogger.warn(LTag.WEAR, "Cannot send BG data since there is currently no profile")
                return
            }
            val unit = profileFunction.getUnits()

            // Get the max BG value for when messages required normalized
            // BG value data. If overviewData.maxBgValue is not available
            // (it only is if overviewData.bgReadingsArray actually has values),
            // use the common 180 mg/dL upper margin (or 10 mmol/L) as the
            // default. Later, the actual max BG value might be available,
            // and then, the graph will automatically be corrected because
            // the new normalized values will use this actual max BG value.
            //
            // Note that the max BG value may change in a sudden way between
            // sendBGData calls, especially if up until a certain point,
            // only the default value could be used, and the actual value
            // then ends up being significantly different. This way cause
            // a sudden visual change on display. This is considered to be
            // okay, since such sudden changes can happen in AndroidAPS
            // overall at several points.
            val (maxBgValue, maxBgValueChanged) = if (overviewData.bgReadingsArray.isEmpty()) {
                val defaultValue = if (unit == GlucoseUnit.MGDL) 180.0 else 10.0
                aapsLogger.debug(LTag.WEAR, "Using default max BG value $defaultValue for normalizing BG data")
                previousValues.maxBgValue = defaultValue
                Pair(defaultValue, false)
            } else {
                if (previousValues.maxBgValue != overviewData.maxBgValue) {
                    aapsLogger.debug(LTag.WEAR, "Using max BG value ${overviewData.maxBgValue} for normalizing BG data")
                    previousValues.maxBgValue = overviewData.maxBgValue
                    Pair(overviewData.maxBgValue, true)
                } else
                    Pair(overviewData.maxBgValue, false)
            }

            val doFullUpdate = !initialFullBGDataSent || maxBgValueChanged || (event is EventAppInitialized) || (event is EventPreferenceChange)

            // Helper function and structures to check for value changes. This
            // also marks the message as to be sent if the value changed.
            // (If a full update is requested, this is overridden.)
            abstract class ValueCheckBase {
                abstract fun useNewValue()
                abstract fun checkValueAndUpdateIfChanged(): Boolean
            }
            class ValueCheck<T>(
                private val previousValueProperty: KMutableProperty1<PreviousValues, T?>,
                private val newValue: T,
                private val description: String
            ) : ValueCheckBase() {
                override fun useNewValue() {
                    aapsLogger.debug(LTag.WEAR, "$description is now $newValue")
                    previousValueProperty.set(previousValues, newValue)
                }

                override fun checkValueAndUpdateIfChanged(): Boolean {
                    val previousValue = previousValueProperty.get(previousValues)

                    return if (previousValue != newValue) {
                        previousValue?.let {
                            aapsLogger.debug(LTag.WEAR, "$description changed from $it to $newValue")
                        } ?: {
                            aapsLogger.debug(LTag.WEAR, "$description is now $newValue")
                        }
                        previousValueProperty.set(previousValues, newValue)
                        true
                    } else {
                        aapsLogger.debug(LTag.WEAR, "$description did not change")
                        false
                    }
                }
            }
            fun checkForValueChanges(vararg checks: ValueCheckBase): Boolean {
                if (doFullUpdate) {
                    // Do not bother performing checks if doFullUpdate is true.
                    // Just use any potentially present new value directly.
                    checks.forEach { check ->
                        check.useNewValue()
                    }
                    return true
                }

                // If doFullUpdate is false, actually check if the value
                // changed. Only update if it did, and set doSendMessage
                // to true to communicate that change to the smartwatch.

                var atLeastOneValueChanged = false

                checks.forEach { check ->
                    if (check.checkValueAndUpdateIfChanged())
                        atLeastOneValueChanged = true
                }

                if (atLeastOneValueChanged)
                    doSendMessage = true

                return atLeastOneValueChanged
            }

            // In case of a full update, always send out a message.
            if (doFullUpdate)
                doSendMessage = true

            aapsLogger.debug(LTag.WEAR, "=== Begin new BG data message ===")

            checkForValueChanges(ValueCheck(PreviousValues::unit, unit, "Glucose unit"))

            ////// Message generation //////

            // Set up the buffer (messageBytes) and write the header byte.

            val initialByte = (BG_DATA_FORMAT_VERSION_NUMBER or
                (if (unit == GlucoseUnit.MGDL) InitialByteFlags.UNIT_IS_MG_DL.value else 0)).toByte()

            val messageBytes = mutableListOf(initialByte)

            aapsLogger.debug(LTag.WEAR, "Full update: $doFullUpdate  " +
                             "event type: ${event::class.simpleName}  cur time: $currentTime")

            // Next, compare the event against a series of events. Each event
            // is associated with a certain data block in the binary format.
            // the event matches a given event type, process the associated
            // data block. doFullUpdate bypasses this, and always processes
            // that data block.

            // EventTempBasalChange

            // TODO: What events announce a base basal change?
            if ((event is EventTempBasalChange) || doFullUpdate) {
                val profileBasalRate = profile.getBasal(currentTime)
                // Converting a relative TBR to an absolute one,
                // since the smartwatch expects absolute TBRs.
                val tbrRate = processedTbrEbData.getTempBasalIncludingConvertedExtended(currentTime)?.let {
                    if (it.isAbsolute)
                        it.rate
                    else
                        profileBasalRate * it.rate / 100
                } ?: profileBasalRate

                aapsLogger.debug("Profile basal rate: $profileBasalRate  TBR rate: $tbrRate")

                val profileBasalRateAsShort = profileBasalRate.toInsulinQuantity()
                val tbrRateAsShort = tbrRate.toInsulinQuantity()

                if (checkForValueChanges(
                    ValueCheck(PreviousValues::profileBasalRateAsShort, profileBasalRateAsShort, "Profile basal rate (fixed point integer)"),
                    ValueCheck(PreviousValues::tbrRateAsShort, tbrRateAsShort, "TBR rate (fixed point integer)"),
                )) {
                    messageBytes.add(BlockID.BASAL_RATE.index.toByte())
                    messageBytes.addNumeric(profileBasalRateAsShort)
                    messageBytes.addNumeric(tbrRateAsShort)
                }
            }

            // EventTreatmentChange and EventAutosensCalculationFinished

            if ((event is EventTreatmentChange) || (event is EventAutosensCalculationFinished) || (event is EventUpdateOverviewIobCob) || doFullUpdate) {
                val basalIOB = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round().basaliob
                val bolusIOB = iobCobCalculator.calculateIobFromBolus().round().iob

                val cobInfo = iobCobCalculator.getCobInfo("AsteroidOS COB")
                val currentCarbs = cobInfo.displayCob?.toInt() ?: 0
                val futureCarbs = cobInfo.futureCarbs.toInt()

                // TODO: Currently, it seems that AndroidAPS has no notion of "unknown IOB".
                // Initially, IOB is just 0 instead. Should this be removed, or could this
                // "unknown IOB" semantic be added to AndroidAPS in the future?
                val hasIOB = true
                val hasCOB = (cobInfo.displayCob != null)

                aapsLogger.debug(
                    LTag.WEAR,
                    "Has IOB / COB: $hasIOB / $hasCOB  " +
                    "basal / bolus IOB: $basalIOB / $bolusIOB  " +
                    "current / future carbs: $currentCarbs / $futureCarbs")

                val (basalIOBAsShort, bolusIOBAsShort) = if (hasIOB)
                    Pair(basalIOB.toInsulinQuantity(), bolusIOB.toInsulinQuantity())
                else
                    Pair(Double.NaN.toInsulinQuantity(), Double.NaN.toInsulinQuantity())

                val (currentCarbAsShort, futureCarbsAsShort) = if (hasCOB)
                    Pair(currentCarbs.toShort(), futureCarbs.toShort())
                else
                    Pair(UNKNOWN_INTEGER_QUANTITY.toShort(), UNKNOWN_INTEGER_QUANTITY.toShort())

                if (checkForValueChanges(
                    ValueCheck(PreviousValues::basalIOBAsShort, basalIOBAsShort, "Basal IOB (fixed point integer)"),
                    ValueCheck(PreviousValues::bolusIOBAsShort, bolusIOBAsShort, "Bolus IOB (fixed point integer)"),
                    ValueCheck(PreviousValues::currentCarbAsShort, currentCarbAsShort, "Current carbs"),
                    ValueCheck(PreviousValues::futureCarbsAsShort, futureCarbsAsShort, "Future carbs"),
                )) {
                    messageBytes.add(BlockID.IOB_COB.index.toByte())
                    messageBytes.addNumeric(basalIOBAsShort)
                    messageBytes.addNumeric(bolusIOBAsShort)
                    messageBytes.addNumeric(currentCarbAsShort)
                    messageBytes.addNumeric(futureCarbsAsShort)
                }
            }

            // EventRunningModeChange

            if ((event is EventRunningModeChange) || doFullUpdate) {
                // The timestamp is needed in seconds, not milliseconds, hence the division by 1000.
                // Millisecond precision is not needed for the display.
                val lastLoopRunTimestamp = loop.lastRun?.let { it.lastAPSRun / 1000 } ?: UNKNOWN_TIMESTAMP

                val loopState = when (loop.runningMode) {
                    RM.Mode.OPEN_LOOP         -> AOSLoopState.OPEN
                    RM.Mode.CLOSED_LOOP       -> AOSLoopState.CLOSED
                    RM.Mode.CLOSED_LOOP_LGS   -> AOSLoopState.LGS
                    RM.Mode.DISABLED_LOOP     -> AOSLoopState.DISABLED
                    RM.Mode.SUPER_BOLUS       -> AOSLoopState.PAUSED
                    RM.Mode.DISCONNECTED_PUMP -> AOSLoopState.DISCONNECTED
                    RM.Mode.SUSPENDED_BY_PUMP -> AOSLoopState.PAUSED
                    RM.Mode.SUSPENDED_BY_USER -> AOSLoopState.PAUSED
                    RM.Mode.SUSPENDED_BY_DST  -> AOSLoopState.PAUSED
                    else -> AOSLoopState.UNKNOWN
                }
                val loopStateIndex = loopState.index

                aapsLogger.debug(LTag.WEAR, "Last loop run timestamp: $lastLoopRunTimestamp  loop state index: $loopStateIndex")

                if (checkForValueChanges(
                        ValueCheck(PreviousValues::loopState, loopState, "Loop state"),
                        ValueCheck(PreviousValues::lastLoopRunTimestamp, lastLoopRunTimestamp, "Last loop run timestamp"),
                    )) {
                    val blockIDByte = (BlockID.LOOP_STATUS.index or (loopStateIndex shl 4)).toByte()
                    messageBytes.add(blockIDByte)
                    messageBytes.addNumeric(lastLoopRunTimestamp)
                }
            }

            // EventBucketedDataCreated

            if ((event is EventBucketedDataCreated) || doFullUpdate) {
                val aapsTrendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)
                val aosTrendArrow = when (aapsTrendArrow) {
                    null,
                    TrendArrow.NONE      -> AOSTrendArrow.NONE
                    TrendArrow.TRIPLE_UP -> AOSTrendArrow.TRIPLE_UP
                    TrendArrow.DOUBLE_UP -> AOSTrendArrow.DOUBLE_UP
                    TrendArrow.SINGLE_UP -> AOSTrendArrow.SINGLE_UP
                    TrendArrow.FORTY_FIVE_UP -> AOSTrendArrow.FORTY_FIVE_UP
                    TrendArrow.FLAT -> AOSTrendArrow.FLAT
                    TrendArrow.FORTY_FIVE_DOWN -> AOSTrendArrow.FORTY_FIVE_DOWN
                    TrendArrow.SINGLE_DOWN -> AOSTrendArrow.SINGLE_DOWN
                    TrendArrow.DOUBLE_DOWN -> AOSTrendArrow.DOUBLE_DOWN
                    TrendArrow.TRIPLE_DOWN -> AOSTrendArrow.TRIPLE_DOWN
                }

                val lastBG = lastBgData.lastBg()
                val currentBG = lastBG?.let {
                    when (unit) {
                        GlucoseUnit.MGDL -> it.recalculated.roundToInt().toDouble()
                        GlucoseUnit.MMOL -> it.recalculated * Constants.MGDL_TO_MMOLL
                    }
                } ?: Double.NaN

                val glucoseStatus = glucoseStatusProvider.glucoseStatusData
                val bgDelta = glucoseStatus?.delta ?: Double.NaN

                val bgStatusTimestamp = lastBG?.timestamp ?: UNKNOWN_TIMESTAMP

                aapsLogger.debug(LTag.WEAR, "Current BG: $currentBG  BG delta: $bgDelta  BG status timestamp: $bgStatusTimestamp")

                val currentBGAsShort = currentBG.toGlucoseQuantity(unit)
                val bgDeltaAsShort = bgDelta.toGlucoseQuantity(unit)

                if (checkForValueChanges(
                        ValueCheck(PreviousValues::aosTrendArrow, aosTrendArrow, "Trend arrow"),
                        ValueCheck(PreviousValues::currentBGAsShort, currentBGAsShort, "Current BG (fixed point integer)"),
                        ValueCheck(PreviousValues::bgDeltaAsShort, bgDeltaAsShort, "BG delta (fixed point integer)"),
                        ValueCheck(PreviousValues::bgStatusTimestamp, bgStatusTimestamp, "BG status timestamp"),
                )) {
                    // First, add information about the current glucose value, along
                    // with the delta, the trend arrow, and the BG status timestamp.

                    val bgStatusBlockIDByte = (BlockID.BG_STATUS.index or (aosTrendArrow.index shl 4)).toByte()
                    messageBytes.add(bgStatusBlockIDByte)
                    messageBytes.addNumeric(currentBGAsShort)
                    messageBytes.addNumeric(bgDeltaAsShort)
                    // The timestamp is needed in seconds, not milliseconds, hence the division by 1000.
                    // Millisecond precision is not needed for the display.
                    messageBytes.addNumeric(bgStatusTimestamp / 1000)

                    // Next, add the BG time series.

                    // Extract all data points whose timestamp is not older than the
                    // timeSeriesTimespan. This yields the "original" time series,
                    // that is, the time series within that time span that directly
                    // comes from AndroidAPS, unnormalized, and not reduced. The
                    // result is hard-limited to no more than 127 data points, since
                    // the transmission to AsteroidOS cannot handle more than that.
                    assert(timeSeriesTimespan > 0)
                    val timeSeriesTimespanInMs = timeSeriesTimespan.toLong() * 3600 * 1000
                    val timeSeriesStartTimestamp = currentTime - timeSeriesTimespanInMs
                    val originalTimeSeries = iobCobCalculator.ads.getBucketedDataTableCopy()?.filter {
                        (it.timestamp > timeSeriesStartTimestamp) && !it.filledGap
                    }?.sortedBy { it.timestamp }?.let { it.subList(maxOf(0, it.size - 127), it.size) } ?: listOf()

                    aapsLogger.debug(LTag.WEAR, "Original num time series data points: ${originalTimeSeries.size}")

                    if (originalTimeSeries.isNotEmpty()) {
                        // Normalize the timestamps and glucose values in the 0.0-1.0
                        // range. This is necessary for the followup reduction.
                        val normalizedTimeSeries = originalTimeSeries.map { glucoseValue ->
                            val normalizedTimestamp = ((glucoseValue.timestamp - timeSeriesStartTimestamp).toDouble() / timeSeriesTimespanInMs).coerceIn(0.0, 1.0)
                            val normalizedValue = (glucoseValue.recalculated / maxBgValue).coerceIn(0.0, 1.0)
                            NormalizedDataPoint(normalizedTimestamp, normalizedValue)
                        }

                        // Reduce the time series to minimize the amount of data that
                        // is to be transmitted via BLE (whose bandwidth is limited).
                        // This reduction downsamples the time series, removing bits
                        // that are deemed unimportant enough to skip.
                        val reducedTimeSeries = when (_timeSeriesVisualizationType) {
                            TimeSeriesVisualizationType.DOTS ->
                                normalizedTimeSeries.reduceToMaxNumDataPointsForDots(aapsLogger, (6 * 60 * 1000).toDouble() / timeSeriesTimespanInMs)
                            TimeSeriesVisualizationType.LINES ->
                                normalizedTimeSeries.reduceToMaxNumDataPointsForLines()
                        }

                        val useLines = (_timeSeriesVisualizationType == TimeSeriesVisualizationType.LINES)

                        aapsLogger.debug(
                            LTag.WEAR,
                            "Produced ${reducedTimeSeries.size} data points out of ${originalTimeSeries.size} " +
                            "original one(s); will be visualized as ${if (useLines) "lines" else "dots"}"
                        )

                        // Now write the reduced time series into the message data block.
                        // First, write the number of data points. (The MSB of the  length
                        // byte is not part of the length value; it instead encodes whether
                        // the watchface shall visualize the time series using dots or lines).
                        // Then, write the timestamp and glucose value of each normalized data
                        // point. Scale these values from the 0.0-1.0 to the 0-255 range, since
                        // these values need to fit in a byte each. (The 0-255 range may seem
                        // very limited, but it is sufficient on a smartwatch, since its screen
                        // is not big, and time series graphs usually are not shown fullscreen.)

                        // The format limits the max number to 127, and any amount higher than
                        // that won't fit in the 7 LSB of the length byte that is written here
                        // prior to the timestamp and glucose values.
                        assert(reducedTimeSeries.size <= 127)
                        messageBytes.add((reducedTimeSeries.size or (if (useLines) 0x80 else 0x00)).toByte())
                        // TODO: Instead of doing (x*255.0), wouldn't min(x*256.0, 255.0) be better?
                        // If so, also apply that change to qmlbgview, and document this calculation in the BG format spec
                        reducedTimeSeries.forEach { dataPoint ->
                            messageBytes.add((dataPoint.timestamp * 255.0).toInt().toByte())
                            messageBytes.add((dataPoint.glucose * 255.0).toInt().toByte())
                        }
                    } else {
                        // If the original time series contains no data points, just
                        // write an amount of 0 data points into the message data block.
                        messageBytes.add(0.toByte())
                    }
                }
            }

            // Low/high BG threshold (announced by EventPreferenceChange, which anyway
            // sets doFullUpdate to true)

            if (doFullUpdate) {
                val lowBGThreshold = preferences.get(UnitDoubleKey.OverviewLowMark)
                val highBGThreshold = preferences.get(UnitDoubleKey.OverviewHighMark)
                val normalizedLowBGTimeSeriesThreshold = (lowBGThreshold * 255.0 / maxBgValue).toInt().coerceIn(0, 255)
                val normalizedHighBGTimeSeriesThreshold = (highBGThreshold * 255.0 / maxBgValue).toInt().coerceIn(0, 255)

                aapsLogger.debug(LTag.WEAR, "Low / high BG threshold: $lowBGThreshold / $highBGThreshold")
                aapsLogger.debug(LTag.WEAR, "Normalized low / high BG threshold for time series: $normalizedLowBGTimeSeriesThreshold / $normalizedHighBGTimeSeriesThreshold")

                val bgStatusBlockIDByte = BlockID.LOW_HIGH_BG_THRESHOLD.index.toByte()
                messageBytes.add(bgStatusBlockIDByte)
                messageBytes.addNumeric(lowBGThreshold.toGlucoseQuantity(unit))
                messageBytes.addNumeric(highBGThreshold.toGlucoseQuantity(unit))
                messageBytes.add(normalizedLowBGTimeSeriesThreshold.toByte())
                messageBytes.add(normalizedHighBGTimeSeriesThreshold.toByte())
            }

            ////// Message transmission //////

            if (doSendMessage) {
                sendDataToSyncApp(messageBytes.toByteArray())
                initialFullBGDataSent = true
            } else {
                aapsLogger.debug(LTag.WEAR, "Not sending out message since no values changed")
            }

            aapsLogger.debug(LTag.WEAR, "=== End new BG data message ===")
            success = true
        } finally {
            if (!success)
                aapsLogger.debug(LTag.WEAR, "=== New BG data message not finished due to exception ===")
        }
    }

    private fun clearWatchface() {
        var success = false
        try {
            // Clear the watchface by sending a full dataset with all values
            // set to "unknown" (and the time series to 0 data points).

            aapsLogger.debug(LTag.WEAR, "=== Begin new BG data message for clearing watchface ===")

            val messageBytes = mutableListOf(
                (BG_DATA_FORMAT_VERSION_NUMBER or InitialByteFlags.UNIT_IS_MG_DL.value).toByte()
            )

            messageBytes.add(BlockID.BASAL_RATE.index.toByte())
            messageBytes.addNumeric(Double.NaN.toInsulinQuantity())
            messageBytes.addNumeric(Double.NaN.toInsulinQuantity())

            messageBytes.add(BlockID.IOB_COB.index.toByte())
            messageBytes.addNumeric(Double.NaN.toInsulinQuantity())
            messageBytes.addNumeric(Double.NaN.toInsulinQuantity())
            messageBytes.addNumeric(UNKNOWN_INTEGER_QUANTITY.toShort())
            messageBytes.addNumeric(UNKNOWN_INTEGER_QUANTITY.toShort())

            messageBytes.add((BlockID.LOOP_STATUS.index or (AOSLoopState.UNKNOWN.index shl 4)).toByte())
            messageBytes.addNumeric(0.toLong())

            messageBytes.add((BlockID.BG_STATUS.index or (AOSTrendArrow.NONE.index shl 4)).toByte())
            messageBytes.addNumeric(Double.NaN.toGlucoseQuantity(GlucoseUnit.MGDL))
            messageBytes.addNumeric(Double.NaN.toGlucoseQuantity(GlucoseUnit.MGDL))
            messageBytes.addNumeric(0.toLong())
            messageBytes.add(0.toByte())

            sendDataToSyncApp(messageBytes.toByteArray())

            initialFullBGDataSent = false

            // Reset the previous values to ensure no comparisons
            // against stale previous values happen.
            previousValues = PreviousValues()

            aapsLogger.debug(LTag.WEAR, "=== End new BG data message for clearing watchface ===")
            success = true
        } finally {
            if (!success)
                aapsLogger.debug(LTag.WEAR, "=== New BG data message for clearing watchface not finished due to exception ===")
        }
    }

    private fun sendDataToSyncApp(messageBody: ByteArray) {
        // The external app message protocol expects three Extra fields in the intent:
        //
        // - "sender" : identifies the sender of the intent
        // - "destination" : identifies the destination inside the smartwatch
        //   (this is used by the watch for routing the message to a destination
        //   D-Bus object)
        // - "rawPayload" : the actual message payload, in raw (= binary) form
        //   as opposed to a text (= string) form (which would instead use
        //   a "textPayload" extra)

        aapsLogger.debug(LTag.WEAR, "Sending BG data to AsteroidOS with ${messageBody.size} byte(s)")

        val intent = Intent(INTENT_ACTION_EXTAPPMESSAGE_PUSH)
        intent.flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES
        intent.setPackage(SYNC_APP_PACKAGE_NAME)
        intent.putExtra("sender", "AndroidAPS")
        intent.putExtra("destination", "BGDataReceiver")
        intent.putExtra("rawPayload", messageBody)

        context.sendBroadcast(intent)
    }
}
