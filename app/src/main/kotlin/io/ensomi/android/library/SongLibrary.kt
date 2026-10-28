package io.ensomi.android.library

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A song the player added. [durationMs] is the decoded length; [fileName] is what the document provider calls it. */
data class Song(
    val id: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val fileName: String,
    /** Charts made from this song and kept on the device. */
    val chartCount: Int = 0,
)

/**
 * The songs Ensomi can make charts from: files the player picked with the system's document picker
 * (`ACTION_OPEN_DOCUMENT`), kept with a persistable read permission. No storage permission is needed.
 */
interface SongLibrary {
    val songs: StateFlow<List<Song>>

    fun song(id: String): Song? = songs.value.firstOrNull { it.id == id }

    /** Adds the documents the picker returned. */
    fun add(documents: List<Uri>)

    /** Forgets a song; the file itself stays where it is. */
    fun remove(id: String)
}

/**
 * Not wired: fixed sample songs for the prototype. [add] and [remove] change nothing; the real library (plan step 2)
 * reads the picked documents' names and lengths and keeps them in app storage.
 */
class SampleSongLibrary(initial: List<Song>) : SongLibrary {
    private val state = MutableStateFlow(initial)
    override val songs: StateFlow<List<Song>> = state.asStateFlow()

    override fun add(documents: List<Uri>) = Unit

    override fun remove(id: String) = Unit

    companion object {
        /** Made-up songs; the prototype has no audio. */
        val SONGS = listOf(
            Song("lantern-walk", "Lantern Walk", "Mira Sato", 222_000, "lantern-walk.mp3", chartCount = 1),
            Song("northbound", "Northbound", "The Quiet Hours", 245_000, "northbound.flac"),
            Song("small-hours", "Small Hours", "Aoi", 178_000, "small-hours.m4a", chartCount = 1),
            Song("glass-garden", "Glass Garden", "Ren & Kei", 201_000, "Glass Garden.mp3"),
            Song("afterglow-run", "Afterglow Run", null, 312_000, "afterglow_run.ogg"),
        )
    }
}
