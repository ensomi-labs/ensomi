package io.ensomi.android.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFilesTest {
    @Test
    fun `the extensions the library indexes are the audio ones`() {
        assertTrue(AudioFiles.isAudioName("song.mp3"))
        assertTrue(AudioFiles.isAudioName("song.FLAC"))
        assertTrue(AudioFiles.isAudioName("song.m4a"))
        assertTrue(AudioFiles.isAudioName("song.opus"))
        assertFalse(AudioFiles.isAudioName("cover.jpg"))
        assertFalse(AudioFiles.isAudioName("song"))
        assertFalse(AudioFiles.isAudioName(".nomedia"))
    }

    @Test
    fun `a provider that reports an audio mime type is believed`() {
        assertTrue(AudioFiles.isAudio("track.bin", "audio/mpeg"))
        assertTrue(AudioFiles.isAudio("track", "audio/x-flac"))
        assertFalse(AudioFiles.isAudio("track.bin", "video/mp4"))
        assertFalse(AudioFiles.isAudio("track.bin", null))
    }

    @Test
    fun `a file name without an extension becomes its own title`() {
        assertEquals("song", AudioFiles.titleFromName("song.mp3"))
        assertEquals("song", AudioFiles.titleFromName("song"))
        assertEquals("song.name", AudioFiles.titleFromName("song.name.flac"))
    }
}

class LibraryEntryTest {
    @Test
    fun `the display title prefers metadata and falls back to the file name`() {
        val withTitle = entry(title = "Real Title", name = "file01.mp3")
        assertEquals("Real Title", withTitle.displayTitle)

        val blankTitle = entry(title = "   ", name = "file01.mp3")
        assertEquals("file01", blankTitle.displayTitle)

        val noTitle = entry(title = null, name = "file01.flac")
        assertEquals("file01", noTitle.displayTitle)
    }

    @Test
    fun `a failed entry still has a name to show`() {
        val failed = entry(title = null, name = "broken.wav", failure = "could not decode")
        assertEquals("broken", failed.displayTitle)
        assertEquals("could not decode", failed.failure)
    }

    private fun entry(title: String?, name: String, failure: String? = null) = LibraryEntry(
        uri = "content://test/$name",
        name = name,
        treeUri = "content://tree/test",
        sizeBytes = 1024,
        durationMs = 180_000,
        title = title,
        artist = null,
        album = null,
        indexedAtMs = 0,
        failure = failure,
    )
}
