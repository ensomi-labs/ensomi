package io.ensomi.android.recognition

import android.content.Context
import android.util.AtomicFile
import io.ensomi.android.prototype.DebugTools
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Where Sync's audio comes from. */
@Serializable
enum class CaptureChoice { MICROPHONE, REPLAY }

/**
 * Public defaults. Only debug/checks builds read or write options in the app's external files.
 */
@Serializable
data class SessionOptions(
    val stars: Double = 3.5,
    /** Added to the game's times, in place of the audio offset: trims how the join feels. */
    val ambientOffsetMs: Int = 0,
    val capture: CaptureChoice = CaptureChoice.MICROPHONE,
    /** Where a WAV replay starts in its file. */
    val replayStartMs: Long = 0,
    /** How long capture may run without a final lock. */
    val syncLimitMs: Long = SessionLimits().syncMs,
    /** Idle time waiting for this song's audio after its identity is known. */
    val audioWaitMs: Long = SessionLimits().audioWaitMs,
    val autoplay: Boolean = false,
)

/**
 * What the session keeps on disk: its snapshot, which is how it survives a process death; which file each song id was
 * found in; and bounded traces. Release uses private storage and fixed defaults.
 */
class SessionStorage(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val debug = DebugTools.enabled(context)
    private val inside = File(context.filesDir, "recognition").also { it.mkdirs() }
    private val snapshot = AtomicFile(File(inside, "session.json"))
    private val knownFile = AtomicFile(File(inside, "known-songs.json"))

    /** Where a tester's files go: pulled and pushed with adb, without root. */
    val outside: File = if (debug) File(context.getExternalFilesDir(null) ?: context.filesDir, "recognition").also { it.mkdirs() } else inside
    val replayFile = File(outside, "replay.wav")
    private val optionsFile = File(outside, "options.json")

    fun traceFile(cid: String) = File(outside, "session-$cid.jsonl")

    fun pruneTraces(keepCid: String) {
        outside.listFiles { file -> file.name.startsWith("session-") && file.extension == "jsonl" }
            .orEmpty().filter { it != traceFile(keepCid) }.sortedByDescending { it.lastModified() }
            .drop(9).forEach { it.delete() }
    }

    fun readOptions(): SessionOptions =
        if (debug) runCatching { json.decodeFromString(SessionOptions.serializer(), optionsFile.readText()) }.getOrDefault(SessionOptions())
        else SessionOptions()

    fun writeOptions(options: SessionOptions) {
        if (!debug) return
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
        val known = (knownSongs() - songId + (songId to path)).entries.toList().takeLast(256).associate { it.toPair() }
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
