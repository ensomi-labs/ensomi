package io.ensomi.android.play

import android.view.KeyEvent
import io.ensomi.android.settings.KeySlot

/**
 * What each key does on the play screen and on Results (keyboard.md, Keys around the game). Lane keys play their lanes
 * through [lanes] while the game plays, and are taken and do nothing on the pause panel, in the count-in and on Results;
 * the Pause key pauses, resumes or goes back to the panel; Escape, Space, Tab, the arrows and Enter that the map does not
 * hold are taken in the game, so focus never moves onto the HUD under the player; anything else, and every key of a
 * device that is not a keyboard, is passed on. Used on the main thread.
 *
 * **One press, one action.** The Pause key acts only on a fresh down, a repeat count of 0, and is then spent until its
 * up: its repeats and its up are taken and do nothing, whatever the state by then. A held Pause key never pauses and
 * resumes, and its up never reaches the activity as Back on the panel its own down opened.
 */
class GameKeys(private val lanes: KeyLanes) {
    /** Where the player is. The game is playing, the count-in and the pause panel; Results come after it. */
    enum class State { PLAYING, COUNT_IN, PAUSED, RESULTS }

    /** What the screen does with a key. A lane key's press or release has been sent already. */
    enum class Action {
        /** Not the game's key: Compose and the system get it as they would without the game. */
        PASS_ON,

        /** Taken; nothing more happens. */
        TAKE,
        PAUSE,

        /** Resume, with the count-in. */
        RESUME,

        /** From the count-in back to the pause panel. */
        BACK_TO_PANEL,
    }

    /** The Pause key whose down acted, until its up. */
    private var spent: LaneHolder.Key? = null

    fun handle(key: KeyInput, state: State): Action {
        spent?.let { pause ->
            if (pause.deviceId == key.deviceId && pause.keyCode == key.keyCode) {
                if (key.action == KeyEvent.ACTION_UP) spent = null
                return Action.TAKE
            }
        }
        val slot = lanes.slotOf(key)
        val lane = slot?.lane
        return when {
            slot == KeySlot.PAUSE -> pauseKey(key, state)
            lane != null -> {
                lanes.play(key, lane, pressing = state == State.PLAYING)
                Action.TAKE
            }
            !lanes.isKeyboard(key.deviceId) -> Action.PASS_ON
            // Outside the game Escape is Back, Space and Enter press the focused button, and Tab and the arrows move.
            state == State.RESULTS -> Action.PASS_ON
            key.keyCode == KeyEvent.KEYCODE_ESCAPE -> Action.TAKE
            state == State.PAUSED -> Action.PASS_ON
            key.keyCode in NAVIGATION -> Action.TAKE
            else -> Action.PASS_ON
        }
    }

    private fun pauseKey(key: KeyInput, state: State): Action {
        // Results have nothing to pause: Escape goes back to the song, as on any screen, and another Pause key does nothing.
        if (state == State.RESULTS) return if (key.keyCode == KeyEvent.KEYCODE_ESCAPE) Action.PASS_ON else Action.TAKE
        // A repeat or an up whose down this did not see, such as a key held since before the game.
        if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount != 0) return Action.TAKE
        spent = LaneHolder.Key(key.deviceId, key.keyCode)
        return when (state) {
            State.PAUSED -> Action.RESUME
            State.COUNT_IN -> Action.BACK_TO_PANEL
            else -> Action.PAUSE
        }
    }

    private companion object {
        /**
         * The keys that move focus or press the focused button, taken during play and the count-in when the map does not
         * hold them. Enter includes numeric Enter and the D-pad centre, which Compose's clickable takes as Enter.
         */
        val NAVIGATION = setOf(
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
