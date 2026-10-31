package io.ensomi.android.recognition

import io.ensomi.android.play.NoteSpec
import io.ensomi.android.play.PlayChart
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmbientPlayClockTest {
    private val ms = 1_000_000L

    /** 61 s of the song was captured at 5 s on the monotonic clock. */
    private val anchor = LockAnchor(referenceMs = 61_000.0, captureNanos = 5_000 * ms)

    private fun running() = AmbientPlayClock(anchor).also { it.start(0, fromMs = -2_000.0) }

    @Test
    fun readsNothingBeforeItStarts() {
        assertNull(AmbientPlayClock(anchor).songTimeMs(5_000 * ms))
    }

    @Test
    fun runsAtSpeedOneFromTheLockedCaptureTime() {
        val clock = running()
        assertEquals(61_000.0, clock.songTimeMs(5_000 * ms)!!, 1e-6)
        assertEquals(62_500.0, clock.songTimeMs(6_500 * ms)!!, 1e-6)
        assertEquals(161_000.0, clock.songTimeMs(105_000 * ms)!!, 1e-6)
    }

    @Test
    fun theTimeOfTheCallIsNotTheAnchor() {
        // However late the clock is made or asked, the same moment reads the same.
        val early = AmbientPlayClock(anchor).also { it.start(5_001 * ms, 0.0) }
        val late = AmbientPlayClock(anchor).also { it.start(40_000 * ms, 0.0) }
        assertEquals(early.songTimeMs(50_000 * ms)!!, late.songTimeMs(50_000 * ms)!!, 1e-9)
        assertEquals(106_000.0, late.songTimeMs(50_000 * ms)!!, 1e-6)
    }

    @Test
    fun theLeadInPlayAsksForMeansNothing() {
        val clock = AmbientPlayClock(anchor)
        clock.start(5_000 * ms, fromMs = -2_000.0)
        assertEquals(61_000.0, clock.songTimeMs(5_000 * ms)!!, 1e-6)
    }

    @Test
    fun aMomentBeforeTheLockIsEarlierInTheSong() {
        assertEquals(60_000.0, running().songTimeMs(4_000 * ms)!!, 1e-6)
    }

    @Test
    fun nanoTimeAtIsTheInverse() {
        val clock = running()
        assertEquals(6_500 * ms, clock.nanoTimeAt(62_500.0))
        assertEquals(62_500.0, clock.songTimeMs(clock.nanoTimeAt(62_500.0))!!, 1e-3)
    }

    @Test
    fun pauseHoldsTheGameAndResumeFindsTheMusicWhereItHasGotTo() {
        val clock = running()
        clock.pause(6_000 * ms)
        assertTrue(clock.isPaused)
        assertEquals(62_000.0, clock.songTimeMs(9_000 * ms)!!, 1e-6)
        clock.resume(10_000 * ms)
        assertFalse(clock.isPaused)
        assertEquals(66_000.0, clock.songTimeMs(10_000 * ms)!!, 1e-6)
    }

    @Test
    fun aPauseBeforeTheStartIsNotHeld() {
        val clock = AmbientPlayClock(anchor)
        clock.pause(6_000 * ms)
        assertFalse(clock.isPaused)
    }

    @Test
    fun whereTheMusicIsHoldsWhileTheGameIsPaused() {
        val clock = running()
        clock.pause(6_000 * ms)
        assertEquals(66_000.0, clock.heardMs(10_000 * ms), 1e-6)
    }

    @Test
    fun aLateAskStillFitsTheCaptureNotTheRead() {
        // A lock read 800 ms after the audio it refers to: the clock is that much further on, not behind.
        val clock = running()
        val readAt = anchor.captureNanos + 800 * ms
        assertEquals(anchor.referenceMs + 800, clock.songTimeMs(readAt)!!, 1e-6)
    }
}

class JoinPointTest {
    /** The scheduler's own test: lane 1 holds over 1000 to 2000, lane 2 over 1800 to 2600, taps at 2200 and 2600. */
    private val notes = listOf(
        NoteSpec(1, 1000, 2000),
        NoteSpec(2, 1800, 2600),
        NoteSpec(0, 2200),
        NoteSpec(0, 2600),
    )
    private val chart = PlayChart("song", 3000, notes)

    @Test
    fun outsideEveryHoldItIsTheTimeAskedFor() {
        assertEquals(700.0, JoinPoint.at(notes, 700.0), 0.0)
        assertEquals(2800.0, JoinPoint.at(notes, 2800.0), 0.0)
    }

    @Test
    fun insideAHoldItIsItsEndThenTheEndOfAHoldOpenThere() {
        assertEquals(2600.0, JoinPoint.at(notes, 1500.0), 0.0)
    }

    @Test
    fun aHoldThatEndsAtATimeIsNotOpenAtIt() {
        assertEquals(2000.0, JoinPoint.at(listOf(NoteSpec(1, 1000, 2000)), 2000.0), 0.0)
    }

    @Test
    fun aHoldThatStartsAtATimeIsNotOpenAtIt() {
        assertEquals(1000.0, JoinPoint.at(listOf(NoteSpec(1, 1000, 2000)), 1000.0), 0.0)
    }

    @Test
    fun aPlanSkipsWhatIsPastAndKeepsTheSong() {
        val plan = JoinPoint.plan(chart, 1500.0)!!
        assertEquals(2600.0, plan.joinMs, 0.0)
        assertEquals(listOf(NoteSpec(0, 2600)), plan.chart.notes)
        assertEquals(3, plan.notesSkipped)
        assertEquals(3000, plan.chart.durationMs)
    }

    @Test
    fun aHoldThatStartsAtTheJoinIsPlayed() {
        val plan = JoinPoint.plan(chart, 1000.0)!!
        assertEquals(notes, plan.chart.notes)
        assertEquals(0, plan.notesSkipped)
    }

    @Test
    fun whenNoNoteIsLeftThereIsNothingToJoin() {
        assertNull(JoinPoint.plan(chart, 2601.0))
        assertNull(JoinPoint.plan(chart.copy(notes = emptyList()), 0.0))
    }
}

class SessionTraceTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun everyLineHasItsClockCidSeqAndEvent() {
        val file = File(folder.root, "traces/session.jsonl")
        var clock = 41L
        val trace = SessionTrace(file, "abc") { ++clock }
        trace.log("share.received", mapOf("title" to "晴天", "song_id" to 186016L, "nothing" to null))
        trace.log("session.end", emptyMap())
        trace.close()
        assertEquals(
            listOf(
                """{"elapsed_ns":42,"cid":"abc","seq":0,"event":"share.received","title":"晴天","song_id":186016,"nothing":null}""",
                """{"elapsed_ns":43,"cid":"abc","seq":1,"event":"session.end"}""",
            ),
            file.readLines(),
        )
    }

    @Test
    fun afterAProcessDeathItGoesOnWhereItStopped() {
        val file = File(folder.root, "session.jsonl")
        SessionTrace(file, "abc") { 1 }.apply {
            log("one", emptyMap())
            log("two", emptyMap())
            close()
        }
        SessionTrace(file, "abc") { 2 }.apply {
            log("three", emptyMap())
            close()
        }
        val lines = file.readLines()
        assertEquals(3, lines.size)
        assertTrue(lines.last(), lines.last().contains("\"seq\":2"))
    }
}

class WavFormatTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun wav(tag: Int, channels: Int, rate: Int, bits: Int, data: ByteArray, junk: Boolean = false): File {
        val bytesPerFrame = channels * bits / 8
        val extra = if (junk) 8 + 3 + 1 else 0
        val buffer = ByteBuffer.allocate(44 + extra + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + extra + data.size).put("WAVE".toByteArray())
        if (junk) {
            buffer.put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0))
        }
        buffer.put("fmt ".toByteArray()).putInt(16)
        buffer.putShort(tag.toShort()).putShort(channels.toShort()).putInt(rate).putInt(rate * bytesPerFrame)
        buffer.putShort(bytesPerFrame.toShort()).putShort(bits.toShort())
        buffer.put("data".toByteArray()).putInt(data.size).put(data)
        return folder.newFile().apply { writeBytes(buffer.array()) }
    }

    private fun shorts(vararg values: Short): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    @Test
    fun readsStereoShortsToMono() {
        val file = wav(1, 2, 44_100, 16, shorts(16_384, 0, -16_384, -16_384))
        RandomAccessFile(file, "r").use { input ->
            val format = WavFormat.read(input)
            assertEquals(44_100, format.sampleRate)
            assertEquals(2, format.channels)
            assertEquals(8L, format.dataBytes)
            input.seek(format.dataOffset)
            val bytes = ByteArray(8).also(input::readFully)
            val mono = FloatArray(2)
            format.toMono(bytes, 2, mono)
            assertEquals(0.25f, mono[0], 1e-6f)
            assertEquals(-0.5f, mono[1], 1e-6f)
        }
    }

    @Test
    fun readsFloatsAndSkipsChunksItDoesNotKnow() {
        val data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.75f).array()
        val file = wav(3, 1, 48_000, 32, data, junk = true)
        RandomAccessFile(file, "r").use { input ->
            val format = WavFormat.read(input)
            assertTrue(format.float)
            input.seek(format.dataOffset)
            val bytes = ByteArray(4).also(input::readFully)
            val mono = FloatArray(1)
            format.toMono(bytes, 1, mono)
            assertEquals(0.75f, mono[0], 1e-6f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesWhatItCannotRead() {
        val file = folder.newFile().apply { writeBytes(ByteArray(64)) }
        RandomAccessFile(file, "r").use { WavFormat.read(it) }
    }
}
