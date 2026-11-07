package io.ensomi.android.settings

import io.ensomi.android.play.InputSource
import io.ensomi.android.play.JudgedPress
import io.ensomi.android.play.PlayTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The calibration's log lines (latency.md, Diagnostics): what a check recomputes the errors from, and never a name. */
class CalibrationLogTest {
    private val timing = PlayTiming(-2.0, 8.0, 30.0, scrollTimeMs = 459.0, audioSlot = 1, touchSlot = 0, keyboardSlot = 1)

    private fun press(source: InputSource, offsetMs: Double?, late: Boolean = false, chartMs: Double? = null): JudgedPress {
        val songMs = 5_523.4
        val ownMs = timing.inputMs(songMs, source)
        return JudgedPress(source, 9_876_543_210L, songMs, ownMs, chartMs ?: ownMs, timing, offsetMs, offsetMs?.let { "p1" }, late)
    }

    @Test
    fun aPressSaysItsMethodSlotsTimesAndTheRuntimesError() {
        val line = CalibrationLog.press(press(InputSource.TOUCH, offsetMs = 17.4))
        assertEquals(
            "Press: touch on input slot 0 +8 ms, audio slot 1 -2 ms; event 9876543210 ns, song 5523.40 ms, " +
                "chart 5517.40 ms, from the nearest click +23.40 ms; error +17.40 ms (p1)",
            line,
        )
        val key = CalibrationLog.press(press(InputSource.KEYBOARD, offsetMs = null))
        assertTrue(key, key.startsWith("Press: keyboard on input slot 1 +30 ms, audio slot 1 -2 ms;"))
        assertTrue(key, key.endsWith("; no hit"))
        assertTrue(CalibrationLog.press(press(InputSource.TOUCH, 3.0, late = true)).endsWith("late: judged at the session's clock, not a reading"))
        assertTrue(CalibrationLog.press(press(InputSource.TOUCH, 3.0, chartMs = 5_600.0)).endsWith("not a reading"))
    }

    @Test
    fun eachChangeOfAValueOrASlotIsOneLineAndNamesAreNeverLogged() {
        val old = PlaySettings(
            audioLatency = AudioLatency(listOf(LatencySlot("WH-1000XM4", 142), LatencySlot(), LatencySlot()), active = 0),
        )
        val new = old.copy(
            audioLatency = old.audioLatency.withActive(1).withActiveMs(-9).withName(0, "Buds"),
            inputLatency = old.inputLatency.withMs(1, 31).withKeyboard(2).withName(2, "Desk keyboard"),
        )
        val lines = CalibrationLog.changes(old, new)
        assertEquals(
            listOf(
                "Audio latency: slot 1 active, was 0",
                "Audio latency slot 0: named",
                "Audio latency slot 1: -9 ms, was +0 ms",
                "Input latency slot 1: +31 ms, was +0 ms",
                "Input latency slot 2: named",
                "Keyboard uses input slot 2, was 1",
            ),
            lines,
        )
        assertFalse(lines.joinToString().contains("Buds") || lines.joinToString().contains("Desk"))
        assertEquals(emptyList<String>(), CalibrationLog.changes(new, new))
        assertEquals(listOf("Audio latency slot 0: name cleared"), CalibrationLog.changes(new, new.copy(audioLatency = new.audioLatency.withName(0, ""))))
    }
}
