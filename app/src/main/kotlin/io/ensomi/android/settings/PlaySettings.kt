package io.ensomi.android.settings

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The judge difficulties A to E: the runtime's `Judge`. */
enum class JudgeDifficulty { A, B, C, D, E }

/** The orientation play holds on a phone, from the count-in to the results. */
enum class PlayOrientation { LANDSCAPE, PORTRAIT }

/**
 * The settings play reads. Offsets are in milliseconds: the audio offset belongs to the output device play uses, the
 * visual offset to the display.
 */
data class PlaySettings(
    val scrollSpeed: Double = DEFAULT_SCROLL_SPEED,
    val judge: JudgeDifficulty = JudgeDifficulty.C,
    /** A fraction of the song's own level, so it only scales down. */
    val musicVolume: Float = 0.8f,
    val audioOffsetMs: Int = 0,
    val visualOffsetMs: Int = 0,
    val vibrateOnTouch: Boolean = false,
    val orientation: PlayOrientation = PlayOrientation.LANDSCAPE,
    /**
     * How far the lanes are tilted back, in degrees, 0 for flat: one value for each orientation, since landscape
     * lanes are short and lean by default while portrait ones are long and flat by default.
     */
    val landscapeLean: Int = DEFAULT_LANDSCAPE_LEAN,
    val portraitLean: Int = DEFAULT_PORTRAIT_LEAN,
) {
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

    companion object {
        const val DEFAULT_SCROLL_SPEED = 25.0
        val SCROLL_SPEEDS = 1.0..40.0
        val OFFSETS = -300..300

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
 * The output the audio offset is kept for: the routed `AudioDeviceInfo`, keyed by its type and product name
 * ([OutputRoutes.key]), with the name the screens show for it.
 */
data class OutputRoute(val key: String, val name: String)

interface PlaySettingsStore {
    val settings: StateFlow<PlaySettings>

    /** The output play would use now. [settings] carries its audio offset. */
    val route: StateFlow<OutputRoute>

    /** Changes the settings; a change of the audio offset is kept for [route]. */
    fun update(change: (PlaySettings) -> PlaySettings)
}

/** For the prototype's scenarios: kept in memory only. The app's store is [DataStorePlaySettingsStore]. */
class InMemoryPlaySettingsStore(initial: PlaySettings, route: OutputRoute) : PlaySettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<PlaySettings> = state.asStateFlow()
    override val route: StateFlow<OutputRoute> = MutableStateFlow(route).asStateFlow()

    override fun update(change: (PlaySettings) -> PlaySettings) = state.update(change)

    companion object {
        val SPEAKER = OutputRoute(key = "builtin-speaker", name = "Phone speaker")
    }
}
