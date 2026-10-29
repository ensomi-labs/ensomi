package io.ensomi.android.play

import io.ensomi.runtime.Chart
import io.ensomi.runtime.ChartObject

/** One object of a chart: a tap when [endMs] is null, else a hold. Lanes 0 to 3, times in song milliseconds. */
data class NoteSpec(val lane: Int, val startMs: Int, val endMs: Int? = null)

/**
 * A chart to play: a chart of the player's song, or a sample (the playfield preview and the calibration). A game ends
 * at [durationMs], when the song has played to its end.
 */
data class PlayChart(val title: String, val durationMs: Int, val notes: List<NoteSpec>) {
    /** The chart as the runtime's session takes it. */
    fun toRuntime(): Chart = Chart(
        durationMs = durationMs.toUInt(),
        objects = notes.map { ChartObject(it.lane.toUInt(), it.startMs.toUInt(), it.endMs?.toUInt()) },
    )

    companion object {
        /** A chart the runtime made or read, played over a song of [durationMs] (the longer of the two decides). */
        fun of(title: String, chart: Chart, durationMs: Int): PlayChart = PlayChart(
            title = title,
            durationMs = maxOf(durationMs, chart.durationMs.toInt()),
            notes = chart.objects.map { NoteSpec(it.lane.toInt(), it.startMs.toInt(), it.endMs?.toInt()) },
        )
    }
}
