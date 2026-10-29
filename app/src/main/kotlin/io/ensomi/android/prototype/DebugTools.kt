package io.ensomi.android.prototype

import android.content.Context
import android.content.pm.ApplicationInfo
import io.ensomi.android.R

/**
 * Whether this build takes the debug launch extras (the scenarios, the autoplay, stars and `lowMemory`) and keeps each
 * game's frame and clock logs: debuggable builds, and the `checks` build, which is not debuggable so that the phone
 * checks measure frame pacing as a release runs (docs/phone-checks.md). A release build never does.
 */
object DebugTools {
    fun enabled(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 || context.resources.getBoolean(R.bool.debug_tools)
}
