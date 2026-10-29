package io.ensomi.android.audio

/**
 * What the first MPEG Layer III frame of a file carries, in the classes of ensomi-model's release engine (its
 * `audio_rows/decoder.py`), which puts every song on osu!'s clock, BASS's: the one R2 was fitted to and charts are made
 * and played on.
 */
sealed interface Mp3Header {
    /** A Xing or Info header with a LAME extension: its encoder string and its encoder delay and padding fields. */
    data class Lame(val encoder: String, val delay: Int, val padding: Int) : Mp3Header

    /** A Xing or Info header without a LAME extension. */
    data object Xing : Mp3Header

    data object Vbri : Mp3Header

    /** A Layer III frame with neither header. */
    data object None : Mp3Header

    /** No Layer III frame followed by another in the bytes looked at. */
    data object NoSync : Mp3Header
}

/**
 * The lead of an MP3: how many frames to put before what Android's decoder gives, or to take off its start when
 * negative, so that the PCM is on the song's time.
 *
 * BASS trims an MP3's encoder delay and the 529-frame Layer III decoder delay only when a LAME extension gives a
 * non-zero encoder delay, and trims nothing otherwise. Android's own MP3 decoder (`c2.android.mp3.decoder`) trims the
 * decoder delay from every file, and the extractor skips a Xing, Info or VBRI frame, as BASS does. The app decodes
 * without the platform's gapless trimming (it removes the extractor's encoder delay and padding from the format), so
 * the decoder's 529 frames are the only ones gone, whatever the header. Hence:
 *
 * - no header, a Xing or Info header without a LAME extension, or one with an encoder delay of 0: 529 frames of
 *   silence first, as BASS keeps them (the Swift app's lead, the same for the same reason);
 * - a LAME encoder delay d > 0: the first d frames off, as BASS trims them with the decoder delay;
 * - a VBRI header, no Layer III sync: as the decoder gives it, which is how the Swift app leaves them too (what the
 *   release engine does with them has not been measured);
 * - an iTunes gapless comment (`iTunSMPB`) in the ID3v2 tag: its priming off, which is what AVFoundation trims and so
 *   how the Swift app plays it (also not measured against the release engine).
 *
 * The frames are at the file's own rate. The end of the file is left as the decoder gives it.
 */
object Mp3Lead {
    /** The Layer III decoder delay, in frames, for MPEG-1, 2 and 2.5 alike. */
    const val DECODER_DELAY_FRAMES = 529

    /** How far past an ID3v2 tag the first frame is looked for, as the release engine looks. */
    const val SCAN_BYTES = 65_536

    /** The frames BASS trims from the start of a file with [header]. */
    fun releaseEngineTrim(header: Mp3Header): Int =
        if (header is Mp3Header.Lame && header.delay > 0) header.delay + DECODER_DELAY_FRAMES else 0

    /**
     * The lead for a file whose first bytes are [bytes] (its ID3v2 tag and at least [SCAN_BYTES] after it, or all of
     * it), for a decoder that trims [decoderTrim] frames from the start.
     */
    fun leadFrames(bytes: ByteArray, decoderTrim: Int = DECODER_DELAY_FRAMES): Int {
        val priming = iTunesPriming(bytes)
        if (priming != null) return decoderTrim - DECODER_DELAY_FRAMES - priming
        return when (val header = header(bytes)) {
            is Mp3Header.Lame, Mp3Header.Xing, Mp3Header.None -> decoderTrim - releaseEngineTrim(header)
            Mp3Header.Vbri, Mp3Header.NoSync -> 0
        }
    }

    /** How many bytes of a file [leadFrames] needs, given its first ten bytes (or fewer, for a short file). */
    fun bytesNeeded(head: ByteArray): Int = id3v2Bytes(head) + SCAN_BYTES

    /**
     * The header class: past one ID3v2 tag (and its footer), the first Layer III frame whose next frame also syncs,
     * within [SCAN_BYTES]. A Xing or Info header follows the frame's side information; a LAME extension follows the
     * header's optional fields and starts with four printable characters.
     */
    fun header(bytes: ByteArray): Mp3Header {
        val start = id3v2Bytes(bytes)
        val end = minOf(bytes.size, start + SCAN_BYTES)
        for (j in start until end - 4) {
            val first = frameAt(bytes, j, end) ?: continue
            if (frameAt(bytes, j + first.length, end) == null) continue
            if (matches(bytes, j + 36, end, "VBRI")) return Mp3Header.Vbri
            val x = j + 4 + first.sideInfoBytes
            if (!matches(bytes, x, end, "Xing") && !matches(bytes, x, end, "Info")) return Mp3Header.None
            // A header or extension cut short by the end of the bytes counts as one without an extension.
            if (x + 8 > end) return Mp3Header.Xing
            val flags = int32(bytes, x + 4)
            var p = x + 8
            if (flags and 1 != 0) p += 4 // frames
            if (flags and 2 != 0) p += 4 // bytes
            if (flags and 4 != 0) p += 100 // table of contents
            if (flags and 8 != 0) p += 4 // quality
            if (p + 23 > end || (0 until 4).any { (bytes[p + it].toInt() and 0xFF) !in 32..126 }) return Mp3Header.Xing
            val encoder = String(CharArray(9) { (bytes[p + it].toInt() and 0xFF).toChar() }).trim('\u0000', ' ')
            val b21 = bytes[p + 21].toInt() and 0xFF
            val b22 = bytes[p + 22].toInt() and 0xFF
            val b23 = if (p + 24 <= end) bytes[p + 23].toInt() and 0xFF else 0
            return Mp3Header.Lame(encoder, delay = b21 shl 4 or (b22 shr 4), padding = (b22 and 0x0F) shl 8 or b23)
        }
        return Mp3Header.NoSync
    }

    /**
     * The priming of an iTunes gapless comment in the ID3v2 tag at the start of [bytes] (`iTunSMPB`: hexadecimal
     * fields, the second of which is the priming in frames), or null when there is none.
     */
    fun iTunesPriming(bytes: ByteArray): Int? {
        val tagEnd = minOf(bytes.size, id3v2Bytes(bytes))
        val key = "iTunSMPB".toByteArray(Charsets.US_ASCII)
        val at = (0..tagEnd - key.size).firstOrNull { i -> key.indices.all { bytes[i + it] == key[it] } } ?: return null
        // The comment's text follows its description, in Latin-1 or UTF-16: keep the ASCII, drop the zero bytes.
        val text = buildString {
            for (i in at + key.size until minOf(tagEnd, at + key.size + 400)) {
                val c = bytes[i].toInt() and 0xFF
                when {
                    c == 0 -> Unit
                    c in 0x20..0x7E -> append(c.toChar())
                    else -> append(' ')
                }
            }
        }
        val fields = text.trim().split(Regex("\\s+")).filter { f -> f.isNotEmpty() && f.all { it.isLetterOrDigit() } }
        val hex = fields.dropWhile { it.any { c -> c !in HEX } }
        return hex.getOrNull(1)?.takeIf { f -> f.all { it in HEX } }?.toLongOrNull(16)?.toInt()
    }

    /** The bytes an ID3v2 tag at the start of [bytes] takes with its header and footer, or 0. */
    private fun id3v2Bytes(bytes: ByteArray): Int {
        if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) {
            return 0
        }
        var size = 0
        for (i in 6..9) size = size shl 7 or (bytes[i].toInt() and 0x7F)
        val footer = if (bytes[5].toInt() and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }

    private class Frame(val length: Int, val sideInfoBytes: Int)

    private val BITRATES_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATES_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private const val HEX = "0123456789abcdefABCDEF"

    /** By the version bits: 3 is MPEG-1, 2 MPEG-2, 0 MPEG-2.5. */
    private fun sampleRates(version: Int): IntArray? = when (version) {
        3 -> intArrayOf(44_100, 48_000, 32_000)
        2 -> intArrayOf(22_050, 24_000, 16_000)
        0 -> intArrayOf(11_025, 12_000, 8_000)
        else -> null
    }

    /** The Layer III frame whose header starts at [j], or null. */
    private fun frameAt(b: ByteArray, j: Int, end: Int): Frame? {
        if (j < 0 || j + 4 > end) return null
        val b0 = b[j].toInt() and 0xFF
        val b1 = b[j + 1].toInt() and 0xFF
        val b2 = b[j + 2].toInt() and 0xFF
        val b3 = b[j + 3].toInt() and 0xFF
        if (b0 != 0xFF || b1 and 0xE0 != 0xE0) return null
        val version = b1 shr 3 and 3
        val layer = b1 shr 1 and 3
        val bitrateIndex = b2 shr 4
        val rateIndex = b2 shr 2 and 3
        if (layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val sampleRate = sampleRates(version)?.get(rateIndex) ?: return null
        val mpeg1 = version == 3
        val mono = b3 shr 6 == 3
        val bitrate = (if (mpeg1) BITRATES_V1 else BITRATES_V2)[bitrateIndex] * 1_000
        return Frame(
            length = (if (mpeg1) 144 else 72) * bitrate / sampleRate + (b2 shr 1 and 1),
            sideInfoBytes = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17),
        )
    }

    private fun matches(b: ByteArray, at: Int, end: Int, tag: String): Boolean =
        at >= 0 && at + tag.length <= end && tag.indices.all { b[at + it] == tag[it].code.toByte() }

    private fun int32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)
}
