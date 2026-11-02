package io.ensomi.android.model

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Debug APK only. These flag names and signal calls are absent from the release source set. */
internal object ModelInstallFaults {
    fun afterMarker(context: Context) {
        if (File(context.filesDir, "fault-model-open").exists()) Os.kill(Os.getpid(), OsConstants.SIGILL)
    }

    fun afterStaging(context: Context) {
        if (File(context.filesDir, "fault-model-staged").exists()) Os.kill(Os.getpid(), OsConstants.SIGKILL)
    }
}
