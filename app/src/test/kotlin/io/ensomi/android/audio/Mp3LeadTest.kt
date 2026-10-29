package io.ensomi.android.audio

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The release engine's MP3 header classes, on frames built here, and the lead each gives on Android. */
class Mp3LeadTest {
    @Test
    fun aFileWithoutAHeaderStartsWithTheDecoderDelayAsSilence() {
        val file = frames(stereoFrame(), stereoFrame(), stereoFrame())
        assertEquals(Mp3Header.None, Mp3Lead.header(file))
        assertEquals(529, Mp3Lead.leadFrames(file))
    }

    @Test
    fun aXingHeaderWithoutALameExtensionKeepsTheDecoderDelay() {
        val file = frames(stereoFrame { xing("Xing", flags = 0x0F) }, stereoFrame())
        assertEquals(Mp3Header.Xing, Mp3Lead.header(file))
        assertEquals(529, Mp3Lead.leadFrames(file))
    }

    @Test
    fun aLameDelayIsTrimmedWithTheDecoderDelayAsTheReleaseEngineDoes() {
        val file = frames(stereoFrame { xing("Info", flags = 0x0F, lame = "LAME3.100", delay = 576, padding = 1_234) }, stereoFrame())
        assertEquals(Mp3Header.Lame("LAME3.100", delay = 576, padding = 1_234), Mp3Lead.header(file))
        assertEquals(576 + 529, Mp3Lead.releaseEngineTrim(Mp3Lead.header(file)))
        // Android's decoder has trimmed the 529 already: the encoder delay is what is left to take off.
        assertEquals(-576, Mp3Lead.leadFrames(file))
        // A decoder that trimmed nothing would need both off.
        assertEquals(-(576 + 529), Mp3Lead.leadFrames(file, decoderTrim = 0))
    }

    @Test
    fun theLameExtensionFollowsOnlyTheFieldsTheFlagsName() {
        val file = frames(stereoFrame { xing("Xing", flags = 0x01, lame = "Lavc61.19", delay = 1_105, padding = 0) }, stereoFrame())
        assertEquals(Mp3Header.Lame("Lavc61.19", delay = 1_105, padding = 0), Mp3Lead.header(file))
    }

    @Test
    fun aLameDelayOfZeroCountsAsNoDelay() {
        val file = frames(stereoFrame { xing("Info", flags = 0x0F, lame = "LAME3.99r", delay = 0, padding = 0) }, stereoFrame())
        assertEquals(Mp3Header.Lame("LAME3.99r", 0, 0), Mp3Lead.header(file))
        assertEquals(0, Mp3Lead.releaseEngineTrim(Mp3Lead.header(file)))
        assertEquals(529, Mp3Lead.leadFrames(file))
    }

    @Test
    fun aMonoMpeg2FrameHasShorterSideInformation() {
        val file = frames(mpeg2MonoFrame { xing("Xing", flags = 0x0F, lame = "LAME3.100", delay = 576, padding = 0) }, mpeg2MonoFrame())
        assertEquals(Mp3Header.Lame("LAME3.100", 576, 0), Mp3Lead.header(file))
    }

    @Test
    fun vbriAndNoSyncAreLeftAsTheDecoderGivesThem() {
        val vbri = frames(stereoFrame { put(36, "VBRI".toByteArray()) }, stereoFrame())
        assertEquals(Mp3Header.Vbri, Mp3Lead.header(vbri))
        assertEquals(0, Mp3Lead.leadFrames(vbri))
        val noise = ByteArray(5_000) { (it * 7 % 251).toByte() }
        assertEquals(Mp3Header.NoSync, Mp3Lead.header(noise))
        assertEquals(0, Mp3Lead.leadFrames(noise))
    }

    @Test
    fun aFrameWhoseNextFrameDoesNotSyncIsPassedBy() {
        // A stray sync word in the tag's padding, then the real frames.
        val stray = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00, 1, 2, 3)
        val file = frames(stray, stereoFrame { xing("Xing", flags = 0x0F) }, stereoFrame())
        assertEquals(Mp3Header.Xing, Mp3Lead.header(file))
    }

    @Test
    fun anId3v2TagIsSkippedAndAnITunesGaplessCommentGivesItsPriming() {
        val tag = id3v2(comm("iTunSMPB", " 00000000 00000840 000001C0 0000000000ABCDEF 00000000"))
        val file = frames(tag, stereoFrame { xing("Info", flags = 0x0F, lame = "LAME3.100", delay = 576, padding = 0) }, stereoFrame())
        assertEquals(Mp3Header.Lame("LAME3.100", 576, 0), Mp3Lead.header(file))
        assertEquals(0x840, Mp3Lead.iTunesPriming(file))
        // As AVFoundation plays it: the priming off as well as the decoder delay.
        assertEquals(-0x840, Mp3Lead.leadFrames(file))
        val plain = frames(id3v2(comm("", "a comment")), stereoFrame(), stereoFrame())
        assertNull(Mp3Lead.iTunesPriming(plain))
        assertEquals(Mp3Header.None, Mp3Lead.header(plain))
        assertEquals(tag.size + Mp3Lead.SCAN_BYTES, Mp3Lead.bytesNeeded(file.copyOf(10)))
    }

    // MPEG-1 Layer III, 128 kbit/s, 44.1 kHz, stereo: 417 bytes, 32 bytes of side information.
    private fun stereoFrame(fill: FrameBytes.() -> Unit = {}) =
        FrameBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00), length = 417, sideInfo = 32).apply(fill).bytes

    // MPEG-2 Layer III, 64 kbit/s, 22.05 kHz, mono: 208 bytes, 9 bytes of side information.
    private fun mpeg2MonoFrame(fill: FrameBytes.() -> Unit = {}) =
        FrameBytes(byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x80.toByte(), 0xC0.toByte()), length = 208, sideInfo = 9).apply(fill).bytes

    private class FrameBytes(header: ByteArray, length: Int, private val sideInfo: Int) {
        val bytes = ByteArray(length).also { header.copyInto(it) }

        fun put(at: Int, value: ByteArray) = value.copyInto(bytes, at)

        /** A Xing or Info header after the side information, with its optional fields and a LAME extension. */
        fun xing(tag: String, flags: Int, lame: String? = null, delay: Int = 0, padding: Int = 0) {
            var p = 4 + sideInfo
            put(p, tag.toByteArray())
            put(p + 4, byteArrayOf(0, 0, 0, flags.toByte()))
            p += 8
            if (flags and 1 != 0) p += 4
            if (flags and 2 != 0) p += 4
            if (flags and 4 != 0) p += 100
            if (flags and 8 != 0) p += 4
            if (lame != null) {
                put(p, lame.padEnd(9, ' ').toByteArray())
                put(p + 21, byteArrayOf((delay shr 4).toByte(), ((delay and 0xF) shl 4 or (padding shr 8)).toByte(), padding.toByte()))
            }
        }
    }

    private fun frames(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    /** An ID3v2.3 tag holding [frames], its size syncsafe. */
    private fun id3v2(vararg frames: ByteArray): ByteArray {
        val body = frames(*frames) + ByteArray(64)
        val size = body.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            (size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(), (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte(),
        )
        return header + body
    }

    /** A COMM frame in Latin-1 with [description] and [text]. */
    private fun comm(description: String, text: String): ByteArray {
        val body = byteArrayOf(0) + "eng".toByteArray() + description.toByteArray() + byteArrayOf(0) + text.toByteArray()
        val size = body.size
        return "COMM".toByteArray() +
            byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte(), 0, 0) + body
    }
}
