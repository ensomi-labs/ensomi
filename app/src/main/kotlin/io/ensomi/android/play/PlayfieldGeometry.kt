package io.ensomi.android.play

import kotlin.math.max
import kotlin.math.min

/**
 * Where the playfield sits on the surface, in pixels: four touching lanes from [fieldTop] down to the judgement line
 * at [lineY], then the key tray to [trayBottom]. Lanes are the touch zones: a touch anywhere in a lane's column, from
 * the top of the screen to the bottom, is that lane, and a touch beside the field belongs to the nearest lane.
 */
data class PlayfieldGeometry(
    val width: Float,
    val height: Float,
    val density: Float,
    val fieldLeft: Float,
    val fieldWidth: Float,
    val fieldTop: Float,
    val lineY: Float,
    val trayBottom: Float,
) {
    val laneWidth: Float get() = fieldWidth / LANES

    /** The distance a note falls in the scroll time. */
    val fallHeight: Float get() = lineY - fieldTop

    fun laneLeft(lane: Int): Float = fieldLeft + lane * laneWidth

    fun laneAt(x: Float): Int {
        val lane = kotlin.math.floor((x - fieldLeft) / laneWidth).toInt()
        return lane.coerceIn(0, LANES - 1)
    }

    fun dp(value: Float): Float = value * density

    companion object {
        const val LANES = 4

        /** The HUD row over the top of the playfield (title, accuracy, pause), drawn by Compose. */
        const val HUD_HEIGHT_DP = 64f

        /** Lanes are at most this wide together, as on the Swift play screen; a phone in portrait uses its width. */
        const val MAX_FIELD_WIDTH_DP = 440f
        const val SIDE_MARGIN_DP = 8f
        const val TRAY_HEIGHT_DP = 88f
        const val CAP_INSET_DP = 10f

        /** Space under the tray when the system reserves no gesture area. */
        const val MIN_BOTTOM_DP = 16f

        /**
         * [topInsetPx] keeps the HUD clear of a display cutout; [bottomInsetPx] keeps the key tray above the
         * system's mandatory gesture area (the home gesture), where touches may be taken by the system. A playfield
         * without the HUD row (the calibration's) passes a small [hudHeightDp].
         */
        fun of(
            width: Float,
            height: Float,
            density: Float,
            topInsetPx: Float,
            bottomInsetPx: Float,
            hudHeightDp: Float = HUD_HEIGHT_DP,
        ): PlayfieldGeometry {
            val fieldWidth = max(0f, min(width - 2 * SIDE_MARGIN_DP * density, MAX_FIELD_WIDTH_DP * density))
            val fieldTop = topInsetPx + hudHeightDp * density
            val trayBottom = height - max(bottomInsetPx, MIN_BOTTOM_DP * density)
            val lineY = max(fieldTop, trayBottom - TRAY_HEIGHT_DP * density)
            return PlayfieldGeometry(
                width = width,
                height = height,
                density = density,
                fieldLeft = (width - fieldWidth) / 2,
                fieldWidth = fieldWidth,
                fieldTop = fieldTop,
                lineY = lineY,
                trayBottom = trayBottom,
            )
        }
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
