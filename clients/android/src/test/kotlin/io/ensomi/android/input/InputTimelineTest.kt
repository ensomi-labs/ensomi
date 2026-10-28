package io.ensomi.android.input

import io.ensomi.android.clock.AnchorAudioClock
import io.ensomi.android.clock.AudioAnchor
import kotlin.test.Test
import kotlin.test.assertEquals

class InputTimelineTest {
    @Test
    fun `the two host clocks are related by their offset`() {
        // Uptime says 200_000 ms; elapsed says 6_000_000_000 ns, so the device slept 5_800_000 ms.
        val elapsed = HostTime.elapsedNanosOf(
            uptimeMs = 199_500,
            nowUptimeMs = 200_000,
            nowElapsedNanos = 6_000_000_000,
        )

        assertEquals(5_500_000_000L, elapsed)
    }

    @Test
    fun `a press enters the session at the audio time it happened`() {
        val clock = AnchorAudioClock(AudioAnchor(positionMs = 10_000.0, hostNanos = 5_000_000_000))
        val timeline = InputTimeline(
            clock = clock,
            nowUptimeMs = { 200_000 },
            nowElapsedNanos = { 6_000_000_000 },
        )

        // The press happened 500 ms ago on the uptime clock, so 10_500 ms into the song.
        assertEquals(10_500.0, timeline.chartTimeMs(199_500), 0.001)
    }

    @Test
    fun `a press after the reading is placed after it`() {
        val clock = AnchorAudioClock(AudioAnchor(positionMs = 1_000.0, hostNanos = 10_000_000_000))
        val timeline = InputTimeline(
            clock = clock,
            nowUptimeMs = { 500_000 },
            nowElapsedNanos = { 10_500_000_000 },
        )

        assertEquals(1_750.0, timeline.chartTimeMs(500_250), 0.001)
    }
}
