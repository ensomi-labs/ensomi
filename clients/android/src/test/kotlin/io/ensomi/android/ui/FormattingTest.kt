package io.ensomi.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FormattingTest {
    @Test
    fun `a length reads as m colon ss`() {
        assertEquals("0:05", formatLength(5_000))
        assertEquals("3:52", formatLength(232_000))
        assertEquals("1:00", formatLength(60_000))
        assertEquals("unknown", formatLength(null))
        assertEquals("unknown", formatLength(0))
    }

    @Test
    fun `a song's length reads as m colon ss`() {
        assertEquals("0:05", formatSongLength(5_000))
        assertEquals("3:52", formatSongLength(232_000))
        assertEquals("1:00", formatSongLength(60_000))
        assertEquals("0:00", formatSongLength(0))
    }

    @Test
    fun `the saved chart is named after the song`() {
        assertEquals("fish long (Ensomi).osu", osuName("fish long"))
        assertEquals("fish long_ (Ensomi).osu", osuName("fish long!"))
        assertEquals("chart (Ensomi).osu", osuName("   "))
        assertEquals("fish long (Ensomi).osz", oszName("fish long"))
    }
}
