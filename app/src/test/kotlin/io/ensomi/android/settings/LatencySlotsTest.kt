package io.ensomi.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Settings › Sound and timing does with the latency slots (latency.md, Settings and Naming a slot): the active
 * audio slot chosen by hand, each method's input slot, the values in 1 ms steps within the range, and optional names
 * of at most 24 characters, kept through the settings file.
 */
class LatencySlotsTest {
    @Test
    fun theActiveAudioSlotIsChosenByHandAndItsValueIsTheOneTheStepsMove() {
        val audio = AudioLatency().withActive(2).withActiveMs(120)
        assertEquals(2, audio.active)
        assertEquals(120, audio.activeMs)
        assertEquals(listOf(0, 0, 120), audio.slots.map { it.ms })
        assertEquals("Choosing another slot moves no value", listOf(0, 0, 120), audio.withActive(0).slots.map { it.ms })
    }

    @Test
    fun stepsStopAtTheEndsOfTheRange() {
        val audio = AudioLatency().withActiveMs(300)
        assertEquals(300, audio.withActiveMs(audio.activeMs + 1).activeMs)
        val input = InputLatency().withMs(0, -300)
        assertEquals(-300, input.withMs(0, input.touchMs - 1).touchMs)
    }

    @Test
    fun eachMethodUsesTheSlotChosenForItAndMethodsOnOneSlotChangeTogether() {
        val input = InputLatency().withMs(1, 30).withKeyboard(2).withTouch(1)
        assertEquals(1, input.touch)
        assertEquals(2, input.keyboard)
        assertEquals(30, input.touchMs)
        assertEquals(0, input.keyboardMs)
        val shared = input.withKeyboard(1)
        assertEquals(30, shared.keyboardMs)
        val moved = shared.withMs(shared.keyboard, shared.keyboardMs + 1)
        assertEquals("One slot, one value", 31, moved.touchMs)
        assertEquals(31, moved.keyboardMs)
    }

    @Test
    fun namesAreOptionalTrimmedAndCut() {
        val audio = AudioLatency().withName(1, "  WH-1000XM4 ")
        assertEquals(listOf("", "WH-1000XM4", ""), audio.slots.map { it.name })
        assertEquals("Saved empty, the slot has no name", "", audio.withName(1, "   ").slots[1].name)
        val long = "Living room soundbar HD2 and more"
        assertEquals("Living room soundbar HD2", InputLatency().withName(0, long).slots[0].name)
        assertEquals("A name changes no value", 0, InputLatency().withName(0, long).touchMs)
    }

    @Test
    fun aNameAsTypedFitsByCharactersAsPeopleCountThem() {
        assertTrue(Latency.fitsName("a".repeat(24)))
        assertFalse(Latency.fitsName("a".repeat(25)))
        // 24 emoji are 48 UTF-16 units and 24 characters.
        val buds = "🎧".repeat(24)
        assertTrue(Latency.fitsName(buds))
        assertFalse(Latency.fitsName(buds + "x"))
        assertEquals(buds, Latency.name(buds))
    }

    @Test
    fun slotsMethodsAndNamesAreKeptThroughTheSettingsFile() {
        val settings = PlaySettings(
            audioLatency = AudioLatency().withName(0, "Phone speaker").withActive(2).withActiveMs(-8),
            inputLatency = InputLatency().withName(1, "K380").withMs(1, 30).withTouch(2).withKeyboard(1),
        )
        val stored = StoredSettings().with(settings)
        val text = StoredSettings.JSON.encodeToString(StoredSettings.serializer(), stored)
        val read = StoredSettings.JSON.decodeFromString(StoredSettings.serializer(), text).toPlaySettings()
        assertEquals(settings.audioLatency, read.audioLatency)
        assertEquals(settings.inputLatency, read.inputLatency)
    }
}
