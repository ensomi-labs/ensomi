package io.ensomi.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer

/** What the player is doing, for the page that owns it. */
enum class PlayerState { IDLE, LOADING, PLAYING, ENDED, FAILED }

/**
 * Plays one library file and reports the audio time being heard.
 *
 * The position comes from `AudioTrack.getTimestamp`, not from how far the decoder has read: the
 * contract in the design is the audio heard, and a decoder runs ahead of the speaker by its buffer.
 * That is the same reading [io.ensomi.android.clock.AudioClock] anchors on.
 */
class LibraryPlayer(private val context: Context) {
    var state by mutableStateOf(PlayerState.IDLE)
        private set

    var failure by mutableStateOf<String?>(null)
        private set

    @Volatile
    private var stopped = false

    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var sampleRate = 0
    private var channelCount = 0
    private var framesWritten = 0L

    val isActive: Boolean
        get() = state == PlayerState.PLAYING || state == PlayerState.LOADING

    fun play(uri: Uri) {
        if (isActive) return
        stopped = false
        failure = null
        state = PlayerState.LOADING
        thread = Thread({ pump(uri) }, "ensomi-player").also { it.start() }
    }

    fun stop() {
        stopped = true
        thread?.interrupt()
        thread = null
        releaseTrack()
        state = PlayerState.IDLE
    }

    /** The audio time heard, in milliseconds, or null before the track has played anything. */
    fun positionMs(): Double? {
        val audioTrack = track ?: return null
        if (sampleRate == 0) return null
        val stamp = AudioTimestamp()
        // On API 36 this returns whether the device could answer, not a status code.
        val available = audioTrack.getTimestamp(stamp)
        val frames = if (available) stamp.framePosition else audioTrack.playbackHeadPosition.toLong()
        return frames.toDouble() * 1000.0 / sampleRate
    }

    private fun pump(uri: Uri) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        // The extractor reads from the descriptor while the loop runs, so it must outlive setDataSource.
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("the file could not be opened")
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
            var inputDone = false
            var outputDone = false

            while (!stopped && !outputDone) {
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
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startTrack(codec.outputFormat)

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0 && track != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            writeFully(buffer, info.size)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            waitForPlaybackEnd()
            if (!stopped) state = PlayerState.ENDED
        } catch (error: Throwable) {
            if (!stopped) {
                failure = error.message ?: error::class.java.simpleName
                state = PlayerState.FAILED
            }
        } finally {
            releaseTrack()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            runCatching { descriptor.close() }
        }
    }

    private fun startTrack(format: MediaFormat) {
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val channelMask =
            if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, 32_768))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }

        state = PlayerState.PLAYING
    }

    private fun writeFully(buffer: ByteBuffer, size: Int) {
        val bytes = ByteArray(size)
        buffer.get(bytes)
        var offset = 0
        while (offset < bytes.size && !stopped) {
            val written = track?.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING) ?: return
            if (written <= 0) break
            offset += written
            framesWritten += written / (2 * maxOf(channelCount, 1))
        }
    }

    /** EOS is the decoder's end, not the speaker's: wait until the head reaches what was written. */
    private fun waitForPlaybackEnd() {
        val audioTrack = track ?: return
        while (!stopped && audioTrack.playbackHeadPosition.toLong() < framesWritten) {
            Thread.sleep(20)
        }
    }

    private fun releaseTrack() {
        track?.let { audioTrack ->
            runCatching { audioTrack.stop() }
            runCatching { audioTrack.release() }
        }
        track = null
        framesWritten = 0L
    }
}
