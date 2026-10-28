package io.ensomi.android.play

import kotlin.math.max
import kotlin.math.min

/**
 * Where the playfield sits on the surface, in pixels, and how its lanes lean.
 *
 * Four touching lanes run from their far end at [fieldTop] down to the judgement line at [lineY], where they are
 * [lineWidth] wide from [lineLeft]; under the line is the key tray, to [trayBottom]. A surface wider than tall gets the
 * landscape layout: wider lanes, no HUD row above them (the HUD sits in the corners beside the lanes) and a lower tray.
 *
 * **Lean.** With [farScale] below 1 the lanes are drawn as a flat strip tilted back from the player and seen in
 * perspective: the far end is [farScale] times as wide as the line, and a note shrinks and slows as it recedes. Lean
 * changes only what is drawn above the line; the line, the tray and the touch zones stay where they are.
 *
 * **Lane space.** Everything on the lanes is drawn in lane space, the strip before it is tilted: x is the screen's x at
 * the line, y runs from [laneTop] at the far end to [lineY] at the line, and [perspective] maps it to the screen.
 * Lane space and the screen agree exactly along the line, so a note at the line is drawn at its full size, and with no
 * lean they agree everywhere. The strip is [fallLength] long, the distance the runtime's `noteGeometry` places notes
 * along: a note travels it in the scroll time, whatever the lean.
 *
 * **Touch zones** are the lanes at the line, extended up and down the whole screen as columns; a touch beside the field
 * belongs to the nearest lane. They do not follow the lean, so changing it never moves a lane under the thumbs.
 */
data class PlayfieldGeometry(
    val width: Float,
    val height: Float,
    val density: Float,
    val landscape: Boolean,
    val lineLeft: Float,
    val lineWidth: Float,
    val fieldTop: Float,
    val lineY: Float,
    val trayBottom: Float,
    /** The far end's width over the line's: 1 is no lean. */
    val farScale: Float = 1f,
) {
    val laneWidth: Float get() = lineWidth / LANES
    val centerX: Float get() = lineLeft + lineWidth / 2
    val leans: Boolean get() = farScale < 1f

    /** The lanes' height on the screen, from the far end to the line. */
    val screenFall: Float get() = lineY - fieldTop

    /** The lanes' length in lane space: the distance a note travels in the scroll time. */
    val fallLength: Float get() = screenFall / farScale

    /** The far end of the lanes in lane space. */
    val laneTop: Float get() = lineY - fallLength

    val farWidth: Float get() = lineWidth * farScale
    val farLeft: Float get() = centerX - farWidth / 2

    /** Lane space's x of a lane's left edge, which is also the screen's at the line. */
    fun laneLeft(lane: Int): Float = lineLeft + lane * laneWidth

    /** The lane a touch at screen [x] belongs to, at any height. */
    fun laneAt(x: Float): Int {
        val lane = kotlin.math.floor((x - lineLeft) / laneWidth).toInt()
        return lane.coerceIn(0, LANES - 1)
    }

    fun dp(value: Float): Float = value * density

    /**
     * The projection from lane space to the screen, as the nine values of an `android.graphics.Matrix` (row by row:
     * scale x, skew x, translate x, skew y, scale y, translate y, then the perspective row). It is the identity when
     * the lanes do not lean.
     *
     * A point [d] of the way from the line to the far end (0 to 1) is drawn at `s = farScale / (farScale + (1 −
     * farScale)·d)` of its size at the line, `d·screenFall·s / farScale` above the line: the picture of a flat strip
     * tilted away from the eye, whose lane edges meet at a vanishing point above the far end.
     */
    val perspective: FloatArray = run {
        val a = (1 - farScale) / farScale
        val h = fallLength
        val cx = centerX
        floatArrayOf(
            1f, -cx * a / h, cx * a * lineY / h,
            0f, 1 - a * lineY / h, a * lineY * lineY / h,
            0f, -a / h, 1 + a * lineY / h,
        )
    }

    /** The screen point of lane-space ([x], [y]), into [out] as x then y. Used for what is drawn without the matrix. */
    fun project(x: Float, y: Float, out: FloatArray = FloatArray(2)): FloatArray {
        val m = perspective
        val w = m[6] * x + m[7] * y + m[8]
        out[0] = (m[0] * x + m[1] * y + m[2]) / w
        out[1] = (m[3] * x + m[4] * y + m[5]) / w
        return out
    }

    /**
     * How much taller than at the line a note must be in lane space at [y] to keep its shape on the screen: the
     * perspective narrows a note by `s` but flattens it by `s²`, and notes far away would thin to slivers. Taps and
     * hold ends are stretched by this so they only shrink as they recede; hold bodies are not, since their length is
     * time. 1 at the line and with no lean.
     */
    fun keepShape(y: Float): Float {
        val d = ((lineY - y) / fallLength).coerceIn(0f, 1f)
        return (farScale + (1 - farScale) * d) / farScale
    }

    /** The lane-space y drawn at screen [y] (between the far end and the line), the inverse of [project] in y. */
    fun laneY(y: Float): Float {
        val shown = lineY - y
        val a = (1 - farScale) / farScale
        return lineY - shown / (1 - a * shown / fallLength)
    }

    companion object {
        const val LANES = 4

        /** Portrait: the HUD row over the top of the playfield (title, accuracy, pause), drawn by Compose. */
        const val HUD_HEIGHT_DP = 64f

        /** Portrait: lanes are at most this wide together, as on the Swift play screen; a phone uses its width. */
        const val MAX_FIELD_WIDTH_DP = 440f
        const val SIDE_MARGIN_DP = 8f
        const val TRAY_HEIGHT_DP = 88f

        /**
         * Landscape: the lanes at the line take this share of the width (Malody's key mode on phones spans about 65
         * to 80 %), at most [LANDSCAPE_MAX_FIELD_WIDTH_DP]: about 148 dp per lane on a phone 923 dp long. The corners
         * beside the lanes' far end hold the HUD; the far end sits just under the top edge.
         */
        const val LANDSCAPE_FIELD_SHARE = 0.64f
        const val LANDSCAPE_MAX_FIELD_WIDTH_DP = 640f
        const val LANDSCAPE_TOP_DP = 6f
        const val LANDSCAPE_TRAY_HEIGHT_DP = 64f

        const val CAP_INSET_DP = 10f

        /** Space under the tray when the system reserves no gesture area. */
        const val MIN_BOTTOM_DP = 16f
        const val LANDSCAPE_MIN_BOTTOM_DP = 10f

        /**
         * [topInsetPx] keeps the HUD clear of a display cutout; [bottomInsetPx] keeps the key tray above the
         * system's mandatory gesture area (the home gesture), where touches may be taken by the system. A portrait
         * playfield without the HUD row (the calibration's) passes a small [hudHeightDp].
         */
        fun of(
            width: Float,
            height: Float,
            density: Float,
            topInsetPx: Float,
            bottomInsetPx: Float,
            hudHeightDp: Float = HUD_HEIGHT_DP,
            farScale: Float = 1f,
        ): PlayfieldGeometry {
            val landscape = width > height
            val lineWidth = lineWidth(width, density, landscape)
            val fieldTop = topInsetPx + (if (landscape) LANDSCAPE_TOP_DP else hudHeightDp) * density
            val minBottom = if (landscape) LANDSCAPE_MIN_BOTTOM_DP else MIN_BOTTOM_DP
            val trayBottom = height - max(bottomInsetPx, minBottom * density)
            val tray = if (landscape) LANDSCAPE_TRAY_HEIGHT_DP else TRAY_HEIGHT_DP
            // The line stays a pixel below the far end, so a very short surface still has a strip to tilt.
            val lineY = max(fieldTop + 1, trayBottom - tray * density)
            return PlayfieldGeometry(
                width = width,
                height = height,
                density = density,
                landscape = landscape,
                lineLeft = (width - lineWidth) / 2,
                lineWidth = lineWidth,
                fieldTop = fieldTop,
                lineY = lineY,
                trayBottom = trayBottom,
                farScale = farScale.coerceIn(MIN_FAR_SCALE, 1f),
            )
        }

        /** The lanes' width at the line on a surface [width] wide, in the same unit. */
        fun lineWidth(width: Float, density: Float, landscape: Boolean): Float {
            val most = if (landscape) min(width * LANDSCAPE_FIELD_SHARE, LANDSCAPE_MAX_FIELD_WIDTH_DP * density) else MAX_FIELD_WIDTH_DP * density
            return max(0f, min(width - 2 * SIDE_MARGIN_DP * density, most))
        }

        /** The strongest lean drawn: below this the far end is too small to read notes at. */
        const val MIN_FAR_SCALE = 0.2f
    }
}

/**
 * Which lanes are pressed, from the pointers on the surface. A pointer belongs to the lane it went down in until it
 * lifts; sliding into another lane does not move it. A lane is pressed while any pointer holds it, so the press goes
 * out with the first pointer and the release with the last.
 */
class LaneTouchTracker(private val lanes: Int = PlayfieldGeometry.LANES) {
    private val laneByPointer = HashMap<Int, Int>()
    private val pointersInLane = IntArray(lanes)

    /** A pointer went down in [lane]. True when that lane was not pressed before: a press to send. */
    fun down(pointerId: Int, lane: Int): Boolean {
        if (lane !in 0 until lanes || laneByPointer.containsKey(pointerId)) return false
        laneByPointer[pointerId] = lane
        return pointersInLane[lane]++ == 0
    }

    /** A pointer lifted. The lane it released, when no other pointer still holds it: a release to send. */
    fun up(pointerId: Int): Int? {
        val lane = laneByPointer.remove(pointerId) ?: return null
        pointersInLane[lane]--
        return if (pointersInLane[lane] == 0) lane else null
    }

    /** The system took the gesture: every pressed lane is released. */
    fun cancel(): List<Int> {
        val pressed = (0 until lanes).filter { pointersInLane[it] > 0 }
        laneByPointer.clear()
        pointersInLane.fill(0)
        return pressed
    }

    fun isPressed(lane: Int): Boolean = pointersInLane.getOrElse(lane) { 0 } > 0
}
