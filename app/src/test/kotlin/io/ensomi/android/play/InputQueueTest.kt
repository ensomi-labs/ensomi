package io.ensomi.android.play

import io.ensomi.android.audio.AudioPlayClock
import io.ensomi.android.audio.OutputTimestamp
import io.ensomi.android.audio.PlayOutput
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputQueueTest {
    private val ms = 1_000_000L
    private val clock = FakePlayClock().apply { start(0, fromMs = 0.0) }
    private val taken = ArrayList<LaneInput>()
    private val delivered = ArrayList<Pair<Double, LaneInput>>()
    private val queue = InputQueue(clock, taken = { taken += it }, deliver = { at, input -> delivered += at to input })
    private var sequence = 0L

    private fun input(lane: Int, press: Boolean, atMs: Long, atPause: Boolean = false) =
        LaneInput(lane, press, atMs * ms, ++sequence, InputSource.TOUCH, atPause).also(queue::add)

    /** A frame at [atMs] on the `System.nanoTime()` clock, as the controller runs one. */
    private fun frame(atMs: Long, offsetMs: Double = 0.0) {
        queue.frame(clock.songTimeMs(atMs * ms)!! + offsetMs, offsetMs, clock.isPaused)
    }

    @Test
    fun aFrameHandsOnWhatIsDueInTimeOrderAndKeepsTheRest() {
        val later = input(1, press = true, atMs = 30)
        val sooner = input(0, press = false, atMs = 20)
        frame(25, offsetMs = 4.0)
        assertEquals("Both are taken, so their lanes light at once", listOf(later, sooner), taken)
        assertEquals("Due by gameplay time 29: the one at 24", listOf(24.0 to sooner), delivered)
        frame(40, offsetMs = 4.0)
        assertEquals(listOf(24.0 to sooner, 34.0 to later), delivered)
    }

    @Test
    fun aPausedFrameHandsOnWhatWasQueuedBeforeThePause() {
        val waiting = input(1, press = false, atMs = 105)
        frame(100)
        assertTrue("Stamped after the frame's time, it waits", delivered.isEmpty())
        val queued = input(2, press = true, atMs = 108)
        clock.pause(110 * ms)
        frame(500)
        assertEquals(
            "Nothing made before the pause is dropped; what the frame takes then is at the pause's time",
            listOf(105.0 to waiting, 110.0 to queued),
            delivered,
        )
        frame(900)
        assertEquals(2, delivered.size)
    }

    @Test
    fun aReleaseAtTheResumeTakesTheTimeThePausedFramesHeldAt() {
        frame(100, offsetMs = 7.0)
        clock.pause(110 * ms)
        frame(200, offsetMs = 7.0)
        frame(3_000, offsetMs = 7.0)
        // As the playfield resumes: the release is queued, then the clock runs again.
        val release = input(0, press = false, atMs = 4_000, atPause = true)
        clock.resume(4_000 * ms)
        frame(4_010, offsetMs = 7.0)
        assertEquals(listOf(117.0 to release), delivered)
    }

    @Test
    fun aReleaseAtTheResumeThatAPausedFrameTakesIsAtThePausesTimeToo() {
        frame(100)
        clock.pause(110 * ms)
        frame(200)
        val release = input(3, press = false, atMs = 4_000, atPause = true)
        frame(4_001)
        clock.resume(4_002 * ms)
        frame(4_010)
        assertEquals(listOf(110.0 to release), delivered)
    }

    @Test
    fun aResumeWithNoPausedFrameBetweenStampsTheReleaseAtThePauseFromTheClock() {
        frame(100)
        clock.pause(110 * ms)
        val release = input(0, press = false, atMs = 120, atPause = true)
        clock.resume(120 * ms)
        frame(130)
        assertEquals(listOf(110.0 to release), delivered)
    }

    @Test
    fun nothingTakenAfterAPauseIsStampedBeforeTheTimeItHeldAt() {
        frame(100)
        clock.pause(110 * ms)
        frame(200)
        clock.resume(4_000 * ms)
        // Stamped before the resume, which no input taken after it is; it cannot go before the pause either.
        val early = input(1, press = true, atMs = 3_990)
        frame(4_005)
        val after = input(1, press = false, atMs = 4_020)
        frame(4_030)
        assertEquals(listOf(110.0 to early, 130.0 to after), delivered)
    }

    @Test
    fun aReleaseAtTheResumeComesBeforeAPressAtTheSameTime() {
        frame(100)
        clock.pause(110 * ms)
        frame(200)
        val release = input(0, press = false, atMs = 4_000, atPause = true)
        clock.resume(4_000 * ms)
        val press = input(0, press = true, atMs = 4_000)
        frame(4_005)
        assertEquals(listOf(110.0 to release, 110.0 to press), delivered)
    }

    @Test
    fun clearingForgetsWhatWaitsAndThePause() {
        frame(100)
        clock.pause(110 * ms)
        frame(200)
        input(0, press = true, atMs = 300)
        queue.clear()
        // The preview starts its game again from the lead-in, before the time the pause held at.
        clock.start(1_000 * ms, fromMs = -2_000.0)
        val press = input(2, press = true, atMs = 1_100)
        frame(1_200)
        assertEquals(listOf(-1_900.0 to press), delivered)
    }

    /** An output whose timestamps a test sets, for the game's own clock. */
    private class Output : PlayOutput {
        var next: OutputTimestamp? = null

        override fun timestamp(): OutputTimestamp? = next

        override fun play() = Unit

        override fun pause() = Unit
    }

    private val rate = 48_000

    private fun frames(songMs: Double) = (songMs * rate / 1000).toLong()

    /**
     * The audio clock, as a game runs on it: a frame updates it, asks its song time now and at the frame's presentation
     * 16 ms later, and hands the queue the time now. The release at the resume is stamped with the time the clock held
     * at, whether the first frame after the resume finds the clock still waiting for a timestamp or already past one.
     */
    @Test
    fun onTheAudioClockAReleaseAtTheResumeTakesTheTimeItHeldAt() {
        for (timestampFirst in listOf(false, true)) {
            val output = Output()
            val audio = AudioPlayClock(output, rate, leadInFrames = 0)
            val delivered = ArrayList<Pair<Double, LaneInput>>()
            val queue = InputQueue(audio, taken = {}, deliver = { at, input -> delivered += at to input })
            fun frame(atMs: Long) {
                audio.update(atMs * ms)
                val song = audio.songTimeMs(atMs * ms)!!
                audio.songTimeMs((atMs + 16) * ms)
                queue.frame(song, 0.0, audio.isPaused)
            }
            audio.start(0, 0.0)
            output.next = OutputTimestamp(frames(5_000.0), 5_000 * ms)
            frame(5_000)
            val press = LaneInput(2, press = true, 5_005 * ms, 1, InputSource.TOUCH).also(queue::add)
            frame(5_016)
            // Paused 4 ms after that frame, whose presentation was asked for at 5 032 ms: the clock holds 5 032.
            audio.pause(5_020 * ms)
            frame(5_030)
            frame(9_000)
            // The output stopped at 5 012 ms. The playfield queues the release, then the clock runs again.
            output.next = OutputTimestamp(frames(5_012.0), 5_013 * ms)
            val release = LaneInput(2, press = false, 20_000 * ms, 2, InputSource.TOUCH, atPause = true)
            queue.add(release)
            audio.resume(20_000 * ms)
            if (timestampFirst) output.next = OutputTimestamp(frames(5_013.0), 20_005 * ms)
            frame(20_010)
            assertEquals(listOf(5_005.0 to press, 5_032.0 to release), delivered)
        }
    }

    /**
     * The first timestamp after a resume may put the audio clock ahead of the time it held at, which it takes at once.
     * A press made while it still held, taken by a later frame, then maps before that time; it is stamped at it
     * instead, where the session's clock already is after the release at the resume, so it is not late.
     */
    @Test
    fun onTheAudioClockAPressAfterTheResumeIsNeverStampedBeforeTheTimeItHeldAt() {
        val output = Output()
        val audio = AudioPlayClock(output, rate, leadInFrames = 0)
        val delivered = ArrayList<Pair<Double, LaneInput>>()
        val queue = InputQueue(audio, taken = {}, deliver = { at, input -> delivered += at to input })
        audio.start(0, 0.0)
        output.next = OutputTimestamp(frames(5_000.0), 5_000 * ms)
        audio.update(5_000 * ms)
        queue.frame(audio.songTimeMs(5_000 * ms)!!, 0.0, paused = false)
        audio.pause(5_010 * ms)
        queue.frame(audio.songTimeMs(5_020 * ms)!!, 0.0, audio.isPaused)
        output.next = OutputTimestamp(frames(5_010.0), 5_011 * ms)
        queue.add(LaneInput(0, press = false, 20_000 * ms, 1, InputSource.TOUCH, atPause = true))
        audio.resume(20_000 * ms)
        // A press handled while the clock still waited, but only taken by the frame that finds the first timestamp,
        // which is ahead of the held time by then: 5 022 ms at 20 020 ms.
        val press = LaneInput(0, press = true, 20_004 * ms, 2, InputSource.TOUCH)
        audio.update(20_002 * ms)
        queue.frame(audio.songTimeMs(20_002 * ms)!!, 0.0, audio.isPaused)
        queue.add(press)
        output.next = OutputTimestamp(frames(5_012.0), 20_010 * ms)
        audio.update(20_020 * ms)
        assertEquals("On the new line it is before the pause", 5_006.0, audio.songTimeMs(press.eventNanos)!!, 1e-9)
        queue.frame(audio.songTimeMs(20_020 * ms)!!, 0.0, audio.isPaused)
        assertEquals(listOf(5_010.0, 5_010.0), delivered.map { it.first })
        assertEquals(press, delivered.last().second)
    }

    /**
     * The whole path from holders to a session that, like the runtime's, keeps a pressed flag per lane, never moves its
     * clock back and counts an input stamped before its clock as late. Through presses, releases, cancels, pauses,
     * resumes and frames at any time, the playfield's order at a pause and a resume, and touches handled up to 50 ms
     * after they happened: after every frame the session holds the lanes the app holds (through a pause, those held as
     * it began), and no input is late. A paused frame runs before each resume, as the count-in's 1.5 seconds make sure.
     */
    @Test
    fun aSessionFedThroughTheQueueHoldsWhatTheAppHoldsAndHearsNothingLate() {
        val random = Random(11)
        repeat(300) {
            val clock = FakePlayClock()
            val pressed = BooleanArray(4)
            var sessionMs = Double.NEGATIVE_INFINITY
            var late = 0
            val queue = InputQueue(clock, taken = {}, deliver = { chartMs, input ->
                if (chartMs < sessionMs) late++
                sessionMs = maxOf(sessionMs, chartMs)
                pressed[input.lane] = input.press
            })
            var sequence = 0L
            val sender = LaneSender({ ++sequence }, queue::add)
            val offsetMs = random.nextInt(41) - 20.0
            var now = 0L
            var lastEvent = 0L
            var paused = false
            var pausedFrame = false
            var atPause = emptyList<Int>()
            clock.start(now * ms, fromMs = -2_000.0)

            fun frame() {
                val gameplayMs = clock.songTimeMs(now * ms)!! + offsetMs
                val clockPaused = clock.isPaused
                if (clockPaused) pausedFrame = true
                queue.frame(gameplayMs, offsetMs, clockPaused)
                if (!clockPaused) sessionMs = maxOf(sessionMs, gameplayMs - PlayController.INPUT_ALLOWANCE_MS)
                assertEquals(if (paused) atPause else sender.heldLanes(), (0 until 4).filter { pressed[it] })
            }

            repeat(200) {
                now += random.nextInt(12)
                // The main thread handles events in order, a touch up to 50 ms after it happened.
                lastEvent = maxOf(lastEvent, now - random.nextInt(50))
                val holder = when (random.nextBoolean()) {
                    true -> LaneHolder.Pointer(random.nextInt(4))
                    false -> LaneHolder.Key(3, 50 + random.nextInt(4))
                }
                when (random.nextInt(16)) {
                    in 0..5 -> sender.press(holder, random.nextInt(4), lastEvent * ms)
                    in 6..9 -> sender.release(holder, lastEvent * ms)
                    10 -> sender.releasePointers(lastEvent * ms)
                    11 -> if (!paused) {
                        clock.pause(now * ms)
                        sender.pause()
                        paused = true
                        pausedFrame = false
                        atPause = sender.heldLanes()
                        lastEvent = now
                    }
                    12 -> if (paused) {
                        if (!pausedFrame) frame()
                        sender.resume(now * ms)
                        paused = false
                        if (random.nextBoolean()) frame()
                        clock.resume(now * ms)
                        lastEvent = now
                    }
                    else -> frame()
                }
            }
            assertEquals("Nothing reached the session late", 0, late)
        }
    }
}
