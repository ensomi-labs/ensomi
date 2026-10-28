package io.ensomi.android.play

import io.ensomi.runtime.Score
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** A finished game as the results screen shows it. Counts come from the runtime's score. */
@Serializable
data class PlayResult(
    val title: String,
    val detail: String,
    val accuracy: Double,
    val perfect: Int,
    val good: Int,
    val miss: Int,
    val maxCombo: Int,
    /** Hits per 10 ms of error from −150 to +150 ms; early on the left. */
    val histogram: List<Int>,
    val meanOffsetMs: Double?,
    /** The runtime's suggested change to the audio offset: minus the mean error of hit taps and hold heads. */
    val suggestedAdjustmentMs: Int?,
    /** The game was the playfield preview's sample chart, not a chart of the player's song. */
    val sample: Boolean,
) {
    companion object {
        const val BIN_MS = 10
        const val RANGE_MS = 150

        fun of(title: String, detail: String, score: Score, hitOffsetsMs: List<Double>, sample: Boolean): PlayResult {
            val counts = score.tierCounts
            return PlayResult(
                title = title,
                detail = detail,
                accuracy = score.accuracy,
                // The runtime's rule: bigP shows Perfect, p1 to g show Good, m shows Miss.
                perfect = counts.bigP.toInt(),
                good = (counts.p1 + counts.p2 + counts.p3 + counts.g).toInt(),
                miss = counts.m.toInt(),
                maxCombo = score.maxCombo.toInt(),
                histogram = histogram(hitOffsetsMs),
                meanOffsetMs = hitOffsetsMs.takeIf { it.isNotEmpty() }?.average(),
                suggestedAdjustmentMs = score.suggestedAudioOffsetMs?.roundToInt(),
                sample = sample,
            )
        }

        fun histogram(offsetsMs: List<Double>): List<Int> {
            val bins = IntArray(2 * RANGE_MS / BIN_MS)
            for (offset in offsetsMs) {
                val bin = ((offset + RANGE_MS) / BIN_MS).toInt().coerceIn(0, bins.size - 1)
                bins[bin]++
            }
            return bins.toList()
        }
    }
}
