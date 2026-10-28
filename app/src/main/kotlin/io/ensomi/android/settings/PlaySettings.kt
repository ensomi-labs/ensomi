package io.ensomi.android.settings

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The judge difficulties A to E: the runtime's `Judge`. */
enum class JudgeDifficulty { A, B, C, D, E }

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
) {
    /** How long a note takes to fall from the top of the playfield to the line. */
    val scrollTimeMs: Double get() = scrollTimeMs(scrollSpeed)

    companion object {
        const val DEFAULT_SCROLL_SPEED = 25.0
        val SCROLL_SPEEDS = 1.0..40.0
        val OFFSETS = -300..300

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

/** The output the audio offset is kept for: the routed `AudioDeviceInfo`, keyed by its type and product name. */
data class OutputRoute(val key: String, val name: String)

interface PlaySettingsStore {
    val settings: StateFlow<PlaySettings>

    /** The output play would use now. */
    val route: StateFlow<OutputRoute>

    fun update(change: (PlaySettings) -> PlaySettings)
}

/** Not wired: kept in memory only. The real store (plan step 1) persists with DataStore, offsets per output. */
class InMemoryPlaySettingsStore(initial: PlaySettings, route: OutputRoute) : PlaySettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<PlaySettings> = state.asStateFlow()
    override val route: StateFlow<OutputRoute> = MutableStateFlow(route).asStateFlow()

    override fun update(change: (PlaySettings) -> PlaySettings) = state.update(change)

    companion object {
        val SPEAKER = OutputRoute(key = "builtin-speaker", name = "Phone speaker")
    }
}
