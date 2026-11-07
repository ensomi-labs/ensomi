package io.ensomi.android.settings

import android.media.AudioDeviceInfo
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlinx.coroutines.flow.StateFlow

/** The judge difficulties A to E: the runtime's `Judge`. */
enum class JudgeDifficulty { A, B, C, D, E }

/** The orientation play holds on a phone, from the count-in to the results. */
enum class PlayOrientation { LANDSCAPE, PORTRAIT }

/**
 * The settings play reads. A game takes the active audio latency slot's value and each input method's slot value when it
 * starts (latency.md); the output device chooses nothing.
 */
data class PlaySettings(
    val scrollSpeed: Double = DEFAULT_SCROLL_SPEED,
    val judge: JudgeDifficulty = JudgeDifficulty.C,
    /** A fraction of the song's own level, so it only scales down. */
    val musicVolume: Float = 0.8f,
    val orientation: PlayOrientation = PlayOrientation.LANDSCAPE,
    /**
     * How far the lanes are tilted back, in degrees, 0 for flat: one value for each orientation, since landscape
     * lanes are short and lean by default while portrait ones are long and flat by default.
     */
    val landscapeLean: Int = DEFAULT_LANDSCAPE_LEAN,
    val portraitLean: Int = DEFAULT_PORTRAIT_LEAN,
    /** Share of the window's width at the line; null follows the window's default. Kept per orientation. */
    val landscapeWidth: Float? = null,
    val portraitWidth: Float? = null,
    /** The key map, valid as read; a game reads it when it starts (keyboard.md). */
    val keys: KeyMap = KeyMap.DEFAULT,
    /** The audio latency slots and the one chosen by hand. */
    val audioLatency: AudioLatency = AudioLatency(),
    /** The input latency slots and the one each input method uses. */
    val inputLatency: InputLatency = InputLatency(),
) {
    /** The active audio slot's value: the audio latency a game starts with. */
    val audioLatencyMs: Int get() = audioLatency.activeMs

    /** The active audio slot set to [ms]. */
    fun withAudioLatencyMs(ms: Int): PlaySettings = copy(audioLatency = audioLatency.withActiveMs(ms))

    /** How long a note takes to fall from the top of the playfield to the line. */
    val scrollTimeMs: Double get() = scrollTimeMs(scrollSpeed)

    /** The lean of the orientation play will use. */
    val lean: Int get() = lean(orientation)

    fun lean(orientation: PlayOrientation): Int = when (orientation) {
        PlayOrientation.LANDSCAPE -> landscapeLean
        PlayOrientation.PORTRAIT -> portraitLean
    }

    fun withLean(orientation: PlayOrientation, degrees: Int): PlaySettings = when (orientation) {
        PlayOrientation.LANDSCAPE -> copy(landscapeLean = degrees)
        PlayOrientation.PORTRAIT -> copy(portraitLean = degrees)
    }

    fun laneWidth(orientation: PlayOrientation): Float? = when (orientation) {
        PlayOrientation.LANDSCAPE -> landscapeWidth
        PlayOrientation.PORTRAIT -> portraitWidth
    }

    fun withLaneWidth(orientation: PlayOrientation, share: Float?): PlaySettings = when (orientation) {
        PlayOrientation.LANDSCAPE -> copy(landscapeWidth = share)
        PlayOrientation.PORTRAIT -> copy(portraitWidth = share)
    }

    companion object {
        const val DEFAULT_SCROLL_SPEED = 25.0
        val SCROLL_SPEEDS = 1.0..40.0

        /** The convention's common lean: Malody players mostly set 40. */
        const val DEFAULT_LANDSCAPE_LEAN = 40
        const val DEFAULT_PORTRAIT_LEAN = 0

        /** Degrees: 0 is flat; 55 is the most Malody allows in its key mode. */
        val LEANS = 0..55

        /**
         * Where the eye is in the lean's picture, in screen heights of the lanes in front of the screen. The eye is
         * level with the lanes' far end, so the far end stays at the top whatever the lean.
         */
        const val EYE_DISTANCE = 0.36

        /**
         * The far end's width over the line's for a lean of [degrees]: lanes tilted back by that angle, seen from an
         * eye [EYE_DISTANCE] in front of the screen, narrow to `1 / (1 + tan(angle) / EYE_DISTANCE)`: 1 when flat,
         * 0.30 at 40°, 0.20 at 55°.
         */
        fun farScale(degrees: Int): Float {
            val angle = Math.toRadians(degrees.coerceIn(LEANS.first, LEANS.last).toDouble())
            return (1 / (1 + tan(angle) / EYE_DISTANCE)).toFloat()
        }

        /** A slider's value to the nearest whole degree. */
        fun snapLean(value: Float): Int = value.roundToInt().coerceIn(LEANS.first, LEANS.last)

        /** The scale the Swift app uses, so a speed means the same on every client. */
        fun scrollTimeMs(speed: Double): Double = 11_485.0 / speed

        fun formatSpeed(speed: Double): String = String.format(Locale.ROOT, "%.1f×", speed)

        /** "+12 ms", "0 ms", "−8 ms" with a real minus sign. */
        fun formatOffset(ms: Int): String = when {
            ms > 0 -> "+$ms ms"
            ms < 0 -> "−${-ms} ms"
            else -> "0 ms"
        }
    }
}

/**
 * An output device: the routed `AudioDeviceInfo`, keyed by its type and product name ([OutputRoutes.key]), with the name
 * the screens show for it. It chooses no setting; the screens name it as a hint.
 */
data class OutputRoute(val key: String, val name: String)

interface PlaySettingsStore {
    val settings: StateFlow<PlaySettings>

    /** The output play would use now, for the screens to name. */
    val route: StateFlow<OutputRoute>

    fun update(change: (PlaySettings) -> PlaySettings)
}

/** The device a game's sound plays on, for the name the pause panel and the calibration show. */
interface PlayOutputs {
    /** The output [device] is; null for the one Android would pick now. */
    fun routeOf(device: AudioDeviceInfo?): OutputRoute

    /** A game's track plays on [device] now: it is the output from then on. */
    fun playingOn(device: AudioDeviceInfo?)
}
