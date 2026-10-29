package io.ensomi.android.settings

import android.content.Context
import android.media.AudioDeviceInfo
import android.util.Log
import io.ensomi.android.audio.AudioPlayClock
import io.ensomi.android.audio.ClickTrack
import io.ensomi.android.audio.GameAudio
import io.ensomi.android.play.PlayClock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The sound step's arithmetic. A tap's error is its song time minus the nearest click's; a tap is judged at its song
 * time plus the audio offset, so the offset that puts the taps on the clicks is minus the median error of the last
 * [TAPS] taps, whatever the offset was while they were made.
 */
object CalibrationTaps {
    const val TAPS = 16

    /**
     * The error of a tap heard at [songMs], in milliseconds, or null for a tap more than half a period before the
     * first click.
     */
    fun errorMs(songMs: Double): Double? {
        val k = Math.round((songMs - ClickTrack.FIRST_CLICK_MS) / ClickTrack.PERIOD_MS)
        if (k < 0) return null
        val error = songMs - (ClickTrack.FIRST_CLICK_MS + k * ClickTrack.PERIOD_MS)
        return error.takeIf { abs(it) <= ClickTrack.PERIOD_MS / 2 }
    }

    /** The last [TAPS] errors, oldest first. */
    fun recent(errorsMs: List<Double>): List<Double> = errorsMs.takeLast(TAPS)

    /** The middle of [values]: the mean of the two middle ones for an even count. */
    fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** The audio offset the last [TAPS] taps suggest, or null until there are that many. */
    fun suggestionMs(errorsMs: List<Double>): Int? {
        if (errorsMs.size < TAPS) return null
        return (-median(recent(errorsMs))).roundToInt().coerceIn(PlaySettings.OFFSETS)
    }
}

/** A click track playing: its clock, on which click k is heard at `1000 + 500 k` ms. Close it on any thread. */
interface ClickPlayer : AutoCloseable {
    val clock: PlayClock
}

/** Opens the calibration's click track; [onRoute] hears the device it plays on, on the main thread. */
fun interface ClickTracks {
    fun open(context: Context, onRoute: (AudioDeviceInfo?) -> Unit): ClickPlayer
}

/**
 * The click track through the play output: the same [GameAudio] path and [AudioPlayClock] as a game, so the clicks
 * are timed as a song's notes are. The track's gain is full; the phone's media volume sets how loud the clicks are.
 */
object AudioClickTracks : ClickTracks {
    override fun open(context: Context, onRoute: (AudioDeviceInfo?) -> Unit): ClickPlayer {
        var clock: AudioPlayClock? = null
        var deviceId: Int? = null
        val audio = GameAudio(
            context,
            ClickTrack.song(),
            leadInMs = 0.0,
            volume = 1f,
            onRoute = { device ->
                if (device != null && device.id != deviceId) {
                    // A change after the first route: the clock reads the new output from its start.
                    if (deviceId != null) clock?.outputChanged(System.nanoTime())
                    deviceId = device.id
                    Log.i(TAG, "Clicks play on ${device.type}/${device.productName} (id ${device.id})")
                    onRoute(device)
                }
            },
            loopFromFrame = ClickTrack.loopFromFrame(),
        )
        val audioClock = AudioPlayClock(audio, audio.sampleRate, leadInFrames = 0) { Log.i(TAG, it) }
        clock = audioClock
        return object : ClickPlayer {
            override val clock: PlayClock = audioClock

            override fun close() {
                audio.close()
                Log.i(TAG, "Clicks closed: ${audioClock.reads} timestamps read, ${audioClock.stepsBack} steps back held")
            }
        }
    }

    const val TAG = "EnsomiCalibration"
}
