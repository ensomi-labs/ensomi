package io.ensomi.android.play

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowInsets as PlatformWindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ensomi.android.R
import io.ensomi.android.settings.JudgeDifficulty
import io.ensomi.android.settings.PlaySettings
import io.ensomi.android.ui.ActionButton
import io.ensomi.android.ui.theme.Brand
import io.ensomi.android.ui.theme.GameTheme
import io.ensomi.runtime.Judge
import java.util.Locale
import kotlinx.coroutines.delay

private enum class PlayPhase { PLAYING, PAUSED, COUNTDOWN }

/**
 * The play screen: the playfield full screen with the system bars hidden, the HUD over its top edge, and the pause
 * panel. Play pauses on the pause button, on Back, when the app leaves the foreground and when another window takes
 * focus; Resume counts in before the music goes on.
 *
 * Prototype: the chart is [SampleCharts.PREVIEW] on a [FakePlayClock] with the autoplay pressing the lanes; there is
 * no sound. Touches on the playfield are judged too. Plan step 7 puts the player's chart and the audio clock here.
 */
@Composable
fun PlayScreen(
    title: String,
    detail: String,
    settings: PlaySettings,
    loop: Boolean,
    onFinished: (PlayResult) -> Unit,
    onQuit: () -> Unit,
    /** Debug scenarios only: pause after this long, and hold the count-in on its first beat. */
    autoPauseAfterMs: Long? = null,
    holdCountdown: Boolean = false,
) {
    GameTheme {
        ImmersivePortrait()
        val density = LocalDensity.current
        val cutoutTop = WindowInsets.displayCutout.getTop(density).toFloat()
        val gestureBottom = WindowInsets.mandatorySystemGestures.getBottom(density).toFloat()
        val reduceMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
        var phase by rememberSaveable { mutableStateOf(PlayPhase.PLAYING) }
        var restarts by remember { mutableIntStateOf(0) }
        var view by remember { mutableStateOf<PlayfieldView?>(null) }

        fun pause() {
            if (phase == PlayPhase.PAUSED) return
            view?.pause()
            phase = PlayPhase.PAUSED
        }

        BackHandler(enabled = phase != PlayPhase.PAUSED) { pause() }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { pause() }
        // Another window taking focus (the notification shade, a dialog) pauses; the first composition may come
        // before the window has focus, so only a loss of focus counts.
        val focused = LocalWindowInfo.current.isWindowFocused
        var wasFocused by remember { mutableStateOf(false) }
        LaunchedEffect(focused) {
            if (wasFocused && !focused) pause()
            wasFocused = focused
        }
        if (autoPauseAfterMs != null) {
            LaunchedEffect(Unit) {
                delay(autoPauseAfterMs)
                pause()
                if (holdCountdown) phase = PlayPhase.COUNTDOWN
            }
        }

        Box(Modifier.fillMaxSize().background(Brand.Plum)) {
            // A new view on Restart: its controller starts the chart from the lead-in.
            androidx.compose.runtime.key(restarts) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayfieldView(context).apply {
                            keepScreenOn = true
                            controllerFactory = { hud, sequence ->
                                PlayController(
                                    chart = SampleCharts.PREVIEW,
                                    judge = settings.judge.toRuntime(),
                                    clock = FakePlayClock(),
                                    timing = PlayTiming(
                                        audioOffsetMs = settings.audioOffsetMs.toDouble(),
                                        visualOffsetMs = settings.visualOffsetMs.toDouble(),
                                        scrollTimeMs = settings.scrollTimeMs,
                                    ),
                                    script = Autoplay.inputs(SampleCharts.PREVIEW),
                                    loop = loop,
                                    hud = hud,
                                    nextSequence = sequence,
                                )
                            }
                            view = this
                        }
                    },
                    update = {
                        it.insets = cutoutTop to gestureBottom
                        it.reduceMotion = reduceMotion
                        // A view made while the game is paused (after a configuration change) starts paused.
                        if (phase != PlayPhase.PLAYING) it.pause()
                    },
                )
            }
            val currentView = view
            val hud = if (currentView != null) currentView.hud.collectAsStateWithLifecycle().value else PlayHud()
            LaunchedEffect(hud.finished) {
                val score = hud.finished ?: return@LaunchedEffect
                onFinished(PlayResult.of(title, detail, score, hud.hitOffsetsMs, sample = true))
            }

            PlayHudRow(title, detail, hud.accuracy, paused = phase != PlayPhase.PLAYING, onPause = ::pause)

            when (phase) {
                PlayPhase.PLAYING -> Unit
                PlayPhase.PAUSED -> PausePanel(
                    onResume = { phase = PlayPhase.COUNTDOWN },
                    onRestart = {
                        restarts++
                        phase = PlayPhase.PLAYING
                    },
                    onQuit = onQuit,
                )
                PlayPhase.COUNTDOWN -> Countdown(reduceMotion, hold = holdCountdown) {
                    view?.resume()
                    phase = PlayPhase.PLAYING
                }
            }
        }
    }
}

/** Title, chart and accuracy on the left and right of the top edge, and the pause button: no panel of their own. */
@Composable
private fun PlayHudRow(title: String, detail: String, accuracy: Double, paused: Boolean, onPause: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(PlayfieldGeometry.HUD_HEIGHT_DP.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Brand.Text, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Brand.MutedText, maxLines = 1)
        }
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
private fun PausePanel(onResume: () -> Unit, onRestart: () -> Unit, onQuit: () -> Unit) {
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

/** Hides the status and navigation bars while playing (a swipe from an edge shows them for a moment), and keeps the
 * phone in portrait, where the lanes are tallest. Android 16 ignores the orientation request on large screens, where
 * the playfield is centred instead. */
@SuppressLint("SourceLockedOrientationActivity")
@Composable
private fun ImmersivePortrait() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = view.context as? Activity
        val controller = activity?.window?.insetsController
        controller?.hide(PlatformWindowInsets.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val orientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        onDispose {
            controller?.show(PlatformWindowInsets.Type.systemBars())
            if (activity != null && orientation != null) activity.requestedOrientation = orientation
        }
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
