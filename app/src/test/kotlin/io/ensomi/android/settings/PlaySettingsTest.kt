package io.ensomi.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySettingsTest {
    @Test
    fun scrollSpeedUsesTheSwiftAppsScale() {
        assertEquals(459.4, PlaySettings().scrollTimeMs, 1e-9)
        assertEquals(11_485.0, PlaySettings.scrollTimeMs(1.0), 1e-9)
    }

    @Test
    fun phonesPlayInLandscapeWithTheLanesLeaningAndPortraitFlat() {
        val settings = PlaySettings()
        assertEquals(PlayOrientation.LANDSCAPE, settings.orientation)
        assertEquals(40, settings.lean)
        assertEquals(0, settings.lean(PlayOrientation.PORTRAIT))
        assertEquals(0, settings.copy(orientation = PlayOrientation.PORTRAIT).lean)
    }

    @Test
    fun eachOrientationKeepsItsOwnLean() {
        val settings = PlaySettings().withLean(PlayOrientation.PORTRAIT, 20)
        assertEquals(20, settings.portraitLean)
        assertEquals(40, settings.landscapeLean)
    }

    @Test
    fun leanNarrowsTheFarEndLikeATiltedStrip() {
        assertEquals(1f, PlaySettings.farScale(0), 0f)
        assertEquals(0.30f, PlaySettings.farScale(40), 0.005f)
        assertEquals(0.20f, PlaySettings.farScale(55), 0.005f)
        assertEquals(PlaySettings.farScale(55), PlaySettings.farScale(90), 0f)
        // More lean always narrows it more.
        assertTrue((1..55).all { PlaySettings.farScale(it) < PlaySettings.farScale(it - 1) })
    }

    @Test
    fun leanSnapsToWholeDegrees() {
        assertEquals(0, PlaySettings.snapLean(0.4f))
        assertEquals(41, PlaySettings.snapLean(40.6f))
        assertEquals(55, PlaySettings.snapLean(1000f))
    }

    @Test
    fun offsetsShowTheirSign() {
        assertEquals("+12 ms", PlaySettings.formatOffset(12))
        assertEquals("0 ms", PlaySettings.formatOffset(0))
        assertEquals("−15 ms", PlaySettings.formatOffset(-15))
    }
}
