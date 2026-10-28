package io.ensomi.android.audio

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Mono PCM at the rate the generator asked for, already in the transport it takes: little-endian `f32` bytes.
 * The decoder writes those bytes as it resamples, so a full song never holds a float copy beside them.
 */
data class DecodedAudio(val leBytes: ByteArray, val sampleRate: Int) {
    val durationMs: Long
        get() = if (sampleRate == 0) 0 else frameCount * 1000L / sampleRate

    val frameCount: Int get() = leBytes.size / 4
}

/**
 * Decodes one library file to the mono PCM the generator reads. It resamples here rather than asking the
 * core to: the core's resampler needs the model's `resample-*` tables, and the Android build does not
 * ship them, so decoding to the rate the generator wants keeps that dependency out of the client.
 */
/** The generator takes its samples as little-endian `f32` bytes, not as one boxed object per sample. */
fun FloatArray.toLeBytes(): ByteArray {
    val bytes = ByteArray(size * 4)
    for (index in indices) {
        val bits = this[index].toRawBits()
        val offset = index * 4
        bytes[offset] = (bits and 0xFF).toByte()
        bytes[offset + 1] = ((bits shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((bits shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((bits shr 24) and 0xFF).toByte()
    }
    return bytes
}

object AudioDecoder {
    /** The rate the R2 pipeline reads; the model's own tables are built for it. */
    const val TARGET_RATE = 22_050

    /** The same decode for a file the app already holds. */
    fun decodeMono(file: File, targetRate: Int = TARGET_RATE): DecodedAudio {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return decodeDescriptor(descriptor, targetRate)
    }

    fun decodeMono(context: Context, uri: Uri, targetRate: Int = TARGET_RATE): DecodedAudio {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("the file could not be opened")
        return decodeDescriptor(descriptor, targetRate)
    }

    private fun decodeDescriptor(descriptor: ParcelFileDescriptor, targetRate: Int): DecodedAudio {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(descriptor.fileDescriptor)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            check(trackIndex >= 0 && format != null) { "the file has no audio track" }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("the track has no MIME type")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val pcm = ShortCollector()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: error("no input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        pcm.sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        pcm.channelCount = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            pcm.append(buffer, info.size)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            check(pcm.sampleRate > 0 && pcm.channelCount > 0) { "the decoder reported no audio format" }
            return DecodedAudio(pcm.toLeBytes(targetRate), targetRate)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            runCatching { descriptor.close() }
        }
    }

    /**
     * Decodes 16-bit PCM at the source rate, mixes it to mono and resamples it into the generator's transport
     * in one pass. The mix and the resample share a loop so a full song holds the source samples and the
     * bytes, not a float copy of both.
     */
    private class ShortCollector {
        private val samples = ShortList()
        var sampleRate = 0
        var channelCount = 0

        fun append(buffer: java.nio.ByteBuffer, size: Int) {
            val shorts = size / 2
            val chunk = ShortArray(shorts)
            buffer.asShortBuffer().get(chunk)
            samples.addAll(chunk)
        }

        /** One frame, mixed to mono and scaled to [-1, 1]. */
        private fun frameAt(frame: Int, channels: Int): Float {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += samples[frame * channels + channel] / 32_768f
            }
            return sum / channels
        }

        fun toLeBytes(targetRate: Int): ByteArray {
            val channels = channelCount.coerceAtLeast(1)
            val frames = samples.size / channels
            val ratio = sampleRate.toDouble() / targetRate
            val outFrames = (frames / ratio).toInt()
            val bytes = ByteArray(outFrames * 4)
            val lastFrame = (frames - 1).coerceAtLeast(0)
            for (index in 0 until outFrames) {
                val position = index * ratio
                val left = position.toInt().coerceAtMost(lastFrame)
                val right = (left + 1).coerceAtMost(lastFrame)
                val fraction = (position - left).toFloat()
                val value = frameAt(left, channels) * (1 - fraction) + frameAt(right, channels) * fraction
                val bits = value.toRawBits()
                val offset = index * 4
                bytes[offset] = (bits and 0xFF).toByte()
                bytes[offset + 1] = ((bits shr 8) and 0xFF).toByte()
                bytes[offset + 2] = ((bits shr 16) and 0xFF).toByte()
                bytes[offset + 3] = ((bits shr 24) and 0xFF).toByte()
            }
            return bytes
        }
    }


    private class ShortList {
        private var data = ShortArray(1 shl 16)
        var size = 0
            private set

        operator fun get(index: Int): Short = data[index]

        fun addAll(values: ShortArray) {
            if (size + values.size > data.size) {
                data = data.copyOf(maxOf(data.size * 2, size + values.size))
            }
            values.copyInto(data, size)
            size += values.size
        }
    }
}
