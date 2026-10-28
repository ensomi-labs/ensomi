package io.ensomi.android.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ensomi.android.audio.LibraryPlayer
import io.ensomi.android.audio.PlayerState
import io.ensomi.android.clock.AnchorAudioClock
import io.ensomi.android.clock.AudioAnchor
import io.ensomi.android.input.InputTimeline
import io.ensomi.android.library.LibraryEntry
import io.ensomi.android.settings.EnsomiSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import uniffi.ensomi_runtime_ffi.Chart
import uniffi.ensomi_runtime_ffi.ChartObject
import uniffi.ensomi_runtime_ffi.GameplayFeedback
import uniffi.ensomi_runtime_ffi.GameplaySession
import uniffi.ensomi_runtime_ffi.Geometry
import uniffi.ensomi_runtime_ffi.InputEvent
import uniffi.ensomi_runtime_ffi.Judge
import uniffi.ensomi_runtime_ffi.JudgementPresentation
import uniffi.ensomi_runtime_ffi.Phase
import uniffi.ensomi_runtime_ffi.Ruleset
import uniffi.ensomi_runtime_ffi.Tier
import uniffi.ensomi_runtime_ffi.VisibleObject
import uniffi.ensomi_runtime_ffi.noteGeometry

/**
 * S4, laid out the way the iOS client lays it out: the four lanes end at a judgement line near the bottom,
 * the key tray sits under the line, and the song's progress runs along the top edge. The notes are the
 * core's: `visibleObjects` decides what is on screen and `noteGeometry` where it sits.
 *
 * Judgement happens at the audio time of the press, not the frame time: the press is mapped through the
 * audio clock and handed to the session as its chart time.
 */
@Composable
fun PlayScene(
    entry: LibraryEntry,
    chart: Chart? = null,
    settings: EnsomiSettings,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember { LibraryPlayer(context) }
    val session = remember(entry.uri, chart) {
        GameplaySession(chart ?: demoChart(entry.durationMs ?: DEFAULT_DURATION_MS), Ruleset(judge = Judge.C))
    }

    var nowMs by remember { mutableStateOf(0.0) }
    var score by remember { mutableStateOf(session.score()) }
    var resolved by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var presentation by remember { mutableStateOf<JudgementPresentation?>(null) }
    val feedback = remember { GameplayFeedback() }

    val clock = remember { AnchorAudioClock(AudioAnchor(0.0, SystemClock.elapsedRealtimeNanos())) }
    val timeline = remember {
        InputTimeline(
            clock = clock,
            nowUptimeMs = { SystemClock.uptimeMillis() },
            nowElapsedNanos = { SystemClock.elapsedRealtimeNanos() },
        )
    }

    // The scene's time is the audio clock's, so the notes and the judgements share one timeline. A
    // paused scene neither advances nor judges: the loop stops reading the clock.
    LaunchedEffect(entry.uri) {
        player.play(android.net.Uri.parse(entry.uri))
        while (currentCoroutineContext().isActive) {
            if (!paused) {
                val position = player.positionMs()
                if (position != null) {
                    val host = SystemClock.elapsedRealtimeNanos()
                    clock.onReading(AudioAnchor(position, host))
                    val heard = clock.positionMsAt(SystemClock.elapsedRealtimeNanos())
                    nowMs = heard
                    started = true
                    val events = session.advance(heard)
                    feedback.recordJudgementEvents(events, heard)
                    feedback.advanceJudgementTime(heard)
                    score = session.score()
                    resolved = session.isResolved()
                    presentation = feedback.judgementPresentation(heard)
                }
            }
            if (player.state == PlayerState.ENDED || player.state == PlayerState.FAILED) {
                if (!paused) {
                    session.advance(nowMs + 5_000.0)
                    score = session.score()
                    resolved = session.isResolved()
                }
                break
            }
            delay(16)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.stop() }
    }

    // S4 keeps the screen on and plays in landscape while a run is in progress, and offers its audio to
    // the system: a call or another player takes the focus, and the run pauses instead of talking over it.
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    DisposableEffect(view, activity) {
        view.keepScreenOn = true
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val focusRequest = audio?.let {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener { change ->
                    if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        paused = true
                        player.pause()
                    }
                }
                .build()
        }
        focusRequest?.let { audio?.requestAudioFocus(it) }

        onDispose {
            view.keepScreenOn = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            focusRequest?.let { audio?.abandonAudioFocusRequest(it) }
        }
    }

    BackHandler(enabled = !resolved) {
        paused = true
        player.pause()
    }

    val scrollSpeed by rememberUpdatedState(settings.scrollSpeed)
    val durationMs = (chart?.durationMs ?: entry.durationMs?.toUInt() ?: DEFAULT_DURATION_MS.toUInt()).toLong()

    Box(
        Modifier
            .fillMaxSize()
            .background(EnsomiStyle.ground)
            .pointerInput(entry.uri, paused) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val lane = laneOf(down.position.x, size.width)
                    if (started && !paused) {
                        val pressTime = timeline.chartTimeMs(down.uptimeMillis)
                        session.input(InputEvent(lane.toUInt(), Phase.PRESS, pressTime))
                    }
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (event.type == PointerEventType.Release || !change.pressed) {
                            if (started && !paused) {
                                val releaseTime = timeline.chartTimeMs(change.uptimeMillis)
                                session.input(InputEvent(lane.toUInt(), Phase.RELEASE, releaseTime))
                            }
                            break
                        }
                    }
                    score = session.score()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val laneWidth = size.width / 4f
            val keyArea = KEY_AREA.dp.toPx()
            val lineY = (size.height - keyArea).coerceAtLeast(size.height * 0.4f)
            val scrollTimeMs = 11_485.0 / scrollSpeed
            val tapHeight = 10.dp.toPx()
            val noteInset = 4.dp.toPx()
            val capInset = 10.dp.toPx()

            for (lane in 0 until 4) {
                drawRect(
                    color = if (lane % 2 == 0) LANE_A else LANE_B,
                    topLeft = Offset(lane * laneWidth, 0f),
                    size = Size(laneWidth, lineY),
                )
            }

            for (lane in 0 until 4) {
                val light = feedback.laneBrightness(lane.toUInt(), nowMs).lane.toFloat()
                if (light > 0f) {
                    val lightHeight = lineY * 0.45f
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                EnsomiStyle.hold.copy(alpha = 0f),
                                EnsomiStyle.hold.copy(alpha = (light * 2f).coerceAtMost(1f)),
                            ),
                            startY = lineY - lightHeight,
                            endY = lineY,
                        ),
                        topLeft = Offset(lane * laneWidth, lineY - lightHeight),
                        size = Size(laneWidth, lightHeight),
                    )
                }
            }

            drawRoundRect(
                color = KEY_TRAY,
                topLeft = Offset(0f, lineY - 14.dp.toPx()),
                size = Size(size.width, size.height - lineY + 14.dp.toPx()),
                cornerRadius = CornerRadius(14.dp.toPx()),
            )
            for (lane in 0 until 4) {
                val cap = Offset(lane * laneWidth + 6.dp.toPx(), lineY + capInset)
                val capSize = Size(laneWidth - 12.dp.toPx(), keyArea - capInset * 2)
                drawRoundRect(
                    color = EnsomiStyle.panel,
                    topLeft = cap,
                    size = capSize,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
                val receptorLight = feedback.laneBrightness(lane.toUInt(), nowMs).receptor.toFloat()
                if (receptorLight > 0f) {
                    drawRoundRect(
                        color = EnsomiStyle.hold.copy(alpha = receptorLight),
                        topLeft = cap,
                        size = capSize,
                        cornerRadius = CornerRadius(12.dp.toPx()),
                    )
                }
            }

            val visible: List<VisibleObject> = session.visibleObjects(nowMs - 500.0, nowMs + scrollTimeMs)
            for (note in visible) {
                val laneIndex = note.lane.toInt().coerceIn(0, 3)
                val left = laneIndex * laneWidth + noteInset
                val width = laneWidth - noteInset * 2
                val missed = note.state.name.contains("Miss", ignoreCase = true)
                val tapColour = if (missed) EnsomiStyle.missedNote else EnsomiStyle.tap
                val holdColour = if (missed) EnsomiStyle.missedNote else EnsomiStyle.hold
                when (val geometry = noteGeometry(note, nowMs, scrollTimeMs, lineY.toDouble())) {
                    is Geometry.Tap -> drawRoundRect(
                        color = tapColour.copy(alpha = if (missed) 0.5f else 1f),
                        topLeft = Offset(left, geometry.y.toFloat() - tapHeight / 2),
                        size = Size(width, tapHeight),
                        cornerRadius = CornerRadius(5.dp.toPx()),
                    )

                    is Geometry.Hold -> {
                        val bodyWidth = width * 0.72f
                        val bodyX = left + (width - bodyWidth) / 2
                        drawRoundRect(
                            color = EnsomiStyle.holdBody.copy(alpha = 0.6f),
                            topLeft = Offset(bodyX, geometry.bodyTopY.toFloat()),
                            size = Size(bodyWidth, (geometry.bodyBottomY - geometry.bodyTopY).toFloat()),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                        )
                        geometry.tailY?.let { tail ->
                            drawRoundRect(
                                color = holdColour.copy(alpha = 0.9f),
                                topLeft = Offset(bodyX, tail.toFloat() - 3.dp.toPx()),
                                size = Size(bodyWidth, 6.dp.toPx()),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                            )
                        }
                        drawRoundRect(
                            color = holdColour,
                            topLeft = Offset(left, geometry.headY.toFloat() - tapHeight / 2),
                            size = Size(width, tapHeight),
                            cornerRadius = CornerRadius(5.dp.toPx()),
                        )
                    }
                }
            }

            drawRect(
                color = EnsomiStyle.action,
                topLeft = Offset(0f, lineY - 1.5.dp.toPx()),
                size = Size(size.width, 3.dp.toPx()),
            )

            presentation?.let { shown ->
                val alpha = shown.opacity.toFloat().coerceIn(0f, 1f)
                if (alpha > 0.02f) {
                    val lane = shown.event.lane.toInt().coerceIn(0, 3)
                    val colour = judgementColour(shown.event.tier).copy(alpha = alpha)
                    val laneX = lane * laneWidth
                    if (shown.event.tier == Tier.M) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(colour.copy(alpha = 0f), colour.copy(alpha = 0.34f * alpha)),
                                startY = lineY - 96.dp.toPx(),
                                endY = lineY,
                            ),
                            topLeft = Offset(laneX, lineY - 96.dp.toPx()),
                            size = Size(laneWidth, 96.dp.toPx()),
                        )
                    } else {
                        drawRoundRect(
                            color = colour,
                            topLeft = Offset(laneX + 3.dp.toPx(), lineY - 3.dp.toPx()),
                            size = Size(laneWidth - 6.dp.toPx(), 6.dp.toPx()),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(colour.copy(alpha = 0f), colour.copy(alpha = 0.24f * alpha)),
                                startY = lineY - 110.dp.toPx(),
                                endY = lineY,
                            ),
                            topLeft = Offset(laneX + 15.dp.toPx(), lineY - 110.dp.toPx()),
                            size = Size(laneWidth - 30.dp.toPx(), 110.dp.toPx()),
                        )
                    }
                }
            }

            drawRect(color = EnsomiStyle.panel, topLeft = Offset.Zero, size = Size(size.width, 3.dp.toPx()))
            val progress = (nowMs / durationMs).coerceIn(0.0, 1.0).toFloat()
            drawRect(
                color = EnsomiStyle.action,
                topLeft = Offset.Zero,
                size = Size(size.width * progress, 3.dp.toPx()),
            )
        }

        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopStart) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.displayTitle,
                    color = EnsomiStyle.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    if (chart == null) "Demo chart - generate one to play your song" else "${chart.objects.size} notes",
                    color = EnsomiStyle.textSecondary,
                    fontSize = 12.sp,
                )
            }
            Text("${(score.accuracy * 100).toInt()}%", color = EnsomiStyle.textPrimary, fontSize = 16.sp)
            Text("x${score.combo}", color = EnsomiStyle.textPrimary, fontSize = 16.sp)
            OutlinedButton(
                onClick = {
                    paused = !paused
                    if (paused) player.pause() else player.resume()
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = EnsomiStyle.accent),
            ) { Text(if (paused) "Resume" else "Pause", fontSize = 13.sp) }
            OutlinedButton(
                onClick = { player.stop(); onExit() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = EnsomiStyle.textSecondary),
            ) { Text("Quit", fontSize = 13.sp) }
        }
        }

        presentation?.let { shown ->
            val alpha = shown.opacity.toFloat().coerceIn(0f, 1f)
            if (alpha > 0.05f) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        judgementWord(shown.event.tier),
                        color = judgementColour(shown.event.tier).copy(alpha = alpha),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        if (paused && !resolved) {
            SceneOverlay(title = "Paused", body = "Resume when you're ready.") {
                Button(
                    onClick = { paused = false; player.resume() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EnsomiStyle.action,
                        contentColor = EnsomiStyle.actionInk,
                    ),
                ) { Text("Resume") }
                OutlinedButton(
                    onClick = { player.stop(); onExit() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EnsomiStyle.textSecondary),
                ) { Text("Quit") }
            }
        }

        if (resolved) {
            SceneOverlay(title = "Results", body = "Accuracy ${(score.accuracy * 100).toInt()}%") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Max combo ${score.maxCombo}", color = EnsomiStyle.textPrimary, fontSize = 14.sp)
                    Text("Misses ${score.tierCounts.m}", color = EnsomiStyle.textPrimary, fontSize = 14.sp)
                }
                Button(
                    onClick = { player.stop(); onExit() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EnsomiStyle.action,
                        contentColor = EnsomiStyle.actionInk,
                    ),
                ) { Text("Back to Setup") }
            }
        }

        if (player.state == PlayerState.FAILED) {
            SceneOverlay(title = "Play stopped", body = player.failure ?: "the audio could not be opened") {
                Button(
                    onClick = { player.stop(); onExit() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EnsomiStyle.action,
                        contentColor = EnsomiStyle.actionInk,
                    ),
                ) { Text("Back to Setup") }
            }
        }
    }
}

/** A panel over the field, in the iOS client's colours. */
@Composable
private fun SceneOverlay(title: String, body: String, actions: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(24.dp)
                .background(EnsomiStyle.panel, RoundedCornerShape(12.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = EnsomiStyle.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(body, color = EnsomiStyle.textSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
        }
    }
}

private fun laneOf(x: Float, width: Int): Int = ((x / (width / 4f)).toInt()).coerceIn(0, 3)

/** The judgement in the iOS client's words. */
private fun judgementWord(tier: Tier): String = when (tier) {
    Tier.BIG_P, Tier.P1 -> "PERFECT"
    Tier.P2 -> "GREAT"
    Tier.P3 -> "GOOD"
    Tier.G -> "BAD"
    Tier.M -> "MISS"
}

/** The tier's colour, from the iOS palette's `judgement(_:)`. */
private fun judgementColour(tier: Tier): Color = when (tier) {
    Tier.BIG_P, Tier.P1 -> EnsomiStyle.textPrimary
    Tier.P2, Tier.P3 -> EnsomiStyle.warn
    Tier.G -> EnsomiStyle.textMuted
    Tier.M -> EnsomiStyle.error
}

private const val KEY_AREA = 76
private const val DEFAULT_DURATION_MS = 30_000L

/** The lane bed's two tones and the tray's fill, from the iOS palette. */
private val LANE_A = Color(0xFF1D1627)
private val LANE_B = Color(0xFF191220)
private val KEY_TRAY = Color(0xFF171020)

/**
 * The pattern the scene runs until a setup makes a chart: one tap every half second, rotating through the
 * lanes. It exists so the renderer, the clock and the input path can be checked against core geometry; the
 * subtitle says so on screen.
 */
private fun demoChart(durationMs: Long): Chart {
    val objects = mutableListOf<ChartObject>()
    var time = 1_500u
    var lane = 0u
    while (time < durationMs.toUInt().coerceAtMost(600_000u)) {
        objects += ChartObject(lane = lane, startMs = time, endMs = null)
        time += 500u
        lane = (lane + 1u) % 4u
    }
    return Chart(durationMs = durationMs.toUInt().coerceAtMost(600_000u), objects = objects)
}
