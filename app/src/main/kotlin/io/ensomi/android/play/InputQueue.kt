package io.ensomi.android.play

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The lane inputs on their way from the main thread to the session (latency.md, Formulas). Each frame takes what was
 * queued since the last and stamps each input with its chart time, the song time of its event less the audio latency
 * and its source's input latency:
 *
 * ```
 * inputMs    = songMs(eventTime) − A − I[source]
 * inputClock = gameplayMs − max(I[m] over the live methods, 0 before any), never moving back
 * ```
 *
 * and hands on those stamped at or before the input clock, in chart-time order (then sequence). A method, touch or
 * keyboard, is live once it has pressed in this game; the autoplay is no method and has no input latency. With one live
 * method the input clock is the gameplay time less that method's latency, so its inputs are handed on in the frame that
 * takes them and nothing waits. When a second method first presses with a larger latency, the clock holds instead of
 * moving back, and that press, or one of its method's made within the difference of it, can be late. The controller
 * advances the session to the input clock less the allowance.
 *
 * Nothing queued is dropped, so the session holds the lanes [LaneSender] sent it, the lanes [LaneHolds] holds:
 * - A lane's inputs from touch and keys reach the session in the order they were sent: an input is never stamped before
 *   the one sent before it in its lane, which a release by a source with a larger latency than its press's would be.
 * - A paused frame hands on everything queued before the pause, each at its own chart time but at the pause's time at
 *   the latest, and the controller advances nothing. [LaneSender] sends nothing while paused.
 * - A release sent at the resume for a lane let go during the pause ([LaneInput.atPause]) is stamped with the time the
 *   paused frames held at, with no input latency: the latest the session can have heard of, so it is never late.
 * - Nothing taken after a pause is stamped before the time it held at, latency included: the song had not gone on, and
 *   the session's clock never moves back.
 *
 * The pause's time is the gameplay time it froze at, or the input clock if that is later, which a negative input
 * latency can make it.
 *
 * [add] may be called from any thread, the rest only on the render thread. [taken] sees each input as a frame takes
 * it, for the lane lights; [deliver] gets each due one with its chart time, for the session; [becameLive] hears of each
 * method's first press in the game, with its chart time and how far the input clock is held then (0 for not at all).
 */
class InputQueue(
    private val clock: PlayClock,
    private val taken: (LaneInput) -> Unit,
    private val deliver: (chartMs: Double, input: LaneInput) -> Unit,
    private val becameLive: (source: InputSource, chartMs: Double, holdMs: Double) -> Unit = { _, _, _ -> },
    lanes: Int = PlayfieldGeometry.LANES,
) {
    private val queue = ConcurrentLinkedQueue<LaneInput>()
    private val pending = ArrayList<Pair<Double, LaneInput>>()

    /** The methods that have pressed in this game, by [InputSource.ordinal], and those that first did this frame. */
    private val live = BooleanArray(InputSource.entries.size)
    private val joined = ArrayList<Pair<InputSource, Double>>()

    /** The chart time inputs are handed on up to; it never moves back. The session advances to it less the allowance. */
    var inputClockMs = Double.NEGATIVE_INFINITY
        private set

    /** The chart time of the last touch or key input of each lane: the next one in that lane is never earlier. */
    private val laneMs = DoubleArray(lanes) { Double.NEGATIVE_INFINITY }

    /** Nothing taken is stamped before this: the time the latest pause held at, once play went on after it. */
    private var floorMs = Double.NEGATIVE_INFINITY

    /** The time the latest pause held at, as its paused frames read it. */
    private var pauseMs = Double.NEGATIVE_INFINITY

    /** A paused frame ran since the last frame that was not: the releases sent at the resume take [pauseMs]. */
    private var pausedSince = false

    fun add(input: LaneInput) {
        queue.add(input)
    }

    /**
     * One frame at [gameplayMs], with the game's [timing]. While [paused] the song time is frozen: whatever waits is
     * handed on, all of it made before the pause, at the pause's time at the latest. Otherwise [script] adds the
     * autoplay's inputs due by the time it is given, the later of the gameplay time and the input clock, and the inputs
     * stamped up to the input clock are handed on. The number of inputs handed on.
     */
    fun frame(gameplayMs: Double, timing: PlayTiming, paused: Boolean, script: (untilMs: Double) -> Unit = {}): Int {
        // Play goes on after a pause: from now on nothing is stamped before the time it held at.
        if (!paused && pausedSince) floorMs = maxOf(floorMs, pauseMs)
        take(timing)
        val clockMs = advanceClock(gameplayMs, timing)
        if (paused) {
            pauseMs = maxOf(gameplayMs, clockMs)
            pausedSince = true
        } else {
            script(maxOf(gameplayMs, clockMs))
            take(timing)
            pausedSince = false
        }
        return handOn(paused)
    }

    private fun take(timing: PlayTiming) {
        while (true) {
            val input = queue.poll() ?: break
            // A clock with no time for it has nothing to judge it at.
            val chartMs = stamp(input, timing) ?: continue
            if (input.source.isMethod) {
                if (input.lane in laneMs.indices) laneMs[input.lane] = chartMs
                if (input.press && !live[input.source.ordinal]) {
                    live[input.source.ordinal] = true
                    joined += input.source to chartMs
                }
            }
            taken(input)
            pending += chartMs to input
        }
    }

    private fun stamp(input: LaneInput, timing: PlayTiming): Double? {
        val inLane = laneMs.getOrNull(input.lane)?.takeIf { input.source.isMethod } ?: Double.NEGATIVE_INFINITY
        if (input.atPause) {
            // The time the game paused at, with no input latency: its clock was frozen when the lane was let go. With
            // no paused frame between the pause and the resume, the clock still holds that time at the resume.
            val atMs = if (pausedSince) {
                pauseMs
            } else {
                (clock.eventSongTimeMs(input.eventNanos) ?: return null) - timing.audioLatencyMs
            }
            return maxOf(atMs, floorMs, inLane).also { floorMs = maxOf(floorMs, it) }
        }
        val ownMs = timing.inputMs(clock.eventSongTimeMs(input.eventNanos) ?: return null, input.source)
        return maxOf(ownMs, floorMs, inLane)
    }

    /** The input clock for this frame, from the methods live now; each that joined this frame hears of it. */
    private fun advanceClock(gameplayMs: Double, timing: PlayTiming): Double {
        var most = Double.NEGATIVE_INFINITY
        for (source in InputSource.entries) if (live[source.ordinal]) most = maxOf(most, timing.inputLatencyMs(source))
        val target = gameplayMs - (if (most == Double.NEGATIVE_INFINITY) 0.0 else most)
        inputClockMs = maxOf(inputClockMs, target)
        for ((source, chartMs) in joined) becameLive(source, chartMs, inputClockMs - target)
        joined.clear()
        return inputClockMs
    }

    private fun handOn(paused: Boolean): Int {
        if (pending.isEmpty()) return 0
        pending.sortWith(compareBy({ it.first }, { it.second.sequence }))
        // Nothing stamped at the pause's time waits, though a clock that runs again may read a hair before it.
        val dueBy = maxOf(inputClockMs, floorMs)
        var due = 0
        while (due < pending.size && (paused || pending[due].first <= dueBy)) {
            val (chartMs, input) = pending[due++]
            deliver(if (paused) minOf(chartMs, pauseMs) else chartMs, input)
        }
        pending.subList(0, due).clear()
        // What a paused frame handed on was at the pause's time at the latest, and so is every lane's last input.
        if (paused) for (lane in laneMs.indices) laneMs[lane] = minOf(laneMs[lane], pauseMs)
        return due
    }

    /** Everything queued and waiting is dropped, and the methods, the input clock and the pause forgotten. */
    fun clear() {
        queue.clear()
        pending.clear()
        live.fill(false)
        joined.clear()
        inputClockMs = Double.NEGATIVE_INFINITY
        laneMs.fill(Double.NEGATIVE_INFINITY)
        floorMs = Double.NEGATIVE_INFINITY
        pauseMs = Double.NEGATIVE_INFINITY
        pausedSince = false
    }
}
