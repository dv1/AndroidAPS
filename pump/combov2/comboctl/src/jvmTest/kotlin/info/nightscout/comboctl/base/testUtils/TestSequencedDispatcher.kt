package info.nightscout.comboctl.base.testUtils

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

// Special sequenced dispatcher for tests. This explicitly uses
// a dedicated thread instead of CoroutineDispatcher.limitedParallelism()
// because the latter may switch between threads when a next task is to
// be dispatched. (limitedParallelism() only ensures that no more than N
// of its tasks are run concurrently; it does not imply that the tasks
// are run in N dedicated threads, and in fact, runs the next task in
// any thread that is currently available). For logging test output,
// it is beneficial to be able to quickly verify that the ComboCtl
// coroutine tasks are run in sequence (and not concurrently). The
// easiest way to accomplish that is to run them in a dedicated thread,
// because then, the logger can show the thread name.
val testSequencedDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable).apply {
        name = "CCtlTestSeqDispatcher"
        // Mark this thread as a daemon thread to allow the JVM
        // to exit while it is still running. There is no easy
        // way to automatically stop the thread at the end of the
        // tests, so marking it as a daemon thread is necessary.
        isDaemon = true
    }
}.asCoroutineDispatcher()