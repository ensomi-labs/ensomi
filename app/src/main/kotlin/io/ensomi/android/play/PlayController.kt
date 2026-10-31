package io.ensomi.android.play

import android.util.Log
import io.ensomi.runtime.GameplayFeedback
import io.ensomi.runtime.GameplaySession
import io.ensomi.runtime.InputEvent
import io.ensomi.runtime.Judge
import io.ensomi.runtime.JudgementEvent
import io.ensomi.runtime.JudgementEventBatch
import io.ensomi.runtime.JudgementPresentation
import io.ensomi.runtime.LaneBrightness
import io.ensomi.runtime.Phase
import io.ensomi.runtime.Ruleset
import io.ensomi.runtime.Score
import io.ensomi.runtime.VisibleObject
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow

/** A lane press or release from a touch or the preview's autoplay, stamped on the `System.nanoTime()` clock. */
data class LaneInput(val lane: Int, val press: Boolean, val eventNanos: Long, val sequence: Long)

/** The offsets and scroll time a game keeps from start to end. */
data class PlayTiming(val audioOffsetMs: Double, val visualOffsetMs: Double, val scrollTimeMs: Double)

/** What the HUD over the playfield shows; it changes only when a value does, not every frame. */
data class PlayHud(
    val accuracy: Double = 1.0,
    val combo: Int = 0,
    /** The final score once the chart is over and every object is decided. */
    val finished: Score? = null,
    /** The hit errors of the game so far, for the results' timing chart. */
    val hitOffsetsMs: List<Double> = emptyList(),
)

/** Everything one frame draws, read from the runtime on the render thread. */
class PlayFrame(
    val gameplayMs: Double,
    val renderMs: Double,
    val scrollTimeMs: Double,
    val durationMs: Double,
    val visible: List<VisibleObject>,
    val brightness: List<LaneBrightness>,
    val batches: List<JudgementEventBatch>,
    val presentation: JudgementPresentation?,
    val combo: Int,
    val anyJudgement: Boolean,
    /** The calibration's picture step judges nothing, so it shows no judgement word, combo or song progress. */
    val judging: Boolean,
    /** Paused or not yet started: the panel or the count-in is in front, so the judgement word and combo are not drawn. */
    val paused: Boolean,
)

/**
 * One game on the render thread: the runtime's session judges, its feedback eases lane lights and the judgement word,
 * and this class only moves inputs and time between them. The order each frame follows the Swift app's timing
 * contract: deliver the inputs stamped up to the frame's gameplay time in time order, advance the session to that time
 * less the input allowance, then read what to draw at the time the frame will be shown.
 *
 * Created and used on the render thread, and closed off the main thread once the playfield is gone; [enqueue] may be
 * called from any thread.
 */
class PlayController(
    private val chart: PlayChart,
    private val judge: Judge,
    val clock: PlayClock,
    private val timing: PlayTiming,
    /**
     * The audio offset of the output the game plays on, asked once when the clock first runs (the output's route is
     * known by then); null keeps [timing]'s.
     */
    private val resolveAudioOffset: (() -> Double)? = null,
    /** Debug builds: each frame's times, for the clock check. */
    private val clockLog: ClockLog? = null,
    /** Runs after the controller has closed its session: releases the game's sound. */
    private val onClose: () -> Unit = {},
    /** Judges input and advances the session; off for the calibration's picture step, which only draws. */
    private val judging: Boolean = true,
    /** The preview's autoplay; empty in play. */
    private val script: List<ScriptedInput> = emptyList(),
    /** Starts again at the end instead of finishing: the preview from Settings and the calibration. */
    private val loop: Boolean = false,
    private val leadInMs: Double = LEAD_IN_MS,
    /**
     * Whether the game starts and closes [clock]. The calibration's clicks keep one clock for both its steps, so its
     * playfield only reads it.
     */
    private val ownsClock: Boolean = true,
    private val hud: MutableStateFlow<PlayHud>,
    private val nextSequence: () -> Long,
) : AutoCloseable {
    private val queue = ConcurrentLinkedQueue<LaneInput>()
    private val visibleWindow = VisibleWindow()
    private var visible = emptyList<VisibleObject>()
    private val laneAsking = LaneAsking()
    private val brightness = Array(PlayfieldGeometry.LANES) { DARK }
    private val brightnessView = brightness.asList()
    private var batches = emptyList<JudgementEventBatch>()
    private var presentation: JudgementPresentation? = null

    /** Events were recorded since the batches and the judgement word were last read. */
    private var feedbackChanged = false
    private val pending = ArrayList<Pair<Double, LaneInput>>()
    private val runtimeChart = chart.toRuntime()
    private var audioOffsetMs = timing.audioOffsetMs
    private var pendingOffset = resolveAudioOffset
    private var closed = false
    private var session = GameplaySession(runtimeChart, Ruleset(judge))
    private var feedback = GameplayFeedback()
    /** The calibration's picture step moves this while the notes fall; play keeps the game's value. */
    @Volatile
    var visualOffsetMs: Double = timing.visualOffsetMs

    /** The settings' playfield preview moves this while it runs; play keeps the game's value. */
    @Volatile
    var scrollTimeMs: Double = timing.scrollTimeMs

    private var scriptIndex = 0
    private var anyJudgement = false
    private var lastScore: Score? = null
    private var scoreChanged = true
    private var publishedScore: Score? = null
    private val hitOffsets = ArrayList<Double>()

    fun start(nanoTime: Long) {
        if (ownsClock) clock.start(nanoTime, fromMs = -leadInMs)
    }

    /** Queues an input for the next frame. Inputs while paused are dropped, as the session judges none then. */
    fun enqueue(input: LaneInput) {
        if (!clock.isPaused) queue.add(input)
    }

    /** One frame at [nowNanos], drawn for [presentNanos], the frame's expected presentation time. */
    fun frame(nowNanos: Long, presentNanos: Long): PlayFrame? {
        clock.update(nowNanos)
        val songNow = clock.songTimeMs(nowNanos) ?: return null
        pendingOffset?.let { resolve ->
            pendingOffset = null
            audioOffsetMs = resolve()
            Log.i(TAG, "Audio offset for this game: $audioOffsetMs ms")
        }
        val gameplayMs = songNow + audioOffsetMs
        if (clock.isPaused) {
            // A paused session judges nothing; inputs stamped before the pause are dropped, as in the Swift app.
            queue.clear()
            pending.clear()
        } else if (judging) {
            runScript(gameplayMs)
            deliverInputs(gameplayMs)
            record(session.advance(gameplayMs - INPUT_ALLOWANCE_MS), gameplayMs)
        }
        // The score changes only when events are decided; each call through the bindings costs time.
        val score = lastScore?.takeIf { !scoreChanged } ?: session.score().also {
            lastScore = it
            scoreChanged = false
        }
        publishHud(score, songNow)

        val presentSong = clock.songTimeMs(presentNanos) ?: songNow
        val renderMs = presentSong + audioOffsetMs + visualOffsetMs
        clockLog?.record(nowNanos, songNow, renderMs)
        val scrollMs = scrollTimeMs
        readFeedback(presentNanos / 1e6, gameplayMs)
        if (visibleWindow.mustAsk(renderMs, scrollMs)) {
            visible = session.visibleObjects(visibleWindow.lowerMs(renderMs), visibleWindow.upperMs(renderMs, scrollMs))
            visibleWindow.asked(renderMs, scrollMs)
        }
        val frame = PlayFrame(
            gameplayMs = gameplayMs,
            renderMs = renderMs,
            scrollTimeMs = scrollMs,
            durationMs = chart.durationMs.toDouble(),
            visible = visible,
            brightness = brightnessView,
            batches = batches,
            presentation = presentation,
            combo = score.combo.toInt(),
            anyJudgement = anyJudgement,
            judging = judging,
            paused = clock.isPaused,
        )
        if (loop && ownsClock && songNow > chart.durationMs + LOOP_GAP_MS) restart(nowNanos)
        return frame
    }

    /**
     * The lane lights and what follows a judgement, asked only while they can change: a dark lane stays dark until its
     * next input, the batches change only with new events (the renderer ages out the ones it has), and with no word
     * showing none comes until the next event.
     */
    private fun readFeedback(uiMs: Double, gameplayMs: Double) {
        for (lane in brightness.indices) {
            if (!laneAsking.mustAsk(lane)) continue
            val light = feedback.laneBrightness(lane.toUInt(), uiMs)
            brightness[lane] = light
            laneAsking.answered(lane, isLit = light.lane != 0.0 || light.receptor != 0.0)
        }
        if (feedbackChanged) batches = feedback.judgementEventBatches()
        if (feedbackChanged || presentation != null) presentation = feedback.judgementPresentation(gameplayMs)
        feedbackChanged = false
    }

    private fun runScript(gameplayMs: Double) {
        while (scriptIndex < script.size && script[scriptIndex].songMs <= gameplayMs) {
            val input = script[scriptIndex++]
            val eventNanos = clock.nanoTimeAt(input.songMs - audioOffsetMs)
            queue.add(LaneInput(input.lane, input.press, eventNanos, nextSequence()))
        }
    }

    private fun deliverInputs(gameplayMs: Double) {
        while (true) {
            val input = queue.poll() ?: break
            val song = clock.songTimeMs(input.eventNanos) ?: continue
            val phase = if (input.press) Phase.PRESS else Phase.RELEASE
            feedback.recordInput(input.lane.toUInt(), phase, input.sequence.toULong(), input.eventNanos / 1e6)
            laneAsking.touched(input.lane)
            pending += (song + audioOffsetMs) to input
        }
        if (pending.isEmpty()) return
        pending.sortWith(compareBy({ it.first }, { it.second.sequence }))
        val due = pending.takeWhile { it.first <= gameplayMs }
        for ((chartMs, input) in due) {
            val phase = if (input.press) Phase.PRESS else Phase.RELEASE
            record(session.input(InputEvent(input.lane.toUInt(), phase, chartMs)), chartMs)
            visibleWindow.invalidate()
        }
        pending.subList(0, due.size).clear()
    }

    private fun record(events: List<JudgementEvent>, chartMs: Double) {
        if (events.isEmpty()) return
        anyJudgement = true
        scoreChanged = true
        feedbackChanged = true
        visibleWindow.invalidate()
        feedback.recordJudgementEvents(events, chartMs)
        events.forEach { event -> event.offsetMs?.let { hitOffsets += it } }
    }

    /** The game ends when every object is decided and the song has played to its end, as in the Swift app. */
    private fun publishHud(score: Score, songNow: Double) {
        val over = !loop && songNow >= chart.durationMs && session.isResolved()
        if (!over && score === publishedScore) return
        publishedScore = score
        val finished = if (over) score else null
        if (finished != null && hud.value.finished == null) {
            Log.i(
                TAG,
                "Game over: accuracy ${score.accuracy}, max combo ${score.maxCombo}, ${hitOffsets.size} hits timed, " +
                    "late inputs ${session.lateInputs()}",
            )
        }
        val next = PlayHud(
            accuracy = score.accuracy,
            combo = score.combo.toInt(),
            finished = finished,
            hitOffsetsMs = if (finished != null) hitOffsets.toList() else emptyList(),
        )
        if (next != hud.value) hud.value = next
    }

    private fun restart(nowNanos: Long) {
        session.close()
        feedback.close()
        session = GameplaySession(runtimeChart, Ruleset(judge))
        feedback = GameplayFeedback()
        scriptIndex = 0
        anyJudgement = false
        lastScore = null
        scoreChanged = true
        publishedScore = null
        hitOffsets.clear()
        queue.clear()
        pending.clear()
        visibleWindow.invalidate()
        laneAsking.reset()
        brightness.fill(DARK)
        batches = emptyList()
        presentation = null
        feedbackChanged = false
        start(nowNanos)
    }

    override fun close() {
        if (closed) return
        closed = true
        clockLog?.let { Log.i(TAG, "Clock: ${it.summary()}") }
        session.close()
        feedback.close()
        if (ownsClock) clock.close()
        onClose()
    }

    companion object {
        /**
         * How long after its timestamp an input may reach the session and still be judged at that time. The session
         * never moves its clock back, so a frame declares inputs delivered only up to its time less this.
         */
        const val INPUT_ALLOWANCE_MS = 100.0

        /** Song time before the first frame of the song when play starts: time to bring the hands to the lanes. */
        const val LEAD_IN_MS = 2_000.0

        /** What a frame asks the session for: from 700 ms before the render time to 250 ms past the top. */
        const val VISIBLE_BEFORE_MS = 700.0
        const val VISIBLE_AFTER_MS = 250.0

        const val LOOP_GAP_MS = 1_500.0

        private val DARK = LaneBrightness(receptor = 0.0, lane = 0.0)
        private const val TAG = "EnsomiPlay"
    }
}

/**
 * Debug builds: the song time of each frame now and at its presentation, to check that neither ever goes back over a
 * whole song. [summary] goes to the log when the game closes, and [csv] holds every frame.
 */
class ClockLog {
    private var nanos = LongArray(1 shl 14)
    private var song = DoubleArray(1 shl 14)
    private var render = DoubleArray(1 shl 14)
    private var size = 0

    @Synchronized
    fun record(nowNanos: Long, songMs: Double, renderMs: Double) {
        if (size == nanos.size) {
            nanos = nanos.copyOf(size * 2)
            song = song.copyOf(size * 2)
            render = render.copyOf(size * 2)
        }
        nanos[size] = nowNanos
        song[size] = songMs
        render[size] = renderMs
        size++
    }

    /** Frames, and the steps back of song time and render time from one frame to the next (paused frames hold). */
    @Synchronized
    fun summary(): String {
        var songBack = 0
        var renderBack = 0
        var worst = 0.0
        for (i in 1 until size) {
            if (song[i] < song[i - 1]) songBack++
            if (render[i] < render[i - 1]) renderBack++
            worst = maxOf(worst, song[i - 1] - song[i], render[i - 1] - render[i])
        }
        val first = if (size > 0) song[0] else 0.0
        val last = if (size > 0) song[size - 1] else 0.0
        return "frames=$size songBack=$songBack renderBack=$renderBack worstBackMs=$worst fromMs=$first toMs=$last"
    }

    @Synchronized
    fun csv(): String = buildString {
        append("nanos,songMs,renderMs\n")
        for (i in 0 until size) append(nanos[i]).append(',').append(song[i]).append(',').append(render[i]).append('\n')
    }
}
