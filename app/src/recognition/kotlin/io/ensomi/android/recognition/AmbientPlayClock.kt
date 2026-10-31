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
 * Nothing plays, so [pause] only stops the game: the room's music goes on, and [resume] finds it where it has got to, a
 * jump forward that the session plays through as misses. As with the audio clock, song time never goes back: a pause holds
 * the furthest time shown, which a frame drawn for its presentation may have put ahead of the pause, and the clock stays
 * at that until the music has passed it. A start only opens the clock, and `fromMs` means nothing here.
 */
class AmbientPlayClock(private val anchor: LockAnchor) : PlayClock {
    private var started = false

    /** Read without the lock by [isPaused]: a touch asks on the main thread, and must not contend with a frame. */
    @Volatile
    private var paused = false

    /** The time shown while paused, and afterwards the least the clock may show. */
    private var heldMs = Double.NEGATIVE_INFINITY

    /** The latest time asked for while running: nothing shown up to it may move back. */
    private var latestQueryNanos = Long.MIN_VALUE

    /** Where the music is at [nanoTime], paused or not. */
    fun heardMs(nanoTime: Long): Double = anchor.referenceMs + (nanoTime - anchor.captureNanos) / 1e6

    @Synchronized
    override fun start(nanoTime: Long, fromMs: Double) {
        started = true
    }

    @Synchronized
    override fun songTimeMs(nanoTime: Long): Double? {
        if (!started) return null
        if (paused) return heldMs
        if (nanoTime > latestQueryNanos) latestQueryNanos = nanoTime
        return maxOf(heardMs(nanoTime), heldMs)
    }

    override fun nanoTimeAt(songMs: Double): Long = anchor.captureNanos + ((songMs - anchor.referenceMs) * 1e6).toLong()

    @Synchronized
    override fun pause(nanoTime: Long) {
        if (!started || paused) return
        heldMs = maxOf(heardMs(maxOf(nanoTime, latestQueryNanos)), heldMs)
        paused = true
    }

    @Synchronized
    override fun resume(nanoTime: Long) {
        paused = false
    }

    override val isPaused: Boolean
        get() = paused
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
