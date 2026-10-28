package io.ensomi.android.play

import io.ensomi.runtime.Chart
import io.ensomi.runtime.ChartObject
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
 * Created, used and closed on the render thread; [enqueue] may be called from any thread.
 */
class PlayController(
    private val chart: SampleChart,
    private val judge: Judge,
    val clock: PlayClock,
    private val timing: PlayTiming,
    /** Judges input and advances the session; off for the calibration's picture step, which only draws. */
    private val judging: Boolean = true,
    /** The preview's autoplay; empty in play. */
    private val script: List<ScriptedInput> = emptyList(),
    /** Starts again at the end instead of finishing: the preview from Settings and the calibration. */
    private val loop: Boolean = false,
    private val leadInMs: Double = LEAD_IN_MS,
    private val hud: MutableStateFlow<PlayHud>,
    private val nextSequence: () -> Long,
) : AutoCloseable {
    private val queue = ConcurrentLinkedQueue<LaneInput>()
    private val pending = ArrayList<Pair<Double, LaneInput>>()
    private val runtimeChart = Chart(
        durationMs = chart.durationMs.toUInt(),
        objects = chart.notes.map { ChartObject(it.lane.toUInt(), it.startMs.toUInt(), it.endMs?.toUInt()) },
    )
    private var session = GameplaySession(runtimeChart, Ruleset(judge))
    private var feedback = GameplayFeedback()
    /** The calibration's picture step moves this while the notes fall; play keeps the game's value. */
    @Volatile
    var visualOffsetMs: Double = timing.visualOffsetMs

    private var scriptIndex = 0
    private var anyJudgement = false
    private var lastScore: Score? = null
    private var scoreChanged = true
    private val hitOffsets = ArrayList<Double>()

    fun start(nanoTime: Long) {
        (clock as? FakePlayClock)?.start(nanoTime, fromMs = -leadInMs)
    }

    /** Queues an input for the next frame. Inputs while paused are dropped, as the session judges none then. */
    fun enqueue(input: LaneInput) {
        if (!clock.isPaused) queue.add(input)
    }

    /** One frame at [nowNanos], drawn for [presentNanos], the frame's expected presentation time. */
    fun frame(nowNanos: Long, presentNanos: Long): PlayFrame? {
        val songNow = clock.songTimeMs(nowNanos) ?: return null
        val gameplayMs = songNow + timing.audioOffsetMs
        if (clock.isPaused) {
            // A paused session judges nothing; inputs stamped before the pause are dropped, as in the Swift app.
            queue.clear()
            pending.clear()
        } else if (judging) {
            runScript(gameplayMs)
            deliverInputs(gameplayMs)
            record(session.advance(gameplayMs - INPUT_ALLOWANCE_MS), gameplayMs)
        }
        feedback.advanceJudgementTime(gameplayMs)
        // The score changes only when events are decided; each call through the bindings costs time.
        val score = lastScore?.takeIf { !scoreChanged } ?: session.score().also {
            lastScore = it
            scoreChanged = false
        }
        publishHud(score, songNow)

        val presentSong = clock.songTimeMs(presentNanos) ?: songNow
        val renderMs = presentSong + timing.audioOffsetMs + visualOffsetMs
        val uiMs = presentNanos / 1e6
        val frame = PlayFrame(
            gameplayMs = gameplayMs,
            renderMs = renderMs,
            scrollTimeMs = timing.scrollTimeMs,
            durationMs = chart.durationMs.toDouble(),
            visible = session.visibleObjects(renderMs - VISIBLE_BEFORE_MS, renderMs + timing.scrollTimeMs + VISIBLE_AFTER_MS),
            brightness = List(PlayfieldGeometry.LANES) { feedback.laneBrightness(it.toUInt(), uiMs) },
            batches = feedback.judgementEventBatches(),
            presentation = feedback.judgementPresentation(gameplayMs),
            combo = score.combo.toInt(),
            anyJudgement = anyJudgement,
            judging = judging,
            paused = clock.isPaused,
        )
        if (loop && songNow > chart.durationMs + LOOP_GAP_MS) restart(nowNanos)
        return frame
    }

    private fun runScript(gameplayMs: Double) {
        while (scriptIndex < script.size && script[scriptIndex].songMs <= gameplayMs) {
            val input = script[scriptIndex++]
            val eventNanos = clock.nanoTimeAt(input.songMs - timing.audioOffsetMs)
            queue.add(LaneInput(input.lane, input.press, eventNanos, nextSequence()))
        }
    }

    private fun deliverInputs(gameplayMs: Double) {
        while (true) {
            val input = queue.poll() ?: break
            val song = clock.songTimeMs(input.eventNanos) ?: continue
            val phase = if (input.press) Phase.PRESS else Phase.RELEASE
            feedback.recordInput(input.lane.toUInt(), phase, input.sequence.toULong(), input.eventNanos / 1e6)
            pending += (song + timing.audioOffsetMs) to input
        }
        if (pending.isEmpty()) return
        pending.sortWith(compareBy({ it.first }, { it.second.sequence }))
        val due = pending.takeWhile { it.first <= gameplayMs }
        for ((chartMs, input) in due) {
            val phase = if (input.press) Phase.PRESS else Phase.RELEASE
            record(session.input(InputEvent(input.lane.toUInt(), phase, chartMs)), chartMs)
        }
        pending.subList(0, due.size).clear()
    }

    private fun record(events: List<JudgementEvent>, chartMs: Double) {
        if (events.isEmpty()) return
        anyJudgement = true
        scoreChanged = true
        feedback.recordJudgementEvents(events, chartMs)
        events.forEach { event -> event.offsetMs?.let { hitOffsets += it } }
    }

    /** The game ends when every object is decided and the song has played to its end, as in the Swift app. */
    private fun publishHud(score: Score, songNow: Double) {
        val over = !loop && songNow >= chart.durationMs && session.isResolved()
        val finished = if (over) score else null
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
        hitOffsets.clear()
        queue.clear()
        pending.clear()
        start(nowNanos)
    }

    override fun close() {
        session.close()
        feedback.close()
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
    }
}
