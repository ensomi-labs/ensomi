package io.ensomi.android.prototype

import android.content.Intent
import androidx.navigation3.runtime.NavKey
import io.ensomi.android.Calibration
import io.ensomi.android.Diagnostics
import io.ensomi.android.Library
import io.ensomi.android.AppStores
import io.ensomi.android.MakeChart
import io.ensomi.android.NoticeText
import io.ensomi.android.Notices
import io.ensomi.android.Play
import io.ensomi.android.Results
import io.ensomi.android.Settings
import io.ensomi.android.audio.PlayAudioCache
import io.ensomi.android.chart.ChartMaker
import io.ensomi.android.chart.MakePhase
import io.ensomi.android.chart.MakeState
import io.ensomi.android.chart.SectionDraft
import io.ensomi.android.chart.SectionKind
import io.ensomi.android.library.SongLibrary
import io.ensomi.android.model.ChartModelStore
import io.ensomi.android.model.ModelState
import io.ensomi.android.model.ModelUpdate
import io.ensomi.android.model.ONNX_RUNTIME_NOTICES
import io.ensomi.android.settings.PLAYFIELD_SETTINGS
import io.ensomi.android.settings.PlayOrientation
import io.ensomi.android.settings.AudioClickTracks
import io.ensomi.android.settings.CalibrationStep
import io.ensomi.android.settings.ClickTracks
import io.ensomi.android.settings.PlayOutputs
import io.ensomi.android.settings.PlaySettings
import io.ensomi.android.settings.PlaySettingsStore

/**
 * What the screens read and act on: the app's stores ([AppStores]) on a normal launch, samples in a prototype
 * scenario.
 */
class AppGraph(
    val songs: SongLibrary,
    val model: ChartModelStore,
    val charts: ChartMaker,
    val settings: PlaySettingsStore,
    /** The device a game's sound plays on, for its audio offset. */
    val outputs: PlayOutputs,
    /** The calibration's click track. */
    val clicks: ClickTracks,
    /** The sound of the song last played; null in the scenarios, whose games are the sample chart without sound. */
    val playAudio: PlayAudioCache? = null,
    val debug: DebugOptions = DebugOptions(),
)

/**
 * What a debuggable build's launch intent may ask of a normal launch, for checks on the emulator: `--ef stars 7` for
 * stars outside the slider's range, `--ez autoplay true` for games played by the autoplay, `--ez low-memory true` for
 * the generator's `lowMemory`.
 */
data class DebugOptions(
    val stars: Double? = null,
    val autoplay: Boolean = false,
    val lowMemory: Boolean? = null,
    /** Debug builds and the checks build ([DebugTools]): games log every frame's times and take the debug broadcasts. */
    val debuggable: Boolean = false,
)

/**
 * A state of the prototype to open in, for screenshots: the back stack and the sample data behind it. A debuggable
 * build reads it from the launch intent's `prototype` extra (`adb shell am start ... --es prototype make-chart-made`);
 * any other launch opens [DEFAULT], the app's own stores. The scenarios run on the fakes in `Fakes.kt`.
 */
data class Scenario(
    val backStack: List<NavKey>,
    val model: ModelState = FakeChartModelStore.INSTALLED,
    val withSongs: Boolean = true,
    val make: MakeState = MakeState.Idle,
    val sections: List<SectionDraft> = emptyList(),
    val sectionsOpen: Boolean = false,
    val pauseAfterMs: Long? = null,
    val holdCountdown: Boolean = false,
    val calibrationStep: CalibrationStep = CalibrationStep.SOUND,
    /** Taps the calibration starts with, as errors in milliseconds. */
    val calibrationTaps: List<Double> = emptyList(),
    val dynamicColor: Boolean = false,
    val settings: PlaySettings = PlaySettings(),
    /** Opens Settings scrolled to this item: [PLAYFIELD_SETTINGS] for the playfield section. */
    val settingsItem: Int = 0,
    /** One of the sample songs has lost its file. */
    val missingSong: Boolean = false,
    /** The app's own stores instead of samples: a normal launch. */
    val real: Boolean = false,
    val debug: DebugOptions = DebugOptions(),
) {
    fun graph(stores: () -> AppStores): AppGraph = if (real) {
        val app = stores()
        debug.lowMemory?.let { app.charts.lowMemory = it }
        AppGraph(
            songs = app.songs,
            model = app.model,
            charts = app.charts,
            settings = app.settings,
            outputs = app.route,
            clicks = AudioClickTracks,
            playAudio = app.playAudio,
            debug = debug,
        )
    } else {
        sampleGraph()
    }

    private fun sampleGraph(): AppGraph = AppGraph(
        songs = FakeSongLibrary(
            when {
                !withSongs -> emptyList()
                missingSong -> FakeSongLibrary.WITH_MISSING
                else -> FakeSongLibrary.SONGS
            },
        ),
        model = FakeChartModelStore(model),
        charts = FakeChartMaker(make, FakeChartMaker.CAPABILITIES.takeIf { model is ModelState.Installed }),
        settings = FakePlaySettingsStore(settings, FakePlaySettingsStore.SPEAKER),
        outputs = FakePlayOutputs(FakePlaySettingsStore.SPEAKER),
        clicks = SilentClickTracks,
    )

    companion object {
        private const val SONG = "lantern-walk"
        private const val FREE = 18_400_000_000L
        private val PACKAGE = FakeChartModelStore.PACKAGE_BYTES
        private val ONE_SECTION = listOf(SectionDraft(id = 1, start = "00:32", end = "00:48", kind = SectionKind.JUMPSTREAM))

        /** A normal launch: the app's own stores. */
        val DEFAULT = Scenario(backStack = listOf(Library), real = true)

        private val FLAT = PlaySettings(landscapeLean = 0)
        private val MOST_LEAN = PlaySettings(landscapeLean = PlaySettings.LEANS.last)
        private val PORTRAIT = PlaySettings(orientation = PlayOrientation.PORTRAIT)

        val ALL: Map<String, Scenario> = mapOf(
            "first-run" to Scenario(listOf(Library), model = ModelState.Absent(PACKAGE, FREE), withSongs = false),
            "downloading" to Scenario(listOf(Library), model = ModelState.Downloading(50_600_000, PACKAGE), withSongs = false),
            "download-stopped" to Scenario(
                listOf(Library),
                model = ModelState.Stopped(50_600_000, PACKAGE, failure = "The connection was lost."),
                withSongs = true,
            ),
            "no-space" to Scenario(listOf(Library), model = ModelState.Absent(PACKAGE, 80_200_000), withSongs = false),
            "installing-files" to Scenario(
                listOf(Library),
                model = ModelState.Downloading(50_600_000, PACKAGE, fromFiles = true),
                withSongs = false,
            ),
            "download-failed" to Scenario(
                listOf(Library),
                model = ModelState.Absent(PACKAGE, FREE, failure = "The connection was lost."),
                withSongs = false,
            ),
            "install-files-refused" to Scenario(
                listOf(Library),
                model = ModelState.Absent(
                    PACKAGE,
                    FREE,
                    filesFailure = "These are not the files of the Ensomi R2 package r2-1.0.1, the one this version of Ensomi uses.",
                ),
                withSongs = false,
            ),
            "library" to Scenario(listOf(Library)),
            "library-missing" to Scenario(listOf(Library), missingSong = true),
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
                make = MakeState.Made(SONG, FakeChartMaker.MADE),
                sections = ONE_SECTION,
            ),
            "make-chart-stopped" to Scenario(listOf(Library, MakeChart(SONG)), make = MakeState.Stopped(SONG)),
            "make-chart-failed" to Scenario(
                listOf(Library, MakeChart(SONG)),
                make = MakeState.Failed(SONG, "star_out_of_range: star must be in [2, 6)"),
            ),
            "play" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = false))),
            "play-flat" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = false)), settings = FLAT),
            "play-lean-max" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = false)), settings = MOST_LEAN),
            "play-portrait" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = false)), settings = PORTRAIT),
            "play-portrait-lean" to Scenario(
                listOf(Library, MakeChart(SONG), Play(SONG, loop = false)),
                settings = PORTRAIT.copy(portraitLean = PlaySettings.DEFAULT_LANDSCAPE_LEAN),
            ),
            "play-preview" to Scenario(listOf(Library, Settings, Play(null, loop = true))),
            // The sample game without end, for the phone checks' frame pacing (androidTest FramePacingCheck).
            "pacing-flat" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = true)), settings = FLAT),
            "pacing" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = true))),
            "pacing-lean-max" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = true)), settings = MOST_LEAN),
            "pacing-portrait" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = true)), settings = PORTRAIT),
            "play-paused" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = false)), pauseAfterMs = 12_400),
            "play-countdown" to Scenario(
                listOf(Library, MakeChart(SONG), Play(SONG, loop = false)),
                pauseAfterMs = 12_400,
                holdCountdown = true,
            ),
            "results" to Scenario(listOf(Library, MakeChart(SONG), Results(SONG, SAMPLE_RESULT))),
            // The sample result over the app's own stores: Use writes the stored offset.
            "results-stored" to Scenario(listOf(Library, Results(null, SAMPLE_RESULT)), real = true),
            "settings" to Scenario(listOf(Library, Settings)),
            "settings-playfield" to Scenario(listOf(Library, Settings), settingsItem = PLAYFIELD_SETTINGS),
            "settings-playfield-flat" to Scenario(listOf(Library, Settings), settings = FLAT, settingsItem = PLAYFIELD_SETTINGS),
            "settings-playfield-max" to Scenario(listOf(Library, Settings), settings = MOST_LEAN, settingsItem = PLAYFIELD_SETTINGS),
            "settings-playfield-portrait" to Scenario(listOf(Library, Settings), settings = PORTRAIT, settingsItem = PLAYFIELD_SETTINGS),
            "settings-downloading" to Scenario(listOf(Library, Settings), model = ModelState.Downloading(50_600_000, PACKAGE)),
            "settings-update-pending" to Scenario(
                listOf(Library, Settings),
                model = ModelState.Installed(
                    "Ensomi R2 r2-1.0",
                    revision = "r2-1.0",
                    sizeBytes = PACKAGE,
                    license = "MIT",
                    update = ModelUpdate.Pending(FakeChartModelStore.PINNED_REVISION, "The connection was lost.", resumable = true),
                ),
            ),
            "notices" to Scenario(listOf(Library, Settings, Notices)),
            "notice-text" to Scenario(listOf(Library, Settings, Notices, NoticeText(ONNX_RUNTIME_NOTICES.last().path, asset = true))),
            "calibration-sound" to Scenario(listOf(Library, Settings, Calibration), calibrationTaps = SAMPLE_TAP_ERRORS_MS),
            "calibration-picture" to Scenario(listOf(Library, Settings, Calibration), calibrationStep = CalibrationStep.PICTURE),
            "diagnostics" to Scenario(listOf(Library, Settings, Diagnostics)),
        )

        /** The scenario and debug options [intent] asks for, when [debugTools] ([DebugTools]); else [DEFAULT]. */
        fun from(intent: Intent?, debugTools: Boolean): Scenario {
            if (!debugTools || intent == null) return DEFAULT
            val scenario = ALL[intent.getStringExtra("prototype")] ?: DEFAULT
            val debug = DebugOptions(
                stars = intent.getFloatExtra("stars", Float.NaN).takeIf { !it.isNaN() }?.toDouble(),
                autoplay = intent.getBooleanExtra("autoplay", false),
                lowMemory = if (intent.hasExtra("low-memory")) intent.getBooleanExtra("low-memory", false) else null,
                debuggable = true,
            )
            return scenario.copy(dynamicColor = intent.getBooleanExtra("dynamicColor", false), debug = debug)
        }
    }
}
