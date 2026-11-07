package io.ensomi.android.play

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** The key log of debug and checks builds (keyboard.md, Diagnostics). */
class KeyLogTest {
    @Test
    fun theKeyLogHasACellForEveryColumnAndTheHandlingDelay() {
        val log = KeyLog()
        assertEquals("events=0", log.summary())
        log.record(KeyInput(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, 0, 4, 1_000), 1_002_000_000, 44, 0, 0, sent = true)
        log.record(
            KeyInput(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0, KeyEvent.FLAG_CANCELED, -1, 1_100),
            1_110_000_000, 0, -1, KeyLog.PAUSE, sent = false,
        )
        val rows = log.csv().trim().lines()
        assertEquals("action,eventNanos,handledNanos,keyCode,scanCode,repeat,flags,keyboard,lane,sent", rows[0])
        assertEquals("0,1000000000,1002000000,${KeyEvent.KEYCODE_Z},44,0,0,0,0,1", rows[1])
        assertEquals("1,1100000000,1110000000,${KeyEvent.KEYCODE_ESCAPE},0,0,${KeyEvent.FLAG_CANCELED},-1,-2,0", rows[2])
        assertEquals("events=2 sent=1 handling_p50=2.00ms handling_p99=2.00ms handling_max=10.00ms", log.summary())
    }
}
