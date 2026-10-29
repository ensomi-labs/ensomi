package io.ensomi.android.library

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFileTest {
    private val song = StoredSong(
        id = "a",
        uri = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fwalk.mp3",
        fileName = "walk.mp3",
        title = "Lantern Walk",
        artist = "Mira Sato",
        durationMs = 222_000,
        addedAtMs = 1_700_000_000_000,
    )

    @Test
    fun theLibraryReadsBackAsItWasWritten() {
        val library = StoredLibrary(listOf(song, song.copy(id = "b", uri = "content://x/2", title = null, artist = null)))
        assertEquals(library, LibraryFile.decode(LibraryFile.encode(library)))
    }

    @Test
    fun unknownFieldsAreSkippedAndAFileThatDoesNotParseIsEmpty() {
        val newer = """{"songs":[{"id":"a","uri":"content://x/1","fileName":"x.flac","rating":5}],"version":3}"""
        assertEquals(listOf(StoredSong("a", "content://x/1", "x.flac")), LibraryFile.decode(newer).songs)
        assertEquals(StoredLibrary(), LibraryFile.decode("{not json"))
    }

    @Test
    fun aSongWithoutATitleTagShowsItsFileName() {
        assertEquals("Lantern Walk", song.shownTitle)
        assertEquals("afterglow_run", song.copy(title = null, fileName = "afterglow_run.ogg").shownTitle)
        assertEquals("Glass Garden.final", song.copy(title = " ", fileName = "Glass Garden.final.ogg").shownTitle)
        assertEquals(".mp3", song.copy(title = null, fileName = ".mp3").shownTitle)
    }

    @Test
    fun songsAreListedByTitleWhateverTheirCase() {
        val songs = listOf(
            song.copy(id = "1", title = "northbound"),
            song.copy(id = "2", title = "Afterglow Run"),
            song.copy(id = "3", title = "Lantern Walk", artist = "B"),
            song.copy(id = "4", title = "lantern walk", artist = "A"),
        )
        assertEquals(listOf("2", "4", "3", "1"), LibraryFile.sorted(songs, Locale.ENGLISH).map { it.id })
    }
}
