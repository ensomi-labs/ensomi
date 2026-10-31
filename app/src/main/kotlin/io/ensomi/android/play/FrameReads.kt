package io.ensomi.android.play

/*
 * What a frame asks the runtime, as seldom as the answer can change: each call goes through JNA with its allocations,
 * and they were most of a frame's work on a phone. These only decide when to ask; what is drawn is the same as asking
 * every frame.
 */

/**
 * When a frame must ask the session for its visible objects: their states change only with an input or an event, and
 * what enters at the far end is known ahead, so a list holds for [REUSE_MS] of render time. Its range reaches
 * [REUSE_MS] further than a frame needs; what lies past the far end or behind the line is not drawn.
 */
class VisibleWindow {
    private var stale = true
    private var askedAtMs = 0.0
    private var scrollTimeMs = 0.0

    /** An input or an event changed what the session would answer, or the game started again. */
    fun invalidate() {
        stale = true
    }

    /** Whether a frame drawn at [renderMs] needs a new list; render time only goes back when the game restarts. */
    fun mustAsk(renderMs: Double, scrollTimeMs: Double): Boolean =
        stale || scrollTimeMs != this.scrollTimeMs || renderMs < askedAtMs || renderMs > askedAtMs + REUSE_MS

    fun lowerMs(renderMs: Double): Double = renderMs - PlayController.VISIBLE_BEFORE_MS

    fun upperMs(renderMs: Double, scrollTimeMs: Double): Double = renderMs + scrollTimeMs + PlayController.VISIBLE_AFTER_MS + REUSE_MS

    fun asked(renderMs: Double, scrollTimeMs: Double) {
        stale = false
        askedAtMs = renderMs
        this.scrollTimeMs = scrollTimeMs
    }

    companion object {
        const val REUSE_MS = 100.0
    }
}

/**
 * Which lanes' lights a frame must ask for: a dark lane stays dark until its next input, so it costs no call. A lit one
 * is asked every frame, as it fades.
 */
class LaneAsking(lanes: Int = PlayfieldGeometry.LANES) {
    private val lit = BooleanArray(lanes)
    private val touched = BooleanArray(lanes)

    /** The lane had an input since it was last asked for. */
    fun touched(lane: Int) {
        touched[lane] = true
    }

    fun mustAsk(lane: Int): Boolean = lit[lane] || touched[lane]

    fun answered(lane: Int, isLit: Boolean) {
        lit[lane] = isLit
        touched[lane] = false
    }

    fun reset() {
        lit.fill(false)
        touched.fill(false)
    }
}

/** The runtime's `noteYPosition`, as [TapLine] takes it. */
fun interface NoteY {
    fun at(objectMs: Double, renderMs: Double, scrollTimeMs: Double, receptorY: Double): Double
}

/**
 * Where a tap is drawn, without a call per note per frame. The runtime's rule moves every tap at one speed, a straight
 * line in time, so two of its answers fix the line for a scroll time and a fall length, and only a change of those asks
 * again. The rule stays the runtime's: `RuntimeCheck` holds the line to `noteGeometry`.
 */
class TapLine(private val noteY: NoteY) {
    private var scrollTimeMs = Double.NaN
    private var fallLength = Double.NaN
    private var lineY = 0.0
    private var perMs = 0.0

    /** A tap at [timeMs], drawn at [renderMs], on lanes [fallLength] long. */
    fun y(timeMs: Double, renderMs: Double, scrollTimeMs: Double, fallLength: Double): Double {
        if (scrollTimeMs != this.scrollTimeMs || fallLength != this.fallLength) {
            this.scrollTimeMs = scrollTimeMs
            this.fallLength = fallLength
            lineY = noteY.at(0.0, 0.0, scrollTimeMs, fallLength)
            perMs = (noteY.at(scrollTimeMs, 0.0, scrollTimeMs, fallLength) - lineY) / scrollTimeMs
        }
        return lineY + (timeMs - renderMs) * perMs
    }
}
