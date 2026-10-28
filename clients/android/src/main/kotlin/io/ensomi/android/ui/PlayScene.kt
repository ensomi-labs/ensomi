package io.ensomi.android.ui

import android.os.SystemClock
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
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
import uniffi.ensomi_runtime_ffi.GameplaySession
import uniffi.ensomi_runtime_ffi.GameplayFeedback
import uniffi.ensomi_runtime_ffi.Geometry
import uniffi.ensomi_runtime_ffi.InputEvent
import uniffi.ensomi_runtime_ffi.JudgementPresentation
import uniffi.ensomi_runtime_ffi.Judge
import uniffi.ensomi_runtime_ffi.Phase
import uniffi.ensomi_runtime_ffi.Ruleset
import uniffi.ensomi_runtime_ffi.Score
import uniffi.ensomi_runtime_ffi.Tier
import uniffi.ensomi_runtime_ffi.VisibleObject
import uniffi.ensomi_runtime_ffi.noteGeometry

/**
 * S4 Play scene. The notes are the core's: `visibleObjects` decides what is on screen and
 * `noteGeometry` where it sits, so the four-lane layout is the same geometry every client draws. The
 * chart is a demo pattern until the generator is linked, and the page says so.
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
    // paused scene neither advances nor judges: the loop stops reading the clock, which is what makes
    // the pause real rather than a visual state.
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
        val previousOrientation = activity?.requestedOrientation
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
            if (previousOrientation != null) activity?.requestedOrientation = previousOrientation
            focusRequest?.let { audio?.abandonAudioFocusRequest(it) }
        }
    }

    // A back gesture pauses the run rather than leaving it: the stage is not lost by touching Back.
    BackHandler(enabled = !resolved) {
        paused = true
        player.pause()
    }

    val scrollSpeed by rememberUpdatedState(settings.scrollSpeed)
    val laneColors = listOf(
        Color(0xFF7C6BFF),
        Color(0xFF52C7D9),
        Color(0xFFE0A458),
        Color(0xFFD96A8B),
    )

    Box(Modifier.fillMaxSize().background(Color(0xFF121016)).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Text(
                        if (chart == null) "demo chart, ${formatLength(entry.durationMs)} - the generator is not linked"
                        else "generated chart, ${chart.objects.size} notes",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA8A2B8),
                    )
                }
                Text("${(score.accuracy * 100).toInt()}%", color = Color.White)
                Text("x${score.combo}", color = Color.White)
                OutlinedButton(
                    onClick = {
                        paused = !paused
                        if (paused) player.pause() else player.resume()
                    },
                ) { Text(if (paused) "Resume" else "Pause") }
                OutlinedButton(onClick = { player.stop(); onExit() }) { Text("Quit") }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
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
                val laneWidth = size.width / 4f
                val receptorY = size.height - 120.dp.toPx()
                val scrollTimeMs = 11_485.0 / scrollSpeed
                val noteHeight = 18.dp.toPx()

                // The lane bed and its receptor line.
                for (lane in 0 until 4) {
                    drawRect(
                        color = Color(0xFF191622),
                        topLeft = Offset(lane * laneWidth, 0f),
                        size = Size(laneWidth, size.height),
                    )
                    drawRect(
                        color = laneColors[lane].copy(alpha = 0.18f),
                        topLeft = Offset(lane * laneWidth, receptorY),
                        size = Size(laneWidth, 2.dp.toPx()),
                    )
                }

                val visible: List<VisibleObject> = session.visibleObjects(nowMs - 500.0, nowMs + scrollTimeMs)
                for (note in visible) {
                    val laneIndex = note.lane.toInt().coerceIn(0, 3)
                    val left = laneIndex * laneWidth + laneWidth * 0.14f
                    val width = laneWidth * 0.72f
                    when (val geometry = noteGeometry(note, nowMs, scrollTimeMs, receptorY.toDouble())) {
                        is Geometry.Tap -> drawRoundRect(
                            color = laneColors[laneIndex],
                            topLeft = Offset(left, geometry.y.toFloat() - noteHeight / 2),
                            size = Size(width, noteHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(noteHeight / 2),
                        )

                        is Geometry.Hold -> {
                            drawRect(
                                color = laneColors[laneIndex].copy(alpha = 0.55f),
                                topLeft = Offset(left + width * 0.3f, geometry.bodyTopY.toFloat()),
                                size = Size(width * 0.4f, (geometry.bodyBottomY - geometry.bodyTopY).toFloat()),
                            )
                            drawRoundRect(
                                color = laneColors[laneIndex],
                                topLeft = Offset(left, geometry.headY.toFloat() - noteHeight / 2),
                                size = Size(width, noteHeight),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(noteHeight / 2),
                            )
                            geometry.tailY?.let { tail ->
                                drawRoundRect(
                                    color = laneColors[laneIndex].copy(alpha = 0.8f),
                                    topLeft = Offset(left, tail.toFloat() - noteHeight / 2),
                                    size = Size(width, noteHeight),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(noteHeight / 2),
                                )
                            }
                        }
                    }
                }

                // The core's own presentation of the last judgement: its lane, its tier colour and the
                // opacity it has left. The scene draws it; it does not decide how a hit looked.
                presentation?.let { shown ->
                    val alpha = shown.opacity.toFloat().coerceIn(0f, 1f)
                    if (alpha > 0.02f) {
                        val laneIndex = shown.event.lane.toInt().coerceIn(0, 3)
                        val color = tierColor(shown.event.tier).copy(alpha = alpha)
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(
                                laneIndex * laneWidth + laneWidth * 0.2f,
                                receptorY - 34.dp.toPx() + shown.verticalOffset.toFloat(),
                            ),
                            size = Size(laneWidth * 0.6f, 8.dp.toPx()),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                        )
                        drawRect(
                            color = color.copy(alpha = alpha * 0.35f),
                            topLeft = Offset(laneIndex * laneWidth, receptorY - 10.dp.toPx()),
                            size = Size(laneWidth, 20.dp.toPx()),
                        )
                    }
                }
            }
        }

        if (paused && !resolved) {
            Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paused", style = MaterialTheme.typography.headlineSmall)
                    Text("Resume when you're ready.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { paused = false; player.resume() }) { Text("Resume") }
                        OutlinedButton(onClick = { player.stop(); onExit() }) { Text("Quit") }
                    }
                }
            }
        }

        if (resolved) {
            Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Results", style = MaterialTheme.typography.headlineSmall)
                    Text("Accuracy ${(score.accuracy * 100).toInt()}%")
                    Text("Max combo ${score.maxCombo}")
                    Text("Misses ${score.tierCounts.m}")
                    Button(onClick = { player.stop(); onExit() }) { Text("Back to Setup") }
                }
            }
        } else if (player.state == PlayerState.FAILED) {
            Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Play stopped", style = MaterialTheme.typography.headlineSmall)
                    Text(player.failure ?: "the audio could not be opened")
                    Button(onClick = { player.stop(); onExit() }) { Text("Back to Setup") }
                }
            }
        }
    }
}

private fun laneOf(x: Float, width: Int): Int =
    ((x / (width / 4f)).toInt()).coerceIn(0, 3)

private const val DEFAULT_DURATION_MS = 30_000L

/**
 * The pattern the scene runs until generation is linked: one tap every half second, rotating through the
 * lanes. It exists so the renderer, the clock and the input path can be built and checked against real
 * core geometry; it is not a generated chart.
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

/** A judgement tier as the scene colours it. */
private fun tierColor(tier: Tier): Color = when (tier) {
    Tier.BIG_P -> Color(0xFF7C6BFF)
    Tier.P1 -> Color(0xFF52C7D9)
    Tier.P2 -> Color(0xFF8FD16A)
    Tier.P3 -> Color(0xFFE0A458)
    Tier.G -> Color(0xFFB0A8C0)
    Tier.M -> Color(0xFFD96A8B)
}
