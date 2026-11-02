package io.ensomi.android.diagnostics

import android.system.Os
import android.system.OsConstants
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Compiled only into debug; both extras are required. Never present in release or checks. */
internal object DiagnosticsFaults {
    fun run(activity: DiagnosticsActivity) {
        if (!activity.intent.getBooleanExtra("diagnostics-faults", false)) return
        val fault = activity.intent.getStringExtra("diagnostics-fault")
        activity.lifecycleScope.launch {
            delay(1500)
            when (fault) {
                "java" -> { Journal.record("debug_fault", "java"); error("Invented diagnostic failure") }
                "native" -> { Journal.record("debug_fault", "native"); Os.kill(Os.getpid(), OsConstants.SIGABRT) }
            }
        }
    }
}
