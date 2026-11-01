package io.ensomi.android.chart

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** The five phases a generation reports, in order: the runtime's `GenerationPhase`. */
enum class MakePhase { LOADING, TIMING, ROWS, ARRANGEMENT, EXPORT }

/** What the player asked for. A null [seed] makes a new chart each time. */
data class ChartRequest(
    val stars: Double,
    val seed: ULong?,
    val sections: List<SectionDraft>,
)

/** A chart that was made, as the screen describes it. */
data class MadeChart(
    val noteCount: Int,
    val holdCount: Int,
    /** The seed the generator used, from its receipt: the same request with it makes the same chart. */
    val seed: ULong,
    val stars: Double,
    val modelName: String,
)

sealed interface MakeState {
    data object Idle : MakeState

    data class Making(val songId: String, val phase: MakePhase) : MakeState

    data class Made(val songId: String, val chart: MadeChart) : MakeState

    /** Stopped by the player: no chart was made. */
    data class Stopped(val songId: String) : MakeState

    /** The runtime refused or failed: [message] is its own text, such as "star_out_of_range: ...". */
    data class Failed(val songId: String, val message: String) : MakeState
}

/** The last chart made for a song, to play: the app's record and the `.osu` the runtime wrote. */
class LastChart(val record: StoredChart, val osu: String)

/**
 * Makes charts on this device: decodes the song, runs the runtime's generator on it through `RuntimeWorker`, reports
 * the phase it is in, and stops on request (`Cancellation.cancel()` from another thread).
 */
interface ChartMaker {
    /** Null until the model is open. */
    val capabilities: StateFlow<ChartCapabilities?>

    /** Each song's state; a song without one is [MakeState.Idle]. */
    val states: StateFlow<Map<String, MakeState>>

    /** Songs whose stored audio stages have been checked in this process. */
    val preparedSongs: StateFlow<Set<String>> get() = EMPTY_PREPARED

    /** Allow library preparation while the app is foregrounded on an idle screen. */
    fun background(enabled: Boolean) = Unit

    /** The song's screen opened: shows its last chart. */
    fun open(songId: String) = Unit

    fun make(songId: String, request: ChartRequest)

    fun stop(songId: String)

    /** A game is about to start: work nobody waits for stops, so the game has the processor to itself. */
    fun quiet() = Unit

    /** Wait for cancelled background work to release its processor and model before starting play. */
    suspend fun awaitQuiet() { quiet() }

    /** The last chart made for the song, or null. Reads app storage: call it off the main thread. */
    fun lastChart(songId: String): LastChart? = null
}

private val EMPTY_PREPARED: StateFlow<Set<String>> = MutableStateFlow(emptySet())
