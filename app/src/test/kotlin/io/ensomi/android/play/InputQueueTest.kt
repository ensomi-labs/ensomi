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

    private fun timing(audioMs: Double = 0.0, touchMs: Double = 0.0, keyboardMs: Double = 0.0) =
        PlayTiming(audioMs, touchMs, keyboardMs, scrollTimeMs = 1_000.0)

    private fun input(lane: Int, press: Boolean, atMs: Long, atPause: Boolean = false, source: InputSource = InputSource.TOUCH) =
        LaneInput(lane, press, atMs * ms, ++sequence, source, atPause).also(queue::add)

    /** A frame at [atMs] on the `System.nanoTime()` clock, as the controller runs one. */
    private fun frame(atMs: Long, timing: PlayTiming = timing()) {
        queue.frame(timing.gameplayMs(clock.songTimeMs(atMs * ms)!!), timing, clock.isPaused)
    }

    @Test
    fun aFrameHandsOnWhatIsDueInTimeOrderAndKeepsTheRest() {
        // An audio latency of −4 ms is the old audio offset of +4 ms.
        val early = timing(audioMs = -4.0)
        val later = input(1, press = true, atMs = 30)
        val sooner = input(0, press = false, atMs = 20)
        frame(25, early)
        assertEquals("Both are taken, so their lanes light at once", listOf(later, sooner), taken)
        assertEquals("Due by gameplay time 29: the one at 24", listOf(24.0 to sooner), delivered)
        frame(40, early)
        assertEquals(listOf(24.0 to sooner, 34.0 to later), delivered)
    }

    /**
     * The paused-frame fix: an input queued before a pause and taken by a paused frame is stamped at its own chart time,
     * with its method's input latency, and at the pause's time at the latest; before, it was stamped at the pause's
     * time whatever its own was.
     */
    @Test
    fun aPausedFrameHandsOnWhatWasQueuedBeforeThePauseAtItsOwnTime() {
        val waiting = input(1, press = false, atMs = 105)
        frame(100)
        assertTrue("Stamped after the frame's time, it waits", delivered.isEmpty())
        val queued = input(2, press = true, atMs = 108)
        clock.pause(110 * ms)
        frame(500)
        assertEquals(
            "Nothing made before the pause is dropped, and each keeps its own time",
            listOf(105.0 to waiting, 108.0 to queued),
            delivered,
        )
        frame(900)
        assertEquals(2, delivered.size)
    }

    @Test
    fun aPausedFrameStampsWithEachMethodsInputLatencyAndCapsAtThePause() {
        val latencies = timing(audioMs = 20.0, touchMs = 12.0, keyboardMs = -15.0)
        frame(100, latencies)
        val touch = input(0, press = true, atMs = 106)
        val key = input(1, press = true, atMs = 107, source = InputSource.KEYBOARD)
        clock.pause(110 * ms)
        frame(400, latencies)
        // The pause holds gameplay time 110 − 20 = 90. The touch is its own 106 − 20 − 12; the key's own, 107 − 20 + 15,
        // is past the pause, where it is held.
        assertEquals(listOf(74.0 to touch, 90.0 to key), delivered)
    }

    /** As the game's audio clock does it: a paused frame maps an input made before the pause on the clock's line. */
    @Test
    fun onTheAudioClockAPausedFrameStampsAnInputAtItsOwnTime() {
        val output = Output()
        val audio = AudioPlayClock(output, rate, leadInFrames = 0)
        val delivered = ArrayList<Pair<Double, LaneInput>>()
        val queue = InputQueue(audio, taken = {}, deliver = { at, input -> delivered += at to input })
        val latencies = timing(touchMs = 8.0)
        audio.start(0, 0.0)
        output.next = OutputTimestamp(frames(5_000.0), 5_000 * ms)
        audio.update(5_000 * ms)
        queue.frame(audio.songTimeMs(5_000 * ms)!!, latencies, paused = false)
        val press = LaneInput(2, press = true, 5_009 * ms, 1, InputSource.TOUCH).also(queue::add)
        audio.pause(5_012 * ms)
        queue.frame(audio.songTimeMs(5_020 * ms)!!, latencies, audio.isPaused)
        assertEquals(listOf(5_001.0 to press), delivered)
    }

    @Test
    fun aReleaseAtTheResumeTakesTheTimeThePausedFramesHeldAt() {
        val early = timing(audioMs = -7.0, touchMs = 30.0)
        frame(100, early)
        clock.pause(110 * ms)
        frame(200, early)
        frame(3_000, early)
        // As the playfield resumes: the release is queued, then the clock runs again. It takes no input latency.
        val release = input(0, press = false, atMs = 4_000, atPause = true)
        clock.resume(4_000 * ms)
        frame(4_010, early)
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
        frame(100, timing(touchMs = 25.0))
        clock.pause(110 * ms)
        val release = input(0, press = false, atMs = 120, atPause = true)
        clock.resume(120 * ms)
        frame(130, timing(touchMs = 25.0))
        assertEquals(listOf(110.0 to release), delivered)
    }

    @Test
    fun nothingTakenAfterAPauseIsStampedBeforeTheTimeItHeldAtLatencyIncluded() {
        val late = timing(touchMs = 30.0)
        frame(100, late)
        clock.pause(110 * ms)
        frame(200, late)
        clock.resume(4_000 * ms)
        // Stamped before the resume, which no input taken after it is; it cannot go before the pause either.
        val early = input(1, press = true, atMs = 3_990)
        frame(4_005, late)
        // 20 ms of song after the resume, less 30 ms of latency, would be before the pause.
        val soon = input(2, press = true, atMs = 4_020)
        frame(4_030, late)
        val after = input(1, press = false, atMs = 4_060)
        frame(4_070, late)
        assertEquals(listOf(110.0 to early, 110.0 to soon, 140.0 to after), delivered)
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

    /**
     * A lane held by a finger and then a key, let go by the finger first: the press went with the touch and the release
     * goes with the key, whose larger latency would stamp it before the press. It is stamped at the press instead, so
     * the session gets them in order and holds nothing after.
     */
    @Test
    fun aLanesReleaseIsNeverStampedBeforeItsPressWhateverTheirSources() {
        val latencies = timing(touchMs = 0.0, keyboardMs = 30.0)
        val pressed = BooleanArray(4)
        val order = ArrayList<Pair<Double, Boolean>>()
        val queue = InputQueue(clock, taken = {}, deliver = { at, input ->
            pressed[input.lane] = input.press
            order += at to input.press
        })
        val sender = LaneSender({ ++sequence }, queue::add)
        sender.press(LaneHolder.Pointer(0), 0, 100 * ms)
        sender.press(LaneHolder.Key(3, 54), 0, 105 * ms)
        sender.release(LaneHolder.Pointer(0), 110 * ms)
        sender.release(LaneHolder.Key(3, 54), 120 * ms)
        queue.frame(latencies.gameplayMs(clock.songTimeMs(125 * ms)!!), latencies, paused = false)
        queue.frame(latencies.gameplayMs(clock.songTimeMs(300 * ms)!!), latencies, paused = false)
        assertEquals(listOf(100.0 to true, 100.0 to false), order)
        assertTrue("The session holds what the app holds: nothing", pressed.none { it })
    }

    @Test
    fun clearingForgetsWhatWaitsThePauseAndTheMethods() {
        frame(100, timing(touchMs = 30.0))
        input(1, press = true, atMs = 105)
        frame(110, timing(touchMs = 30.0))
        clock.pause(110 * ms)
        frame(200)
        input(0, press = true, atMs = 300)
        queue.clear()
        assertEquals(Double.NEGATIVE_INFINITY, queue.inputClockMs, 0.0)
        // The preview starts its game again from the lead-in, before the time the pause held at.
        clock.start(1_000 * ms, fromMs = -2_000.0)
        val press = input(2, press = true, atMs = 1_100)
        frame(1_200)
        assertEquals(listOf(75.0, -1_900.0), delivered.map { it.first })
        assertEquals(press, delivered.last().second)
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
                queue.frame(song, timing(), audio.isPaused)
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
        queue.frame(audio.songTimeMs(5_000 * ms)!!, timing(), paused = false)
        audio.pause(5_010 * ms)
        queue.frame(audio.songTimeMs(5_020 * ms)!!, timing(), audio.isPaused)
        output.next = OutputTimestamp(frames(5_010.0), 5_011 * ms)
        queue.add(LaneInput(0, press = false, 20_000 * ms, 1, InputSource.TOUCH, atPause = true))
        audio.resume(20_000 * ms)
        // A press handled while the clock still waited, but only taken by the frame that finds the first timestamp,
        // which is ahead of the held time by then: 5 022 ms at 20 020 ms.
        val press = LaneInput(0, press = true, 20_004 * ms, 2, InputSource.TOUCH)
        audio.update(20_002 * ms)
        queue.frame(audio.songTimeMs(20_002 * ms)!!, timing(), audio.isPaused)
        queue.add(press)
        output.next = OutputTimestamp(frames(5_012.0), 20_010 * ms)
        audio.update(20_020 * ms)
        assertEquals("On the new line it is before the pause", 5_006.0, audio.songTimeMs(press.eventNanos)!!, 1e-9)
        queue.frame(audio.songTimeMs(20_020 * ms)!!, timing(), audio.isPaused)
        assertEquals(listOf(5_010.0, 5_010.0), delivered.map { it.first })
        assertEquals(press, delivered.last().second)
    }

    /**
     * A session that, like the runtime's, keeps a pressed flag per lane and counts an input stamped before its clock. Each
     * lane's inputs must come in the order they were sent.
     */
    private class Session {
        val pressed = BooleanArray(4)
        var clockMs = Double.NEGATIVE_INFINITY
        val late = ArrayList<LaneInput>()
        private val lastSequence = LongArray(4)

        fun input(chartMs: Double, input: LaneInput) {
            assertTrue("Lane ${input.lane} in the order sent", input.sequence > lastSequence[input.lane])
            lastSequence[input.lane] = input.sequence
            if (chartMs < clockMs) late += input
            clockMs = maxOf(clockMs, chartMs)
            pressed[input.lane] = input.press
        }

        fun advance(watermarkMs: Double) {
            clockMs = maxOf(clockMs, watermarkMs)
        }

        fun held(): List<Int> = (0 until 4).filter { pressed[it] }
    }

    /**
     * One random game through holders, the queue and a [Session], as the playfield and the controller run it: presses,
     * releases, cancels, pauses, resumes and frames at any time, in the playfield's order at a pause and a resume, each
     * event handled up to [handlingMs] after it happened (0: taken by the first frame after it). A paused frame runs
     * before each resume, as the count-in's 1.5 seconds make sure. With [strict], the session holds the app's lanes after
     * every frame; otherwise after every paused frame, inputs of a method with a smaller latency waiting meanwhile.
     */
    private class Game(
        private val random: Random,
        private val latencies: PlayTiming,
        private val handlingMs: Int,
        private val strict: Boolean,
    ) {
        val clock = FakePlayClock()
        val session = Session()
        val queue = InputQueue(clock, taken = {}, deliver = session::input)
        private var sequence = 0L
        val sender = LaneSender({ ++sequence }, queue::add)
        private var now = 0L
        private var lastEvent = 0L
        private var paused = false
        private var pausedFrame = false
        private var atPause = emptyList<Int>()

        /** Each method's first press, by its event time in milliseconds, in the order they came. */
        val firstPress = LinkedHashMap<InputSource, Long>()

        fun frame() {
            val gameplayMs = latencies.gameplayMs(clock.songTimeMs(now * MS)!!)
            val clockPaused = clock.isPaused
            if (clockPaused) pausedFrame = true
            queue.frame(gameplayMs, latencies, clockPaused)
            // The controller advances to the input clock less the allowance, and only while the game runs.
            if (!clockPaused) session.advance(queue.inputClockMs - PlayController.INPUT_ALLOWANCE_MS)
            if (strict || clockPaused) assertEquals(if (paused) atPause else sender.heldLanes(), session.held())
        }

        fun play(steps: Int) {
            clock.start(now * MS, fromMs = -2_000.0)
            repeat(steps) {
                now += random.nextInt(12)
                // The main thread handles events in order.
                lastEvent = maxOf(lastEvent, now - (if (handlingMs == 0) 0 else random.nextInt(handlingMs)))
                val holder = when (random.nextBoolean()) {
                    true -> LaneHolder.Pointer(random.nextInt(4))
                    false -> LaneHolder.Key(3, 50 + random.nextInt(4))
                }
                when (random.nextInt(16)) {
                    in 0..5 -> if (sender.press(holder, random.nextInt(4), lastEvent * MS)) {
                        firstPress.putIfAbsent(holder.source, lastEvent)
                    }
                    in 6..9 -> sender.release(holder, lastEvent * MS)
                    10 -> sender.releasePointers(lastEvent * MS)
                    11 -> if (!paused) {
                        clock.pause(now * MS)
                        sender.pause()
                        paused = true
                        pausedFrame = false
                        atPause = sender.heldLanes()
                        lastEvent = now
                    }
                    12 -> if (paused) {
                        if (!pausedFrame) frame()
                        sender.resume(now * MS)
                        paused = false
                        if (random.nextBoolean()) frame()
                        clock.resume(now * MS)
                        lastEvent = now
                    }
                    else -> frame()
                }
                if (handlingMs == 0) frame()
            }
            // Long enough for everything waiting to come due: then the session holds what the app holds.
            now += 1_000
            frame()
            assertEquals(if (paused) atPause else sender.heldLanes(), session.held())
        }

        /**
         * The time the input clock held at for the first presses up to [eventMs], in event milliseconds less latency,
         * the audio latency left out as it moves everything alike: at a method's first press the clock is the press's
         * time less the largest latency of the methods live before it (0 before any), and it never moves back, so it
         * holds there until the time less the largest live latency passes it.
         */
        fun heldAtMs(eventMs: Double): Double {
            var most: Double? = null
            var held = Double.NEGATIVE_INFINITY
            for ((source, atMs) in firstPress) {
                if (atMs > eventMs) break
                held = maxOf(held, atMs - (most ?: 0.0))
                most = maxOf(most ?: Double.NEGATIVE_INFINITY, latencies.inputLatencyMs(source))
            }
            return held
        }

        companion object {
            const val MS = 1_000_000L
        }
    }

    /**
     * The whole path from holders to a session that, like the runtime's, keeps a pressed flag per lane, never moves its
     * clock back and counts an input stamped before its clock as late, with touches and keys handled up to 50 ms after
     * they happened and any audio latency, touch and keys on one input latency: after every frame the session holds the
     * lanes the app holds (through a pause, those held as it began), and no input is late.
     */
    @Test
    fun aSessionFedThroughTheQueueHoldsWhatTheAppHoldsAndHearsNothingLate() {
        val random = Random(11)
        repeat(300) {
            val inputMs = random.nextInt(61) - 20.0
            val latencies = timing(audioMs = random.nextInt(41) - 20.0, touchMs = inputMs, keyboardMs = inputMs)
            val game = Game(random, latencies, handlingMs = 50, strict = true)
            game.play(200)
            assertEquals("Nothing reached the session late", emptyList<LaneInput>(), game.session.late)
        }
    }

    /**
     * Touch and keys at different input latencies, each input taken by the first frame after it. Each lane's inputs reach
     * the session in the order sent, it holds what the app holds after every paused frame and once nothing waits, and
     * an input is late only while the input clock holds for a method's first press, never moving back (latency.md, Live
     * methods): when its own time, less its method's latency, is before the time the clock held at.
     */
    @Test
    fun withTwoLatenciesAnInputIsLateOnlyWhileTheInputClockHoldsForAFirstPress() {
        val random = Random(23)
        var lateSeen = 0
        repeat(300) {
            val latencies = timing(
                audioMs = random.nextInt(41) - 20.0,
                touchMs = random.nextInt(81) - 20.0,
                keyboardMs = random.nextInt(81) - 20.0,
            )
            val game = Game(random, latencies, handlingMs = 0, strict = false)
            game.play(200)
            for (input in game.session.late) {
                lateSeen++
                val eventMs = input.eventNanos / 1e6
                val ownMs = eventMs - latencies.inputLatencyMs(input.source)
                val heldMs = game.heldAtMs(eventMs)
                assertTrue("Late at $eventMs ms, own $ownMs ms, the clock held at $heldMs ms", ownMs < heldMs)
            }
        }
        assertTrue("Some games had a late press while the clock held", lateSeen > 0)
    }
}
