package io.ensomi.android.play

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneHoldsTest {
    private fun finger(id: Int) = LaneHolder.Pointer(id)

    private fun key(keyCode: Int, deviceId: Int = 3) = LaneHolder.Key(deviceId, keyCode)

    private val sent = ArrayList<LaneInput>()
    private var sequence = 0L
    private val sender = LaneSender({ ++sequence }) { sent += it }

    @Test
    fun aLaneIsPressedFromItsFirstPointerToItsLast() {
        val holds = LaneHolds()
        assertTrue(holds.press(finger(0), lane = 1))
        assertFalse("A second finger on the same lane sends no press", holds.press(finger(1), lane = 1))
        assertNull("The lane is still held", holds.release(finger(0)))
        assertEquals(1, holds.release(finger(1)))
        assertNull("An unknown pointer releases nothing", holds.release(finger(7)))
    }

    @Test
    fun chordsPressEachLaneAndACancelReleasesThemAll() {
        val holds = LaneHolds()
        assertTrue(holds.press(finger(0), 0))
        assertTrue(holds.press(finger(1), 3))
        assertTrue(holds.press(finger(2), 2))
        assertEquals(listOf(0, 2, 3), holds.releaseAll { it is LaneHolder.Pointer })
        assertFalse(holds.isHeld(0))
        assertNull(holds.release(finger(1)))
    }

    @Test
    fun aPointerStaysInTheLaneItWentDownIn() {
        val holds = LaneHolds()
        assertTrue(holds.press(finger(5), 2))
        assertFalse("The same pointer cannot go down twice", holds.press(finger(5), 3))
        assertEquals(2, holds.release(finger(5)))
    }

    @Test
    fun aFingerAndAKeyOnOneLaneGiveOnePressAndOneRelease() {
        assertTrue(sender.press(finger(0), 2, eventNanos = 10))
        assertFalse("A key on a lane a finger holds presses nothing", sender.press(key(54), 2, eventNanos = 20))
        assertFalse("The key still holds the lane", sender.release(finger(0), eventNanos = 30))
        assertTrue(sender.release(key(54), eventNanos = 40))
        assertEquals(
            listOf(
                LaneInput(2, press = true, 10, 1, InputSource.TOUCH),
                LaneInput(2, press = false, 40, 2, InputSource.KEYBOARD),
            ),
            sent,
        )
    }

    @Test
    fun theSameKeyOnTwoKeyboardsIsTwoHoldersAndOnePress() {
        sender.press(key(54, deviceId = 3), 0, 10)
        sender.press(key(54, deviceId = 4), 0, 20)
        sender.release(key(54, deviceId = 3), 30)
        assertEquals(1, sent.size)
        sender.release(key(54, deviceId = 4), 40)
        assertEquals(listOf(true to 10L, false to 40L), sent.map { it.press to it.eventNanos })
    }

    @Test
    fun aCancelReleasesThePointersAndLeavesTheKeysHeld() {
        sender.press(finger(0), 0, 10)
        sender.press(finger(1), 1, 10)
        sender.press(key(54), 1, 10)
        sender.press(key(52), 3, 10)
        sent.clear()
        sender.releasePointers(eventNanos = 50)
        assertEquals(listOf(LaneInput(0, press = false, 50, 4, InputSource.TOUCH)), sent)
        sender.release(key(54), 60)
        sender.release(key(52), 70)
        assertEquals(listOf(1, 3), sent.drop(1).map { it.lane })
    }

    @Test
    fun aKeyboardThatGoesReleasesItsKeysAndLeavesTheOthers() {
        sender.press(key(54, deviceId = 3), 0, 10)
        sender.press(key(52, deviceId = 3), 1, 10)
        sender.press(key(52, deviceId = 4), 1, 10)
        sender.press(finger(0), 2, 10)
        sent.clear()
        sender.releaseKeys(deviceId = 3, eventNanos = 50)
        assertEquals(listOf(LaneInput(0, press = false, 50, 4, InputSource.KEYBOARD)), sent)
        assertFalse("Keyboard 3's keys are gone", sender.release(key(54, deviceId = 3), 60))
        assertTrue(sender.release(key(52, deviceId = 4), 60))
        assertTrue(sender.release(finger(0), 60))
    }

    @Test
    fun anUnknownHolderReleasesNothingAndTakesNoNumber() {
        assertFalse(sender.release(key(54), 10))
        assertFalse(sender.release(finger(9), 10))
        sender.releasePointers(10)
        sender.releaseKeys(3, 10)
        assertTrue(sent.isEmpty())
        assertEquals("Only what is sent takes a number, which the autoplay shares", 0L, sequence)
    }

    @Test
    fun aPauseForgetsEveryHolderAndSendsNothing() {
        sender.press(finger(0), 0, 10)
        sender.press(key(54), 1, 10)
        sent.clear()
        sender.forget()
        assertTrue(sent.isEmpty())
        assertFalse("The lane was let go, so the finger's up releases nothing", sender.release(finger(0), 20))
        assertFalse(sender.release(key(54), 20))
        assertTrue("A lane let go presses again", sender.press(finger(1), 0, 30))
    }

    /**
     * Touch is as it was: for any touch sequence, the same presses, releases and sequence numbers as `LaneTouchTracker`
     * gave before [LaneHolds] took its place. [TouchTracker] is that tracker's rules, and [OldTouches] the playfield's use
     * of it, as at `8a09ddf`.
     */
    @Test
    fun touchesSendWhatTheTrackerSent() {
        val random = Random(29)
        repeat(200) {
            sent.clear()
            sequence = 0
            val old = OldTouches()
            var time = 0L
            repeat(60) {
                time += random.nextInt(20)
                val pointer = random.nextInt(5)
                when (random.nextInt(10)) {
                    in 0..4 -> {
                        val lane = random.nextInt(4)
                        old.down(pointer, lane, time)
                        sender.press(finger(pointer), lane, time)
                    }
                    in 5..8 -> {
                        old.up(pointer, time)
                        sender.release(finger(pointer), time)
                    }
                    else -> {
                        old.cancel(time)
                        sender.releasePointers(time)
                    }
                }
            }
            assertEquals(old.sent, sent)
        }
    }

    private class OldTouches {
        private val tracker = TouchTracker()
        private var sequence = 0L
        val sent = ArrayList<LaneInput>()

        fun down(pointer: Int, lane: Int, nanos: Long) {
            if (tracker.down(pointer, lane)) sent += LaneInput(lane, true, nanos, ++sequence, InputSource.TOUCH)
        }

        fun up(pointer: Int, nanos: Long) {
            tracker.up(pointer)?.let { sent += LaneInput(it, false, nanos, ++sequence, InputSource.TOUCH) }
        }

        fun cancel(nanos: Long) {
            tracker.cancel().forEach { sent += LaneInput(it, false, nanos, ++sequence, InputSource.TOUCH) }
        }
    }

    private class TouchTracker(private val lanes: Int = 4) {
        private val laneByPointer = HashMap<Int, Int>()
        private val pointersInLane = IntArray(lanes)

        fun down(pointerId: Int, lane: Int): Boolean {
            if (lane !in 0 until lanes || laneByPointer.containsKey(pointerId)) return false
            laneByPointer[pointerId] = lane
            return pointersInLane[lane]++ == 0
        }

        fun up(pointerId: Int): Int? {
            val lane = laneByPointer.remove(pointerId) ?: return null
            pointersInLane[lane]--
            return if (pointersInLane[lane] == 0) lane else null
        }

        fun cancel(): List<Int> {
            val pressed = (0 until lanes).filter { pointersInLane[it] > 0 }
            laneByPointer.clear()
            pointersInLane.fill(0)
            return pressed
        }
    }
}
