package io.ensomi.android.recognition

import io.ensomi.android.play.NoteSpec
import io.ensomi.android.play.PlayChart
import io.ensomi.android.play.PlayClock

/**
 * Song time for a game along with music in the room: the final lock's position, carried forward at speed 1. The lock
 * says that [LockAnchor.referenceMs] of the song was heard at the capture time [LockAnchor.captureNanos], so the anchor
 * is that moment, however long ago the lock was read; the time the lock took to compute and the time the game took to
 * start are already behind it.
 *
 * Every time here is on `CLOCK_MONOTONIC`, the play path's one clock ([PlayClock]), which is also what `AudioRecord`'s
 * timestamps are asked for. Like the Swift app's anchored clock it never tracks the music again after the lock.
 *
 * Nothing plays, so [pause] only stops the game: the room's music goes on, and [resume] finds it where it has got to.
 * The time never holds back the player; a start only opens the clock, and `fromMs` means nothing here.
 */
class AmbientPlayClock(private val anchor: LockAnchor) : PlayClock {
    private var started = false
    private var pausedAtMs: Double? = null

    /** Where the music is at [nanoTime], paused or not. */
    fun heardMs(nanoTime: Long): Double = anchor.referenceMs + (nanoTime - anchor.captureNanos) / 1e6

    @Synchronized
    override fun start(nanoTime: Long, fromMs: Double) {
        started = true
        pausedAtMs = null
    }

    @Synchronized
    override fun songTimeMs(nanoTime: Long): Double? {
        if (!started) return null
        return pausedAtMs ?: heardMs(nanoTime)
    }

    override fun nanoTimeAt(songMs: Double): Long = anchor.captureNanos + ((songMs - anchor.referenceMs) * 1e6).toLong()

    @Synchronized
    override fun pause(nanoTime: Long) {
        if (started && pausedAtMs == null) pausedAtMs = heardMs(nanoTime)
    }

    @Synchronized
    override fun resume(nanoTime: Long) {
        pausedAtMs = null
    }

    override val isPaused: Boolean
        @Synchronized get() = pausedAtMs != null
}

/** Where a game joins a song already playing, and the chart from there on. */
class JoinPlan(val joinMs: Double, val chart: PlayChart, val notesSkipped: Int)

/**
 * A join by the rule of the runtime's scheduler (`join_point` in ensomi-generate), which the Swift app plays by: the
 * first time at or after the asked time at which no hold is open, every object before it left out, so that a hold that
 * started before the join has ended by it. The scheduler joins a chart it is generating; a stored chart has no
 * scheduler, so the same rule is applied here, and the session judges the notes that are left from the first.
 */
object JoinPoint {
    /** The first time at or after [fromMs] with no hold open across it. */
    fun at(notes: List<NoteSpec>, fromMs: Double): Double {
        var at = fromMs
        while (true) {
            val open = notes
                .filter { it.endMs != null && it.startMs < at && at < it.endMs }
                .maxOfOrNull { it.endMs!!.toDouble() }
                ?: return at
            at = open
        }
    }

    /** Null when no note is left from [fromMs] on: the song has as good as ended. */
    fun plan(chart: PlayChart, fromMs: Double): JoinPlan? {
        val join = at(chart.notes, fromMs)
        val kept = chart.notes.filter { it.startMs >= join }
        if (kept.isEmpty()) return null
        return JoinPlan(join, chart.copy(notes = kept), chart.notes.size - kept.size)
    }
}
