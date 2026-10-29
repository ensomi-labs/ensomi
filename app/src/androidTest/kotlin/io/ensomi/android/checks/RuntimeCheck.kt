package io.ensomi.android.checks

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ensomi.android.diagnostics.RuntimeProbe
import io.ensomi.android.play.PlayController
import io.ensomi.android.play.PlayfieldGeometry
import io.ensomi.android.play.SampleCharts
import io.ensomi.runtime.GameplayFeedback
import io.ensomi.runtime.GameplaySession
import io.ensomi.runtime.GeneratorException
import io.ensomi.runtime.Judge
import io.ensomi.runtime.Ruleset
import io.ensomi.runtime.RuntimeWorker
import io.ensomi.runtime.noteGeometry
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The runtime on the phone without a model: its native library loads through the AAR's Kotlin API, the calls that need
 * no model answer, UniFFI's checksum functions read back, and what one runtime call costs through JNA (plan.md, "One
 * call per frame").
 */
@RunWith(AndroidJUnit4::class)
class RuntimeCheck {
    @Test
    fun loadsAndAnswersWithoutAModel() = runBlocking {
        val facts = RuntimeProbe.check()
        val opened = RuntimeWorker().use { worker ->
            try {
                worker.withGenerator(PhoneCheck.context, File(PhoneCheck.context.cacheDir, "no-model-here")) { "opened" }
            } catch (refused: GeneratorException.Model) {
                "refused: ${refused.message}"
            }
        }
        PhoneCheck.report(
            "runtime-load",
            linkedMapOf(
                "loaded" to facts.loaded,
                "initMs" to facts.initMillis,
                "noticeFiles" to facts.noticeFiles,
                "packageCheck" to facts.checkPackage,
                "missingModelOpen" to opened,
                "error" to facts.error,
                "ok" to (facts.loaded && facts.error == null && opened.startsWith("refused")),
            ),
        )
        assertNull(facts.error, facts.error)
        assertTrue("The runtime did not load", facts.loaded)
        assertTrue("A missing model opened: $opened", opened.startsWith("refused"))
    }

    /**
     * UniFFI 0.31 compares each API checksum the bindings were made with against the native library's, and on AArch64
     * that comparison fails for values of 0x8000 and above (the function returns 16 bits and Kotlin reads a wider
     * register), so the runtime's AAR is built with the comparison omitted (its android/README.md). This reads every
     * checksum function through the bindings' own declarations, twice: the raw value as Kotlin gets it, and the low
     * 16 bits. The digest of the 16-bit values must be the same on every device with the same AAR (compare it with the
     * emulator's); raw values with high bits show the AArch64 behaviour on this phone.
     */
    @Test
    fun readsTheUniffiChecksums() = runBlocking {
        val rows = RuntimeWorker().use { worker ->
            worker.run {
                val type = Class.forName("io.ensomi.runtime.IntegrityCheckingUniffiLib")
                val instance = type.getField("INSTANCE").get(null)
                val contract = type.getMethod("ffi_ensomi_runtime_ffi_uniffi_contract_version").invoke(instance)
                val checksums = type.methods
                    .filter { it.name.startsWith("uniffi_ensomi_runtime_ffi_checksum_") && it.parameterCount == 0 }
                    .filter { Modifier.isNative(it.modifiers) }
                    .sortedBy { it.name }
                    .map { method ->
                        val first = (method.invoke(instance) as Number).toLong()
                        val second = (method.invoke(instance) as Number).toLong()
                        Checksum(method.name.removePrefix("uniffi_ensomi_runtime_ffi_checksum_"), first, second)
                    }
                (contract as Number).toInt() to checksums
            }
        }
        val (contract, checksums) = rows
        val text = checksums.joinToString("") { "${it.name}=${it.masked}\n" }
        val file = PhoneCheck.writeResult(
            "uniffi-checksums.txt",
            checksums.joinToString("") { "${it.name} raw=${it.raw} again=${it.again} low16=${it.masked}\n" },
        )
        val stable = checksums.all { it.raw == it.again }
        PhoneCheck.report(
            "uniffi-checksums",
            linkedMapOf(
                "contractVersion" to contract,
                "checksums" to checksums.size,
                "rawWithHighBits" to checksums.count { it.raw != it.masked.toLong() },
                "atLeast0x8000" to checksums.count { it.masked >= 0x8000 },
                "stableAcrossCalls" to stable,
                "low16Sha256" to PhoneCheck.sha256(text.toByteArray()),
                "file" to file,
                "ok" to (checksums.isNotEmpty() && stable && contract == BINDINGS_CONTRACT_VERSION),
            ),
        )
        assertTrue("No checksum functions found", checksums.isNotEmpty())
        assertTrue("A checksum changed between calls", stable)
        assertEquals(BINDINGS_CONTRACT_VERSION, contract)
    }

    /**
     * What a frame's runtime calls cost through JNA, on the sample chart the playfield preview plays: each call alone,
     * and a frame's worth (advance, the visible objects with one `noteGeometry` each, four lane lights, the batches,
     * the judgement word, the score), at 1 ms steps through the chart. Above about 1 ms per frame, plan.md's one call
     * per frame becomes worth asking of the runtime.
     */
    @Test
    fun measuresTheCostOfRuntimeCalls() = runBlocking {
        val result = RuntimeWorker().use { worker ->
            worker.run {
                val chart = SampleCharts.PREVIEW
                GameplaySession(chart.toRuntime(), Ruleset(Judge.C)).use { session ->
                    GameplayFeedback().use { feedback ->
                        measure(session, feedback, chart.durationMs)
                    }
                }
            }
        }
        PhoneCheck.report("runtime-call-cost", result + ("ok" to true))
    }

    private fun measure(session: GameplaySession, feedback: GameplayFeedback, durationMs: Int): Map<String, Any?> {
        val frames = durationMs - 200
        val frameNanos = DoubleArray(frames)
        val visibleCounts = IntArray(frames)
        var advanceNanos = 0L
        var visibleNanos = 0L
        var geometryNanos = 0L
        var geometryCalls = 0
        var lightNanos = 0L
        var scoreNanos = 0L
        val scroll = 459.0
        // Warm up JNA's reflection and the JIT first, as a game's first seconds do.
        repeat(500) { t ->
            session.visibleObjects(t - PlayController.VISIBLE_BEFORE_MS, t + scroll)
            session.score()
        }
        for (i in 0 until frames) {
            val t = 100.0 + i
            val start = System.nanoTime()
            session.advance(t - PlayController.INPUT_ALLOWANCE_MS)
            val a = System.nanoTime()
            val visible = session.visibleObjects(t - PlayController.VISIBLE_BEFORE_MS, t + scroll + PlayController.VISIBLE_AFTER_MS)
            val b = System.nanoTime()
            for (obj in visible) noteGeometry(obj, t, scroll, 1000.0)
            val c = System.nanoTime()
            feedback.advanceJudgementTime(t)
            for (lane in 0 until PlayfieldGeometry.LANES) feedback.laneBrightness(lane.toUInt(), t)
            feedback.judgementEventBatches()
            feedback.judgementPresentation(t)
            val d = System.nanoTime()
            session.score()
            val e = System.nanoTime()
            frameNanos[i] = (e - start).toDouble()
            visibleCounts[i] = visible.size
            advanceNanos += a - start
            visibleNanos += b - a
            geometryNanos += c - b
            geometryCalls += visible.size
            lightNanos += d - c
            scoreNanos += e - d
        }
        val frameMs = DoubleArray(frames) { frameNanos[it] / 1e6 }
        return linkedMapOf<String, Any?>(
            "frames" to frames,
            "visibleMax" to visibleCounts.max(),
            "advanceUs" to PhoneCheck.round(advanceNanos / 1e3 / frames, 1),
            "visibleObjectsUs" to PhoneCheck.round(visibleNanos / 1e3 / frames, 1),
            "noteGeometryUsEach" to PhoneCheck.round(geometryNanos / 1e3 / maxOf(1, geometryCalls), 1),
            "feedbackUs" to PhoneCheck.round(lightNanos / 1e3 / frames, 1),
            "scoreUs" to PhoneCheck.round(scoreNanos / 1e3 / frames, 1),
            "frameMeanMs" to PhoneCheck.round(Stats.mean(frameMs)),
        ) + Stats.summary("frameMs", frameMs) + ("underOneMsPerFrameAtP99" to (Stats.quantile(frameMs.sortedArray(), 0.99) < 1.0))
    }

    private class Checksum(val name: String, val raw: Long, val again: Long) {
        val masked: Int get() = (raw and 0xFFFF).toInt()
    }

    private companion object {
        /** The UniFFI contract version the AAR's bindings expect (`uniffiCheckContractApiVersion`). */
        const val BINDINGS_CONTRACT_VERSION = 30
    }
}
