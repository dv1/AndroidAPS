package info.nightscout.androidaps.plugins.pump.combov2

import info.nightscout.shared.logging.AAPSLogger
import info.nightscout.shared.logging.LTag
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.LoggerBackend as ComboCtlLoggerBackend

internal class AAPSComboCtlLogger(private val aapsLogger: AAPSLogger) : ComboCtlLoggerBackend {
    override fun log(tag: String, level: LogLevel, throwable: Throwable?, message: String?) {
        val ltag = with (tag) {
            when {
                contains("Bluetooth") -> LTag.PUMPBTCOMM
                endsWith("IO") -> LTag.PUMPCOMM
                else -> LTag.PUMP
            }
        }

        val fullMessage = "[$tag]" +
            (if (throwable != null) " (${throwable::class.qualifiedName}: \"${throwable.message}\")" else "") +
            (if (message != null) " $message" else "")


        when (level) {
            LogLevel.VERBOSE -> aapsLogger.debug(ltag, fullMessage)
            LogLevel.DEBUG   -> aapsLogger.debug(ltag, fullMessage)
            LogLevel.INFO    -> aapsLogger.info(ltag, fullMessage)
            LogLevel.WARN    -> aapsLogger.warn(ltag, fullMessage)
            LogLevel.ERROR   -> aapsLogger.error(ltag, fullMessage)
        }
    }
}
