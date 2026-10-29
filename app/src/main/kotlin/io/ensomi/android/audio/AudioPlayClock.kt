package io.ensomi.android.audio

import io.ensomi.android.play.PlayClock
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** A reading of the output: a frame position, of which only the low 32 bits are valid, and when it was presented. */
data class OutputTimestamp(val framePosition: Long, val nanoTime: Long)

/** What the clock reads and drives: `AudioTrack.getTimestamp`, `play` and `pause`. */
interface PlayOutput {
    /** The newest timestamp, or null while the output has none. */
    fun timestamp(): OutputTimestamp?

    fun play()

    fun pause()
}

/**
 * Song time from the audio being heard: the newest timestamp pair of the output, carried to any asked time at rate 1.
 *
 * ```
 * songMs(t) = (framePosition − leadInFrames) / sampleRate × 1000 + (t − nanoTime) / 1e6
 * ```
 *
 * - Only the low 32 bits of the position are valid; the clock extends them across wraps.
 * - It reads the output every 10 ms after a start, a resume or [outputChanged] for [WARM_NANOS], then every
 *   [STABLE_POLL_NANOS].
 * - Before the first timestamp presented after a start or a resume, it holds its time: none after a start, the time
 *   paused at after a resume.
 * - It never runs backwards. A new pair that would put song time back is taken, but the clock keeps to the old line up
 *   to the latest time it was asked for and then holds there until the new line catches up; it takes no other pair
 *   while it holds. A pair ahead of the old line is taken at once.
 *
 * Thread-safe: the render thread calls [update] and [songTimeMs], the main thread [pause] and [resume].
 */
class AudioPlayClock(
    private val output: PlayOutput,
    private val sampleRate: Int,
    private val leadInFrames: Long,
    private val log: (String) -> Unit = {},
) : PlayClock {
    private class Line(val songMs: Double, val nanos: Long) {
        fun at(t: Long): Double = songMs + (t - nanos) / 1e6
    }

    private var started = false
    private var paused = false

    /** Waiting for the first timestamp presented after [waitFromNanos], with a frame past [waitFrame]. */
    private var waiting = false
    private var waitFromNanos = 0L
    private var waitFrame = 0L

    /** The time shown while paused or waiting: null after a start, until the first frame is heard. */
    private var heldMs: Double? = null

    private var line: Line? = null

    /** A step back being held: no less than [holdMs], and up to it the line before ([holdFrom]; null for none). */
    private var holdMs: Double? = null
    private var holdFrom: Line? = null

    /** The latest time asked for while running: nothing shown up to it may move back. */
    private var latestQueryNanos = Long.MIN_VALUE

    private var wraps = 0L
    private var lastLow = -1L
    private var lastFrame = 0L

    private var lastPollNanos = Long.MIN_VALUE
    private var warmUntilNanos = Long.MIN_VALUE

    /** For logs and tests: timestamps read, and steps back held. */
    var reads = 0
        @Synchronized get
        private set
    var stepsBack = 0
        @Synchronized get
        private set

    @Synchronized
    override fun start(nanoTime: Long, fromMs: Double) {
        // The lead-in is in the audio: [leadInFrames] of silence before the song's first frame.
        if (started) return
        started = true
        paused = false
        waitFor(nanoTime, frame = 0L)
        heldMs = null
        output.play()
    }

    @Synchronized
    override fun update(nanoTime: Long) {
        if (!started || paused) return
        val interval = if (waiting || nanoTime < warmUntilNanos) WARM_POLL_NANOS else STABLE_POLL_NANOS
        if (lastPollNanos != Long.MIN_VALUE && nanoTime - lastPollNanos < interval) return
        lastPollNanos = nanoTime
        val timestamp = output.timestamp() ?: return
        reads++
        val frame = extend(timestamp.framePosition)
        val next = Line((frame - leadInFrames) * 1000.0 / sampleRate, timestamp.nanoTime)
        if (waiting) {
            if (timestamp.nanoTime < waitFromNanos || frame <= waitFrame) return
            waiting = false
            line = next
            holdFrom = null
            holdMs = heldMs?.takeIf { next.at(max(nanoTime, latestQueryNanos)) < it }
            heldMs = null
            warmUntilNanos = nanoTime + WARM_NANOS
            return
        }
        val current = line ?: return
        val floor = holdMs
        if (floor != null) {
            // Still holding a step back: no new line until the one taken has caught up.
            if (current.at(nanoTime) < floor) return
            holdMs = null
            holdFrom = null
        }
        val at = max(nanoTime, latestQueryNanos)
        val shown = current.at(at)
        val back = shown - next.at(at)
        if (back > STEP_EPSILON_MS) {
            stepsBack++
            holdFrom = current
            holdMs = shown
            if (back >= LOG_STEP_MS) log(String.format(Locale.ROOT, "Clock: a timestamp %.1f ms behind; holding until it catches up", back))
        }
        line = next
    }

    @Synchronized
    override fun songTimeMs(nanoTime: Long): Double? {
        if (!started) return null
        if (paused || waiting) return heldMs
        if (nanoTime > latestQueryNanos) latestQueryNanos = nanoTime
        return valueAt(nanoTime)
    }

    @Synchronized
    override fun nanoTimeAt(songMs: Double): Long {
        val current = line ?: return waitFromNanos
        return current.nanos + ((songMs - current.songMs) * 1e6).toLong()
    }

    @Synchronized
    override fun pause(nanoTime: Long) {
        if (!started || paused) return
        // The furthest time shown, so that nothing drawn moves back.
        if (!waiting) heldMs = valueAt(max(nanoTime, latestQueryNanos))
        paused = true
        waiting = false
        output.pause()
    }

    @Synchronized
    override fun resume(nanoTime: Long) {
        if (!paused) return
        paused = false
        // A paused output reports the frame it stopped at; the first timestamp to take is one past it.
        val stoppedAt = output.timestamp()?.let { extend(it.framePosition) } ?: lastFrame
        waitFor(nanoTime, stoppedAt)
        output.play()
    }

    /** The output moved to another device: read it often again while the new route settles. */
    @Synchronized
    fun outputChanged(nanoTime: Long) {
        warmUntilNanos = nanoTime + WARM_NANOS
        lastPollNanos = Long.MIN_VALUE
    }

    override val isPaused: Boolean
        @Synchronized get() = paused

    private fun waitFor(nanoTime: Long, frame: Long) {
        waiting = true
        waitFromNanos = nanoTime
        waitFrame = frame
        holdMs = null
        holdFrom = null
        lastPollNanos = Long.MIN_VALUE
    }

    private fun valueAt(nanoTime: Long): Double? {
        val current = line ?: return null
        val base = current.at(nanoTime)
        val floor = holdMs ?: return base
        val before = holdFrom
        return max(base, if (before == null) floor else min(before.at(nanoTime), floor))
    }

    /** The low 32 bits of [position], extended across wraps. */
    private fun extend(position: Long): Long {
        val low = position and 0xFFFF_FFFFL
        if (lastLow >= 0 && low < lastLow && lastLow - low > HALF_RANGE) wraps++
        lastLow = low
        val frame = (wraps shl 32) or low
        lastFrame = frame
        return frame
    }

    companion object {
        /** How often the output is read while it warms up and for a second after each start, resume or route change. */
        const val WARM_POLL_NANOS = 10_000_000L
        const val WARM_NANOS = 1_000_000_000L

        /** How often once it is stable; a phone measurement may change it (gameplay.md). */
        const val STABLE_POLL_NANOS = 1_000_000_000L

        /** Differences smaller than this are rounding, not steps. */
        private const val STEP_EPSILON_MS = 0.01
        private const val LOG_STEP_MS = 2.0
        private const val HALF_RANGE = 0x8000_0000L
    }
}
