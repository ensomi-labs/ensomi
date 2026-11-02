package io.ensomi.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.ensomi.android.diagnostics.ExitRecords
import io.ensomi.android.diagnostics.exceptionContext
import io.ensomi.android.diagnostics.Journal
import io.ensomi.android.diagnostics.ProcessStage

/** Recovery starts before any activity, app store or native runtime can be loaded. */
class EnsomiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Journal.start(this)
        ProcessStage.set(this, ProcessStage.Stage.START)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                Journal.record("uncaught_exception", "crash", exceptionContext(failure))
            } finally { previous?.uncaughtException(thread, failure) }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) { Journal.record("lifecycle", "stop") }
            override fun onActivityStarted(activity: Activity) { Journal.record("lifecycle", "start", durable = false) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        ExitRecords.start(this)
    }
}
