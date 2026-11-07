package io.ensomi.android.settings

import android.view.KeyEvent
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMapTest {
    private fun key(name: String) = KeyTable.named(name)!!

    private fun KeyMap.names() = KeySlot.entries.map { this[it]?.name }

    @Test
    fun theDefaultsAreZXPeriodSlashAndEscape() {
        val map = KeyMap.DEFAULT
        assertEquals(listOf("Z", "X", "PERIOD", "SLASH", "ESCAPE"), map.names())
        assertEquals(
            listOf(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_ESCAPE),
            KeySlot.entries.map { map[it]?.keyCode },
        )
        assertTrue(map.isComplete)
        assertEquals(KeyMap.DEFAULT, PlaySettings().keys)
    }

    @Test
    fun aKeyInAnotherSlotSwapsTheTwoAndEndsTheWait() {
        val bind = KeyMap.DEFAULT.bind(KeySlot.LANE_1, key("X"))
        assertEquals(listOf("X", "Z", "PERIOD", "SLASH", "ESCAPE"), bind.map.names())
        assertEquals(KeySlot.LANE_2, bind.swapped)
        assertNull("A swap ends the wait, so the player sees what moved", bind.next)
    }

    @Test
    fun pauseSwapsLikeAnyOtherSlot() {
        val toLane = KeyMap.DEFAULT.bind(KeySlot.LANE_4, key("ESCAPE"))
        assertEquals(listOf("Z", "X", "PERIOD", "ESCAPE", "SLASH"), toLane.map.names())
        assertEquals(KeySlot.PAUSE, toLane.swapped)
        val toPause = KeyMap.DEFAULT.bind(KeySlot.PAUSE, key("Z"))
        assertEquals(listOf("ESCAPE", "X", "PERIOD", "SLASH", "Z"), toPause.map.names())
        assertEquals(KeySlot.LANE_1, toPause.swapped)
    }

    @Test
    fun aSwapWithAnEmptySlotEmptiesTheOther() {
        val bind = KeyMap.DEFAULT.clear(KeySlot.PAUSE).bind(KeySlot.PAUSE, key("SLASH"))
        assertEquals(listOf("Z", "X", "PERIOD", null, "SLASH"), bind.map.names())
        assertEquals(KeySlot.LANE_4, bind.swapped)
        assertEquals(listOf(KeySlot.LANE_4), bind.map.emptyLanes)
    }

    @Test
    fun clearEmptiesASlot() {
        assertEquals(listOf("Z", "X", null, "SLASH", "ESCAPE"), KeyMap.DEFAULT.clear(KeySlot.LANE_3).names())
        assertEquals(listOf("Z", "X", "PERIOD", "SLASH", null), KeyMap.DEFAULT.clear(KeySlot.PAUSE).names())
    }

    @Test
    fun aBindThatSwappedNothingMovesTheWaitToTheNextLaneAndNeverToPause() {
        var map = KeyMap.DEFAULT
        var waiting: KeySlot? = KeySlot.LANE_1
        val waited = ArrayList<KeySlot>()
        for (name in listOf("A", "S", "K", "L", "Q")) {
            val slot = waiting ?: break
            waited += slot
            val bind = map.bind(slot, key(name))
            assertNull(bind.swapped)
            map = bind.map
            waiting = bind.next
        }
        assertEquals("Four presses set four lanes, and the wait ends after lane 4", KeySlot.LANES, waited)
        assertEquals(listOf("A", "S", "K", "L", "ESCAPE"), map.names())
        assertNull("Pause's bind ends the wait", KeyMap.DEFAULT.bind(KeySlot.PAUSE, key("P")).next)
        assertEquals(KeySlot.LANE_4, KeyMap.DEFAULT.bind(KeySlot.LANE_3, key("M")).next)
    }

    @Test
    fun aSlotsOwnKeyAgainChangesNothingAndMovesOn() {
        val bind = KeyMap.DEFAULT.bind(KeySlot.LANE_2, key("X"))
        assertEquals(KeyMap.DEFAULT, bind.map)
        assertNull(bind.swapped)
        assertEquals(KeySlot.LANE_3, bind.next)
    }

    @Test
    fun escapeBindsToAnySlot() {
        for (slot in KeySlot.entries) {
            val map = KeyMap.DEFAULT.clear(KeySlot.PAUSE).bind(slot, key("ESCAPE")).map
            assertEquals(slot, map.slotOf(key("ESCAPE")))
        }
    }

    @Test
    fun theTableHoldsLettersDigitsPunctuationSpaceArrowsAndEscape() {
        val names = KeyTable.keys.map { it.name }
        assertEquals(26 + 10 + 11 + 1 + 4 + 1, names.size)
        assertEquals(names.size, names.toSet().size)
        assertEquals(names.size, KeyTable.keys.map { it.keyCode }.toSet().size)
        assertEquals(KeyEvent.KEYCODE_A, key("A").keyCode)
        assertEquals(KeyEvent.KEYCODE_7, key("7").keyCode)
        assertEquals(KeyEvent.KEYCODE_APOSTROPHE, key("APOSTROPHE").keyCode)
        assertEquals("[", key("LEFT_BRACKET").label)
        assertEquals("←", key("DPAD_LEFT").label)
        assertNull("Space is named in words by the screens", key("SPACE").label)
        assertNull(KeyTable.named("ENTER"))
    }

    @Test
    fun keysOutsideTheTableAreRefused() {
        val refused = listOf(
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_NUM_LOCK,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_NUMPAD_DOT, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_UNKNOWN,
        )
        for (code in refused) assertNull("$code", KeyTable.producing(code) { it })
    }

    @Test
    fun aPressedKeyBindsByItsPlace() {
        val azerty: (Int) -> Int = { place ->
            when (place) {
                KeyEvent.KEYCODE_Z -> KeyEvent.KEYCODE_W
                KeyEvent.KEYCODE_W -> KeyEvent.KEYCODE_Z
                else -> place
            }
        }
        assertEquals("W on AZERTY is at the Z place", key("Z"), KeyTable.producing(KeyEvent.KEYCODE_W, azerty))
        assertEquals(key("W"), KeyTable.producing(KeyEvent.KEYCODE_Z, azerty))
        assertEquals(key("PERIOD"), KeyTable.producing(KeyEvent.KEYCODE_PERIOD) { KeyEvent.KEYCODE_UNKNOWN })
    }

    @Test
    fun theMapIsCompleteWithFourLanesAndNamesTheEmptyOnes() {
        assertTrue("Pause may be empty", KeyMap.DEFAULT.clear(KeySlot.PAUSE).isComplete)
        val two = KeyMap.DEFAULT.clear(KeySlot.LANE_3).clear(KeySlot.LANE_2)
        assertFalse(two.isComplete)
        assertEquals(listOf(KeySlot.LANE_2, KeySlot.LANE_3), two.emptyLanes)
        assertEquals(emptyList<KeySlot>(), KeyMap.DEFAULT.emptyLanes)
    }

    @Test
    fun aMapNeverHoldsOneKeyTwice() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyMap(listOf(key("Z"), key("Z"), key("PERIOD"), key("SLASH")), key("ESCAPE"))
        }
        assertThrows(IllegalArgumentException::class.java) { KeyMap(listOf(key("Z")), null) }
    }

    @Test
    fun aFileWithoutKeysReadsTheDefaultMapAndKeepsItsOtherSettings() {
        // As the 0.1.3 build writes it.
        val file = """{"scrollSpeed":31.5,"judge":"A","musicVolume":0.35,"audioOffsets":{"speaker/Pixel 9":-7},""" +
            """"visualOffsetMs":9,"orientation":"PORTRAIT","landscapeLean":22,"portraitLean":10,""" +
            """"landscapeWidth":0.98,"portraitWidth":null}"""
        val stored = runBlocking { StoredSettingsSerializer.readFrom(file.byteInputStream()) }
            .migrated(currentKey = "speaker/Pixel 9", nameOf = { "Phone speaker" })
        assertEquals(StoredKeys(), stored.keys)
        val read = stored.toPlaySettings()
        assertEquals(KeyMap.DEFAULT, read.keys)
        assertEquals(
            PlaySettings(31.5, JudgeDifficulty.A, 0.35f, PlayOrientation.PORTRAIT, 22, 10, 0.98f, null),
            read.copy(audioLatency = AudioLatency(), inputLatency = InputLatency()),
        )
        // Written again, the file keeps every field it had but the offsets, which the slots took, and gains the map.
        val written = ByteArrayOutputStream().also { runBlocking { StoredSettingsSerializer.writeTo(stored, it) } }.toString()
        for (field in listOf(""""scrollSpeed":31.5""", """"landscapeLean":22""", """"landscapeWidth":0.98""")) {
            assertTrue(field, field in written)
        }
        assertTrue(written, """"keys":{"lanes":["Z","X","PERIOD","SLASH"],"pause":"ESCAPE"}""" in written)
        assertFalse(written, "audioOffsets" in written || "visualOffsetMs" in written)
    }

    @Test
    fun anUnknownNameATwiceNamedKeyOrThreeLanesReadTheDefaultMap() {
        val maps = listOf(
            """{"lanes":["Z","X","PERIOD","F13"],"pause":"ESCAPE"}""",
            """{"lanes":["Z","X","PERIOD","SLASH"],"pause":"enter"}""",
            """{"lanes":["Z","X","Z","SLASH"],"pause":"ESCAPE"}""",
            """{"lanes":["A","S","K","L"],"pause":"A"}""",
            """{"lanes":["A","S","K"],"pause":"P"}""",
            """{"lanes":["A","S","K","L","M"],"pause":"P"}""",
        )
        for (keys in maps) {
            val read = StoredSettings.JSON.decodeFromString(StoredSettings.serializer(), """{"judge":"B","keys":$keys}""")
            assertEquals(keys, KeyMap.DEFAULT, read.toPlaySettings().keys)
            assertEquals(JudgeDifficulty.B, read.judge)
        }
    }

    @Test
    fun anEmptySlotReadsBackEmptyAndARoundTripKeepsTheOrder() {
        val map = KeyMap(listOf(key("A"), null, key("DPAD_LEFT"), key("SPACE")), null)
        val stored = StoredSettings().with(PlaySettings(keys = map))
        val text = StoredSettings.JSON.encodeToString(StoredSettings.serializer(), stored)
        assertTrue(text, """"keys":{"lanes":["A",null,"DPAD_LEFT","SPACE"],"pause":null}""" in text)
        val read = StoredSettings.JSON.decodeFromString(StoredSettings.serializer(), text).toPlaySettings()
        assertEquals(map, read.keys)
        assertEquals(listOf(KeySlot.LANE_2), read.keys.emptyLanes)
    }
}
