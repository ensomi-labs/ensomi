package io.ensomi.android.play

/** Charts for the prototype, built in code; nothing is generated. */
object SampleCharts {
    private const val BEAT = 400 // 150 BPM
    private const val EIGHTH = BEAT / 2
    private const val SIXTEENTH = BEAT / 4
    private const val BAR = BEAT * 4
    private const val CALIBRATION_CLICKS = 3_600

    /**
     * About 30 seconds at 150 BPM that shows what the playfield draws: a stream with jumps, holds under taps, chords,
     * a sixteenth stream, two holds side by side, a jack and a trill, then a closing chord.
     */
    val PREVIEW: PlayChart = run {
        val notes = mutableListOf<NoteSpec>()
        fun tap(lane: Int, at: Int) { notes += NoteSpec(lane, at) }
        fun hold(lane: Int, from: Int, to: Int) { notes += NoteSpec(lane, from, to) }

        var t = 1_600
        // Eighths across the lanes with a jump on every other beat.
        val walk = intArrayOf(0, 2, 1, 3, 2, 0, 3, 1)
        for (i in 0 until 32) {
            val at = t + i * EIGHTH
            tap(walk[i % walk.size], at)
            if (i % 4 == 0) tap((walk[i % walk.size] + 2) % 4, at)
        }
        t += 4 * BAR
        // A hold in lane 0 under taps in lanes 2 and 3, then a hold in lane 3 under taps in lanes 0 and 1.
        hold(0, t, t + BAR - EIGHTH)
        for (i in 0 until 8) tap(if (i % 2 == 0) 2 else 3, t + i * EIGHTH)
        hold(3, t + BAR, t + 2 * BAR - EIGHTH)
        for (i in 0 until 8) tap(if (i % 2 == 0) 1 else 0, t + BAR + i * EIGHTH)
        t += 2 * BAR
        // Chords on the beat.
        for (i in 0 until 8) {
            val at = t + i * BEAT
            val chord = when {
                i % 4 == 0 -> listOf(0, 1, 3)
                i % 2 == 0 -> listOf(0, 3)
                else -> listOf(1, 2)
            }
            chord.forEach { tap(it, at) }
        }
        t += 2 * BAR
        // A sixteenth stream, then a jumpstream in eighths.
        val stream = intArrayOf(0, 1, 2, 3, 2, 1)
        for (i in 0 until 32) tap(stream[i % stream.size], t + i * SIXTEENTH)
        t += 2 * BAR
        for (i in 0 until 16) {
            val at = t + i * EIGHTH
            val lane = walk[(i + 3) % walk.size]
            tap(lane, at)
            if (i % 2 == 0) tap((lane + 1) % 4, at)
        }
        t += 2 * BAR
        // Lanes 1 and 2 held together while taps alternate outside them.
        hold(1, t, t + BAR)
        hold(2, t, t + BAR)
        for (i in 0 until 8) tap(if (i % 2 == 0) 0 else 3, t + i * EIGHTH)
        t += BAR + BEAT
        // A jack in lane 2 with offbeats in lane 0.
        for (i in 0 until 8) {
            tap(2, t + i * EIGHTH)
            if (i % 2 == 1) tap(0, t + i * EIGHTH + SIXTEENTH)
        }
        t += BAR + BEAT
        // A trill, then the closing chord.
        for (i in 0 until 16) tap(if (i % 2 == 0) 0 else 3, t + i * SIXTEENTH)
        t += BAR
        listOf(0, 1, 2, 3).forEach { tap(it, t) }

        PlayChart("Sample chart", durationMs = t + 1_000, notes = notes.sortedWith(compareBy({ it.startMs }, { it.lane })))
    }

    /**
     * The calibration's picture step: a note in lane 1 on every click of the click track, 500 ms apart from 1 s on,
     * for half an hour of clicks (the track goes on as long as the screen is open).
     */
    val CALIBRATION: PlayChart = PlayChart(
        title = "Calibration",
        durationMs = 1_000 + CALIBRATION_CLICKS * 500 + 1_000,
        notes = (0 until CALIBRATION_CLICKS).map { NoteSpec(lane = 1, startMs = 1_000 + it * 500) },
    )

    /** The first object of a lane that starts before the previous one of that lane has ended, or null. */
    fun firstOverlap(chart: PlayChart): NoteSpec? {
        val lastEnd = IntArray(PlayfieldGeometry.LANES) { Int.MIN_VALUE }
        for (note in chart.notes) {
            if (note.startMs <= lastEnd[note.lane]) return note
            lastEnd[note.lane] = note.endMs ?: note.startMs
        }
        return null
    }
}

/** A lane press or release the preview's autoplay makes, at a song time. */
data class ScriptedInput(val lane: Int, val press: Boolean, val songMs: Double)

/**
 * The preview's player: presses each note a little early or late, and leaves a few alone, so the preview shows every
 * judgement the runtime gives. The offsets are fixed, so every run looks the same.
 */
object Autoplay {
    fun inputs(chart: PlayChart): List<ScriptedInput> {
        val inputs = mutableListOf<ScriptedInput>()
        chart.notes.forEachIndexed { index, note ->
            if (index % 29 == 13) return@forEachIndexed
            val error = (index * 37) % 101 - 45
            val press = note.startMs + error.toDouble()
            val release = if (note.endMs != null) {
                note.endMs + ((index * 53) % 61 - 30).toDouble()
            } else {
                press + 70
            }
            inputs += ScriptedInput(note.lane, press = true, songMs = press)
            inputs += ScriptedInput(note.lane, press = false, songMs = release)
        }
        return inputs.sortedBy { it.songMs }
    }
}
