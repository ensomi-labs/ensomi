package io.ensomi.android.play

/**
 * Song time as it is heard. Times on the play path share one clock, `CLOCK_MONOTONIC`: `System.nanoTime()`, the
 * `MotionEvent` event times and `AudioTimestamp.nanoTime` with `TIMEBASE_MONOTONIC`.
 *
 * Called from the render thread ([start], [update], [songTimeMs]) and the main thread ([pause], [resume]).
 */
interface PlayClock : AutoCloseable {
    /** Starts the song from [fromMs], which is negative for a lead-in. */
    fun start(nanoTime: Long, fromMs: Double)

    /** Reads the output's position when it is due; the render thread calls it at the start of every frame. */
    fun update(nanoTime: Long) = Unit

    /**
     * The song time, in milliseconds, of the sound heard at [nanoTime]: negative during the lead-in before the
     * song's first frame, frozen while paused, null until the output has reported its first frame.
     */
    fun songTimeMs(nanoTime: Long): Double?

    /** The nanoTime at which [songTimeMs] reaches [songMs] if the clock runs on, for scripted input. */
    fun nanoTimeAt(songMs: Double): Long

    fun pause(nanoTime: Long)

    fun resume(nanoTime: Long)

    val isPaused: Boolean

    override fun close() = Unit
}

/**
 * Not the audio clock: a clock that runs at rate 1 from [start], for the playfield preview and the calibration's
 * picture step, which have no sound of their own. A game runs on [AudioPlayClock], which keeps the same contract.
 */
class FakePlayClock : PlayClock {
    /** The nanoTime at song time 0. */
    private var originNanos = 0L
    private var pausedAtMs: Double? = null
    private var started = false

    @Synchronized
    override fun start(nanoTime: Long, fromMs: Double) {
        originNanos = nanoTime - (fromMs * 1e6).toLong()
        pausedAtMs = null
        started = true
    }

    @Synchronized
    override fun songTimeMs(nanoTime: Long): Double? {
        if (!started) return null
        return pausedAtMs ?: ((nanoTime - originNanos) / 1e6)
    }

    @Synchronized
    override fun nanoTimeAt(songMs: Double): Long = originNanos + (songMs * 1e6).toLong()

    @Synchronized
    override fun pause(nanoTime: Long) {
        if (started && pausedAtMs == null) pausedAtMs = (nanoTime - originNanos) / 1e6
    }

    @Synchronized
    override fun resume(nanoTime: Long) {
        val at = pausedAtMs ?: return
        originNanos = nanoTime - (at * 1e6).toLong()
        pausedAtMs = null
    }

    override val isPaused: Boolean
        @Synchronized get() = pausedAtMs != null
}
