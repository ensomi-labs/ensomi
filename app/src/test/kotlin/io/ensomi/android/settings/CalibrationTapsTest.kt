package io.ensomi.android.settings

import io.ensomi.android.audio.ClickTrack
import io.ensomi.android.play.FakePlayClock
import io.ensomi.android.play.SampleCharts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTapsTest {
    @Test
    fun errorIsFromTheNearestClick() {
        assertEquals(0.0, CalibrationTaps.errorMs(1_000.0)!!, 1e-9)
        assertEquals(20.0, CalibrationTaps.errorMs(1_520.0)!!, 1e-9)
        assertEquals(-20.0, CalibrationTaps.errorMs(1_480.0)!!, 1e-9)
        assertEquals(-240.0, CalibrationTaps.errorMs(760.0)!!, 1e-9)
        assertEquals(249.0, CalibrationTaps.errorMs(10_249.0)!!, 1e-9)
        // More than half a period before the first click is no tap on a click.
        assertNull(CalibrationTaps.errorMs(740.0))
        assertNull(CalibrationTaps.errorMs(-500.0))
    }

    @Test
    fun medianOfOddAndEvenCounts() {
        assertEquals(3.0, CalibrationTaps.median(listOf(5.0, 1.0, 3.0)), 1e-9)
        assertEquals(2.5, CalibrationTaps.median(listOf(4.0, 1.0, 3.0, 2.0)), 1e-9)
    }

    @Test
    fun noSuggestionUntilSixteenTaps() {
        assertNull(CalibrationTaps.suggestionMs(List(15) { 20.0 }))
        assertEquals(-20, CalibrationTaps.suggestionMs(List(16) { 20.0 }))
    }

    @Test
    fun onlyTheLastSixteenCount() {
        val errors = List(10) { 100.0 } + List(16) { -12.0 }
        assertEquals(12, CalibrationTaps.suggestionMs(errors))
    }

    @Test
    fun anOddTapMovesTheMedianLittle() {
        val errors = List(15) { 30.0 } + 240.0
        assertEquals(-30, CalibrationTaps.suggestionMs(errors))
    }

    /** Taps stamped at known times on the play clock, as the pad stamps them, give the expected suggestion. */
    @Test
    fun tapsAtKnownTimesOnTheClockGiveTheSuggestion() {
        val clock = FakePlayClock()
        clock.start(nanoTime = 5_000_000_000L, fromMs = 0.0)
        val lateMs = listOf(31.0, 27.0, 35.0, 29.0, 33.0, 30.0, 26.0, 34.0, 28.0, 32.0, 30.0, 31.0, 29.0, 30.0, 36.0, 24.0)
        val errors = lateMs.mapIndexed { k, late ->
            val eventNanos = clock.nanoTimeAt(ClickTrack.FIRST_CLICK_MS + (k + 2) * ClickTrack.PERIOD_MS + late)
            CalibrationTaps.errorMs(clock.songTimeMs(eventNanos)!!)!!
        }
        errors.zip(lateMs).forEach { (error, late) -> assertEquals(late, error, 1e-3) }
        assertEquals(-30, CalibrationTaps.suggestionMs(errors))
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
    fun pictureStepNotesFallOnTheClicks() {
        val starts = SampleCharts.CALIBRATION.notes.map { it.startMs.toDouble() }
        starts.forEachIndexed { k, at -> assertEquals(ClickTrack.FIRST_CLICK_MS + k * ClickTrack.PERIOD_MS, at, 1e-9) }
        assertTrue("Half an hour of clicks", starts.size * ClickTrack.PERIOD_MS >= 30 * 60_000)
    }
}
