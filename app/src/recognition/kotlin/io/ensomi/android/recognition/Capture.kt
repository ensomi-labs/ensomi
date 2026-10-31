package io.ensomi.android.recognition

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Receives capture on the capture thread. [captureNanos] is the first sample's capture time on `CLOCK_MONOTONIC`. */
interface CaptureSink {
    fun block(samples: FloatArray, count: Int, sampleRate: Int, captureNanos: Long)

    /** Once a second: what the room sounded like, and how the blocks are being stamped. */
    fun level(rmsDb: Double, peakDb: Double, stamps: String)

    fun failed(reason: String)
}

/** Where Sync's audio comes from. Nothing a capture records leaves the device; it goes only to the aligner. */
interface CapturePort {
    /** For the trace: the source and format. */
    val description: String

    fun start(sink: CaptureSink)

    /** Stops and waits for the capture thread. */
    fun stop()

    /** Where in its file a source that knows what it plays was at [captureNanos]; null for the microphone. */
    fun truthMs(captureNanos: Long): Double? = null
}

/** A second's level of what a capture passed through. */
private class LevelMeter(private val sink: CaptureSink) {
    private var frames = 0
    private var sum = 0.0
    private var peak = 0f

    fun add(samples: FloatArray, count: Int, sampleRate: Int, stamps: () -> String) {
        for (i in 0 until count) {
            val value = samples[i]
            sum += value * value
            if (abs(value) > peak) peak = abs(value)
        }
        frames += count
        if (frames >= sampleRate) {
            sink.level(decibels(sqrt(sum / frames)), decibels(peak.toDouble()), stamps())
            frames = 0
            sum = 0.0
            peak = 0f
        }
    }

    private fun decibels(value: Double): Double = if (value > 0) 20 * log10(value) else -120.0
}

/**
 * The microphone at 48 kHz mono in float, from `UNPROCESSED` where the device has it, else `VOICE_RECOGNITION`, the
 * two with the least done to them. Android gives the microphone to the app on top, so this runs only while Ensomi is in
 * front ([SessionMachine]), and the level goes to the trace so that silence shows.
 *
 * A block's time comes from `AudioRecord.getTimestamp` on the monotonic clock: a frame position and when that frame
 * was captured, carried to the block's first frame at the sample rate. The moment `read()` returned says when the app
 * got to it, not when the sound was heard, so it is used only while no timestamp is there, and the trace says so.
 */
class MicCapture(context: Context) : CapturePort {
    private val unprocessed = context.getSystemService(AudioManager::class.java)
        .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    private val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION

    @Volatile
    private var running = false
    private var thread: Thread? = null

    override val description: String
        get() = "microphone ${if (unprocessed) "UNPROCESSED" else "VOICE_RECOGNITION"} $SAMPLE_RATE Hz"

    override fun start(sink: CaptureSink) {
        running = true
        thread = Thread({ run(sink) }, "EnsomiCapture").also { it.start() }
    }

    override fun stop() {
        running = false
        thread?.join(STOP_WAIT_MS)
        thread = null
    }

    @SuppressLint("MissingPermission") // The session asks for the microphone; a refusal is reported as a failure.
    private fun open(encoding: Int): AudioRecord? {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
        if (minimum <= 0) return null
        val record = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(encoding)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum, SAMPLE_RATE * Float.SIZE_BYTES))
            .build()
        if (record.state == AudioRecord.STATE_INITIALIZED) return record
        record.release()
        return null
    }

    private fun run(sink: CaptureSink) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val record = try {
            open(AudioFormat.ENCODING_PCM_FLOAT) ?: open(AudioFormat.ENCODING_PCM_16BIT)
        } catch (failure: Exception) {
            // SecurityException without the permission, UnsupportedOperationException on a format it will not take.
            sink.failed(failure.message ?: failure.javaClass.simpleName)
            return
        }
        if (record == null) {
            sink.failed("AudioRecord could not be opened")
            return
        }
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                sink.failed("The microphone did not start: another app may be using it")
                return
            }
            read(record, sink)
        } catch (failure: IllegalStateException) {
            sink.failed(failure.message ?: "AudioRecord failed")
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun read(record: AudioRecord, sink: CaptureSink) {
        val float = record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
        val samples = FloatArray(BLOCK_FRAMES)
        val shorts = ShortArray(if (float) 0 else BLOCK_FRAMES)
        val timestamp = AudioTimestamp()
        val meter = LevelMeter(sink)
        var frames = 0L
        var stamped = 0L
        var unstamped = 0L
        var lastGapMs = 0.0
        while (running) {
            val count = if (float) {
                record.read(samples, 0, BLOCK_FRAMES, AudioRecord.READ_BLOCKING)
            } else {
                record.read(shorts, 0, BLOCK_FRAMES, AudioRecord.READ_BLOCKING).also { n ->
                    for (i in 0 until n) samples[i] = shorts[i] / 32_768f
                }
            }
            if (count < 0) {
                sink.failed("AudioRecord.read returned $count")
                return
            }
            if (count == 0) continue
            val returned = System.nanoTime()
            val hasStamp = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
            val first = if (hasStamp) {
                stamped++
                timestamp.nanoTime + (frames - timestamp.framePosition) * NANOS_PER_SECOND / SAMPLE_RATE
            } else {
                unstamped++
                returned - count * NANOS_PER_SECOND / SAMPLE_RATE
            }
            frames += count
            // How long after the block's last sample this thread had it: large or negative means the stamps are wrong.
            lastGapMs = (returned - (first + count * NANOS_PER_SECOND / SAMPLE_RATE)) / 1e6
            sink.block(samples, count, SAMPLE_RATE, first)
            meter.add(samples, count, SAMPLE_RATE) { "timestamp=$stamped read=$unstamped gap_ms=$lastGapMs frames=$frames" }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BLOCK_FRAMES = 960
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val STOP_WAIT_MS = 2_000L
    }
}

/**
 * A WAV file in the app's storage played into the session in real time instead of the microphone, so that an emulator
 * can run the whole path: mono or stereo, 16-bit or float, at the file's own rate. Blocks are stamped as if the sound
 * were heard as it is read. [startMs] skips into the file, which is a room that was already playing.
 */
class WavReplayCapture(private val file: File, private val startMs: Long) : CapturePort {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    private var startNanos = 0L

    override val description: String get() = "replay ${file.name} from $startMs ms"

    override fun truthMs(captureNanos: Long): Double = startMs + (captureNanos - startNanos) / 1e6

    override fun start(sink: CaptureSink) {
        running = true
        thread = Thread({
            try {
                replay(sink)
            } catch (failure: Exception) {
                sink.failed("Replay failed: ${failure.message ?: failure.javaClass.simpleName}")
            }
        }, "EnsomiCapture").also { it.start() }
    }

    override fun stop() {
        running = false
        thread?.join(STOP_WAIT_MS)
        thread = null
    }

    private fun replay(sink: CaptureSink) {
        RandomAccessFile(file, "r").use { wav ->
            val format = WavFormat.read(wav)
            val frameBytes = format.channels * format.bytesPerSample
            val skip = startMs * format.sampleRate / 1000
            wav.seek(format.dataOffset + skip * frameBytes)
            val blockFrames = format.sampleRate / BLOCKS_PER_SECOND
            val bytes = ByteArray(blockFrames * frameBytes)
            val samples = FloatArray(blockFrames)
            val meter = LevelMeter(sink)
            startNanos = System.nanoTime()
            var sent = 0L
            var left = format.dataBytes - skip * frameBytes
            while (running && left >= frameBytes) {
                val read = wav.read(bytes, 0, minOf(bytes.size.toLong(), left).toInt())
                if (read < frameBytes) break
                left -= read
                val count = read / frameBytes
                format.toMono(bytes, count, samples)
                val first = startNanos + sent * NANOS_PER_SECOND / format.sampleRate
                sent += count
                val due = startNanos + sent * NANOS_PER_SECOND / format.sampleRate
                val wait = (due - System.nanoTime()) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
                sink.block(samples, count, format.sampleRate, first)
                meter.add(samples, count, format.sampleRate) { "replay frames=$sent" }
            }
        }
    }

    private companion object {
        const val BLOCKS_PER_SECOND = 50
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val STOP_WAIT_MS = 2_000L
    }
}

/** A WAV file's `fmt ` and where its `data` is. */
class WavFormat private constructor(
    val channels: Int,
    val sampleRate: Int,
    val bytesPerSample: Int,
    val float: Boolean,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    /** The mean of each of [frames] frames in [bytes]. */
    fun toMono(bytes: ByteArray, frames: Int, into: FloatArray) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) {
            var sum = 0f
            repeat(channels) {
                sum += if (float) {
                    buffer.getFloat()
                } else {
                    buffer.getShort() / 32_768f
                }
            }
            into[frame] = sum / channels
        }
    }

    companion object {
        private const val PCM = 1
        private const val IEEE_FLOAT = 3
        private const val EXTENSIBLE = 0xFFFE

        fun read(file: RandomAccessFile): WavFormat {
            val header = ByteArray(12)
            file.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE") {
                "Not a WAV file"
            }
            var channels = 0
            var sampleRate = 0
            var bits = 0
            var tag = 0
            val chunk = ByteArray(8)
            while (true) {
                if (file.read(chunk) < chunk.size) throw IllegalArgumentException("No data chunk")
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val fmt = ByteArray(minOf(size, 40L).toInt())
                    file.readFully(fmt)
                    val buffer = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    tag = buffer.getShort(0).toInt() and 0xFFFF
                    channels = buffer.getShort(2).toInt()
                    sampleRate = buffer.getInt(4)
                    bits = buffer.getShort(14).toInt()
                    if (tag == EXTENSIBLE && fmt.size >= 26) tag = buffer.getShort(24).toInt() and 0xFFFF
                    file.seek(file.filePointer + size - fmt.size + (size and 1))
                } else if (id == "data") {
                    require(channels > 0 && sampleRate > 0) { "No fmt chunk before the data" }
                    val float = tag == IEEE_FLOAT && bits == 32
                    require(float || (tag == PCM && bits == 16)) { "Only 16-bit PCM or 32-bit float: tag $tag, $bits bits" }
                    val available = file.length() - file.filePointer
                    return WavFormat(channels, sampleRate, bits / 8, float, file.filePointer, minOf(size, available))
                } else {
                    file.seek(file.filePointer + size + (size and 1))
                }
            }
        }
    }
}
