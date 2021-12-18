package info.nightscout.androidaps.plugins.pump.combov2

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.data.DetailedBolusInfo
import info.nightscout.androidaps.data.PumpEnactResult
import info.nightscout.androidaps.events.EventPumpStatusChanged
import info.nightscout.androidaps.interfaces.CommandQueue
import info.nightscout.androidaps.interfaces.Constraint
import info.nightscout.androidaps.interfaces.Constraints
import info.nightscout.androidaps.interfaces.PluginDescription
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.interfaces.Profile
import info.nightscout.androidaps.interfaces.Pump
import info.nightscout.androidaps.interfaces.PumpDescription
import info.nightscout.androidaps.interfaces.PumpPluginBase
import info.nightscout.androidaps.interfaces.PumpSync
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.plugins.common.ManufacturerType
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification
import info.nightscout.androidaps.plugins.general.overview.events.EventOverviewBolusProgress
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification
import info.nightscout.androidaps.plugins.pump.common.defs.PumpType
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.ToastUtils
import info.nightscout.androidaps.utils.alertDialogs.OKDialog
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.comboctl.android.AndroidBluetoothInterface
import info.nightscout.comboctl.base.ApplicationLayerIO
import info.nightscout.comboctl.base.BasicProgressStage
import info.nightscout.comboctl.base.BluetoothException
import info.nightscout.comboctl.base.ComboException
import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.PairingPIN
import info.nightscout.comboctl.base.ReservoirState
import info.nightscout.comboctl.main.PumpCommandDispatcher
import info.nightscout.comboctl.base.BluetoothAddress as ComboCtlBluetoothAddress
import info.nightscout.comboctl.base.Logger as ComboCtlLogger
import info.nightscout.comboctl.base.PumpIO as ComboCtlPumpIO
import info.nightscout.comboctl.main.Pump as ComboCtlPump
import info.nightscout.comboctl.main.PumpManager
import info.nightscout.comboctl.main.RTCommandProgressStage
import info.nightscout.comboctl.parser.AlertScreenContent
import info.nightscout.comboctl.parser.AlertScreenException
import info.nightscout.comboctl.parser.BatteryState
import info.nightscout.shared.logging.AAPSLogger
import info.nightscout.shared.logging.LTag
import info.nightscout.shared.sharedPreferences.SP
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.IllegalStateException
import kotlin.math.absoluteValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.Seconds
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max

@Singleton
class ComboV2Plugin @Inject constructor (
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    commandQueue: CommandQueue,
    private val context: Context,
    private val rxBus: RxBus,
    private val constraintChecker: ConstraintChecker,
    private val sp: SP,
    private val pumpSync: PumpSync,
    private val dateUtil: DateUtil
) :
    PumpPluginBase(
        PluginDescription()
            .mainType(PluginType.PUMP)
            .fragmentClass(ComboV2Fragment::class.java.name)
            .pluginIcon(R.drawable.ic_combov2)
            .pluginName(R.string.combov2_plugin_name)
            .shortName(R.string.combov2_plugin_shortname)
            .description(R.string.combov2_plugin_description)
            .preferencesId(R.xml.pref_combov2),
        injector,
        aapsLogger,
        rh,
        commandQueue
    ),
    Pump,
    Constraints {

    // Coroutine scope and the associated job. All coroutines
    // start in this plugin are part of this scope.
    private val pumpCoroutineMainJob = SupervisorJob()
    private val pumpCoroutineScope = CoroutineScope(Dispatchers.Default + pumpCoroutineMainJob)

    private val _pumpDescription = PumpDescription()

    private val pumpStateStore = SPPumpStateStore(sp)

    // These are initialized in onStart() and torn down in onStop().
    private var bluetoothInterface: AndroidBluetoothInterface? = null
    private var pumpManager: PumpManager? = null

    // These are initialized in connect() and torn down in disconnect().
    private var pump: ComboCtlPump? = null
    private var pumpCommandDispatcher: PumpCommandDispatcher? = null
    private var pumpUIFlowsDeferred: Deferred<Unit>? = null

    // States for the Pump interface and for the UI.
    // pumpStatus is null when the pump status isn't known yet.
    private var pumpStatus: PumpCommandDispatcher.PumpStatus? = null
    private var lastConnectionTimestamp = 0L
    private var lastBolus: LastBolus? = null

    // The exception that happened during the last command execution.
    private var lastPumpException: Exception? = null
    private var lastComboAlert: AlertScreenContent? = null

    // Set to true if a disconnect request came in while the driver
    // was in the CONNECTING, CHECKING_PUMP, or EXECUTING_COMMAND
    // state (in other words, while isBusy() was returning true).
    private var disconnectRequestPending = false

    // The current driver state. We use a StateFlow here to
    // allow other components to react to state changes.
    private val mutableDriverStateFlow = MutableStateFlow(DriverState.NOT_INITIALIZED)

    // The basal profile that is set to be the pump's current profile.
    // If the pump's actual basal profile deviates from this, it is
    // overwritten. This check is performed in checkBasalProfile().
    // In setNewBasalProfile(), this value is changed.
    private var activeBasalProfile: BasalProfile? = null

    /*** Public functions and base class & interface overrides ***/

    data class LastBolus(val bolusAmount: Int, val timestamp: Long)

    enum class DriverState {
        // Initial state when the driver is created.
        NOT_INITIALIZED,
        // Driver is disconnected from the pump, or no pump
        // is currently paired. In onStart(), the driver state
        // changes from NOT_INITIALIZED to this.
        DISCONNECTED,
        // Driver is currently connecting to the pump. isBusy()
        // will return true in this state.
        CONNECTING,
        // Driver is running checks on the pump, like verifying
        // that the basal rate is OK, checking for any bolus
        // and TBR activity that AAPS doesn't know about etc.
        // isBusy() will return true in this state.
        CHECKING_PUMP,
        // Driver is connected and ready to execute commands.
        READY,
        // Driver is connected, but pump is suspended and
        // cannot currently execute commands.
        SUSPENDED,
        // Driver is currently executing a command.
        // isBusy() will return true in this state.
        EXECUTING_COMMAND
    }

    val driverStateFlow = mutableDriverStateFlow.asStateFlow()

    class SettingPumpDatetimeException: ComboException("Could not set pump datetime")

    init {
        ComboCtlLogger.backend = AAPSComboCtlLogger(aapsLogger)
        updateComboCtlLogLevel()

        _pumpDescription.fillFor(PumpType.ACCU_CHEK_COMBO)
    }

    override fun onStart() {
        aapsLogger.debug(LTag.PUMP, "Creating bluetooth interface")
        bluetoothInterface = AndroidBluetoothInterface(context)

        aapsLogger.debug(LTag.PUMP, "Setting up bluetooth interface")
        bluetoothInterface!!.setup()

        val unpairUnknownPumps = sp.getBoolean(rh.gs(R.string.key_combov2_unpair_unknown_combos), true)
        aapsLogger.debug(LTag.PUMP, "Setting up pump manager (unpairUnknownPumps: $unpairUnknownPumps)")
        pumpManager = PumpManager(bluetoothInterface!!, pumpStateStore)
        pumpManager!!.setup(unpairUnknownPumps) {
            mutablePairedStateUIFlow.value = false
        }

        // UI flows that must have defined values right
        // at start are initialized here.

        // The paired state UI flow is special in that it is also
        // used as the backing store for the isPaired() function,
        // so setting up that UI state flow equals updating that
        // paired state.
        val paired = pumpManager!!.getPairedPumpAddresses().isNotEmpty()
        mutablePairedStateUIFlow.value = paired

        setDriverState(DriverState.DISCONNECTED)
    }

    override fun onStop() {
        pumpCoroutineScope.cancel()
        pumpManager = null
        bluetoothInterface?.teardown()
        bluetoothInterface = null

        setDriverState(DriverState.NOT_INITIALIZED)

        super.onStop()
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)

        val unpairPumpPreference: Preference? = preferenceFragment.findPreference(rh.gs(R.string.key_combov2_unpair_pump))
        unpairPumpPreference?.setOnPreferenceClickListener {
            preferenceFragment.context?.let { ctx ->
                OKDialog.showConfirmation(ctx, "Confirm pump unpairing", "Do you really want to unpair the pump?", ok = Runnable {
                    unpair()
                })
            }
            false
        }

        // Setup coroutine to enable/disable the pair and unpair
        // preferences depending on the pairing state.
        preferenceFragment.run {
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    val pairPref: Preference? = findPreference(rh.gs(R.string.key_combov2_pair_with_pump))
                    val unpairPref: Preference? = findPreference(rh.gs(R.string.key_combov2_unpair_pump))

                    pairedStateUIFlow
                        .onEach { isPaired ->
                            pairPref?.isEnabled = !isPaired
                            unpairPref?.isEnabled = isPaired
                        }
                        .launchIn(this)
                }
            }
        }
    }

    override fun isInitialized(): Boolean =
        isPaired() && (driverStateFlow.value != DriverState.NOT_INITIALIZED)

    override fun isSuspended(): Boolean = (driverStateFlow.value == DriverState.SUSPENDED)

    override fun isBusy(): Boolean =
        when (driverStateFlow.value) {
            DriverState.CONNECTING,
            DriverState.CHECKING_PUMP,
            DriverState.SUSPENDED,
            DriverState.EXECUTING_COMMAND -> true
            else -> false
        }

    override fun isConnected(): Boolean =
        when (driverStateFlow.value) {
            DriverState.CHECKING_PUMP,
            DriverState.READY,
            DriverState.SUSPENDED,
            DriverState.EXECUTING_COMMAND -> true
            else -> false
        }

    override fun isConnecting(): Boolean = (driverStateFlow.value == DriverState.CONNECTING)

    // There is no corresponding indicator for this
    // in Combo connections, so just return false
    override fun isHandshakeInProgress() = false

    override fun connect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "Connecting to Combo; reason: $reason")

        if (isConnecting()) {
            aapsLogger.debug(LTag.PUMP, "Already connecting")
            return
        }

        if (isConnected()) {
            aapsLogger.debug(LTag.PUMP, "Already connected")
            return
        }

        if (!isPaired()) {
            aapsLogger.debug(LTag.PUMP, "Cannot connect since no Combo has been paired")
            return
        }

        // It makes no sense to reach this location with pump
        // being non-null due to the checks above.
        assert(pump == null)

        // Set this to null here, _not_ in disconnect(), since we may
        // also have to call disconnect() in error situations, and then,
        // we do want to preserve the last pump exception.
        lastPumpException = null
        lastComboAlert = null

        pumpStatus = null

        val bluetoothAddress = when (val address = getBluetoothAddress()) {
            null -> {
                aapsLogger.error(LTag.PUMP, "No Bluetooth address stored - pump state store may be corrupted")
                unpairDueToPumpDataError()
                return
            }
            else -> address
        }

        try {
            runBlocking {
                pump = pumpManager?.acquirePump(bluetoothAddress)
            }

            if (pump == null) {
                aapsLogger.error(LTag.PUMP, "Could not get pump instance - pump state store may be corrupted")
                unpairDueToPumpDataError()
                return
            }

            pumpCommandDispatcher = PumpCommandDispatcher(pump!!)

            mutableBluetoothAddressUIFlow.value = bluetoothAddress.toString()
            mutableSerialNumberUIFlow.value = pumpManager!!.getPumpID(bluetoothAddress)

            // Erase any display frame that may be left over from a previous connection.
            mutableDisplayFrameUIFlow.resetReplayCache()

            // Set up the flows that will notify the UI about changes.
            // We run these in a separate coroutine scope to be able
            // to cancel all of them with one call in disconnect(),
            // thus taking advantage of structured concurrency.
            pumpUIFlowsDeferred = pumpCoroutineScope.async {
                try {
                    coroutineScope {
                        launch {
                            pump!!.connectProgressFlow
                                .onEach { progressReport ->
                                    val description = when (val progStage = progressReport.stage) {
                                        is BasicProgressStage.EstablishingBtConnection   ->
                                            rh.gs(
                                                R.string.combov2_establishing_bt_connection,
                                                progStage.currentAttemptNr,
                                                progStage.totalNumAttempts
                                            )
                                        BasicProgressStage.PerformingConnectionHandshake -> rh.gs(R.string.combov2_pairing_performing_handshake)
                                        else                                             -> ""
                                    }
                                    mutableCurrentActivityUIFlow.value = CurrentActivityInfo(
                                        description,
                                        progressReport.overallProgress
                                    )
                                }
                                .collect()
                        }

                        launch {
                            pumpCommandDispatcher!!.setDateTimeProgressFlow
                                .onEach { progressReport ->
                                    val description = when (progressReport.stage) {
                                        RTCommandProgressStage.SettingDateTimeHour,
                                        RTCommandProgressStage.SettingDateTimeMinute -> rh.gs(R.string.combov2_setting_current_pump_time)
                                        RTCommandProgressStage.SettingDateTimeYear,
                                        RTCommandProgressStage.SettingDateTimeMonth,
                                        RTCommandProgressStage.SettingDateTimeDay    -> rh.gs(R.string.combov2_setting_current_pump_date)
                                        else                                         -> ""
                                    }
                                    mutableCurrentActivityUIFlow.value = CurrentActivityInfo(
                                        description,
                                        progressReport.overallProgress
                                    )
                                }
                                .collect()
                        }

                        launch {
                            pumpCommandDispatcher!!.basalProfileAccessFlow
                                .onEach { progressReport ->
                                    when (val stage = progressReport.stage) {
                                        is RTCommandProgressStage.BasalProfileAccess -> {
                                            val description = rh.gs(
                                                R.string.combov2_accessing_basal_profile,
                                                stage.numSetFactors
                                            )
                                            mutableCurrentActivityUIFlow.value = CurrentActivityInfo(
                                                description,
                                                progressReport.overallProgress
                                            )
                                        }
                                        BasicProgressStage.Finished -> CurrentActivityInfo("", 1.0)
                                        else -> CurrentActivityInfo("", 0.0)
                                    }
                                }
                                .collect()
                        }

                        launch {
                            pumpCommandDispatcher!!.bolusDeliveryProgressFlow
                                .onEach { progressReport ->
                                    when (val stage = progressReport.stage) {
                                        is RTCommandProgressStage.DeliveringBolus -> {
                                            val description = rh.gs(
                                                R.string.combov2_delivering_bolus,
                                                stage.deliveredAmount.cctlBolusToIU(),
                                                stage.totalAmount.cctlBolusToIU()
                                            )
                                            mutableCurrentActivityUIFlow.value = CurrentActivityInfo(
                                                description,
                                                progressReport.overallProgress
                                            )
                                        }
                                        BasicProgressStage.Finished -> CurrentActivityInfo("", 1.0)
                                        else -> CurrentActivityInfo("", 0.0)
                                    }
                                }
                                .collect()
                        }

                        launch {
                            pump!!.displayFrameFlow
                                .onEach { displayFrame ->
                                    mutableDisplayFrameUIFlow.emit(displayFrame)
                                }
                                .collect()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    aapsLogger.error(LTag.PUMP, "Exception thrown in UI flows coroutine scope: $e")
                    throw e
                }
            }

            // Update the log level here in case the user changed it.
            updateComboCtlLogLevel()

            ////
            // The actual connect procedure begins here.
            ////

            disconnectRequestPending = false
            setDriverState(DriverState.CONNECTING)

            val result = pump?.connectAsync(pumpCoroutineScope, initialMode = ComboCtlPumpIO.Mode.COMMAND)

            // Connection setup runs in a background coroutine.
            // Set up another one that waits for the first coroutine
            // to finish so that it can run the on-connect checks.
            // If disconnect() is called, await() throws an exception,
            // and this coroutine is aborted.
            pumpCoroutineScope.launch {
                try {
                    result?.let { connectAsyncDeferred ->
                        // Suspend until connectAsync() is done.
                        connectAsyncDeferred.await()

                        runOnConnectChecks()

                        syncDriverState()
                    }

                    // Command queue may have requested a disconnect
                    // while we were connecting. Run it now.
                    executePendingDisconnect()
                } catch (e: CancellationException) {
                    // Re-throw to mark this coroutine as cancelled.
                    throw e
                } catch (e: Exception) {
                    // In case of a connection failure, just disconnect. The command
                    // queue will retry after a while. Repeated failed attempts will
                    // eventually trigger a "pump unreachable" error message.
                    aapsLogger.error(LTag.PUMP, "Exception while connecting: $e")
                    ToastUtils.showToastInUiThread(context, rh.gs(R.string.combov2_could_not_connect))
                    disconnectInternal(forceDisconnect = true)
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Connection failure: $e")
            ToastUtils.showToastInUiThread(context, rh.gs(R.string.combov2_could_not_connect))
            disconnectInternal(forceDisconnect = true)
        }
    }

    override fun disconnect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "Disconnecting from Combo; reason: $reason")
        disconnectInternal(forceDisconnect = false)
    }

    // This is called when (a) the AAPS watchdog is about to toggle
    // Bluetooth (if permission is given by the user) and (b) when
    // the command queue is being emptied. In both cases, the
    // connection attempt must be stopped immediately, which is why
    // forceDisconnect is set to true.
    override fun stopConnecting() = disconnectInternal(forceDisconnect = true)

    // Marked as synchronized since this may get called by a finishing
    // connect operation and by the command queue at the same time.
    @Synchronized private fun disconnectInternal(forceDisconnect: Boolean) {
        // Sometimes, the CommandQueue may decide to call disconnect while the
        // driver is still busy with something, for example because some checks
        // are being performed. Ignore disconnect requests in that case, unless
        // the forceDisconnect flag is set.
        if (!forceDisconnect && isBusy()) {
            disconnectRequestPending = true
            aapsLogger.debug(LTag.PUMP, "Ignoring disconnect request since driver is currently busy")
            return
        }

        if (isDisconnected()) {
            aapsLogger.debug(LTag.PUMP, "Already disconnected")
            return
        }

        // It makes no sense to reach this location with pump
        // being null due to the checks above.
        assert(pump != null)

        // Run these operations in a coroutine to be able to wait
        // until the disconnect really completes and the UI flows
        // are all cancelled & their coroutines finished. Otherwise
        // we can end up with race conditions because the coroutines
        // are still ongoing in the background.
        runBlocking {
            pump?.disconnect()
            pumpUIFlowsDeferred?.cancelAndJoin()
            getBluetoothAddress()?.let { pumpManager?.releasePump(it) }
        }

        updateLastConnectionTimestamp()

        pumpUIFlowsDeferred = null
        pump = null

        disconnectRequestPending = false

        setDriverState(DriverState.DISCONNECTED)
    }

    override fun getPumpStatus(reason: String) {
        aapsLogger.debug(LTag.PUMP, "Getting pump status; reason: $reason")

        lastComboAlert = null

        // Retrieve the pump status. Do this with the
        // executeCommand() helper to let it also check
        // whether or not the pump is in an OK state.
        runBlocking {
            try {
                executeCommand {
                    pumpStatus = pumpCommandDispatcher!!.readPumpStatus()

                    mutableBatteryStateUIFlow.value = pumpStatus!!.batteryState
                    mutableReservoirLevelUIFlow.value = ReservoirLevel(
                        pumpStatus!!.reservoirState,
                        pumpStatus!!.availableUnitsInReservoir
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
            }
        }

        // After retrieving the pump state, we may also have
        // to update the driver state accordingly. The pump
        // may have been suspended, and is now running, and
        // vice versa.
        if (((driverStateFlow.value == DriverState.SUSPENDED) && pumpStatus!!.running) ||
            ((driverStateFlow.value != DriverState.SUSPENDED) && !pumpStatus!!.running))
            syncDriverState()
    }

    override fun setNewBasalProfile(profile: Profile): PumpEnactResult {
        if (!isInitialized()) {
            aapsLogger.error(LTag.PUMP, "Cannot set profile since driver is not initialized")

            val notification = Notification(
                Notification.PROFILE_NOT_SET_NOT_INITIALIZED,
                rh.gs(R.string.pumpNotInitializedProfileNotSet),
                Notification.URGENT
            )
            rxBus.send(EventNewNotification(notification))

            return PumpEnactResult(injector).apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.pumpNotInitializedProfileNotSet)
            }
        }

        rxBus.send(EventDismissNotification(Notification.PROFILE_NOT_SET_NOT_INITIALIZED))
        rxBus.send(EventDismissNotification(Notification.FAILED_UPDATE_PROFILE))

        val pumpEnactResult = PumpEnactResult(injector)

        val requestedBasalProfile = BasalProfile(profile)

        runBlocking {
            try {
                executeCommand {
                    // If there is no active profile known, get it first
                    // from the pump. Getting the profile is typically faster
                    // than setting it, especially since due to the way
                    // setBasalProfile() works with its carrying-over-last-factor
                    // behavior, it can lead to unnecessary adjustments.
                    // (Using carrying-over-last-factor is overall faster
                    // than not using it when actually writing a new profile
                    // though, which is why it is not simply disabled. See
                    // PumpCommandDispatcher.setBasalProfile for details.
                    if (activeBasalProfile == null) {
                        activeBasalProfile = BasalProfile(pumpCommandDispatcher!!.getBasalProfile())
                        aapsLogger.debug(LTag.PUMP, "Read basal profile: $activeBasalProfile")
                    }

                    if (activeBasalProfile == requestedBasalProfile) {
                        // Nothing to do - profile already set.
                        pumpEnactResult.apply {
                            success = true
                            enacted = false
                        }
                    }

                    pumpCommandDispatcher!!.setBasalProfile(requestedBasalProfile.factors)

                    activeBasalProfile = requestedBasalProfile
                }

                pumpEnactResult.apply {
                    success = true
                    enacted = true
                }
            } catch (e: CancellationException) {
                // Cancellation is not an error, but it also means
                // that the profile update was not enacted.
                pumpEnactResult.apply {
                    success = true
                    enacted = false
                }
                throw e
            } catch (e: Exception) {
                aapsLogger.error("Exception thrown during basal profile update: $e")

                val notification = Notification(
                    Notification.FAILED_UPDATE_PROFILE,
                    rh.gs(R.string.failedupdatebasalprofile),
                    Notification.URGENT
                )
                rxBus.send(EventNewNotification(notification))

                pumpEnactResult.apply {
                    success = false
                    enacted = false
                    comment = rh.gs(R.string.failedupdatebasalprofile)
                }
            }
        }

        return pumpEnactResult
    }

    override fun isThisProfileSet(profile: Profile): Boolean {
        if (!isInitialized())
            return true

        return (activeBasalProfile == BasalProfile(profile))
    }

    override fun lastDataTime(): Long = lastConnectionTimestamp

    override val baseBasalRate: Double
        get() {
            val currentHour = DateTime().hourOfDay().get()
            return activeBasalProfile?.getFactor(currentHour)?.cctlBasalToIU() ?: 0.0
        }

    override val reservoirLevel: Double
        get() = pumpStatus?.availableUnitsInReservoir?.toDouble() ?: 0.0

    override val batteryLevel: Int
        // The Combo does not provide any numeric battery
        // level, so we have to use some reasonable values
        // based on the indicated battery state.
        get() = when (pumpStatus?.batteryState) {
            null,
            BatteryState.NO_BATTERY -> 5
            BatteryState.LOW_BATTERY -> 25
            BatteryState.FULL_BATTERY -> 100
        }

    private var bolusJob: Job? = null

    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        detailedBolusInfo.insulin = constraintChecker
            .applyBolusConstraints(Constraint(detailedBolusInfo.insulin))
            .value()

        // Carbs are not allowed because the Combo does not record carbs.
        // This is defined in the ACCU_CHEK_COMBO PumpType enum's
        // pumpCapability field, so AndroidAPS is informed about this
        // lack of carb storage capability. We therefore do not expect
        // nonzero carbs here.
        // (Also, a zero insulin value makes no sense when bolusing.)
        require((detailedBolusInfo.insulin > 0) && (detailedBolusInfo.carbs <= 0.0)) { detailedBolusInfo.toString() }

        getPumpStatus("Checking before treatment delivery")
        if (lastPumpException != null)
            return createFailurePumpEnactResult(R.string.error) // TODO: More specific comment
        // Pump status must be known after a successful getPumpStatus() run.
        assert(pumpStatus != null)

        if (driverStateFlow.value == DriverState.SUSPENDED)
            return createFailurePumpEnactResult(R.string.combov2_cannot_deliver_pump_suspended)

        if ((pumpStatus!!.availableUnitsInReservoir.cctlBasalToIU() - 0.5) < detailedBolusInfo.insulin)
            return createFailurePumpEnactResult(R.string.combov2_insufficient_insulin_in_reservoir)

        val requestedBolusAmount = (detailedBolusInfo.insulin * 10).toInt()

        val pumpEnactResult = PumpEnactResult(injector)
        pumpEnactResult.success = false

        runBlocking {
            var checkPostBolusHistory = true
            var bolusWasCancelled = false
            var bolusDeliveryFailed = false
            try {
                executeCommand {
                    val historyDelta = pumpCommandDispatcher!!
                        .fetchHistory(setOf(PumpCommandDispatcher.HistoryPart.HISTORY_DELTA))
                        .historyDeltaEvents

                    // We abort if any unaccounted bolus delivery is detected.
                    // In such a case, this bolus may have come to be enacted
                    // under false assumptions. To be safe, abort this bolus.
                    // (The unaccounted bolus deliveries are inserted into the
                    // AndroidAPS database by processHistoryDelta().)
                    if (processHistoryDelta(historyDelta) == ProcessHistoryDeltaResult.UNACCOUNTED_BOLUS_DETECTED) {
                        pumpEnactResult.apply {
                            success = false
                            enacted = false
                            comment = rh.gs(R.string.combov2_unaccounted_bolus_detected)
                        }
                        checkPostBolusHistory = false
                        return@executeCommand
                    }

                    // Set up initial bolus progress along with details that are invariant.
                    // FIXME: EventOverviewBolusProgress is a singleton purely for
                    // historical reasons and could be updated to be a regular
                    // class. So far, this hasn't been done, so we must use it
                    // like a singleton, at least for me.
                    EventOverviewBolusProgress.t = EventOverviewBolusProgress.Treatment(
                        insulin = 0.0,
                        carbs = 0,
                        isSMB = (detailedBolusInfo.bolusType == DetailedBolusInfo.BolusType.SMB)
                    )

                    val bolusProgressJob = launch {
                        pumpCommandDispatcher!!.bolusDeliveryProgressFlow
                            .collect { progressReport ->
                                val bolusingEvent = EventOverviewBolusProgress
                                bolusingEvent.percent = (progressReport.overallProgress * 100.0).toInt()
                                when (val stage = progressReport.stage) {
                                    is RTCommandProgressStage.DeliveringBolus ->
                                        bolusingEvent.status = rh.gs(
                                            R.string.combov2_delivering_bolus,
                                            stage.deliveredAmount.cctlBolusToIU(),
                                            stage.totalAmount.cctlBolusToIU()
                                        )
                                    else -> Unit
                                }

                                rxBus.send(bolusingEvent)
                            }
                    }

                    try {
                        pumpCommandDispatcher!!.deliverBolus(
                            bolusAmount = requestedBolusAmount
                        )

                        val bolusingEvent = EventOverviewBolusProgress
                        bolusingEvent.status = rh.gs(R.string.combov2_bolus_successfuly_delivered)
                        bolusingEvent.percent = 100
                        rxBus.send(bolusingEvent)

                        pumpEnactResult.apply {
                            success = true
                            comment = rh.gs(R.string.combov2_bolus_successfuly_delivered)
                        }

                        // Even though deliverBolus() finished properly at this
                        // point (that is - no exception was thrown), we do not
                        // just assume that a bolus was given. The check in the
                        // finally block further below which reads the history
                        // delta is still applied to catch remote cases where
                        // no error was detected during bolus delivery but the
                        // Combo registered that something other than the expected
                        // bolus happened.
                    } catch (e: CancellationException) {
                        val bolusingEvent = EventOverviewBolusProgress
                        bolusingEvent.status = rh.gs(R.string.combov2_bolus_cancelled)
                        bolusingEvent.percent = 100
                        rxBus.send(bolusingEvent)
                        throw e
                    } catch (e: Exception) {
                        pumpEnactResult.enacted(false).comment(R.string.combov2_bolus_delivery_failed)
                        val bolusingEvent = EventOverviewBolusProgress
                        bolusingEvent.status = rh.gs(R.string.combov2_bolus_delivery_failed)
                        bolusingEvent.percent = 100
                        rxBus.send(bolusingEvent)
                        throw e
                    } finally {
                        bolusProgressJob.cancelAndJoin()
                    }
                }
            } catch (e: CancellationException) {
                // Cancellation is not an error, but it also means
                // that the profile update was not enacted.
                pumpEnactResult.apply {
                    comment = rh.gs(R.string.combov2_bolus_cancelled)
                }
                bolusWasCancelled = true
                // Rethrowing to finish coroutine cancellation.
                throw e
            } catch (e: Exception) {
                aapsLogger.error("Exception thrown during bolus delivery: $e")
                pumpEnactResult.apply {
                    comment = rh.gs(R.string.combov2_bolus_delivery_failed)
                }
                bolusDeliveryFailed = true
            } finally {
                if (checkPostBolusHistory) {
                    // We are looking for the bolus that we administered.
                    val bolusEvent = try {
                        // Use a NonCancellable context in case the coroutine was
                        // cancelled earlier to circumvent the promt cancellation
                        // guarantee, which in this case would case incorrect behavior
                        // (it would immediately cancel the fetchHistory() call).
                        val historyDelta = withContext(NonCancellable) {
                            pumpCommandDispatcher!!
                                .fetchHistory(setOf(PumpCommandDispatcher.HistoryPart.HISTORY_DELTA))
                                .historyDeltaEvents
                        }

                        // Find the first StandardBolusInfused entry whose "manual"
                        // field is false. StandardBolusInfused tells us that a
                        // bolus was infused, and how much insulin was infused.
                        // manual == false is specific to a bolus that wasn't
                        // administered via the Combo's remote terminal mode, but
                        // instead was delivered through the command mode (which
                        // we did above).
                        val bolusEvent = historyDelta.firstOrNull {
                            (it.detail as? ApplicationLayerIO.CMDHistoryEventDetail.StandardBolusInfused)?.let {
                                it.manual == false
                            } ?: false
                        }

                        // Process the history delta. This adds the bolus entry
                        // in the delta into the AndroidAPS database. Run this
                        // even if bolusEvent is empty in order to process everything
                        // that happened since the bolus delivery (and including the
                        // bolus delivery - if it took place).
                        // TODO: Can the pumpSync functions that are called inside
                        // processHistoryDelta() throw exceptions?
                        processHistoryDelta(
                            historyDelta,
                            deliveredBolusID = bolusEvent?.eventCounter,
                            deliveredBolusType = detailedBolusInfo.bolusType
                        )

                        bolusEvent
                    } catch (e: NoSuchElementException) {
                        // Swallow exceptions. We are already closing down the bolus
                        // delivery. Rethrowin that exception is of no use at this point.
                        // In case something did happen and the delta contains data but
                        // history delta delivery failed, the next connection attempt
                        // will rectify this when it checks the history.
                        aapsLogger.error(
                            LTag.PUMP,
                            "Swallowing exception that was caught while retrieving post-bolus history delta: $e"
                        )

                        null
                    }

                    if (bolusEvent == null) {
                        // No bolus was delivered.
                        pumpEnactResult.enacted = false
                    } else {
                        val bolusDetail =
                            bolusEvent.detail as ApplicationLayerIO.CMDHistoryEventDetail.StandardBolusInfused
                        val actualBolusAmount = bolusDetail.bolusAmount

                        lastBolus = LastBolus(
                            bolusAmount = actualBolusAmount,
                            timestamp = bolusEvent.timestamp.toJodaDateTime().millis
                        )

                        mutableLastBolusUIFlow.value = lastBolus


                        pumpEnactResult.apply {
                            // Catch corner case where there _is_ a bolus entry
                            // but its bolus amount is 0 IU.
                            enacted = (actualBolusAmount > 0)
                            // We consider the bolus command execution to be a
                            // success only if the expected amount was delivered.
                            // A partial bolus is not a success. (A partial bolus
                            // due to cancellation is, however.)
                            success = !bolusDeliveryFailed &&
                                      (actualBolusAmount > 0) &&
                                      ((requestedBolusAmount == actualBolusAmount) || bolusWasCancelled)
                        }
                    }
                }
            }
        }

        return pumpEnactResult
    }

    override fun stopBolusDelivering() {
        runBlocking {
            bolusJob?.cancelAndJoin()
            bolusJob = null
        }
    }

    // XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX continue here

    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        TODO("Not yet implemented")
    }

    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        TODO("Not yet implemented")
    }

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        TODO("Not yet implemented")
    }

    // It is currently not known how to program an extended bolus into the Combo.
    // Until that is reverse engineered, inform callers that we can't handle this.
    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult = createFailurePumpEnactResult(R.string.combov2_extended_bolus_not_supported)
    override fun cancelExtendedBolus(): PumpEnactResult = createFailurePumpEnactResult(R.string.combov2_extended_bolus_not_supported)

    override fun getJSONStatus(profile: Profile, profileName: String, version: String): JSONObject {
        // TODO: Should we call getPumpStatus first?
        if (!isInitialized() || (pumpStatus == null))
            return JSONObject()

        val pumpJson = JSONObject()

        try {
            pumpJson.apply {
                put("clock", dateUtil.toISOString(dateUtil.now()))
                put("status", JSONObject().apply {
                    put("status", if (pumpStatus!!.running) "running" else "suspended")
                    put("timestamp", dateUtil.toISOString(lastConnectionTimestamp))
                })
                put("battery", JSONObject().apply {
                    put("percent", batteryLevel)
                })
                put("reservoir", pumpStatus!!.availableUnitsInReservoir)
                put("extended", JSONObject().apply {
                    put("Version", version)
                    lastBolus?.let {
                        put("LastBolus", dateUtil.dateAndTimeString(it.timestamp))
                        put("LastBolusAmount", it.bolusAmount.cctlBolusToIU())
                    }
                    // TODO: TBR
                    put("BaseBasalRate", baseBasalRate)
                    // TODO: DanaRSPlugin surrounds the ActiveProfile line
                    // with a try-catch block - why? Also, it doesn't use
                    // the profileName argument - why?
                    put("ActiveProfile", profileName)
                    when (val alert = lastComboAlert) {
                        is AlertScreenContent.Warning ->
                            put("WarningCode", alert.code)
                        is AlertScreenContent.Error ->
                            put("ErrorCode", alert.code)
                        else -> Unit
                    }
                })
            }
        } catch (e: JSONException) {
            aapsLogger.error(LTag.PUMP, "Unhandled JSON exception", e)
        }

        return pumpJson
    }

    override fun manufacturer() = ManufacturerType.Roche

    override fun model() = PumpType.ACCU_CHEK_COMBO

    override fun serialNumber(): String {
        val bluetoothAddress = getBluetoothAddress()
        return if ((bluetoothAddress != null) && (pumpManager != null))
            pumpManager!!.getPumpID(bluetoothAddress)
        else
            rh.gs(R.string.combov2_not_paired)
    }

    override val pumpDescription: PumpDescription
        get() = _pumpDescription

    override fun shortStatus(veryShort: Boolean): String {
        val currentTimestamp = System.currentTimeMillis()
        var ret = ""
        if (lastConnectionTimestamp != 0L) {
            val minutesPassed = max((currentTimestamp - lastConnectionTimestamp) / 60 / 1000, 0)
            ret += "LastConn: $minutesPassed\n"
        }
        lastComboAlert?.let {
            when (it) {
                is AlertScreenContent.Warning -> ret += "Alert: W${it.code}"
                is AlertScreenContent.Error -> ret += "Alert: E${it.code}"
                else -> Unit
            }
        }
        pumpStatus?.let {
            ret += "Reserv: ${it.availableUnitsInReservoir} U\n"
            val batteryStateDesc = when (it.batteryState) {
                BatteryState.NO_BATTERY -> "EMPTY"
                BatteryState.LOW_BATTERY -> "LOW"
                BatteryState.FULL_BATTERY -> "FULL"
            }
            ret += "Batt: $batteryStateDesc\n"
            ret += "Status: ${if (it.running) "Running" else "Suspended"}"
        }

        // TODO: TDD, TBR - if veryShort is false
        if (!veryShort) {
            lastBolus?.let {
                val minutesPassed = max((currentTimestamp - it.timestamp) / 60 / 1000, 0)
                val bolusDosageStr = "%.1f".format(it.bolusAmount.cctlBolusToIU())
                ret += "LastBolus: $minutesPassed minAgo ($bolusDosageStr U)"
            }
        }

        return ret
    }

    override val isFakingTempsByExtendedBoluses = false

    override fun loadTDDs(): PumpEnactResult {
        val pumpEnactResult = PumpEnactResult(injector)

        runBlocking {
            try {
                // Map key = timestamp; value = TDD
                val tddMap = mutableMapOf<Long, Int>()

                executeCommand {
                    val tddHistory = pumpCommandDispatcher!!.fetchHistory(setOf(PumpCommandDispatcher.HistoryPart.TDD_HISTORY))

                    tddHistory.tddEvents
                        .filter { it.totalDailyAmount >= 1 }
                        .forEach { tddEvent ->
                            val timestamp = tddEvent.date.toJodaDateTime().millis
                            tddMap[timestamp] = (tddMap[timestamp] ?: 0) + tddEvent.totalDailyAmount
                        }
                }

                for (tddEntry in tddMap) {
                    val timestamp = tddEntry.key
                    val totalDailyAmount = tddEntry.value

                    pumpSync.createOrUpdateTotalDailyDose(
                        timestamp,
                        bolusAmount = 0.0,
                        basalAmount = 0.0,
                        totalAmount = totalDailyAmount.cctlBasalToIU(),
                        pumpId = null,
                        pumpType = PumpType.ACCU_CHEK_COMBO,
                        pumpSerial = serialNumber()
                    )
                }

                pumpEnactResult.apply {
                    success = true
                    enacted = true
                }
            } catch (e: CancellationException) {
                pumpEnactResult.apply {
                    success = true
                    enacted = false
                    comment = rh.gs(R.string.combov2_load_tdds_cancelled)
                }
                throw e
            } catch (e: Exception) {
                aapsLogger.error("Exception thrown during TDD retrieval: $e")

                pumpEnactResult.apply {
                    success = false
                    enacted = false
                    comment = rh.gs(R.string.combov2_retrieving_tdds_failed)
                }
            }
        }

        return pumpEnactResult
    }

    // The pump's current date and time are set through ComboCtl commands
    // to program the pump to the current UTC time. This is done in the
    // connect checks (see runOnConnectChecks()).
    override fun canHandleDST() = true

    /*** Pairing API ***/

    fun getPairingProgressFlow() =
        pumpManager?.pairingProgressFlow ?: throw IllegalStateException("Attempting access uninitialized pump manager")

    fun resetPairingProgress() = pumpManager?.resetPairingProgress()

    private val mutablePreviousPairingAttemptFailedFlow = MutableStateFlow(false)
    val previousPairingAttemptFailedFlow = mutablePreviousPairingAttemptFailedFlow.asStateFlow()

    private var pairingJob: Job? = null
    private var pairingPINChannel: Channel<PairingPIN>? = null

    fun startPairing() {
        val discoveryDuration = sp.getInt(R.string.key_combov2_discovery_duration, 300)

        val newPINChannel = Channel<PairingPIN>(capacity = Channel.RENDEZVOUS)
        pairingPINChannel = newPINChannel

        mutablePreviousPairingAttemptFailedFlow.value = false

        // Update the log level here in case the user changed it.
        updateComboCtlLogLevel()

        pairingJob = pumpCoroutineScope.async {
            try {
                pumpManager?.pairWithNewPump(discoveryDuration) { newPumpAddress, previousAttemptFailed ->
                    aapsLogger.info(
                        LTag.PUMP,
                        "New pairing PIN request from Combo pump with Bluetooth " +
                            "address $newPumpAddress (previous attempt failed: $previousAttemptFailed)"
                    )
                    mutablePreviousPairingAttemptFailedFlow.value = previousAttemptFailed
                    newPINChannel.receive()
                } ?: throw IllegalStateException("Attempting to access uninitialized pump manager")

                mutablePairedStateUIFlow.value = true

                // Notify AndroidAPS that this is a new pump and that
                // the history that is associated with any previously
                // paired pump is to be discarded.
                pumpSync.connectNewPump()
            } finally {
                pairingJob = null
                pairingPINChannel?.close()
                pairingPINChannel = null
            }
        }
    }

    fun cancelPairing() {
        runBlocking {
            aapsLogger.debug(LTag.PUMP, "Cancelling pairing")
            pairingJob?.cancelAndJoin()
            aapsLogger.debug(LTag.PUMP, "Pairing cancelled")
        }
    }

    suspend fun providePairingPIN(pairingPIN: PairingPIN) {
        try {
            pairingPINChannel?.send(pairingPIN)
        } catch (ignored: ClosedSendChannelException) {
        }
    }

    fun unpair() {
        val bluetoothAddress = getBluetoothAddress() ?: return

        disconnectInternal(forceDisconnect = true)

        runBlocking {
            try {
                val pump = pumpManager?.acquirePump(bluetoothAddress) ?: return@runBlocking
                pump.unpair()
                pumpManager?.releasePump(bluetoothAddress)
            } catch (ignored: ComboException) {
            } catch (ignored: BluetoothException) {
            }
        }

        // Reset the UI flows that are associated with the pump
        // that just got unpaired to prevent the UI from showing
        // information about that now-unpaired pump anymore.
        mutableCurrentActivityUIFlow.value = noCurrentActivity()
        mutableLastConnectionTimestampUIFlow.value = null
        mutableBatteryStateUIFlow.value = null
        mutableReservoirLevelUIFlow.value = null
        mutableSerialNumberUIFlow.value = ""
        mutableBluetoothAddressUIFlow.value = ""
    }

    /*** User interface flows ***/

    // "UI flows" are hot flows that are meant to be used for showing
    // information about the pump and its current state on the UI.
    // These are kept in the actual plugin class to make sure they
    // are always available, even if no pump is paired (which means
    // that the "pump" variable is set to null and thus its flows
    // are inaccessible).
    //
    // A few UI flows are internally also used for other checks, such
    // as pairedStateUIFlow (which is used internally to verify whether
    // or not the pump is paired).
    //
    // Some UI flows are nullable and have a null initial state to
    // indicate to UIs that they haven't been filled with actual
    // state yet.

    // "Activity" is not to be confused with the Android Activity class.
    // An "activity" is something that a command does, for example
    // establishing a BT connection, or delivering a bolus, setting
    // a basal rate factor, reading the current pump datetime etc.
    data class CurrentActivityInfo(val description: String, val overallProgress: Double)
    private fun noCurrentActivity() = CurrentActivityInfo("", 0.0)
    private var mutableCurrentActivityUIFlow = MutableStateFlow(noCurrentActivity())
    val currentActivityUIFlow = mutableCurrentActivityUIFlow.asStateFlow()

    private var mutableLastConnectionTimestampUIFlow = MutableStateFlow<Long?>(null)
    val lastConnectionTimestampUIFlow = mutableLastConnectionTimestampUIFlow.asStateFlow()

    private var mutableBatteryStateUIFlow = MutableStateFlow<BatteryState?>(null)
    val batteryStateUIFlow = mutableBatteryStateUIFlow.asStateFlow()

    data class ReservoirLevel(val state: ReservoirState, val availableUnits: Int)
    private var mutableReservoirLevelUIFlow = MutableStateFlow<ReservoirLevel?>(null)
    val reservoirLevelUIFlow = mutableReservoirLevelUIFlow.asStateFlow()

    private var mutableLastBolusUIFlow = MutableStateFlow<LastBolus?>(null)
    val lastBolusUIFlow = mutableLastBolusUIFlow.asStateFlow()

    private var mutableSerialNumberUIFlow = MutableStateFlow("")
    val serialNumberUIFlow = mutableSerialNumberUIFlow.asStateFlow()

    private var mutableBluetoothAddressUIFlow = MutableStateFlow("")
    val bluetoothAddressUIFlow = mutableBluetoothAddressUIFlow.asStateFlow()

    private var mutablePairedStateUIFlow = MutableStateFlow(false)
    val pairedStateUIFlow = mutablePairedStateUIFlow.asStateFlow()

    // UI flow to show the current RT display frame on the UI. Unlike
    // the other UI flows, this is a SharedFlow, not a StateFlow,
    // since frames aren't "states", and StateFlow filters out duplicates
    // (which isn't useful in this case). The flow is configured such
    // that it never suspends; if its replay cache contains a frame already,
    // that older frame is overwritten. This makes sure the flow always
    // contains the current frame.
    private var mutableDisplayFrameUIFlow = MutableSharedFlow<DisplayFrame?>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val displayFrameUIFlow = mutableDisplayFrameUIFlow.asSharedFlow()


    /*** Misc private functions ***/

    private fun updateComboCtlLogLevel() {
        ComboCtlLogger.threshold =
            if (sp.getBoolean(R.string.key_combov2_verbose_logging, false)) LogLevel.VERBOSE else LogLevel.DEBUG
    }

    private fun setDriverState(newState: DriverState) {
        val oldState = mutableDriverStateFlow.value

        if (oldState == newState)
            return

        mutableDriverStateFlow.value = newState

        aapsLogger.info(LTag.PUMP, "Setting Combo driver state:  old: $oldState  new: $newState")

        // TODO: Is it OK to send CONNECTED twice? It can happen when changing from READY to SUSPENDED.
        when (newState) {
            DriverState.DISCONNECTED -> rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
            DriverState.CONNECTING -> rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTING))
            DriverState.READY,
            DriverState.SUSPENDED -> rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.CONNECTED))
            else -> Unit
        }
    }

    // Utility function to sync the driver state to the state of the Pump  instance.
    // Sometimes this needs to be used, sometimes the state needs to be set manually
    // with setDriverState(), which is why both functions exist.
    private fun syncDriverState() {
        if (pump == null) {
            setDriverState(DriverState.DISCONNECTED)
            return
        }

        setDriverState(
            when (pump!!.connectionState.value) {
                ComboCtlPump.ConnectionState.DISCONNECTED ->
                    DriverState.DISCONNECTED
                ComboCtlPump.ConnectionState.CONNECTING ->
                    DriverState.CONNECTING
                ComboCtlPump.ConnectionState.CONNECTED ->
                    // Pump is considered READY if running is true and if the pump status isn't known yet
                    if (pumpStatus?.running != false) DriverState.READY else DriverState.SUSPENDED
            }
        )
    }

    private fun executePendingDisconnect() {
        if (!disconnectRequestPending)
            return

        aapsLogger.debug(LTag.PUMP, "Executing pending disconnect request")
        disconnectInternal(forceDisconnect = true)
    }

    private fun unpairDueToPumpDataError() {
        disconnectInternal(forceDisconnect = true)
        val notification = Notification(
            id = Notification.PUMP_ERROR,
            date = dateUtil.now(),
            text = rh.gs(R.string.combov2_cannot_access_pump_data),
            level = Notification.URGENT,
            validTo = 0
        )
        rxBus.send(EventNewNotification(notification))
        unpair()
    }

    private enum class ProcessHistoryDeltaResult {
        NO_IRREGULARITIES,
        UNACCOUNTED_BOLUS_DETECTED
    }

    private fun processHistoryDelta(
        historyDelta: List<ApplicationLayerIO.CMDHistoryEvent>,
        deliveredBolusID: Long? = null,
        deliveredBolusType: DetailedBolusInfo.BolusType = DetailedBolusInfo.BolusType.NORMAL
    ): ProcessHistoryDeltaResult {
        var processResult = ProcessHistoryDeltaResult.NO_IRREGULARITIES

        for (entry in historyDelta) {
            aapsLogger.debug("Got entry in history delta: $entry")

            val id = entry.eventCounter
            // Timestamps in the history are stored as UTC because we set the pump's clock that way.
            val timestamp = entry.timestamp.toJodaDateTime().millis

            when (val detail = entry.detail) {
                is ApplicationLayerIO.CMDHistoryEventDetail.QuickBolusInfused -> {
                    pumpSync.syncBolusWithPumpId(
                        timestamp,
                        detail.bolusAmount.toDouble() / 10,
                        DetailedBolusInfo.BolusType.NORMAL,
                        id,
                        PumpType.ACCU_CHEK_COMBO,
                        serialNumber()
                    )
                    processResult = ProcessHistoryDeltaResult.UNACCOUNTED_BOLUS_DETECTED
                }
                is ApplicationLayerIO.CMDHistoryEventDetail.StandardBolusInfused -> {
                    pumpSync.syncBolusWithPumpId(
                        timestamp,
                        detail.bolusAmount.toDouble() / 10,
                        if (id == deliveredBolusID)
                            deliveredBolusType
                        else
                            DetailedBolusInfo.BolusType.NORMAL,
                        id,
                        PumpType.ACCU_CHEK_COMBO,
                        serialNumber()
                    )
                    if (id != deliveredBolusID)
                        processResult = ProcessHistoryDeltaResult.UNACCOUNTED_BOLUS_DETECTED
                }
                is ApplicationLayerIO.CMDHistoryEventDetail.ExtendedBolusStarted -> {
                    pumpSync.syncExtendedBolusWithPumpId(
                        timestamp,
                        detail.totalBolusAmount.toDouble() / 10,
                        detail.totalDurationMinutes.toLong() * 60 * 1000,
                        false,
                        id,
                        PumpType.ACCU_CHEK_COMBO,
                        serialNumber()
                    )
                    processResult = ProcessHistoryDeltaResult.UNACCOUNTED_BOLUS_DETECTED
                }
                is ApplicationLayerIO.CMDHistoryEventDetail.ExtendedBolusEnded -> {
                    pumpSync.syncStopExtendedBolusWithPumpId(
                        timestamp,
                        id,
                        PumpType.ACCU_CHEK_COMBO,
                        serialNumber()
                    )
                    processResult = ProcessHistoryDeltaResult.UNACCOUNTED_BOLUS_DETECTED
                }
                else -> Unit // TODO
            }
        }

        return processResult
    }

    /**
     * Checks to be performed after a (re)connect. (Not after each command!)
     *
     * This runs the following checks (in this order):
     *
     * 1. Check datetime and adjust it if necessary.
     * 2. Retrieve and check history delta. "Delta" means that this
     *    contains events that happened since the last history retrieval.
     *    See what events happened since then. In particular, check if
     *    any bolus was delivered, and if any extended/multiwave bolus
     *    started or was finished. Any standard bolus that isn't
     *    known to AndroidAPS is inserted into its database. If
     *    any extended/multiwave bolus activity is ongoing or did
     *    happen, inform AndroidAPS to deactivate the loop for a while.
     * 3. See if the current pump status include information about
     *    an ongoing TBR, and insert a TBR entry into AAPS if necessary.
     * 4. Set basal profile, overwriting any changes, to make sure
     *    the pump uses the same basal profile as AndroidAPS.
     *    NOTE: Only do this if the basal rate factor shown on screen
     *    diverges from the factor as specified in the current profile,
     *    or if the plugin got loaded (-> in onStart()).
     * 5. Insert 15 minute zero TBR if the pump is currently stopped.
     */
    private suspend fun runOnConnectChecks() {
        val stateToRestore = driverStateFlow.value

        setDriverState(DriverState.CHECKING_PUMP)

        checkDatetime()
        checkHistoryDelta()
        processOngoingTbr()
        checkBasalProfile(forceProfileOverwrite = false)
        addZeroTbrIfPumpSuspended()

        setDriverState(stateToRestore)
    }

    private suspend fun checkDatetime() {
        // Here, we retrieve the pump's current datetime, and check
        // how far away it is from the current datetime in UTC. If
        // it is too far away, the pump's datetime is updated. We
        // use UTC to sidestep problems with DST and locale changes
        // made by the user, since the Combo stores all timestamps
        // as localtime and it thus unaware of any timezone offsets.

        // Retry 3 times. This is because setting the date and time
        // may take a while (it is done through the remote terminal
        // mode). In extreme cases, it can take a few minutes. If
        // this happens, then the programmed time is a few minutes
        // behind the actual current time. To address this, try
        // again. Next time, setting the time finishes much faster,
        // since most parts of the current datetime will already
        // be at the current value (so for example, the hour value
        // is most likely not going to change, so the setDateTime()
        // code can immediately move past it). At the third attempt,
        // the timeDifference check below is not expected to find
        // a relevant time difference anymore. On the off chance
        // that it does, we abort by throwing an exception, since
        // there is something wrong (we can't set the datetime
        // correctly for some reason).
        var success = false

        for (attemptNr in 0 until 3) {
            val nowDatetime = DateTime(DateTimeZone.UTC)
            val pumpDatetime = pumpCommandDispatcher!!.getDateTime().toJodaDateTime()

            val timeDifference = Seconds.secondsBetween(pumpDatetime, nowDatetime)
            // TODO: Determine optimal threshold beyond which the pump's clock is considered to be out of sync
            // Perhaps make that threshold configurable? Does this make sense?
            if (timeDifference.seconds.absoluteValue >= 60) {
                aapsLogger.debug(
                    LTag.PUMP,
                    "Pump datetime ($pumpDatetime) is out of sync " +
                        "(difference ${timeDifference.seconds} second(s)); setting to current UTC time $nowDatetime"
                )

                pumpCommandDispatcher!!.setDateTime(nowDatetime.toComboCtlDateTime())
            } else {
                aapsLogger.debug("Pump datetime is in sync with the current UTC time")
                success = true
                break
            }
        }

        if (!success)
            throw SettingPumpDatetimeException()
    }

    private suspend fun checkHistoryDelta() {
        val historyDelta = pumpCommandDispatcher!!
            .fetchHistory(setOf(PumpCommandDispatcher.HistoryPart.HISTORY_DELTA))
            .historyDeltaEvents

        processHistoryDelta(historyDelta)
    }

    private fun processOngoingTbr() {
        // TODO
    }

    private suspend fun checkBasalProfile(forceProfileOverwrite: Boolean) {
        aapsLogger.debug(LTag.PUMP, "Checking basal profile; forceProfileOverwrite = $forceProfileOverwrite")

        // If no profile is set, we have nothing to check the pump's state against.
        if (activeBasalProfile == null)
            return

        val writeProfile = if (forceProfileOverwrite) {
            // forceProfileOverwrite == true means that we always
            // want to write the profile to the pump.
            true
        } else {
            // forceProfileOverwrite == false means that we only
            // want to write to the pump if a mismatch between the
            // expected current basal rate factor and the one the
            // pump is actually using is detected.

            val currentHour = DateTime().hourOfDay().get()

            // If the current basal rate factor could not be read, we can't perform a check.
            val currentBasalRateFactor = pumpCommandDispatcher!!.readCurrentBasalRateFactor() ?: return
            val expectedBasalRateFactor = activeBasalProfile!!.getFactor(currentHour)
            val factorsMismatch = (expectedBasalRateFactor != currentBasalRateFactor)

            if (factorsMismatch) {
                aapsLogger.debug(
                    LTag.PUMP,
                    "Factor mismatch detected at hour $currentHour: expected " +
                    "factor $expectedBasalRateFactor, got factor $currentBasalRateFactor"
                )
            }

            factorsMismatch
        }

        if (writeProfile) {
            aapsLogger.debug(LTag.PUMP, "Writing basal profile to pump")
            pumpCommandDispatcher!!.setBasalProfile(activeBasalProfile!!.factors)
        }
    }

    private fun addZeroTbrIfPumpSuspended() {
        // TODO
    }

    private fun isPaired() = pairedStateUIFlow.value

    // Utility function to make sure certain procedures are performed
    // after command execution. These are: Running post-connection
    // checks if the command dispatcher reconnected during execution;
    // recording any exception that occurred (unless it was a
    // CancellationException, which is not an error); disconnecting
    // from the pump if a disconnect command was pending.
    private suspend fun executeCommand(
        block: suspend CoroutineScope.() -> Unit
    ) {
        try {
            coroutineScope {
                block.invoke(this)
            }

            if (pumpCommandDispatcher!!.reconnectedDuringLastDispatch)
                runOnConnectChecks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AlertScreenException) {
            lastComboAlert = e.alertScreenContent
            lastPumpException = e

            notifyAboutComboAlert(e.alertScreenContent)

            throw e
        } catch (e: Exception) {
            lastPumpException = e
            throw e
        } finally {
            executePendingDisconnect()
        }
    }

    private fun updateLastConnectionTimestamp() {
        lastConnectionTimestamp = System.currentTimeMillis()
        mutableLastConnectionTimestampUIFlow.value = lastConnectionTimestamp
    }

    private fun getAlertDescription(alert: AlertScreenContent) =
        when (alert) {
            is AlertScreenContent.Warning -> {
                val desc = when (alert.code) {
                    4 -> rh.gs(R.string.combov2_warning_4)
                    10 -> rh.gs(R.string.combov2_warning_10)
                    else -> ""
                }

                "${rh.gs(R.string.combov2_warning)} W${alert.code}" +
                    if (desc.isEmpty()) "" else ": $desc"
            }

            is AlertScreenContent.Error -> {
                val desc = when (alert.code) {
                    1 -> rh.gs(R.string.combov2_error_1)
                    2 -> rh.gs(R.string.combov2_error_2)
                    4 -> rh.gs(R.string.combov2_error_4)
                    5 -> rh.gs(R.string.combov2_error_5)
                    6 -> rh.gs(R.string.combov2_error_6)
                    7 -> rh.gs(R.string.combov2_error_7)
                    8 -> rh.gs(R.string.combov2_error_8)
                    9 -> rh.gs(R.string.combov2_error_9)
                    10 -> rh.gs(R.string.combov2_error_10)
                    11 -> rh.gs(R.string.combov2_error_11)
                    else -> ""
                }

                "${rh.gs(R.string.combov2_error)} E${alert.code}" +
                    if (desc.isEmpty()) "" else ": $desc"
            }

            else -> rh.gs(R.string.combov2_unrecognized_alert)
        }

    private fun notifyAboutComboAlert(alert: AlertScreenContent) {
        val notification = Notification(
            Notification.COMBO_PUMP_ALARM,
            text = "${rh.gs(R.string.combov2_combo_alert)}: ${getAlertDescription(alert)}",
            level = if (alert is AlertScreenContent.Warning) Notification.NORMAL else Notification.URGENT
        )
        rxBus.send(EventNewNotification(notification))
    }

    private fun createFailurePumpEnactResult(comment: Int) =
        PumpEnactResult(injector)
            .success(false)
            .enacted(false)
            .comment(comment)

    private fun getBluetoothAddress(): ComboCtlBluetoothAddress? =
        pumpManager!!.getPairedPumpAddresses().firstOrNull()

    private fun isDisconnected() =
        when (driverStateFlow.value) {
            DriverState.NOT_INITIALIZED,
            DriverState.DISCONNECTED -> true
            else -> false
        }
}
