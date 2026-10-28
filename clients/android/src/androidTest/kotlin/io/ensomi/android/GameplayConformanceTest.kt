package io.ensomi.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ensomi_runtime_ffi.Chart
import uniffi.ensomi_runtime_ffi.ChartObject
import uniffi.ensomi_runtime_ffi.GameplaySession
import uniffi.ensomi_runtime_ffi.InputEvent
import uniffi.ensomi_runtime_ffi.Judge
import uniffi.ensomi_runtime_ffi.Phase
import uniffi.ensomi_runtime_ffi.Ruleset
import uniffi.ensomi_runtime_ffi.Tier

/**
 * The runtime's `conformance/gameplay/early_press_ignored.json`, run against the packaged core on a
 * device. It is the same case the Windows client runs, and the same one the Apple client derives its
 * engine from: a press 201 ms early is ignored, and the miss is decided at the end of the window rather
 * than at the advance that observed it.
 *
 * The chart is written out here rather than read from the fixture, as on Windows, so the check needs no
 * JSON reader in the test module.
 */
@RunWith(AndroidJUnit4::class)
class GameplayConformanceTest {
    @Test
    fun aPressLongBeforeTheNoteIsIgnoredAndMissesAtTheWindowEnd() {
        val session = GameplaySession(
            Chart(durationMs = 2000u, objects = listOf(ChartObject(lane = 0u, startMs = 1000u, endMs = null))),
            Ruleset(judge = Judge.C),
        )

        val events = mutableListOf<uniffi.ensomi_runtime_ffi.JudgementEvent>()
        events += session.input(InputEvent(lane = 0u, phase = Phase.PRESS, timeMs = 799.0))
        events += session.advance(1201.0)
        events += session.advance(1_000_000.0)

        assertEquals("exactly one judgement", 1, events.size)
        assertEquals(Tier.M, events[0].tier)
        assertEquals(1200.0, events[0].timeMs, 1e-9)

        val score = session.score()
        assertEquals(1u, score.tierCounts.m)
        assertEquals(0.0, score.accuracy, 1e-9)
        assertEquals(0u, score.combo)
        assertEquals(0u, session.lateInputs())
    }
}
