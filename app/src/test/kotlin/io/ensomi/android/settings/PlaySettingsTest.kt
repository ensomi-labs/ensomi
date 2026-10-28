package io.ensomi.android.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaySettingsTest {
    @Test
    fun scrollSpeedUsesTheSwiftAppsScale() {
        assertEquals(459.4, PlaySettings().scrollTimeMs, 1e-9)
        assertEquals(11_485.0, PlaySettings.scrollTimeMs(1.0), 1e-9)
    }

    @Test
    fun offsetsShowTheirSign() {
        assertEquals("+12 ms", PlaySettings.formatOffset(12))
        assertEquals("0 ms", PlaySettings.formatOffset(0))
        assertEquals("−15 ms", PlaySettings.formatOffset(-15))
    }
}
