package io.ensomi.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A game's sound: one `AudioTrack` (`ENCODING_PCM_FLOAT`, `MODE_STREAM`) fed by its own writer thread, first with
 * [leadInMs] of silence, then the song, then silence until the game closes it, so the output keeps reporting real
 * timestamps past the song's end. With [loopFromFrame] the song repeats from that frame instead of ending, as the
 * calibration's click track does. The music volume is the track's gain, which leaves frame positions alone.
 *
 * The writer fills the track's buffer ([QUEUED_MS] or more) before [play] and blocks while it is full, so at least
 * 200 ms stay queued. [onRoute] hears the device the track plays on, on the main thread: once when it starts, and at
 * every change after.
 */
class GameAudio(
    context: Context,
    private val song: DecodedSong,
    leadInMs: Double,
    volume: Float,
    private val onRoute: (AudioDeviceInfo?) -> Unit,
    /** Where the song starts again once it has played to its end; null to end it. */
    private val loopFromFrame: Int? = null,
) : PlayOutput, AutoCloseable {
    val sampleRate: Int = song.sampleRate
    val leadInFrames: Long = (leadInMs * sampleRate / 1000).roundToLong()
    private val channels = song.channels
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val track: AudioTrack
    private val writer: Thread
    private val timestamp = AudioTimestamp()

    /** Held for every call on the track but the writer's, so none comes after [close] released it. */
    private val lock = Any()

    @Volatile
    private var running = true

    private var released = false

    /** The track's underruns when it was released. */
    private var closedUnderruns = 0

    private val routing = AudioRouting.OnRoutingChangedListener { router -> if (running) onRoute(router.routedDevice) }

    init {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(sampleRate, format.channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        val queued = QUEUED_MS * sampleRate / 1000 * channels * Float.SIZE_BYTES
        track = AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minimum, queued))
            .build()
        track.setVolume(volume.coerceIn(0f, 1f))
        track.addOnRoutingChangedListener(routing, Handler(Looper.getMainLooper()))
        Log.i(
            TAG,
            "Output: $sampleRate Hz, $channels channels, buffer ${track.bufferSizeInFrames} of " +
                "${track.bufferCapacityInFrames} frames, lead-in $leadInFrames frames, song ${song.frames} frames",
        )
        writer = Thread(::write, "EnsomiAudioWriter").also { it.start() }
    }

    /** The track's underruns so far (`AudioTrack.getUnderrunCount`), or at [close] once closed. */
    val underruns: Int
        get() = synchronized(lock) { if (released) closedUnderruns else track.underrunCount }

    /** The device the track plays on now; null until it has started. */
    val routedDevice: AudioDeviceInfo?
        get() = synchronized(lock) { if (released) null else track.routedDevice }

    override fun timestamp(): OutputTimestamp? = synchronized(lock) {
        if (!released && track.getTimestamp(timestamp)) OutputTimestamp(timestamp.framePosition, timestamp.nanoTime) else null
    }

    override fun play() {
        synchronized(lock) { if (!released) track.play() }
    }

    override fun pause() {
        synchronized(lock) { if (!released) track.pause() }
    }

    /**
     * Debug builds only: asks the track for another output than the one it plays on, which Android reports as a route
     * change, so the pause on a change of output can be checked on an emulator, which has one output.
     */
    fun preferAnotherOutput(): String? {
        synchronized(lock) {
            if (released) return null
            val current = track.routedDevice?.id
            val other = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.isSink && it.id != current && it.type != AudioDeviceInfo.TYPE_TELEPHONY }
                ?: return null
            track.preferredDevice = other
            return "${other.type}/${other.productName}"
        }
    }

    private fun write() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val chunk = CHUNK_MS * sampleRate / 1000 * channels
        val silence = FloatArray(chunk)
        var lead = leadInFrames * channels
        while (running && lead > 0) {
            val count = min(chunk.toLong(), lead).toInt()
            if (!writeAll(silence, 0, count)) return
            lead -= count
        }
        val samples = song.samples
        val loopFrom = loopFromFrame?.let { it.coerceIn(0, song.frames - 1) * channels }
        var offset = 0
        while (running) {
            if (offset >= samples.size) offset = loopFrom ?: break
            val count = min(chunk, samples.size - offset)
            if (!writeAll(samples, offset, count)) return
            offset += count
        }
        // Past the song's end the output keeps running, so the clock keeps reading real timestamps until the game ends.
        while (running) {
            if (!writeAll(silence, 0, chunk)) return
        }
    }

    private fun writeAll(data: FloatArray, offset: Int, count: Int): Boolean {
        var done = 0
        while (done < count) {
            if (!running) return false
            val written = track.write(data, offset + done, count - done, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                if (running) Log.w(TAG, "The output refused a write: $written")
                return false
            }
            done += written
        }
        return true
    }

    /** Stops the writer and releases the track; logs the game's underruns. Any thread. */
    override fun close() {
        synchronized(lock) {
            if (released) return
            running = false
            closedUnderruns = track.underrunCount
            Log.i(TAG, "Output closed: $closedUnderruns underruns, routed to ${track.routedDevice?.productName}")
            track.removeOnRoutingChangedListener(routing)
            // A paused track would keep the writer blocked: flushing frees the buffer, and stop() wakes a blocked write.
            track.pause()
            track.flush()
            track.stop()
        }
        writer.join(WRITER_JOIN_MS)
        synchronized(lock) {
            released = true
            track.release()
        }
    }

    companion object {
        private const val TAG = "EnsomiAudio"

        /** The track's buffer: what the writer keeps queued, at least. */
        const val QUEUED_MS = 400

        /** How much the writer hands the track at a time. */
        private const val CHUNK_MS = 20
        private const val WRITER_JOIN_MS = 1_000L

        /** A game's music: the same attributes for the track and for its audio focus. */
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
