package io.ensomi.android.recognition

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Where Sync's audio comes from. */
@Serializable
enum class CaptureChoice { MICROPHONE, REPLAY }

/**
 * A tester's choices, kept in `recognition/options.json` in the app's external files. The screen writes them; a script
 * can push the file instead, for the choices the screen leaves out.
 */
@Serializable
data class SessionOptions(
    val path: LaunchPath = LaunchPath.AUTO,
    val stars: Double = 3.5,
    /** Added to the game's times, in place of the audio offset: trims how the join feels. */
    val ambientOffsetMs: Int = 0,
    val capture: CaptureChoice = CaptureChoice.MICROPHONE,
    /** Where a WAV replay starts in its file. */
    val replayStartMs: Long = 0,
    /** With the stub in place of the aligner, how long a replay runs before it says "locked". */
    val replayLockAfterMs: Long = 8_000,
    val autoplay: Boolean = false,
)

/**
 * What the session keeps on disk: its snapshot, which is how it survives a process death; which file each song id was
 * found in, the only way this app ever connects an id to a file; the tester's options; and the traces.
 */
class SessionStorage(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val inside = File(context.filesDir, "recognition").also { it.mkdirs() }
    private val snapshot = AtomicFile(File(inside, "session.json"))
    private val knownFile = AtomicFile(File(inside, "known-songs.json"))

    /** Where a tester's files go: pulled and pushed with adb, without root. */
    val outside: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "recognition").also { it.mkdirs() }
    val replayFile = File(outside, "replay.wav")
    private val optionsFile = File(outside, "options.json")

    fun traceFile(cid: String) = File(outside, "session-$cid.jsonl")

    fun readOptions(): SessionOptions =
        runCatching { json.decodeFromString(SessionOptions.serializer(), optionsFile.readText()) }.getOrDefault(SessionOptions())

    fun writeOptions(options: SessionOptions) {
        runCatching { optionsFile.writeText(json.encodeToString(SessionOptions.serializer(), options)) }
    }

    fun readSnapshot(): SessionState? =
        runCatching { json.decodeFromString(SessionState.serializer(), snapshot.readFully().decodeToString()) }.getOrNull()

    fun writeSnapshot(state: SessionState) = write(snapshot, json.encodeToString(SessionState.serializer(), state))

    fun clearSnapshot() = snapshot.delete()

    fun knownSongs(): Map<Long, String> = runCatching {
        json.decodeFromString(KNOWN, knownFile.readFully().decodeToString()).mapKeys { it.key.toLong() }
    }.getOrDefault(emptyMap())

    fun remember(songId: Long, path: String) {
        val known = knownSongs() + (songId to path)
        write(knownFile, json.encodeToString(KNOWN, known.mapKeys { it.key.toString() }))
    }

    private fun write(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try {
            stream.write(text.encodeToByteArray())
            file.finishWrite(stream)
        } catch (failure: java.io.IOException) {
            file.failWrite(stream)
        }
    }

    private companion object {
        val KNOWN = MapSerializer(String.serializer(), String.serializer())
    }
}
