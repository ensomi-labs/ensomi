package io.ensomi.android.keyboard

import android.view.KeyEvent
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.KeyTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * When keyboards are resolved (keyboard.md, Key identity and the map): at connect, at a change and at every start, and
 * when the map changes; never per key. Each ask stands for the binder calls `getKeyCodeForKeyLocation` makes.
 */
class KeyboardRosterTest {
    private val qwerty: (Int) -> Int = { it }

    /** French AZERTY's swaps of the Z and W places, as `getKeyCodeForKeyLocation` gives them. */
    private val azerty: (Int) -> Int = { place ->
        when (place) {
            KeyEvent.KEYCODE_Z -> KeyEvent.KEYCODE_W
            KeyEvent.KEYCODE_W -> KeyEvent.KEYCODE_Z
            else -> place
        }
    }

    /** The devices the system has: their layouts; any other device is no keyboard (a gamepad, the power button). */
    private val layouts = mutableMapOf(US to qwerty, FRENCH to azerty)
    private val asked = ArrayList<Int>()
    private val maps = ArrayList<KeyMap>()

    private fun describe(deviceId: Int, map: KeyMap): Keyboard? {
        asked += deviceId
        maps += map
        val layout = layouts[deviceId] ?: return null
        val keys = map.resolve(layout)
        val labels = KeyLabels.of(keys, { code -> labelOf(code) }, "Space", "Esc")
        return Keyboard(deviceId, "Keyboard $deviceId", 0x046d, deviceId, external = true, keys = keys, labels = labels)
    }

    private fun labelOf(code: Int): Char = KeyTable.keys.firstOrNull { it.keyCode == code }?.label?.singleOrNull() ?: KeyLabels.NONE

    private val roster = KeyboardRoster(::describe)

    private fun ids() = roster.keyboards.value.map { it.deviceId }

    @Test
    fun aKeyboardIsResolvedWhenItConnectsAndNeverPerKey() {
        roster.added(US)
        assertEquals(listOf(US), asked)
        repeat(100) {
            assertEquals(KeySlot.LANE_1, roster.keys(US)?.slotOf(KeyEvent.KEYCODE_Z))
            assertEquals(KeySlot.PAUSE, roster.keys(US)?.slotOf(KeyEvent.KEYCODE_ESCAPE))
        }
        assertEquals("Keys read what connecting resolved", listOf(US), asked)
        assertEquals(listOf(US), ids())
    }

    @Test
    fun aChangeResolvesAgainInPlace() {
        roster.added(US)
        roster.added(FRENCH)
        layouts[US] = azerty
        roster.changed(US)
        assertEquals(listOf(US, FRENCH, US), asked)
        assertEquals("A changed keyboard keeps its place", listOf(US, FRENCH), ids())
        assertEquals(KeySlot.LANE_1, roster.keys(US)?.slotOf(KeyEvent.KEYCODE_W))
        assertEquals("W", roster.keyboards.value.first().laneLabels.first())
    }

    @Test
    fun everyStartResolvesEveryDeviceAgainInTheOrderTheyConnected() {
        roster.added(FRENCH)
        roster.added(US)
        asked.clear()
        roster.started(listOf(GAMEPAD, US, FRENCH, OTHER_KEYBOARD))
        layouts[OTHER_KEYBOARD] = qwerty
        roster.started(listOf(GAMEPAD, US, FRENCH, OTHER_KEYBOARD))
        assertEquals(
            "Each start asks about every device the system lists, the keyboards it knows first",
            listOf(FRENCH, US, GAMEPAD, OTHER_KEYBOARD, FRENCH, US, GAMEPAD, OTHER_KEYBOARD),
            asked,
        )
        assertEquals("The first connected labels the caps; one new since comes after", listOf(FRENCH, US, OTHER_KEYBOARD), ids())
        roster.started(listOf(US))
        assertEquals("A keyboard the system no longer lists is gone", listOf(US), ids())
    }

    @Test
    fun aDeviceThatIsNoKeyboardIsAskedAboutOnceUntilItChanges() {
        assertNull(roster.keys(GAMEPAD))
        assertNull(roster.keys(GAMEPAD))
        roster.added(GAMEPAD)
        assertNull(roster.keys(GAMEPAD))
        assertEquals(listOf(GAMEPAD, GAMEPAD), asked)
        roster.changed(GAMEPAD)
        assertEquals(3, asked.size)
        assertEquals(emptyList<Int>(), ids())
    }

    @Test
    fun aKeyFromAKeyboardNotHeardOfYetResolvesItOnce() {
        // The key can come before the input system's own word of the device.
        assertEquals(KeySlot.LANE_2, roster.keys(US)?.slotOf(KeyEvent.KEYCODE_X))
        assertEquals(KeySlot.LANE_2, roster.keys(US)?.slotOf(KeyEvent.KEYCODE_X))
        assertEquals(listOf(US), asked)
        roster.added(US)
        assertEquals(listOf(US, US), asked)
        assertEquals("Listed once", listOf(US), ids())
    }

    @Test
    fun aKeyboardRemovedLeavesTheListAndIsNoKeyboardAfter() {
        roster.added(US)
        roster.added(FRENCH)
        layouts.remove(US)
        roster.removed(US)
        assertEquals(listOf(FRENCH), ids())
        assertNull("A canceled up from it finds no keys", roster.keys(US))
        assertNull(roster.keys(US))
        assertEquals(listOf(US, FRENCH, US), asked)
    }

    @Test
    fun aNewMapResolvesEveryKeyboardAgainAndTheSameMapNothing() {
        roster.added(US)
        val pauseOnP = KeyMap.DEFAULT.bind(KeySlot.PAUSE, KeyTable.named("P")!!).map
        roster.mapChanged(pauseOnP)
        assertEquals(listOf(US, US), asked)
        assertSame(pauseOnP, maps.last())
        assertEquals(KeySlot.PAUSE, roster.keys(US)?.slotOf(KeyEvent.KEYCODE_P))
        assertNull(roster.keys(US)?.slotOf(KeyEvent.KEYCODE_ESCAPE))
        roster.mapChanged(pauseOnP)
        assertEquals(2, asked.size)
        roster.added(FRENCH)
        assertSame("A keyboard connecting later resolves the map now", pauseOnP, maps.last())
    }

    @Test
    fun aStartThatResolvesTheSameKeysChangesNothingTheScreensSee() {
        roster.added(US)
        val before = roster.keyboards.value
        roster.started(listOf(US))
        assertEquals(before, roster.keyboards.value)
    }

    @Test
    fun labelsFollowTheLayoutWithSpaceAndEscapeInWords() {
        assertEquals("Z", KeyLabels.of(KeyEvent.KEYCODE_Z, 'Z', "Space", "Esc"))
        assertEquals("The Z place on AZERTY", "W", KeyLabels.of(KeyEvent.KEYCODE_W, 'W', "Space", "Esc"))
        assertEquals("Space", KeyLabels.of(KeyEvent.KEYCODE_SPACE, ' ', "Space", "Esc"))
        assertEquals("Esc", KeyLabels.of(KeyEvent.KEYCODE_ESCAPE, KeyLabels.NONE, "Space", "Esc"))
        assertEquals("Arrows have no display label", "←", KeyLabels.of(KeyEvent.KEYCODE_DPAD_LEFT, KeyLabels.NONE, "Space", "Esc"))
        assertEquals("A key map without a label falls back on the table", "/", KeyLabels.of(KeyEvent.KEYCODE_SLASH, KeyLabels.NONE, "S", "E"))
        assertNull("Nothing names it: a cap without a label", KeyLabels.of(KeyEvent.KEYCODE_F1, KeyLabels.NONE, "S", "E"))
        val keys = KeyMap.DEFAULT.clear(KeySlot.LANE_3).resolve(qwerty)
        assertEquals(listOf("Z", "X", null, "/", "Esc"), KeyLabels.of(keys, ::labelOf, "Space", "Esc"))
    }

    private companion object {
        const val FRENCH = 3
        const val US = 4
        const val GAMEPAD = 7
        const val OTHER_KEYBOARD = 8
    }
}
