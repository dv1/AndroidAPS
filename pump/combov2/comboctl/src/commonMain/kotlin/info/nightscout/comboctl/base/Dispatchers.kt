package info.nightscout.comboctl.base

import kotlinx.coroutines.Dispatchers

/**
 * Default sequenced dispatcher for ComboCtl.
 *
 * A "sequenced" dispatcher is one that enforces sequential execution of
 * coroutines, thus disallowing parallelism (= tasks are "sequenced").
 *
 * Sequenced dispatchers are important for ComboCtl, since parallel
 * IO is not supported by the Combo and only causes IO errors. The
 * driver is overall not written with thread safety in mind, since
 * there are no benefits of running the driver with multiple threads,
 * and this would only increase code complexity.
 *
 * This here is a sequenced dispatcher that acts as a default if no
 * other one is used explicitly. For how a sequenced dispatcher is used,
 * see [info.nightscout.comboctl.main.PumpManager].
 */
val defaultSequencedDispatcher = Dispatchers.Default.limitedParallelism(1, "CCtlDefaultSeqDispatcher")
