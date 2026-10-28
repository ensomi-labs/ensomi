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
    fun `a long path keeps both ends`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val short = middleTruncate(uri, 20)

        assertEquals("content:...%3AMusic", short)
        assertEquals(true, short.length <= 20)
    }

    @Test
    fun `a path that fits is untouched`() {
        assertEquals("content://x", middleTruncate("content://x", 40))
    }
}
