package io.ensomi.android.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRulesTest {
    @Test
    fun `scroll speed stays inside the range S5 gives`() {
        assertEquals(1.0f, SettingsRules.clampScrollSpeed(0.2f))
        assertEquals(40.0f, SettingsRules.clampScrollSpeed(64f))
        assertEquals(12.5f, SettingsRules.clampScrollSpeed(12.5f))
    }

    @Test
    fun `scroll speed snaps to the tenth the control shows`() {
        assertEquals(8.3f, SettingsRules.snapScrollSpeed(8.34f))
        assertEquals(8.4f, SettingsRules.snapScrollSpeed(8.36f))
        assertEquals(40.0f, SettingsRules.snapScrollSpeed(41.2f))
    }

    @Test
    fun `volume is a fraction of the song's level`() {
        assertEquals(0.0f, SettingsRules.clampVolume(-1f))
        assertEquals(1.0f, SettingsRules.clampVolume(3f))
        assertEquals(0.5f, SettingsRules.clampVolume(0.5f))
    }

    @Test
    fun `a difficulty the core does not name falls back instead of reaching it`() {
        assertEquals("A", SettingsRules.difficultyOrFallback("A"))
        assertEquals("E", SettingsRules.difficultyOrFallback("E"))
        assertEquals(SettingsKeys.DEFAULT_DIFFICULTY, SettingsRules.difficultyOrFallback("Z"))
        assertEquals(SettingsKeys.DEFAULT_DIFFICULTY, SettingsRules.difficultyOrFallback(null))
    }

    @Test
    fun `the keys keep the Apple clients' spelling`() {
        assertEquals("mania4k", SettingsKeys.STORE)
        assertEquals("mania4k.scrollSpeed", SettingsKeys.SCROLL_SPEED)
        assertEquals("mania4k.musicVolume", SettingsKeys.MUSIC_VOLUME)
    }
}
