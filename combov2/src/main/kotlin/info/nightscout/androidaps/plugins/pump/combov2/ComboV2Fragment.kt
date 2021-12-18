package info.nightscout.androidaps.plugins.pump.combov2

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dagger.android.support.DaggerFragment
import info.nightscout.androidaps.interfaces.CommandQueue
import info.nightscout.androidaps.plugins.pump.combov2.databinding.Combov2FragmentBinding
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.comboctl.base.NullDisplayFrame
import info.nightscout.comboctl.base.ReservoirState
import info.nightscout.comboctl.parser.BatteryState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.joda.time.DateTime
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max

class ComboV2Fragment : DaggerFragment() {
    @Inject lateinit var combov2Plugin: ComboV2Plugin
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var commandQueue: CommandQueue

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding: Combov2FragmentBinding = DataBindingUtil.inflate(
            inflater, R.layout.combov2_fragment, container, false)
        val view = binding.root

        binding.combov2RefreshButton.setOnClickListener {
            binding.combov2RefreshButton.isEnabled = false
            commandQueue.readStatus(rh.gs(R.string.user_request), null)
        }

        lifecycleScope.launch {
            // Start all of these flows with repeatOnLifecycle()
            // which will automatically cancel the flows when
            // the lifecycle reaches the STOPPED stage and
            // re-runs the lambda (as a suspended function)
            // when the lifecycle reaches the STARTED stage.
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combov2Plugin.pairedStateUIFlow
                    .onEach { isPaired ->
                        binding.combov2FragmentUnpairedUi.visibility = if (isPaired) View.GONE else View.VISIBLE
                        binding.combov2FragmentMainUi.visibility = if (isPaired) View.VISIBLE else View.GONE
                    }
                    .launchIn(this)

                combov2Plugin.driverStateFlow
                    .onEach { connectionState ->
                        val text = when (connectionState) {
                            ComboV2Plugin.DriverState.NOT_INITIALIZED -> rh.gs(R.string.combov2_not_initialized)
                            ComboV2Plugin.DriverState.DISCONNECTED -> rh.gs(R.string.disconnected)
                            ComboV2Plugin.DriverState.CONNECTING -> rh.gs(R.string.connecting)
                            ComboV2Plugin.DriverState.CHECKING_PUMP -> rh.gs(R.string.combov2_checking_pump)
                            ComboV2Plugin.DriverState.READY -> rh.gs(R.string.combov2_ready)
                            ComboV2Plugin.DriverState.SUSPENDED -> rh.gs(R.string.combov2_suspended)
                            ComboV2Plugin.DriverState.EXECUTING_COMMAND -> rh.gs(R.string.combov2_executing_command)
                        }
                        binding.combov2DriverState.text = text

                        binding.combov2RefreshButton.isEnabled = when (connectionState) {
                            ComboV2Plugin.DriverState.DISCONNECTED,
                            ComboV2Plugin.DriverState.READY,
                            ComboV2Plugin.DriverState.SUSPENDED -> true
                            else -> false
                        }
                    }
                    .launchIn(this)

                combov2Plugin.lastConnectionTimestampUIFlow
                    .onEach { lastConnectionTimestamp ->
                        updateLastConnectionField(lastConnectionTimestamp, binding)
                    }
                    .launchIn(this)

                // This "Activity" is not to be confused with Android's "Activity" class.
                combov2Plugin.currentActivityUIFlow
                    .onEach { currentActivity ->
                        binding.combov2CurrentActivityDesc.text = currentActivity.description
                        binding.combov2CurrentActivityProgress.progress = (currentActivity.overallProgress * 100.0).toInt()
                    }
                    .launchIn(this)

                combov2Plugin.batteryStateUIFlow
                    .onEach { batteryState ->
                        when (batteryState) {
                            null -> binding.combov2Battery.text = ""
                            BatteryState.NO_BATTERY -> {
                                binding.combov2Battery.text = "{fa-battery-empty}"
                                binding.combov2LastConnection.setTextColor(Color.RED)
                            }
                            BatteryState.LOW_BATTERY -> {
                                binding.combov2Battery.text = "{fa-battery-quarter}"
                                binding.combov2LastConnection.setTextColor(Color.YELLOW)
                            }
                            BatteryState.FULL_BATTERY -> {
                                binding.combov2Battery.text = "{fa-battery-full}"
                                binding.combov2LastConnection.setTextColor(Color.WHITE)
                            }
                        }
                    }
                    .launchIn(this)

                combov2Plugin.reservoirLevelUIFlow
                    .onEach { reservoirLevel ->
                        binding.combov2Reservoir.text = if (reservoirLevel != null)
                            "${reservoirLevel.availableUnits} ${rh.gs(R.string.insulin_unit_shortname)}"
                        else
                            ""

                        binding.combov2Reservoir.setTextColor(
                            when (reservoirLevel?.state) {
                                null -> Color.WHITE
                                ReservoirState.EMPTY -> Color.RED
                                ReservoirState.LOW -> Color.YELLOW
                                ReservoirState.FULL -> Color.WHITE
                            }
                        )
                    }
                    .launchIn(this)

                combov2Plugin.lastBolusUIFlow
                    .onEach { lastBolusTimestamp ->
                        updateLastBolusField(lastBolusTimestamp, binding)
                    }
                    .launchIn(this)

                combov2Plugin.serialNumberUIFlow
                    .onEach { serialNumber ->
                        binding.combov2PumpId.text = serialNumber
                    }
                    .launchIn(this)

                combov2Plugin.bluetoothAddressUIFlow
                    .onEach { bluetoothAddress ->
                        binding.combov2BluetoothAddress.text = bluetoothAddress.uppercase(Locale.ROOT)
                    }
                    .launchIn(this)

                combov2Plugin.displayFrameUIFlow
                    .onEach { displayFrame ->
                        binding.combov2RtDisplayFrame.displayFrame = displayFrame ?: NullDisplayFrame
                    }
                    .launchIn(this)

                launch {
                    while (true) {
                        delay(30 * 1000L) // Wait for 30 seconds
                        updateLastConnectionField(combov2Plugin.lastConnectionTimestampUIFlow.value, binding)
                        updateLastBolusField(combov2Plugin.lastBolusUIFlow.value, binding)
                    }
                }

                launch {
                    while (true) {
                        binding.combov2BaseBasalRate.text =
                            rh.gs(R.string.pump_basebasalrate, combov2Plugin.baseBasalRate)
                        val currentMinute = DateTime().minuteOfHour().get()

                        // Calculate how many minutes need to pass until we
                        // reach the next hour and thus the next basal profile
                        // factor becomes active. That way, the amount of UI
                        // refreshes is minimized.
                        // We cap the max waiting period to 58 minutes instead
                        // of 60 to allow for a small tolerance range for cases
                        // when this loop iterates exactly when the current hour
                        // is about to turn.
                        val minutesUntilNextFactor = max((58 - currentMinute), 0)
                        delay(minutesUntilNextFactor * 60 * 1000L)
                    }
                }
            }
        }

        return view
    }

    private fun updateLastConnectionField(lastConnectionTimestamp: Long?, binding: Combov2FragmentBinding) {
        val currentTimestamp = System.currentTimeMillis()

        // If the last connection is >= 30 minutes ago,
        // we display a different message, one that
        // warns the user that a long time passed
        when (val secondsPassed = lastConnectionTimestamp?.let { (currentTimestamp - it) / 1000 }) {
            null ->
                binding.combov2LastConnection.text = ""

            in 0..60 -> {
                binding.combov2LastConnection.text = rh.gs(R.string.combov2_less_than_one_minute_ago)
                binding.combov2LastConnection.setTextColor(Color.WHITE)
            }

            in 60..(30 * 60) -> {
                binding.combov2LastConnection.text = rh.gs(info.nightscout.androidaps.core.R.string.minago, secondsPassed / 60)
                binding.combov2LastConnection.setTextColor(Color.WHITE)
            }

            else -> {
                binding.combov2LastConnection.text = rh.gs(R.string.combov2_no_connection_for_n_mins, secondsPassed / 60)
                binding.combov2LastConnection.setTextColor(Color.RED)
            }
        }
    }

    private fun updateLastBolusField(lastBolus: ComboV2Plugin.LastBolus?, binding: Combov2FragmentBinding) {
        val currentTimestamp = System.currentTimeMillis()

        if (lastBolus == null) {
            binding.combov2LastBolus.text = ""
            return
        }

        // If the last bolus is >= 30 minutes ago,
        // we display a different message, one that
        // warns the user that a long time passed
        val bolusAgoText = when (val secondsPassed = (currentTimestamp - lastBolus.timestamp) / 1000) {
            in 60..(30 * 60) ->
                rh.gs(R.string.combov2_less_than_one_minute_ago)

            else ->
                rh.gs(info.nightscout.androidaps.core.R.string.minago, secondsPassed / 60)
        }

        binding.combov2LastConnection.text =
            rh.gs(
                R.string.combov2_last_bolus,
                lastBolus.bolusAmount.cctlBolusToIU(),
                rh.gs(R.string.insulin_unit_shortname),
                bolusAgoText
            )
    }
}
