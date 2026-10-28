package io.ensomi.android.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayfieldGeometryTest {
    // A 1080 × 2424 phone at 2.625 px per dp, with a 24 dp gesture area and no cutout.
    private val phone = PlayfieldGeometry.of(1080f, 2424f, density = 2.625f, topInsetPx = 0f, bottomInsetPx = 63f)

    @Test
    fun phoneLanesTakeTheWidthLessMargins() {
        assertEquals(1080f - 2 * 8 * 2.625f, phone.fieldWidth, 0.01f)
        assertEquals((1080f - phone.fieldWidth) / 2, phone.fieldLeft, 0.01f)
    }

    @Test
    fun wideSurfacesKeepTheFieldNarrowAndCentred() {
        val tablet = PlayfieldGeometry.of(2560f, 1600f, density = 2f, topInsetPx = 0f, bottomInsetPx = 0f)
        assertEquals(440f * 2, tablet.fieldWidth, 0.01f)
        assertEquals((2560f - 880f) / 2, tablet.fieldLeft, 0.01f)
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
        assertEquals(0, phone.laneAt(phone.fieldLeft + 1))
        assertEquals(1, phone.laneAt(phone.fieldLeft + lane + 1))
        assertEquals(2, phone.laneAt(phone.fieldLeft + 2 * lane + 1))
        assertEquals(3, phone.laneAt(phone.fieldLeft + phone.fieldWidth - 1))
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
