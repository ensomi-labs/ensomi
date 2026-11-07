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
    fun aPauseKeepsEveryHolderAndSendsNothing() {
        sender.press(finger(0), 0, 10)
        sender.press(key(54), 1, 10)
        sent.clear()
        sender.pause()
        sender.pause()
        assertTrue("The game hears nothing of the pause", sent.isEmpty())
        assertEquals(listOf(0, 1), sender.heldLanes())
        assertTrue("Nothing was let go, so the resume releases nothing", sender.resume(eventNanos = 50).isEmpty())
        assertTrue(sent.isEmpty())
        assertTrue("The finger still holds its lane after the pause", sender.release(finger(0), 60))
        assertTrue(sender.release(key(54), 70))
        assertEquals(
            listOf(
                LaneInput(0, press = false, 60, 3, InputSource.TOUCH),
                LaneInput(1, press = false, 70, 4, InputSource.KEYBOARD),
            ),
            sent,
        )
    }

    @Test
    fun aLaneLetGoDuringThePauseIsReleasedAtTheResume() {
        sender.press(finger(0), 0, 10)
        sender.press(key(54), 2, 10)
        sender.press(finger(1), 3, 10)
        sent.clear()
        sender.pause()
        assertFalse("While paused a release is kept for the resume", sender.release(finger(0), 20))
        assertFalse(sender.release(key(54), 30))
        assertTrue(sent.isEmpty())
        assertEquals("Let go, so no longer held", listOf(3), sender.heldLanes())
        assertEquals(listOf(0, 2), sender.resume(eventNanos = 90))
        assertEquals(
            "Released at the resume, each from its source, to be judged at the pause's time",
            listOf(
                LaneInput(0, press = false, 90, 4, InputSource.TOUCH, atPause = true),
                LaneInput(2, press = false, 90, 5, InputSource.KEYBOARD, atPause = true),
            ),
            sent,
        )
        assertTrue("A second resume releases nothing again", sender.resume(eventNanos = 95).isEmpty())
        assertTrue("A lane released presses again", sender.press(finger(2), 0, 100))
    }

    @Test
    fun aLaneStillHeldAtTheResumeHoldsOnThroughTheCountIn() {
        sender.press(finger(0), 1, 10)
        sender.press(key(52), 1, 20)
        sent.clear()
        sender.pause()
        assertFalse("The key still holds the lane", sender.release(finger(0), 30))
        assertFalse("A press in the count-in sends nothing for a lane already held", sender.press(finger(4), 1, 40))
        assertTrue("Still held at the resume: nothing is released", sender.resume(eventNanos = 50).isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals(listOf(1), sender.heldLanes())
        assertFalse("The finger that went down in the count-in holds nothing", sender.release(finger(4), 60))
        assertTrue(sender.release(key(52), 70))
        assertEquals(listOf(LaneInput(1, press = false, 70, 2, InputSource.KEYBOARD)), sent)
    }

    @Test
    fun pressesDuringThePauseAreDropped() {
        sender.pause()
        assertFalse(sender.press(finger(0), 0, 10))
        assertFalse(sender.press(key(54), 2, 10))
        assertTrue(sender.heldLanes().isEmpty())
        assertFalse("A finger that went down during the pause releases nothing", sender.release(finger(0), 20))
        assertTrue(sender.resume(eventNanos = 30).isEmpty())
        assertFalse("A key held from the pause on releases nothing after it", sender.release(key(54), 40))
        assertTrue(sent.isEmpty())
        assertEquals(0L, sequence)
    }

    @Test
    fun aCancelOrAKeyboardGoingDuringThePauseWaitsForTheResume() {
        sender.press(finger(0), 0, 10)
        sender.press(key(54, deviceId = 3), 1, 10)
        sender.press(key(52, deviceId = 4), 2, 10)
        sent.clear()
        sender.pause()
        sender.releasePointers(eventNanos = 20)
        sender.releaseKeys(deviceId = 3, eventNanos = 30)
        assertTrue(sent.isEmpty())
        assertEquals(listOf(2), sender.heldLanes())
        sender.resume(eventNanos = 40)
        assertEquals(
            listOf(
                LaneInput(0, press = false, 40, 4, InputSource.TOUCH, atPause = true),
                LaneInput(1, press = false, 40, 5, InputSource.KEYBOARD, atPause = true),
            ),
            sent,
        )
    }

    @Test
    fun aLaneTakesTheSourceOfItsLastHolder() {
        val holds = LaneHolds()
        holds.press(finger(0), 3)
        holds.press(key(54), 3)
        holds.pause()
        assertNull(holds.release(key(54)))
        assertNull(holds.release(finger(0)))
        assertEquals(listOf(3 to InputSource.TOUCH), holds.resume())
        assertFalse(holds.isPaused)
    }

    @Test
    fun clearingForgetsTheHoldersAndTheLanesLetGo() {
        sender.press(finger(0), 0, 10)
        sender.press(key(54), 1, 10)
        sender.pause()
        sender.release(finger(0), 20)
        sent.clear()
        // Restart, Quit or leaving: the playfield closes and its holders go with it.
        sender.clear()
        assertTrue(sender.heldLanes().isEmpty())
        assertTrue("Nothing let go is released into another game", sender.resume(eventNanos = 30).isEmpty())
        assertFalse(sender.release(key(54), 40))
        assertTrue(sent.isEmpty())
        assertTrue(sender.press(finger(0), 0, 50))
    }

    /**
     * The session hears every press and release that is sent, and holds a lane from its press to its release, as the
     * runtime's pressed flag does. Through any presses, releases, cancels, keyboards going, pauses and resumes, it then
     * holds the lanes the app holds whenever the game plays, and through a pause the lanes held as it began.
     */
    @Test
    fun theSessionHoldsWhatTheAppHolds() {
        val random = Random(7)
        repeat(300) {
            sent.clear()
            val sender = LaneSender({ ++sequence }) { sent += it }
            val pressed = BooleanArray(4)
            var paused = false
            var atPause = emptyList<Int>()
            repeat(80) { step ->
                val holder = when (random.nextBoolean()) {
                    true -> finger(random.nextInt(4))
                    false -> key(50 + random.nextInt(4), deviceId = 3 + random.nextInt(2))
                }
                val resuming = paused
                when (random.nextInt(14)) {
                    in 0..4 -> sender.press(holder, random.nextInt(4), step.toLong())
                    in 5..8 -> sender.release(holder, step.toLong())
                    9 -> sender.releasePointers(step.toLong())
                    10 -> sender.releaseKeys(3 + random.nextInt(2), step.toLong())
                    11 -> if (!paused) {
                        sender.pause()
                        paused = true
                        atPause = sender.heldLanes()
                    }
                    else -> if (paused) {
                        sender.resume(step.toLong())
                        paused = false
                    }
                }
                for (input in sent) {
                    assertEquals("A press of a lane not pressed, a release of one", !input.press, pressed[input.lane])
                    assertEquals("Only a resume marks a release for the pause", resuming && !paused, input.atPause)
                    pressed[input.lane] = input.press
                }
                sent.clear()
                val expected = if (paused) atPause else sender.heldLanes()
                assertEquals(expected, (0 until 4).filter { pressed[it] })
            }
        }
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
            val sender = LaneSender({ ++sequence }) { sent += it }
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
