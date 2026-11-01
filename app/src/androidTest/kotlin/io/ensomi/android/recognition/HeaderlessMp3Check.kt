package io.ensomi.android.recognition

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.util.Log
import io.ensomi.android.json.Json
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in decoder check with two generated MP3 fixtures: full and cut at a complete frame, neither with Xing. */
@RunWith(AndroidJUnit4::class)
class HeaderlessMp3Check {
    @Test fun aHeaderlessPartialHasNoIndependentFullLengthToCompare() {
        val args = InstrumentationRegistry.getArguments()
        val full = args.getString("headerlessFull")
        val partial = args.getString("headerlessPartial")
        assumeNotNull(full, partial)
        val probe = AudioProbe(ApplicationProvider.getApplicationContext<Context>())
        val results = listOf(full!!, partial!!).map { path ->
            val disk = File(path)
            val file = FileFacts(path, disk.name, disk.length(), disk.lastModified())
            val (facts, _) = probe.probe(file)
            assertTrue(facts.failure, facts.decoded)
            assertEquals(Verdict.READY, CandidateRules.judgeDecoded(file, facts))
            facts
        }
        assertTrue(results[0].decodedMs > results[1].decodedMs * 1.8)
        Log.i("EnsomiPhoneCheck", Json.encode(mapOf("check" to "headerless_partial", "full_ms" to results[0].decodedMs,
            "partial_ms" to results[1].decodedMs, "partial_declared_ms" to results[1].declaredMs,
            "outcome" to "no_independent_full_length")))
    }
}
