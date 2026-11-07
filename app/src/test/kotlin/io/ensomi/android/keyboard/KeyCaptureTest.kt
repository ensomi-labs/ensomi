package io.ensomi.android.keyboard

import android.view.KeyEvent
import io.ensomi.android.play.KeyInput
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.KeyTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wait for a key on Settings › Keyboard (keyboard.md, Settings › Keyboard, item 2; O9, O10). */
class KeyCaptureTest {
    /** French AZERTY's swaps of the Z and W places, and of A and Q, as `getKeyCodeForKeyLocation` gives them. */
    private val azerty: (Int) -> Int = { place ->
        when (place) {
            KeyEvent.KEYCODE_Z -> KeyEvent.KEYCODE_W
            KeyEvent.KEYCODE_W -> KeyEvent.KEYCODE_Z
            KeyEvent.KEYCODE_Q -> KeyEvent.KEYCODE_A
            KeyEvent.KEYCODE_A -> KeyEvent.KEYCODE_Q
            else -> place
        }
    }

    /** The virtual keyboard of `adb shell input` and a US keyboard produce each place's own key; the French one AZERTY's. */
    private fun layoutOf(deviceId: Int): ((Int) -> Int)? = when (deviceId) {
        VIRTUAL, US -> { place -> place }
        FRENCH -> azerty
        else -> null
    }

    private var map = KeyMap.DEFAULT

    private fun key(name: String) = KeyTable.named(name)!!

    private fun KeyMap.names() = KeySlot.entries.map { this[it]?.name }

    private fun down(keyCode: Int, device: Int = US, repeat: Int = 0, flags: Int = 0) =
        KeyInput(KeyEvent.ACTION_DOWN, keyCode, repeat, flags, device, 100)

    private fun up(keyCode: Int, device: Int = US) = KeyInput(KeyEvent.ACTION_UP, keyCode, 0, 0, device, 200)

    /** [key] to [capture], keeping the map it makes, as the screen does. */
    private fun KeyCapture.press(input: KeyInput): KeyCapture.Step =
        this.key(input, map, ::layoutOf).also { step -> step.map?.let { map = it } }

    /** A down and its up, as `adb shell input keyevent` sends them: the capture after both, and whether both were taken. */
    private fun KeyCapture.tap(keyCode: Int, device: Int = US): Pair<KeyCapture, Boolean> {
        val down = press(down(keyCode, device))
        val up = down.capture.press(up(keyCode, device))
        return up.capture to (down.taken && up.taken)
    }

    @Test
    fun pressingASlotWaitsAndPressingItAgainStopsWaiting() {
        val waiting = KeyCapture().start(KeySlot.LANE_2)
        assertEquals(KeySlot.LANE_2, waiting.waiting)
        assertEquals(KeySlot.PAUSE, waiting.start(KeySlot.PAUSE).waiting)
        assertNull(waiting.start(KeySlot.LANE_2).waiting)
        assertNull(waiting.cancel().waiting)
    }

    @Test
    fun fourPressesSetFourLanesAndTheWaitEndsAfterLane4NeverMovingToPause() {
        var capture = KeyCapture().start(KeySlot.LANE_1)
        val waited = ArrayList<KeySlot>()
        for (code in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L)) {
            waited += capture.waiting!!
            val (next, taken) = capture.tap(code)
            assertTrue(taken)
            assertNull("A bind that swapped nothing says nothing", next.note)
            capture = next
        }
        assertEquals(KeySlot.LANES, waited)
        assertNull("The wait ends after lane 4", capture.waiting)
        assertEquals(listOf("A", "S", "K", "L", "ESCAPE"), map.names())
        assertFalse("Nothing waits, so keys go on", capture.press(down(KeyEvent.KEYCODE_Q)).taken)
    }

    @Test
    fun aBindOfPauseEndsTheWait() {
        val (capture, _) = KeyCapture().start(KeySlot.PAUSE).tap(KeyEvent.KEYCODE_P)
        assertNull(capture.waiting)
        assertEquals("P", map.pause?.name)
    }

    @Test
    fun aKeyInAnotherSlotSwapsTheTwoEndsTheWaitAndSaysWhatMoved() {
        val (capture, taken) = KeyCapture().start(KeySlot.LANE_1).tap(KeyEvent.KEYCODE_X)
        assertTrue(taken)
        assertEquals(listOf("X", "Z", "PERIOD", "SLASH", "ESCAPE"), map.names())
        assertNull("A swap ends the wait", capture.waiting)
        assertEquals(CaptureNote.Swapped(KeySlot.LANE_1, key("X"), KeySlot.LANE_2, key("Z")), capture.note)
    }

    @Test
    fun escapeBindsLikeAnyKeyAndPauseTakesTheLanesKey() {
        val (capture, taken) = KeyCapture().start(KeySlot.LANE_4).tap(KeyEvent.KEYCODE_ESCAPE, VIRTUAL)
        assertTrue("Escape is the capture's: it neither cancels nor goes Back", taken)
        assertEquals(listOf("Z", "X", "PERIOD", "ESCAPE", "SLASH"), map.names())
        assertEquals(CaptureNote.Swapped(KeySlot.LANE_4, key("ESCAPE"), KeySlot.PAUSE, key("SLASH")), capture.note)
    }

    @Test
    fun aSwapWithAnEmptySlotEmptiesTheOther() {
        map = KeyMap.DEFAULT.clear(KeySlot.PAUSE)
        val (capture, _) = KeyCapture().start(KeySlot.PAUSE).tap(KeyEvent.KEYCODE_SLASH)
        assertEquals(listOf("Z", "X", "PERIOD", null, "SLASH"), map.names())
        assertEquals(CaptureNote.Swapped(KeySlot.PAUSE, key("SLASH"), KeySlot.LANE_4, null), capture.note)
        assertEquals(listOf(KeySlot.LANE_4), map.emptyLanes)
    }

    @Test
    fun aKeyNoSlotMayTakeIsRefusedAndTheWaitStaysOpen() {
        var capture = KeyCapture().start(KeySlot.LANE_2)
        for (code in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_NUMPAD_1)) {
            val (next, taken) = capture.tap(code)
            assertTrue("Every key goes to the capture: Enter cannot press a button under it", taken)
            assertEquals(KeySlot.LANE_2, next.waiting)
            assertEquals(CaptureNote.Refused, next.note)
            capture = next
        }
        assertEquals(KeyMap.DEFAULT, map)
        val (bound, _) = capture.tap(KeyEvent.KEYCODE_M)
        assertNull("A bind replaces the refusal", bound.note)
        assertEquals(KeySlot.LANE_3, bound.waiting)
    }

    @Test
    fun shiftHeldWithALetterBindsTheLetter() {
        var capture = KeyCapture().start(KeySlot.LANE_1)
        capture = capture.press(down(KeyEvent.KEYCODE_SHIFT_LEFT)).capture
        capture = capture.press(down(KeyEvent.KEYCODE_A)).capture
        assertEquals("A", map.lanes[0]?.name)
        assertEquals(KeySlot.LANE_2, capture.waiting)
        assertTrue(capture.press(up(KeyEvent.KEYCODE_SHIFT_LEFT)).taken)
    }

    @Test
    fun backGoesOnToTheActivityWhichEndsTheWait() {
        val capture = KeyCapture().start(KeySlot.LANE_1)
        val step = capture.press(down(KeyEvent.KEYCODE_BACK))
        assertFalse(step.taken)
        assertEquals("The screen's Back ends the wait, not the capture", KeySlot.LANE_1, step.capture.waiting)
        assertEquals(KeyMap.DEFAULT, map)
    }

    @Test
    fun aHeldKeysRepeatsNeverBindTheNextLane() {
        var capture = KeyCapture().start(KeySlot.LANE_1)
        capture = capture.press(down(KeyEvent.KEYCODE_A)).capture
        for (repeat in 1..5) {
            val step = capture.press(down(KeyEvent.KEYCODE_A, repeat = repeat))
            assertTrue(step.taken)
            capture = step.capture
        }
        assertEquals(KeySlot.LANE_2, capture.waiting)
        assertEquals("A", map.lanes[0]?.name)
        assertEquals("X", map.lanes[1]?.name)
    }

    @Test
    fun theUpOfTheKeyThatEndedTheWaitIsTakenToo() {
        var capture = KeyCapture().start(KeySlot.LANE_4)
        capture = capture.press(down(KeyEvent.KEYCODE_SPACE)).capture
        assertNull(capture.waiting)
        val up = capture.press(up(KeyEvent.KEYCODE_SPACE))
        assertTrue("Space's up reaches no button", up.taken)
        assertFalse("Once it is up, the key is no longer the capture's", up.capture.press(down(KeyEvent.KEYCODE_SPACE)).taken)
    }

    @Test
    fun keysOfDevicesThatAreNoKeyboardGoOn() {
        val capture = KeyCapture().start(KeySlot.LANE_1)
        val step = capture.press(down(KeyEvent.KEYCODE_VOLUME_DOWN, device = BUTTONS))
        assertFalse("The phone's own volume buttons work as ever", step.taken)
        assertEquals(KeySlot.LANE_1, step.capture.waiting)
        assertNull(step.capture.note)
    }

    @Test
    fun eventsNoOnePressedToBindAreTakenAndBindNothing() {
        val capture = KeyCapture().start(KeySlot.LANE_1)
        for (flag in listOf(KeyEvent.FLAG_FALLBACK, KeyEvent.FLAG_SOFT_KEYBOARD, KeyEvent.FLAG_VIRTUAL_HARD_KEY)) {
            val step = capture.press(down(KeyEvent.KEYCODE_A, flags = flag))
            assertTrue(step.taken)
            assertEquals(KeySlot.LANE_1, step.capture.waiting)
        }
        assertEquals(KeyMap.DEFAULT, map)
    }

    @Test
    fun aKeyBindsByItsPlaceOnItsKeyboard() {
        // On AZERTY the key labelled A sits at the Q place, and W at the Z place.
        val (capture, _) = KeyCapture().start(KeySlot.LANE_2).tap(KeyEvent.KEYCODE_A, FRENCH)
        assertEquals("Q", map.lanes[1]?.name)
        assertEquals(KeySlot.LANE_3, capture.waiting)
        capture.tap(KeyEvent.KEYCODE_W, FRENCH)
        assertEquals("The W key is at the Z place, lane 1's: a swap", "Z", map.lanes[2]?.name)
        assertEquals("PERIOD", map.lanes[0]?.name)
    }

    @Test
    fun clearEmptiesTheWaitingSlotAndEndsTheWait() {
        val (capture, cleared) = KeyCapture().start(KeySlot.LANE_3).clear(map)!!
        assertNull(capture.waiting)
        assertEquals(listOf("Z", "X", null, "SLASH", "ESCAPE"), cleared.names())
        assertEquals(listOf(KeySlot.LANE_3), cleared.emptyLanes)
        assertNull("Clear needs a slot waiting", KeyCapture().clear(map))
    }

    @Test
    fun cancelEndsTheWaitWithTheMapAsItWas() {
        val waiting = KeyCapture().start(KeySlot.LANE_1).tap(KeyEvent.KEYCODE_ENTER).first
        assertEquals(CaptureNote.Refused, waiting.note)
        val cancelled = waiting.cancel()
        assertNull(cancelled.waiting)
        assertNull(cancelled.note)
        assertEquals(KeyMap.DEFAULT, map)
        val idle = KeyCapture()
        assertSame("Nothing waiting, nothing to cancel", idle, idle.cancel())
    }

    private companion object {
        const val VIRTUAL = -1
        const val US = 4
        const val FRENCH = 3

        /** The phone's own buttons: a device with keys that is no keyboard. */
        const val BUTTONS = 13
    }
}
