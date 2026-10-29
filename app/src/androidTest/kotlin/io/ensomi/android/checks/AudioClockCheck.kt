package io.ensomi.android.checks

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ensomi.android.audio.AudioPlayClock
import io.ensomi.android.audio.DecodedSong
import io.ensomi.android.audio.GameAudio
import io.ensomi.android.play.PlayController
import io.ensomi.android.settings.OutputDevice
import io.ensomi.android.settings.OutputRoutes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The game's sound and clock on the phone's output (plan step 6's phone check, gameplay.md's "Clock smoothness" and
 * "Picture against audio"): the game's `GameAudio` plays quiet clicks for `audioSeconds` (180) with the lead-in, and
 * a vsync-paced thread does what the render thread does each frame, `AudioPlayClock.update` and the song time at the
 * frame's expected presentation, while also reading the track's raw timestamp. Halfway it pauses for two seconds and
 * resumes, as a game does.
 *
 * Reported: the raw timestamp pairs' straight-line fit (residual standard deviation, the audio clock's drift against
 * `CLOCK_MONOTONIC` in ppm, the largest residual), how often the timestamp moves, steps back, the song time between
 * frames against the presentation time between them (sd and worst), the time to the first new song time after the
 * resume, underruns, and the routed output. Run it once per output (speaker, wired, Bluetooth) with the `label`
 * argument naming it; the output is recorded too. Song time going back fails the check; the rest is measured.
 */
@RunWith(AndroidJUnit4::class)
class AudioClockCheck {
    private class Frame(val nanos: Long, val presentNanos: Long, val songMs: Double, val paused: Boolean)

    private class Stamp(val readNanos: Long, val framePosition: Long, val nanoTime: Long)

    @Test
    fun theClockIsSmoothAndNeverGoesBack() {
        val seconds = PhoneCheck.argInt("audioSeconds", 180)
        val volume = PhoneCheck.argDouble("audioVolume", 0.3).toFloat()
        val context = PhoneCheck.context
        val song = clickLoop(RATE)
        val routes = mutableListOf<String>()
        val audio = GameAudio(context, song, PlayController.LEAD_IN_MS, volume, onRoute = { device: AudioDeviceInfo? ->
            synchronized(routes) { routes += device?.let { OutputRoutes.key(OutputDevice(it.type, it.productName.toString())) } ?: "none" }
        }, loopFromFrame = 0)
        val clock = AudioPlayClock(audio, audio.sampleRate, audio.leadInFrames)
        val frames = ArrayList<Frame>(seconds * 130)
        val pairs = ArrayList<Stamp>(seconds * 130)
        val thread = HandlerThread("PhoneCheckClock", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        val pauseAtNanos = LongArray(2)
        var resumedAt = 0L
        handler.post {
            val choreographer = Choreographer.getInstance()
            val start = System.nanoTime()
            clock.start(start, -PlayController.LEAD_IN_MS)
            val half = start + seconds * 500_000_000L
            val end = start + seconds * 1_000_000_000L
            choreographer.postVsyncCallback(object : Choreographer.VsyncCallback {
                override fun onVsync(data: Choreographer.FrameData) {
                    val now = System.nanoTime()
                    if (now >= end) {
                        done.countDown()
                        return
                    }
                    if (pauseAtNanos[0] == 0L && now >= half) {
                        pauseAtNanos[0] = now
                        clock.pause(now)
                    } else if (pauseAtNanos[0] != 0L && pauseAtNanos[1] == 0L && now >= pauseAtNanos[0] + PAUSE_NANOS) {
                        pauseAtNanos[1] = now
                        resumedAt = now
                        clock.resume(now)
                    }
                    clock.update(now)
                    val present = data.preferredFrameTimeline.expectedPresentationTimeNanos
                    frames += Frame(now, present, clock.songTimeMs(present) ?: Double.NaN, clock.isPaused)
                    audio.timestamp()?.let { pairs += Stamp(now, it.framePosition, it.nanoTime) }
                    choreographer.postVsyncCallback(this)
                }
            })
        }
        assertTrue("The check did not finish", done.await(seconds + 60L, TimeUnit.SECONDS))
        thread.quitSafely()
        thread.join()
        val underruns = audio.underruns
        audio.close()

        val result = analyse(frames, pairs, pauseAtNanos, resumedAt, audio.sampleRate, audio.leadInFrames)
        val audioManager = context.getSystemService(AudioManager::class.java)
        val csvFrames = PhoneCheck.writeResult(
            "audio-clock${suffix()}-frames.csv",
            buildString {
                append("nanos,presentNanos,songMs,paused\n")
                for (f in frames) append(f.nanos).append(',').append(f.presentNanos).append(',').append(f.songMs).append(',').append(f.paused).append('\n')
            },
        )
        val csvPairs = PhoneCheck.writeResult(
            "audio-clock${suffix()}-timestamps.csv",
            buildString {
                append("readNanos,framePosition,nanoTime\n")
                for (p in pairs) append(p.readNanos).append(',').append(p.framePosition).append(',').append(p.nanoTime).append('\n')
            },
        )
        val songBack = result["songTimeStepsBack"] as Int
        PhoneCheck.report(
            "audio-clock",
            linkedMapOf<String, Any?>(
                "seconds" to seconds,
                "routes" to synchronized(routes) { routes.toList() },
                "sampleRate" to audio.sampleRate,
                "outputSampleRate" to audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE),
                "outputFramesPerBuffer" to audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER),
                "queuedMs" to GameAudio.QUEUED_MS,
                "underruns" to underruns,
                "clockReads" to clock.reads,
                "clockStepsBackHeld" to clock.stepsBack,
            ) + result + linkedMapOf(
                "files" to listOf(csvFrames, csvPairs),
                "ok" to (songBack == 0),
            ),
        )
        assertEquals("Song time went back between frames", 0, songBack)
    }

    private fun analyse(
        frames: List<Frame>,
        pairs: List<Stamp>,
        pause: LongArray,
        resumedAt: Long,
        rate: Int,
        leadInFrames: Long,
    ): Map<String, Any?> {
        // Song time between frames: never back while the time asked for moves on; against the presentation time, the
        // picture's own jitter. A frame whose preferred presentation time is earlier than the one before (the frame
        // timeline Choreographer prefers changed) asks for an earlier time: counted apart, as a finding about vsync.
        var songBack = 0
        var worstBack = 0.0
        var presentBack = 0
        var presentWorstBack = 0.0
        var presentRepeats = 0
        val steps = ArrayList<Double>()
        for (i in 1 until frames.size) {
            val a = frames[i - 1]
            val b = frames[i]
            if (b.presentNanos < a.presentNanos) {
                presentBack++
                presentWorstBack = maxOf(presentWorstBack, (a.presentNanos - b.presentNanos) / 1e6)
                continue
            }
            if (b.presentNanos == a.presentNanos) presentRepeats++
            if (a.songMs.isNaN() || b.songMs.isNaN()) continue
            if (b.songMs < a.songMs - 1e-6) {
                songBack++
                worstBack = maxOf(worstBack, a.songMs - b.songMs)
            }
            // Only frames of a running clock, a second past the start and away from the pause.
            val nearPause = pause[0] != 0L && b.nanos > pause[0] - 1_000_000_000L && b.nanos < pause[1] + 1_000_000_000L
            if (!a.paused && !b.paused && !nearPause && a.songMs > 1_000) {
                steps += (b.songMs - a.songMs) - (b.presentNanos - a.presentNanos) / 1e6
            }
        }
        val firstMoving = if (resumedAt == 0L) null else {
            val held = frames.lastOrNull { it.nanos < resumedAt }?.songMs
            frames.firstOrNull { it.nanos >= resumedAt && held != null && it.songMs > held + 1e-6 }?.let { (it.nanos - resumedAt) / 1e6 }
        }

        // The raw pairs: each distinct one once, a straight line per stretch of play (before and after the pause).
        val distinct = pairs.distinctBy { it.framePosition to it.nanoTime }
        var positionBack = 0
        for (i in 1 until distinct.size) if (distinct[i].framePosition < distinct[i - 1].framePosition) positionBack++
        val leadInEnd = frames.firstOrNull { it.songMs >= 1_000 }?.nanos ?: Long.MAX_VALUE
        fun stretch(first: Long, last: Long) = distinct.filter { it.nanoTime >= first && it.nanoTime < last && it.framePosition > leadInFrames }
        val before = stretch(leadInEnd, if (pause[0] != 0L) pause[0] else Long.MAX_VALUE)
        val after = if (pause[1] != 0L) stretch(pause[1] + 1_000_000_000L, Long.MAX_VALUE) else emptyList()
        val fits = listOf(before, after).filter { it.size >= 10 }.map { points ->
            val t0 = points.first().nanoTime
            val x = DoubleArray(points.size) { (points[it].nanoTime - t0) / 1e9 }
            val y = DoubleArray(points.size) { points[it].framePosition.toDouble() }
            Stats.fit(x, y)
        }.filterNotNull()
        val gaps = DoubleArray(maxOf(0, distinct.size - 1)) { (distinct[it + 1].nanoTime - distinct[it].nanoTime) / 1e6 }
        val ahead = DoubleArray(distinct.size) { (distinct[it].nanoTime - distinct[it].readNanos) / 1e6 }
        val residualSdMs = fits.maxOfOrNull { it.residualSd * 1000 / rate } ?: Double.NaN
        val stepArray = steps.toDoubleArray()
        val stepSd = Stats.sd(stepArray)
        return linkedMapOf<String, Any?>(
            "frames" to frames.size,
            "frameRateHz" to frames.takeIf { it.size > 1 }?.let { PhoneCheck.round((it.size - 1) * 1e9 / (it.last().nanos - it.first().nanos), 1) },
            "songTimeStepsBack" to songBack,
            "songTimeWorstBackMs" to PhoneCheck.round(worstBack),
            "presentationStepsBack" to presentBack,
            "presentationWorstBackMs" to PhoneCheck.round(presentWorstBack),
            "presentationRepeats" to presentRepeats,
            "pictureStepSdMs" to PhoneCheck.round(stepSd),
            "pictureStepWorstMs" to PhoneCheck.round(stepArray.maxOfOrNull { abs(it) } ?: Double.NaN),
            "pictureStepGood" to (stepSd < 0.5),
            "firstNewSongTimeAfterResumeMs" to firstMoving?.let { PhoneCheck.round(it, 1) },
            "timestamps" to distinct.size,
            "timestampPositionsBack" to positionBack,
            "timestampGapMsP50" to PhoneCheck.round(Stats.quantile(gaps.sortedArray(), 0.5), 2),
            "timestampGapMsMax" to PhoneCheck.round(gaps.maxOrNull() ?: Double.NaN, 2),
            "timestampAheadOfReadMsP50" to PhoneCheck.round(Stats.quantile(ahead.sortedArray(), 0.5), 2),
            "fitResidualSdMs" to PhoneCheck.round(residualSdMs),
            "fitMaxResidualMs" to PhoneCheck.round(fits.maxOfOrNull { it.maxAbsResidual * 1000 / rate } ?: Double.NaN),
            "driftPpm" to fits.map { PhoneCheck.round((it.b / rate - 1) * 1e6, 1) },
            "smoothnessGood" to (residualSdMs < 0.5),
        )
    }

    private fun suffix() = if (PhoneCheck.label.isEmpty()) "" else "-${PhoneCheck.label}"

    /** Four seconds of 44.1 kHz stereo with a quiet click every 500 ms, repeated by the writer for the whole check. */
    private fun clickLoop(rate: Int): DecodedSong {
        val frames = rate * 4
        val samples = FloatArray(frames * 2)
        val period = rate / 2
        for (i in 0 until frames) {
            val inPeriod = i % period
            val t = inPeriod.toDouble() / rate
            val value = if (inPeriod < rate / 50) (0.2 * exp(-t * 200) * sin(2 * PI * 1760 * t)).toFloat() else 0f
            samples[2 * i] = value
            samples[2 * i + 1] = value
        }
        return DecodedSong(samples, 2, rate, 0, "audio/raw", "phone check clicks", null)
    }

    private companion object {
        const val RATE = 44_100
        const val PAUSE_NANOS = 2_000_000_000L
    }
}
