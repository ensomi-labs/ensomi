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
    private fun guard(afterMarker: () -> Unit = {}) = ModelOpenGuard(root, { 123 }, events, afterMarker = afterMarker)

    @After fun clean() { root.deleteRecursively() }

    @Test fun markerAtStartBlocksEvenWithStoredCapabilities() = runBlocking {
        guard().read(key) { capabilities }
        marker.writeText("a previous process died")
        val guard = guard()
        assertEquals(ModelOpenState.Blocked, guard.state.value)
        assertNull(guard.read(key) { error("Must not open") })
        assertTrue(marker.exists())
        assertTrue("model_open" to "blocked" in recorded)
    }

    @Test fun tryAgainClearsMarkerAndSuccessStoresCapabilities() = runBlocking {
        marker.writeText("pending")
        val guard = guard()
        guard.retry()
        var opens = 0
        assertEquals(capabilities, guard.read(key) { opens++; capabilities })
        assertEquals(1, opens)
        assertFalse(marker.exists())
        assertEquals(ModelOpenState.Ready, guard.state.value)
        assertEquals(capabilities, guard().read(key) { error("Must use cache") })
    }

    @Test fun storedCapabilitiesAvoidOpenAtStartButAppUpdateOpensOnce() = runBlocking {
        guard().read(key) { capabilities }
        assertEquals(capabilities, guard().read(key) { error("Must use cache") })
        var opens = 0
        val next = key.copy(lastUpdateTime = 5678)
        guard().read(next) { opens++; capabilities }
        guard().read(next) { error("Must use updated cache") }
        assertEquals(1, opens)
    }

    @Test fun failedOpenPublishesReasonAndDeletesMarkerUntilExplicitRetry() = runBlocking {
        val guard = guard()
        assertNull(guard.read(key) { throw AssertionError("Lumen failed") })
        assertEquals(ModelOpenState.Failed("Lumen failed"), guard.state.value)
        assertFalse(marker.exists())
        assertNull(guard.read(key) { error("No automatic retry") })
        guard.retry()
        assertEquals(capabilities, guard.read(key) { capabilities })
    }

    @Test fun uniffiInternalExceptionIsAnOpenFailureAndClearsMarker() = runBlocking {
        val guard = guard()
        assertNull(guard.read(key) { throw io.ensomi.runtime.InternalException("Lumen runtime error") })
        assertEquals(ModelOpenState.Failed("Lumen runtime error"), guard.state.value)
        assertFalse(marker.exists())
    }

    @Test fun vmErrorPublishesAndRethrowsLeavingMarkerForNextStart() = runBlocking {
        val guard = guard()
        try {
            guard.read(key) { throw OutOfMemoryError("Lumen memory") }
            fail("Must rethrow")
        } catch (_: OutOfMemoryError) {
            assertTrue(guard.state.value is ModelOpenState.Failed)
            assertTrue(marker.exists())
            assertEquals(ModelOpenState.Blocked, guard().state.value)
        }
    }

    @Test fun durableMarkerPrecedesOpenAndEventsAreOrdered() = runBlocking {
        val guard = guard {
            assertTrue(marker.readText().contains("1234"))
            assertTrue(marker.readText().contains("wallTime"))
            assertTrue(marker.readText().contains("123"))
        }
        guard.read(key) {
            assertTrue(marker.exists())
            capabilities
        }
        assertEquals(listOf("model_open" to "begin", "model_open" to "end"), recorded)
        assertFalse(marker.exists())
    }
}
