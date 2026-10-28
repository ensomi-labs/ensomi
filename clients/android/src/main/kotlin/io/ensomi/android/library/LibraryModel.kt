package io.ensomi.android.library

import kotlinx.serialization.Serializable

/** One folder the user granted, identified by the persistable tree URI a SAF picker returns. */
@Serializable
data class LibraryFolder(
    val treeUri: String,
    val displayName: String,
    val addedAtMs: Long,
)

/** One audio file the scanner indexed. The URI is what playback and generation will open. */
@Serializable
data class LibraryEntry(
    val uri: String,
    val name: String,
    val treeUri: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val indexedAtMs: Long,
    val failure: String? = null,
) {
    /** What the lists show when metadata is missing: the file name without its extension. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.').ifBlank { name }
}

/**
 * The client's library as one document. `clients/android` owns this file until the app core exists:
 * the design puts the index in Rusу's SQLite schema, and this JSON is the shell's stand-in, not a
 * second contract.
 */
@Serializable
data class LibraryIndex(
    val folders: List<LibraryFolder> = emptyList(),
    val entries: List<LibraryEntry> = emptyList(),
    val lastScanMs: Long? = null,
)

/** What a scan found, in the counts S2 reports. */
data class ScanSummary(
    val discovered: Int,
    val indexed: Int,
    val failed: Int,
) {
    companion object {
        val EMPTY = ScanSummary(discovered = 0, indexed = 0, failed = 0)
    }
}

/** The decisions the scanner makes that do not need a device, so they are testable here. */
object AudioFiles {
    /** The extensions the Apple library indexes; a SAF folder can hold anything. */
    val EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "aiff", "alac")

    fun isAudioName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    fun isAudioMimeType(mimeType: String?): Boolean =
        mimeType?.startsWith("audio/") == true

    /** A file is audio when either the name or the platform's MIME type says so. */
    fun isAudio(name: String, mimeType: String?): Boolean =
        isAudioName(name) || isAudioMimeType(mimeType)

    fun titleFromName(name: String): String =
        name.substringBeforeLast('.').ifBlank { name }
}

/** The strip-down state the client needs from a scan while it runs. */
data class ScanProgress(
    val running: Boolean = false,
    val discovered: Int = 0,
    val indexed: Int = 0,
    val failed: Int = 0,
    val message: String? = null,
)
