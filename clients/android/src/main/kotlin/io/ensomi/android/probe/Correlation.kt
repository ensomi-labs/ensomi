package io.ensomi.android.probe

/**
 * The watch events the download probe cares about, named after `android.os.FileObserver`'s masks.
 * A watcher turns the platform's bitmask into these so the correlation rules stay platform-free.
 */
enum class WatchKind { CREATE, MODIFY, CLOSE_WRITE, MOVED_TO, MOVED_FROM, DELETE }

data class WatchEvent(
    val kind: WatchKind,
    val path: String,
    val tMonoNs: Long,
    val wallMs: Long,
)

/** What a candidate file looks like at the moment it is judged. */
data class FileSnapshot(
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

/**
 * What the report calls the final acceptance condition for `audio_ready`. The order of the checks is
 * the order the report lists them, because the cheap, certain rejections must come before a decoder
 * is opened or a duration compared.
 */
enum class CandidateVerdict {
    IGNORE_UNFINISHED,
    IGNORE_TEMP,
    IGNORE_BEFORE_SESSION,
    WAIT_SIZE_STABLE,
    IGNORE_UNDECODABLE,
    IGNORE_DURATION,
    READY,
}

/**
 * The rules that decide whether a file the watcher saw is the audio this session asked for. They take
 * plain data, not a `File`, so they are unit-testable without a device.
 */
object CorrelationRules {
    /** Downloaders write one of these while a transfer is in progress, then rename. */
    val TEMP_SUFFIXES = listOf(".tmp", ".part", ".download", ".crdownload", ".bak", ".!ut", ".td", ".opdownload")

    /** A file whose modification time is younger than this may still be growing. */
    const val SIZE_STABLE_MS = 1_000L

    /** How far a downloaded recording may differ from the recognized one and still be the same take. */
    const val DURATION_TOLERANCE_MS = 2_000L

    fun isTemporary(name: String): Boolean {
        val lower = name.lowercase()
        return TEMP_SUFFIXES.any { lower.endsWith(it) } || lower.startsWith(".")
    }

    /**
     * Judges one event. [nowWallMs] is passed in rather than read so a test can decide what "now" is.
     */
    fun judge(
        event: WatchEvent,
        snapshot: FileSnapshot,
        sessionStartWallMs: Long,
        decodeOk: Boolean,
        durationMs: Long?,
        expectedDurationMs: Long?,
        nowWallMs: Long,
    ): CandidateVerdict {
        if (event.kind != WatchKind.CLOSE_WRITE && event.kind != WatchKind.MOVED_TO) {
            return CandidateVerdict.IGNORE_UNFINISHED
        }
        if (isTemporary(snapshot.name)) {
            return CandidateVerdict.IGNORE_TEMP
        }
        if (snapshot.lastModifiedMs < sessionStartWallMs) {
            return CandidateVerdict.IGNORE_BEFORE_SESSION
        }
        if (nowWallMs - snapshot.lastModifiedMs < SIZE_STABLE_MS) {
            return CandidateVerdict.WAIT_SIZE_STABLE
        }
        if (!decodeOk) {
            return CandidateVerdict.IGNORE_UNDECODABLE
        }
        if (expectedDurationMs != null && durationMs != null &&
            kotlin.math.abs(durationMs - expectedDurationMs) > DURATION_TOLERANCE_MS
        ) {
            return CandidateVerdict.IGNORE_DURATION
        }
        return CandidateVerdict.READY
    }
}
