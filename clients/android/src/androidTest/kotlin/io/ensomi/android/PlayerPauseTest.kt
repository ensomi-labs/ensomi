package io.ensomi.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ensomi.android.audio.LibraryPlayer
import io.ensomi.android.audio.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * S4's pause contract, checked through the player rather than through the screen: a pause holds the audio
 * clock, so the scene that reads it stops advancing and stops judging. It runs headless — no activity, no
 * taps — because the contract lives in the output device, not in the UI.
 */
@RunWith(AndroidJUnit4::class)
class PlayerPauseTest {
    @Test
    fun pausingHoldsTheHeardPositionAndResumingContinuesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(context.cacheDir, "pause-check.wav").apply { writeWav(this, seconds = 4) }
        val player = LibraryPlayer(context)

        try {
            player.play(wav)
            val start = awaitPosition(player, timeoutMs = 5_000) { it > 100.0 }

            player.pause()
            Thread.sleep(250)
            val paused1 = awaitPosition(player) { true }
            Thread.sleep(500)
            val paused2 = awaitPosition(player) { true }

            assertEquals("a paused clock does not advance", paused1, paused2, 60.0)

            player.resume()
            val resumed = awaitPosition(player, timeoutMs = 4_000) { it > paused2 + 200.0 }

            assertTrue(
                "resuming continues from where it stopped: $paused2 -> $resumed",
                resumed > paused2 + 200.0,
            )
            assertTrue("the run made progress before pausing: $start", resumed > start)
        } finally {
            player.stop()
            wav.delete()
        }
    }

    private fun awaitPosition(
        player: LibraryPlayer,
        timeoutMs: Long = 3_000,
        predicate: (Double) -> Boolean,
    ): Double {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Double? = null
        while (System.currentTimeMillis() < deadline) {
            val position = player.positionMs()
            if (position != null) {
                last = position
                if (predicate(position)) return position
            }
            Thread.sleep(20)
        }
        assertNotNull("the player never reported a position", last)
        assertTrue("last position $last never satisfied the wait", last != null && predicate(last!!))
        return last!!
    }

    /** A 16-bit mono WAV the player can decode: a tone, so the decoder has real frames to hand over. */
    private fun writeWav(file: File, seconds: Int, sampleRate: Int = 44_100) {
        val frames = seconds * sampleRate
        val pcm = ByteArray(frames * 2)
        for (frame in 0 until frames) {
            val value = (0.3 * sin(2 * PI * 220.0 * frame / sampleRate) * Short.MAX_VALUE).toInt().toShort()
            pcm[frame * 2] = (value.toInt() and 0xFF).toByte()
            pcm[frame * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }

        file.outputStream().use { out ->
            fun intLe(value: Int) = byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte(),
            )

            fun shortLe(value: Int) = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intLe(36 + pcm.size))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intLe(16))
            out.write(shortLe(1))
            out.write(shortLe(1))
            out.write(intLe(sampleRate))
            out.write(intLe(sampleRate * 2))
            out.write(shortLe(2))
            out.write(shortLe(16))
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intLe(pcm.size))
            out.write(pcm)
        }
    }

    @Suppress("unused")
    private fun libraryPlayerStates() = PlayerState.entries
}
