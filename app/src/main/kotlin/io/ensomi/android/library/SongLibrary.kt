package io.ensomi.android.library

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * For the prototype's scenarios: fixed sample songs. [add] and [remove] change nothing. The app's library is
 * [DocumentSongLibrary].
 */
class SampleSongLibrary(initial: List<Song>) : SongLibrary {
    private val state = MutableStateFlow(initial)
    override val songs: StateFlow<List<Song>> = state.asStateFlow()

    override suspend fun add(documents: List<Uri>) = AddResult(0, 0, emptyList())

    override suspend fun findAgain(id: String, document: Uri) = false

    override fun remove(id: String) = Unit

    override fun refresh() = Unit

    override fun markMissing(id: String) = Unit

    companion object {
        /** Made-up songs; the prototype has no audio. */
        val SONGS = listOf(
            Song("lantern-walk", "Lantern Walk", "Mira Sato", 222_000, "lantern-walk.mp3", chartCount = 1),
            Song("northbound", "Northbound", "The Quiet Hours", 245_000, "northbound.flac"),
            Song("small-hours", "Small Hours", "Aoi", 178_000, "small-hours.m4a", chartCount = 1),
            Song("glass-garden", "Glass Garden", "Ren & Kei", 201_000, "Glass Garden.mp3"),
            Song("afterglow-run", "Afterglow Run", null, 312_000, "afterglow_run.ogg"),
        )

        /** The same songs with one whose file has gone. */
        val WITH_MISSING = SONGS.map { if (it.id == "glass-garden") it.copy(missing = true) else it }
    }
}
