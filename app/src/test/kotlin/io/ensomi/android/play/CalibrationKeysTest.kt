package io.ensomi.android.play

import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.ensomi.android.play.CalibrationKeys.Action
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.KeyTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lane keys on the calibration screen (keyboard.md, Calibration with keys; K5). */
class CalibrationKeysTest {
    private val sent = ArrayList<LaneInput>()
    private var sequence = 0L
    private val sender = LaneSender({ ++sequence }) { sent += it }
    private val lane = SampleCharts.CALIBRATION_LANE

    /** The calibration's keys on [map], from a US keyboard (device 4); device 7 is a gamepad. */
    private fun keys(map: KeyMap = KeyMap.DEFAULT): CalibrationKeys {
        val keyboard = map.resolve { it }
        return CalibrationKeys(KeyLanes(map, { if (it == KEYBOARD) keyboard else null }, sender))
    }

    private fun down(keyCode: Int, atMs: Long = 100, repeat: Int = 0, device: Int = KEYBOARD) =
        KeyInput(KeyEvent.ACTION_DOWN, keyCode, repeat, 0, device, atMs)

    private fun up(keyCode: Int, atMs: Long = 200, device: Int = KEYBOARD) = KeyInput(KeyEvent.ACTION_UP, keyCode, 0, 0, device, atMs)

    private fun CalibrationKeys.tap(keyCode: Int, paused: Boolean = false) =
        handle(down(keyCode), lane, paused) to handle(up(keyCode), lane, paused)

    @Test
    fun everyLaneKeyPressesTheNotesLaneAsAKeyAtItsOwnTime() {
        val calibration = keys()
        for (code in listOf(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH)) {
            assertEquals(Action.TAKE to Action.TAKE, calibration.tap(code))
        }
        assertEquals(List(4) { listOf(lane to true, lane to false) }.flatten(), sent.map { it.lane to it.press })
        assertTrue("Judged with the keyboard's input slot", sent.all { it.source == InputSource.KEYBOARD })
        assertEquals("The key event's own time", listOf(100L, 200L), sent.take(2).map { it.eventNanos / 1_000_000 })
    }

    @Test
    fun repeatsPressNothingAndTheUpReleases() {
        val calibration = keys()
        assertEquals(Action.TAKE, calibration.handle(down(KeyEvent.KEYCODE_X), lane, paused = false))
        for (repeat in 1..5) {
            assertEquals(Action.TAKE, calibration.handle(down(KeyEvent.KEYCODE_X, atMs = 600L + repeat * 50, repeat = repeat), lane, paused = false))
        }
        assertEquals(Action.TAKE, calibration.handle(up(KeyEvent.KEYCODE_X, atMs = 1_000), lane, paused = false))
        assertEquals(listOf(true to 100L, false to 1_000L), sent.map { it.press to it.eventNanos / 1_000_000 })
    }

    @Test
    fun aSecondLaneKeyWhileOneIsHeldPressesNothing() {
        val calibration = keys()
        calibration.handle(down(KeyEvent.KEYCODE_Z), lane, paused = false)
        calibration.handle(down(KeyEvent.KEYCODE_SLASH, atMs = 150), lane, paused = false)
        calibration.handle(up(KeyEvent.KEYCODE_Z), lane, paused = false)
        calibration.handle(up(KeyEvent.KEYCODE_SLASH, atMs = 250), lane, paused = false)
        // One lane, held by two keys: one press, one release, as in play.
        assertEquals(listOf(true to 100L, false to 250L), sent.map { it.press to it.eventNanos / 1_000_000 })
    }

    @Test
    fun whileTheClicksArePausedALaneKeyMakesThemGoOnAndPressesNothing() {
        val calibration = keys()
        sender.pause()
        assertEquals(Action.RESUME, calibration.handle(down(KeyEvent.KEYCODE_X), lane, paused = true))
        assertEquals("Its repeats only", Action.TAKE, calibration.handle(down(KeyEvent.KEYCODE_X, repeat = 1), lane, paused = true))
        sender.resume(150 * MS)
        assertEquals("Its up is taken, and releases nothing", Action.TAKE, calibration.handle(up(KeyEvent.KEYCODE_X), lane, paused = false))
        assertTrue(sent.isEmpty())
        calibration.tap(KeyEvent.KEYCODE_X)
        assertEquals(listOf(true, false), sent.map { it.press })
    }

    @Test
    fun thePauseKeyAndOtherKeysArePassedOnAsOnAnyScreen() {
        val calibration = keys()
        // Escape, the Pause key by default, goes on to be Back; Tab, the arrows, Enter and Space move focus or press.
        for (code in listOf(
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_A,
        )) {
            assertEquals("$code", Action.PASS_ON to Action.PASS_ON, calibration.tap(code))
            assertEquals("$code paused", Action.PASS_ON, calibration.handle(down(code), lane, paused = true))
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun escapeOnALanePresses() {
        val calibration = keys(KeyMap.DEFAULT.bind(KeySlot.LANE_4, KeyTable.named("ESCAPE")!!).map)
        assertEquals(Action.TAKE to Action.TAKE, calibration.tap(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(listOf(lane to true, lane to false), sent.map { it.lane to it.press })
        assertEquals("Slash took Pause by the swap, and is passed on", Action.PASS_ON to Action.PASS_ON, calibration.tap(KeyEvent.KEYCODE_SLASH))
    }

    @Test
    fun injectedKeysPressAndAGamepadsArePassedOn() {
        val calibration = keys()
        assertEquals(Action.PASS_ON, calibration.handle(down(KeyEvent.KEYCODE_Z, device = GAMEPAD), lane, paused = false))
        assertTrue(sent.isEmpty())
        val virtual = KeyCharacterMap.VIRTUAL_KEYBOARD
        assertEquals(Action.TAKE, calibration.handle(down(KeyEvent.KEYCODE_Z, device = virtual), lane, paused = false))
        assertEquals(Action.TAKE, calibration.handle(up(KeyEvent.KEYCODE_Z, device = virtual), lane, paused = false))
        assertEquals(listOf(lane to true, lane to false), sent.map { it.lane to it.press })
    }

    @Test
    fun beforeTheFieldsGameExistsALaneKeyIsTakenAndPressesNothing() {
        val calibration = keys()
        assertEquals(Action.TAKE, calibration.handle(down(KeyEvent.KEYCODE_Z), lane, paused = false, canPress = false))
        assertEquals(Action.TAKE, calibration.handle(up(KeyEvent.KEYCODE_Z), lane, paused = false, canPress = false))
        assertTrue(sent.isEmpty())
    }

    private companion object {
        const val KEYBOARD = 4
        const val GAMEPAD = 7
        const val MS = 1_000_000L
    }
}
