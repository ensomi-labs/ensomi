package io.ensomi.android.play

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDrainTest {
    private val interval = 8_333_333L
    private val quick = 1_000_000L
    private val held = 7_000_000L

    @Test
    fun framesHandedOverQuicklyAreNeverSkipped() {
        val drain = QueueDrain()
        repeat(100) {
            drain.posted(quick, interval)
            assertFalse(drain.takeSkip())
        }
    }

    @Test
    fun oneHeldFrameIsNoReasonToSkip() {
        val drain = QueueDrain()
        drain.posted(held, interval)
        assertFalse(drain.takeSkip())
        drain.posted(quick, interval)
        drain.posted(held, interval)
        assertFalse(drain.takeSkip())
    }

    @Test
    fun twoHeldFramesInARowSkipTheNextVsyncOnce() {
        val drain = QueueDrain()
        drain.posted(held, interval)
        assertFalse(drain.takeSkip())
        drain.posted(held, interval)
        assertTrue(drain.takeSkip())
        assertFalse(drain.takeSkip())
    }

    @Test
    fun afterASkipItTakesTwoMoreHeldFrames() {
        val drain = QueueDrain()
        repeat(QueueDrain.HELD_FRAMES) { drain.posted(held, interval) }
        assertTrue(drain.takeSkip())
        drain.posted(held, interval)
        assertFalse(drain.takeSkip())
        drain.posted(held, interval)
        assertTrue(drain.takeSkip())
    }

    @Test
    fun halfARefreshIsNotYetHeld() {
        val drain = QueueDrain()
        repeat(4) { drain.posted(interval / 2, interval) }
        assertFalse(drain.takeSkip())
    }
}
