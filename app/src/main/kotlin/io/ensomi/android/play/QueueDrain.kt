package io.ensomi.android.play

/**
 * Keeps the surface's buffer queue from staying a frame too deep. A frame that reaches SurfaceFlinger late can leave one
 * more buffer queued than the display takes, and since every vsync brings a new frame nothing takes it out again: each
 * frame then waits most of a refresh in HWUI for a free buffer, and is shown a refresh later than it was drawn for, until
 * a frame is skipped. So when HWUI holds [HELD_FRAMES] frames in a row for over half a refresh, the next vsync draws
 * nothing. Used on the render thread.
 */
class QueueDrain {
    private var heldInARow = 0
    private var skipNext = false

    /** A frame was handed to HWUI, which held it for [postNanos]. */
    fun posted(postNanos: Long, intervalNanos: Long) {
        if (postNanos <= intervalNanos / 2) {
            heldInARow = 0
        } else if (++heldInARow >= HELD_FRAMES) {
            heldInARow = 0
            skipNext = true
        }
    }

    /** Whether this vsync draws nothing; asked once per vsync. */
    fun takeSkip(): Boolean {
        val skip = skipNext
        skipNext = false
        return skip
    }

    companion object {
        const val HELD_FRAMES = 2
    }
}
