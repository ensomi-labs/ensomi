package io.ensomi.android.prototype

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.navigation3.runtime.NavKey
import io.ensomi.android.Calibration
import io.ensomi.android.Diagnostics
import io.ensomi.android.Library
import io.ensomi.android.MakeChart
import io.ensomi.android.Play
import io.ensomi.android.Results
import io.ensomi.android.Settings
import io.ensomi.android.chart.ChartMaker
import io.ensomi.android.chart.MakePhase
import io.ensomi.android.chart.MakeState
import io.ensomi.android.chart.SampleChartMaker
import io.ensomi.android.chart.SectionDraft
import io.ensomi.android.chart.SectionKind
import io.ensomi.android.library.SampleSongLibrary
import io.ensomi.android.library.SongLibrary
import io.ensomi.android.model.ChartModelStore
import io.ensomi.android.model.ModelState
import io.ensomi.android.model.SampleChartModelStore
import io.ensomi.android.play.SAMPLE_RESULT
import io.ensomi.android.settings.CalibrationStep
import io.ensomi.android.settings.InMemoryPlaySettingsStore
import io.ensomi.android.settings.PlaySettings
import io.ensomi.android.settings.PlaySettingsStore

/** What the screens read and act on. Every implementation is a sample of the prototype; see each class. */
class AppGraph(
    val songs: SongLibrary,
    val model: ChartModelStore,
    val charts: ChartMaker,
    val settings: PlaySettingsStore,
)

/**
 * A state of the prototype to open in, for screenshots: the back stack and the sample data behind it. A debuggable
 * build reads it from the launch intent's `prototype` extra (`adb shell am start ... --es prototype make-chart-made`);
 * any other launch opens [DEFAULT]. Nothing here ships: once the stores are real, the scenarios go.
 */
data class Scenario(
    val backStack: List<NavKey>,
    val model: ModelState = SampleChartModelStore.INSTALLED,
    val withSongs: Boolean = true,
    val make: MakeState = MakeState.Idle,
    val sections: List<SectionDraft> = emptyList(),
    val sectionsOpen: Boolean = false,
    val pauseAfterMs: Long? = null,
    val holdCountdown: Boolean = false,
    val calibrationStep: CalibrationStep = CalibrationStep.SOUND,
    val dynamicColor: Boolean = false,
) {
    fun graph(): AppGraph = AppGraph(
        songs = SampleSongLibrary(if (withSongs) SampleSongLibrary.SONGS else emptyList()),
        model = SampleChartModelStore(model),
        charts = SampleChartMaker(make, SampleChartMaker.CAPABILITIES.takeIf { model is ModelState.Installed }),
        settings = InMemoryPlaySettingsStore(PlaySettings(), InMemoryPlaySettingsStore.SPEAKER),
    )

    companion object {
        private const val SONG = "lantern-walk"
        private const val FREE = 18_400_000_000L
        private val PACKAGE = SampleChartModelStore.PACKAGE_BYTES
        private val ONE_SECTION = listOf(SectionDraft(id = 1, start = "00:32", end = "00:48", kind = SectionKind.JUMPSTREAM))

        val DEFAULT = Scenario(backStack = listOf(Library))

        val ALL: Map<String, Scenario> = mapOf(
            "first-run" to Scenario(listOf(Library), model = ModelState.Absent(PACKAGE, FREE), withSongs = false),
            "downloading" to Scenario(listOf(Library), model = ModelState.Downloading(50_600_000, PACKAGE), withSongs = false),
            "download-stopped" to Scenario(
                listOf(Library),
                model = ModelState.Stopped(50_600_000, PACKAGE, failure = "The connection was lost."),
                withSongs = true,
            ),
            "no-space" to Scenario(listOf(Library), model = ModelState.Absent(PACKAGE, 80_200_000), withSongs = false),
            "library" to DEFAULT,
            "make-chart" to Scenario(listOf(Library, MakeChart(SONG))),
            "make-chart-sections" to Scenario(listOf(Library, MakeChart(SONG)), sections = ONE_SECTION, sectionsOpen = true),
            "make-chart-section-problem" to Scenario(
                listOf(Library, MakeChart(SONG)),
                sections = listOf(SectionDraft(id = 1, start = "00:32", end = "00:36", kind = SectionKind.STREAM)),
                sectionsOpen = true,
            ),
            "make-chart-no-model" to Scenario(listOf(Library, MakeChart(SONG)), model = ModelState.Downloading(50_600_000, PACKAGE)),
            "make-chart-making" to Scenario(
                listOf(Library, MakeChart(SONG)),
                make = MakeState.Making(SONG, MakePhase.ROWS),
                sections = ONE_SECTION,
            ),
            "make-chart-made" to Scenario(
                listOf(Library, MakeChart(SONG)),
                make = MakeState.Made(SONG, SampleChartMaker.MADE),
                sections = ONE_SECTION,
            ),
            "make-chart-stopped" to Scenario(listOf(Library, MakeChart(SONG)), make = MakeState.Stopped(SONG)),
            "make-chart-failed" to Scenario(
                listOf(Library, MakeChart(SONG)),
                make = MakeState.Failed(SONG, "star_out_of_range: star must be in [2, 6)"),
            ),
            "play" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = false))),
            "play-preview" to Scenario(listOf(Library, Settings, Play(null, loop = true))),
            "play-paused" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = false)), pauseAfterMs = 12_400),
            "play-countdown" to Scenario(
                listOf(Library, MakeChart(SONG), Play(SONG, loop = false)),
                pauseAfterMs = 12_400,
                holdCountdown = true,
            ),
            "results" to Scenario(listOf(Library, MakeChart(SONG), Results(SONG, SAMPLE_RESULT))),
            "settings" to Scenario(listOf(Library, Settings)),
            "settings-downloading" to Scenario(listOf(Library, Settings), model = ModelState.Downloading(50_600_000, PACKAGE)),
            "calibration-sound" to Scenario(listOf(Library, Settings, Calibration)),
            "calibration-picture" to Scenario(listOf(Library, Settings, Calibration), calibrationStep = CalibrationStep.PICTURE),
            "diagnostics" to Scenario(listOf(Library, Settings, Diagnostics)),
        )

        fun from(intent: Intent?, applicationInfo: ApplicationInfo): Scenario {
            val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (!debuggable || intent == null) return DEFAULT
            val scenario = ALL[intent.getStringExtra("prototype")] ?: DEFAULT
            return scenario.copy(dynamicColor = intent.getBooleanExtra("dynamicColor", false))
        }
    }
}
