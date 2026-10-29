package io.ensomi.android.prototype

import android.content.Context
import android.media.AudioDeviceInfo
import android.net.Uri
import io.ensomi.android.chart.ChartCapabilities
import io.ensomi.android.chart.ChartMaker
import io.ensomi.android.chart.ChartRequest
import io.ensomi.android.chart.KindOption
import io.ensomi.android.chart.MadeChart
import io.ensomi.android.chart.MakeState
import io.ensomi.android.chart.SectionKind
import io.ensomi.android.chart.songId
import io.ensomi.android.library.AddResult
import io.ensomi.android.library.Song
import io.ensomi.android.library.SongLibrary
import io.ensomi.android.model.ChartModelStore
import io.ensomi.android.model.ModelSource
import io.ensomi.android.model.ModelState
import io.ensomi.android.model.Notice
import io.ensomi.android.model.ONNX_RUNTIME_NOTICES
import io.ensomi.android.play.FakePlayClock
import io.ensomi.android.play.PlayClock
import io.ensomi.android.play.PlayResult
import io.ensomi.android.settings.ClickPlayer
import io.ensomi.android.settings.ClickTracks
import io.ensomi.android.settings.OutputRoute
import io.ensomi.android.settings.PlayOutputs
import io.ensomi.android.settings.PlaySettings
import io.ensomi.android.settings.PlaySettingsStore
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * The fakes behind the prototype's screenshot scenarios. The app's own stores replace every one of them on a normal
 * launch (AppStores); these stay so each screen and state can be captured for review without a model, songs or sound.
 */

/**
 * For the prototype's scenarios: fixed sample songs. [add] and [remove] change nothing. The app's library is
 * [DocumentSongLibrary].
 */
class FakeSongLibrary(initial: List<Song>) : SongLibrary {
    private val state = MutableStateFlow(initial)
    override val songs: StateFlow<List<Song>> = state.asStateFlow()

    override suspend fun add(documents: List<Uri>) = AddResult(0, 0, emptyList())

    override suspend fun findAgain(id: String, document: Uri) = false

    override fun remove(id: String) = Unit

    override fun refresh() = Unit

    override fun markMissing(id: String) = Unit

    companion object {
        /** Made-up songs; the prototype has no audio. */
        val SONGS = listOf(
            Song("lantern-walk", "Lantern Walk", "Mira Sato", 222_000, "lantern-walk.mp3", chartCount = 1),
            Song("northbound", "Northbound", "The Quiet Hours", 245_000, "northbound.flac"),
            Song("small-hours", "Small Hours", "Aoi", 178_000, "small-hours.m4a", chartCount = 1),
            Song("glass-garden", "Glass Garden", "Ren & Kei", 201_000, "Glass Garden.mp3"),
            Song("afterglow-run", "Afterglow Run", null, 312_000, "afterglow_run.ogg"),
        )

        /** The same songs with one whose file has gone. */
        val WITH_MISSING = SONGS.map { if (it.id == "glass-garden") it.copy(missing = true) else it }
    }
}

/** For the prototype's scenarios: a fixed state. The app's maker is [RuntimeChartMaker]. */
class FakeChartMaker(initial: MakeState, capabilities: ChartCapabilities?) : ChartMaker {
    override val states: StateFlow<Map<String, MakeState>> =
        MutableStateFlow(initial.songId?.let { mapOf(it to initial) }.orEmpty()).asStateFlow()
    override val capabilities: StateFlow<ChartCapabilities?> = MutableStateFlow(capabilities).asStateFlow()

    override fun make(songId: String, request: ChartRequest) = Unit

    override fun stop(songId: String) = Unit

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

/**
 * For the prototype's scenarios: a fixed state. The app's store is [DownloadingChartModelStore].
 */
class FakeChartModelStore(initial: ModelState) : ChartModelStore {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<ModelState> = mutableState.asStateFlow()
    override val directory: File? = null
    override val notices: List<Notice> = ONNX_RUNTIME_NOTICES

    override fun download() = Unit

    override fun cancel() = Unit

    override fun remove() = Unit

    companion object {
        /** The package at the pinned tag: 30 files, 120.4 MB by its manifest. */
        const val PACKAGE_BYTES = ModelSource.PACKAGE_BYTES
        const val PINNED_REVISION = ModelSource.PINNED_REVISION

        val INSTALLED = ModelState.Installed("Ensomi R2 r2-1.0", PINNED_REVISION, PACKAGE_BYTES, "MIT")
    }
}

/** For the prototype's scenarios: kept in memory only. The app's store is [DataStorePlaySettingsStore]. */
class FakePlaySettingsStore(initial: PlaySettings, route: OutputRoute) : PlaySettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<PlaySettings> = state.asStateFlow()
    override val route: StateFlow<OutputRoute> = MutableStateFlow(route).asStateFlow()

    override fun update(change: (PlaySettings) -> PlaySettings) = state.update(change)

    override fun forRoute(route: OutputRoute): PlaySettings = state.value

    companion object {
        val SPEAKER = OutputRoute(key = "builtin-speaker", name = "Phone speaker")
    }
}

/** For the prototype's scenarios: every game plays on [route]. */
class FakePlayOutputs(private val route: OutputRoute) : PlayOutputs {
    override fun routeOf(device: AudioDeviceInfo?): OutputRoute = route

    override fun playingOn(device: AudioDeviceInfo?) = Unit
}

/** Fixed numbers for the results scenario of the prototype. */
val SAMPLE_RESULT = PlayResult(
    title = "Lantern Walk",
    detail = "Mira Sato · 3.5 stars · seed 2914",
    accuracy = 0.9784,
    perfect = 1102,
    good = 119,
    miss = 8,
    maxCombo = 642,
    histogram = listOf(0, 0, 0, 1, 1, 2, 4, 6, 11, 19, 33, 61, 98, 141, 170, 182, 160, 128, 92, 60, 37, 22, 12, 6, 3, 2, 1, 0, 0, 0),
    meanOffsetMs = 6.4,
    suggestedAdjustmentMs = (-6.4).roundToInt(),
    sample = false,
)

/** For the prototype's scenarios: the calibration's clicks on a clock without sound. The app's are [io.ensomi.android.settings.AudioClickTracks]. */
object SilentClickTracks : ClickTracks {
    override fun open(context: Context, onRoute: (AudioDeviceInfo?) -> Unit): ClickPlayer = object : ClickPlayer {
        override val clock: PlayClock = FakePlayClock()

        override fun close() = Unit
    }
}

/** Sample taps for the calibration scenario: about 22 ms late, as a speaker's unreported latency might be. */
val SAMPLE_TAP_ERRORS_MS = listOf(18.0, 25.0, 21.0, 30.0, 16.0, 22.0, 27.0, 19.0, 24.0, 20.0, 23.0, 26.0, 21.0, 23.0, 19.0, 24.0)
