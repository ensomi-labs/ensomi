package io.ensomi.android.settings

import android.animation.ValueAnimator
import android.media.AudioDeviceInfo
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ensomi.android.R
import io.ensomi.android.audio.ClickTrack
import io.ensomi.android.keyboard.HardwareKeyHandler
import io.ensomi.android.keyboard.KeepInputMethodAway
import io.ensomi.android.keyboard.Keyboard
import io.ensomi.android.keyboard.Keyboards
import io.ensomi.android.play.CalibrationKeys
import io.ensomi.android.play.InputSource
import io.ensomi.android.play.JudgedPress
import io.ensomi.android.play.PlayController
import io.ensomi.android.play.PlayTiming
import io.ensomi.android.play.PlayfieldView
import io.ensomi.android.play.SampleCharts
import io.ensomi.android.play.rememberAudioFocus
import io.ensomi.android.play.toRuntime
import io.ensomi.android.ui.DarkSystemBars
import io.ensomi.android.ui.theme.Brand
import io.ensomi.android.ui.theme.GameTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Timing calibration on one screen (latency.md, Timing calibration), in the game's dark presentation. The click track
 * plays through the play output from the moment the screen opens until it closes, and the game's renderer draws a note
 * onto the line on each click and judges every press on it: a finger anywhere on the field, or any lane key, presses the
 * note's lane, each judged with its own method's input slot as in play (keyboard.md, Calibration with keys).
 *
 * The player moves the audio latency, the active audio slot's value, and the input latency of the method chosen, Touch
 * or Keyboard (O3), in any order, judging by sight and sound alone or by pressing too. Each change is kept at once in the
 * slot it edits and the field takes it on its next frame; the slots are chosen and named here as in Settings. The
 * readings show the chosen method's last hits as they would be judged with the values now. Nothing is suggested,
 * applied or confirmed: Back leaves.
 *
 * In portrait the field is at the top and stays there, the readings under it, and the controls scroll below them; wider
 * than tall, the field fills the left, and the readings and the controls take the right. Either way the field stays in
 * sight while a value moves.
 *
 * The clicks pause when the app leaves the foreground or another app takes audio focus, and the field says so; a tap on
 * it, or a lane key, makes them go on. A change of output starts the readings over; the slots stay as chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    settings: PlaySettings,
    route: OutputRoute,
    clicks: ClickTracks,
    keyboards: Keyboards,
    /** The device the clicks play on, which the screen names. */
    onPlayingOn: (AudioDeviceInfo?) -> Unit,
    onChange: ((PlaySettings) -> PlaySettings) -> Unit,
    onBack: () -> Unit,
    /** Screenshots only: hits already made, a slot's name dialog open, the clicks paused as the screen opens. */
    initialHits: List<CalibrationHit> = emptyList(),
    initialNaming: SlotNaming? = null,
    startPaused: Boolean = false,
) {
    GameTheme {
        DarkSystemBars()
        // The game's screens keep the input method away from keys (keyboard.md, D6); the name dialog keeps its own.
        KeepInputMethodAway()
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val playingOn by rememberUpdatedState(onPlayingOn)
        val connected by keyboards.keyboards.collectAsStateWithLifecycle()
        // Which method's input slot is edited and whose readings show: Keyboard when one is connected as the screen
        // opens, Touch otherwise. Only the player changes it, so a stray press never moves the slot being edited.
        var method by rememberSaveable { mutableStateOf(CalibrationEdits.firstMethod(keyboards.keyboards.value.isNotEmpty())) }
        var player by remember { mutableStateOf<ClickPlayer?>(null) }
        var playfield by remember { mutableStateOf<PlayfieldView?>(null) }
        var paused by remember { mutableStateOf(false) }
        var readings by remember { mutableStateOf(CalibrationReadings(initialHits)) }
        var naming by remember { mutableStateOf(initialNaming) }
        // The field's presses, from its render thread.
        val presses = remember { Channel<JudgedPress>(Channel.UNLIMITED) }
        val reduceMotion = remember { !ValueAnimator.areAnimatorsEnabled() }

        DisposableEffect(clicks) {
            val opened = clicks.open(context.applicationContext) { playingOn(it) }
            opened.clock.start(System.nanoTime(), 0.0)
            player = opened
            Log.i(AudioClickTracks.TAG, "Clicks started; latency: ${PlayTiming.game(settings).describe()}")
            onDispose {
                player = null
                // Releasing the track waits for its writer: not on the main thread.
                Thread({ opened.close() }, "EnsomiClicksClose").start()
            }
        }

        fun pauseClicks(cause: String) {
            val clicking = player ?: return
            if (!clicking.clock.isPaused) {
                clicking.clock.pause(System.nanoTime())
                Log.i(AudioClickTracks.TAG, "Clicks paused: $cause")
            }
            // Fingers and keys down keep their lane, and nothing is pressed until the clicks go on (keyboard.md, O7).
            playfield?.pause()
            paused = true
        }

        // Focus back after a loss for a while, or the app back in front: the clicks go on.
        var resumeRequested by remember { mutableStateOf(false) }
        val focus = rememberAudioFocus(
            onLost = { cause -> pauseClicks(cause) },
            onGained = { if (paused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resumeRequested = true },
        )
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { pauseClicks("the app left the foreground (ON_PAUSE)") }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (paused) resumeRequested = true }

        fun resumeClicks() {
            val clicking = player ?: return
            if (!focus.request()) {
                Log.i(AudioClickTracks.TAG, "Clicks stay paused: no audio focus")
                return
            }
            playfield?.resume()
            clicking.clock.resume(System.nanoTime())
            paused = false
            Log.i(AudioClickTracks.TAG, "Clicks go on")
        }

        LaunchedEffect(resumeRequested) {
            if (!resumeRequested) return@LaunchedEffect
            resumeRequested = false
            resumeClicks()
        }
        if (startPaused) {
            LaunchedEffect(player) {
                if (player == null) return@LaunchedEffect
                delay(SCENARIO_PAUSE_MS)
                pauseClicks("the scenario")
            }
        }

        // When click 0 is heard on the System.nanoTime() clock, for a check to recompute the errors: when it moves, as it
        // does after a pause or when the output reports anew, and every five seconds. The field's render thread reads
        // the output every frame; this only asks the clock.
        LaunchedEffect(player) {
            val clicking = player ?: return@LaunchedEffect
            var loggedAt: Long? = null
            var loggedNanos = 0L
            while (isActive) {
                delay(CLICK_POLL_MS)
                val now = System.nanoTime()
                val songMs = clicking.clock.songTimeMs(now) ?: continue
                if (songMs <= 0 || clicking.clock.isPaused) continue
                val click0 = clicking.clock.nanoTimeAt(ClickTrack.FIRST_CLICK_MS)
                val moved = loggedAt?.let { abs(click0 - it) > CLICK_MOVED_NANOS } ?: true
                if (!moved && now - loggedNanos < CLICK_LOG_NANOS) continue
                loggedAt = click0
                loggedNanos = now
                Log.i(
                    AudioClickTracks.TAG,
                    String.format(Locale.ROOT, "Clicks: song time 1000 ms at %d ns; now %.1f ms", click0, songMs),
                )
            }
        }

        LaunchedEffect(presses) {
            for (press in presses) {
                Log.i(AudioClickTracks.TAG, CalibrationLog.press(press))
                CalibrationReadings.hitOf(press)?.let { readings = readings.add(it) }
            }
        }

        // Another output has its own latency: the readings start over, and the slots stay as chosen.
        var readingsRoute by remember { mutableStateOf(route.key) }
        LaunchedEffect(route.key) {
            if (route.key == readingsRoute) return@LaunchedEffect
            readingsRoute = route.key
            Log.i(AudioClickTracks.TAG, "Sound moved: the readings start over")
            readings = readings.cleared()
        }

        // Each change of a value or a slot, as the screen takes it; a change is kept the moment it is made.
        val logged = remember { LoggedSettings(settings) }
        LaunchedEffect(settings) {
            CalibrationLog.changes(logged.settings, settings).forEach { Log.i(AudioClickTracks.TAG, it) }
            logged.settings = settings
        }
        LaunchedEffect(method) { Log.i(AudioClickTracks.TAG, CalibrationLog.method(method)) }

        // Lane keys press the note's lane, with the keyboard's input slot, through the field's sender and queue.
        HardwareKeyHandler { event ->
            val view = playfield ?: return@HardwareKeyHandler false
            when (view.calibrationKeyInput(event, paused)) {
                CalibrationKeys.Action.PASS_ON -> false
                CalibrationKeys.Action.TAKE -> true
                CalibrationKeys.Action.RESUME -> {
                    resumeClicks()
                    true
                }
            }
        }

        Scaffold(
            containerColor = Brand.Plum,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    title = { Text(stringResource(R.string.calibration_title)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand.Plum),
                )
            },
        ) { padding ->
            // One layout for both shapes, so that turning the phone resizes the field and keeps its game: wider than
            // tall, the field on the left and the rest on the right; otherwise the field on top, the rest under it.
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
            ) {
                val wide = maxWidth > maxHeight
                val fieldWidth = if (wide) (maxWidth - GAP) / 2 else maxWidth
                // The field keeps its share however large the text: the readings and controls beside or under it give way.
                val fieldHeight = if (wide) maxHeight else maxHeight * FIELD_SHARE
                CalibrationField(
                    player = player,
                    settings = settings,
                    keyboards = keyboards,
                    connected = connected,
                    paused = paused,
                    reduceMotion = reduceMotion,
                    onPress = { presses.trySend(it) },
                    onView = { playfield = it },
                    onGoOn = ::resumeClicks,
                    modifier = Modifier.size(fieldWidth, fieldHeight),
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = if (wide) fieldWidth + GAP else 0.dp, top = if (wide) 0.dp else fieldHeight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = COLUMN_MAX)
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(start = if (wide) 0.dp else 16.dp, end = 16.dp),
                    ) {
                        Readings(
                            readings,
                            method,
                            settings,
                            onClear = {
                                Log.i(AudioClickTracks.TAG, "Hits cleared")
                                readings = readings.cleared()
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = if (wide) 0.dp else 8.dp),
                        )
                        Controls(
                            settings = settings,
                            route = route,
                            method = method,
                            onMethod = { method = it },
                            onChange = onChange,
                            onName = { naming = it },
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
                        )
                    }
                }
            }
        }
        naming?.let { open -> NameSlot(open, settings, onChange, onClose = { naming = null }) }
    }
}

/** The settings the calibration last logged against. Not state: nothing is drawn from it. */
private class LoggedSettings(var settings: PlaySettings)

/**
 * The play renderer on the clicks' own clock, judging: a note in the calibration's lane on every click, with the
 * player's lean and lane width, the game's flares, judgement word and lane lights. A finger anywhere on it, or any lane
 * key ([PlayfieldView.calibrationKeyInput]), presses that lane, so each press reaches [onPress] through the same sender,
 * queue and input latency slots as in play. While [paused], it says so over its frozen picture, and a tap goes on.
 */
@Composable
private fun CalibrationField(
    player: ClickPlayer?,
    settings: PlaySettings,
    keyboards: Keyboards,
    connected: List<Keyboard>,
    paused: Boolean,
    reduceMotion: Boolean,
    onPress: (JudgedPress) -> Unit,
    onView: (PlayfieldView?) -> Unit,
    onGoOn: () -> Unit,
    modifier: Modifier,
) {
    val description = stringResource(R.string.calibration_field_description)
    val pausedText = stringResource(R.string.calibration_paused)
    Box(modifier) {
        if (player != null) {
            key(player) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        PlayfieldView(viewContext).apply {
                            hudHeightDp = FIELD_TOP_DP
                            contentDescription = description
                            pressLane = SampleCharts.CALIBRATION_LANE
                            takesUntimedClicks = true
                            // The map as the screen opens: Settings › Keyboard is not reachable from here.
                            useKeys(settings.keys, keyboards::keys)
                            if (paused) pause()
                            controllerFactory = { hud, sequence ->
                                PlayController(
                                    chart = SampleCharts.CALIBRATION,
                                    judge = settings.judge.toRuntime(),
                                    clock = player.clock,
                                    // The clicks play from the moment the screen opens: the field neither starts nor closes them.
                                    ownsClock = false,
                                    timing = PlayTiming.game(settings),
                                    progress = false,
                                    leadInMs = 0.0,
                                    hud = hud,
                                    nextSequence = sequence,
                                    onPress = onPress,
                                )
                            }
                            onView(this)
                        }
                    },
                    update = {
                        it.reduceMotion = reduceMotion
                        // The values now: the field takes them on its next frame.
                        it.timing = PlayTiming.game(settings)
                        // The lean and width the player plays with, whatever the field's own shape.
                        val lean = PlaySettings.farScale(settings.lean)
                        it.landscapeFarScale = lean
                        it.portraitFarScale = lean
                        it.landscapeWidth = settings.laneWidth(settings.orientation)
                        it.portraitWidth = settings.laneWidth(settings.orientation)
                        it.capLabels = connected.firstOrNull()?.laneLabels
                        it.keyboardsChanged(connected)
                        if (paused) it.pause()
                    },
                    onRelease = { onView(null) },
                )
            }
        }
        if (paused) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brand.Plum.copy(alpha = PAUSED_SCRIM))
                    .clickable(role = Role.Button, onClick = onGoOn)
                    .semantics { contentDescription = pausedText },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    pausedText,
                    style = MaterialTheme.typography.titleMedium,
                    color = Brand.Text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp).clearAndSetSemantics { },
                )
            }
        }
    }
}

/**
 * The readings for [method]: the strip of its last hits from early to late, and "Last 16 touch hits: 6 ms early on
 * average.", each hit as it would be judged with the values now; and Clear hits, which empties them and changes no value.
 * The line is a polite live region, so TalkBack hears it whenever it changes.
 */
@Composable
private fun Readings(
    readings: CalibrationReadings,
    method: InputSource,
    settings: PlaySettings,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latencyMs = CalibrationEdits.latencyMs(settings, method)
    val shown = readings.shown(method, latencyMs)
    val average = readings.averageMs(method, latencyMs)
    val line = readingLine(method, shown.size, average)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HitStrip(shown, average, method)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.Text,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onClear, enabled = readings.hits.isNotEmpty()) {
                Text(stringResource(R.string.calibration_clear))
            }
        }
    }
}

@Composable
private fun readingLine(method: InputSource, count: Int, averageMs: Double?): String {
    val keys = method == InputSource.KEYBOARD
    if (count == 0 || averageMs == null) {
        return stringResource(if (keys) R.string.calibration_no_key_hits else R.string.calibration_no_touch_hits)
    }
    val lead = pluralStringResource(if (keys) R.plurals.calibration_last_key else R.plurals.calibration_last_touch, count, count)
    val where = if (count == 1) {
        offsetWords(averageMs, R.string.calibration_early, R.string.calibration_late, R.string.calibration_on_time)
    } else {
        offsetWords(averageMs, R.string.calibration_early_average, R.string.calibration_late_average, R.string.calibration_on_time_average)
    }
    return stringResource(R.string.calibration_reading, lead, where)
}

/** "6 ms early", "6 ms late" or "on time", to the nearest millisecond, in the words given. */
@Composable
private fun offsetWords(ms: Double, @StringRes early: Int, @StringRes late: Int, @StringRes onTime: Int): String {
    val rounded = ms.roundToInt()
    return when {
        rounded < 0 -> stringResource(early, -rounded)
        rounded > 0 -> stringResource(late, rounded)
        else -> stringResource(onTime)
    }
}

/**
 * The hits on a track from [STRIP_MS] early to as late, the newest brightest: early in lavender on the left, late in rose
 * on the right, a hit further out at the end it is on. The middle is the judgement line's coral, where a hit on the click
 * lands, and white marks show the average the line states. TalkBack hears how far the hits spread.
 */
@Composable
private fun HitStrip(errorsMs: List<Double>, averageMs: Double?, method: InputSource) {
    val description = stringResource(if (method == InputSource.KEYBOARD) R.string.calibration_strip_key else R.string.calibration_strip_touch)
    val state = if (errorsMs.isEmpty()) {
        stringResource(R.string.calibration_strip_empty)
    } else {
        val early = offsetWords(errorsMs.min(), R.string.calibration_early, R.string.calibration_late, R.string.calibration_on_time)
        val late = offsetWords(errorsMs.max(), R.string.calibration_early, R.string.calibration_late, R.string.calibration_on_time)
        stringResource(R.string.calibration_strip_range, early, late)
    }
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(STRIP_HEIGHT).semantics {
                contentDescription = description
                stateDescription = state
            },
        ) {
            val radius = 5.dp.toPx()
            val middle = size.width / 2
            val reach = middle - radius
            fun xOf(ms: Double) = middle + (ms.coerceIn(-STRIP_MS, STRIP_MS) / STRIP_MS).toFloat() * reach
            drawRoundRect(Brand.Panel, cornerRadius = CornerRadius(size.height / 2))
            drawLine(Brand.Action, Offset(middle, 4.dp.toPx()), Offset(middle, size.height - 4.dp.toPx()), strokeWidth = 2.dp.toPx())
            errorsMs.forEachIndexed { index, error ->
                val alpha = 0.35f + 0.65f * (index + 1) / errorsMs.size
                drawCircle(if (error < 0) Brand.Lavender else Brand.Rose, radius, Offset(xOf(error), size.height / 2), alpha)
            }
            averageMs?.let {
                val x = xOf(it)
                val mark = 2.5.dp.toPx()
                drawLine(Brand.Text, Offset(x, 0f), Offset(x, mark * 2), strokeWidth = mark)
                drawLine(Brand.Text, Offset(x, size.height - mark * 2), Offset(x, size.height), strokeWidth = mark)
            }
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            Text(
                stringResource(R.string.results_early),
                style = MaterialTheme.typography.labelSmall,
                color = Brand.Lavender,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.results_late),
                style = MaterialTheme.typography.labelSmall,
                color = Brand.Rose,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The help, then the two values, each on a panel: Audio latency with its slots, its value with steps and a slider, Name
 * and the output the clicks play on; Input latency with the method chosen, the slot that method uses, its value with
 * steps and a slider, and Name. Every change goes straight to the settings store.
 */
@Composable
private fun Controls(
    settings: PlaySettings,
    route: OutputRoute,
    method: InputSource,
    onMethod: (InputSource) -> Unit,
    onChange: ((PlaySettings) -> PlaySettings) -> Unit,
    onName: (SlotNaming) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.calibration_help), style = MaterialTheme.typography.bodyMedium, color = Brand.MutedText)
        Panel {
            val active = settings.audioLatency.active
            val what = stringResource(R.string.settings_audio_latency_value, latencySlotLabel(active, settings.audioLatency.slots[active]))
            PanelTitle(R.string.settings_audio_latency)
            AudioSlotChoice(
                latency = settings.audioLatency,
                onChoose = { slot -> onChange { CalibrationEdits.audioSlot(it, slot) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = PANEL_TEXT_INSET),
            )
            LatencyStepper(
                value = settings.audioLatencyMs,
                what = what,
                nameLabel = stringResource(R.string.latency_name_audio, active),
                onStep = { step -> onChange { CalibrationEdits.audioStep(it, step) } },
                onName = { onName(SlotNaming(LatencyKind.AUDIO, active)) },
                modifier = Modifier.fillMaxWidth(),
                valueStyle = MaterialTheme.typography.headlineSmall,
            )
            LatencySlider(
                value = settings.audioLatencyMs,
                what = what,
                onValue = { ms -> onChange { CalibrationEdits.audioMs(it, ms) } },
                onStep = { step -> onChange { CalibrationEdits.audioStep(it, step) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = PANEL_TEXT_INSET),
            )
            PanelNote(stringResource(R.string.calibration_audio_help))
            PanelNote(stringResource(R.string.settings_sound_now, route.name))
        }
        Panel {
            val slot = CalibrationEdits.inputSlot(settings.inputLatency, method)
            val methodName = stringResource(if (method == InputSource.KEYBOARD) R.string.settings_input_keyboard else R.string.settings_input_touch)
            val what = stringResource(R.string.settings_input_latency_value, methodName, latencySlotLabel(slot, settings.inputLatency.slots[slot]))
            PanelTitle(R.string.settings_input_latency)
            MethodChoice(method, onMethod, Modifier.fillMaxWidth().padding(horizontal = PANEL_TEXT_INSET))
            InputSlotMenu(
                label = stringResource(R.string.settings_input_uses, methodName),
                latency = settings.inputLatency,
                slot = slot,
                onSlot = { chosen -> onChange { CalibrationEdits.inputSlot(it, method, chosen) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = PANEL_TEXT_INSET).padding(top = 4.dp),
            )
            LatencyStepper(
                value = CalibrationEdits.inputMs(settings, method),
                what = what,
                nameLabel = stringResource(R.string.latency_name_input, slot),
                onStep = { step -> onChange { CalibrationEdits.inputStep(it, method, step) } },
                onName = { onName(SlotNaming(LatencyKind.INPUT, slot)) },
                modifier = Modifier.fillMaxWidth(),
                valueStyle = MaterialTheme.typography.headlineSmall,
            )
            LatencySlider(
                value = CalibrationEdits.inputMs(settings, method),
                what = what,
                onValue = { ms -> onChange { CalibrationEdits.inputMs(it, method, ms) } },
                onStep = { step -> onChange { CalibrationEdits.inputStep(it, method, step) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = PANEL_TEXT_INSET),
            )
            PanelNote(stringResource(R.string.calibration_input_help))
        }
    }
}

/** Touch or Keyboard: whose input slot the panel edits and whose readings show (O3). */
@Composable
private fun MethodChoice(method: InputSource, onMethod: (InputSource) -> Unit, modifier: Modifier) {
    val methods = listOf(InputSource.TOUCH, InputSource.KEYBOARD)
    SingleChoiceSegmentedButtonRow(modifier) {
        methods.forEachIndexed { index, each ->
            SegmentedButton(
                selected = method == each,
                onClick = { onMethod(each) },
                shape = SegmentedButtonDefaults.itemShape(index, methods.size),
                icon = {},
            ) {
                Text(stringResource(if (each == InputSource.KEYBOARD) R.string.settings_input_keyboard else R.string.settings_input_touch))
            }
        }
    }
}

/** A panel in the brand's card shape, as the game's other panels are (design.md, Visual language). */
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 8.dp),
        color = Brand.Panel,
        border = BorderStroke(1.dp, Brand.PanelBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Icon buttons carry their own 12 dp: the steps' symbols line up with the text beside them.
        Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun PanelTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = Brand.Text,
        modifier = Modifier.padding(horizontal = PANEL_TEXT_INSET).semantics { heading() },
    )
}

@Composable
private fun PanelNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Brand.MutedText,
        modifier = Modifier.padding(horizontal = PANEL_TEXT_INSET),
    )
}

/** The field's share of a tall window's height: the rest is the readings and the controls, which scroll. */
private const val FIELD_SHARE = 0.42f

/** Room above the lanes: the field has no HUD. */
private const val FIELD_TOP_DP = 12f

/** The readings and controls, centred in a window wider than this. */
private val COLUMN_MAX = 640.dp

/** Between the field and the column beside it. */
private val GAP = 16.dp

private val PANEL_TEXT_INSET = 12.dp
private val STRIP_HEIGHT = 32.dp

/** The strip's reach each way, in milliseconds. */
private const val STRIP_MS = 100.0

/** The plum laid over the field's frozen picture while the clicks are paused. */
private const val PAUSED_SCRIM = 0.72f

/** How often the screen asks the clicks' clock when click 0 is heard, how far it must move to be logged again, and the longest between two lines. */
private const val CLICK_POLL_MS = 500L
private const val CLICK_MOVED_NANOS = 1_000_000L
private const val CLICK_LOG_NANOS = 5_000_000_000L

/** The paused scenario pauses this long after the clicks start, once the screen is in front. */
private const val SCENARIO_PAUSE_MS = 600L
