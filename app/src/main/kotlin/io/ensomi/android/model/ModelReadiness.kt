package io.ensomi.android.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/** A cold store/maker must finish its asynchronous guard read before deciding that the model is unavailable. */
suspend fun awaitModelReady(
    states: Flow<ModelState>,
    capabilitiesRead: Flow<Boolean> = flowOf(true),
    timeoutMillis: Long = 60_000,
): Boolean = withTimeoutOrNull(timeoutMillis) {
    combine(states, capabilitiesRead) { state, read ->
        when {
            state is ModelState.Checking -> null
            state is ModelState.Installed &&
                (state.openState is ModelOpenState.Waiting || state.openState is ModelOpenState.Opening) -> null
            state.ready && !read -> null
            else -> state.ready
        }
    }.first { it != null } == true
} ?: false
