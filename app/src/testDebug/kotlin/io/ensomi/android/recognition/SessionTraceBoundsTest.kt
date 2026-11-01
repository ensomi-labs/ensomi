package io.ensomi.android.recognition

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SessionTraceBoundsTest {
    @Test fun anUnwritableTraceCannotEndTheSession() {
        val directory = Files.createTempDirectory("recognition-trace-error").toFile()
        try {
            val trace = SessionTrace(directory, "test") { 1L } // Opening a directory for output fails.
            trace.log("session.start", emptyMap())
            trace.close()
        } finally { directory.deleteRecursively() }
    }

    @Test fun traceIsBoundedAndLateCallbacksAfterCloseAreHarmless() {
        val directory = Files.createTempDirectory("recognition-trace").toFile()
        try {
            val file = directory.resolve("session.jsonl")
            val trace = SessionTrace(file, "test") { 1L }
            repeat(2000) { trace.log("level", mapOf("value" to "a".repeat(1000))) }
            assertTrue(file.length() <= 1_048_576)
            assertTrue(file.length() > 1_000_000)
            trace.close()
            val bytes = file.length()
            trace.log("late", emptyMap())
            trace.close()
            assertEquals(bytes, file.length())
        } finally { directory.deleteRecursively() }
    }
}
