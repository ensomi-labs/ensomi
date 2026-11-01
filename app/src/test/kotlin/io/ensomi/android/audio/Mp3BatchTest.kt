package io.ensomi.android.audio

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Mp3BatchTest {
    private fun header(vararg bytes: Int): Int? = mp3FrameFormat(ByteBuffer.wrap(bytes.map { it.toByte() }.toByteArray()), 0, bytes.size)

    @Test fun rateVersionAndChannelChangesEndABatch() {
        val stereo = header(0xff, 0xfb, 0x90, 0x00)
        assertNotNull(stereo)
        assertEquals(stereo, header(0xff, 0xfa, 0xa2, 0x40)) // CRC, bitrate, padding and joint stereo may vary.
        assertNotEquals(stereo, header(0xff, 0xfb, 0x94, 0x00))
        assertNotEquals(stereo, header(0xff, 0xf3, 0x90, 0x00))
        assertNotEquals(stereo, header(0xff, 0xfb, 0x90, 0xc0))
    }

    @Test fun invalidOrShortHeadersAreNotBatched() {
        assertNull(header(0xff, 0xfb, 0x90))
        assertNull(header(0x00, 0xfb, 0x90, 0x00))
        assertNull(header(0xff, 0xeb, 0x90, 0x00)) // Reserved version.
        assertNull(header(0xff, 0xfd, 0x90, 0x00)) // Layer II.
        assertNull(header(0xff, 0xfb, 0x9c, 0x00)) // Reserved sample rate.
    }

    @Test fun respectsTheExtractorOffset() {
        val bytes = byteArrayOf(0, 0, 0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0)
        assertEquals(header(0xff, 0xfb, 0x90, 0), mp3FrameFormat(ByteBuffer.wrap(bytes), 2, 4))
    }
}
