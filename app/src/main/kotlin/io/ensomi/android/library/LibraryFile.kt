package io.ensomi.android.library

import java.text.Collator
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A song as `library.json` keeps it: the document's URI and what was read from the file when it was picked. */
@Serializable
data class StoredSong(
    val id: String,
    val uri: String,
    val fileName: String,
    /** The file's title and artist tags, when it has them. */
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long = 0,
    val addedAtMs: Long = 0,
) {
    /** The title tag, else the file name without its extension. */
    val shownTitle: String
        get() = title?.trim()?.takeIf { it.isNotEmpty() } ?: fileName.substringBeforeLast('.').ifEmpty { fileName }
}

@Serializable
data class StoredLibrary(val songs: List<StoredSong> = emptyList())

/** The library file's format: JSON, readable by older and newer versions of the app. */
object LibraryFile {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(library: StoredLibrary): String = json.encodeToString(StoredLibrary.serializer(), library)

    /** The library [text] holds; an empty one if it does not parse. */
    fun decode(text: String): StoredLibrary =
        runCatching { json.decodeFromString(StoredLibrary.serializer(), text) }.getOrDefault(StoredLibrary())

    /** Songs in the order the list shows them: by title as the language sorts, then by artist. */
    fun sorted(songs: List<StoredSong>, locale: Locale = Locale.getDefault()): List<StoredSong> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        return songs.sortedWith { a, b ->
            collator.compare(a.shownTitle, b.shownTitle).takeIf { it != 0 }
                ?: collator.compare(a.artist.orEmpty(), b.artist.orEmpty())
        }
    }
}
