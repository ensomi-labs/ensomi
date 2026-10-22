package io.ensomi.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import uniffi.ensomi_runtime_ffi.Chart
import uniffi.ensomi_runtime_ffi.ChartObject
import uniffi.ensomi_runtime_ffi.GameplaySession
import uniffi.ensomi_runtime_ffi.InputEvent
import uniffi.ensomi_runtime_ffi.Judge
import uniffi.ensomi_runtime_ffi.Phase
import uniffi.ensomi_runtime_ffi.Ruleset
import uniffi.ensomi_runtime_ffi.Tier

/**
 * The fixture `conformance/gameplay/early_press_ignored.json` of the runtime, written
 * out here so the check needs no JSON parser: a press 201 ms early is ignored, and the
 * miss is decided at the end of the window, not when the advance observed it.
 */
class GameplayConformanceTest {
    @Test
    fun aPressLongBeforeTheNoteIsIgnoredAndMissesAtTheWindowEnd() {
        val session = GameplaySession(
            Chart(durationMs = 2000u, objects = listOf(ChartObject(lane = 0u, startMs = 1000u, endMs = null))),
            Ruleset(judge = Judge.C),
        )

        var events = session.input(InputEvent(lane = 0u, phase = Phase.PRESS, timeMs = 799.0))
        events = events + session.advance(1201.0)
        events = events + session.advance(1_000_000.0)

        assertEquals(1, events.size, "exactly one judgement")
        assertEquals(Tier.M, events[0].tier)
        assertEquals(1200.0, events[0].timeMs, 1e-9)

        val score = session.score()
        assertEquals(1u, score.tierCounts.m)
        assertEquals(0.0, score.accuracy, 1e-9)
        assertEquals(0u, score.combo)
        assertEquals(0u, session.lateInputs())
    }
}
