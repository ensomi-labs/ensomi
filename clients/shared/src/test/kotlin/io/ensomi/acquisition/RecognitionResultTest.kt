package io.ensomi.acquisition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecognitionResultTest {
    @Test
    fun `a raw position is not usable while its semantics are unknown`() {
        val result = RecognitionResult(
            songId = 31_861_269,
            rawPositionValue = -180,
            positionSemantics = PositionSemantics.UNKNOWN,
        )

        assertEquals(-180, result.rawPositionValue)
        assertFalse(
            result.positionIsUsable,
            "the report's rule: a field named startTime is not a position until the experiment says which anchor it is",
        )
    }

    @Test
    fun `a measured anchor makes the position usable`() {
        val result = RecognitionResult(
            songId = 31_861_269,
            rawPositionValue = 2_360,
            positionSemantics = PositionSemantics.CAPTURE_END,
            evidence = EvidenceLevel.DEVICE_CONFIRMED,
        )

        assertTrue(result.positionIsUsable)
    }

    @Test
    fun `the ladder runs from local audio to manual`() {
        val levels = AcquisitionLevel.entries.sortedBy { it.depth }

        assertEquals(AcquisitionLevel.EXISTING_LOCAL_AUDIO, levels.first())
        assertEquals(AcquisitionLevel.MANUAL, levels.last())
        assertEquals(levels.size, levels.map { it.depth }.distinct().size)
    }
}
