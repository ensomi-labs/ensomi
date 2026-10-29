package io.ensomi.android.checks

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ensomi.android.audio.DecodedSong
import io.ensomi.android.audio.PcmChannels
import io.ensomi.android.audio.SongDecoder
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone's own decoders against the release engine's times (plan step 3's phone check), on the click tracks of
 * `scripts/make-click-tracks.sh`: a 1 kHz click every 500 ms from exactly 1000 ms of the source, in each container and
 * MP3 header class. Decoded by the app's `SongDecoder` to mono and stereo, the first click (the first sample at half
 * the peak, 0.083 ms into the tone) must be where BASS puts it: 1000.08 ms for every file but the MP3 without a Xing or
 * Info header, whose LAME encoder delay (576 frames) and decoder delay (529) BASS keeps, so 1025.14 ms at 44.1 kHz.
 * Stereo mixed down must equal mono. One line per file, then a summary.
 */
@RunWith(AndroidJUnit4::class)
class ClickTrackDecodeCheck {
    @Test
    fun clicksLandWhereTheReleaseEngineHearsThem() {
        val names = PhoneCheck.pushedFiles(PhoneCheck.clicksDir).filter { it.substringAfterLast('.') in EXTENSIONS }
        if (names.isEmpty()) PhoneCheck.report("decode-clicks", linkedMapOf("skipped" to "no click tracks pushed"))
        assumeTrue("No click tracks in ${PhoneCheck.clicksDir}", names.isNotEmpty())
        val wrong = mutableListOf<String>()
        for (name in names) {
            val file = PhoneCheck.copyPushed(PhoneCheck.clicksDir, name)
            val line = linkedMapOf<String, Any?>("file" to name)
            try {
                val started = System.nanoTime()
                val mono = SongDecoder.decode(PhoneCheck.context, Uri.fromFile(file), PcmChannels.MONO)
                val decodeMs = (System.nanoTime() - started) / 1e6
                val stereo = SongDecoder.decode(PhoneCheck.context, Uri.fromFile(file), PcmChannels.STEREO)
                val onset = firstOnset(mono)?.let { it * 1000.0 / mono.sampleRate }
                val expected = expectedFirstClickMs(name, mono.sampleRate)
                val error = onset?.let { it - expected }
                val stereoMatches = stereoMatchesMono(mono, stereo)
                val good = error != null && abs(error) <= TOLERANCE_MS && stereoMatches
                if (!good) wrong += name
                line += linkedMapOf(
                    "mime" to mono.mime,
                    "codec" to mono.codec,
                    "sampleRate" to mono.sampleRate,
                    "header" to mono.header?.toString(),
                    "leadFrames" to mono.leadFrames,
                    "frames" to mono.frames,
                    "durationMs" to PhoneCheck.round(mono.durationMs, 2),
                    "firstClickMs" to onset?.let { PhoneCheck.round(it, 3) },
                    "expectedMs" to PhoneCheck.round(expected, 3),
                    "errorMs" to error?.let { PhoneCheck.round(it, 3) },
                    "stereoEqualsMono" to stereoMatches,
                    "decodeMs" to PhoneCheck.round(decodeMs, 1),
                    "ok" to good,
                )
            } catch (e: Exception) {
                wrong += name
                line += linkedMapOf("error" to e.toString(), "ok" to false)
            } finally {
                file.delete()
            }
            PhoneCheck.report("decode-click", line)
        }
        PhoneCheck.report(
            "decode-clicks",
            linkedMapOf("files" to names.size, "wrong" to wrong, "toleranceMs" to TOLERANCE_MS, "ok" to wrong.isEmpty()),
        )
        assertTrue("Clicks not where the release engine hears them: $wrong", wrong.isEmpty())
    }

    /** The first frame at half the peak, or null for silence. */
    private fun firstOnset(song: DecodedSong): Int? {
        val peak = song.samples.maxOfOrNull { abs(it) } ?: return null
        if (peak <= 0f) return null
        val index = song.samples.indexOfFirst { abs(it) >= peak / 2 }
        return if (index < 0) null else index / song.channels
    }

    private fun stereoMatchesMono(mono: DecodedSong, stereo: DecodedSong): Boolean {
        if (mono.frames != stereo.frames || stereo.channels != 2) return false
        val step = maxOf(1, mono.frames / 2000)
        for (frame in 0 until mono.frames step step) {
            val mixed = (stereo.samples[2 * frame] + stereo.samples[2 * frame + 1]) / 2
            if (abs(mixed - mono.samples[frame]) > 1e-4f) return false
        }
        return true
    }

    private companion object {
        val EXTENSIONS = setOf("wav", "flac", "ogg", "opus", "m4a", "mp3", "aac")
        const val TOLERANCE_MS = 0.5

        /** Half the peak of a sine from phase 0 is reached 1/12 of its 1 ms period in. */
        const val HALF_PEAK_MS = 1.0 / 12

        /** BASS keeps an MP3's encoder and decoder delay when no LAME header says what to trim. */
        const val LAME_ENCODER_DELAY = 576
        const val MP3_DECODER_DELAY = 529

        fun expectedFirstClickMs(name: String, sampleRate: Int): Double {
            val kept = if (name.endsWith(".mp3") && "noxing" in name) LAME_ENCODER_DELAY + MP3_DECODER_DELAY else 0
            return 1000.0 + HALF_PEAK_MS + kept * 1000.0 / sampleRate
        }
    }
}
