package io.ensomi.android.play

/**
 * Song time as it is heard. Times on the play path share one clock, `CLOCK_MONOTONIC`: `System.nanoTime()`, the
 * `MotionEvent` event times and `AudioTimestamp.nanoTime` with `TIMEBASE_MONOTONIC`.
 */
interface PlayClock {
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
}

/**
 * Not the audio clock: a clock that runs at rate 1 from [start], for the playfield preview, which has no sound. The
 * real clock (plan step 6) reads `AudioTrack.getTimestamp` and keeps the same contract.
 */
class FakePlayClock : PlayClock {
    /** The nanoTime at song time 0. */
    private var originNanos = 0L
    private var pausedAtMs: Double? = null
    private var started = false

    /** Starts at song time [fromMs], which is negative for a lead-in. */
    @Synchronized
    fun start(nanoTime: Long, fromMs: Double) {
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
