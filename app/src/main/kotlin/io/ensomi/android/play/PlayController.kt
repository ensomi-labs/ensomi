package io.ensomi.android.play

import android.util.Log
import io.ensomi.android.settings.PlaySettings
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
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow

/** Where a lane input came from: a finger, a key on a keyboard, or the autoplay's script. */
enum class InputSource {
    TOUCH, KEYBOARD, AUTOPLAY;

    /** A way the player presses, with an input latency slot of its own (latency.md); the autoplay is none. */
    val isMethod: Boolean get() = this != AUTOPLAY
}

/**
 * A lane press or release from a touch, a key or the autoplay, stamped on the `System.nanoTime()` clock. [source]
 * chooses the input latency it is judged with and goes into the logs.
 */
data class LaneInput(
    val lane: Int,
    val press: Boolean,
    val eventNanos: Long,
    val sequence: Long,
    val source: InputSource,
    /**
     * A release sent at the resume for a lane whose holders all let go while the game was paused: judged at the song
     * time the game paused at, since its clock was frozen, whatever [eventNanos] says (the resume, for the lane light).
     */
    val atPause: Boolean = false,
)

/**
 * The latencies and scroll time a game keeps from start to end (latency.md, Formulas): its audio latency A and each
 * source's input latency I, in milliseconds, positive for later. The autoplay's script has no input latency.
 *
 * ```
 * gameplayMs = songMs(now) − A
 * renderMs   = songMs(expectedPresentation) − A
 * inputMs    = songMs(eventTime) − A − I[source]
 * ```
 *
 * With every I at 0 these are the formulas of the audio offset, `audioOffset = −A`, without a visual offset. The slots
 * the values came from are for the game's log; null where no slot gave the value.
 */
data class PlayTiming(
    val audioLatencyMs: Double,
    val touchLatencyMs: Double,
    val keyboardLatencyMs: Double,
    val scrollTimeMs: Double,
    val audioSlot: Int? = null,
    val touchSlot: Int? = null,
    val keyboardSlot: Int? = null,
) {
    fun inputLatencyMs(source: InputSource): Double = when (source) {
        InputSource.TOUCH -> touchLatencyMs
        InputSource.KEYBOARD -> keyboardLatencyMs
        InputSource.AUTOPLAY -> 0.0
    }

    /** The chart time judged at song time [songMs]. */
    fun gameplayMs(songMs: Double): Double = songMs - audioLatencyMs

    /** The chart time drawn at the line for the song time [presentSongMs] of a frame's expected presentation. */
    fun renderMs(presentSongMs: Double): Double = presentSongMs - audioLatencyMs

    /** The chart time of an input from [source] whose event was heard at song time [eventSongMs]. */
    fun inputMs(eventSongMs: Double, source: InputSource): Double =
        eventSongMs - audioLatencyMs - inputLatencyMs(source)

    /** For the game's log: "audio slot 1 +142 ms; touch slot 0 +8 ms; keyboard slot 1 +30 ms". Slots are numbers. */
    fun describe(): String = listOf(
        "audio" to (audioSlot to audioLatencyMs),
        "touch" to (touchSlot to touchLatencyMs),
        "keyboard" to (keyboardSlot to keyboardLatencyMs),
    ).joinToString("; ") { (what, value) ->
        val (slot, ms) = value
        val formatted = PlaySettings.formatOffset(ms.roundToInt())
        if (slot != null) "$what slot $slot $formatted" else "$what $formatted (no slot)"
    }

    companion object {
        /** A game of the player's song: the active audio slot, and each method's input slot. */
        fun game(settings: PlaySettings): PlayTiming = PlayTiming(
            audioLatencyMs = settings.audioLatencyMs.toDouble(),
            touchLatencyMs = settings.inputLatency.touchMs.toDouble(),
            keyboardLatencyMs = settings.inputLatency.keyboardMs.toDouble(),
            scrollTimeMs = settings.scrollTimeMs,
            audioSlot = settings.audioLatency.active,
            touchSlot = settings.inputLatency.touch,
            keyboardSlot = settings.inputLatency.keyboard,
        )

        /**
         * Listen & Play: no output plays, so its own trim of the join takes the audio slot's place, with the audio
         * offset's sign (`A = −trim`); the input slots apply, since they belong to the method.
         */
        fun ambient(settings: PlaySettings, trimMs: Double): PlayTiming =
            game(settings).copy(audioLatencyMs = -trimMs, audioSlot = null)

        /** The sample game on a clock without sound: no audio slot; touches and keys keep their input slots. */
        fun sample(settings: PlaySettings): PlayTiming = game(settings).copy(audioLatencyMs = 0.0, audioSlot = null)

        /** The settings' preview, without sound and pressed by the autoplay: no slot applies. */
        fun preview(scrollTimeMs: Double): PlayTiming = PlayTiming(0.0, 0.0, 0.0, scrollTimeMs)
    }
}

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
    /** Song progress is drawn; the calibration's field, whose clicks have no end, draws none. */
    val progress: Boolean,
    /** Paused or not yet started: the panel or the count-in is in front, so the judgement word and combo are not drawn. */
    val paused: Boolean,
)

/**
 * One game on the render thread: the runtime's session judges, its feedback eases lane lights and the judgement word,
 * and this class only moves inputs and time between them. The order each frame follows the Swift app's timing
 * contract, with the input clock of latency.md: deliver the inputs stamped up to the input clock in time order
 * ([InputQueue]), advance the session to the input clock less the input allowance, then read what to draw at the time
 * the frame will be shown.
 *
 * Created and used on the render thread, and closed off the main thread once the playfield is gone; [enqueue] may be
 * called from any thread.
 */
class PlayController(
    private val chart: PlayChart,
    private val judge: Judge,
    val clock: PlayClock,
    timing: PlayTiming,
    /** Debug builds: each frame's times, for the clock check. */
    private val clockLog: ClockLog? = null,
    /** Debug builds: every input the session is given, for the latency check. */
    private val inputLog: InputLog? = null,
    /** Runs after the controller has closed its session: releases the game's sound. */
    private val onClose: () -> Unit = {},
    /** Draws song progress; the calibration's field, on a click track without end, has none. */
    private val progress: Boolean = true,
    /**
     * The calibration's field: a note left alone passes without its Miss, its word or its lane's tint, when no method
     * has pressed for this long before it is decided, so that lining the notes up by sight and sound alone is not shown
     * as failing. The session judges it all the same. Null in a game, where every miss shows.
     */
    private val quietMissesAfterMs: Double? = null,
    /** The preview's autoplay; empty in play. */
    private val script: List<ScriptedInput> = emptyList(),
    /** Starts again at the end instead of finishing: the preview from Settings and the calibration. */
    private val loop: Boolean = false,
    private val leadInMs: Double = LEAD_IN_MS,
    /**
     * Whether the game starts and closes [clock]. The calibration's clicks play from the moment its screen opens, so its
     * playfield only reads their clock.
     */
    private val ownsClock: Boolean = true,
    private val hud: MutableStateFlow<PlayHud>,
    private val nextSequence: () -> Long,
    /**
     * The calibration's readings and log: each press of a method the session took, with what it decided. Called on the
     * render thread.
     */
    private val onPress: ((JudgedPress) -> Unit)? = null,
) : AutoCloseable {
    private val inputs = InputQueue(clock, taken = ::taken, deliver = ::deliver, becameLive = ::becameLive)
    private val visibleWindow = VisibleWindow()
    private var visible = emptyList<VisibleObject>()
    private val laneAsking = LaneAsking()
    private val brightness = Array(PlayfieldGeometry.LANES) { DARK }
    private val brightnessView = brightness.asList()
    private var batches = emptyList<JudgementEventBatch>()
    private var presentation: JudgementPresentation? = null

    /** Events were recorded since the batches and the judgement word were last read. */
    private var feedbackChanged = false

    /** The last frame was paused: the next one that is not logs what the session holds as play goes on. */
    private var wasPaused = false

    private val runtimeChart = chart.toRuntime()
    private var closed = false
    private var session = GameplaySession(runtimeChart, Ruleset(judge))
    private var feedback = GameplayFeedback()

    /**
     * The game's latencies, read once a frame. A game keeps them from start to end; the calibration moves them while the
     * notes fall, and the field takes a new value on its next frame.
     */
    @Volatile
    var timing: PlayTiming = timing

    /** The latencies of the frame being run, for what it hands on. */
    private var frameTiming = timing

    /** The settings' playfield preview moves this while it runs; play keeps the game's value. */
    @Volatile
    var scrollTimeMs: Double = timing.scrollTimeMs

    private var scriptIndex = 0
    private var anyJudgement = false
    private var lastScore: Score? = null
    private var scoreChanged = true
    private var publishedScore: Score? = null
    private val hitOffsets = ArrayList<Double>()
    private val pressesBySource = IntArray(InputSource.entries.size)

    /** Hits timed, the sum of their errors and late inputs, by the source of the input whose call decided them. */
    private val hitsBySource = IntArray(InputSource.entries.size)
    private val hitErrorBySource = DoubleArray(InputSource.entries.size)
    private val lateBySource = IntArray(InputSource.entries.size)

    /** The chart time of the latest press of a method, for [quietMissesAfterMs]. */
    private var lastPressMs = Double.NEGATIVE_INFINITY

    /** The session's clock as the runtime keeps it, the largest watermark or input time given: for late inputs. */
    private var sessionClockMs = Double.NEGATIVE_INFINITY

    /** The time of the frame being run, for the input log. */
    private var frameNanos = 0L

    /** The lanes released at the pause's time since the last frame that was not paused, one bit each. */
    private var resumeReleased = 0

    init {
        Log.i(TAG, "Latency for this game: ${timing.describe()}")
    }

    fun start(nanoTime: Long) {
        if (ownsClock) clock.start(nanoTime, fromMs = -leadInMs)
    }

    /**
     * Queues an input for the next frame. Every one reaches the session, a paused frame's too ([InputQueue]): what is
     * dropped at a pause, [LaneSender] never sends.
     */
    fun enqueue(input: LaneInput) {
        inputs.add(input)
    }

    /** One frame at [nowNanos], drawn for [presentNanos], the frame's expected presentation time. */
    fun frame(nowNanos: Long, presentNanos: Long): PlayFrame? {
        clock.update(nowNanos)
        val songNow = clock.songTimeMs(nowNanos) ?: return null
        val timing = this.timing
        frameTiming = timing
        val gameplayMs = timing.gameplayMs(songNow)
        val paused = clock.isPaused
        frameNanos = nowNanos
        // A paused frame hands on what was queued before the pause, at the pause's time at the latest, so that the
        // session holds the lanes the app holds, and advances nothing: the session's clock stays where it was.
        val delivered = inputs.frame(gameplayMs, timing, paused) { untilMs -> runScript(untilMs, timing) }
        if (paused && !wasPaused) logLanes("Paused", gameplayMs, "delivered $delivered inputs queued before it")
        if (!paused) {
            if (wasPaused) logLanes("Resumed", gameplayMs, "released at the pause's time [${lanes(resumeReleased)}]")
            resumeReleased = 0
            val watermarkMs = inputs.inputClockMs - INPUT_ALLOWANCE_MS
            sessionClockMs = maxOf(sessionClockMs, watermarkMs)
            record(session.advance(watermarkMs), gameplayMs, source = null)
        }
        wasPaused = paused
        // The score changes only when events are decided; each call through the bindings costs time.
        val score = lastScore?.takeIf { !scoreChanged } ?: session.score().also {
            lastScore = it
            scoreChanged = false
        }
        publishHud(score, songNow)

        val presentSong = clock.songTimeMs(presentNanos) ?: songNow
        val renderMs = timing.renderMs(presentSong)
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
            progress = progress,
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

    /**
     * The autoplay's presses and releases due by [untilMs], each stamped at its own chart time: it has no input
     * latency, so its event is when the song is at that chart time plus the audio latency.
     */
    private fun runScript(untilMs: Double, timing: PlayTiming) {
        while (scriptIndex < script.size && script[scriptIndex].songMs <= untilMs) {
            val input = script[scriptIndex++]
            val eventNanos = clock.nanoTimeAt(input.songMs + timing.audioLatencyMs)
            inputs.add(LaneInput(input.lane, input.press, eventNanos, nextSequence(), InputSource.AUTOPLAY))
        }
    }

    /** An input a frame took: the lane light follows it at once, at the time it was made. */
    private fun taken(input: LaneInput) {
        val phase = if (input.press) Phase.PRESS else Phase.RELEASE
        feedback.recordInput(input.lane.toUInt(), phase, input.sequence.toULong(), input.eventNanos / 1e6)
        laneAsking.touched(input.lane)
        inputLog?.taken(input.sequence, frameNanos)
    }

    /** An input due at [chartMs], to the session; [stamp] is how its frame stamped it. */
    private fun deliver(chartMs: Double, input: LaneInput, stamp: InputStamp) {
        if (input.press) pressesBySource[input.source.ordinal]++
        if (input.atPause) resumeReleased = resumeReleased or (1 shl input.lane)
        // The runtime's rule: an input stamped before the session's clock is late, and handled at the clock.
        val late = chartMs < sessionClockMs
        if (late) lateBySource[input.source.ordinal]++
        sessionClockMs = maxOf(sessionClockMs, chartMs)
        inputLog?.delivered(
            input, clock.eventSongTimeMs(input.eventNanos), chartMs, frameNanos, inputs.inputClockMs, late,
        )
        val phase = if (input.press) Phase.PRESS else Phase.RELEASE
        if (input.press && input.source.isMethod) lastPressMs = chartMs
        val events = session.input(InputEvent(input.lane.toUInt(), phase, chartMs))
        record(events, chartMs, input.source)
        visibleWindow.invalidate()
        val listener = onPress ?: return
        if (!input.press || !input.source.isMethod) return
        // A tap is decided by its press: the hit, if any, is among the events this call returned.
        val hit = events.firstOrNull { it.offsetMs != null }
        listener(
            JudgedPress(
                source = input.source,
                eventNanos = input.eventNanos,
                songMs = stamp.songMs,
                ownMs = stamp.ownMs,
                chartMs = chartMs,
                timing = frameTiming,
                offsetMs = hit?.offsetMs,
                tier = hit?.tier?.name?.lowercase(),
                late = late,
            ),
        )
    }

    /** A method's first input in this game, its press or a release that shows it pressed: it is live from then on. */
    private fun becameLive(source: InputSource, chartMs: Double, holdMs: Double) {
        Log.i(
            TAG,
            String.format(
                Locale.ROOT,
                "%s is live from its first input at chart time %.1f ms, input latency %s; %s",
                source.name.lowercase().replaceFirstChar { it.uppercase() },
                chartMs,
                PlaySettings.formatOffset(timing.inputLatencyMs(source).roundToInt()),
                if (holdMs > 0) {
                    String.format(Locale.ROOT, "the input clock holds %.1f ms", holdMs)
                } else {
                    "the input clock does not hold"
                },
            ),
        )
    }

    /**
     * The first frame of a pause, and the first after it: the lanes the session holds then, and the holds held in them,
     * which must be the lanes the app's holders hold (`EnsomiPlayfield` logs those at the resume), with the late inputs
     * so far. Lanes are numbered 1 to 4. Once each, so the runtime's lane states are asked for only then.
     */
    private fun logLanes(what: String, gameplayMs: Double, detail: String) {
        val lanes = session.laneStates()
        Log.i(
            TAG,
            String.format(
                Locale.ROOT,
                "%s at chart time %.1f ms: %s; the session holds [%s], holding a hold in [%s]; late inputs so far %d",
                what,
                gameplayMs,
                detail,
                lanes.filter { it.isPressed }.joinToString { "${it.lane.toInt() + 1}" },
                lanes.filter { it.holdingObject != null }.joinToString { "${it.lane.toInt() + 1}" },
                session.lateInputs().toInt(),
            ),
        )
    }

    /** The lanes of [bits], numbered 1 to 4. */
    private fun lanes(bits: Int): String =
        (0 until PlayfieldGeometry.LANES).filter { bits and (1 shl it) != 0 }.joinToString { "${it + 1}" }

    /** Events a session call at [chartMs] decided; [source] is that of the input it took, null for an advance. */
    private fun record(events: List<JudgementEvent>, chartMs: Double, source: InputSource?) {
        if (events.isEmpty()) return
        scoreChanged = true
        visibleWindow.invalidate()
        val quiet = source == null && quietMissesAfterMs?.let { chartMs - lastPressMs > it } == true
        if (!quiet) {
            anyJudgement = true
            feedbackChanged = true
            feedback.recordJudgementEvents(events, chartMs)
        }
        events.forEach { event ->
            val offset = event.offsetMs ?: return@forEach
            hitOffsets += offset
            if (source != null) {
                hitsBySource[source.ordinal]++
                hitErrorBySource[source.ordinal] += offset
            }
        }
    }

    /** The game ends when every object is decided and the song has played to its end, as in the Swift app. */
    private fun publishHud(score: Score, songNow: Double) {
        val over = !loop && songNow >= chart.durationMs && session.isResolved()
        if (!over && score === publishedScore) return
        publishedScore = score
        val finished = if (over) score else null
        if (finished != null && hud.value.finished == null) Log.i(TAG, gameOver(score))
        val next = PlayHud(
            accuracy = score.accuracy,
            combo = score.combo.toInt(),
            finished = finished,
            hitOffsetsMs = if (finished != null) hitOffsets.toList() else emptyList(),
        )
        if (next != hud.value) hud.value = next
    }

    /**
     * The game's log at game over: the score, the presses by source and the methods that pressed, the hits timed by the
     * source of the input that decided them, with their average error, and the late inputs by source (latency.md,
     * Diagnostics). The runtime's own late inputs stay the total.
     */
    private fun gameOver(score: Score): String {
        fun bySource(each: (InputSource, String) -> String) =
            InputSource.entries.joinToString { each(it, it.name.lowercase()) }
        val methods = InputSource.entries.filter { it.isMethod && pressesBySource[it.ordinal] > 0 }
        val hits = bySource { source, name ->
            val count = hitsBySource[source.ordinal]
            if (count == 0) {
                "$name 0"
            } else {
                val mean = hitErrorBySource[source.ordinal] / count
                String.format(Locale.ROOT, "%s %d at %+.1f ms on average", name, count, mean)
            }
        }
        val presses = bySource { source, name -> "$name ${pressesBySource[source.ordinal]}" }
        return "Game over: accuracy ${score.accuracy}, max combo ${score.maxCombo}, ${hitOffsets.size} hits timed, " +
            "late inputs ${session.lateInputs()}, presses $presses; " +
            "methods that pressed: ${methods.joinToString { it.name.lowercase() }.ifEmpty { "none" }}; " +
            "hits timed by source: $hits; " +
            "late inputs by source: ${bySource { source, name -> "$name ${lateBySource[source.ordinal]}" }}"
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
        pressesBySource.fill(0)
        hitsBySource.fill(0)
        hitErrorBySource.fill(0.0)
        lateBySource.fill(0)
        sessionClockMs = Double.NEGATIVE_INFINITY
        lastPressMs = Double.NEGATIVE_INFINITY
        resumeReleased = 0
        inputs.clear()
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
        inputLog?.let { Log.i(TAG, "Inputs: ${it.summary()}") }
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
 * A press of a method that the session took, for the calibration's readings and log (latency.md, Timing calibration):
 * the song time of its event and the chart time that and the latencies gave it ([ownMs]), the chart time it was handed on
 * at ([chartMs], which a pause or its lane's last input can hold later), the frame's latencies, and the runtime's error
 * and tier of the hit it decided, press time less note time; both null when it hit nothing. [late] by the runtime's rule:
 * handed on behind the session's clock, so judged at the clock.
 */
data class JudgedPress(
    val source: InputSource,
    val eventNanos: Long,
    val songMs: Double?,
    val ownMs: Double,
    val chartMs: Double,
    val timing: PlayTiming,
    val offsetMs: Double?,
    val tier: String?,
    val late: Boolean,
) {
    /**
     * Judged at its own time with the latencies it was stamped with: not late, and not held to a pause or its lane's
     * last input. Only such a press's error says where it landed.
     */
    val exact: Boolean get() = !late && chartMs == ownMs

    /** The audio latency and its method's input latency it was stamped with, A + I, from its own song and chart time. */
    val stampedWithMs: Double? get() = songMs?.let { it - ownMs }
}

/**
 * Debug builds: every input the session was given, for the latency check (latency.md, Checks): its event's song time
 * and its chart time, whose difference is −A − I for its source (save releases at a resume and inputs held to a
 * pause's time or to their lane's last), the frames that took it and handed it on, the input clock then, and whether it
 * was late by the runtime's rule. [csv] goes to `input-log.csv` when the game closes, and [summary] to the log.
 */
class InputLog {
    private val takenAt = HashMap<Long, Long>()
    private val rows = StringBuilder(
        "sequence,source,lane,press,atPause,eventNanos,songMs,chartMs,takenNanos,deliveredNanos,inputClockMs,late\n",
    )

    private class Tally {
        var count = 0
        var waited = 0
        var mostWaitMs = 0.0
        var late = 0
        var shifts = 0
        var leastShiftMs = Double.POSITIVE_INFINITY
        var mostShiftMs = Double.NEGATIVE_INFINITY
    }

    private val tallies = InputSource.entries.associateWith { Tally() }

    @Synchronized
    fun taken(sequence: Long, frameNanos: Long) {
        takenAt[sequence] = frameNanos
    }

    @Synchronized
    fun delivered(
        input: LaneInput,
        songMs: Double?,
        chartMs: Double,
        frameNanos: Long,
        inputClockMs: Double,
        late: Boolean,
    ) {
        val takenNanos = takenAt.remove(input.sequence) ?: frameNanos
        rows.append(input.sequence).append(',').append(input.source.name.lowercase()).append(',').append(input.lane)
            .append(',').append(if (input.press) 1 else 0).append(',').append(if (input.atPause) 1 else 0)
            .append(',').append(input.eventNanos).append(',').append(songMs ?: Double.NaN).append(',').append(chartMs)
            .append(',').append(takenNanos).append(',').append(frameNanos).append(',').append(inputClockMs)
            .append(',').append(if (late) 1 else 0).append('\n')
        val tally = tallies.getValue(input.source)
        tally.count++
        if (frameNanos != takenNanos) {
            tally.waited++
            tally.mostWaitMs = maxOf(tally.mostWaitMs, (frameNanos - takenNanos) / 1e6)
        }
        if (late) tally.late++
        if (songMs != null && !input.atPause) {
            tally.shifts++
            tally.leastShiftMs = minOf(tally.leastShiftMs, chartMs - songMs)
            tally.mostShiftMs = maxOf(tally.mostShiftMs, chartMs - songMs)
        }
    }

    /** By source: the inputs, chart time less song time at least and at most, those that waited a frame, the late. */
    @Synchronized
    fun summary(): String = tallies.entries.joinToString("; ") { (source, t) ->
        val name = source.name.lowercase()
        if (t.count == 0) return@joinToString "$name none"
        val shift = if (t.shifts == 0) {
            "none"
        } else {
            String.format(Locale.ROOT, "%+.1f to %+.1f ms", t.leastShiftMs, t.mostShiftMs)
        }
        String.format(
            Locale.ROOT,
            "%s %d, chart less song time %s, waited %d (most %.1f ms), late %d",
            name, t.count, shift, t.waited, t.mostWaitMs, t.late,
        )
    }

    @Synchronized
    fun csv(): String = rows.toString()
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
