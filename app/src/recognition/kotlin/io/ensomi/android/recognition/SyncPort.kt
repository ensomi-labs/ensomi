package io.ensomi.android.recognition

/** Where alignment stands, in the Swift app's terms. */
enum class SyncState { LISTENING, LOCKING, CONFIRMED, LOCKED, DRIFTING, RELOCKING, LOST, FAILED }

/**
 * One reading of an aligner. With [finalLock], [referenceMs] of the song was captured at [captureNanos]; that pair, not
 * the moment of the reading, is what play is anchored on.
 */
data class SyncReading(
    val state: SyncState,
    val finalLock: Boolean = false,
    val referenceMs: Double? = null,
    /** On the clock [AmbientAligner.push] was given. */
    val captureNanos: Long? = null,
    val detail: String? = null,
)

/**
 * The live alignment of the microphone against one song. Called from one thread, which also closes it. This is the
 * whole of what the session needs from alignment; the work is the runtime's, not the app's.
 */
interface AmbientAligner : AutoCloseable {
    /** For the trace. */
    val backend: String

    /** One block of mono samples, the first of which was captured at [captureNanos] (`CLOCK_MONOTONIC`). */
    fun push(samples: FloatArray, count: Int, sampleRate: Int, captureNanos: Long)

    fun read(): SyncReading

    override fun close() = Unit
}

/** Builds an aligner's reference from a song decoded to mono PCM on the song's time, which reference times are then on. */
fun interface SyncPort {
    fun open(reference: FloatArray, sampleRate: Int): AmbientAligner
}

/**
 * Where the runtime's aligner plugs in. `ensomi-runtime` is porting the Swift app's live extractor to Rust, and its
 * Kotlin API is not in the AAR this build links, so [port] answers null and a session on the microphone says Sync is
 * unavailable rather than guessing. When the binding is there, implement [AmbientAligner] over it here: its reference
 * from the PCM, each block with its stamp, its state, final lock, reference time and the capture time that refers to.
 * The session and the clock change no line for it.
 */
object RuntimeSync {
    fun port(): SyncPort? = null
}

/**
 * Not alignment: for an emulator run of the whole path without the runtime's aligner, where [WavReplayCapture] stands in
 * for the microphone. It knows where the replay started in the song, because it was told, and says "locked" after
 * [lockAfterMs] of pushed audio. It shows that the session and the clock join at a position they are given, and nothing
 * about whether real alignment would find it. The trace names it, and it never runs on the microphone.
 */
class ReplayTruthAligner(private val replayStartMs: Long, private val lockAfterMs: Long) : AmbientAligner {
    override val backend = "replay_truth_stub"
    private var firstCaptureNanos: Long? = null
    private var lastCaptureNanos = 0L
    private var pushedMs = 0.0

    override fun push(samples: FloatArray, count: Int, sampleRate: Int, captureNanos: Long) {
        if (firstCaptureNanos == null) firstCaptureNanos = captureNanos
        lastCaptureNanos = captureNanos
        pushedMs += count * 1000.0 / sampleRate
    }

    override fun read(): SyncReading {
        val first = firstCaptureNanos ?: return SyncReading(SyncState.LISTENING)
        if (pushedMs < lockAfterMs) {
            return SyncReading(if (pushedMs < lockAfterMs / 2) SyncState.LISTENING else SyncState.LOCKING, detail = backend)
        }
        // The replay's file position at the last block's first sample.
        val referenceMs = replayStartMs + (lastCaptureNanos - first) / 1e6
        return SyncReading(SyncState.LOCKED, finalLock = true, referenceMs = referenceMs, captureNanos = lastCaptureNanos, detail = backend)
    }
}
