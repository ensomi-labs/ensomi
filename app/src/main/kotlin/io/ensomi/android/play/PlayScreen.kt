package io.ensomi.android.play

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import io.ensomi.android.audio.AudioPlayClock
import io.ensomi.android.audio.DecodedSong
import io.ensomi.android.audio.GameAudio
import io.ensomi.android.prototype.DebugTools
import io.ensomi.android.settings.OutputRoute
import io.ensomi.android.settings.PlayOutputs
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import android.view.WindowInsets as PlatformWindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextOverflow
import io.ensomi.android.R
import io.ensomi.android.settings.JudgeDifficulty
import io.ensomi.android.settings.PlayOrientation
import io.ensomi.android.settings.PlaySettings
import io.ensomi.android.ui.ActionButton
import io.ensomi.android.ui.theme.Brand
import io.ensomi.android.ui.theme.GameTheme
import io.ensomi.runtime.Judge
import java.util.Locale
import kotlinx.coroutines.delay

private enum class PlayPhase { PLAYING, PAUSED, COUNTDOWN }

/**
 * A game of the player's song: its chart and its sound, decoded for play, and the outputs the sound may play on, each
 * with an audio offset of its own.
 */
class SongGame(
    val chart: PlayChart,
    val audio: DecodedSong,
    val outputs: PlayOutputs,
    /** The settings with an output's audio offset. */
    val settingsFor: (OutputRoute) -> PlaySettings,
    /** Debug launches: the autoplay plays the game. */
    val autoplay: Boolean = false,
    /** Debug builds: every frame's times go to `clock-log.csv`, and the debug broadcasts are taken. */
    val debuggable: Boolean = false,
)

/** The parts of a running game the screen reaches from the main thread; made on the render thread. */
private class RunningGame {
    @Volatile
    var audio: GameAudio? = null

    @Volatile
    var clock: AudioPlayClock? = null

    /** The output the sound plays on, by `AudioDeviceInfo.id`, once the track has said. */
    var deviceId: Int? = null
}

/**
 * The play screen: the playfield full screen with the system bars hidden, the HUD over its top edge, and the pause
 * panel. Play pauses on the pause button, on Back, when the app leaves the foreground, when another window takes
 * focus, when another app takes audio focus, when headphones are unplugged and when the output changes; Resume counts
 * in before the music goes on.
 *
 * With a [game], the player's chart plays on its own sound and the audio clock ([AudioPlayClock]); without one, the
 * chart is [SampleCharts.PREVIEW] on a [FakePlayClock] with the autoplay pressing the lanes and no sound (the
 * settings' preview and the prototype's scenarios). Touches on the playfield are judged in both.
 */
@Composable
fun PlayScreen(
    title: String,
    detail: String,
    settings: PlaySettings,
    loop: Boolean,
    onFinished: (PlayResult) -> Unit,
    onQuit: () -> Unit,
    game: SongGame? = null,
    /** Debug scenarios only: pause after this long, and hold the count-in on its first beat. */
    autoPauseAfterMs: Long? = null,
    holdCountdown: Boolean = false,
) {
    GameTheme {
        Immersive()
        val context = LocalContext.current
        val density = LocalDensity.current
        val cutoutTop = WindowInsets.displayCutout.getTop(density).toFloat()
        val gestureBottom = WindowInsets.mandatorySystemGestures.getBottom(density).toFloat()
        val reduceMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
        val debuggable = remember(context) { DebugTools.enabled(context) }
        var phase by rememberSaveable { mutableStateOf(PlayPhase.PLAYING) }
        var restarts by remember { mutableIntStateOf(0) }
        var view by remember { mutableStateOf<PlayfieldView?>(null) }
        // Where the sound moved during this game, for the pause panel.
        var movedTo by remember { mutableStateOf<String?>(null) }
        val running = remember(restarts) { RunningGame() }

        fun pause(cause: String) {
            if (phase == PlayPhase.PAUSED) return
            Log.i(TAG, "Paused: $cause")
            view?.pause()
            phase = PlayPhase.PAUSED
        }

        val audioFocus = if (game != null) rememberAudioFocus(onLost = { cause -> pause(cause) }) else null
        if (game != null) {
            BecomingNoisy { pause("becoming noisy (ACTION_AUDIO_BECOMING_NOISY)") }
            if (game.debuggable) DebugPlayBroadcasts(running)
        }

        BackHandler(enabled = phase != PlayPhase.PAUSED) { pause("Back") }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { pause("the app left the foreground (ON_PAUSE)") }
        // The lead-in runs only while the window has had focus for a moment, so a system prompt over the first game
        // (Android's "Viewing full screen", which comes a little after the bars hide) holds the lead-in instead of
        // eating it: losing focus before the song has started holds it without the pause panel. Once the song has
        // started, another window taking focus (the notification shade, a dialog, split screen) pauses.
        val focused = LocalWindowInfo.current.isWindowFocused
        var started by remember(restarts) { mutableStateOf(false) }
        // With TalkBack on, a tap on a lane explores instead of touching: before the game starts the screen says so,
        // and the lead-in waits until the player starts anyway.
        val touchExploration = rememberTouchExploration()
        var talkBackNoticeSeen by rememberSaveable { mutableStateOf(false) }
        val talkBackNotice = touchExploration && !talkBackNoticeSeen && !started
        LaunchedEffect(focused, view, talkBackNotice) {
            val current = view ?: return@LaunchedEffect
            if (phase != PlayPhase.PLAYING || talkBackNotice) return@LaunchedEffect
            when {
                focused && !started -> {
                    delay(FOCUS_SETTLE_MS)
                    current.resume()
                    started = true
                }
                !focused && started && current.inLeadIn() -> {
                    current.pause()
                    started = false
                }
                !focused && started -> pause("another window took focus")
            }
        }
        if (autoPauseAfterMs != null) {
            LaunchedEffect(Unit) {
                delay(autoPauseAfterMs)
                pause("the scenario")
                if (holdCountdown) phase = PlayPhase.COUNTDOWN
            }
        }

        /** The track's route, on the main thread: the first is the game's output; a change after it pauses. */
        fun routed(device: AudioDeviceInfo?) {
            val outputs = game?.outputs ?: return
            if (device == null) return
            val previous = running.deviceId
            val id = device.id
            if (previous == id) return
            running.deviceId = id
            outputs.playingOn(device)
            Log.i(TAG, "Sound plays on ${device.type}/${device.productName} (id $id)")
            if (previous == null) return
            running.clock?.outputChanged(System.nanoTime())
            movedTo = outputs.routeOf(device).name
            pause("the output changed")
        }

        fun controller(hud: MutableStateFlow<PlayHud>, sequence: () -> Long): PlayController {
            val timing = PlayTiming(
                audioOffsetMs = settings.audioOffsetMs.toDouble(),
                visualOffsetMs = settings.visualOffsetMs.toDouble(),
                scrollTimeMs = settings.scrollTimeMs,
            )
            if (game == null) {
                return PlayController(
                    chart = SampleCharts.PREVIEW,
                    judge = settings.judge.toRuntime(),
                    clock = FakePlayClock(),
                    timing = timing,
                    script = Autoplay.inputs(SampleCharts.PREVIEW),
                    loop = loop,
                    hud = hud,
                    nextSequence = sequence,
                )
            }
            // The track reports its route on the main thread.
            val audio = GameAudio(context, game.audio, PlayController.LEAD_IN_MS, settings.musicVolume, ::routed)
            val clock = AudioPlayClock(audio, audio.sampleRate, audio.leadInFrames) { Log.i(TAG, it) }
            running.audio = audio
            running.clock = clock
            val clockLog = if (game.debuggable) ClockLog() else null
            return PlayController(
                chart = game.chart,
                judge = settings.judge.toRuntime(),
                clock = clock,
                timing = timing,
                resolveAudioOffset = {
                    game.settingsFor(game.outputs.routeOf(audio.routedDevice)).audioOffsetMs.toDouble()
                },
                clockLog = clockLog,
                onClose = {
                    audio.close()
                    Log.i(TAG, "Clock: ${clock.reads} timestamps read, ${clock.stepsBack} steps back held")
                    clockLog?.let { log -> runCatching { File(context.filesDir, CLOCK_LOG).writeText(log.csv()) } }
                },
                script = if (game.autoplay) Autoplay.inputs(game.chart) else emptyList(),
                hud = hud,
                nextSequence = sequence,
            )
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(Brand.Plum)) {
            // A window too small to play in (split screen, a small free-form window) pauses the game and says so
            // instead of drawing a squashed playfield.
            val tooSmall = maxHeight < MIN_PLAY_HEIGHT || maxWidth < MIN_PLAY_WIDTH
            LaunchedEffect(tooSmall) { if (tooSmall) pause("the window is too small to play in") }
            // A new view on Restart: its controller starts the chart from the lead-in.
            androidx.compose.runtime.key(restarts) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        PlayfieldView(viewContext).apply {
                            keepScreenOn = true
                            if (debuggable) {
                                frameLogFile = File(viewContext.filesDir, FRAME_LOG)
                                touchLogFile = File(viewContext.filesDir, TOUCH_LOG)
                            }
                            // Held at the start of the lead-in until the window has focus (above).
                            this.pause()
                            controllerFactory = ::controller
                            view = this
                        }
                    },
                    update = {
                        it.insets = cutoutTop to gestureBottom
                        it.landscapeFarScale = PlaySettings.farScale(settings.landscapeLean)
                        it.portraitFarScale = PlaySettings.farScale(settings.portraitLean)
                        it.reduceMotion = reduceMotion
                        it.vibrateOnTouch = settings.vibrateOnTouch
                        it.excludeBackGesture = phase == PlayPhase.PLAYING && !tooSmall
                        // A view made while the game is paused (after a configuration change) starts paused.
                        if (phase != PlayPhase.PLAYING) it.pause()
                    },
                )
            }
            val currentView = view
            val hud = if (currentView != null) currentView.hud.collectAsStateWithLifecycle().value else PlayHud()
            LaunchedEffect(hud.finished) {
                val score = hud.finished ?: return@LaunchedEffect
                onFinished(PlayResult.of(title, detail, score, hud.hitOffsetsMs, sample = game == null))
            }

            PlayHudRow(
                title,
                detail,
                hud.accuracy,
                paused = phase != PlayPhase.PLAYING,
                onPause = { pause("the pause button") },
                landscapeFarScale = PlaySettings.farScale(settings.landscapeLean),
            )

            when (phase) {
                PlayPhase.PLAYING -> if (talkBackNotice) TalkBackNotice(onStart = { talkBackNoticeSeen = true }, onBack = onQuit)
                PlayPhase.PAUSED -> PausePanel(
                    movedTo = movedTo,
                    onResume = { phase = PlayPhase.COUNTDOWN },
                    onRestart = {
                        restarts++
                        movedTo = null
                        phase = PlayPhase.PLAYING
                    },
                    onQuit = onQuit,
                )
                PlayPhase.COUNTDOWN -> Countdown(reduceMotion, hold = holdCountdown) {
                    // Audio focus lost for good is asked for again; refused (a call goes on), the game stays paused.
                    if (audioFocus != null && !audioFocus.request()) {
                        phase = PlayPhase.PAUSED
                        Log.i(TAG, "Resume refused: no audio focus")
                    } else {
                        view?.resume()
                        phase = PlayPhase.PLAYING
                    }
                }
            }
            if (tooSmall) WindowTooSmall()
        }
    }
}

/** A game being loaded, or why it cannot be played: in the game's dark presentation and orientation. */
@Composable
fun GameLoading(title: String, failure: Int?, onBack: () -> Unit) {
    GameTheme {
        Box(
            Modifier.fillMaxSize().background(Brand.Plum).windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(24.dp).widthIn(max = 420.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Brand.Text, textAlign = TextAlign.Center)
                if (failure == null) {
                    CircularProgressIndicator(color = Brand.Lavender)
                    Text(
                        stringResource(R.string.play_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.MutedText,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    Text(
                        stringResource(failure),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.Text,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.play_back)) }
                }
            }
        }
    }
}

/** The game's audio focus, asked for when the game opens and again on each resume. */
class GameAudioFocus(private val audioManager: AudioManager, private val request: AudioFocusRequest) {
    fun request(): Boolean = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }
}

/**
 * Holds audio focus while the game (or the calibration) is open: another app taking it (a call, a voice assistant)
 * pauses it. A short sound that only ducks (a notification) does not: Android lowers the game's volume for it by
 * itself. [onGained] hears focus come back after a loss for a while; a game stays paused then, the calibration's
 * clicks go on.
 */
@Composable
fun rememberAudioFocus(onLost: (String) -> Unit, onGained: () -> Unit = {}): GameAudioFocus {
    val context = LocalContext.current
    val lost by rememberUpdatedState(onLost)
    val gained by rememberUpdatedState(onGained)
    val focus = remember(context) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(GameAudio.ATTRIBUTES)
            .setOnAudioFocusChangeListener(
                { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> lost("audio focus lost (AUDIOFOCUS_LOSS)")
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> lost("audio focus lost for a while (AUDIOFOCUS_LOSS_TRANSIENT)")
                        AudioManager.AUDIOFOCUS_GAIN -> gained()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            .build()
        GameAudioFocus(audioManager, request)
    }
    DisposableEffect(focus) {
        if (!focus.request()) lost("audio focus refused")
        onDispose { focus.abandon() }
    }
    return focus
}

/** Headphones unplugged or disconnected: Android says the sound is about to come out of the speaker. */
@Composable
private fun BecomingNoisy(onNoisy: () -> Unit) {
    val context = LocalContext.current
    val noisy by rememberUpdatedState(onNoisy)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) noisy()
            }
        }
        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
}

/**
 * Debug builds: broadcasts that make the emulator, which has one output and no other app playing, do what a phone
 * does, through the same system paths: `adb shell am broadcast -a io.ensomi.android.debug.PLAY --es do <what>` with
 * `change-output` (the track asks for another output, which Android reports as a route change), `take-focus` (a
 * second focus request of the app takes audio focus from the game) or `give-focus`.
 */
@Composable
private fun DebugPlayBroadcasts(running: RunningGame) {
    val context = LocalContext.current
    DisposableEffect(context, running) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        var taken: AudioFocusRequest? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (val what = intent.getStringExtra("do")) {
                    "change-output" -> Log.i(TAG, "Debug: asked for another output: ${running.audio?.preferAnotherOutput()}")
                    "take-focus" -> {
                        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(DEBUG_FOCUS_ATTRIBUTES)
                            .setOnAudioFocusChangeListener { }
                            .build()
                        Log.i(TAG, "Debug: took audio focus: ${audioManager.requestAudioFocus(request)}")
                        taken = request
                    }
                    "give-focus" -> {
                        taken?.let { audioManager.abandonAudioFocusRequest(it) }
                        taken = null
                        Log.i(TAG, "Debug: gave audio focus back")
                    }
                    else -> Log.w(TAG, "Debug: no such play broadcast: $what")
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(DEBUG_PLAY_ACTION), Context.RECEIVER_EXPORTED)
        onDispose {
            context.unregisterReceiver(receiver)
            taken?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }
}

private const val DEBUG_PLAY_ACTION = "io.ensomi.android.debug.PLAY"

private val DEBUG_FOCUS_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * Title, chart and accuracy on the left and right of the top edge, and the pause button: no panel of their own. In
 * portrait they are a row above the lanes; in landscape they sit in the top corners beside the lanes, the title kept
 * to the room left of the lanes' far end.
 */
@Composable
private fun PlayHudRow(
    title: String,
    detail: String,
    accuracy: Double,
    paused: Boolean,
    onPause: () -> Unit,
    landscapeFarScale: Float,
) = BoxWithConstraints {
    val landscape = maxWidth > maxHeight
    // The room left of the lanes where the HUD row ends, in dp: wider the more the lanes lean.
    val beside = remember(maxWidth, maxHeight, landscapeFarScale) {
        val g = PlayfieldGeometry.of(maxWidth.value, maxHeight.value, 1f, 0f, 0f, farScale = landscapeFarScale)
        g.project(g.lineLeft, g.laneY(g.fieldTop + PlayfieldGeometry.HUD_HEIGHT_DP))[0].dp
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(PlayfieldGeometry.HUD_HEIGHT_DP.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(if (landscape) Modifier.widthIn(max = (beside - 32.dp).coerceAtLeast(0.dp)) else Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Brand.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Beside flat landscape lanes the room is narrow: the detail takes a second line rather than being cut.
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = Brand.MutedText,
                maxLines = if (landscape) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (landscape) Spacer(Modifier.weight(1f))
        val accuracyText = formatAccuracy(accuracy)
        val accuracyLabel = stringResource(R.string.play_accuracy)
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$accuracyLabel $accuracyText" },
        ) {
            Text(
                accuracyText,
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                color = Brand.Text,
                fontWeight = FontWeight.Bold,
            )
            Text(accuracyLabel, style = MaterialTheme.typography.labelSmall, color = Brand.MutedText)
        }
        IconButton(onClick = onPause, enabled = !paused) {
            Box(
                Modifier.size(40.dp).border(BorderStroke(1.dp, Brand.PanelBorder), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_pause),
                    contentDescription = stringResource(R.string.play_pause),
                    tint = Brand.Lavender,
                )
            }
        }
    }
}

@Composable
private fun PausePanel(movedTo: String?, onResume: () -> Unit, onRestart: () -> Unit, onQuit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brand.Plum.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Surface(
            // The brand's card shape: round corners, a tighter one at the bottom left.
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 10.dp),
            color = Brand.Panel,
            border = BorderStroke(1.dp, Brand.PanelBorder),
            modifier = Modifier.padding(24.dp).widthIn(max = 360.dp),
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.play_paused),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Brand.Text,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(stringResource(R.string.play_paused_detail), style = MaterialTheme.typography.bodyMedium, color = Brand.MutedText)
                if (movedTo != null) {
                    Text(
                        stringResource(R.string.play_output_moved, movedTo),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.Text,
                        textAlign = TextAlign.Center,
                    )
                }
                ActionButton(
                    stringResource(R.string.play_resume),
                    onClick = onResume,
                    icon = R.drawable.ic_play,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.play_restart))
                    }
                    OutlinedButton(onClick = onQuit, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.play_quit))
                    }
                }
            }
        }
    }
}

/**
 * Before the game starts with TalkBack on: the lanes need direct touch, which touch exploration takes over. A dialog,
 * its own window, so TalkBack moves to it and reads it as it appears, rather than starting on the playfield.
 */
@Composable
private fun TalkBackNotice(onStart: () -> Unit, onBack: () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 10.dp),
            color = Brand.Panel,
            border = BorderStroke(1.dp, Brand.PanelBorder),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.play_talkback_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Brand.Text,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.play_talkback_body), style = MaterialTheme.typography.bodyMedium, color = Brand.MutedText)
                ActionButton(
                    stringResource(R.string.play_talkback_start),
                    onClick = onStart,
                    icon = R.drawable.ic_play,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_back))
                }
            }
        }
    }
}

/** The window is too small for the playfield: the game is paused behind this until the window grows. */
@Composable
private fun WindowTooSmall() {
    Box(Modifier.fillMaxSize().background(Brand.Plum).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.play_window_small_title),
                style = MaterialTheme.typography.titleMedium,
                color = Brand.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Text(
                stringResource(R.string.play_window_small_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.MutedText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Whether TalkBack (or another screen reader) explores by touch, as it changes. */
@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var on by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/** Three beats before the music goes on, so the hands are back on the lanes. With reduced motion the digits only change. */
@Composable
private fun Countdown(reduceMotion: Boolean, hold: Boolean, onDone: () -> Unit) {
    var beat by remember { mutableIntStateOf(3) }
    LaunchedEffect(hold) {
        if (hold) return@LaunchedEffect
        while (beat > 1) {
            delay(COUNT_IN_BEAT_MS)
            beat--
        }
        delay(COUNT_IN_BEAT_MS)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Brand.Plum.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                beat.toString(),
                fontSize = if (reduceMotion) 72.sp else 96.sp,
                fontWeight = FontWeight.Bold,
                color = Brand.Text,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Text(stringResource(R.string.play_get_ready), style = MaterialTheme.typography.titleMedium, color = Brand.MutedText)
        }
    }
}

/** Hides the status and navigation bars while playing (a swipe from an edge shows them for a moment). The
 * orientation is held by [GameOrientation], over the results too. */
@Composable
private fun Immersive() {
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = (view.context as? Activity)?.window?.insetsController
        controller?.hide(PlatformWindowInsets.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(PlatformWindowInsets.Type.systemBars()) }
    }
}

/**
 * Holds the phone in the play orientation while [orientation] is set: through the count-in, play, pause and the
 * results, so the end of a song does not turn the screen under the player's hands. `USER_LANDSCAPE` and
 * `USER_PORTRAIT` let the phone take either side of that orientation, as its sensor and the player's rotation setting
 * allow. When it is cleared the phone returns to its own orientation. Android 16 ignores the request on large screens
 * (at least 600 dp wide), where the playfield takes the window's orientation instead.
 */
@SuppressLint("SourceLockedOrientationActivity")
@Composable
fun GameOrientation(orientation: PlayOrientation?) {
    val activity = LocalView.current.context as? Activity ?: return
    DisposableEffect(activity, orientation) {
        if (orientation == null) return@DisposableEffect onDispose {}
        val previous = activity.requestedOrientation
        activity.requestedOrientation = when (orientation) {
            PlayOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            PlayOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
        onDispose { activity.requestedOrientation = previous }
    }
}

fun formatAccuracy(accuracy: Double): String = String.format(Locale.ROOT, "%.2f%%", accuracy * 100)

fun JudgeDifficulty.toRuntime(): Judge = when (this) {
    JudgeDifficulty.A -> Judge.A
    JudgeDifficulty.B -> Judge.B
    JudgeDifficulty.C -> Judge.C
    JudgeDifficulty.D -> Judge.D
    JudgeDifficulty.E -> Judge.E
}

private const val COUNT_IN_BEAT_MS = 500L

private const val TAG = "EnsomiPlay"

/** Debug builds: the last game's song and render time per frame, in the app's files. */
private const val CLOCK_LOG = "clock-log.csv"

/** Debug builds: the last playfield's vsync, presentation and work per frame (step 9's frame log). */
private const val FRAME_LOG = "frame-log.csv"

/** Debug builds: each lane press and release of the last game, when it happened and when it was handled. */
private const val TOUCH_LOG = "touch-log.csv"

/** Smaller than this, the playfield would be squashed: the screen says so instead (design.md, short windows). */
private val MIN_PLAY_HEIGHT = 280.dp
private val MIN_PLAY_WIDTH = 280.dp

/** How long the window keeps focus before the lead-in runs. */
private const val FOCUS_SETTLE_MS = 400L
