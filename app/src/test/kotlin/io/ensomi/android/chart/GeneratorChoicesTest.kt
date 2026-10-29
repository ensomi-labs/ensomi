package io.ensomi.android.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratorChoicesTest {
    private val gib = 1L shl 30

    @Test
    fun lowMemoryBelowSevenGibibytes() {
        // The 4 GB emulator, a 6 GB phone: on. A phone sold with 8 GB reports a little over 7 GiB: off.
        assertTrue(GeneratorChoices.lowMemory(3_900_000_000L, lowRamDevice = false))
        assertTrue(GeneratorChoices.lowMemory(5 * gib + gib / 2, lowRamDevice = false))
        assertFalse(GeneratorChoices.lowMemory(7 * gib + gib / 3, lowRamDevice = false))
        assertTrue("A low-RAM device whatever its size", GeneratorChoices.lowMemory(12 * gib, lowRamDevice = true))
    }

    @Test
    fun theRefusalNamesItsCodeOnce() {
        assertEquals(
            "star_out_of_range: star must be in [2, 6)",
            GeneratorChoices.refusal("star_out_of_range", "star_out_of_range: star must be in [2, 6)"),
        )
        assertEquals(
            "unsupported_control: sections overlap",
            GeneratorChoices.refusal("unsupported_control", "sections overlap"),
        )
    }
}
