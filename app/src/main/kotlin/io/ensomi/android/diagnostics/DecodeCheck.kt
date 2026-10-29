package io.ensomi.android.diagnostics

import android.content.Context
import android.util.Log
import io.ensomi.android.AppStores
import io.ensomi.android.audio.DecodeException
import io.ensomi.android.audio.DecodedSong
import io.ensomi.android.audio.PcmChannels
import io.ensomi.android.audio.SongDecoder
import io.ensomi.android.json.Json
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A debug check of the decoder on the songs in the library, started by a launch extra in debuggable builds
 * (`adb shell am start -n io.ensomi.android/.MainActivity --ez decode-check true`). Each song is decoded to mono and to
 * stereo; one JSON line per song goes to logcat under [TAG], then `{"done":true}`. The first onset is the first sample
 * at half the song's peak, for click tracks whose first click time is known.
 */
object DecodeCheck {
    const val EXTRA = "decode-check"
    const val TAG = "EnsomiDecodeCheck"

    fun start(context: Context, stores: AppStores) {
        stores.scope.launch {
            val songs = withTimeoutOrNull(10_000) { stores.songs.songs.first { it.isNotEmpty() } }.orEmpty()
            for (song in songs) {
                val uri = song.uri ?: continue
                val line = try {
                    val started = System.nanoTime()
                    val mono = SongDecoder.decode(context, uri, PcmChannels.MONO)
                    val monoMs = (System.nanoTime() - started) / 1e6
                    val stereo = SongDecoder.decode(context, uri, PcmChannels.STEREO)
                    val onset = firstOnset(mono)
                    linkedMapOf(
                        "file" to song.fileName,
                        "mime" to mono.mime,
                        "codec" to mono.codec,
                        "sampleRate" to mono.sampleRate,
                        "header" to mono.header?.toString(),
                        "leadFrames" to mono.leadFrames,
                        "monoFrames" to mono.frames,
                        "stereoFrames" to stereo.frames,
                        "decodedMs" to mono.durationMs,
                        "tagMs" to song.durationMs,
                        "firstOnsetMs" to onset?.let { it * 1000.0 / mono.sampleRate },
                        "decoderOnsetMs" to onset?.let { (it - mono.leadFrames) * 1000.0 / mono.sampleRate },
                        "stereoEqualsMono" to stereoMatchesMono(mono, stereo),
                        "monoDecodeMs" to monoMs,
                    )
                } catch (e: DecodeException) {
                    linkedMapOf("file" to song.fileName, "error" to e.message, "missing" to e.missing)
                } catch (e: RuntimeException) {
                    linkedMapOf("file" to song.fileName, "error" to e.toString())
                }
                Log.i(TAG, Json.encode(line))
            }
            Log.i(TAG, Json.encode(linkedMapOf("done" to true, "songs" to songs.size)))
        }
    }

    /** The first frame at half the peak, or null for silence. */
    private fun firstOnset(song: DecodedSong): Int? {
        val peak = song.samples.maxOfOrNull { abs(it) } ?: return null
        if (peak <= 0f) return null
        val index = song.samples.indexOfFirst { abs(it) >= peak / 2 }
        return if (index < 0) null else index / song.channels
    }

    /** Whether the stereo decode, mixed down, has the mono decode's frames: the same start and length. */
    private fun stereoMatchesMono(mono: DecodedSong, stereo: DecodedSong): Boolean {
        if (mono.frames != stereo.frames) return false
        val step = maxOf(1, mono.frames / 1000)
        for (frame in 0 until mono.frames step step) {
            val mixed = (stereo.samples[2 * frame] + stereo.samples[2 * frame + 1]) / 2
            if (abs(mixed - mono.samples[frame]) > 1e-4f) return false
        }
        return true
    }
}
