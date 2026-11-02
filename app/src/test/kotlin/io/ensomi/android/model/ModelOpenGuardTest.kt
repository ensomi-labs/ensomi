package io.ensomi.android.model

import io.ensomi.android.chart.ChartCapabilities
import io.ensomi.runtime.GeneratorException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ModelOpenGuardTest {
    private val root = Files.createTempDirectory("lumen-open").toFile()
    private val key = ModelOpenKey("lumen-1", 7, 1234)
    private val capabilities = ChartCapabilities("Lumen", 2.0, 6.0, emptyList())
    private val marker get() = File(root, "model-open-pending.json")
    private val recorded = mutableListOf<Pair<String, String>>()
    private val events = InstallEvents { event, phase, _ -> recorded += event to phase }
    private fun guard(reason: Int? = null) =
        ModelOpenGuard(root, { 123 }, events, exitReason = { _, _ -> reason })
    private fun interrupted(stage: String = "model_prepare", update: Long = key.lastUpdateTime, pid: Int = 321) {
        // A dead process never reaches finally; reproduce precisely its persisted marker.
        marker.writeText("""{"key":{"revision":"lumen-1","versionCode":7,"lastUpdateTime":$update},"wallTime":100,"pid":$pid,"stage":"$stage"}""")
    }
    private fun inferenceComplete(): Boolean = kotlinx.serialization.json.Json
        .parseToJsonElement(File(root, "model-capabilities.json").readText()).jsonObject
        .get("inferenceComplete")?.jsonPrimitive?.booleanOrNull ?: false
    @After fun clean() { root.deleteRecursively() }

    @Test fun noMarkerAfterOpenOrBetweenInferencesAndOnlyFirstCallIsGuarded() = runBlocking {
        val first = guard()
        assertEquals(capabilities, first.read(key) { assertTrue(marker.exists()); capabilities })
        assertFalse(marker.exists())
        assertEquals(capabilities, first.read(key) { error("Already open") })
        // Cached preparation/play can leave the first inference pending across process lifetimes safely.
        val second = guard()
        assertEquals(capabilities, second.read(key) { error("Use stored capabilities before inference too") })
        assertFalse(marker.exists())
        assertFalse(inferenceComplete())
        second.inference("model_prepare") { assertTrue(marker.exists()); true }
        assertTrue(inferenceComplete())
        assertFalse(marker.exists())
        second.inference("model_chart") { assertFalse(marker.exists()); true }
        val third = guard()
        third.read(key) { error("Must use cache") }
        third.inference("model_prepare") { assertFalse(marker.exists()); true }
        assertEquals(listOf("model_open" to "begin", "model_open" to "end", "model_prepare" to "begin", "model_prepare" to "end"), recorded)
    }
    @Test fun cancelledFirstInferenceLeavesTheNextCallGuarded() = failedInferenceRemainsGuarded(GeneratorException.Cancelled())
    @Test fun coroutineCancellationLeavesTheNextCallGuarded() = failedInferenceRemainsGuarded(CancellationException("stop"))
    @Test fun refusedFirstChartLeavesTheNextCallGuarded() =
        failedInferenceRemainsGuarded(GeneratorException.Generation("star_out_of_range", "refused"))
    @Test fun otherFailureLeavesTheNextCallGuarded() = failedInferenceRemainsGuarded(IOException("failed"))

    private fun failedInferenceRemainsGuarded(failure: Throwable) = runBlocking {
        val first = guard()
        first.read(key) { capabilities }
        repeat(2) {
            // The second call in this process must get a marker too.
            try { first.inference(if (failure is GeneratorException.Generation) "model_chart" else "model_prepare") {
                assertTrue(marker.exists()); throw failure
            }; fail("Expected exception") } catch (caught: Throwable) { assertSame(failure, caught) }
            assertEquals(ModelOpenState.Ready, first.state.value)
            assertFalse(marker.exists())
            assertFalse(inferenceComplete())
        }
        // Persisted false survives a process restart, without reopening just to read capabilities.
        val next = guard()
        next.read(key) { error("Use cached capabilities") }
        next.inference("model_prepare") { assertTrue(marker.exists()); true }
        assertFalse(marker.exists())
        assertTrue(inferenceComplete())
        next.inference("model_chart") { assertFalse(marker.exists()) }
        val completed = guard()
        completed.read(key) { error("Must use cache") }
        completed.inference("model_prepare") { assertFalse(marker.exists()) }
    }
    @Test fun modelLoadFailureIsFailedButReturnedExceptionNeverLeavesAMarker() = runBlocking {
        val first = guard()
        first.read(key) { capabilities }
        try { first.inference("model_chart") { throw GeneratorException.Model("missing model") } }
        catch (_: GeneratorException.Model) { }
        assertTrue(first.state.value is ModelOpenState.Failed)
        assertFalse(marker.exists())
    }
    @Test fun previousProcessInferenceDeathBlocksAndExplicitRetryRecovers() = runBlocking {
        guard().read(key) { capabilities }
        interrupted("model_chart")
        val next = guard()
        assertNull(next.read(key) { error("Blocked") })
        assertEquals(ModelOpenState.Blocked("model_chart"), next.state.value)
        next.retry()
        assertEquals(capabilities, next.read(key) { capabilities })
        assertFalse(marker.exists())
        next.inference("model_chart") { true }
        assertFalse(marker.exists())
    }
    @Test fun oldKeyOrCurrentPidMarkerIsDiscardedWithoutBlocking() = runBlocking {
        for ((update, pid) in listOf(123L to 321, 1234L to 123)) {
            root.listFiles().orEmpty().forEach { it.delete() }
            guard().read(key) { capabilities }
            interrupted(update = update, pid = pid)
            var opened = false
            val next = guard()
            next.read(key) { opened = true; capabilities }
            assertTrue(opened)
            assertEquals(ModelOpenState.Ready, next.state.value)
            assertFalse(marker.exists())
            assertTrue("model_open" to "discard_marker" in recorded)
        }
    }
    @Test fun userRequestedOrStoppedRetriesButUnknownReasonBlocks() = runBlocking {
        for (reason in listOf(10, 11, 5, null)) {
            root.listFiles().orEmpty().forEach { it.delete() }
            interrupted()
            var opened = false
            val next = guard(reason)
            next.read(key) { opened = true; capabilities }
            assertEquals(reason == 10 || reason == 11, opened)
        }
    }
    @Test fun ordinaryOpenFailureClearsMarkerAndWaitsForRetry() = runBlocking {
        val first = guard()
        assertNull(first.read(key) { throw io.ensomi.runtime.InternalException("failed") })
        assertTrue(first.state.value is ModelOpenState.Failed)
        assertFalse(marker.exists())
        assertNull(first.read(key) { error("No automatic retry") })
        first.retry()
        assertEquals(capabilities, first.read(key) { capabilities })
    }
    @Test fun markerRenameSyncsDirectoryBeforeNativeWork() = runBlocking {
        var synced = false
        val first = ModelOpenGuard(root, { 123 }, syncDirectory = { synced = true }, afterMarker = {
            assertTrue(synced)
            assertTrue(marker.readText().contains("1234"))
        })
        first.read(key) { capabilities }
        synced = false
        first.inference("model_prepare") { assertTrue(synced); true }
        assertFalse(marker.exists())
    }
}
