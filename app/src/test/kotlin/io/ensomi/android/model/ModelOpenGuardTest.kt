package io.ensomi.android.model

import io.ensomi.android.chart.ChartCapabilities
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
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
    private fun guard(reason: Int? = null, afterMarker: () -> Unit = {}) =
        ModelOpenGuard(root, { 123 }, events, afterMarker = afterMarker, exitReason = { _, _ -> reason })
    @After fun clean() { root.deleteRecursively() }

    @Test fun markerBlocksEvenWithCapabilitiesAndNamesInference() = runBlocking {
        val first = guard()
        first.read(key) { capabilities }
        val second = guard()
        assertNull(second.read(key) { error("Must not open") })
        assertEquals(ModelOpenState.Blocked("model_prepare"), second.state.value)
        assertTrue(marker.exists())
    }
    @Test fun openKeepsMarkerUntilFirstInferenceAndSecondMakerUsesSameGuard() = runBlocking {
        val first = guard()
        assertEquals(capabilities, first.read(key) { capabilities })
        assertTrue(marker.exists())
        assertEquals(capabilities, first.read(key) { error("Already open") })
        first.inference("model_prepare") { assertTrue(marker.exists()); true }
        assertFalse(marker.exists())
        assertEquals(capabilities, guard().read(key) { error("Must use cache") })
        assertEquals(listOf("model_open" to "begin", "model_open" to "end", "model_prepare" to "begin", "model_prepare" to "end"), recorded)
    }
    @Test fun inferenceCrashLeavesMarkerAndExplicitRetryRecovers() = runBlocking {
        val first = guard()
        first.read(key) { capabilities }
        try { first.inference("model_chart") { throw OutOfMemoryError("test") } } catch (_: OutOfMemoryError) { }
        val next = guard()
        assertNull(next.read(key) { error("Blocked") })
        assertEquals(ModelOpenState.Blocked("model_chart"), next.state.value)
        next.retry()
        assertEquals(capabilities, next.read(key) { capabilities })
        next.inference("model_chart") { true }
        assertFalse(marker.exists())
    }
    @Test fun userRequestedOrStoppedRetriesButUnknownReasonBlocks() = runBlocking {
        for (reason in listOf(10, 11, 5, null)) {
            root.listFiles().orEmpty().forEach { it.delete() }
            guard().read(key) { capabilities }
            var opened = false
            val next = guard(reason)
            next.read(key) { opened = true; capabilities }
            assertEquals(reason == 10 || reason == 11, opened)
        }
    }
    @Test fun appUpdateOpensAgainAndCancelledInferenceDoesNotCompleteGuard() = runBlocking {
        val first = guard()
        first.read(key) { capabilities }
        first.inference("model_prepare") { false }
        assertTrue(marker.exists())
        first.inference("model_chart") { true }
        var opens = 0
        guard().read(key.copy(lastUpdateTime = 5678)) { opens++; capabilities }
        assertEquals(1, opens)
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
        Unit
    }
}
