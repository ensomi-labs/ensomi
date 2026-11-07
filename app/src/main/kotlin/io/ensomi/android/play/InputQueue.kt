package io.ensomi.android.play

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The lane inputs on their way from the main thread to the session. Each frame takes what was queued since the last,
 * gives each input its chart time (the song time of its event plus the audio offset) and hands those stamped at or
 * before the frame's gameplay time to the session in time order (gameplay.md, The frame).
 *
 * Nothing queued is dropped, so the session holds the lanes [LaneSender] sent it, the lanes [LaneHolds] holds:
 * - A paused frame hands on everything queued before the pause, stamped at the pause's time at the latest, and the
 *   controller advances nothing. [LaneSender] sends nothing while paused.
 * - A release sent at the resume for a lane let go during the pause ([LaneInput.atPause]) is stamped with the chart
 *   time the paused frames held at, the latest the session can have heard of, so it is never a late input.
 * - Nothing taken after a pause is stamped before the time it held at: the song had not gone on, and the session's
 *   clock never moves back.
 *
 * [add] may be called from any thread, the rest only on the render thread. [taken] sees each input as a frame takes
 * it, for the lane lights; [deliver] gets each due one with its chart time, for the session.
 */
class InputQueue(
    private val clock: PlayClock,
    private val taken: (LaneInput) -> Unit,
    private val deliver: (chartMs: Double, input: LaneInput) -> Unit,
) {
    private val queue = ConcurrentLinkedQueue<LaneInput>()
    private val pending = ArrayList<Pair<Double, LaneInput>>()

    /** The chart time the latest pause held at, as its paused frames read it; nothing taken later is earlier. */
    private var pausedAtMs = Double.NEGATIVE_INFINITY

    /** A paused frame ran since the last frame that was not: the releases sent at the resume take [pausedAtMs]. */
    private var pausedSince = false

    fun add(input: LaneInput) {
        queue.add(input)
    }

    /**
     * One frame at [gameplayMs], with the game's [audioOffsetMs]. While [paused] the song time is frozen, so whatever
     * is taken is stamped at the pause's time, and everything waiting is handed on: all of it came before the pause.
     * The number of inputs handed on.
     */
    fun frame(gameplayMs: Double, audioOffsetMs: Double, paused: Boolean): Int {
        if (paused) {
            pausedAtMs = gameplayMs
            pausedSince = true
        }
        while (true) {
            val input = queue.poll() ?: break
            val chartMs = when {
                paused -> gameplayMs
                input.atPause && pausedSince -> pausedAtMs
                // A clock with no time for it has nothing to judge it at.
                else -> maxOf((clock.songTimeMs(input.eventNanos) ?: continue) + audioOffsetMs, pausedAtMs)
            }
            taken(input)
            pending += chartMs to input
        }
        if (!paused) pausedSince = false
        if (pending.isEmpty()) return 0
        pending.sortWith(compareBy({ it.first }, { it.second.sequence }))
        // Nothing stamped at the pause's time waits, though a clock that runs again may read a hair before it.
        val dueBy = maxOf(gameplayMs, pausedAtMs)
        var due = 0
        while (due < pending.size && (paused || pending[due].first <= dueBy)) {
            val (chartMs, input) = pending[due++]
            deliver(if (paused) minOf(chartMs, gameplayMs) else chartMs, input)
        }
        pending.subList(0, due).clear()
        return due
    }

    /** Everything queued and waiting is dropped, and the pause forgotten: the game starts again. */
    fun clear() {
        queue.clear()
        pending.clear()
        pausedAtMs = Double.NEGATIVE_INFINITY
        pausedSince = false
    }
}
