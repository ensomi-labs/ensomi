package io.ensomi.android.settings

import io.ensomi.android.play.FakePlayClock
import io.ensomi.android.play.InputQueue
import io.ensomi.android.play.InputSource
import io.ensomi.android.play.InputStamp
import io.ensomi.android.play.JudgedPress
import io.ensomi.android.play.LaneHolder
import io.ensomi.android.play.LaneInput
import io.ensomi.android.play.LaneSender
import io.ensomi.android.play.PlayTiming
import io.ensomi.android.play.SampleCharts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calibration's readings (latency.md, Timing calibration): the last 16 hits of each method, shown as they would be
 * judged with the values now, and their average; which presses give a reading at all.
 */
class CalibrationReadingsTest {
    private val touch = InputSource.TOUCH
    private val keys = InputSource.KEYBOARD

    private fun hit(method: InputSource, errorMs: Double, judgedWithMs: Double = 0.0) = CalibrationHit(method, errorMs, judgedWithMs)

    private fun timing(audioMs: Double = 0.0, touchMs: Double = 0.0, keyboardMs: Double = 0.0) =
        PlayTiming(audioMs, touchMs, keyboardMs, scrollTimeMs = 1_000.0, audioSlot = 0, touchSlot = 0, keyboardSlot = 1)

    @Test
    fun eachMethodKeepsItsLastSixteenHits() {
        var readings = CalibrationReadings()
        repeat(20) { readings = readings.add(hit(touch, it.toDouble())) }
        repeat(3) { readings = readings.add(hit(keys, -10.0 - it)) }
        assertEquals(16, readings.count(touch))
        assertEquals(3, readings.count(keys))
        assertEquals("The oldest four touches went", (4..19).map { it.toDouble() }, readings.shown(touch, 0.0))
        assertEquals("Keys are their own", listOf(-10.0, -11.0, -12.0), readings.shown(keys, 0.0))
        readings = readings.add(hit(keys, -13.0))
        assertEquals("A key hit pushes out no touch hit", 16, readings.count(touch))
    }

    @Test
    fun theAverageIsOfTheHitsShownForTheMethodChosen() {
        var readings = CalibrationReadings()
        assertNull(readings.averageMs(touch, 0.0))
        listOf(4.0, 9.0, 2.0, 11.0).forEach { readings = readings.add(hit(touch, it)) }
        readings = readings.add(hit(keys, -30.0))
        assertEquals(6.5, readings.averageMs(touch, 0.0)!!, 1e-9)
        assertEquals(-30.0, readings.averageMs(keys, 0.0)!!, 1e-9)
        // Only the last 16 count: an old outlier leaves the average once 16 newer hits are in.
        readings = CalibrationReadings().add(hit(touch, 150.0))
        repeat(16) { readings = readings.add(hit(touch, -6.0)) }
        assertEquals(-6.0, readings.averageMs(touch, 0.0)!!, 1e-9)
    }

    /**
     * A press is judged at its song time less A and its method's I. A hit made before a value changed is shown as it
     * would be judged now: its error less the rise in A + I since.
     */
    @Test
    fun aHitMovesByEachLaterChangeOfAOrOfItsMethodsI() {
        val first = PlaySettings(
            audioLatency = AudioLatency().withActiveMs(100),
            inputLatency = InputLatency().withMs(0, 20).withMs(1, 40),
        )
        var readings = CalibrationReadings()
        // Touch hits 12 ms late and key hits 5 ms early, with A 100, touch's I 20 and the keyboard's 40.
        readings = readings.add(hit(touch, 12.0, CalibrationEdits.latencyMs(first, touch)))
        readings = readings.add(hit(keys, -5.0, CalibrationEdits.latencyMs(first, keys)))

        val audioUp = CalibrationEdits.audioStep(first, 12)
        assertEquals("A moves the touches", listOf(0.0), readings.shown(touch, CalibrationEdits.latencyMs(audioUp, touch)))
        assertEquals("and the keys", listOf(-17.0), readings.shown(keys, CalibrationEdits.latencyMs(audioUp, keys)))

        val touchDown = CalibrationEdits.inputStep(audioUp, touch, -3)
        assertEquals("Touch's I moves the touches", listOf(3.0), readings.shown(touch, CalibrationEdits.latencyMs(touchDown, touch)))
        assertEquals("and not the keys", listOf(-17.0), readings.shown(keys, CalibrationEdits.latencyMs(touchDown, keys)))

        // Touch moves to the keyboard's slot: its I is now 40, so the touches move by the difference.
        val shared = CalibrationEdits.inputSlot(touchDown, touch, 1)
        assertEquals(listOf(-20.0), readings.shown(touch, CalibrationEdits.latencyMs(shared, touch)))
        // And back as they were, once the values are back.
        assertEquals(listOf(12.0), readings.shown(touch, CalibrationEdits.latencyMs(first, touch)))
        assertEquals(listOf(-5.0), readings.shown(keys, CalibrationEdits.latencyMs(first, keys)))
    }

    @Test
    fun clearHitsEmptiesTheReadingsAndChangesNoValue() {
        val settings = PlaySettings(audioLatency = AudioLatency().withActiveMs(30))
        var readings = CalibrationReadings().add(hit(touch, 3.0)).add(hit(keys, 4.0))
        readings = readings.cleared()
        assertEquals(0, readings.count(touch) + readings.count(keys))
        assertEquals(30, settings.audioLatencyMs)
    }

    private fun press(
        offsetMs: Double? = 8.0,
        ownMs: Double = 1_492.0,
        chartMs: Double = ownMs,
        late: Boolean = false,
        source: InputSource = touch,
        timing: PlayTiming = timing(audioMs = 10.0, touchMs = 8.0),
        songMs: Double? = 1_510.0,
    ) = JudgedPress(source, eventNanos = 5_000_000_000L, songMs, ownMs, chartMs, timing, offsetMs, "p1", late)

    @Test
    fun onlyAPressJudgedAtItsOwnTimeIsAReading() {
        assertEquals(CalibrationHit(touch, 8.0, 18.0), CalibrationReadings.hitOf(press()))
        assertNull("It hit nothing", CalibrationReadings.hitOf(press(offsetMs = null)))
        assertNull("Late: judged at the session's clock", CalibrationReadings.hitOf(press(late = true)))
        assertNull("Held to its lane's last input", CalibrationReadings.hitOf(press(chartMs = 1_495.0)))
        assertNull("No song time to say what it was judged with", CalibrationReadings.hitOf(press(songMs = null)))
        val key = CalibrationReadings.hitOf(press(source = keys, ownMs = 1_470.0, timing = timing(audioMs = 10.0, keyboardMs = 30.0)))
        assertEquals("A key's reading is the keyboard's", CalibrationHit(keys, 8.0, 40.0), key)
    }

    @Test
    fun theStampSaysWhatAPressWasJudgedWith() {
        val pressed = press()
        assertTrue(pressed.exact)
        assertEquals("A + I from its own song and chart time", 18.0, pressed.stampedWithMs!!, 1e-9)
        assertFalse(press(late = true).exact)
        assertFalse(press(chartMs = 1_500.0).exact)
    }

    /**
     * Through the queue: touches and keys at known times, with the values changed between them as the screen changes
     * them, are stamped with the values of the frame that took them, so each reading's A + I is the one it was judged
     * with; a key held to its lane's last input is no reading.
     */
    @Test
    fun pressesAreStampedWithTheValuesTheFrameTookThemAt() {
        val ms = 1_000_000L
        val clock = FakePlayClock().apply { start(0, fromMs = 0.0) }
        val stamped = ArrayList<Triple<Double, LaneInput, InputStamp>>()
        val queue = InputQueue(clock, taken = {}, deliver = { chartMs, input, stamp -> stamped += Triple(chartMs, input, stamp) })
        var sequence = 0L
        val sender = LaneSender({ ++sequence }, queue::add)
        val lane = SampleCharts.CALIBRATION_LANE
        val finger = LaneHolder.Pointer(0)
        val key = LaneHolder.Key(4, 52)
        fun frame(atMs: Long, timing: PlayTiming) {
            queue.frame(timing.gameplayMs(clock.songTimeMs(atMs * ms)!!), timing, paused = false)
        }

        val before = timing(audioMs = 100.0, touchMs = 20.0, keyboardMs = 40.0)
        sender.press(finger, lane, 1_520 * ms)
        frame(1_530, before)
        sender.release(finger, 1_560 * ms)
        frame(1_570, before)
        // The audio latency rises by 50 between frames, as a slider moves it.
        val after = before.copy(audioLatencyMs = 150.0)
        sender.press(key, lane, 2_030 * ms)
        frame(2_040, after)
        sender.release(key, 2_060 * ms)
        sender.press(finger, lane, 2_061 * ms)
        frame(2_070, after)
        // A key pressed 10 ms after a finger lets go, with its larger latency: its own time is before the release's.
        sender.release(finger, 2_100 * ms)
        sender.press(key, lane, 2_110 * ms)
        frame(2_120, after)

        val presses = stamped.filter { it.second.press }
        assertEquals(listOf(touch, keys, touch, keys), presses.map { it.second.source })
        assertEquals(listOf(1_400.0, 1_840.0, 1_891.0, 1_930.0), presses.map { it.first })
        assertEquals(
            "A + I as each frame had it: the new A from the third frame on",
            listOf(120.0, 190.0, 170.0, 190.0),
            presses.map { it.third.songMs!! - it.third.ownMs },
        )
        val (heldChart, _, heldStamp) = presses.last()
        assertEquals("Its own chart time", 1_920.0, heldStamp.ownMs, 1e-9)
        assertEquals("held to the finger's release, so no reading", 1_930.0, heldChart, 1e-9)
        assertEquals(
            "In chart-time order, then as sent",
            listOf(1_400.0, 1_440.0, 1_840.0, 1_870.0, 1_891.0, 1_930.0, 1_930.0),
            stamped.map { it.first },
        )
    }
}
