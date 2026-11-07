package io.ensomi.android.play

import android.view.KeyEvent

/**
 * What each key does on the calibration screen (keyboard.md, Calibration with keys). A lane key's fresh down presses the
 * calibration's note lane, whichever lane the key plays in a game, so the player presses with the finger they play
 * with; its up releases, canceled or not, and its repeats do nothing, as in play ([KeyLanes]). While the clicks are
 * paused a lane key's fresh down makes them go on instead, and presses nothing. Every other key, the Pause key among
 * them, is passed on, as on any screen: Escape is Back, Tab and the arrows move focus or a focused slider, Enter and
 * Space press the focused control. Used on the main thread.
 */
class CalibrationKeys(private val lanes: KeyLanes) {
    enum class Action {
        /** Not a lane key: Compose and the system get it as they would on any screen. */
        PASS_ON,

        /** A lane key's press, release or repeat; its press or release has been sent already. */
        TAKE,

        /** A lane key's fresh down while the clicks are paused: they go on. */
        RESUME,
    }

    /**
     * What [key] does with the clicks [paused] or not; a lane key presses [lane] only while they play and [canPress],
     * which the playfield clears until its game exists.
     */
    fun handle(key: KeyInput, lane: Int, paused: Boolean, canPress: Boolean = true): Action {
        if (lanes.slotOf(key)?.lane == null) return Action.PASS_ON
        val fresh = key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0
        // A key that makes the clicks go on is held from its down, so its up is taken too and releases nothing.
        lanes.play(key, lane, pressing = !paused && canPress)
        return if (paused && fresh) Action.RESUME else Action.TAKE
    }
}
