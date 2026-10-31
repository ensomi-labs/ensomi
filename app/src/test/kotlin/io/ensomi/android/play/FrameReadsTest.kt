package io.ensomi.android.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameReadsTest {
    @Test
    fun aListHoldsUntilItsTimeRunsOut() {
        val window = VisibleWindow()
        assertTrue(window.mustAsk(1_000.0, 459.0))
        window.asked(1_000.0, 459.0)
        assertFalse(window.mustAsk(1_000.0, 459.0))
        assertFalse(window.mustAsk(1_000.0 + VisibleWindow.REUSE_MS, 459.0))
        assertTrue(window.mustAsk(1_000.0 + VisibleWindow.REUSE_MS + 1, 459.0))
    }

    @Test
    fun anInputOrAnEventMakesANewList() {
        val window = VisibleWindow()
        window.asked(1_000.0, 459.0)
        window.invalidate()
        assertTrue(window.mustAsk(1_000.0, 459.0))
        window.asked(1_000.0, 459.0)
        assertFalse(window.mustAsk(1_008.0, 459.0))
    }

    @Test
    fun aRestartOrAnotherScrollTimeMakesANewList() {
        val window = VisibleWindow()
        window.asked(5_000.0, 459.0)
        assertTrue(window.mustAsk(-2_000.0, 459.0))
        assertTrue(window.mustAsk(5_000.0, 500.0))
    }

    @Test
    fun aListReachesAsFarAsItsLastFrameNeeds() {
        val window = VisibleWindow()
        val lastFrame = 1_000.0 + VisibleWindow.REUSE_MS
        assertEquals(lastFrame + 459.0 + PlayController.VISIBLE_AFTER_MS, window.upperMs(1_000.0, 459.0), 1e-9)
        assertEquals(1_000.0 - PlayController.VISIBLE_BEFORE_MS, window.lowerMs(1_000.0), 1e-9)
    }

    @Test
    fun aDarkLaneIsNotAskedUntilItsNextInput() {
        val lanes = LaneAsking()
        assertFalse(lanes.mustAsk(2))
        lanes.touched(2)
        assertTrue(lanes.mustAsk(2))
        lanes.answered(2, isLit = true)
        assertTrue(lanes.mustAsk(2))
        lanes.answered(2, isLit = false)
        assertFalse(lanes.mustAsk(2))
        assertFalse(lanes.mustAsk(1))
    }

    @Test
    fun aRestartLeavesEveryLaneDark() {
        val lanes = LaneAsking()
        lanes.touched(0)
        lanes.answered(1, isLit = true)
        lanes.reset()
        assertFalse(lanes.mustAsk(0))
        assertFalse(lanes.mustAsk(1))
    }

    /** The runtime's rule as `gameplay::y_position` has it, 18 short of the top. */
    private fun runtimeY(objectMs: Double, renderMs: Double, scrollTimeMs: Double, receptorY: Double): Double =
        receptorY - (objectMs - renderMs) / scrollTimeMs * (receptorY - 18.0)

    @Test
    fun theLineIsTheRuntimesAnswerAtAnyTime() {
        val line = TapLine(::runtimeY)
        for (renderMs in listOf(-1_865.0, 0.0, 3_141.59, 157_313.07)) {
            for (ahead in listOf(-700.0, 0.0, 1.0, 229.5, 459.0, 709.0)) {
                val expected = runtimeY(renderMs + ahead, renderMs, 459.0, 1_400.0)
                assertEquals(expected, line.y(renderMs + ahead, renderMs, 459.0, 1_400.0), 1e-6)
            }
        }
    }

    @Test
    fun theLineAsksAgainOnlyWhenTheScrollTimeOrTheFallChanges() {
        var asked = 0
        val line = TapLine { objectMs, renderMs, scrollTimeMs, receptorY ->
            asked++
            runtimeY(objectMs, renderMs, scrollTimeMs, receptorY)
        }
        line.y(1_000.0, 900.0, 459.0, 1_400.0)
        val firstFit = asked
        line.y(2_000.0, 1_900.0, 459.0, 1_400.0)
        line.y(3_000.0, 2_950.0, 459.0, 1_400.0)
        assertEquals(firstFit, asked)
        assertEquals(runtimeY(1_000.0, 900.0, 300.0, 1_400.0), line.y(1_000.0, 900.0, 300.0, 1_400.0), 1e-6)
        assertEquals(firstFit * 2, asked)
        assertEquals(runtimeY(1_000.0, 900.0, 300.0, 900.0), line.y(1_000.0, 900.0, 300.0, 900.0), 1e-6)
        assertEquals(firstFit * 3, asked)
    }
}
