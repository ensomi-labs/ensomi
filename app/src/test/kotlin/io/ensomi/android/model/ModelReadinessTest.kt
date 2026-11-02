package io.ensomi.android.model

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ModelReadinessTest {
    private fun installed(open: ModelOpenState) = ModelState.Installed("model", "r1", 1, "MIT", openState = open)

    @Test fun freshMakerWaitsForItsCapabilitiesEvenWhenGuardIsReady() = runBlocking {
        val state = MutableStateFlow<ModelState>(installed(ModelOpenState.Ready))
        val capabilities = MutableStateFlow(false)
        val make = async(start = CoroutineStart.UNDISPATCHED) { awaitModelReady(state, capabilities) }
        assertFalse(make.isCompleted)
        capabilities.value = true
        assertTrue(make.await())
    }

    @Test fun openingWaitsAndBlockedFails() = runBlocking {
        val state = MutableStateFlow<ModelState>(installed(ModelOpenState.Opening))
        val make = async(start = CoroutineStart.UNDISPATCHED) { awaitModelReady(state) }
        assertFalse(make.isCompleted)
        state.value = installed(ModelOpenState.Ready)
        assertTrue(make.await())
        state.value = installed(ModelOpenState.Blocked())
        assertFalse(awaitModelReady(state))
    }

    @Test fun coldShareWaitsThroughCheckingWaitingAndOpening() = runBlocking {
        val state = MutableStateFlow<ModelState>(ModelState.Checking)
        val share = async(start = CoroutineStart.UNDISPATCHED) { awaitModelReady(state) }
        for (open in listOf(ModelOpenState.Waiting, ModelOpenState.Opening)) {
            state.value = installed(open)
            yield()
            assertFalse(share.isCompleted)
        }
        state.value = installed(ModelOpenState.Ready)
        assertTrue(share.await())
    }

    @Test fun waitIsBoundedAndFailureIsTerminal() = runBlocking {
        assertFalse(awaitModelReady(MutableStateFlow(installed(ModelOpenState.Waiting)), timeoutMillis = 10))
        assertFalse(awaitModelReady(MutableStateFlow(installed(ModelOpenState.Failed("open failed")))))
    }
}
