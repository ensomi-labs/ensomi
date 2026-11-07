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
    fun eachOrientationKeepsItsOwnWidthAndCanReturnToTheWindowDefault() {
        val settings = PlaySettings().withLaneWidth(PlayOrientation.LANDSCAPE, 0.98f)
            .withLaneWidth(PlayOrientation.PORTRAIT, 0.90f)
        assertEquals(0.98f, settings.laneWidth(PlayOrientation.LANDSCAPE))
        assertEquals(0.90f, settings.laneWidth(PlayOrientation.PORTRAIT))
        val restored = settings.withLaneWidth(PlayOrientation.LANDSCAPE, null)
        assertEquals(null, restored.landscapeWidth)
        assertEquals(0.90f, restored.portraitWidth)
        val stored = StoredSettings().with(settings).toPlaySettings()
        assertEquals(settings, stored)
    }

    @Test
    fun anOldFileKeepsAllItsSettingsAndUsesTheWindowWidthDefault() {
        val file = """{"scrollSpeed":31.5,"landscapeLean":22,"portraitLean":10,"musicVolume":0.35}"""
        val read = StoredSettings.JSON.decodeFromString(StoredSettings.serializer(), file).toPlaySettings()
        assertEquals(31.5, read.scrollSpeed, 0.0)
        assertEquals(22, read.landscapeLean)
        assertEquals(10, read.portraitLean)
        assertEquals(0.35f, read.musicVolume, 0f)
        assertEquals(null, read.landscapeWidth)
        assertEquals(null, read.portraitWidth)
    }

    @Test
    fun widthFromAnotherFileIsBoundedAndInvalidValuesUseTheDefault() {
        val read = StoredSettings(landscapeWidth = -1f, portraitWidth = 4f).toPlaySettings()
        assertEquals(0f, read.landscapeWidth)
        assertEquals(1f, read.portraitWidth)
        assertEquals(null, StoredSettings(landscapeWidth = Float.NaN).toPlaySettings().landscapeWidth)
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
