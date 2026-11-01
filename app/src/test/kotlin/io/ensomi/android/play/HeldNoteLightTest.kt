package io.ensomi.android.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldNoteLightTest {
    @Test
    fun twoSmallSmoothPulsesPerSecondWithSolidEnds() {
        var crests = 0
        var previous = HeldNoteLight.pulse(-1.0, false)
        var rising = true
        for (ms in 0..1000) {
            val pulse = HeldNoteLight.pulse(ms.toDouble(), false)
            assertTrue(HeldNoteLight.bodyAlpha(pulse) in 207..227)
            assertTrue(HeldNoteLight.haloAlpha(pulse) in 78..102)
            assertTrue(kotlin.math.abs(pulse - previous) < 0.013f)
            if (rising && pulse < previous) { crests++; rising = false }
            if (pulse > previous) rising = true
            previous = pulse
        }
        assertEquals(2, crests)
    }

    @Test
    fun removeAnimationsKeepsTheOriginalSteadyLight() {
        for (ms in listOf(-400.0, 0.0, 125.0, 375.0, 1_234_567.0)) {
            val pulse = HeldNoteLight.pulse(ms, true)
            assertEquals(217, HeldNoteLight.bodyAlpha(pulse))
            assertEquals(90, HeldNoteLight.haloAlpha(pulse))
        }
    }

    @Test
    fun pauseFreezesPhaseAndResumeContinuesIt() {
        val clock = FakePlayClock()
        clock.start(0L, 0.0)
        clock.pause(120_000_000L)
        val atPause = HeldNoteLight.pulse(clock.songTimeMs(120_000_000L)!!, false)
        assertEquals(atPause, HeldNoteLight.pulse(clock.songTimeMs(9_000_000_000L)!!, false), 0f)
        clock.resume(9_000_000_000L)
        assertEquals(atPause, HeldNoteLight.pulse(clock.songTimeMs(9_000_000_000L)!!, false), 0f)
        assertEquals(1f, HeldNoteLight.pulse(clock.songTimeMs(9_005_000_000L)!!, false), 0.0001f)
    }
}
