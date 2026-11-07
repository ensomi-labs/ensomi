package io.ensomi.android.play

import android.view.KeyEvent
import io.ensomi.android.play.GameKeys.Action
import io.ensomi.android.play.GameKeys.State
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.KeyTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameKeysTest {
    private val sent = ArrayList<LaneInput>()
    private var sequence = 0L

    /** A game's keys on [map], from a US keyboard (device 4); device 7 is a gamepad. */
    private fun keys(map: KeyMap = KeyMap.DEFAULT): GameKeys {
        val keyboard = map.resolve { it }
        val sender = LaneSender({ ++sequence }) { sent += it }
        return GameKeys(KeyLanes(map, { if (it == KEYBOARD) keyboard else null }, sender))
    }

    private fun down(keyCode: Int, repeat: Int = 0, device: Int = KEYBOARD) =
        KeyInput(KeyEvent.ACTION_DOWN, keyCode, repeat, 0, device, 100)

    private fun up(keyCode: Int, device: Int = KEYBOARD) = KeyInput(KeyEvent.ACTION_UP, keyCode, 0, 0, device, 200)

    /** A tap of [keyCode] in [state], as `adb shell input keyevent` sends it: what its down and its up did. */
    private fun GameKeys.tap(keyCode: Int, state: State) = handle(down(keyCode), state) to handle(up(keyCode), state)

    private fun key(name: String) = KeyTable.named(name)!!

    /** The default map with Pause on P, so Escape is in no slot. */
    private val pauseOnP = KeyMap.DEFAULT.bind(KeySlot.PAUSE, KeyTable.named("P")!!).map

    @Test
    fun whilePlayingLaneKeysPressAndReleaseTheirLanes() {
        val game = keys()
        assertEquals(Action.TAKE to Action.TAKE, game.tap(KeyEvent.KEYCODE_Z, State.PLAYING))
        assertEquals(Action.TAKE, game.handle(down(KeyEvent.KEYCODE_SLASH), State.PLAYING))
        assertEquals(Action.TAKE, game.handle(down(KeyEvent.KEYCODE_SLASH, repeat = 1), State.PLAYING))
        assertEquals(Action.TAKE, game.handle(up(KeyEvent.KEYCODE_SLASH), State.PLAYING))
        assertEquals(listOf(0 to true, 0 to false, 3 to true, 3 to false), sent.map { it.lane to it.press })
    }

    @Test
    fun thePauseKeyPausesResumesAndGoesBackToThePanel() {
        val game = keys()
        assertEquals(Action.PAUSE to Action.TAKE, game.tap(KeyEvent.KEYCODE_ESCAPE, State.PLAYING))
        assertEquals(Action.RESUME to Action.TAKE, game.tap(KeyEvent.KEYCODE_ESCAPE, State.PAUSED))
        assertEquals(Action.BACK_TO_PANEL to Action.TAKE, game.tap(KeyEvent.KEYCODE_ESCAPE, State.COUNT_IN))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aHeldPauseKeyActsOnceWhateverTheScreenShowsAfter() {
        val game = keys()
        assertEquals(Action.PAUSE, game.handle(down(KeyEvent.KEYCODE_ESCAPE), State.PLAYING))
        // The panel is up now; the key repeats, and must not resume.
        assertEquals(Action.TAKE, game.handle(down(KeyEvent.KEYCODE_ESCAPE, repeat = 1), State.PAUSED))
        assertEquals(Action.TAKE, game.handle(down(KeyEvent.KEYCODE_ESCAPE, repeat = 2), State.PAUSED))
        // Its up is taken too, so the activity never sees it as Back on the panel the down opened.
        assertEquals(Action.TAKE, game.handle(up(KeyEvent.KEYCODE_ESCAPE), State.PAUSED))
        assertEquals("Spent only until its up", Action.RESUME, game.handle(down(KeyEvent.KEYCODE_ESCAPE), State.PAUSED))
        assertEquals(
            "The game ended meanwhile; the up is still taken",
            Action.TAKE,
            game.handle(up(KeyEvent.KEYCODE_ESCAPE), State.RESULTS),
        )
    }

    @Test
    fun aPauseKeyWhoseDownCameBeforeTheGameDoesNothing() {
        val game = keys()
        assertEquals(Action.TAKE, game.handle(down(KeyEvent.KEYCODE_ESCAPE, repeat = 4), State.PLAYING))
        assertEquals(Action.TAKE, game.handle(up(KeyEvent.KEYCODE_ESCAPE), State.PLAYING))
    }

    @Test
    fun navigationKeysTheMapDoesNotHoldAreTakenInPlayAndTheCountIn() {
        val game = keys(pauseOnP)
        val navigation = listOf(
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        for (state in listOf(State.PLAYING, State.COUNT_IN)) {
            for (code in navigation) assertEquals("$code in $state", Action.TAKE to Action.TAKE, game.tap(code, state))
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun theSystemsKeysAndKeysOutsideTheMapArePassedOn() {
        val game = keys()
        val others = listOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_SHIFT_LEFT,
        )
        for (state in State.entries) {
            for (code in others) assertEquals("$code in $state", Action.PASS_ON to Action.PASS_ON, game.tap(code, state))
        }
    }

    @Test
    fun onThePanelLaneKeysDoNothingAndTheButtonsTakeEnterSpaceTabAndArrows() {
        val game = keys(pauseOnP)
        assertEquals(Action.TAKE to Action.TAKE, game.tap(KeyEvent.KEYCODE_Z, State.PAUSED))
        assertEquals(Action.TAKE, game.handle(down(KeyEvent.KEYCODE_X, repeat = 1), State.PAUSED))
        assertTrue("A lane key on the panel presses nothing", sent.isEmpty())
        for (code in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_DOWN)) {
            assertEquals("$code", Action.PASS_ON to Action.PASS_ON, game.tap(code, State.PAUSED))
        }
        assertEquals(
            "Escape is not the Pause key here, and does nothing",
            Action.TAKE to Action.TAKE,
            game.tap(KeyEvent.KEYCODE_ESCAPE, State.PAUSED),
        )
        assertEquals("Back leaves the game", Action.PASS_ON to Action.PASS_ON, game.tap(KeyEvent.KEYCODE_BACK, State.PAUSED))
        assertEquals(Action.RESUME to Action.TAKE, game.tap(KeyEvent.KEYCODE_P, State.PAUSED))
    }

    @Test
    fun inTheCountInLaneKeysDoNothing() {
        val game = keys()
        assertEquals(Action.TAKE to Action.TAKE, game.tap(KeyEvent.KEYCODE_PERIOD, State.COUNT_IN))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun onResultsLaneKeysDoNothingAndEscapeGoesBackToTheSong() {
        val game = keys()
        assertEquals(Action.TAKE to Action.TAKE, game.tap(KeyEvent.KEYCODE_Z, State.RESULTS))
        assertTrue(sent.isEmpty())
        assertEquals(
            "The Pause key, Escape, is Back there",
            Action.PASS_ON to Action.PASS_ON,
            game.tap(KeyEvent.KEYCODE_ESCAPE, State.RESULTS),
        )
        for (code in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            assertEquals("$code", Action.PASS_ON to Action.PASS_ON, game.tap(code, State.RESULTS))
        }
        val elsewhere = keys(pauseOnP)
        assertEquals(Action.PASS_ON to Action.PASS_ON, elsewhere.tap(KeyEvent.KEYCODE_ESCAPE, State.RESULTS))
        assertEquals(
            "Another Pause key has nothing to pause",
            Action.TAKE to Action.TAKE,
            elsewhere.tap(KeyEvent.KEYCODE_P, State.RESULTS),
        )
    }

    @Test
    fun aLaneKeysUpReleasesItsLaneWhateverTheGameShows() {
        val game = keys()
        game.handle(down(KeyEvent.KEYCODE_X), State.PLAYING)
        assertEquals(Action.TAKE, game.handle(up(KeyEvent.KEYCODE_X), State.PAUSED))
        assertEquals(listOf(1 to true, 1 to false), sent.map { it.lane to it.press })
    }

    @Test
    fun aLaneKeyPressedOnThePanelPlaysOnlyWhenPressedAgain() {
        val game = keys()
        game.handle(down(KeyEvent.KEYCODE_X), State.PAUSED)
        game.handle(up(KeyEvent.KEYCODE_X), State.PLAYING)
        assertTrue(sent.isEmpty())
        game.handle(down(KeyEvent.KEYCODE_X), State.PLAYING)
        assertEquals(listOf(1 to true), sent.map { it.lane to it.press })
    }

    @Test
    fun spaceIsAKeyLikeAnyOtherInTheMap() {
        val onALane = keys(KeyMap.DEFAULT.bind(KeySlot.LANE_2, key("SPACE")).map)
        assertEquals(Action.TAKE to Action.TAKE, onALane.tap(KeyEvent.KEYCODE_SPACE, State.PLAYING))
        assertEquals(listOf(1 to true, 1 to false), sent.map { it.lane to it.press })
        assertEquals(
            "It presses no button on the panel",
            Action.TAKE to Action.TAKE,
            onALane.tap(KeyEvent.KEYCODE_SPACE, State.PAUSED),
        )
        assertEquals("Nor on Results", Action.TAKE to Action.TAKE, onALane.tap(KeyEvent.KEYCODE_SPACE, State.RESULTS))
        assertEquals(2, sent.size)

        val pausing = keys(KeyMap.DEFAULT.bind(KeySlot.PAUSE, key("SPACE")).map)
        assertEquals(Action.PAUSE to Action.TAKE, pausing.tap(KeyEvent.KEYCODE_SPACE, State.PLAYING))
        assertEquals(Action.RESUME to Action.TAKE, pausing.tap(KeyEvent.KEYCODE_SPACE, State.PAUSED))
        assertEquals(Action.TAKE to Action.TAKE, pausing.tap(KeyEvent.KEYCODE_SPACE, State.RESULTS))
        assertEquals(
            "Escape, now in no slot, does nothing in the game",
            Action.TAKE to Action.TAKE,
            pausing.tap(KeyEvent.KEYCODE_ESCAPE, State.PLAYING),
        )

        val unbound = keys()
        assertEquals(
            "In no slot it does nothing, and never pauses",
            Action.TAKE to Action.TAKE,
            unbound.tap(KeyEvent.KEYCODE_SPACE, State.PLAYING),
        )
    }

    @Test
    fun escapeIsAKeyLikeAnyOtherInTheMap() {
        val onALane = keys(KeyMap.DEFAULT.bind(KeySlot.LANE_4, key("ESCAPE")).map)
        assertEquals(Action.TAKE to Action.TAKE, onALane.tap(KeyEvent.KEYCODE_ESCAPE, State.PLAYING))
        assertEquals(listOf(3 to true, 3 to false), sent.map { it.lane to it.press })
        assertEquals("Slash took Pause by the swap", Action.PAUSE to Action.TAKE, onALane.tap(KeyEvent.KEYCODE_SLASH, State.PLAYING))
        assertEquals(
            "A lane's Escape is no Back on Results",
            Action.TAKE to Action.TAKE,
            onALane.tap(KeyEvent.KEYCODE_ESCAPE, State.RESULTS),
        )
    }

    @Test
    fun boundArrowsPlayTheirLanesAndTheOthersAreTaken() {
        var map = KeyMap.DEFAULT
        for ((slot, name) in KeySlot.LANES.zip(listOf("DPAD_LEFT", "DPAD_DOWN", "DPAD_UP", "DPAD_RIGHT"))) {
            map = map.bind(slot, key(name)).map
        }
        val game = keys(map.clear(KeySlot.LANE_3))
        game.tap(KeyEvent.KEYCODE_DPAD_LEFT, State.PLAYING)
        game.tap(KeyEvent.KEYCODE_DPAD_RIGHT, State.PLAYING)
        assertEquals(listOf(0, 0, 3, 3), sent.map { it.lane })
        assertEquals("Up is in no slot now", Action.TAKE to Action.TAKE, game.tap(KeyEvent.KEYCODE_DPAD_UP, State.PLAYING))
        assertEquals(4, sent.size)
    }

    @Test
    fun aDeviceThatIsNotAKeyboardIsPassedOn() {
        val game = keys()
        for (code in listOf(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_SPACE)) {
            for (state in State.entries) {
                assertEquals("$code in $state", Action.PASS_ON, game.handle(down(code, device = GAMEPAD), state))
            }
        }
        assertTrue(sent.isEmpty())
    }

    private companion object {
        const val KEYBOARD = 4
        const val GAMEPAD = 7
    }
}
