package io.ensomi.android.model

import io.ensomi.android.chart.ChartCapabilities
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface ModelOpenState {
    data object Waiting : ModelOpenState
    data object Opening : ModelOpenState
    data object Ready : ModelOpenState
    data class Blocked(val stage: String = "model_open") : ModelOpenState
    data class Failed(val reason: String) : ModelOpenState
}

@Serializable
data class ModelOpenKey(val revision: String, val versionCode: Long, val lastUpdateTime: Long)

/** Caller holds the model access gate; all disk/native work is off the main thread. */
class ModelOpenGuard(
    private val root: File,
    private val pid: () -> Int,
    private val events: InstallEvents = InstallEvents.NONE,
    private val log: (String) -> Unit = {},
    private val afterMarker: () -> Unit = {},
    private val exitReason: (Int, Long) -> Int? = { _, _ -> null },
    private val syncDirectory: (File) -> Unit = {},
    private val afterInferenceMarker: (String) -> Unit = {},
) {
    private val marker = File(root, "model-open-pending.json")
    private val cache = File(root, "model-capabilities.json")
    private val mutableState = MutableStateFlow<ModelOpenState>(ModelOpenState.Waiting)
    val state = mutableState.asStateFlow()
    private val mutableRetries = MutableStateFlow(0L)
    val retries = mutableRetries.asStateFlow()
    private var handledRetry = 0L
    @Volatile private var attemptedKey: ModelOpenKey? = null
    private var activeCapabilities: ChartCapabilities? = null
    private var inferencePending = false

    fun stateFor(revision: String): ModelOpenState = when (val current = state.value) {
        ModelOpenState.Ready, is ModelOpenState.Failed -> if (attemptedKey?.revision == revision) current else ModelOpenState.Waiting
        else -> current
    }

    fun retry() {
        if (state.value !is ModelOpenState.Blocked && state.value !is ModelOpenState.Failed) return
        events.record("model_open", "retry", emptyMap())
        mutableRetries.value += 1
    }

    suspend fun read(key: ModelOpenKey, open: suspend () -> ChartCapabilities): ChartCapabilities? {
        val retry = retries.value != handledRetry
        handledRetry = retries.value
        try {
            if (retry) {
                deleteMarker()
                deleteCache()
                activeCapabilities = null
            } else {
                // A second maker in this process shares this guard, including an unfinished first inference.
                if (attemptedKey == key && state.value is ModelOpenState.Ready) return activeCapabilities
                if (marker.exists()) {
                    val pending = runCatching { JSON.decodeFromString(Pending.serializer(), marker.readText()) }.getOrNull()
                    val reason = pending?.let { exitReason(it.pid, it.wallTime) }
                    if (reason == 10 || reason == 11) {
                        deleteMarker()
                        deleteCache()
                        events.record("model_open", "user_stop", emptyMap())
                    } else {
                        blocked(pending?.stage ?: "model_open")
                        return null
                    }
                }
                if (attemptedKey == key && state.value is ModelOpenState.Failed) return null
                val saved = runCatching { cache.takeIf { it.isFile }?.let { JSON.decodeFromString(Saved.serializer(), it.readText()) } }.getOrNull()
                if (saved?.key == key && saved.inferenceComplete) {
                    attemptedKey = key
                    activeCapabilities = saved.capabilities
                    inferencePending = false
                    log("Stored capabilities: no model open")
                    mutableState.value = ModelOpenState.Ready
                    return saved.capabilities
                }
            }
            attemptedKey = key
            mutableState.value = ModelOpenState.Opening
            pending(key, "model_open")
            events.record("model_open", "begin", emptyMap())
            log("Guarded model open begin")
            afterMarker()
            val capabilities = open()
            activeCapabilities = capabilities
            inferencePending = true
            save(key, capabilities, false)
            // Keep the transaction pending until the first real native inference succeeds.
            pending(key, "model_prepare")
            mutableState.value = ModelOpenState.Ready
            events.record("model_open", "end", emptyMap())
            log("Guarded model open end; first inference pending")
            return capabilities
        } catch (e: Throwable) {
            failure("model_open", e)
            if (e is VirtualMachineError || e is CancellationException) throw e
            return null
        }
    }

    /** Wrap the whole first generator/inference scope, including its open. Cached preparation is not inference. */
    suspend fun <T> inference(stage: String, work: suspend () -> T): T {
        if (!inferencePending) return work()
        val key = checkNotNull(attemptedKey)
        try {
            pending(key, stage)
            events.record(stage, "begin", emptyMap())
            log("Guarded $stage begin")
            afterInferenceMarker(stage)
            val result = work()
            // A cancelled preparation/scheduler did not validate native inference.
            if (result != null && result != false) {
                save(key, checkNotNull(activeCapabilities), true)
                deleteMarker()
                inferencePending = false
                events.record(stage, "end", emptyMap())
                log("Guarded $stage end; first inference complete")
            } else events.record(stage, "cancel", emptyMap())
            return result
        } catch (e: Throwable) {
            failure(stage, e)
            throw e
        }
    }

    private fun failure(stage: String, e: Throwable) {
        mutableState.value = ModelOpenState.Failed(e.message ?: e.javaClass.simpleName)
        events.record(stage, "fail", mapOf("reason" to e.javaClass.simpleName))
        log("Guarded $stage failed: ${e.javaClass.simpleName}")
        if (e !is VirtualMachineError) {
            try { deleteMarker() } catch (_: IOException) { log("Could not clear pending marker") }
        }
    }

    fun remove() {
        deleteMarker()
        deleteCache()
        attemptedKey = null
        activeCapabilities = null
        inferencePending = false
        mutableState.value = ModelOpenState.Waiting
    }

    private fun deleteCache() {
        if (cache.exists() && !cache.delete()) throw IOException("Could not remove stored capabilities.")
    }
    private fun blocked(stage: String) {
        mutableState.value = ModelOpenState.Blocked(stage)
        events.record(stage, "blocked", emptyMap())
        log("Pending marker: $stage blocked; no automatic open")
    }
    private fun deleteMarker() {
        if (marker.exists()) {
            if (!marker.delete()) throw IOException("Could not clear the pending model operation.")
            syncDirectory(root)
        }
    }
    private fun pending(key: ModelOpenKey, stage: String) =
        durable(marker, JSON.encodeToString(Pending.serializer(), Pending(key, System.currentTimeMillis(), pid(), stage)))
    private fun save(key: ModelOpenKey, capabilities: ChartCapabilities, complete: Boolean) =
        durable(cache, JSON.encodeToString(Saved.serializer(), Saved(key, capabilities, complete)))
    private fun durable(file: File, text: String) {
        root.mkdirs()
        val temporary = File(root, file.name + ".tmp")
        FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        if (!temporary.renameTo(file)) throw IOException("Could not save model open state.")
        syncDirectory(root)
    }

    @Serializable private data class Pending(val key: ModelOpenKey, val wallTime: Long, val pid: Int, val stage: String = "model_open")
    @Serializable private data class Saved(val key: ModelOpenKey, val capabilities: ChartCapabilities, val inferenceComplete: Boolean = false)
    companion object {
        const val TAG = "EnsomiModelOpen"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
