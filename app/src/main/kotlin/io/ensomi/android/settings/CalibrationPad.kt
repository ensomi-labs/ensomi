package io.ensomi.android.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The sound step's tap pad: a plain `View`, so each tap carries its `MotionEvent` time to the nanosecond
 * (`getEventTimeNanos`, API 34; milliseconds before), as a lane touch does in play. A finger landing is a tap, each
 * finger its own. The pad gives no feedback of its own: no click sound and no vibration, which would be a second cue
 * competing with the click. Its look is drawn by the Compose panel under it.
 */
class CalibrationPadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** Hears each tap's event time, on the main thread. */
    var onTap: (Long) -> Unit = {}

    init {
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
        isFocusable = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> onTap(eventNanos(event))
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    /** A click (TalkBack's double tap included) is not timed, so it counts as no tap. */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun eventNanos(event: MotionEvent): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) event.eventTimeNanos else event.eventTime * 1_000_000
}
