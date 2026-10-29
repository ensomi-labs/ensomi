package io.ensomi.android.checks

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.ensomi.android.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The game pauses on what a test can do to it on a phone (plan step 7's pauses; gameplay.md's "Interruptions are
 * safe"): Back, Home and the launcher, Recents, the notification shade, and the screen turning off. The sample game
 * plays; after each, the game must have logged a pause under `EnsomiPlay` and show the pause panel when the app is in
 * front again; then Resume plays on. What the phone's own system does differently (HyperOS's gestures and windows) shows
 * in the causes it logged. A call, unplugged headphones and another output need a person: the runbook's checklist.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleCheck {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device by lazy { UiDevice.getInstance(instrumentation) }
    private val context get() = PhoneCheck.context

    @Test
    fun theGamePausesOnEachInterruption() {
        val resume = context.getString(R.string.play_resume)
        // Started as the launcher starts the app, so the launcher's intent brings this same task back (bringBack).
        val start = launcherIntent().putExtra("prototype", "pacing").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val results = mutableListOf<Map<String, Any?>>()
        context.startActivity(start)
        try {
            device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), PANEL_WAIT_MS)
            Thread.sleep(START_MS)
            for ((name, trigger) in triggers()) {
                PhoneCheck.shell("logcat -c")
                val started = System.nanoTime()
                trigger()
                bringBack()
                val panel = device.wait(Until.hasObject(By.text(resume)), PANEL_WAIT_MS) == true
                val causes = PhoneCheck.shell("logcat -d -v raw -s EnsomiPlay:I").lines()
                    .filter { it.startsWith("Paused:") }
                    .map { it.removePrefix("Paused:").trim() }
                val result = linkedMapOf<String, Any?>(
                    "trigger" to name,
                    "causes" to causes,
                    "panelShown" to panel,
                    "seconds" to PhoneCheck.round((System.nanoTime() - started) / 1e9, 1),
                    "ok" to (causes.isNotEmpty() && panel),
                )
                results += result
                PhoneCheck.report("lifecycle-pause", result)
                // Resume, and let the count-in run out, so the next interruption finds the game playing.
                device.findObject(By.text(resume))?.click()
                Thread.sleep(COUNT_IN_MS)
            }
        } finally {
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)) monitor.getActivitiesInStage(stage).forEach { it.finish() }
            }
        }
        val failed = results.filter { it["ok"] != true }.map { it["trigger"] }
        PhoneCheck.report("lifecycle", linkedMapOf("triggers" to results.size, "failed" to failed, "ok" to failed.isEmpty()))
        assertTrue("No pause, or no pause panel, after: $failed", failed.isEmpty())
    }

    private fun triggers(): List<kotlin.Pair<String, () -> Unit>> = listOf(
        "back" to { device.pressBack() },
        "home" to {
            device.pressHome()
            Thread.sleep(AWAY_MS)
        },
        "recents" to {
            device.pressRecentApps()
            Thread.sleep(AWAY_MS)
        },
        "notification-shade" to {
            device.openNotification()
            Thread.sleep(AWAY_MS)
            // Closes the shade; on a phone where it did not open, the game takes it as Back, which the causes show.
            device.pressBack()
        },
        "screen-off" to {
            device.sleep()
            Thread.sleep(AWAY_MS)
            device.wakeUp()
            PhoneCheck.shell("wm dismiss-keyguard")
        },
    )

    private fun launcherIntent(): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Brings the app back to the front the way the launcher icon does: an intent equal to the task's first (extras do
     * not count), so the same activity with the same game comes back.
     */
    private fun bringBack() {
        context.startActivity(launcherIntent())
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), PANEL_WAIT_MS)
        Thread.sleep(SETTLE_MS)
    }

    private companion object {
        const val START_MS = 5_000L
        const val AWAY_MS = 2_000L
        const val SETTLE_MS = 1_000L
        const val PANEL_WAIT_MS = 5_000L
        const val COUNT_IN_MS = 5_000L
    }
}
