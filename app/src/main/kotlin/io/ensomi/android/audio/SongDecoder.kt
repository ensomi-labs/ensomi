package io.ensomi.android.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException

/** The channels to decode to: mono for making charts, stereo for play. */
enum class PcmChannels(val count: Int) { MONO(1), STEREO(2) }

/**
 * A song decoded to interleaved float PCM at the file's own rate, on the song's time: [leadFrames] of silence were put
 * first, or taken off when negative ([Mp3Lead]; AAC's priming is taken off too).
 */
class DecodedSong(
    val samples: FloatArray,
    val channels: Int,
    val sampleRate: Int,
    val leadFrames: Int,
    /** What the decoding went through, for logs. */
    val mime: String,
    val codec: String,
    val header: Mp3Header?,
) {
    val frames: Int get() = samples.size / channels
    val durationMs: Double get() = frames * 1000.0 / sampleRate
}

/** The file cannot be decoded; [missing] when it cannot even be opened, as for a file that has gone. */
class DecodeException(message: String, val missing: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

/**
 * Decodes a song with `MediaExtractor` and `MediaCodec`, preferring Android's own decoders (not a vendor's) so every
 * phone decodes alike. The platform's gapless trimming is turned off and the app's own applied, so the start of the
 * PCM is the same on every Android version: for MP3 the release engine's lead ([Mp3Lead]), for AAC the encoder delay
 * the container gives (its priming, which AVFoundation also trims). Other formats are as the decoder gives them.
 *
 * Blocking; call it off the main thread. [isCancelled] is checked between buffers.
 */
object SongDecoder {
    fun decode(
        context: Context,
        uri: Uri,
        channels: PcmChannels,
        isCancelled: () -> Boolean = { false },
    ): DecodedSong {
        val extractor = MediaExtractor()
        try {
            // Opened here, so that a file that has gone is told apart from one that is not audio.
            val file = try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")
            } catch (e: FileNotFoundException) {
                throw DecodeException("The file is not there.", missing = true, cause = e)
            } catch (e: SecurityException) {
                throw DecodeException("Ensomi may no longer read the file.", missing = true, cause = e)
            } ?: throw DecodeException("The file is not there.", missing = true)
            try {
                file.use { extractor.setDataSource(it) }
            } catch (e: IOException) {
                throw DecodeException("The file could not be read as audio.", cause = e)
            }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw DecodeException("The file has no audio.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val header: Mp3Header?
            val startTrim: Int
            when (mime) {
                MediaFormat.MIMETYPE_AUDIO_MPEG -> {
                    val head = readHead(context, uri)
                    header = Mp3Lead.header(head)
                    startTrim = -Mp3Lead.leadFrames(head)
                }
                MediaFormat.MIMETYPE_AUDIO_AAC -> {
                    header = null
                    startTrim = format.intOr(MediaFormat.KEY_ENCODER_DELAY, 0)
                }
                else -> {
                    header = null
                    startTrim = 0
                }
            }
            if (mime == MediaFormat.MIMETYPE_AUDIO_MPEG || mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                format.removeKey(MediaFormat.KEY_ENCODER_DELAY)
                format.removeKey(MediaFormat.KEY_ENCODER_PADDING)
            }
            // Raw PCM (WAV, and FLAC, which the extractor decodes itself) says in this key what its samples are;
            // for a compressed format it asks the decoder for float output.
            if (mime != MediaFormat.MIMETYPE_AUDIO_RAW) {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            }
            val durationUs = format.longOr(MediaFormat.KEY_DURATION, 0)
            val codec = createDecoder(mime, format)
            try {
                return decodeTrack(extractor, codec, format, mime, header, startTrim, durationUs, channels, isCancelled)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun decodeTrack(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        mime: String,
        header: Mp3Header?,
        startTrim: Int,
        durationUs: Long,
        channels: PcmChannels,
        isCancelled: () -> Boolean,
    ): DecodedSong {
        var sampleRate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
        var fileChannels = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var encoding = if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
            inputFormat.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val out = PcmBuilder(channels.count, estimateFrames(durationUs, sampleRate) + maxOf(0, -startTrim))
        out.addSilence(maxOf(0, -startTrim))
        var toSkip = maxOf(0, startTrim).toLong()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idleAfterInput = 0
        codec.start()
        while (true) {
            if (isCancelled()) throw CancellationException("Decoding stopped.")
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (inputDone && index == MediaCodec.INFO_TRY_AGAIN_LATER && ++idleAfterInput > MAX_IDLE_AFTER_INPUT) {
                // A decoder that never signals the end: what it gave is the song.
                break
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    fileChannels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, fileChannels)
                    encoding = format.intOr(MediaFormat.KEY_PCM_ENCODING, encoding)
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.order(ByteOrder.nativeOrder()).position(info.offset).limit(info.offset + info.size)
                    toSkip = out.add(buffer, encoding, fileChannels, toSkip)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        if (sampleRate <= 0) throw DecodeException("The decoder gave no sample rate.")
        return DecodedSong(out.build(), channels.count, sampleRate, -startTrim, mime, codec.name, header)
    }

    /** Android's own decoder for [mime] where there is one, else any; a vendor's may trim differently. */
    private fun createDecoder(mime: String, format: MediaFormat): MediaCodec {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        val ordered = decoders.sortedBy { info: MediaCodecInfo -> if (info.isVendor) 1 else 0 }
        // Float output where the decoder offers it, else its own encoding, which the builder converts.
        val integer = MediaFormat(format).apply { removeKey(MediaFormat.KEY_PCM_ENCODING) }
        for (info in ordered) {
            for (candidate in listOf(format, integer)) {
                val codec = try {
                    MediaCodec.createByCodecName(info.name)
                } catch (e: IOException) {
                    break
                } catch (e: IllegalArgumentException) {
                    break
                }
                try {
                    codec.configure(candidate, null, null, 0)
                    return codec
                } catch (e: IllegalArgumentException) {
                    codec.release()
                } catch (e: MediaCodec.CodecException) {
                    codec.release()
                } catch (e: IllegalStateException) {
                    codec.release()
                }
            }
        }
        throw DecodeException("This phone has no decoder for $mime.")
    }

    /** The bytes [Mp3Lead] reads: the ID3v2 tag and what follows it, as far as the scan goes. */
    private fun readHead(context: Context, uri: Uri): ByteArray {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: FileNotFoundException) {
            throw DecodeException("The file is not there.", missing = true, cause = e)
        } ?: throw DecodeException("The file could not be opened.", missing = true)
        return stream.use { input ->
            val head = input.readUpTo(10)
            val needed = Mp3Lead.bytesNeeded(head)
            head + input.readUpTo(needed - head.size)
        }
    }

    private fun InputStream.readUpTo(count: Int): ByteArray {
        val bytes = ByteArray(maxOf(0, count))
        var read = 0
        while (read < bytes.size) {
            val n = read(bytes, read, bytes.size - read)
            if (n < 0) break
            read += n
        }
        return if (read == bytes.size) bytes else bytes.copyOf(read)
    }

    private fun estimateFrames(durationUs: Long, sampleRate: Int): Int {
        if (durationUs <= 0 || sampleRate <= 0) return sampleRate.coerceAtLeast(48_000) * 60
        // A second more than the container says, so a slightly short duration needs no copy.
        return ((durationUs + 1_000_000) * sampleRate / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong() / 2).toInt()
    }

    private fun MediaFormat.intOr(key: String, default: Int) = if (containsKey(key)) getInteger(key) else default

    private fun MediaFormat.longOr(key: String, default: Long) = if (containsKey(key)) getLong(key) else default

    private const val TIMEOUT_US = 10_000L

    /** Five seconds of empty polls after the last input. */
    private const val MAX_IDLE_AFTER_INPUT = 500
}

/**
 * Collects decoded buffers as float frames with [channels] channels: mono is the mean of the file's channels; stereo
 * is the first two, or the one channel twice.
 */
internal class PcmBuilder(private val channels: Int, estimatedFrames: Int) {
    private var samples = FloatArray(maxOf(1, estimatedFrames) * channels)
    private var size = 0

    fun addSilence(frames: Int) {
        ensure(frames * channels)
        size += frames * channels // The array starts zeroed.
    }

    /**
     * Adds the frames in [buffer], after skipping [skipFrames] of them; returns how many are still to skip.
     * [encoding] is an `AudioFormat.ENCODING_PCM_*`.
     */
    fun add(buffer: ByteBuffer, encoding: Int, fileChannels: Int, skipFrames: Long): Long {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val frameBytes = bytesPerSample * fileChannels
        val frames = buffer.remaining() / frameBytes
        val skip = minOf(skipFrames, frames.toLong()).toInt()
        buffer.position(buffer.position() + skip * frameBytes)
        val kept = frames - skip
        ensure(kept * channels)
        repeat(kept) {
            var left = 0f
            var right = 0f
            var sum = 0f
            for (c in 0 until fileChannels) {
                val value = read(buffer, encoding)
                sum += value
                if (c == 0) left = value
                if (c == 1) right = value
            }
            if (channels == 1) {
                samples[size++] = sum / fileChannels
            } else {
                samples[size++] = left
                samples[size++] = if (fileChannels == 1) left else right
            }
        }
        return skipFrames - skip
    }

    fun build(): FloatArray = if (size == samples.size) samples else samples.copyOf(size)

    private fun read(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> buffer.getFloat()
        AudioFormat.ENCODING_PCM_32BIT -> buffer.getInt() / 2_147_483_648f
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val b0 = buffer.get().toInt() and 0xFF
            val b1 = buffer.get().toInt() and 0xFF
            val b2 = buffer.get().toInt()
            (b2 shl 16 or (b1 shl 8) or b0) / 8_388_608f
        }
        AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xFF) - 128) / 128f
        else -> buffer.getShort() / 32_768f
    }

    private fun ensure(more: Int) {
        if (size + more <= samples.size) return
        samples = samples.copyOf(maxOf(size + more, samples.size + samples.size / 4))
    }
}
