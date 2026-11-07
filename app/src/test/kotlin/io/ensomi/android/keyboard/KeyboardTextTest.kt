package io.ensomi.android.keyboard

import android.view.KeyEvent
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.KeyTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What Settings › Keyboard and its Settings row say (keyboard.md, Settings › Keyboard), in the app's English words. */
class KeyboardTextTest {
    private val words = KeyboardWords(
        space = "Space",
        escape = "Esc",
        escapeName = "Escape",
        none = "None",
        rowLanes = "Lanes: %1\$s",
        rowPause = "Pause: %1\$s",
        rowConnected = "Connected: %1\$s",
        rowNoKeyboard = "No keyboard connected",
        rowNoLaneKey = "—",
        rowNoPauseKey = "none",
        separator = " · ",
        listComma = ", ",
        listAnd = "%1\$s and %2\$s",
        laneEmpty = "Lane %1\$s has no key.",
        lanesEmpty = "Lanes %1\$s have no key.",
        swapLanes = "%1\$s now plays lane %2\$s, and %3\$s lane %4\$s.",
        swapLaneAndPause = "%1\$s now plays lane %2\$s, and %3\$s pauses.",
        swapPauseAndLane = "%1\$s now pauses, and %2\$s plays lane %3\$s.",
        swapLaneAndEmptyLane = "%1\$s now plays lane %2\$s, and lane %3\$s has no key.",
        swapPauseAndEmptyLane = "%1\$s now pauses, and lane %2\$s has no key.",
        swapLaneAndEmptyPause = "%1\$s now plays lane %2\$s, and Pause has no key.",
        keyNames = mapOf(KeyEvent.KEYCODE_SHIFT_LEFT to "Shift", KeyEvent.KEYCODE_ENTER to "Enter"),
    )

    private fun key(name: String) = KeyTable.named(name)!!

    /** German QWERTZ at the places the default lanes use: Z gives Y, and the slash place the minus key. */
    private val qwertz: (Int) -> Int = { place ->
        when (place) {
            KeyEvent.KEYCODE_Z -> KeyEvent.KEYCODE_Y
            KeyEvent.KEYCODE_Y -> KeyEvent.KEYCODE_Z
            KeyEvent.KEYCODE_SLASH -> KeyEvent.KEYCODE_MINUS
            else -> place
        }
    }

    /** A keyboard as the input system would describe it with [map], its caps labelled from the key table. */
    private fun keyboard(name: String, map: KeyMap = KeyMap.DEFAULT, layout: (Int) -> Int = { it }): Keyboard {
        val keys = map.resolve(layout)
        val labels = KeyLabels.of(keys, { code -> KeyTable.keys.firstOrNull { it.keyCode == code }?.label?.singleOrNull() ?: KeyLabels.NONE }, "Space", "Esc")
        return Keyboard(1, name, 0x046d, 0xb342, external = true, keys = keys, labels = labels, map = map)
    }

    @Test
    fun theRowNamesTheLanesKeysPauseAndTheConnection() {
        assertEquals("Lanes: Z X . / · Pause: Esc · No keyboard connected", KeyboardText.row(KeyMap.DEFAULT, emptyList(), words))
        assertEquals(
            "Lanes: Z X . / · Pause: Esc · Connected: K380",
            KeyboardText.row(KeyMap.DEFAULT, listOf(keyboard("K380")), words),
        )
    }

    @Test
    fun theRowFollowsTheFirstKeyboardsLayoutAndListsEveryKeyboard() {
        val keyboards = listOf(keyboard("Tastatur", layout = qwertz), keyboard("K380"))
        assertEquals("Lanes: Y X . - · Pause: Esc · Connected: Tastatur, K380", KeyboardText.row(KeyMap.DEFAULT, keyboards, words))
    }

    @Test
    fun theRowShowsAnEmptyLaneAndAnEmptyPause() {
        val map = KeyMap.DEFAULT.clear(KeySlot.LANE_3).clear(KeySlot.PAUSE)
        assertEquals("Lanes: Z X — / · Pause: none · No keyboard connected", KeyboardText.row(map, emptyList(), words))
        assertEquals("Lane 3 has no key.", KeyboardText.emptyLanes(map, words))
    }

    @Test
    fun theNoticeNamesTheEmptyLanesAndNeverPause() {
        assertNull(KeyboardText.emptyLanes(KeyMap.DEFAULT, words))
        assertNull("An empty Pause is not marked", KeyboardText.emptyLanes(KeyMap.DEFAULT.clear(KeySlot.PAUSE), words))
        val two = KeyMap.DEFAULT.clear(KeySlot.LANE_2).clear(KeySlot.LANE_3)
        assertEquals("Lanes 2 and 3 have no key.", KeyboardText.emptyLanes(two, words))
        val all = KeySlot.LANES.fold(KeyMap.DEFAULT) { map, slot -> map.clear(slot) }
        assertEquals("Lanes 1, 2, 3 and 4 have no key.", KeyboardText.emptyLanes(all, words))
    }

    @Test
    fun aSwapSaysWhatMoved() {
        fun said(slot: KeySlot, name: String, map: KeyMap = KeyMap.DEFAULT, keyboard: Keyboard? = null): String {
            val bind = map.bind(slot, key(name))
            val note = CaptureNote.Swapped(slot, key(name), bind.swapped!!, map[slot])
            return KeyboardText.swapped(note, keyboard, words)
        }
        assertEquals("X now plays lane 1, and Z lane 2.", said(KeySlot.LANE_1, "X"))
        assertEquals("Escape now plays lane 4, and / pauses.", said(KeySlot.LANE_4, "ESCAPE"))
        assertEquals("/ now pauses, and Escape plays lane 4.", said(KeySlot.PAUSE, "SLASH"))
        val noPause = KeyMap.DEFAULT.clear(KeySlot.PAUSE)
        assertEquals("/ now pauses, and lane 4 has no key.", said(KeySlot.PAUSE, "SLASH", noPause))
        assertEquals("Z now plays lane 3, and lane 1 has no key.", said(KeySlot.LANE_3, "Z", KeyMap.DEFAULT.clear(KeySlot.LANE_3)))
        assertEquals("Escape now plays lane 2, and Pause has no key.", said(KeySlot.LANE_2, "ESCAPE", KeyMap.DEFAULT.clear(KeySlot.LANE_2)))
        assertEquals(
            "Its keys as the keyboard labels them",
            "Y now plays lane 2, and X lane 1.",
            said(KeySlot.LANE_2, "Z", keyboard = keyboard("Tastatur", layout = qwertz)),
        )
    }

    @Test
    fun capsTakeTheKeyboardsLabelsTheKeyTablesWithoutOneAndWordsForSpaceAndEscape() {
        val german = keyboard("Tastatur", layout = qwertz)
        assertEquals("Y", KeyboardText.cap(key("Z"), german, words))
        assertEquals("Z", KeyboardText.cap(key("Z"), null, words))
        assertEquals("Esc", KeyboardText.cap(key("ESCAPE"), null, words))
        assertEquals("Space", KeyboardText.cap(key("SPACE"), german, words))
        assertEquals("None", KeyboardText.cap(null, german, words))
        assertEquals("←", KeyboardText.cap(key("DPAD_LEFT"), null, words))
        // A key bound a moment ago, before the keyboard was resolved with the new map: the key table's label meanwhile.
        assertEquals("A", KeyboardText.cap(key("A"), german, words))
        assertEquals("Escape", KeyboardText.spoken(key("ESCAPE"), german, words))
        assertEquals("Y", KeyboardText.spoken(key("Z"), german, words))
    }

    @Test
    fun otherKeysAreNamedInWordsByTheirCharacterOrByTheKeyTable() {
        assertEquals("Shift", KeyboardText.otherKey(KeyEvent.KEYCODE_SHIFT_LEFT, KeyLabels.NONE, words))
        assertEquals("A", KeyboardText.otherKey(KeyEvent.KEYCODE_A, 'A', words))
        assertEquals("ä", KeyboardText.otherKey(KeyEvent.KEYCODE_APOSTROPHE, 'ä', words))
        assertEquals("↑", KeyboardText.otherKey(KeyEvent.KEYCODE_DPAD_UP, KeyLabels.NONE, words))
        assertEquals("Esc", KeyboardText.otherKey(KeyEvent.KEYCODE_ESCAPE, KeyLabels.NONE, words))
        assertNull(KeyboardText.otherKey(KeyEvent.KEYCODE_MEDIA_PLAY, KeyLabels.NONE, words))
    }

    @Test
    fun listsJoinWithAnd() {
        assertEquals("2", KeyboardText.list(listOf("2"), words))
        assertEquals("2 and 3", KeyboardText.list(listOf("2", "3"), words))
        assertEquals("1, 2 and 4", KeyboardText.list(listOf("1", "2", "4"), words))
    }
}
