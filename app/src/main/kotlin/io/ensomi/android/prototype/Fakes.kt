package io.ensomi.android.prototype

import android.content.Context
import android.media.AudioDeviceInfo
import android.net.Uri
import android.view.KeyEvent
import io.ensomi.android.chart.ChartCapabilities
import io.ensomi.android.chart.ChartMaker
import io.ensomi.android.chart.ChartRequest
import io.ensomi.android.chart.KindOption
import io.ensomi.android.chart.MadeChart
import io.ensomi.android.chart.MakeState
import io.ensomi.android.chart.SectionKind
import io.ensomi.android.chart.songId
import io.ensomi.android.keyboard.KeyLabels
import io.ensomi.android.keyboard.Keyboard
import io.ensomi.android.keyboard.KeyboardRoster
import io.ensomi.android.keyboard.Keyboards
import io.ensomi.android.library.AddResult
import io.ensomi.android.library.Song
import io.ensomi.android.library.SongLibrary
import io.ensomi.android.model.ChartModelStore
import io.ensomi.android.model.ModelSource
import io.ensomi.android.model.ModelState
import io.ensomi.android.model.Notice
import io.ensomi.android.model.ONNX_RUNTIME_NOTICES
import io.ensomi.android.play.FakePlayClock
import io.ensomi.android.play.InputSource
import io.ensomi.android.play.PlayClock
import io.ensomi.android.play.PlayResult
import io.ensomi.android.settings.CalibrationEdits
import io.ensomi.android.settings.CalibrationHit
import io.ensomi.android.settings.ClickPlayer
import io.ensomi.android.settings.ClickTracks
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeyTable
import io.ensomi.android.settings.OutputRoute
import io.ensomi.android.settings.PlayOutputs
import io.ensomi.android.settings.PlaySettings
import io.ensomi.android.settings.PlaySettingsStore
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    override fun installFromFolder(folder: Uri) = Unit

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

    companion object {
        val SPEAKER = OutputRoute(key = "builtin-speaker", name = "Phone speaker")
    }
}

/** For the prototype's scenarios: every game plays on [route]. */
class FakePlayOutputs(private val route: OutputRoute) : PlayOutputs {
    override fun routeOf(device: AudioDeviceInfo?): OutputRoute = route

    override fun playingOn(device: AudioDeviceInfo?) = Unit
}

/**
 * A made-up keyboard for the prototype's scenarios: [layout] is the key code each place on it produces, as
 * `InputDevice.getKeyCodeForKeyLocation` gives it, and its caps are labelled from the key table.
 */
data class FakeKeyboard(val deviceId: Int, val name: String, val productId: Int, val layout: (Int) -> Int) {
    /** This keyboard as the input system would describe it with [map]. */
    fun describe(map: KeyMap): Keyboard {
        val keys = map.resolve(layout)
        val labels = KeyLabels.of(
            keys,
            { code -> KeyTable.keys.firstOrNull { it.keyCode == code }?.label?.singleOrNull() ?: KeyLabels.NONE },
            space = "Space",
            escape = "Esc",
        )
        return Keyboard(
            deviceId = deviceId,
            name = name,
            vendorId = 0x046d,
            productId = productId,
            external = true,
            keys = keys,
            labels = labels,
            map = map,
        )
    }
}

/**
 * For the prototype's scenarios: fixed keyboards, none by default, resolved again whenever the key map of [settings]
 * changes, as the app's are. Only their own made-up devices play; keys injected by `adb shell input` come from the
 * virtual keyboard, which plays in any game. The app's are [io.ensomi.android.keyboard.ConnectedKeyboards].
 */
class FakeKeyboards(connected: List<FakeKeyboard>, settings: StateFlow<PlaySettings>) : Keyboards {
    private val roster = KeyboardRoster { deviceId, map -> connected.firstOrNull { it.deviceId == deviceId }?.describe(map) }

    override val keyboards: StateFlow<List<Keyboard>> = roster.keyboards

    init {
        roster.mapChanged(settings.value.keys)
        roster.started(connected.map { it.deviceId })
        // The scenario's graph lives as long as the process, as the app's stores do.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { settings.collect { roster.mapChanged(it.keys) } }
    }

    override fun keys(deviceId: Int) = roster.keys(deviceId)

    override fun refresh() = Unit

    companion object {
        /** A US QWERTY Bluetooth keyboard. */
        val SAMPLE = FakeKeyboard(deviceId = 9_001, name = "K380", productId = 0xb342, layout = { it })

        /**
         * A German QWERTZ keyboard: the Z place gives Y and the Y place Z, and the slash place, right of the period, gives
         * the minus key, as `getKeyCodeForKeyLocation` would. The rest as US QWERTY, which is enough for the default lanes.
         */
        val GERMAN = FakeKeyboard(deviceId = 9_002, name = "Tastatur", productId = 0xc31c) { place ->
            when (place) {
                KeyEvent.KEYCODE_Z -> KeyEvent.KEYCODE_Y
                KeyEvent.KEYCODE_Y -> KeyEvent.KEYCODE_Z
                KeyEvent.KEYCODE_SLASH -> KeyEvent.KEYCODE_MINUS
                else -> place
            }
        }
    }
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

/**
 * Sample hits for the calibration scenarios, judged with [settings]' values so they show as made: 16 touch hits about
 * 6 ms late and 9 key hits about 4 ms early.
 */
fun sampleCalibrationHits(settings: PlaySettings): List<CalibrationHit> {
    val touch = listOf(4.0, 9.0, 2.0, 11.0, 6.0, 7.0, 3.0, 8.0, 5.0, 10.0, 6.0, 4.0, 9.0, 5.0, 7.0, 6.0)
    val keys = listOf(-6.0, -2.0, -9.0, -3.0, -5.0, -1.0, -7.0, -4.0, -3.0)
    fun hits(method: InputSource, errors: List<Double>) =
        errors.map { CalibrationHit(method, it, CalibrationEdits.latencyMs(settings, method)) }
    return hits(InputSource.TOUCH, touch) + hits(InputSource.KEYBOARD, keys)
}
