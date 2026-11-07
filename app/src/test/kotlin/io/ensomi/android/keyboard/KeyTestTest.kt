package io.ensomi.android.keyboard

import android.view.KeyEvent
import io.ensomi.android.play.KeyInput
import io.ensomi.android.play.LaneHolder
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.ResolvedKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key test on Settings › Keyboard (keyboard.md, Settings › Keyboard, item 4): the lanes held, the other keys held,
 * the most lane keys held together, and the limit sentence only after all four keys have been pressed.
 */
class KeyTestTest {
    private var map = KeyMap.DEFAULT

    /** Two keyboards with US QWERTY and the virtual keyboard play the map; device 13, the phone's buttons, is none. */
    private fun keysOf(deviceId: Int): ResolvedKeys? = if (deviceId in listOf(VIRTUAL, US, OTHER)) map.resolve { it } else null

    private fun down(keyCode: Int, device: Int = US, repeat: Int = 0, flags: Int = 0) =
        KeyInput(KeyEvent.ACTION_DOWN, keyCode, repeat, flags, device, 100)

    private fun up(keyCode: Int, device: Int = US) = KeyInput(KeyEvent.ACTION_UP, keyCode, 0, 0, device, 200)

    private var test = KeyTest()

    /** [inputs] in turn; the last one's action. */
    private fun send(vararg inputs: KeyInput): KeyTest.Action {
        var action = KeyTest.Action.PASS_ON
        for (input in inputs) {
            val step = test.key(input, ::keysOf)
            test = step.test
            action = step.action
        }
        return action
    }

    private fun held() = (0 until 4).filter { test.held(it) }

    @Test
    fun keysGoOnUntilTheTestStarts() {
        assertEquals(KeyTest.Action.PASS_ON, send(down(KeyEvent.KEYCODE_Z)))
        assertEquals(KeyTest.Action.PASS_ON, send(up(KeyEvent.KEYCODE_Z)))
        assertEquals(emptyList<Int>(), held())
    }

    @Test
    fun eachLaneIsHeldWhileItsKeyIsDown() {
        test = test.start()
        assertEquals(KeyTest.Action.TAKE, send(down(KeyEvent.KEYCODE_Z), down(KeyEvent.KEYCODE_PERIOD)))
        assertEquals(listOf(0, 2), held())
        assertEquals(KeyTest.Action.TAKE, send(up(KeyEvent.KEYCODE_Z)))
        assertEquals(listOf(2), held())
        assertEquals(2, test.mostHeld)
    }

    @Test
    fun theMostHeldTogetherCountsLanesSoOneLaneOnTwoKeyboardsIsOne() {
        test = test.start()
        send(down(KeyEvent.KEYCODE_Z, US), down(KeyEvent.KEYCODE_Z, OTHER))
        assertEquals(listOf(0), held())
        assertEquals(1, test.mostHeld)
        assertNull("One lane held is no number to show", test.shownMostHeld)
        send(up(KeyEvent.KEYCODE_Z, US))
        assertEquals("The other keyboard still holds it", listOf(0), held())
        send(down(KeyEvent.KEYCODE_X), down(KeyEvent.KEYCODE_SLASH))
        assertEquals(3, test.shownMostHeld)
    }

    @Test
    fun theOtherKeysHeldAreListedInTheOrderTheyWentDown() {
        test = test.start()
        send(down(KeyEvent.KEYCODE_SHIFT_LEFT), down(KeyEvent.KEYCODE_A), down(KeyEvent.KEYCODE_Z))
        assertEquals(
            listOf(LaneHolder.Key(US, KeyEvent.KEYCODE_SHIFT_LEFT), LaneHolder.Key(US, KeyEvent.KEYCODE_A)),
            test.others,
        )
        send(up(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(listOf(LaneHolder.Key(US, KeyEvent.KEYCODE_A)), test.others)
        assertEquals("Z is a lane's", listOf(0), held())
    }

    @Test
    fun theLimitIsSaidOnlyOnceAllFourKeysHaveBeenPressed() {
        test = test.start()
        // A keyboard that reports three keys at once: the fourth, pressed with them, never arrives.
        send(down(KeyEvent.KEYCODE_Z), down(KeyEvent.KEYCODE_X), down(KeyEvent.KEYCODE_PERIOD))
        assertEquals(3, test.shownMostHeld)
        assertNull("Lane 4's key has not been pressed yet", test.limit)
        send(up(KeyEvent.KEYCODE_Z), up(KeyEvent.KEYCODE_X), up(KeyEvent.KEYCODE_PERIOD))
        send(down(KeyEvent.KEYCODE_SLASH), up(KeyEvent.KEYCODE_SLASH))
        assertEquals(setOf(0, 1, 2, 3), test.pressed)
        assertEquals(3, test.limit)
    }

    @Test
    fun fourOfFourIsNoLimit() {
        test = test.start()
        send(down(KeyEvent.KEYCODE_Z), down(KeyEvent.KEYCODE_X), down(KeyEvent.KEYCODE_PERIOD), down(KeyEvent.KEYCODE_SLASH))
        assertEquals(listOf(0, 1, 2, 3), held())
        assertEquals(4, test.shownMostHeld)
        assertNull(test.limit)
    }

    @Test
    fun keysPressedOneAtATimeShowNoNumberAndNoLimit() {
        test = test.start()
        for (code in listOf(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH)) {
            send(down(code), up(code))
        }
        assertEquals(setOf(0, 1, 2, 3), test.pressed)
        assertNull("The player held one at a time: that says nothing of the keyboard", test.shownMostHeld)
        assertNull(test.limit)
        assertFalse(test.hasResult)
    }

    @Test
    fun escapeStopsTheTestUnlessItPlaysALaneAndItsUpGoesNowhere() {
        test = test.start()
        send(down(KeyEvent.KEYCODE_Z), down(KeyEvent.KEYCODE_X))
        assertEquals(KeyTest.Action.STOP, send(down(KeyEvent.KEYCODE_ESCAPE)))
        assertFalse(test.testing)
        assertEquals("What it found stays", 2, test.shownMostHeld)
        assertEquals(emptyList<Int>(), held())
        assertEquals("Escape's up does not go Back", KeyTest.Action.TAKE, send(up(KeyEvent.KEYCODE_ESCAPE)))
        assertEquals("Nor do the ups of keys held as it stopped", KeyTest.Action.TAKE, send(up(KeyEvent.KEYCODE_Z)))
        assertEquals(KeyTest.Action.PASS_ON, send(down(KeyEvent.KEYCODE_ESCAPE)))

        map = KeyMap.DEFAULT.bind(KeySlot.LANE_4, KeyMap.DEFAULT.pause!!).map
        test = KeyTest().start()
        assertEquals(KeyTest.Action.TAKE, send(down(KeyEvent.KEYCODE_ESCAPE)))
        assertEquals("Escape plays lane 4", listOf(3), held())
        assertTrue(test.testing)
    }

    @Test
    fun backGoesOnToTheActivityWhichStopsTheTest() {
        test = test.start()
        assertEquals(KeyTest.Action.PASS_ON, send(down(KeyEvent.KEYCODE_BACK)))
        assertTrue(test.testing)
    }

    @Test
    fun repeatsAndDevicesThatAreNoKeyboard() {
        test = test.start()
        send(down(KeyEvent.KEYCODE_Z))
        assertEquals(KeyTest.Action.TAKE, send(down(KeyEvent.KEYCODE_Z, repeat = 1), down(KeyEvent.KEYCODE_Z, repeat = 2)))
        assertEquals(listOf(0), held())
        assertEquals(KeyTest.Action.PASS_ON, send(down(KeyEvent.KEYCODE_VOLUME_UP, device = BUTTONS)))
        assertEquals(KeyTest.Action.TAKE, send(down(KeyEvent.KEYCODE_X, flags = KeyEvent.FLAG_FALLBACK)))
        assertEquals("A fallback event is nobody's press", listOf(0), held())
        assertEquals(KeyTest.Action.TAKE, send(down(KeyEvent.KEYCODE_X, VIRTUAL)))
        assertEquals("adb shell input's keys light lanes too", listOf(0, 1), held())
    }

    @Test
    fun testingAgainStartsCountingAfresh() {
        test = test.start()
        send(down(KeyEvent.KEYCODE_Z), down(KeyEvent.KEYCODE_X), up(KeyEvent.KEYCODE_Z), up(KeyEvent.KEYCODE_X))
        test = test.stop()
        assertTrue(test.hasResult)
        test = test.start()
        assertEquals(0, test.mostHeld)
        assertEquals(emptySet<Int>(), test.pressed)
        assertFalse(test.hasResult)
    }

    private companion object {
        const val VIRTUAL = -1
        const val US = 4
        const val OTHER = 5
        const val BUTTONS = 13
    }
}
