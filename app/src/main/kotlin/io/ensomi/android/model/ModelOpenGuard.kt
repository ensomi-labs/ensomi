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
    data object Blocked : ModelOpenState
    data class Failed(val reason: String) : ModelOpenState
}

@Serializable
data class ModelOpenKey(val revision: String, val versionCode: Long, val lastUpdateTime: Long)

/**
 * A durable first-open transaction. The caller holds the model store's access gate and opens on its runtime worker.
 * A leftover marker takes precedence over a capabilities cache: only explicit retry or removal clears it.
 */
class ModelOpenGuard(
    private val root: File,
    private val pid: () -> Int,
    private val events: InstallEvents = InstallEvents.NONE,
    private val log: (String) -> Unit = {},
    private val afterMarker: () -> Unit = {},
) {
    private val marker = File(root, "model-open-pending.json")
    private val cache = File(root, "model-capabilities.json")
    private val mutableState = MutableStateFlow<ModelOpenState>(if (marker.exists()) ModelOpenState.Blocked else ModelOpenState.Waiting)
    val state = mutableState.asStateFlow()
    private val mutableRetries = MutableStateFlow(0L)
    val retries = mutableRetries.asStateFlow()
    private var handledRetry = 0L
    @Volatile private var attemptedKey: ModelOpenKey? = null

    init {
        if (marker.exists()) blocked()
    }

    fun stateFor(revision: String): ModelOpenState = when (val current = state.value) {
        ModelOpenState.Ready, is ModelOpenState.Failed -> if (attemptedKey?.revision == revision) current else ModelOpenState.Waiting
        else -> current
    }

    /** No native work here. The chart maker consumes this request under the shared gate. */
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
                // A retry really opens, even if a process died between saving capabilities and deleting its marker.
                if (cache.exists() && !cache.delete()) throw IOException("Could not clear stored capabilities.")
            } else {
                if (marker.exists()) {
                    blocked()
                    return null
                }
                if (attemptedKey == key && state.value is ModelOpenState.Failed) return null
                val saved = try {
                    cache.takeIf { it.isFile }?.let { JSON.decodeFromString(Saved.serializer(), it.readText()) }
                } catch (e: Exception) {
                    log("Capabilities cache unreadable; a guarded open is needed")
                    null
                }
                if (saved?.key == key) {
                    attemptedKey = key
                    log("Stored capabilities: no model open")
                    mutableState.value = ModelOpenState.Ready
                    return saved.capabilities
                }
            }
            attemptedKey = key
            mutableState.value = ModelOpenState.Opening
            durable(marker, JSON.encodeToString(Pending.serializer(), Pending(key, System.currentTimeMillis(), pid())))
            events.record("model_open", "begin", emptyMap())
            log("Guarded model open begin")
            afterMarker()
            val capabilities = open()
            durable(cache, JSON.encodeToString(Saved.serializer(), Saved(key, capabilities)))
            deleteMarker()
            mutableState.value = ModelOpenState.Ready
            events.record("model_open", "end", emptyMap())
            log("Guarded model open end; capabilities stored")
            return capabilities
        } catch (e: Throwable) {
            mutableState.value = ModelOpenState.Failed(e.message ?: e.javaClass.simpleName)
            events.record("model_open", "fail", mapOf("reason" to e.javaClass.simpleName))
            log("Guarded model open failed: ${e.javaClass.simpleName}")
            if (e is VirtualMachineError) throw e
            try {
                deleteMarker()
            } catch (cleanup: IOException) {
                log("Could not clear pending open; next start will remain blocked")
            }
            if (e is CancellationException) throw e
            return null
        }
    }

    /** Only the person's Remove model action calls this, under the model gate. */
    fun remove() {
        deleteMarker()
        if (cache.exists() && !cache.delete()) throw IOException("Could not remove stored capabilities.")
        attemptedKey = null
        mutableState.value = ModelOpenState.Waiting
    }

    private fun blocked() {
        mutableState.value = ModelOpenState.Blocked
        events.record("model_open", "blocked", emptyMap())
        log("Pending marker: model blocked; no automatic open")
    }

    private fun deleteMarker() {
        if (marker.exists() && !marker.delete()) throw IOException("Could not clear the pending model open.")
    }

    private fun durable(file: File, text: String) {
        root.mkdirs()
        val temporary = File(root, file.name + ".tmp")
        FileOutputStream(temporary).use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!temporary.renameTo(file)) throw IOException("Could not save model open state.")
    }

    @Serializable
    private data class Pending(val key: ModelOpenKey, val wallTime: Long, val pid: Int)

    @Serializable
    private data class Saved(val key: ModelOpenKey, val capabilities: ChartCapabilities)

    companion object {
        const val TAG = "EnsomiModelOpen"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
