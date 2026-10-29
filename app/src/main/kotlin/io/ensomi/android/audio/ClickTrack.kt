package io.ensomi.android.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The calibration's click track, made in code: one second of silence, then a click every 500 ms without end, so click
 * k is heard at song time `1000 + 500 k` ms on the play clock. A click is a short tone burst whose first sample is the
 * click's time. The PCM is the silence and one period with its click at the start; [GameAudio] repeats the period
 * ([loopFromFrame]), so every click falls on a whole frame.
 */
object ClickTrack {
    const val FIRST_CLICK_MS = 1_000.0
    const val PERIOD_MS = 500.0

    /** The usual output rate of phones, so the mixer need not resample. */
    const val SAMPLE_RATE = 48_000

    private const val TONE_HZ = 1_760.0
    private const val CLICK_MS = 30.0
    private const val DECAY_MS = 6.0
    private const val ATTACK_MS = 0.5
    private const val LEVEL = 0.5

    /** The first click's frame, where the repeated period starts. */
    fun loopFromFrame(sampleRate: Int = SAMPLE_RATE): Int = (FIRST_CLICK_MS * sampleRate / 1000).roundToInt()

    /** Mono PCM: one second of silence, then one 500 ms period with the click at its start. */
    fun pcm(sampleRate: Int = SAMPLE_RATE): FloatArray {
        val first = loopFromFrame(sampleRate)
        val period = (PERIOD_MS * sampleRate / 1000).roundToInt()
        val samples = FloatArray(first + period)
        val clickFrames = (CLICK_MS * sampleRate / 1000).roundToInt()
        val attackFrames = max(1.0, ATTACK_MS * sampleRate / 1000)
        for (i in 0 until min(clickFrames, period)) {
            val tMs = i * 1000.0 / sampleRate
            // A fast rise, so the click is heard from its first sample, then an exponential fall.
            val envelope = min(1.0, i / attackFrames) * exp(-tMs / DECAY_MS)
            samples[first + i] = (LEVEL * envelope * sin(2 * PI * TONE_HZ * tMs / 1000)).toFloat()
        }
        return samples
    }

    /** The click track as a song for [GameAudio]: mono, no lead. */
    fun song(sampleRate: Int = SAMPLE_RATE): DecodedSong = DecodedSong(
        samples = pcm(sampleRate),
        channels = 1,
        sampleRate = sampleRate,
        leadFrames = 0,
        mime = "audio/raw",
        codec = "click track",
        header = null,
    )
}
