package io.ensomi.android.chart

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ensomi.android.library.Song
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The request a player is putting together for one song, and the maker's state for it. */
class MakeChartViewModel(
    val song: Song,
    private val maker: ChartMaker,
    initialSections: List<SectionDraft>,
    sectionsOpen: Boolean,
    /** Debug launches only: stars outside the slider's range, to see the runtime refuse them. */
    initialStars: Double? = null,
    /** The song's screen has closed for good. */
    private val onClosed: () -> Unit = {},
) : ViewModel() {
    val capabilities: StateFlow<ChartCapabilities?> = maker.capabilities
    val state: StateFlow<MakeState> = maker.states
        .map { it[song.id] ?: MakeState.Idle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, maker.states.value[song.id] ?: MakeState.Idle)

    var stars by mutableDoubleStateOf(initialStars ?: 3.5)
    var seedText by mutableStateOf("")
    var sections by mutableStateOf(initialSections)
        private set
    var sectionsOpen by mutableStateOf(sectionsOpen)
    private var nextSectionId = (initialSections.maxOfOrNull { it.id } ?: 0L) + 1

    fun issues(capabilities: ChartCapabilities): List<SectionIssue> =
        ChartRequestRules.check(sections, song.durationMs, capabilities.selectableStars)

    fun canMake(capabilities: ChartCapabilities?): Boolean =
        capabilities != null && ChartRequestRules.isSeedValid(seedText) && issues(capabilities).isEmpty()

    fun addSection() {
        sections = sections + SectionDraft(id = nextSectionId++, star = stars.toFloat())
    }

    fun updateSection(section: SectionDraft) {
        sections = sections.map { if (it.id == section.id) section else it }
    }

    fun removeSection(id: Long) {
        sections = sections.filterNot { it.id == id }
    }

    fun make() {
        maker.make(song.id, ChartRequest(stars, ChartRequestRules.parseSeed(seedText), sections))
    }

    fun stop() = maker.stop(song.id)

    fun useSeed(seed: ULong) {
        seedText = seed.toString()
    }

    override fun onCleared() = onClosed()
}
