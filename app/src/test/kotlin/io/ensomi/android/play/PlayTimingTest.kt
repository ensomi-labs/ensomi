package io.ensomi.android.play

import io.ensomi.android.settings.AudioLatency
import io.ensomi.android.settings.InputLatency
import io.ensomi.android.settings.LatencySlot
import io.ensomi.android.settings.PlaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller's arithmetic as plain functions (latency.md, Formulas and Live methods). */
class PlayTimingTest {
    private val ms = 1_000_000L

    private val settings = PlaySettings(
        audioLatency = AudioLatency(listOf(LatencySlot("", -2), LatencySlot("", 21), LatencySlot("Buds", 111)), active = 2),
        inputLatency = InputLatency(listOf(LatencySlot("", 8), LatencySlot("", 30), LatencySlot()), touch = 0, keyboard = 1),
    )

    private fun timing(audioMs: Double = 0.0, touchMs: Double = 0.0, keyboardMs: Double = 0.0) =
        PlayTiming(audioMs, touchMs, keyboardMs, scrollTimeMs = 1_000.0)

    @Test
    fun withNoInputLatencyTheFormulasAreTheAudioOffsetsWithAEqualToMinusIt() {
        // An audio offset of +12 ms is an audio latency of −12 ms.
        val timing = timing(audioMs = -12.0)
        assertEquals(1_012.0, timing.gameplayMs(1_000.0), 1e-9)
        assertEquals(1_028.0, timing.renderMs(1_016.0), 1e-9)
        for (source in InputSource.entries) assertEquals(source.name, 1_012.0, timing.inputMs(1_000.0, source), 1e-9)
    }

    @Test
    fun anInputLatencyMovesOnlyItsMethodsInputsAndTheAutoplayHasNone() {
        val timing = timing(audioMs = 120.0, touchMs = 30.0, keyboardMs = -8.0)
        assertEquals("Bluetooth's 120 ms hold the judging back", 880.0, timing.gameplayMs(1_000.0), 1e-9)
        assertEquals("and the picture", 880.0, timing.renderMs(1_000.0), 1e-9)
        assertEquals("Late touches are judged earlier", 850.0, timing.inputMs(1_000.0, InputSource.TOUCH), 1e-9)
        assertEquals("Early keys later", 888.0, timing.inputMs(1_000.0, InputSource.KEYBOARD), 1e-9)
        assertEquals(880.0, timing.inputMs(1_000.0, InputSource.AUTOPLAY), 1e-9)
    }

    @Test
    fun aGameTakesTheActiveAudioSlotAndEachMethodsSlot() {
        val game = PlayTiming.game(settings)
        assertEquals(PlayTiming(111.0, 8.0, 30.0, settings.scrollTimeMs, audioSlot = 2, touchSlot = 0, keyboardSlot = 1), game)
        assertEquals("audio slot 2 +111 ms; touch slot 0 +8 ms; keyboard slot 1 +30 ms", game.describe())
        // Both methods on one slot share its value.
        val shared = PlayTiming.game(settings.copy(inputLatency = settings.inputLatency.copy(keyboard = 0)))
        assertEquals(8.0, shared.keyboardLatencyMs, 0.0)
    }

    @Test
    fun theSampleGameHasNoAudioSlotAndKeepsTheInputSlotsAndThePreviewHasNone() {
        val sample = PlayTiming.sample(settings)
        assertEquals(0.0, sample.audioLatencyMs, 0.0)
        assertNull(sample.audioSlot)
        assertEquals(8.0, sample.touchLatencyMs, 0.0)
        assertEquals(30.0, sample.keyboardLatencyMs, 0.0)
        assertEquals("audio 0 ms (no slot); touch slot 0 +8 ms; keyboard slot 1 +30 ms", sample.describe())
        assertEquals(PlayTiming(0.0, 0.0, 0.0, 459.4), PlayTiming.preview(459.4))
    }

    /** Listen & Play keeps its trim with the audio offset's sign, in place of the audio slot; the input slots apply. */
    @Test
    fun listenAndPlayKeepsItsTrimWithTheAudioOffsetsSignAndTheInputSlots() {
        val ambient = PlayTiming.ambient(settings, trimMs = 20.0)
        assertEquals(-20.0, ambient.audioLatencyMs, 0.0)
        assertEquals("As the trim did: a +20 ms trim puts gameplay 20 ms ahead", 1_020.0, ambient.gameplayMs(1_000.0), 1e-9)
        assertEquals(1_020.0, ambient.renderMs(1_000.0), 1e-9)
        assertEquals("and touch's own latency applies", 1_012.0, ambient.inputMs(1_000.0, InputSource.TOUCH), 1e-9)
        assertEquals(990.0, ambient.inputMs(1_000.0, InputSource.KEYBOARD), 1e-9)
        assertEquals("audio −20 ms (no slot); touch slot 0 +8 ms; keyboard slot 1 +30 ms", ambient.describe())
    }

    /** A game on a clock that runs from 0 with the wall clock, frames at the times given, and a runtime-like session. */
    private inner class Game(val timing: PlayTiming) {
        val clock = FakePlayClock().apply { start(0, fromMs = 0.0) }
        val delivered = ArrayList<Pair<Double, LaneInput>>()
        val late = ArrayList<LaneInput>()
        val joined = ArrayList<Triple<InputSource, Double, Double>>()
        val clocks = ArrayList<Double>()
        var scriptUntil = Double.NaN
        private var sessionMs = Double.NEGATIVE_INFINITY
        private var sequence = 0L
        val queue = InputQueue(
            clock,
            taken = {},
            deliver = { chartMs, input, _ ->
                if (chartMs < sessionMs) late += input
                sessionMs = maxOf(sessionMs, chartMs)
                delivered += chartMs to input
            },
            becameLive = { source, chartMs, holdMs -> joined += Triple(source, chartMs, holdMs) },
        )

        fun press(atMs: Long, source: InputSource = InputSource.TOUCH, lane: Int = 0) =
            LaneInput(lane, press = true, atMs * ms, ++sequence, source).also(queue::add)

        fun release(atMs: Long, source: InputSource = InputSource.TOUCH, lane: Int = 0) =
            LaneInput(lane, press = false, atMs * ms, ++sequence, source).also(queue::add)

        /** A frame at [atMs]; the number of inputs it handed on. */
        fun frame(atMs: Long, script: (Double) -> Unit = {}): Int {
            val gameplayMs = timing.gameplayMs(clock.songTimeMs(atMs * ms)!!)
            val count = queue.frame(gameplayMs, timing, clock.isPaused) { until ->
                scriptUntil = until
                script(until)
            }
            clocks += queue.inputClockMs
            if (!clock.isPaused) sessionMs = maxOf(sessionMs, queue.inputClockMs - PlayController.INPUT_ALLOWANCE_MS)
            return count
        }

        fun chartTimes() = delivered.map { it.first }
    }

    @Test
    fun beforeAnyPressTheInputClockIsTheGameplayTime() {
        val game = Game(timing(audioMs = 40.0, touchMs = 30.0, keyboardMs = 60.0))
        for (at in listOf(0L, 16L, 33L, 50L)) {
            game.frame(at)
            assertEquals(at - 40.0, game.queue.inputClockMs, 1e-9)
        }
    }

    /** A player using only touch never waits, whatever the keyboard's slot says: keys never pressed are not live. */
    @Test
    fun oneMethodAloneNeverWaits() {
        for (source in listOf(InputSource.TOUCH, InputSource.KEYBOARD)) {
            val latencies = when (source) {
                InputSource.TOUCH -> timing(touchMs = 30.0, keyboardMs = 300.0)
                else -> timing(touchMs = 300.0, keyboardMs = 30.0)
            }
            val game = Game(latencies)
            var frameAt = 0L
            game.frame(frameAt)
            var sent = 0
            for (k in 0 until 60) {
                val atMs = 100L + k * 37
                // Handled within 40 ms, in order, and taken by the next frame after that.
                while (frameAt < atMs + (k % 5) * 8) game.frame(++frameAt)
                if (k % 2 == 0) game.press(atMs, source, lane = k % 4) else game.release(atMs - 20, source, lane = (k - 1) % 4)
                sent++
                frameAt += 16
                assertEquals("Input $k is handed on by the frame that takes it", 1, game.frame(frameAt))
            }
            assertEquals(sent, game.delivered.size)
            assertTrue(game.late.isEmpty())
            assertEquals(listOf(InputSource.TOUCH, InputSource.KEYBOARD).filter { it == source }, game.joined.map { it.first })
            // Each at its own time less its latency.
            assertEquals(70.0, game.delivered.first().first, 1e-9)
        }
    }

    /**
     * Touch alone at 0 ms leaves the input clock at the gameplay time; the keyboard's larger latency counts from its
     * first press, when the clock holds and never moves back. That press, made just after a touch already handed on,
     * is late and counted; the touches after it wait the difference.
     */
    @Test
    fun aSecondMethodJoiningWithALargerLatencyHoldsTheClockAndItsPressCanBeLate() {
        val game = Game(timing(touchMs = 0.0, keyboardMs = 30.0))
        game.frame(192)
        game.press(200, lane = 0)
        game.frame(208)
        assertEquals("Touch alone does not hold the clock", 208.0, game.queue.inputClockMs, 1e-9)
        assertEquals(listOf(Triple(InputSource.TOUCH, 200.0, 0.0)), game.joined)
        val key = game.press(210, InputSource.KEYBOARD, lane = 1)
        game.frame(224)
        assertEquals("Its first press holds the clock", 208.0, game.queue.inputClockMs, 1e-9)
        assertEquals(Triple(InputSource.KEYBOARD, 180.0, 14.0), game.joined.last())
        assertEquals("…and is late, after the touch at 200", listOf(key), game.late)
        val touch = game.press(230, lane = 2)
        for (at in listOf(240L, 256L)) {
            assertEquals("A touch waits for the keyboard's latency", 0, game.frame(at))
        }
        assertEquals(1, game.frame(272))
        assertEquals(230.0 to touch, game.delivered.last())
        assertTrue("The input clock never moved back", game.clocks.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1, game.late.size)
    }

    /** Once both have pressed, touches and keys 30 ms apart, made in any order, reach the session in chart-time order. */
    @Test
    fun twoMethodsThirtyApartInterleavedReachTheSessionInChartTimeOrder() {
        val game = Game(timing(audioMs = 15.0, touchMs = 0.0, keyboardMs = 30.0))
        game.press(100, InputSource.KEYBOARD, lane = 1)
        game.frame(104)
        game.press(110, InputSource.TOUCH, lane = 0)
        // The touch waits the keyboard's 30 ms, then both are live.
        var frameAt = 200L
        game.frame(frameAt)
        assertEquals(2, game.delivered.size)
        val before = game.delivered.size
        val lateBefore = game.late.size
        for (k in 0 until 80) {
            val atMs = 400L + k * 9
            val source = if ((k * 7) % 3 == 0) InputSource.KEYBOARD else InputSource.TOUCH
            val lane = if (source == InputSource.KEYBOARD) 1 + (k / 2) % 2 * 2 else (k / 2) % 2 * 2
            while (frameAt < atMs) game.frame(++frameAt)
            if ((k / 4) % 2 == 0) game.press(atMs, source, lane) else game.release(atMs, source, lane)
            frameAt += 4
            game.frame(frameAt)
        }
        game.frame(frameAt + 200)
        val times = game.chartTimes().drop(before)
        assertEquals(80, times.size)
        assertEquals("In chart-time order", times.sorted(), times)
        assertEquals("Nothing late once both are live", lateBefore, game.late.size)
    }

    @Test
    fun aSecondMethodWithASmallerLatencyChangesNothing() {
        val game = Game(timing(touchMs = 0.0, keyboardMs = 30.0))
        game.frame(92)
        game.press(100, InputSource.KEYBOARD)
        game.frame(108)
        val held = game.queue.inputClockMs
        game.press(130, InputSource.TOUCH, lane = 1)
        game.frame(140)
        assertEquals(0.0, game.joined.last().third, 0.0)
        assertEquals("The clock goes on at the gameplay time less the keyboard's latency", 110.0, game.queue.inputClockMs, 1e-9)
        assertTrue(held <= game.queue.inputClockMs)
        assertTrue(game.late.isEmpty())
    }

    @Test
    fun aNegativeLatencyIsHandedOnAtOnce() {
        val game = Game(timing(touchMs = -8.0))
        game.frame(96)
        game.press(100)
        assertEquals(1, game.frame(112))
        assertEquals("Pressed early by habit, judged later", 108.0, game.delivered.single().first, 1e-9)
        assertEquals(120.0, game.queue.inputClockMs, 1e-9)
    }

    /**
     * The autoplay is no method: it never makes the clock hold, and its script runs to the later of the gameplay time
     * and the input clock, so it is never late against touches with a negative latency.
     */
    @Test
    fun theAutoplayIsNoMethodAndRunsToTheInputClock() {
        val game = Game(timing(touchMs = -8.0))
        game.frame(100) { until -> game.queue.add(LaneInput(3, true, (until * ms).toLong(), 1_000, InputSource.AUTOPLAY)) }
        assertEquals(100.0, game.scriptUntil, 1e-9)
        assertTrue("The autoplay does not join", game.joined.isEmpty())
        game.press(110)
        game.frame(116)
        assertEquals("With touch 8 ms early by habit, the autoplay presses up to 8 ms ahead", 124.0, game.scriptUntil, 1e-9)
        assertTrue(game.late.isEmpty())
    }

    /** The session advances to the input clock less 100 ms: the allowance stays 100 ms, latency or not. */
    @Test
    fun theWatermarkIsTheInputClockLessTheAllowance() {
        val game = Game(timing(audioMs = 50.0, touchMs = 30.0))
        game.press(100)
        var at = 104L
        game.frame(at)
        // A touch handled 95 ms after it happened is judged at its time; one handled 120 ms after it is late.
        while (at < 1_095) game.frame(++at)
        val prompt = game.press(1_000)
        game.frame(++at)
        while (at < 1_220) game.frame(++at)
        val tardy = game.press(1_100)
        game.frame(++at)
        assertEquals(1_000.0 - 50 - 30, game.delivered.first { it.second == prompt }.first, 1e-9)
        assertEquals(listOf(tardy), game.late)
        assertEquals(1_221.0 - 50 - 30, game.queue.inputClockMs, 1e-9)
    }

    /**
     * The input latency is applied in song time, after the clock: it never moves an input across a pause. A press made
     * before the pause and taken by a paused frame keeps its own song time; one made just after the resume is not put
     * before the pause, but at its time.
     */
    @Test
    fun anInputAcrossAPauseKeepsItsSongTime() {
        val game = Game(timing(touchMs = 30.0))
        game.frame(984)
        val before = game.press(990)
        game.clock.pause(1_000 * ms)
        game.frame(1_200)
        assertEquals(960.0 to before, game.delivered.single())
        game.clock.resume(5_000 * ms)
        val after = game.press(5_010, lane = 1)
        game.frame(5_016)
        assertEquals("Song time 1 010 less 30 ms would be before the pause", 1_000.0 to after, game.delivered.last())
        assertTrue(game.late.isEmpty())
    }
}
