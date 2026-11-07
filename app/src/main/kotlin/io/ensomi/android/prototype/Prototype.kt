package io.ensomi.android.prototype

import android.content.Intent
import android.view.KeyEvent
import androidx.navigation3.runtime.NavKey
import io.ensomi.android.Calibration
import io.ensomi.android.Diagnostics
import io.ensomi.android.KeyboardSettings
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
import io.ensomi.android.keyboard.CaptureNote
import io.ensomi.android.keyboard.KeyCapture
import io.ensomi.android.keyboard.KeyTest
import io.ensomi.android.keyboard.Keyboards
import io.ensomi.android.library.SongLibrary
import io.ensomi.android.model.ChartModelStore
import io.ensomi.android.model.ModelState
import io.ensomi.android.model.ModelUpdate
import io.ensomi.android.model.ONNX_RUNTIME_NOTICES
import io.ensomi.android.settings.AudioLatency
import io.ensomi.android.settings.InputLatency
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.KeyTable
import io.ensomi.android.settings.LatencyKind
import io.ensomi.android.settings.LatencySlot
import io.ensomi.android.settings.PlayOrientation
import io.ensomi.android.settings.SettingsSection
import io.ensomi.android.settings.SlotNaming
import io.ensomi.android.settings.AudioClickTracks
import io.ensomi.android.settings.CalibrationHit
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
    /** The device a game's sound plays on, which the pause panel names when it changes. */
    val outputs: PlayOutputs,
    /** The calibration's click track. */
    val clicks: ClickTracks,
    /** The keyboards connected, whose keys play and label the caps. */
    val keyboards: Keyboards,
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
    /** Hits the calibration starts with, its slot dialog open, and its clicks paused as it opens. */
    val calibrationHits: List<CalibrationHit> = emptyList(),
    val calibrationNaming: SlotNaming? = null,
    val calibrationPaused: Boolean = false,
    val dynamicColor: Boolean = false,
    val settings: PlaySettings = PlaySettings(),
    /** Opens Settings scrolled to this section. */
    val settingsSection: SettingsSection = SettingsSection.TOP,
    /** Opens Settings with a slot's name dialog open. */
    val naming: SlotNaming? = null,
    /** One of the sample songs has lost its file. */
    val missingSong: Boolean = false,
    /**
     * The keyboards connected, made-up ones ([FakeKeyboards.SAMPLE]), so the caps carry labels and Settings › Keyboard can
     * wait for keys. Without one a scenario has no keyboard: its games take injected keys (`adb shell input`) only, while
     * one over the app's stores takes every keyboard's.
     */
    val keyboards: List<FakeKeyboard> = emptyList(),
    /** Settings › Keyboard opens with a slot waiting, or with what a key said ([KeyCapture]), or a test ([KeyTest]). */
    val keyCapture: KeyCapture = KeyCapture(),
    val keyTest: KeyTest = KeyTest(),
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
            keyboards = app.keyboards,
            playAudio = app.playAudio,
            debug = debug,
        )
    } else {
        sampleGraph()
    }

    private fun sampleGraph(): AppGraph {
        val store = FakePlaySettingsStore(settings, FakePlaySettingsStore.SPEAKER)
        return AppGraph(
            songs = FakeSongLibrary(
                when {
                    !withSongs -> emptyList()
                    missingSong -> FakeSongLibrary.WITH_MISSING
                    else -> FakeSongLibrary.SONGS
                },
            ),
            model = FakeChartModelStore(model),
            charts = FakeChartMaker(make, FakeChartMaker.CAPABILITIES.takeIf { model is ModelState.Installed }),
            settings = store,
            outputs = FakePlayOutputs(FakePlaySettingsStore.SPEAKER),
            clicks = SilentClickTracks,
            keyboards = FakeKeyboards(keyboards, store.settings),
        )
    }

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
        private val KEYBOARD = listOf(FakeKeyboards.SAMPLE)
        private val TWO_KEYBOARDS = listOf(FakeKeyboards.GERMAN, FakeKeyboards.SAMPLE)
        private val KEYS = listOf(Library, Settings, KeyboardSettings)
        private val CALIBRATION = listOf(Library, Settings, Calibration)

        private fun key(name: String) = KeyTable.named(name)!!

        /** X and Z swapped by binding X to lane 1, as the swap scenario says it. */
        private val SWAPPED = KeyMap.DEFAULT.bind(KeySlot.LANE_1, key("X"))
        private val LANE_3_EMPTY = PlaySettings(keys = KeyMap.DEFAULT.clear(KeySlot.LANE_3))
        private val LANES_2_3_EMPTY = PlaySettings(keys = KeyMap.DEFAULT.clear(KeySlot.LANE_2).clear(KeySlot.LANE_3))
        private val PAUSE_EMPTY = PlaySettings(keys = KeyMap.DEFAULT.clear(KeySlot.PAUSE))

        /** Named and unnamed slots: Bluetooth headphones and the speaker, and a keyboard's own input slot. */
        private val NAMED_SLOTS = PlaySettings(
            audioLatency = AudioLatency(
                listOf(LatencySlot("WH-1000XM4", 142), LatencySlot("Phone speaker", -2), LatencySlot()),
                active = 1,
            ),
            inputLatency = InputLatency(listOf(LatencySlot(ms = 8), LatencySlot("K380", 30), LatencySlot()), touch = 0, keyboard = 1),
        )

        /** Both methods on one slot, and the values at the ends of the range. */
        private val SHARED_SLOT = PlaySettings(
            audioLatency = AudioLatency(listOf(LatencySlot(ms = 300), LatencySlot(), LatencySlot(ms = -300)), active = 0),
            inputLatency = InputLatency(listOf(LatencySlot("Thumbs", 12), LatencySlot(), LatencySlot()), touch = 0, keyboard = 0),
        )

        /** Names of the most characters a slot keeps, for truncation at large text sizes. */
        private val LONG_NAMES = PlaySettings(
            audioLatency = AudioLatency(
                listOf(
                    LatencySlot("Living room soundbar HD2", 87),
                    LatencySlot("Wired headphones", 3),
                    LatencySlot("Galaxy Buds2 Pro (left)", 165),
                ),
                active = 2,
            ),
            inputLatency = InputLatency(
                listOf(LatencySlot("Glass, two thumbs", -6), LatencySlot("Desk keyboard by window", 41), LatencySlot()),
                touch = 0,
                keyboard = 1,
            ),
        )

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
            // A keyboard connected: the caps carry the keys' labels (keyboard.md, O6).
            "play-keyboard" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = false)), keyboards = KEYBOARD),
            "play-portrait-keyboard" to Scenario(
                listOf(Library, MakeChart(SONG), Play(SONG, loop = false)),
                settings = PORTRAIT,
                keyboards = KEYBOARD,
            ),
            "play-paused-keyboard" to Scenario(
                listOf(Library, MakeChart(SONG), Play(SONG, loop = false)),
                pauseAfterMs = 12_400,
                keyboards = KEYBOARD,
            ),
            "play-width-max" to Scenario(
                listOf(Library, MakeChart(SONG), Play(SONG, loop = false)),
                settings = PlaySettings(landscapeWidth = 0.98f),
            ),
            "play-portrait-width-max" to Scenario(
                listOf(Library, MakeChart(SONG), Play(SONG, loop = false)),
                settings = PORTRAIT.copy(portraitWidth = 0.98f),
            ),
            "play-portrait-lean" to Scenario(
                listOf(Library, MakeChart(SONG), Play(SONG, loop = false)),
                settings = PORTRAIT.copy(portraitLean = PlaySettings.DEFAULT_LANDSCAPE_LEAN),
            ),
            "play-preview" to Scenario(listOf(Library, Settings, Play(null, loop = true))),
            // The sample game without end, for the phone checks' frame pacing (androidTest FramePacingCheck).
            "pacing-flat" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = true)), settings = FLAT),
            "pacing" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = true))),
            // Same width as the original build, for a held-light cost comparison on the sample chart.
            "pacing-width-min" to Scenario(
                listOf(Library, MakeChart(SONG), Play(SONG, loop = true)),
                settings = PlaySettings(landscapeWidth = 0f),
            ),
            "pacing-lean-max" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = true)), settings = MOST_LEAN),
            "pacing-portrait" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = true)), settings = PORTRAIT),
            "play-paused" to Scenario(listOf(Library, MakeChart(SONG), Play(SONG, loop = false)), pauseAfterMs = 12_400),
            "play-countdown" to Scenario(
                listOf(Library, MakeChart(SONG), Play(SONG, loop = false)),
                pauseAfterMs = 12_400,
                holdCountdown = true,
            ),
            "results" to Scenario(listOf(Library, MakeChart(SONG), Results(SONG, SAMPLE_RESULT))),
            // The sample result over the app's own stores: Use writes the active audio latency slot.
            "results-stored" to Scenario(listOf(Library, Results(null, SAMPLE_RESULT)), real = true),
            // Over the app's own stores, for the latency checks: the sample game, played with the stored input
            // latency slots (no sound, so no audio slot), and Settings and the calibration showing the stored slots.
            "play-stored" to Scenario(listOf(Library, Play(null, loop = false)), real = true),
            "settings-stored" to Scenario(listOf(Library, Settings), real = true),
            "settings-stored-keyboard" to Scenario(listOf(Library, Settings), settingsSection = SettingsSection.KEYBOARD, real = true),
            "settings-stored-sound" to Scenario(listOf(Library, Settings), settingsSection = SettingsSection.SOUND, real = true),
            "keyboard-stored" to Scenario(KEYS, real = true),
            "calibration-stored" to Scenario(CALIBRATION, real = true),
            "settings" to Scenario(listOf(Library, Settings)),
            "settings-playfield" to Scenario(listOf(Library, Settings), settingsSection = SettingsSection.PLAYFIELD),
            "settings-playfield-flat" to Scenario(
                listOf(Library, Settings),
                settings = FLAT,
                settingsSection = SettingsSection.PLAYFIELD,
            ),
            "settings-playfield-max" to Scenario(
                listOf(Library, Settings),
                settings = MOST_LEAN,
                settingsSection = SettingsSection.PLAYFIELD,
            ),
            "settings-playfield-portrait" to Scenario(
                listOf(Library, Settings),
                settings = PORTRAIT,
                settingsSection = SettingsSection.PLAYFIELD,
            ),
            // Settings › Keyboard's row (keyboard.md, Settings › Keyboard): no keyboard, two, and a lane without a key.
            "settings-keyboard" to Scenario(listOf(Library, Settings), settingsSection = SettingsSection.KEYBOARD),
            "settings-keyboard-connected" to Scenario(
                listOf(Library, Settings),
                keyboards = TWO_KEYBOARDS,
                settingsSection = SettingsSection.KEYBOARD,
            ),
            "settings-keyboard-lane-empty" to Scenario(
                listOf(Library, Settings),
                settings = LANE_3_EMPTY,
                keyboards = KEYBOARD,
                settingsSection = SettingsSection.KEYBOARD,
            ),
            // Settings › Keyboard in each state of keyboard.md's table. Keys come from `adb shell input keyevent`.
            "keyboard" to Scenario(KEYS),
            "keyboard-connected" to Scenario(KEYS, keyboards = KEYBOARD),
            "keyboard-two" to Scenario(KEYS, keyboards = TWO_KEYBOARDS),
            "keyboard-waiting" to Scenario(KEYS, keyboards = KEYBOARD, keyCapture = KeyCapture(waiting = KeySlot.LANE_2)),
            "keyboard-refused" to Scenario(
                KEYS,
                keyboards = KEYBOARD,
                keyCapture = KeyCapture(waiting = KeySlot.LANE_2, note = CaptureNote.Refused),
            ),
            "keyboard-swapped" to Scenario(
                KEYS,
                settings = PlaySettings(keys = SWAPPED.map),
                keyboards = KEYBOARD,
                keyCapture = KeyCapture(note = CaptureNote.Swapped(KeySlot.LANE_1, key("X"), KeySlot.LANE_2, key("Z"))),
            ),
            "keyboard-lane-empty" to Scenario(KEYS, settings = LANE_3_EMPTY, keyboards = KEYBOARD),
            "keyboard-lanes-empty" to Scenario(KEYS, settings = LANES_2_3_EMPTY, keyboards = KEYBOARD),
            "keyboard-lane-empty-none" to Scenario(KEYS, settings = LANE_3_EMPTY),
            "keyboard-pause-empty" to Scenario(KEYS, settings = PAUSE_EMPTY, keyboards = KEYBOARD),
            "keyboard-testing" to Scenario(
                KEYS,
                keyboards = KEYBOARD,
                keyTest = KeyTest.sample(
                    testing = true,
                    held = listOf(0, 1, 3),
                    others = listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_A),
                    mostHeld = 3,
                    pressed = setOf(0, 1, 3),
                ),
            ),
            "keyboard-limit" to Scenario(
                KEYS,
                keyboards = KEYBOARD,
                keyTest = KeyTest.sample(testing = false, held = emptyList(), others = emptyList(), mostHeld = 3, pressed = setOf(0, 1, 2, 3)),
            ),
            // Settings › Sound and timing's latency slots (latency.md, Settings).
            "settings-latency" to Scenario(listOf(Library, Settings), settings = NAMED_SLOTS, settingsSection = SettingsSection.SOUND),
            "settings-latency-plain" to Scenario(listOf(Library, Settings), settingsSection = SettingsSection.SOUND),
            "settings-latency-shared" to Scenario(
                listOf(Library, Settings),
                settings = SHARED_SLOT,
                settingsSection = SettingsSection.SOUND,
            ),
            "settings-latency-long" to Scenario(listOf(Library, Settings), settings = LONG_NAMES, settingsSection = SettingsSection.SOUND),
            "settings-latency-name" to Scenario(
                listOf(Library, Settings),
                settings = NAMED_SLOTS,
                settingsSection = SettingsSection.SOUND,
                naming = SlotNaming(LatencyKind.AUDIO, 0),
            ),
            "settings-latency-name-empty" to Scenario(
                listOf(Library, Settings),
                settings = NAMED_SLOTS,
                settingsSection = SettingsSection.SOUND,
                naming = SlotNaming(LatencyKind.INPUT, 2),
            ),
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
            // The calibration, one screen (latency.md, Timing calibration): no hits yet; touch and key hits on named
            // slots, Touch chosen; a keyboard connected, so Keyboard is chosen and the caps carry its keys; the longest
            // names; both methods on one slot with values past the sliders' reach; paused; a slot's name dialog. Taps and
            // keys from `adb shell input` press on the scenarios too, on a click track without sound.
            "calibration" to Scenario(CALIBRATION),
            "calibration-hits" to Scenario(CALIBRATION, settings = NAMED_SLOTS, calibrationHits = sampleCalibrationHits(NAMED_SLOTS)),
            "calibration-keyboard" to Scenario(
                CALIBRATION,
                settings = NAMED_SLOTS,
                keyboards = KEYBOARD,
                calibrationHits = sampleCalibrationHits(NAMED_SLOTS),
            ),
            "calibration-long" to Scenario(CALIBRATION, settings = LONG_NAMES, calibrationHits = sampleCalibrationHits(LONG_NAMES)),
            "calibration-shared" to Scenario(CALIBRATION, settings = SHARED_SLOT),
            "calibration-paused" to Scenario(
                CALIBRATION,
                settings = NAMED_SLOTS,
                calibrationHits = sampleCalibrationHits(NAMED_SLOTS),
                calibrationPaused = true,
            ),
            "calibration-name" to Scenario(
                CALIBRATION,
                settings = NAMED_SLOTS,
                calibrationNaming = SlotNaming(LatencyKind.AUDIO, 1),
            ),
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
