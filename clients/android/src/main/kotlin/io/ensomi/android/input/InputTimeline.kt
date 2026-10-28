package io.ensomi.android.input

import io.ensomi.android.clock.AudioClock

/**
 * The conversions a press needs before it can enter a session. `MotionEvent.getEventTime()` is an
 * `uptimeMillis` reading, while the audio clock's anchors are `elapsedRealtimeNanos` readings, and the
 * two clocks differ by the time the device spent asleep. The offset between them is measured, not
 * assumed, so a press that happened before the device slept cannot land minutes away.
 */
object HostTime {
    fun elapsedNanosOf(uptimeMs: Long, nowUptimeMs: Long, nowElapsedNanos: Long): Long =
        nowElapsedNanos - (nowUptimeMs - uptimeMs) * 1_000_000L
}

/**
 * Maps a pointer event onto the song's timeline through the audio clock, which is the whole point of
 * contract 2: judgement happens at the audio time of the input, not at the frame time it was handled.
 */
class InputTimeline(
    private val clock: AudioClock,
    private val nowUptimeMs: () -> Long,
    private val nowElapsedNanos: () -> Long,
) {
    fun chartTimeMs(eventUptimeMs: Long): Double {
        val hostNanos = HostTime.elapsedNanosOf(eventUptimeMs, nowUptimeMs(), nowElapsedNanos())
        return clock.positionMsAt(hostNanos)
    }
}
