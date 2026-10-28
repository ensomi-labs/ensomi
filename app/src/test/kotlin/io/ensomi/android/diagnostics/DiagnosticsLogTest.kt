package io.ensomi.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogTest {
    @Test
    fun shortJsonIsOneLine() {
        assertEquals(listOf("{\"a\":1}"), DiagnosticsLog.lines("{\"a\":1}"))
    }

    @Test
    fun longJsonSplitsIntoNumberedPartsThatRejoin() {
        val json = "{\"text\":\"" + "é×😀a".repeat(400) + "\"}"
        val lines = DiagnosticsLog.lines(json, maxLineBytes = 500)
        assertTrue(lines.size > 1)
        val prefix = Regex("""part (\d+)/(\d+) """)
        val pieces = lines.mapIndexed { index, line ->
            val match = prefix.find(line)!!
            assertEquals(0, match.range.first)
            assertEquals(index + 1, match.groupValues[1].toInt())
            assertEquals(lines.size, match.groupValues[2].toInt())
            assertTrue(line.toByteArray(Charsets.UTF_8).size <= 500)
            line.substring(match.range.last + 1)
        }
        assertEquals(json, pieces.joinToString(""))
    }
}
