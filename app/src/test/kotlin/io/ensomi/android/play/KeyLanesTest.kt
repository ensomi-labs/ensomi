package io.ensomi.android.play

import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.KeyTable
import io.ensomi.android.settings.ResolvedKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLanesTest {
    /** French AZERTY's swaps on the letter rows, as `getKeyCodeForKeyLocation` gives them; the rest as US QWERTY. */
    private val azerty: (Int) -> Int = { place ->
        when (place) {
            KeyEvent.KEYCODE_Z -> KeyEvent.KEYCODE_W
            KeyEvent.KEYCODE_W -> KeyEvent.KEYCODE_Z
            KeyEvent.KEYCODE_Q -> KeyEvent.KEYCODE_A
            KeyEvent.KEYCODE_A -> KeyEvent.KEYCODE_Q
            else -> place
        }
    }

    private val sent = ArrayList<LaneInput>()
    private var sequence = 0L
    private val sender = LaneSender({ ++sequence }) { sent += it }

    /** Keyboard 3 is AZERTY, keyboard 4 US QWERTY; device 7 is a gamepad, which is no keyboard. */
    private fun lanes(map: KeyMap = KeyMap.DEFAULT): KeyLanes {
        val keyboards = mapOf(FRENCH to map.resolve(azerty), US to map.resolve { it })
        return KeyLanes(map, { keyboards[it] }, sender)
    }

    private fun down(keyCode: Int, device: Int = US, repeat: Int = 0, flags: Int = 0, timeMs: Long = 100) =
        KeyInput(KeyEvent.ACTION_DOWN, keyCode, repeat, flags, device, timeMs)

    private fun up(keyCode: Int, device: Int = US, flags: Int = 0, timeMs: Long = 200) =
        KeyInput(KeyEvent.ACTION_UP, keyCode, 0, flags, device, timeMs)

    /** Plays [key] as the game does while it plays: by its slot's lane, or not at all. */
    private fun KeyLanes.input(key: KeyInput): Boolean {
        val lane = slotOf(key)?.lane ?: return false
        return play(key, lane, pressing = true)
    }

    @Test
    fun aFakeAzertyLayoutMapsTheZPlaceToW() {
        val french = KeyMap.DEFAULT.resolve(azerty)
        assertEquals(KeyEvent.KEYCODE_W, french.keyCode(KeySlot.LANE_1))
        assertEquals(KeyEvent.KEYCODE_X, french.keyCode(KeySlot.LANE_2))
        val keys = lanes()
        assertEquals(KeySlot.LANE_1, keys.slotOf(down(KeyEvent.KEYCODE_W, FRENCH)))
        assertNull("Z is at the W place there, which no slot holds", keys.slotOf(down(KeyEvent.KEYCODE_Z, FRENCH)))
        assertEquals("The same place on a US keyboard is Z", KeySlot.LANE_1, keys.slotOf(down(KeyEvent.KEYCODE_Z, US)))
        assertTrue(keys.input(down(KeyEvent.KEYCODE_W, FRENCH)))
        assertEquals(listOf(LaneInput(0, press = true, 100_000_000, 1, InputSource.KEYBOARD)), sent)
    }

    @Test
    fun whereTheLayoutCannotSayAPlaceKeepsItsOwnKeyCode() {
        val unknown = KeyMap.DEFAULT.resolve { KeyEvent.KEYCODE_UNKNOWN }
        assertEquals(KeyEvent.KEYCODE_PERIOD, unknown.keyCode(KeySlot.LANE_3))
        assertEquals(KeySlot.PAUSE, unknown.slotOf(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun theVirtualKeyboardMapsEveryPlaceToItself() {
        val virtual = KeyCharacterMap.VIRTUAL_KEYBOARD
        val keys = lanes()
        assertTrue(keys.isKeyboard(virtual))
        assertEquals(KeySlot.LANE_1, keys.slotOf(down(KeyEvent.KEYCODE_Z, virtual)))
        assertEquals(KeySlot.LANE_2, keys.slotOf(down(KeyEvent.KEYCODE_X, virtual)))
        assertEquals(KeySlot.LANE_3, keys.slotOf(down(KeyEvent.KEYCODE_PERIOD, virtual)))
        assertEquals(KeySlot.LANE_4, keys.slotOf(down(KeyEvent.KEYCODE_SLASH, virtual)))
        assertEquals(KeySlot.PAUSE, keys.slotOf(down(KeyEvent.KEYCODE_ESCAPE, virtual)))
        for (key in KeyTable.keys) {
            val map = KeyMap.DEFAULT.bind(KeySlot.LANE_1, key).map
            assertEquals(key.name, KeySlot.LANE_1, lanes(map).slotOf(down(key.keyCode, virtual)))
        }
    }

    @Test
    fun repeatsPressNothingAndTheUpReleases() {
        val keys = lanes()
        assertTrue(keys.input(down(KeyEvent.KEYCODE_X, timeMs = 100)))
        assertFalse(keys.input(down(KeyEvent.KEYCODE_X, repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS, timeMs = 600)))
        assertFalse(keys.input(down(KeyEvent.KEYCODE_X, repeat = 2, timeMs = 650)))
        assertTrue(keys.input(up(KeyEvent.KEYCODE_X, timeMs = 800)))
        assertEquals(listOf(true to 100_000_000L, false to 800_000_000L), sent.map { it.press to it.eventNanos })
        assertEquals(listOf(1, 1), sent.map { it.lane })
    }

    @Test
    fun aCanceledUpStillReleases() {
        val keys = lanes()
        keys.input(down(KeyEvent.KEYCODE_SLASH))
        assertTrue(keys.input(up(KeyEvent.KEYCODE_SLASH, flags = KeyEvent.FLAG_CANCELED)))
        assertEquals(listOf(true, false), sent.map { it.press })
    }

    @Test
    fun fallbackSoftKeyboardAndVirtualHardKeyEventsNeverPlay() {
        val keys = lanes()
        for (flag in listOf(KeyEvent.FLAG_FALLBACK, KeyEvent.FLAG_SOFT_KEYBOARD, KeyEvent.FLAG_VIRTUAL_HARD_KEY)) {
            assertNull(keys.slotOf(down(KeyEvent.KEYCODE_Z, flags = flag)))
            assertNull(keys.slotOf(down(KeyEvent.KEYCODE_ESCAPE, flags = flag)))
        }
        assertNull(keys.slotOf(KeyInput(KeyEvent.ACTION_MULTIPLE, KeyEvent.KEYCODE_Z, 3, 0, US, 100)))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aDeviceThatIsNotAKeyboardPlaysNothing() {
        val keys = lanes()
        assertFalse(keys.isKeyboard(GAMEPAD))
        assertNull(keys.slotOf(down(KeyEvent.KEYCODE_Z, GAMEPAD)))
        assertNull(keys.slotOf(down(KeyEvent.KEYCODE_ESCAPE, GAMEPAD)))
        assertFalse(keys.input(down(KeyEvent.KEYCODE_Z, GAMEPAD)))
        assertTrue(keys.isKeyboard(US))
    }

    @Test
    fun aLaneWithoutAKeyIsPlayedByNoKey() {
        val keys = lanes(KeyMap.DEFAULT.clear(KeySlot.LANE_3))
        assertNull(keys.slotOf(down(KeyEvent.KEYCODE_PERIOD)))
        assertNull("An unknown key code is no empty slot", keys.slotOf(down(KeyEvent.KEYCODE_UNKNOWN)))
        assertEquals(KeySlot.LANE_4, keys.slotOf(down(KeyEvent.KEYCODE_SLASH)))
    }

    @Test
    fun aDownWhileTheGameIsNotPlayingPressesNothingAndAnUpStillReleases() {
        val keys = lanes()
        assertFalse(keys.play(down(KeyEvent.KEYCODE_Z), 0, pressing = false))
        assertFalse("Its down pressed nothing, so its up releases nothing", keys.play(up(KeyEvent.KEYCODE_Z), 0, true))
        assertTrue(keys.play(down(KeyEvent.KEYCODE_X), 1, pressing = true))
        assertTrue(keys.play(up(KeyEvent.KEYCODE_X), 1, pressing = false))
        assertEquals(listOf(1 to true, 1 to false), sent.map { it.lane to it.press })
    }

    @Test
    fun aKeyAndATouchShareTheLaneAndItsNumbers() {
        val keys = lanes()
        sender.press(LaneHolder.Pointer(0), 0, 50_000_000)
        assertFalse(keys.input(down(KeyEvent.KEYCODE_Z)))
        sender.release(LaneHolder.Pointer(0), 150_000_000)
        assertTrue(keys.input(up(KeyEvent.KEYCODE_Z)))
        assertEquals(listOf(InputSource.TOUCH, InputSource.KEYBOARD), sent.map { it.source })
        assertEquals(listOf(1L, 2L), sent.map { it.sequence })
    }

    @Test
    fun keysHeldAtAPauseKeepTheirLanesAndThoseLetGoAreReleasedAtTheResume() {
        val keys = lanes()
        keys.input(down(KeyEvent.KEYCODE_Z, timeMs = 100))
        keys.input(down(KeyEvent.KEYCODE_X, timeMs = 100))
        keys.input(down(KeyEvent.KEYCODE_PERIOD, timeMs = 110))
        sent.clear()
        sender.pause()
        // The panel and the count-in take lane keys' downs (pressing = false); their ups still come here.
        assertFalse("Let go: kept for the resume", keys.play(up(KeyEvent.KEYCODE_Z, timeMs = 300), 0, pressing = false))
        assertFalse(
            "Focus leaving the window cancels the keys held, and that up waits for the resume too",
            keys.play(up(KeyEvent.KEYCODE_X, flags = KeyEvent.FLAG_CANCELED, timeMs = 320), 1, pressing = false),
        )
        assertFalse("A press in the pause is dropped", keys.play(down(KeyEvent.KEYCODE_SLASH, timeMs = 330), 3, true))
        assertTrue(sent.isEmpty())
        assertEquals(listOf(0, 1), sender.resume(eventNanos = 900_000_000))
        assertEquals(
            listOf(
                LaneInput(0, press = false, 900_000_000, 4, InputSource.KEYBOARD, atPause = true),
                LaneInput(1, press = false, 900_000_000, 5, InputSource.KEYBOARD, atPause = true),
            ),
            sent,
        )
        assertTrue("The period key held through the pause still holds lane 3", keys.input(up(KeyEvent.KEYCODE_PERIOD)))
        assertFalse("The slash key's down was dropped, so its up is nothing", keys.input(up(KeyEvent.KEYCODE_SLASH)))
        assertEquals(listOf(LaneInput(2, press = false, 200_000_000, 6, InputSource.KEYBOARD)), sent.drop(2))
    }

    private companion object {
        const val FRENCH = 3
        const val US = 4
        const val GAMEPAD = 7
    }
}
