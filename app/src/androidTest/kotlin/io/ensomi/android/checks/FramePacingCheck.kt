package io.ensomi.android.checks

import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Display
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.ensomi.android.MainActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The playfield's frame pacing at the phone's refresh rate (plan step 9's phone check; gameplay.md's "Frame pacing"
 * and "Refresh rate"): the sample game, the densest sample, played by the autoplay without end for `paceSeconds` (60)
 * at each lean, flat (0°), 40° and 55° in landscape and flat in portrait, one after the other. The game asks the
 * display for its fastest mode as play does.
 *
 * From the render thread's own record of every frame (`files/frame-log.csv`, debuggable builds): the frame rate,
 * vsyncs missed (a gap over 1.5 refresh intervals), frames still working when the next vsync came, frames finished after
 * their deadline, and the work per frame at the median, the 99th percentile and the most, in the runtime and in
 * drawing. Also the refresh rate the display ran at during play and the modes it offers. The target is under 0.5 % of
 * vsyncs missed, under 0.5 % of frames late and a p99 of work under 3 ms at every lean; this decides whether GLES is
 * ever needed. Nothing is asserted but that frames were drawn.
 *
 * `pacing` names the scenarios (`pacing-flat,pacing,pacing-lean-max,pacing-portrait`).
 */
@RunWith(AndroidJUnit4::class)
class FramePacingCheck {
    @Test
    fun framesAtEachLean() {
        val seconds = PhoneCheck.argInt("paceSeconds", 60)
        val names = PhoneCheck.arg("pacing", SCENARIOS.keys.joinToString(",")).split(',').map { it.trim() }.filter { it in SCENARIOS }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val empty = mutableListOf<String>()
        for (name in names) {
            val frames = measure(name, seconds, device)
            if (frames == 0) empty += name
        }
        assertTrue("No frames recorded for $empty", empty.isEmpty())
    }

    /**
     * What a loop on Android's own `Choreographer` loses to a frame that runs past the next vsync, by when it asks for
     * that vsync: after the frame, as most examples do, or at its start, as the playfield does. One frame in ten is
     * held to 1.15 refresh intervals after its vsync, short of the deadline; the others are empty. The same on any
     * device, the emulator's 60 Hz too, since this is `Choreographer`'s behaviour and not a phone's speed.
     */
    @Test
    fun aLongFrameKeepsTheNextVsync() {
        val refresh = PhoneCheck.context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate?.toDouble() ?: 60.0
        val interval = (1e9 / refresh).toLong()
        for (askFirst in listOf(false, true)) {
            val times = holdEveryTenthFrame(interval, askFirst)
            val gaps = DoubleArray(times.size - 1) { (times[it + 1] - times[it]).toDouble() }
            val missed = gaps.count { it > interval * 1.5 }
            PhoneCheck.report(
                "vsync-after-long-frame",
                linkedMapOf<String, Any?>(
                    "askedFor" to if (askFirst) "start of frame" else "end of frame",
                    "refreshHz" to PhoneCheck.round(refresh, 1),
                    "frames" to times.size,
                    "longFrames" to times.size / HELD_EVERY,
                    "missedVsyncs" to missed,
                    "ok" to true,
                ),
            )
        }
    }

    private fun holdEveryTenthFrame(interval: Long, askFirst: Boolean): LongArray {
        val times = LongArray(HELD_FRAMES)
        val finished = CountDownLatch(1)
        val thread = HandlerThread("EnsomiVsyncCheck").apply { start() }
        Handler(thread.looper).post {
            val choreographer = Choreographer.getInstance()
            var count = 0
            choreographer.postVsyncCallback(object : Choreographer.VsyncCallback {
                override fun onVsync(data: Choreographer.FrameData) {
                    val more = count + 1 < times.size
                    if (askFirst && more) choreographer.postVsyncCallback(this)
                    val vsync = data.frameTimeNanos
                    times[count++] = vsync
                    if (count % HELD_EVERY == 0) {
                        val until = vsync + interval * 115 / 100
                        while (System.nanoTime() < until) Unit
                    }
                    if (!more) finished.countDown() else if (!askFirst) choreographer.postVsyncCallback(this)
                }
            })
        }
        assertTrue("The frames did not finish", finished.await(HELD_FRAMES * 100L, TimeUnit.MILLISECONDS))
        thread.quitSafely()
        return times
    }

    private fun measure(name: String, seconds: Int, device: UiDevice): Int {
        val context = PhoneCheck.context
        val log = File(context.filesDir, FRAME_LOG)
        log.delete()
        val since = PhoneCheck.logcatNow()
        val thermalBefore = PhoneCheck.thermal()
        val intent = Intent(context, MainActivity::class.java).putExtra("prototype", name)
        val rates = mutableListOf<Double>()
        var modes = emptyList<String>()
        var logLines = emptyList<String>()
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            Thread.sleep(SETTLE_MS)
            // A system prompt about full screen would hold the game's lead-in: it is dismissed where it shows.
            device.findObject(By.text("Got it"))?.click()
            val until = System.nanoTime() + seconds * 1_000_000_000L
            while (System.nanoTime() < until) {
                scenario.onActivity { activity ->
                    val display = activity.display ?: return@onActivity
                    rates += display.refreshRate.toDouble()
                    modes = display.supportedModes.map { String.format(java.util.Locale.ROOT, "%dx%d@%.1f", it.physicalWidth, it.physicalHeight, it.refreshRate) }
                }
                Thread.sleep(minOf(SAMPLE_MS, maxOf(0L, (until - System.nanoTime()) / 1_000_000)))
            }
            // Read while the game still runs, so the pause of closing it is not among them.
            logLines = PhoneCheck.logcatSince(since, "EnsomiPlayfield:I", "EnsomiPlay:I")
        }
        // The playfield writes its record off the main thread once it is detached.
        val deadline = System.currentTimeMillis() + 15_000
        var lastSize = -1L
        while (System.currentTimeMillis() < deadline) {
            val size = if (log.isFile) log.length() else -1L
            if (size > 0 && size == lastSize) break
            lastSize = size
            Thread.sleep(300)
        }
        val asked = logLines.firstOrNull { it.startsWith("Frame rate:") }
        val pauses = logLines.filter { it.startsWith("Paused:") }
        val stats = logLines.filter { it.startsWith("fps=") }
        val rows = if (log.isFile) parse(log.readText()) else emptyList()
        val file = if (log.isFile) PhoneCheck.writeResult("frames-$name${suffix()}.csv", log.readText()) else null
        val summary = analyse(rows)
        val workP99 = summary["workMsP99"] as? Double ?: Double.NaN
        val latePct = summary["latePct"] as? Double ?: Double.NaN
        val missedPct = summary["missedPct"] as? Double ?: Double.NaN
        PhoneCheck.report(
            "frame-pacing",
            linkedMapOf<String, Any?>(
                "scenario" to name,
                "lean" to SCENARIOS[name],
                "seconds" to seconds,
                "displayRefreshHz" to rates.distinct().map { PhoneCheck.round(it, 1) },
                "displayModes" to modes,
                "asked" to asked,
                "pauses" to pauses,
            ) + summary + linkedMapOf(
                "renderThreadLog" to stats.takeLast(3),
                "thermalBefore" to thermalBefore,
                "thermalAfter" to PhoneCheck.thermal(),
                "file" to file,
                "meetsTarget" to (missedPct < 0.5 && latePct < 0.5 && workP99 < 3.0),
                "ok" to rows.isNotEmpty(),
            ),
        )
        return rows.size
    }

    /** Rows of the frame log: `vsyncNanos,presentNanos,runtimeNanos,drawNanos,deadlineNanos,doneNanos,...`. */
    private fun parse(csv: String): List<LongArray> = csv.lineSequence().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
        val parts = line.split(',')
        if (parts.size < 4) null else LongArray(parts.size) { parts[it].trim().toLongOrNull() ?: 0L }
    }.toList()

    private fun analyse(all: List<LongArray>): Map<String, Any?> {
        if (all.size < 3) return linkedMapOf("frames" to all.size)
        // The first two seconds are the game warming up (JIT, the lead-in): left out.
        val rows = all.filter { it[0] - all.first()[0] >= WARM_UP_NANOS }.takeIf { it.size >= 3 } ?: all
        val intervals = DoubleArray(rows.size - 1) { (rows[it + 1][0] - rows[it][0]).toDouble() }
        val interval = Stats.quantile(intervals.sortedArray(), 0.5)
        val missed = intervals.count { it > interval * 1.5 }
        val hasDeadline = rows.first().size >= 6
        val late = if (hasDeadline) rows.count { it[5] > it[4] } else null
        // Still working when the next vsync came: such a frame must not cost the next one.
        val overran = if (hasDeadline) rows.count { it[5] - it[0] > interval } else null
        val runtime = DoubleArray(rows.size) { rows[it][2] / 1e6 }
        val draw = DoubleArray(rows.size) { rows[it][3] / 1e6 }
        val work = DoubleArray(rows.size) { runtime[it] + draw[it] }
        val spanSeconds = (rows.last()[0] - rows.first()[0]) / 1e9
        // Frames drawn for an earlier presentation time than the frame before, or for the same one.
        var presentBack = 0
        var presentRepeats = 0
        for (i in 1 until rows.size) {
            if (rows[i][1] < rows[i - 1][1]) presentBack++ else if (rows[i][1] == rows[i - 1][1]) presentRepeats++
        }
        return linkedMapOf<String, Any?>(
            "frames" to rows.size,
            "fps" to PhoneCheck.round((rows.size - 1) / spanSeconds, 1),
            "vsyncIntervalMs" to PhoneCheck.round(interval / 1e6),
            "missedVsyncs" to missed,
            "missedPct" to PhoneCheck.round(100.0 * missed / intervals.size, 2),
            "overranFrames" to overran,
            "lateFrames" to late,
            "latePct" to late?.let { PhoneCheck.round(100.0 * it / rows.size, 2) },
            "presentationStepsBack" to presentBack,
            "presentationRepeats" to presentRepeats,
        ) + Stats.summary("workMs", work) + Stats.summary("runtimeMs", runtime) + Stats.summary("drawMs", draw)
    }

    private fun suffix() = buildString {
        append(if (PhoneCheck.debuggable) "-debug" else "-checks-build")
        if (PhoneCheck.label.isNotEmpty()) append('-').append(PhoneCheck.label)
    }

    private companion object {
        /** The scenarios (Prototype.kt) and what they show. */
        val SCENARIOS = linkedMapOf(
            "pacing-flat" to "landscape 0°",
            "pacing" to "landscape 40°",
            "pacing-lean-max" to "landscape 55°",
            "pacing-portrait" to "portrait 0°",
        )
        const val FRAME_LOG = "frame-log.csv"
        const val HELD_FRAMES = 1_000
        const val HELD_EVERY = 10
        const val SETTLE_MS = 3_000L
        const val SAMPLE_MS = 5_000L
        const val WARM_UP_NANOS = 2_000_000_000L
    }
}
