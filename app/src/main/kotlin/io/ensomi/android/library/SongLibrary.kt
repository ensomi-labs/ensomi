package io.ensomi.android.library

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A song the player added. [durationMs] is the file's length; [fileName] is what the document provider calls it. */
data class Song(
    val id: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val fileName: String,
    /** Charts made from this song and kept on the device. */
    val chartCount: Int = 0,
    /** The document, readable through the persistable permission taken when it was picked; null for a sample. */
    val uri: Uri? = null,
    /** The file has gone: moved, deleted, or its permission revoked. The song stays until it is found or removed. */
    val missing: Boolean = false,
)

/** What adding documents did: how many became songs, how many were in the library already, which could not be read. */
data class AddResult(val added: Int, val alreadyThere: Int, val unreadable: List<String>)

/**
 * The songs Ensomi can make charts from: files the player picked with the system's document picker
 * (`ACTION_OPEN_DOCUMENT`), kept with a persistable read permission. No storage permission is needed.
 */
interface SongLibrary {
    val songs: StateFlow<List<Song>>

    fun song(id: String): Song? = songs.value.firstOrNull { it.id == id }

    /** Adds the documents the picker returned. */
    suspend fun add(documents: List<Uri>): AddResult

    /** Points the song [id] at [document], picked anew, keeping the song and its charts. False if it cannot be read. */
    suspend fun findAgain(id: String, document: Uri): Boolean

    /** Forgets a song; the file itself stays where it is. */
    fun remove(id: String)

    /** Looks again whether each song's file is still there. */
    fun refresh()

    /** Shows the song [id] as missing: its file failed to open (`DecodeException.missing`). */
    fun markMissing(id: String)
}

/**
 * A library whose songs say whether they have a chart (the last chart made for each is kept, [chartIds]), and whose
 * [remove] forgets a song's chart with it.
 */
class ChartedSongLibrary(
    private val library: SongLibrary,
    chartIds: StateFlow<Set<String>>,
    scope: CoroutineScope,
    private val onRemove: (String) -> Unit,
) : SongLibrary by library {
    override val songs: StateFlow<List<Song>> = combine(library.songs, chartIds) { songs, ids ->
        songs.map { if (it.id in ids) it.copy(chartCount = 1) else it }
    }.stateIn(scope, SharingStarted.Eagerly, library.songs.value)

    override fun song(id: String): Song? = songs.value.firstOrNull { it.id == id } ?: library.song(id)

    override fun remove(id: String) {
        library.remove(id)
        onRemove(id)
    }
}
