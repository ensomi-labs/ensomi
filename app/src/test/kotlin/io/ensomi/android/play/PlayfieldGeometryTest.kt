package io.ensomi.android.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayfieldGeometryTest {
    // A 1080 × 2424 phone at 2.625 px per dp, with a 24 dp gesture area and no cutout.
    private val phone = PlayfieldGeometry.of(1080f, 2424f, density = 2.625f, topInsetPx = 0f, bottomInsetPx = 63f)

    // The same phone turned to landscape, its gesture area along the long bottom edge, the lanes leaning.
    private val sideways = PlayfieldGeometry.of(2424f, 1080f, density = 2.625f, topInsetPx = 0f, bottomInsetPx = 63f, farScale = 0.6f)

    @Test
    fun phoneLanesTakeTheWidthLessMargins() {
        assertFalse(phone.landscape)
        assertEquals(1080f - 2 * 8 * 2.625f, phone.lineWidth, 0.01f)
        assertEquals((1080f - phone.lineWidth) / 2, phone.lineLeft, 0.01f)
    }

    @Test
    fun tallSurfacesKeepTheFieldNarrowAndCentred() {
        val tablet = PlayfieldGeometry.of(1600f, 2560f, density = 2f, topInsetPx = 0f, bottomInsetPx = 0f)
        assertEquals(440f * 2, tablet.lineWidth, 0.01f)
        assertEquals((1600f - 880f) / 2, tablet.lineLeft, 0.01f)
    }

    @Test
    fun landscapeHasWiderLanesNoHudRowAndALowerTray() {
        assertTrue(sideways.landscape)
        assertEquals(2424f * 0.64f, sideways.lineWidth, 0.01f)
        val tablet = PlayfieldGeometry.of(2560f, 1600f, density = 2f, topInsetPx = 0f, bottomInsetPx = 0f)
        assertEquals(640f * 2, tablet.lineWidth, 0.01f)
        assertEquals((2424f - sideways.lineWidth) / 2, sideways.lineLeft, 0.01f)
        assertEquals(6 * 2.625f, sideways.fieldTop, 0.01f)
        assertEquals(1080f - 63f, sideways.trayBottom, 0.01f)
        assertEquals(sideways.trayBottom - 64 * 2.625f, sideways.lineY, 0.01f)
    }

    @Test
    fun withoutLeanLaneSpaceIsTheScreen() {
        assertFalse(phone.leans)
        assertEquals(phone.fieldTop, phone.laneTop, 0.01f)
        assertEquals(phone.screenFall, phone.fallLength, 0.01f)
        val point = phone.project(300f, 900f)
        assertEquals(300f, point[0], 0.01f)
        assertEquals(900f, point[1], 0.01f)
    }

    @Test
    fun theLineIsDrawnAtFullSizeAndTheFarEndNarrower() {
        val g = sideways
        assertTrue(g.leans)
        assertEquals(g.screenFall / 0.6f, g.fallLength, 0.01f)
        val left = g.project(g.lineLeft, g.lineY)
        val right = g.project(g.lineLeft + g.lineWidth, g.lineY)
        assertEquals(g.lineLeft, left[0], 0.01f)
        assertEquals(g.lineY, left[1], 0.01f)
        assertEquals(g.lineLeft + g.lineWidth, right[0], 0.01f)
        val farLeft = g.project(g.lineLeft, g.laneTop)
        val farRight = g.project(g.lineLeft + g.lineWidth, g.laneTop)
        assertEquals(g.fieldTop, farLeft[1], 0.01f)
        assertEquals(g.farLeft, farLeft[0], 0.01f)
        assertEquals(g.lineWidth * 0.6f, farRight[0] - farLeft[0], 0.01f)
        // The middle of the lanes stays upright.
        assertEquals(g.centerX, g.project(g.centerX, g.laneTop + 100f)[0], 0.01f)
    }

    @Test
    fun aLeaningNoteSlowsInTheDistanceAndQuickensNearTheLine() {
        val g = sideways
        val halfway = g.project(g.centerX, g.lineY - g.fallLength / 2)[1]
        // Halfway along the lanes, and so halfway through the scroll time, a note is more than halfway up the screen.
        assertTrue(g.lineY - halfway > g.screenFall / 2)
        // Near the line a lane-space pixel is a screen pixel; at the far end it is farScale squared of one.
        val nearStep = g.project(g.centerX, g.lineY - 1f)[1] - g.project(g.centerX, g.lineY)[1]
        val farStep = g.project(g.centerX, g.laneTop)[1] - g.project(g.centerX, g.laneTop + 1f)[1]
        assertEquals(-1f, nearStep, 0.01f)
        assertEquals(-0.36f, farStep, 0.01f)
    }

    @Test
    fun notesKeepTheirShapeAsTheyRecede() {
        val g = sideways
        assertEquals(1f, g.keepShape(g.lineY), 1e-4f)
        assertEquals(1f / 0.6f, g.keepShape(g.laneTop), 1e-4f)
        assertEquals(1f, phone.keepShape(phone.fieldTop), 1e-4f)
    }

    @Test
    fun laneYUndoesTheProjection() {
        val g = sideways
        for (y in listOf(g.lineY, g.lineY - 200f, g.laneTop + 50f, g.laneTop)) {
            assertEquals(y, g.laneY(g.project(g.centerX, y)[1]), 0.5f)
        }
    }

    @Test
    fun leanNeverMovesTheTouchZonesOrTheLine() {
        val flat = PlayfieldGeometry.of(2424f, 1080f, density = 2.625f, topInsetPx = 0f, bottomInsetPx = 63f, farScale = 1f)
        assertEquals(flat.lineY, sideways.lineY, 0f)
        for (x in listOf(0f, 700f, 1000f, 1300f, 1600f, 1800f, 2424f)) {
            assertEquals(flat.laneAt(x), sideways.laneAt(x))
        }
    }

    @Test
    fun leanIsLimited() {
        val extreme = PlayfieldGeometry.of(2424f, 1080f, density = 2.625f, topInsetPx = 0f, bottomInsetPx = 0f, farScale = 0.01f)
        assertEquals(PlayfieldGeometry.MIN_FAR_SCALE, extreme.farScale, 0f)
    }

    @Test
    fun trayStaysAboveTheGestureArea() {
        assertEquals(2424f - 63f, phone.trayBottom, 0.01f)
        assertEquals(phone.trayBottom - 88 * 2.625f, phone.lineY, 0.01f)
        val noGestureArea = PlayfieldGeometry.of(1080f, 2424f, density = 2.625f, topInsetPx = 0f, bottomInsetPx = 0f)
        assertEquals(2424f - 16 * 2.625f, noGestureArea.trayBottom, 0.01f)
    }

    @Test
    fun hudRowSitsBelowTheCutout() {
        val withCutout = PlayfieldGeometry.of(1080f, 2424f, density = 2.625f, topInsetPx = 120f, bottomInsetPx = 0f)
        assertEquals(120f + 64 * 2.625f, withCutout.fieldTop, 0.01f)
    }

    @Test
    fun touchesMapToLanesAndClampBesideTheField() {
        val lane = phone.laneWidth
        assertEquals(0, phone.laneAt(phone.lineLeft + 1))
        assertEquals(1, phone.laneAt(phone.lineLeft + lane + 1))
        assertEquals(2, phone.laneAt(phone.lineLeft + 2 * lane + 1))
        assertEquals(3, phone.laneAt(phone.lineLeft + phone.lineWidth - 1))
        assertEquals(0, phone.laneAt(0f))
        assertEquals(3, phone.laneAt(1080f))
    }

    @Test
    fun aLaneIsPressedFromItsFirstPointerToItsLast() {
        val tracker = LaneTouchTracker()
        assertTrue(tracker.down(pointerId = 0, lane = 1))
        assertFalse("A second finger on the same lane sends no press", tracker.down(pointerId = 1, lane = 1))
        assertNull("The lane is still held", tracker.up(0))
        assertEquals(1, tracker.up(1))
        assertNull("An unknown pointer releases nothing", tracker.up(7))
    }

    @Test
    fun chordsPressEachLaneAndCancelReleasesThemAll() {
        val tracker = LaneTouchTracker()
        assertTrue(tracker.down(0, 0))
        assertTrue(tracker.down(1, 3))
        assertTrue(tracker.down(2, 2))
        assertEquals(listOf(0, 2, 3), tracker.cancel())
        assertFalse(tracker.isPressed(0))
        assertNull(tracker.up(1))
    }

    @Test
    fun aPointerStaysInTheLaneItWentDownIn() {
        val tracker = LaneTouchTracker()
        assertTrue(tracker.down(5, 2))
        assertFalse("The same pointer cannot go down twice", tracker.down(5, 3))
        assertEquals(2, tracker.up(5))
    }
}
