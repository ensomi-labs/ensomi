package io.ensomi.android.chart

import java.util.Locale
import kotlin.math.roundToInt

/** The section kinds the request can carry; which are offered comes from the model's capabilities. */
enum class SectionKind {
    JUMPSTREAM,
    CHORDSTREAM,
    STREAM,
    JACK,
    CHORDJACK,

    /** A star target for the span instead of a pattern. */
    STAR,
}

/** A kind the model offers, with the note its capabilities give for it. */
data class KindOption(val kind: SectionKind, val experimental: Boolean, val note: String)

/** What the model accepts: the runtime's `ModelCapabilities`, with the kinds whose status is not "not working". */
data class ChartCapabilities(
    val modelName: String,
    val minStar: Double,
    val maxStarExclusive: Double,
    val kinds: List<KindOption>,
) {
    /** The stars a player can choose, in 0.1 steps: `[minStar, maxStarExclusive)`. */
    val selectableStars: ClosedFloatingPointRange<Double>
        get() = minStar..maxOf(minStar, maxStarExclusive - STAR_STEP)

    /** Steps between the ends of [selectableStars], for a Material slider. */
    val starSliderSteps: Int
        get() = ((selectableStars.endInclusive - selectableStars.start) / STAR_STEP).roundToInt() - 1

    companion object {
        const val STAR_STEP = 0.1
    }
}

/** One section as the player edits it: times as typed text, so a half-typed value stays on screen. */
data class SectionDraft(
    val id: Long,
    val start: String = "00:00",
    val end: String = "00:08",
    val kind: SectionKind = SectionKind.JUMPSTREAM,
    val strength: Float = 0.5f,
    val star: Float = 3.5f,
)

/** Why a section, or the set of them, cannot be sent. The screen words each one. */
enum class SectionProblem {
    TIME_FORMAT,
    END_NOT_AFTER_START,
    OUTSIDE_SONG,
    LENGTH,
    STAR_RANGE,
    OVERLAP,
}

/** A problem and the section it belongs to, by position; [index] is null for one about the set. */
data class SectionIssue(val index: Int?, val problem: SectionProblem)

/** The request checks the app makes before Generate is enabled, the same rules the runtime applies. */
object ChartRequestRules {
    /** A section lasts 8 to 32 seconds, whatever its target. */
    val SECTION_LENGTH_MS = 8_000..32_000

    /** "mm:ss" with seconds from 00 to 59, in milliseconds; null when it is not that. */
    fun parseTime(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val (minutes, seconds) = parts
        if (minutes.isEmpty() || seconds.length != 2) return null
        if (!minutes.all { it in '0'..'9' } || !seconds.all { it in '0'..'9' }) return null
        val m = minutes.toIntOrNull() ?: return null
        val s = seconds.toInt()
        if (s >= 60 || m > 9_999) return null
        return (m * 60 + s) * 1_000
    }

    /** "m:ss" for a length. */
    fun formatDuration(milliseconds: Long): String {
        val seconds = milliseconds / 1_000
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    /** A seed is a whole number from 0 to 2^64 − 1; blank means a new chart each time. */
    fun parseSeed(text: String): ULong? = text.trim().toULongOrNull()

    fun isSeedValid(text: String): Boolean = text.isBlank() || parseSeed(text) != null

    fun check(
        sections: List<SectionDraft>,
        songDurationMs: Long,
        stars: ClosedFloatingPointRange<Double>,
    ): List<SectionIssue> {
        val issues = mutableListOf<SectionIssue>()
        val spans = mutableListOf<IntRange>()
        sections.forEachIndexed { index, section ->
            val start = parseTime(section.start)
            val end = parseTime(section.end)
            when {
                start == null || end == null -> issues += SectionIssue(index, SectionProblem.TIME_FORMAT)
                end <= start -> issues += SectionIssue(index, SectionProblem.END_NOT_AFTER_START)
                end > songDurationMs -> issues += SectionIssue(index, SectionProblem.OUTSIDE_SONG)
                end - start !in SECTION_LENGTH_MS -> issues += SectionIssue(index, SectionProblem.LENGTH)
                else -> spans += start until end
            }
            if (section.kind == SectionKind.STAR && section.star.toDouble() !in stars) {
                issues += SectionIssue(index, SectionProblem.STAR_RANGE)
            }
        }
        // Sections may touch (one starts where another ends) but not overlap.
        val sorted = spans.sortedBy { it.first }
        if (sorted.zipWithNext().any { (previous, next) -> previous.last >= next.first }) {
            issues += SectionIssue(null, SectionProblem.OVERLAP)
        }
        return issues
    }
}
