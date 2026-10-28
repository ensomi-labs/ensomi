package io.ensomi.android.clock

import kotlin.test.Test
import kotlin.test.assertEquals

class AnchorAudioClockTest {
    @Test
    fun `one reading answers both directions`() {
        val clock = AnchorAudioClock(AudioAnchor(positionMs = 1_000.0, hostNanos = 1_000_000_000))

        assertEquals(1_000.0, clock.positionMsAt(1_000_000_000), 0.001)
        assertEquals(1_500.0, clock.positionMsAt(1_500_000_000), 0.001)
        assertEquals(1_500_000_000L, clock.hostNanosOf(1_500.0))
        assertEquals(1_000.0, clock.reading().positionMs)
    }

    @Test
    fun `readings too close together keep the nominal rate`() {
        val clock = AnchorAudioClock(AudioAnchor(0.0, 0))
        clock.onReading(AudioAnchor(positionMs = 120.0, hostNanos = 100_000_000))

        // The anchor moved to 120 ms at 100 ms of host time, so 1 s of host time is 1_020 ms of audio.
        assertEquals(1_020.0, clock.positionMsAt(1_000_000_000), 0.001)
    }

    @Test
    fun `a late reading is ignored`() {
        val clock = AnchorAudioClock(AudioAnchor(1_000.0, 1_000_000_000))
        clock.onReading(AudioAnchor(positionMs = 900.0, hostNanos = 500_000_000))

        assertEquals(1_000.0, clock.reading().positionMs)
    }

    @Test
    fun `a backwards position is ignored`() {
        val clock = AnchorAudioClock(AudioAnchor(1_000.0, 1_000_000_000))
        clock.onReading(AudioAnchor(positionMs = 900.0, hostNanos = 2_000_000_000))

        assertEquals(1_000.0, clock.reading().positionMs)
        assertEquals(2_000.0, clock.positionMsAt(2_000_000_000), 0.001)
    }

    @Test
    fun `two far-apart readings measure the drift`() {
        val clock = AnchorAudioClock(AudioAnchor(0.0, 0))
        // The device reports 1.004 s of audio for 1 s of host time.
        clock.onReading(AudioAnchor(positionMs = 1_004.0, hostNanos = 1_000_000_000))

        assertEquals(2_008.0, clock.positionMsAt(2_000_000_000), 0.001)
    }

    @Test
    fun `an absurd rate is treated as a bad reading`() {
        val clock = AnchorAudioClock(AudioAnchor(0.0, 0))
        clock.onReading(AudioAnchor(positionMs = 4_000.0, hostNanos = 1_000_000_000))

        // The anchor is the 4 s reading; only its rate was rejected, so the timeline stays at 1:1.
        assertEquals(5_000.0, clock.positionMsAt(2_000_000_000), 0.001)
    }
}
