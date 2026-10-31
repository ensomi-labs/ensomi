package io.ensomi.android.recognition

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.Serializable

/** Which song the session is after, as far as anything has said. */
@Serializable
data class SongIdentity(
    val songId: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
) {
    fun merged(other: SongIdentity): SongIdentity = SongIdentity(
        songId ?: other.songId,
        title ?: other.title,
        artist ?: other.artist,
        durationMs ?: other.durationMs,
    )

    /** False only when both say what they are and what they say differs. */
    fun sameSongAs(other: SongIdentity): Boolean = when {
        songId != null && other.songId != null -> songId == other.songId
        title != null && other.title != null -> SongMatch.normalize(title) == SongMatch.normalize(other.title)
        else -> true
    }
}

/** One audio file as a source saw it. [path] is its key; [uri] is its MediaStore row, when it has one. */
@Serializable
data class FileFacts(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedWallMs: Long,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
    val uri: String? = null,
    /** A MediaStore row still being written by its owner. */
    val pending: Boolean = false,
)

/** What says a file is there, from a source's point of view. */
enum class Evidence(val finished: Boolean, val rank: Int) {
    /** A `FileObserver` saw it being written. */
    WRITING(finished = false, rank = 0),

    /** The slow poll saw it, with a size and a time. */
    POLL(finished = false, rank = 1),

    /** It was there when the index was built. */
    INDEX(finished = true, rank = 2),

    /** A MediaStore row, no longer pending. */
    MEDIA_STORE(finished = true, rank = 3),

    /** Its writer closed it. */
    CLOSE_WRITE(finished = true, rank = 4),

    /** It was moved here under this name. */
    MOVED_TO(finished = true, rank = 4),
}

/** How sure the session is that a file is its song, weakest first. */
enum class MatchStrength {
    NONE,

    /** The song's name is unknown, and this file appeared after the session began. */
    NEW_FILE,
    TITLE_ONLY,
    TITLE_ARTIST,
    SONG_ID,

    /** The person said so. */
    PERSON,
}

/** What was decided about a candidate, and why. All but [PROBE] and [READY] say why it is not the song yet. */
enum class Verdict {
    /** Not an audio file the app can decode. */
    NOT_AUDIO,

    /** A name downloaders use while a transfer is under way. */
    TEMPORARY,

    /** Nothing says its writer is done: no close, no move, a row still pending, or an empty file. */
    UNFINISHED,

    /** The session does not know its song yet: the file waits. */
    NO_SONG_YET,

    /** New, but it does not look like the song: only the person can say it is. */
    NO_MATCH,

    /** Older than the session and not matched by identity. */
    BEFORE_SESSION,

    /** Written too recently to trust. */
    NOT_QUIET,

    /** A `.ncm`: never read. The session refuses it. */
    PROTECTED,

    /** Everything that needs no decoder holds: decode it. */
    PROBE,

    /** A decoder could not open it or read it to the end. */
    UNDECODABLE,

    /** It decodes, shorter than the file says it should: it is still arriving. */
    TRUNCATED,

    /** Its length differs from the song's by more than the tolerance. */
    DURATION_DIFFERS,

    /** It changed while it was being decoded. */
    CHANGED_WHILE_PROBED,

    /** The session's audio. */
    READY,
}

/** What decoding a file showed; the size and time are as they were when the decoder finished. */
data class ProbeFacts(
    val decoded: Boolean,
    val decodedMs: Double = 0.0,
    /** What the file says of itself, from its tags or headers. */
    val declaredMs: Long? = null,
    val sampleRate: Int = 0,
    val mime: String? = null,
    val codec: String? = null,
    val tookMs: Long = 0,
    val sha256: String? = null,
    val failure: String? = null,
    val sizeBytes: Long = 0,
    val modifiedWallMs: Long = 0,
)

/**
 * Whether a file is the song, in plain data so that it says the same off the device. The cheap, certain refusals come
 * before a decoder is opened or a duration compared.
 */
object CandidateRules {
    /** A file written more recently than this may still be growing. */
    const val QUIET_MS = 1_000L

    /** The same for a file only the slow poll has seen: nothing says its writer finished. */
    const val POLL_QUIET_MS = 8_000L

    /** How far a file's length may differ from the song's and still be the same take. */
    const val DURATION_TOLERANCE_MS = 2_000L

    private val TEMPORARY_SUFFIXES = listOf(
        ".tmp", ".temp", ".part", ".partial", ".download", ".downloading", ".crdownload", ".cache", ".bak", ".!ut", ".td",
        ".opdownload",
    )
    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav")
    private const val PROTECTED_EXTENSION = "ncm"

    fun isProtected(name: String): Boolean = extension(name) == PROTECTED_EXTENSION

    fun isTemporary(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith(".") || lower.startsWith("~") || TEMPORARY_SUFFIXES.any { lower.endsWith(it) }
    }

    fun isAudio(name: String): Boolean = extension(name) in AUDIO_EXTENSIONS

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /**
     * The rules that need no decoder. [lastChangeWallMs] is when the file last changed as far as any source knows.
     * [song] is null until something has said which song it is.
     */
    fun judge(
        file: FileFacts,
        evidence: Evidence,
        song: SongIdentity?,
        match: MatchStrength,
        sessionStartWallMs: Long,
        lastChangeWallMs: Long,
        nowWallMs: Long,
    ): Verdict {
        if (isTemporary(file.name)) return Verdict.TEMPORARY
        if (isProtected(file.name)) {
            return when {
                song == null -> Verdict.NO_SONG_YET
                match == MatchStrength.NONE -> Verdict.NO_MATCH
                else -> Verdict.PROTECTED
            }
        }
        if (!isAudio(file.name)) return Verdict.NOT_AUDIO
        if (file.pending || file.sizeBytes <= 0 || evidence == Evidence.WRITING) return Verdict.UNFINISHED
        val isNew = file.modifiedWallMs >= sessionStartWallMs
        if (song == null && match != MatchStrength.PERSON) return if (isNew) Verdict.NO_SONG_YET else Verdict.BEFORE_SESSION
        when (match) {
            MatchStrength.NONE -> return if (isNew) Verdict.NO_MATCH else Verdict.BEFORE_SESSION
            MatchStrength.NEW_FILE -> if (!isNew) return Verdict.BEFORE_SESSION
            // An older file with the right title and another artist: the title alone says too little.
            MatchStrength.TITLE_ONLY -> if (!isNew && !durationAgrees(file.durationMs, song?.durationMs)) return Verdict.BEFORE_SESSION
            MatchStrength.TITLE_ARTIST, MatchStrength.SONG_ID, MatchStrength.PERSON -> Unit
        }
        // A new file found by listing the folder has not shown that its writer is done, as an old one has.
        val quiet = if (evidence.finished && !(evidence == Evidence.INDEX && isNew)) QUIET_MS else POLL_QUIET_MS
        if (nowWallMs - maxOf(file.modifiedWallMs, lastChangeWallMs) < quiet) return Verdict.NOT_QUIET
        return Verdict.PROBE
    }

    /**
     * The rules after the decoder has run. [file] is the file as it was when the decoder started. A length the file
     * declares can be a guess, for a VBR file without a header, so [personChose] sets the lengths aside.
     */
    fun judgeDecoded(file: FileFacts, probe: ProbeFacts, expectedDurationMs: Long?, personChose: Boolean = false): Verdict {
        if (probe.sizeBytes != file.sizeBytes || probe.modifiedWallMs != file.modifiedWallMs) return Verdict.CHANGED_WHILE_PROBED
        if (!probe.decoded || probe.decodedMs <= 0) return Verdict.UNDECODABLE
        if (personChose) return Verdict.READY
        val declared = probe.declaredMs
        if (declared != null && declared - probe.decodedMs > DURATION_TOLERANCE_MS) return Verdict.TRUNCATED
        if (expectedDurationMs != null && abs(expectedDurationMs - probe.decodedMs) > DURATION_TOLERANCE_MS) {
            return Verdict.DURATION_DIFFERS
        }
        return Verdict.READY
    }

    private fun durationAgrees(a: Long?, b: Long?): Boolean = a != null && b != null && abs(a - b) <= DURATION_TOLERANCE_MS
}

/** Whether a file is a song, by what each says of itself: tags, and names like `Artist - Title.mp3`. */
object SongMatch {
    private val ARTIST_SEPARATORS = charArrayOf('/', ',', '，', '、', '&', ';', '；')
    private const val NAME_SEPARATOR = " - "

    /** Width and case folded, punctuation and spaces gone: what two spellings of one name have in common. */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
        return folded.filter { it.isLetterOrDigit() }.ifEmpty { folded }
    }

    /** [knownPath] is where this app last found the song's id ([KnownSongs]), the only way a file shows an id here. */
    fun strength(song: SongIdentity, file: FileFacts, knownPath: String?): MatchStrength {
        if (knownPath != null && knownPath == file.path) return MatchStrength.SONG_ID
        val title = song.title?.let(::normalize)?.takeIf { it.isNotEmpty() } ?: return MatchStrength.NONE
        val wanted = artists(song.artist)
        var best = MatchStrength.NONE
        for ((claimedTitle, claimedArtist) in claims(file)) {
            if (normalize(claimedTitle) != title) continue
            if (artists(claimedArtist).any { it in wanted }) return MatchStrength.TITLE_ARTIST
            best = MatchStrength.TITLE_ONLY
        }
        return best
    }

    /** Title and artist as the tags have them, then the name read both ways round. */
    private fun claims(file: FileFacts): List<Pair<String, String?>> = buildList {
        file.title?.let { add(it to file.artist) }
        val stem = file.name.substringBeforeLast('.')
        // MediaStore's title for a file without tags is the name, so either can hold `Artist - Title`.
        for (text in listOfNotNull(stem, file.title).distinct()) {
            add(text to null)
            var at = text.indexOf(NAME_SEPARATOR)
            while (at >= 0) {
                val left = text.substring(0, at).trim()
                val right = text.substring(at + NAME_SEPARATOR.length).trim()
                add(right to left)
                add(left to right)
                at = text.indexOf(NAME_SEPARATOR, at + 1)
            }
        }
    }

    private fun artists(text: String?): Set<String> =
        text?.split(*ARTIST_SEPARATORS)?.map(::normalize)?.filter { it.isNotEmpty() }?.toSet().orEmpty()
}
