package io.ensomi.android.chart

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/**
 * Makes charts on this device: decodes the song, runs the runtime's generator on it through `RuntimeWorker`, reports
 * the phase it is in, and stops on request (`Cancellation.cancel()` from another thread).
 */
interface ChartMaker {
    /** Null until the model is open. */
    val capabilities: StateFlow<ChartCapabilities?>

    val state: StateFlow<MakeState>

    fun make(songId: String, request: ChartRequest)

    fun stop()
}

/** Not wired: a fixed state for the prototype. The real maker is plan steps 3 and 5. */
class SampleChartMaker(initial: MakeState, capabilities: ChartCapabilities?) : ChartMaker {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<MakeState> = mutableState.asStateFlow()
    override val capabilities: StateFlow<ChartCapabilities?> = MutableStateFlow(capabilities).asStateFlow()

    override fun make(songId: String, request: ChartRequest) = Unit

    override fun stop() = Unit

    companion object {
        /**
         * Sample capabilities. The star range is R2's (`[2, 6)`); the statuses and notes stand in for what the model
         * reports and are not R2's own.
         */
        val CAPABILITIES = ChartCapabilities(
            modelName = "Ensomi R2 r2-1.0",
            minStar = 2.0,
            maxStarExclusive = 6.0,
            kinds = listOf(
                KindOption(SectionKind.JUMPSTREAM, experimental = false, note = "Sample note: jumps between streams."),
                KindOption(SectionKind.CHORDSTREAM, experimental = false, note = "Sample note: chords between streams."),
                KindOption(SectionKind.STREAM, experimental = false, note = "Sample note: single notes in a row."),
                KindOption(SectionKind.JACK, experimental = true, note = "Sample note: repeated notes in one lane."),
                KindOption(SectionKind.CHORDJACK, experimental = true, note = "Sample note: repeated chords."),
                KindOption(SectionKind.STAR, experimental = false, note = "A star target for this span alone."),
            ),
        )

        val MADE = MadeChart(noteCount = 1229, holdCount = 230, seed = 2914u, stars = 3.5, modelName = "Ensomi R2 r2-1.0")
    }
}
