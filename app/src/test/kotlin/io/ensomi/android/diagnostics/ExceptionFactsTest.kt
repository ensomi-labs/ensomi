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
}
