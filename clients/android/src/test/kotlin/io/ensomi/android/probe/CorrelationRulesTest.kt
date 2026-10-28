package io.ensomi.android.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorrelationRulesTest {
    private val sessionStart = 1_000_000L

    private fun event(kind: WatchKind, name: String = "song.mp3") = WatchEvent(
        kind = kind,
        path = "/storage/emulated/0/netease/cloudmusic/Music/$name",
        tMonoNs = 1,
        wallMs = 2,
    )

    private fun snapshot(
        name: String = "song.mp3",
        size: Long = 4_000_000,
        modified: Long = sessionStart + 5_000,
    ) = FileSnapshot(name, size, modified)

    @Test
    fun `a completed, decodable, settled file is ready`() {
        val verdict = CorrelationRules.judge(
            event = event(WatchKind.CLOSE_WRITE),
            snapshot = snapshot(),
            sessionStartWallMs = sessionStart,
            decodeOk = true,
            durationMs = 276_386,
            expectedDurationMs = 276_000,
            nowWallMs = sessionStart + 20_000,
        )

        assertEquals(CandidateVerdict.READY, verdict)
    }

    @Test
    fun `an unfinished event never becomes ready`() {
        val verdict = CorrelationRules.judge(
            event = event(WatchKind.CREATE),
            snapshot = snapshot(),
            sessionStartWallMs = sessionStart,
            decodeOk = true,
            durationMs = 276_386,
            expectedDurationMs = null,
            nowWallMs = sessionStart + 20_000,
        )

        assertEquals(CandidateVerdict.IGNORE_UNFINISHED, verdict)
    }

    @Test
    fun `temp files are ignored even when closed`() {
        val verdict = CorrelationRules.judge(
            event = event(WatchKind.CLOSE_WRITE, "song.mp3.part"),
            snapshot = snapshot(name = "song.mp3.part"),
            sessionStartWallMs = sessionStart,
            decodeOk = true,
            durationMs = 276_386,
            expectedDurationMs = null,
            nowWallMs = sessionStart + 20_000,
        )

        assertEquals(CandidateVerdict.IGNORE_TEMP, verdict)
    }

    @Test
    fun `a file older than the session belongs to someone else`() {
        val verdict = CorrelationRules.judge(
            event = event(WatchKind.MOVED_TO),
            snapshot = snapshot(modified = sessionStart - 60_000),
            sessionStartWallMs = sessionStart,
            decodeOk = true,
            durationMs = 276_386,
            expectedDurationMs = null,
            nowWallMs = sessionStart + 20_000,
        )

        assertEquals(CandidateVerdict.IGNORE_BEFORE_SESSION, verdict)
    }

    @Test
    fun `a file still being written waits`() {
        val now = sessionStart + 20_000
        val verdict = CorrelationRules.judge(
            event = event(WatchKind.MOVED_TO),
            snapshot = snapshot(modified = now - 200),
            sessionStartWallMs = sessionStart,
            decodeOk = true,
            durationMs = 276_386,
            expectedDurationMs = null,
            nowWallMs = now,
        )

        assertEquals(CandidateVerdict.WAIT_SIZE_STABLE, verdict)
    }

    @Test
    fun `a file that will not decode is not audio`() {
        val verdict = CorrelationRules.judge(
            event = event(WatchKind.CLOSE_WRITE),
            snapshot = snapshot(),
            sessionStartWallMs = sessionStart,
            decodeOk = false,
            durationMs = null,
            expectedDurationMs = null,
            nowWallMs = sessionStart + 20_000,
        )

        assertEquals(CandidateVerdict.IGNORE_UNDECODABLE, verdict)
    }

    @Test
    fun `a different recording of the same title is rejected`() {
        val verdict = CorrelationRules.judge(
            event = event(WatchKind.CLOSE_WRITE),
            snapshot = snapshot(),
            sessionStartWallMs = sessionStart,
            decodeOk = true,
            durationMs = 210_000,
            expectedDurationMs = 276_000,
            nowWallMs = sessionStart + 20_000,
        )

        assertEquals(CandidateVerdict.IGNORE_DURATION, verdict)
    }

    @Test
    fun `the temporary-name rule covers what downloaders write`() {
        assertTrue(CorrelationRules.isTemporary("song.mp3.part"))
        assertTrue(CorrelationRules.isTemporary("song.tmp"))
        assertTrue(CorrelationRules.isTemporary("song.mp3.opdownload"))
        assertTrue(CorrelationRules.isTemporary(".hidden"))
        assertFalse(CorrelationRules.isTemporary("song.mp3"))
        assertFalse(CorrelationRules.isTemporary("song.flac"))
    }
}
