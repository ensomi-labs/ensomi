package io.ensomi.android.settings

import android.animation.ValueAnimator
import android.media.AudioDeviceInfo
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.ensomi.android.R
import io.ensomi.android.play.PlayController
import io.ensomi.android.play.PlayTiming
import io.ensomi.android.play.PlayfieldView
import io.ensomi.android.play.SampleCharts
import io.ensomi.android.play.rememberAudioFocus
import io.ensomi.android.play.toRuntime
import io.ensomi.android.ui.ActionButton
import io.ensomi.android.ui.DarkSystemBars
import io.ensomi.android.ui.theme.Brand
import io.ensomi.android.ui.theme.GameTheme
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class CalibrationStep { SOUND, PICTURE }

/**
 * Offset calibration in two steps, in the game's dark presentation, over one click track ([ClickTracks]) that plays
 * through the play output from the moment the screen opens until it closes. Sound: a click every 500 ms, the player
 * taps the pad on it, and the median of the last 16 taps suggests the audio offset for the output the clicks play on.
 * Picture: the clicks go on while the play renderer draws a note onto the line at each click, on the clicks' own
 * clock, and the player moves the visual offset until they cross it on the click, without tapping, so touch latency
 * stays out of the visual offset.
 *
 * The clicks pause when the app leaves the foreground or another app takes audio focus, and go on when it comes back;
 * a tap on the pad while they are paused starts them again. A change of output starts the taps over.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    settings: PlaySettings,
    route: OutputRoute,
    initialStep: CalibrationStep,
    clicks: ClickTracks,
    /** The device the clicks play on: the output whose audio offset is calibrated. */
    onPlayingOn: (AudioDeviceInfo?) -> Unit,
    onChange: ((PlaySettings) -> PlaySettings) -> Unit,
    onBack: () -> Unit,
    /** Screenshots only: taps already made, as errors in milliseconds. */
    initialTapErrors: List<Double> = emptyList(),
) {
    GameTheme {
        DarkSystemBars()
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val playingOn by rememberUpdatedState(onPlayingOn)
        var step by rememberSaveable { mutableStateOf(initialStep) }
        var player by remember { mutableStateOf<ClickPlayer?>(null) }
        var paused by remember { mutableStateOf(false) }
        // The taps of this output; another output starts them over.
        var errors by remember(route.key) { mutableStateOf(initialTapErrors) }

        DisposableEffect(clicks) {
            val opened = clicks.open(context.applicationContext) { playingOn(it) }
            opened.clock.start(System.nanoTime(), 0.0)
            player = opened
            Log.i(AudioClickTracks.TAG, "Clicks started")
            onDispose {
                player = null
                // Releasing the track waits for its writer: not on the main thread.
                Thread({ opened.close() }, "EnsomiClicksClose").start()
            }
        }

        fun pauseClicks(cause: String) {
            val current = player ?: return
            if (!current.clock.isPaused) {
                current.clock.pause(System.nanoTime())
                Log.i(AudioClickTracks.TAG, "Clicks paused: $cause")
            }
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
            val current = player ?: return
            if (!focus.request()) {
                Log.i(AudioClickTracks.TAG, "Clicks stay paused: no audio focus")
                return
            }
            current.clock.resume(System.nanoTime())
            paused = false
            Log.i(AudioClickTracks.TAG, "Clicks go on")
        }

        LaunchedEffect(resumeRequested) {
            if (!resumeRequested) return@LaunchedEffect
            resumeRequested = false
            resumeClicks()
        }

        // Nothing else reads the output during the sound step, so the screen polls the clock; the picture step's
        // render thread polls it too. Every few seconds the log says when click 0 was heard, for the emulator check.
        LaunchedEffect(player) {
            val current = player ?: return@LaunchedEffect
            var lastLog = 0L
            while (isActive) {
                val now = System.nanoTime()
                current.clock.update(now)
                val songMs = current.clock.songTimeMs(now)
                if (songMs != null && songMs > 0 && !current.clock.isPaused && now - lastLog >= LINE_LOG_NANOS) {
                    lastLog = now
                    Log.i(
                        AudioClickTracks.TAG,
                        String.format(Locale.ROOT, "Clicks: song time 1000 ms at %d ns; now %.1f ms", current.clock.nanoTimeAt(1_000.0), songMs),
                    )
                }
                delay(CLOCK_POLL_MS)
            }
        }

        fun tap(eventNanos: Long) {
            val current = player ?: return
            if (paused || current.clock.isPaused) {
                resumeClicks()
                return
            }
            val songMs = current.clock.songTimeMs(eventNanos) ?: return
            val error = CalibrationTaps.errorMs(songMs) ?: return
            errors = errors + error
            Log.i(
                AudioClickTracks.TAG,
                String.format(
                    Locale.ROOT,
                    "Tap: event %d ns, song %.2f ms, error %+.2f ms, taps %d, suggestion %s",
                    eventNanos, songMs, error, errors.size, CalibrationTaps.suggestionMs(errors)?.toString() ?: "none",
                ),
            )
        }

        val window = LocalWindowInfo.current.containerSize
        val wide = window.width > window.height
        val steps: @Composable (Modifier) -> Unit = { modifier ->
            SingleChoiceSegmentedButtonRow(modifier) {
                CalibrationStep.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = step == entry,
                        onClick = { step = entry },
                        shape = SegmentedButtonDefaults.itemShape(index, CalibrationStep.entries.size),
                    ) {
                        Text(stringResource(if (entry == CalibrationStep.SOUND) R.string.calibration_sound else R.string.calibration_picture))
                    }
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
                    // Wider than tall: the steps beside the title, so the step below keeps the height it needs.
                    actions = { if (wide) steps(Modifier.widthIn(max = 320.dp).padding(end = 8.dp)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand.Plum),
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = if (wide) 900.dp else 560.dp).fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!wide) steps(Modifier.fillMaxWidth())
                    when (step) {
                        CalibrationStep.SOUND -> SoundStep(
                            settings = settings,
                            route = route,
                            wide = wide,
                            errorsMs = errors,
                            paused = paused,
                            onTap = ::tap,
                            onStartOver = { errors = emptyList() },
                            onChange = onChange,
                        )
                        CalibrationStep.PICTURE -> PictureStep(settings, player, wide, onChange, onBack)
                    }
                }
            }
        }
    }
}

@Composable
private fun SoundStep(
    settings: PlaySettings,
    route: OutputRoute,
    wide: Boolean,
    errorsMs: List<Double>,
    paused: Boolean,
    onTap: (Long) -> Unit,
    onStartOver: () -> Unit,
    onChange: ((PlaySettings) -> PlaySettings) -> Unit,
) {
    val view = LocalView.current
    val suggestion = CalibrationTaps.suggestionMs(errorsMs)
    // What the game would judge the taps at with the offset in use now: errors near zero once it is right.
    val shownErrors = CalibrationTaps.recent(errorsMs).map { it + settings.audioOffsetMs }
    val info: @Composable () -> Unit = {
        Text(stringResource(R.string.calibration_sound_help), style = MaterialTheme.typography.bodyMedium, color = Brand.MutedText)
        Text(
            stringResource(R.string.calibration_for_output, route.name, PlaySettings.formatOffset(settings.audioOffsetMs)),
            style = MaterialTheme.typography.bodySmall,
            color = Brand.MutedText,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.calibration_suggested), style = MaterialTheme.typography.labelLarge, color = Brand.Lavender)
                Text(
                    suggestion?.let(PlaySettings::formatOffset) ?: stringResource(R.string.calibration_no_suggestion),
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    color = Brand.Text,
                )
            }
            Text(
                stringResource(R.string.calibration_taps, minOf(errorsMs.size, CalibrationTaps.TAPS), CalibrationTaps.TAPS),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = Brand.MutedText,
            )
        }
        TapStrip(shownErrors)
    }
    val padText = stringResource(if (paused) R.string.calibration_paused else R.string.calibration_tap_here)
    val padDescription = stringResource(R.string.calibration_pad_description, padText)
    val pad: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier
                .background(Brand.Panel, RoundedCornerShape(28.dp))
                .border(BorderStroke(1.dp, Brand.PanelBorder), RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                padText,
                style = MaterialTheme.typography.titleLarge,
                color = if (paused) Brand.Text else Brand.MutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp).clearAndSetSemantics { },
            )
            // The pad itself is a View over the panel, so taps keep their event times.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { CalibrationPadView(it) },
                update = {
                    it.onTap = onTap
                    it.contentDescription = padDescription
                },
            )
        }
    }
    val actions: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp),
        ) {
            TextButton(onClick = onStartOver, enabled = errorsMs.isNotEmpty()) {
                Text(stringResource(R.string.calibration_start_over), color = if (errorsMs.isNotEmpty()) Brand.Lavender else Brand.MutedText)
            }
            ActionButton(
                if (suggestion != null) {
                    stringResource(R.string.calibration_apply, PlaySettings.formatOffset(suggestion))
                } else {
                    stringResource(R.string.calibration_apply_none)
                },
                onClick = {
                    val chosen = suggestion ?: return@ActionButton
                    onChange { it.copy(audioOffsetMs = chosen) }
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                },
                icon = R.drawable.ic_check_circle,
                enabled = suggestion != null && settings.audioOffsetMs != suggestion,
                modifier = Modifier.weight(1f),
            )
        }
    }
    // Wider than tall: the pad beside the readings, so it keeps its size in landscape.
    if (wide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                info()
                actions()
            }
            pad(Modifier.weight(1f).fillMaxHeight().padding(bottom = 12.dp))
        }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            info()
            pad(Modifier.fillMaxWidth().weight(1f))
            actions()
        }
    }
}

/** The last taps on a line from 100 ms early to 100 ms late, the newest brightest. */
@Composable
private fun TapStrip(errorsMs: List<Double>) {
    val description = stringResource(R.string.calibration_strip_description)
    val middle = if (errorsMs.isEmpty()) null else CalibrationTaps.median(errorsMs).roundToInt()
    val state = when {
        middle == null -> stringResource(R.string.calibration_strip_empty)
        middle > 0 -> stringResource(R.string.calibration_strip_late, middle)
        middle < 0 -> stringResource(R.string.calibration_strip_early, -middle)
        else -> stringResource(R.string.calibration_strip_on)
    }
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(36.dp).semantics {
                contentDescription = description
                stateDescription = state
            },
        ) {
            val mid = size.width / 2
            drawLine(Brand.PanelBorder, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
            drawLine(Brand.Text.copy(alpha = 0.7f), Offset(mid, 4f), Offset(mid, size.height - 4f), strokeWidth = 2f)
            errorsMs.forEachIndexed { index, error ->
                val x = mid + (error.coerceIn(-100.0, 100.0) / 100.0).toFloat() * mid
                val alpha = 0.35f + 0.65f * (index + 1) / errorsMs.size
                drawCircle(if (error < 0) Brand.Lavender else Brand.Rose, radius = 6f * density, center = Offset(x, size.height / 2), alpha = alpha)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.results_early), style = MaterialTheme.typography.labelSmall, color = Brand.Lavender, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.results_late), style = MaterialTheme.typography.labelSmall, color = Brand.Rose, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PictureStep(
    settings: PlaySettings,
    player: ClickPlayer?,
    wide: Boolean,
    onChange: ((PlaySettings) -> PlaySettings) -> Unit,
    onDone: () -> Unit,
) {
    val reduceMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    // The play renderer, drawing a note on every click on the clicks' own clock; nothing is judged. Beside the
    // controls when the window is wider than tall, so the field keeps its height in landscape.
    val playfield: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.border(BorderStroke(1.dp, Brand.PanelBorder), RoundedCornerShape(20.dp))) {
            if (player != null) {
                key(player) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().padding(1.dp),
                        factory = { context ->
                            PlayfieldView(context).apply {
                                hudHeightDp = 12f
                                controllerFactory = { hud, sequence ->
                                    PlayController(
                                        chart = SampleCharts.CALIBRATION,
                                        judge = settings.judge.toRuntime(),
                                        clock = player.clock,
                                        // The clicks' clock runs for both steps: the playfield neither starts nor closes it.
                                        ownsClock = false,
                                        timing = PlayTiming(0.0, settings.visualOffsetMs.toDouble(), settings.scrollTimeMs),
                                        judging = false,
                                        leadInMs = 0.0,
                                        hud = hud,
                                        nextSequence = sequence,
                                    )
                                }
                            }
                        },
                        update = {
                            it.reduceMotion = reduceMotion
                            it.visualOffsetMs = settings.visualOffsetMs.toDouble()
                            // The player's lean, so notes cross the line as they will in play.
                            it.landscapeFarScale = PlaySettings.farScale(settings.landscapeLean)
                            it.portraitFarScale = PlaySettings.farScale(settings.portraitLean)
                        },
                    )
                }
            }
        }
    }
    val controls: @Composable () -> Unit = {
        val visual = stringResource(R.string.settings_visual_offset)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(visual, style = MaterialTheme.typography.bodyLarge, color = Brand.Text, modifier = Modifier.weight(1f))
            Text(
                PlaySettings.formatOffset(settings.visualOffsetMs),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = Brand.Text,
            )
        }
        Slider(
            value = settings.visualOffsetMs.toFloat(),
            onValueChange = { value -> onChange { it.copy(visualOffsetMs = value.roundToInt()) } },
            valueRange = -100f..100f,
            modifier = Modifier.semantics {
                contentDescription = visual
                stateDescription = PlaySettings.formatOffset(settings.visualOffsetMs)
            },
        )
        ActionButton(
            stringResource(R.string.calibration_done),
            // The slider keeps each value as it moves; Done leaves.
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp),
        )
    }
    if (wide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            playfield(Modifier.weight(1f).fillMaxHeight().padding(bottom = 12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.calibration_picture_help), style = MaterialTheme.typography.bodyMedium, color = Brand.MutedText)
                controls()
            }
        }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.calibration_picture_help), style = MaterialTheme.typography.bodyMedium, color = Brand.MutedText)
            playfield(Modifier.fillMaxWidth().weight(1f))
            controls()
        }
    }
}

/** How often the screen reads the clicks' output while nothing else does. */
private const val CLOCK_POLL_MS = 10L
private const val LINE_LOG_NANOS = 5_000_000_000L
