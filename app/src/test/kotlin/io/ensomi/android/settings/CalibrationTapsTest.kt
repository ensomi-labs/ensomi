package io.ensomi.android.settings

import io.ensomi.android.audio.ClickTrack
import io.ensomi.android.play.FakePlayClock
import io.ensomi.android.play.SampleCharts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The click track and its arithmetic, which the calibration's field and log stand on. */
class CalibrationTapsTest {
    @Test
    fun errorIsFromTheNearestClick() {
        assertEquals(0.0, CalibrationTaps.errorMs(1_000.0)!!, 1e-9)
        assertEquals(20.0, CalibrationTaps.errorMs(1_520.0)!!, 1e-9)
        assertEquals(-20.0, CalibrationTaps.errorMs(1_480.0)!!, 1e-9)
        assertEquals(-240.0, CalibrationTaps.errorMs(760.0)!!, 1e-9)
        assertEquals(249.0, CalibrationTaps.errorMs(10_249.0)!!, 1e-9)
        // More than half a period before the first click is no press on a click.
        assertNull(CalibrationTaps.errorMs(740.0))
        assertNull(CalibrationTaps.errorMs(-500.0))
    }

    /** Presses stamped at known times on the play clock, as the field stamps them, are where they were made. */
    @Test
    fun pressesAtKnownTimesOnTheClockGiveTheirErrors() {
        val clock = FakePlayClock()
        clock.start(nanoTime = 5_000_000_000L, fromMs = 0.0)
        val lateMs = listOf(31.0, 27.0, 35.0, 29.0, 33.0, 30.0, 26.0, 34.0, 28.0, 32.0, 30.0, 31.0, 29.0, 30.0, 36.0, 24.0)
        val errors = lateMs.mapIndexed { k, late ->
            val eventNanos = clock.nanoTimeAt(ClickTrack.FIRST_CLICK_MS + (k + 2) * ClickTrack.PERIOD_MS + late)
            CalibrationTaps.errorMs(clock.songTimeMs(eventNanos)!!)!!
        }
        errors.zip(lateMs).forEach { (error, late) -> assertEquals(late, error, 1e-3) }
    }

    @Test
    fun clickTrackHasItsFirstClickAtOneSecondAndRepeatsEveryHalfSecond() {
        val rate = ClickTrack.SAMPLE_RATE
        val pcm = ClickTrack.pcm(rate)
        val first = ClickTrack.loopFromFrame(rate)
        assertEquals(rate, first)
        assertEquals(rate + rate / 2, pcm.size)
        assertTrue("Silence before the first click", (0 until first).all { pcm[it] == 0f })
        val onset = (first until pcm.size).first { pcm[it] != 0f }
        assertTrue("The click is heard from its first millisecond", onset - first < rate / 1000)
        assertTrue("The click is short", (first + rate / 10 until pcm.size).all { kotlin.math.abs(pcm[it]) < 1e-3f })
        assertTrue("No clipping", pcm.all { kotlin.math.abs(it) <= 1f })
    }

    @Test
    fun theFieldsNotesFallOnTheClicksInOneLane() {
        val notes = SampleCharts.CALIBRATION.notes
        notes.forEachIndexed { k, note -> assertEquals(ClickTrack.FIRST_CLICK_MS + k * ClickTrack.PERIOD_MS, note.startMs.toDouble(), 1e-9) }
        assertTrue("Every note in the lane every press counts for", notes.all { it.lane == SampleCharts.CALIBRATION_LANE })
        assertTrue("Taps only", notes.all { it.endMs == null })
        assertTrue("Half an hour of clicks", notes.size * ClickTrack.PERIOD_MS >= 30 * 60_000)
    }
}
