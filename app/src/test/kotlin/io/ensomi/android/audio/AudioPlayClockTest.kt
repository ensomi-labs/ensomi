package io.ensomi.android.audio

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlayClockTest {
    private val ms = 1_000_000L
    private val rate = 48_000

    /** An output whose timestamps the test sets one by one. */
    private class ScriptedOutput : PlayOutput {
        var next: OutputTimestamp? = null
        var reads = 0
        var plays = 0
        var pauses = 0

        override fun timestamp(): OutputTimestamp? {
            reads++
            return next
        }

        override fun play() {
            plays++
        }

        override fun pause() {
            pauses++
        }
    }

    private fun frames(songMs: Double, leadIn: Long = 0) = (songMs * rate / 1000).toLong() + leadIn

    @Test
    fun holdsNothingUntilAFramePlaysAfterTheStart() {
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 96_000)
        assertNull(clock.songTimeMs(0))
        clock.start(nanoTime = 1_000 * ms, fromMs = -2_000.0)
        assertEquals(1, output.plays)
        clock.update(1_000 * ms)
        assertNull("No timestamp yet", clock.songTimeMs(1_000 * ms))
        // A position of 0, or one stamped before the start, is not a frame heard since it.
        output.next = OutputTimestamp(0, 1_005 * ms)
        clock.update(1_010 * ms)
        assertNull(clock.songTimeMs(1_010 * ms))
        output.next = OutputTimestamp(480, 990 * ms)
        clock.update(1_020 * ms)
        assertNull(clock.songTimeMs(1_020 * ms))
        output.next = OutputTimestamp(480, 1_025 * ms)
        clock.update(1_030 * ms)
        // 480 frames are 10 ms into the two-second lead-in.
        assertEquals(-1_990.0, clock.songTimeMs(1_025 * ms)!!, 1e-9)
        assertEquals(10.0, clock.songTimeMs(3_025 * ms)!!, 1e-9)
    }

    @Test
    fun runsAtRateOneFromTheNewestPair() {
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 0)
        clock.start(0, 0.0)
        output.next = OutputTimestamp(frames(100.0), 100 * ms)
        clock.update(110 * ms)
        assertEquals(150.0, clock.songTimeMs(150 * ms)!!, 1e-9)
        assertEquals(150 * ms, clock.nanoTimeAt(150.0))
    }

    @Test
    fun extendsTheLow32BitsAcrossAWrap() {
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 0)
        clock.start(0, 0.0)
        val nearTheTop = (1L shl 32) - 4_800 // 100 ms before the position wraps
        output.next = OutputTimestamp(nearTheTop, 10 * ms)
        clock.update(10 * ms)
        val before = clock.songTimeMs(10 * ms)!!
        // 200 ms later the low 32 bits have wrapped; the platform may report them as a negative or small value.
        output.next = OutputTimestamp((nearTheTop + 9_600) and 0xFFFF_FFFFL, 210 * ms)
        clock.update(10 * ms + AudioPlayClock.WARM_NANOS + AudioPlayClock.STABLE_POLL_NANOS)
        assertEquals(before + 200.0, clock.songTimeMs(210 * ms)!!, 1e-6)
        output.next = OutputTimestamp((nearTheTop + 19_200) or (0xFFFF_FFFFL shl 32), 410 * ms)
        clock.update(10 * ms + AudioPlayClock.WARM_NANOS + 2 * AudioPlayClock.STABLE_POLL_NANOS)
        assertEquals(before + 400.0, clock.songTimeMs(410 * ms)!!, 1e-6)
    }

    @Test
    fun holdsAStepBackUntilTheNewPairCatchesUp() {
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 0)
        clock.start(0, 0.0)
        output.next = OutputTimestamp(frames(1_000.0), 1_000 * ms)
        clock.update(1_000 * ms)
        // A frame was drawn for 1 040 ms (its presentation time) before the next pair came.
        assertEquals(1_040.0, clock.songTimeMs(1_040 * ms)!!, 1e-9)
        // The output says it is 30 ms behind the line.
        output.next = OutputTimestamp(frames(990.0), 1_020 * ms)
        clock.update(1_020 * ms)
        assertEquals(1, clock.stepsBack)
        // Up to the latest time asked for, nothing changes; then the clock holds 1 040 until the new line gets there.
        assertEquals(1_020.0, clock.songTimeMs(1_020 * ms)!!, 1e-9)
        assertEquals(1_040.0, clock.songTimeMs(1_040 * ms)!!, 1e-9)
        assertEquals(1_040.0, clock.songTimeMs(1_060 * ms)!!, 1e-9)
        assertEquals(1_040.0, clock.songTimeMs(1_070 * ms)!!, 1e-9)
        assertEquals(1_050.0, clock.songTimeMs(1_080 * ms)!!, 1e-9)
        // Up to the latest time asked for, inputs stamped in the past included, the old line still holds.
        assertEquals(1_010.0, clock.songTimeMs(1_010 * ms)!!, 1e-9)
    }

    @Test
    fun takesAPairAheadAtOnce() {
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 0)
        clock.start(0, 0.0)
        output.next = OutputTimestamp(frames(1_000.0), 1_000 * ms)
        clock.update(1_000 * ms)
        output.next = OutputTimestamp(frames(1_025.0), 1_020 * ms)
        clock.update(1_020 * ms)
        assertEquals(0, clock.stepsBack)
        assertEquals(1_035.0, clock.songTimeMs(1_030 * ms)!!, 1e-9)
    }

    @Test
    fun pauseFreezesAndResumeWaitsForAFrameHeardAfterIt() {
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 0)
        clock.start(0, 0.0)
        output.next = OutputTimestamp(frames(5_000.0), 5_000 * ms)
        clock.update(5_000 * ms)
        assertEquals(5_016.0, clock.songTimeMs(5_016 * ms)!!, 1e-9)
        clock.pause(5_010 * ms)
        assertTrue(clock.isPaused)
        assertEquals(1, output.pauses)
        // Held at the furthest time shown, whatever is asked.
        assertEquals(5_016.0, clock.songTimeMs(9_000 * ms)!!, 1e-9)
        clock.update(9_000 * ms)
        // The output stopped at 5 012 ms; resumed at 20 s.
        output.next = OutputTimestamp(frames(5_012.0), 5_013 * ms)
        clock.resume(20_000 * ms)
        assertFalse(clock.isPaused)
        assertEquals(2, output.plays)
        clock.update(20_000 * ms)
        assertEquals("A timestamp from before the resume is not taken", 5_016.0, clock.songTimeMs(20_010 * ms)!!, 1e-9)
        output.next = OutputTimestamp(frames(5_012.0), 20_030 * ms)
        clock.update(20_030 * ms)
        assertEquals("Nor one that has not moved", 5_016.0, clock.songTimeMs(20_030 * ms)!!, 1e-9)
        output.next = OutputTimestamp(frames(5_013.0), 20_041 * ms)
        clock.update(20_041 * ms)
        // 5 013 ms at 20.041 s: held at 5 016 until the line reaches it at 20.044 s.
        assertEquals(5_016.0, clock.songTimeMs(20_042 * ms)!!, 1e-9)
        assertEquals(5_016.0, clock.songTimeMs(20_044 * ms)!!, 1e-9)
        assertEquals(5_020.0, clock.songTimeMs(20_048 * ms)!!, 1e-9)
    }

    @Test
    fun readsEvery10MsWhileItWarmsUpThenOnceASecond() {
        val output = ScriptedOutput()
        val clock = AudioPlayClock(output, rate, leadInFrames = 0)
        clock.start(0, 0.0)
        output.next = OutputTimestamp(frames(5.0), 5 * ms)
        var t = 0L
        while (t < 3_000 * ms) {
            output.next = OutputTimestamp(frames(t / 1e6), t)
            clock.update(t)
            t += 4 * ms
        }
        // The first second: a read every 10 ms (4 ms frames, so every 12 ms); then two more, at 1 s intervals.
        val warm = output.reads
        assertTrue("reads $warm", warm in 80..90)
        clock.outputChanged(t)
        val before = output.reads
        repeat(25) {
            clock.update(t)
            t += 4 * ms
        }
        assertTrue("A route change reads often again", output.reads - before >= 8)
    }

    /**
     * A whole song, scripted: frames at 60 Hz, each asking for its time now and at its presentation two frames later,
     * timestamps with a millisecond of jitter, a 40 ms underrun in the middle and a pause. Neither series ever goes
     * back, and the clock ends on the output's own time.
     */
    @Test
    fun neverRunsBackwardsOverAWholeSong() {
        val random = Random(7)
        var stalledMs = 0.0
        var heardAt = 0.0
        var lastNanos = 0L
        var playing = false
        val output = object : PlayOutput {
            var nowNanos = 0L

            override fun timestamp(): OutputTimestamp {
                val stampNanos = nowNanos - 3 * ms + (random.nextDouble(-1.0, 1.0) * ms).toLong()
                return OutputTimestamp(frames(heardAt - (nowNanos - stampNanos) / 1e6, leadIn = 96_000), stampNanos)
            }

            override fun play() {
                playing = true
            }

            override fun pause() {
                playing = false
            }
        }
        val clock = AudioPlayClock(output, rate, leadInFrames = 96_000)
        val frameNanos = 16_666_667L
        var now = 1_000 * ms
        clock.start(now, -2_000.0)
        heardAt = -2_000.0
        lastNanos = now
        var lastNow = Double.NEGATIVE_INFINITY
        var lastPresent = Double.NEGATIVE_INFINITY
        var frames = 0
        while (heardAt < 180_000) {
            now += frameNanos
            output.nowNanos = now
            val step = (now - lastNanos) / 1e6
            lastNanos = now
            if (playing) {
                if (heardAt in 60_000.0..60_040.0 && stalledMs < 40) stalledMs += step else heardAt += step
            }
            if (frames == 5_000) clock.pause(now)
            if (frames == 5_100) clock.resume(now)
            clock.update(now)
            val atNow = clock.songTimeMs(now) ?: continue
            val atPresent = clock.songTimeMs(now + 2 * frameNanos)!!
            assertTrue("now went back at frame $frames: $atNow < $lastNow", atNow >= lastNow)
            assertTrue("present went back at frame $frames: $atPresent < $lastPresent", atPresent >= lastPresent)
            lastNow = atNow
            lastPresent = atPresent
            frames++
        }
        assertEquals(heardAt, clock.songTimeMs(now)!!, 2.0)
        assertTrue("The underrun was held, not stepped back", clock.stepsBack >= 1)
    }
}
