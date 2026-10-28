package io.ensomi.android.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleChartsTest {
    @Test
    fun previewChartIsOneTheRuntimeAccepts() {
        val chart = SampleCharts.PREVIEW
        // Sorted by start, then lane; no note before the previous one of its lane has ended; all inside the song.
        assertEquals(chart.notes.sortedWith(compareBy({ it.startMs }, { it.lane })), chart.notes)
        assertNull(SampleCharts.firstOverlap(chart))
        assertTrue(chart.notes.all { (it.endMs ?: it.startMs) <= chart.durationMs && it.lane in 0..3 })
        assertTrue("Holds and chords are in it", chart.notes.any { it.endMs != null })
    }

    @Test
    fun calibrationChartIsAClickEveryHalfSecond() {
        val starts = SampleCharts.CALIBRATION.notes.map { it.startMs }
        assertEquals(1_000, starts.first())
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a == 500 })
        assertNull(SampleCharts.firstOverlap(SampleCharts.CALIBRATION))
    }

    @Test
    fun autoplayReleasesEachLaneAfterPressingIt() {
        val inputs = Autoplay.inputs(SampleCharts.PREVIEW)
        assertEquals(inputs.sortedBy { it.songMs }, inputs)
        val pressed = BooleanArray(4)
        for (input in inputs) {
            if (input.press) pressed[input.lane] = true else pressed[input.lane] = false
        }
        assertTrue("Every lane ends released", pressed.none { it })
        val skipped = SampleCharts.PREVIEW.notes.size - inputs.count { it.press }
        assertTrue("A few notes are left alone, so the preview shows misses", skipped in 1..10)
    }
}
