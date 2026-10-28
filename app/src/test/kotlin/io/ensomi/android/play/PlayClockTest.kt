package io.ensomi.android.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayClockTest {
    private val ms = 1_000_000L

    @Test
    fun readsNothingBeforeItStarts() {
        assertNull(FakePlayClock().songTimeMs(0))
    }

    @Test
    fun runsAtRateOneFromTheLeadIn() {
        val clock = FakePlayClock()
        clock.start(nanoTime = 1_000 * ms, fromMs = -2_000.0)
        assertEquals(-2_000.0, clock.songTimeMs(1_000 * ms)!!, 1e-9)
        assertEquals(0.0, clock.songTimeMs(3_000 * ms)!!, 1e-9)
        assertEquals(3_000 * ms, clock.nanoTimeAt(0.0))
    }

    @Test
    fun holdsWhilePausedAndGoesOnFromThere() {
        val clock = FakePlayClock()
        clock.start(nanoTime = 0, fromMs = 0.0)
        clock.pause(500 * ms)
        assertTrue(clock.isPaused)
        assertEquals(500.0, clock.songTimeMs(9_000 * ms)!!, 1e-9)
        clock.resume(10_000 * ms)
        assertFalse(clock.isPaused)
        assertEquals(600.0, clock.songTimeMs(10_100 * ms)!!, 1e-9)
    }
}
