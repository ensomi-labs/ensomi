package io.ensomi.android.diagnostics

import org.junit.Assert.*
import org.junit.Test

class ExceptionFactsTest {
    @Test fun keepsFiveCauseClassesAndRedactsMessagesWithoutLosingDottedFrames() {
        var failure: Throwable = java.io.IOException("private Lumen Song")
        repeat(6) { failure = IllegalStateException("private file /data/Lumen.mp3", failure) }
        failure.stackTrace = arrayOf(StackTraceElement("io.ensomi.android.json.Json", "encode", "Json.kt", 12))
        val facts = exceptionContext(failure).mapValues { (key, value) -> Redact.detail(key, value) }
        assertEquals(5, facts.keys.count { it.matches(Regex("cause[0-9]+Class")) })
        assertEquals("java.lang.IllegalStateException", facts["cause4Class"])
        assertEquals("<redacted>", facts["cause0Message"])
        assertEquals("io.ensomi.android.json.Json.encode:12", facts["frame0"])
        assertFalse(facts.values.any { "Lumen" in it })
    }
    @Test fun journalKeepsAllFifteenFramesAndFiveCauses() {
        var failure: Throwable = IllegalStateException("redacted")
        repeat(4) { failure = IllegalStateException("redacted", failure) }
        failure.stackTrace = Array(15) { StackTraceElement("io.ensomi.android.json.Json", "encode", "Json.kt", it) }
        val dir = java.nio.file.Files.createTempDirectory("exception-journal").toFile()
        try {
            val journal = JournalEngine(FileJournalSink(dir), "test-run", { 1 }, { 1 })
            journal.record("uncaught_exception", "crash", exceptionContext(failure), true)
            val facts = journal.snapshot(1).single()["ctx"] as Map<*, *>
            assertEquals(27, facts.size)
            assertEquals("java.lang.IllegalStateException", facts["cause4Class"])
            assertEquals("io.ensomi.android.json.Json.encode:14", facts["frame14"])
        } finally { dir.deleteRecursively() }
    }

}
