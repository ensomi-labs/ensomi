package io.ensomi.android.checks

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ensomi.android.diagnostics.RuntimeProbe
import io.ensomi.android.play.NoteSpec
import io.ensomi.android.play.PlayChart
import io.ensomi.android.play.PlayController
import io.ensomi.android.play.PlayfieldGeometry
import io.ensomi.android.play.SampleCharts
import io.ensomi.android.play.TapLine
import io.ensomi.runtime.GameplayFeedback
import io.ensomi.runtime.GameplaySession
import io.ensomi.runtime.GeneratorException
import io.ensomi.runtime.Geometry
import io.ensomi.runtime.InputEvent
import io.ensomi.runtime.Judge
import io.ensomi.runtime.Phase
import io.ensomi.runtime.Ruleset
import io.ensomi.runtime.RuntimeWorker
import io.ensomi.runtime.VisibleObject
import io.ensomi.runtime.VisibleState
import io.ensomi.runtime.noteGeometry
import io.ensomi.runtime.noteYPosition
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The runtime on the phone without a model: its native library loads through the AAR's Kotlin API, the calls that need
 * no model answer, UniFFI's checksum functions read back, and what one runtime call costs through JNA (plan.md, "One
 * call per frame").
 */
@RunWith(AndroidJUnit4::class)
// By name: the load comes first, so its time is the native library's first load in this process.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
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

    /** The playfield's line for taps ([TapLine]) against the runtime's own answer, at the speeds and lengths a game uses. */
    @Test
    fun theTapLineIsTheRuntimesGeometry() {
        for (scrollTimeMs in listOf(115.0, 459.0, 1_148.5)) {
            for (fallLength in listOf(300.0, 1_400.0, 3_500.0)) {
                val line = TapLine(::noteYPosition)
                for (state in listOf(VisibleState.WAITING, VisibleState.MISSED_BUT_VISIBLE)) {
                    for (renderMs in listOf(-1_865.25, 0.0, 12_345.678, 157_313.07)) {
                        for (ahead in listOf(-700.0, -1.5, 0.0, 33.3, scrollTimeMs, scrollTimeMs + 250.0)) {
                            val note = VisibleObject(0u, 0u, renderMs + ahead, null, state)
                            val geometry = noteGeometry(note, renderMs, scrollTimeMs, fallLength) as Geometry.Tap
                            val y = line.y(note.startTimeMs, renderMs, scrollTimeMs, fallLength)
                            assertEquals(geometry.y, y, 1e-6)
                        }
                    }
                }
            }
        }
    }

    /**
     * What each runtime path costs the first time a process takes it, in the order a game first takes them: a game's
     * rare long frames come early and do not come back. Run on its own, so that nothing has warmed the paths:
     * `-e class io.ensomi.android.checks.RuntimeCheck#firstCallsOfAGame`.
     */
    @Test
    fun firstCallsOfAGame() = runBlocking {
        val result = RuntimeWorker().use { worker -> worker.run { timeFirstCalls() } }
        PhoneCheck.report("runtime-first-calls", result + ("ok" to true))
    }

    private fun timeFirstCalls(): Map<String, Any?> {
        val times = linkedMapOf<String, Any?>()
        fun <T> timed(name: String, block: () -> T): T {
            val start = System.nanoTime()
            val value = block()
            times[name] = PhoneCheck.round((System.nanoTime() - start) / 1e6)
            return value
        }
        val notes = listOf(NoteSpec(0, 1_000), NoteSpec(1, 2_000), NoteSpec(2, 3_000), NoteSpec(3, 4_000, 4_400), NoteSpec(0, 9_000))
        val chart = PlayChart("first calls", 10_000, notes).toRuntime()
        val session = timed("session") { GameplaySession(chart, Ruleset(Judge.C)) }
        val feedback = timed("feedback") { GameplayFeedback() }
        val visible = timed("visibleObjects") { session.visibleObjects(0.0, 10_000.0) }
        timed("advance") { session.advance(0.0) }
        timed("score") { session.score() }
        timed("noteGeometryTap") { noteGeometry(visible.first(), 0.0, 459.0, 1000.0) }
        timed("noteGeometryHold") { noteGeometry(visible.first { it.endTimeMs != null }, 0.0, 459.0, 1000.0) }
        timed("noteYPosition") { noteYPosition(1_000.0, 0.0, 459.0, 1000.0) }
        timed("laneBrightnessDark") { feedback.laneBrightness(0u, 0.0) }
        timed("presentationNone") { feedback.judgementPresentation(0.0) }
        timed("batchesNone") { feedback.judgementEventBatches() }
        fun tap(name: String, lane: UInt, atMs: Double, sequence: ULong) {
            timed("$name.recordInput") { feedback.recordInput(lane, Phase.PRESS, sequence, atMs) }
            val events = timed("$name.input") { session.input(InputEvent(lane, Phase.PRESS, atMs)) }
            timed("$name.recordEvents") { feedback.recordJudgementEvents(events, atMs) }
            timed("$name.score") { session.score() }
            timed("$name.batches") { feedback.judgementEventBatches() }
            timed("$name.presentation") { feedback.judgementPresentation(atMs) }
            timed("$name.brightness") { feedback.laneBrightness(lane, atMs) }
            timed("$name.release") { session.input(InputEvent(lane, Phase.RELEASE, atMs + 50)) }
        }
        tap("perfect", 0u, 1_000.0, 1u)
        tap("good", 1u, 2_080.0, 2u)
        val misses = timed("miss.advance") { session.advance(3_400.0) }
        timed("miss.recordEvents") { feedback.recordJudgementEvents(misses, 3_400.0) }
        timed("miss.batches") { feedback.judgementEventBatches() }
        timed("miss.presentation") { feedback.judgementPresentation(3_400.0) }
        tap("hold", 3u, 4_000.0, 3u)
        timed("hold.visible") { session.visibleObjects(3_500.0, 5_000.0) }
        timed("isResolved") { session.isResolved() }
        timed("close") {
            session.close()
            feedback.close()
        }
        return times
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
        val worst = Array(5) { Worst() }
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
            worst[0].see(a - start, i)
            worst[1].see(b - a, i)
            worst[2].see(c - b, i)
            worst[3].see(d - c, i)
            worst[4].see(e - d, i)
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
            "worstMsAndFrame" to listOf("advance", "visible", "geometry", "feedback", "score").mapIndexed { index, name ->
                "$name ${PhoneCheck.round(worst[index].nanos / 1e6)} at ${worst[index].frame}"
            },
            "frameMeanMs" to PhoneCheck.round(Stats.mean(frameMs)),
        ) + Stats.summary("frameMs", frameMs) + ("underOneMsPerFrameAtP99" to (Stats.quantile(frameMs.sortedArray(), 0.99) < 1.0))
    }

    private class Worst {
        var nanos = 0L
        var frame = -1

        fun see(value: Long, at: Int) {
            if (value > nanos) {
                nanos = value
                frame = at
            }
        }
    }

    private class Checksum(val name: String, val raw: Long, val again: Long) {
        val masked: Int get() = (raw and 0xFFFF).toInt()
    }

    private companion object {
        /** The UniFFI contract version the AAR's bindings expect (`uniffiCheckContractApiVersion`). */
        const val BINDINGS_CONTRACT_VERSION = 30
    }
}
