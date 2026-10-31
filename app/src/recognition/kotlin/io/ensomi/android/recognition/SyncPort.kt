package io.ensomi.android.recognition

import io.ensomi.runtime.SyncException
import io.ensomi.runtime.SyncReference
import io.ensomi.runtime.SyncSession
import io.ensomi.runtime.SyncSnapshot
import io.ensomi.runtime.fromPcm
import io.ensomi.runtime.push
import io.ensomi.runtime.SyncState as RuntimeSyncState

/** Where alignment stands, in the runtime's terms. */
enum class SyncState { READY, LISTENING, LOCKING, CONFIRMED, LOCKED, DRIFTING, RELOCKING, LOST, FAILED }

/**
 * One reading of an aligner. With [finalLock], [referenceMs] of the song was captured at [captureNanos]; that pair, not
 * the moment of the reading, is what play is anchored on. Before it they are provisional, and nothing acts on them.
 */
data class SyncReading(
    val state: SyncState,
    val finalLock: Boolean = false,
    val referenceMs: Double? = null,
    /** On the clock [AmbientAligner.push] was given. */
    val captureNanos: Long? = null,
    /** Spectral agreement from 0 to 1, for the trace; nothing gates on it. */
    val confidence: Double? = null,
    /** Why no position is held, in the aligner's words. */
    val detail: String? = null,
)

/**
 * The live alignment of the microphone against one song: all the session needs from it. The alignment itself is the
 * runtime's. Called from one thread at a time.
 */
interface AmbientAligner : AutoCloseable {
    /** For the trace. */
    val backend: String

    /** One block of mono samples, the first of which was captured at [captureNanos] on `CLOCK_MONOTONIC`. */
    fun push(samples: FloatArray, count: Int, sampleRate: Int, captureNanos: Long)

    fun read(): SyncReading

    override fun close() = Unit
}

/** Builds an aligner from a song decoded to mono PCM on the song's time, which reference times are then on. */
fun interface SyncPort {
    fun open(reference: FloatArray, sampleRate: Int): AmbientAligner
}

/**
 * The runtime's aligner (`SyncReference`, `SyncSession`). The reference is the same mono PCM the chart comes from, at
 * the file's rate, so a reference time is a chart time: `SongDecoder` has already put the file's lead on it, which the
 * Swift app adds at this point because its alignment reads the file as AVFoundation decodes it.
 */
object RuntimeSync {
    val port = SyncPort { reference, sampleRate -> RuntimeAligner(SyncReference.fromPcm(reference, sampleRate.toDouble())) }
}

private class RuntimeAligner(private val reference: SyncReference) : AmbientAligner {
    override val backend = "runtime"
    private var session: SyncSession? = null
    private var last = SyncReading(SyncState.READY)

    override fun push(samples: FloatArray, count: Int, sampleRate: Int, captureNanos: Long) {
        val current = session ?: SyncSession(reference, sampleRate.toDouble()).also { session = it }
        last = try {
            current.push(samples, captureNanos / NANOS_PER_MS, count).toReading()
        } catch (refused: SyncException) {
            // A refused block changes nothing, and the next is taken.
            last.copy(detail = "refused: ${refused.message}")
        }
    }

    override fun read(): SyncReading = last

    override fun close() {
        session?.close()
        reference.close()
    }

    private fun SyncSnapshot.toReading() = SyncReading(
        state = when (state) {
            RuntimeSyncState.READY -> SyncState.READY
            RuntimeSyncState.LISTENING -> SyncState.LISTENING
            RuntimeSyncState.LOCKING -> SyncState.LOCKING
            RuntimeSyncState.CONFIRMED -> SyncState.CONFIRMED
            RuntimeSyncState.LOCKED -> SyncState.LOCKED
            RuntimeSyncState.DRIFTING -> SyncState.DRIFTING
            RuntimeSyncState.RELOCKING -> SyncState.RELOCKING
            RuntimeSyncState.LOST -> SyncState.LOST
            RuntimeSyncState.FAILED -> SyncState.FAILED
        },
        finalLock = isFinal,
        referenceMs = referenceTimeMs,
        captureNanos = captureTimeMs?.let { (it * NANOS_PER_MS).toLong() },
        confidence = confidence,
        detail = withholdReason?.name,
    )

    private companion object {
        const val NANOS_PER_MS = 1e6
    }
}
