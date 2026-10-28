package io.ensomi.android.probe

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One recorded window, kept as a WAV so the same samples can be fingerprinted offline and replayed
 * against the calibration experiment the report asks for.
 *
 * [firstReadNanos] and [lastReadNanos] are `SystemClock.elapsedRealtimeNanos()` readings taken around
 * the block reads, and [timestampFramePosition] is the platform's own frame clock at
 * [timestampNanos]. The report's rule is that the capture window's monotonic bounds are the anchor,
 * not the time an HTTP response arrived.
 */
data class CaptureResult(
    val wavPath: String,
    val durationMs: Long,
    val sampleRate: Int,
    val rms: Double,
    val peak: Int,
    val firstReadNanos: Long,
    val lastReadNanos: Long,
    val timestampFramePosition: Long?,
    val timestampNanos: Long?,
    val sha256: String,
)

/**
 * Records a short window with `AudioRecord` and writes it as a 44.1 kHz mono WAV. It deliberately
 * uses the plain microphone source: `VOICE_RECOGNITION` changes the signal, and a probe that feeds a
 * fingerprint wants the signal the fingerprint was designed for.
 */
class CaptureProbe(private val directory: File) {
    fun capture(seconds: Int, onResult: (Result<CaptureResult>) -> Unit) {
        Thread({
            val result = runCatching { record(seconds) }
            onResult(result)
        }, "ensomi-capture").start()
    }

    private fun record(seconds: Int): CaptureResult {
        directory.mkdirs()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "the device reports no usable microphone buffer size" }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer * 4, 16_384))
            .build()

        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord did not initialise" }

        val pcm = ByteArrayOutputStream(SAMPLE_RATE * 2 * seconds)
        val block = ShortArray(BLOCK_SAMPLES)
        var samples = 0L
        var sumOfSquares = 0.0
        var peak = 0
        var firstRead = 0L
        var lastRead = 0L
        var timestampFrame: Long? = null
        var timestampNanos: Long? = null

        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "the microphone did not start" }

            val target = SAMPLE_RATE.toLong() * seconds
            while (samples < target) {
                val read = record.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                require(read > 0) { "AudioRecord.read returned $read" }

                val now = SystemClock.elapsedRealtimeNanos()
                if (firstRead == 0L) firstRead = now
                lastRead = now

                if (timestampFrame == null) {
                    val stamp = AudioTimestamp()
                    if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        timestampFrame = stamp.framePosition
                        timestampNanos = stamp.nanoTime
                    }
                }

                for (index in 0 until read) {
                    val value = block[index].toInt()
                    sumOfSquares += value.toDouble() * value
                    if (abs(value) > peak) peak = abs(value)
                    pcm.write(value and 0xFF)
                    pcm.write((value shr 8) and 0xFF)
                }
                samples += read
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }

        val bytes = pcm.toByteArray()
        val file = File(directory, "take-${System.currentTimeMillis()}.wav")
        writeWav(file, bytes)

        return CaptureResult(
            wavPath = file.absolutePath,
            durationMs = samples * 1_000 / SAMPLE_RATE,
            sampleRate = SAMPLE_RATE,
            rms = if (samples == 0L) 0.0 else sqrt(sumOfSquares / samples),
            peak = peak,
            firstReadNanos = firstRead,
            lastReadNanos = lastRead,
            timestampFramePosition = timestampFrame,
            timestampNanos = timestampNanos,
            sha256 = sha256(file),
        )
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intLe(36 + pcm.size))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intLe(16))
            out.write(shortLe(1))
            out.write(shortLe(channels))
            out.write(intLe(SAMPLE_RATE))
            out.write(intLe(byteRate))
            out.write(shortLe(channels * bitsPerSample / 8))
            out.write(shortLe(bitsPerSample))
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intLe(pcm.size))
            out.write(pcm)
        }
    }

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun shortLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        private const val BLOCK_SAMPLES = 1_024
    }
}
