package io.ensomi.android.diagnostics

import org.junit.Assert.*
import org.junit.Test

class ExitRecordsTest {
    private val run = JournalRun("0123456789abcdef", 42, 1000, 2000)
    @Test fun matchesOnlyTheSamePidWithinInclusiveClockEdges() {
        assertEquals(run.id, matchExit(42, 1000, null, listOf(run)))
        assertEquals(run.id, matchExit(42, 602000, null, listOf(run)))
        assertNull(matchExit(42, 999, null, listOf(run)))
        assertNull(matchExit(42, 602001, null, listOf(run)))
        assertNull(matchExit(43, 1500, null, listOf(run)))
        assertNull(matchExit(42, 1500, null, emptyList()))
        assertNull(matchExit(42, 1500, null, listOf(run.copy(last = 999))))
    }
    @Test fun pidReuseIsNotGuessedAndSummaryDisambiguates() {
        val reused = run.copy(id = "fedcba9876543210", start = 1800, last = 3000)
        assertNull(matchExit(42, 1900, null, listOf(run, reused)))
        assertEquals(run.id, matchExit(42, 1900, "e1|01234567|start", listOf(run, reused)))
        assertEquals(reused.id, matchExit(42, 1900, "e1|fedcba9876543210|start", listOf(run, reused)))
        assertNull(matchExit(42, 1900, "e1|deadbeef|start", listOf(run, reused)))
        assertNull(matchExit(43, 1900, "e1|01234567|start", listOf(run)))
        assertEquals(run.id, matchExit(42, 999, "e1|01234567|start", listOf(run)))
        assertEquals(run.id, matchExit(42, 9_000_000, "e1|01234567|model_open", listOf(run)))
    }
    @Test fun headerSuppliesPidAndProcessStartAndLastEventSuppliesEnd() {
        val rows = parseLines("""{"run":"a","t":1100,"ev":"run","ph":"start","ctx":{"pid":"42","startWall":"1000"}}
{"run":"a","t":1200,"ev":"uncaught_exception","ph":"crash","ctx":{}}
{"run":"b","t":1400,"ev":"install","ph":"begin","ctx":{}}
""")
        assertEquals(listOf(JournalRun("a", 42, 1000, 1200)), journalRuns(rows))
    }
    @Test fun namesIncludeFutureReasonsAndSignals() {
        assertEquals("REASON_CRASH", reasonName(4))
        assertEquals("REASON_CRASH_NATIVE", reasonName(5))
        assertEquals("REASON_ANR", reasonName(6))
        assertEquals("REASON_MEMORY_LIMITER", reasonName(17))
        assertEquals("REASON_ANOMALY", reasonName(18))
        assertEquals("REASON_99", reasonName(99))
        assertEquals("SIGABRT", signalName(6))
        assertEquals("SIGILL", signalName(4))
        assertEquals("SIGSEGV", signalName(11))
        assertNull(signalName(0))
    }
    @Test fun badTimestampRowsAreDropped() {
        val valid = mapOf<String, Any?>("timestamp" to 123L, "pid" to 42)
        assertEquals(listOf(valid), validExitRows(listOf(mapOf("pid" to 43), mapOf("timestamp" to "bad"), valid)))
    }

}
