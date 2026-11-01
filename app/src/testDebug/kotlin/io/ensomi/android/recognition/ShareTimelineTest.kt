package io.ensomi.android.recognition

import org.junit.Assert.*
import org.junit.Test

class ShareTimelineTest {
    private val time = SessionNow(60_000_000_000, 100_000)
    private val share = SharedSong(songId = 42, title = "Evening Paper", artist = "Cloud Quartet")
    private fun machine(): SessionMachine = SessionMachine.start("timeline", true, time) { _, _ -> }.first.also {
        it.on(SessionEvent.ShareReceived("invented song", share, null), time)
    }
    private fun file(artist: String? = null, modified: Long = 50_000) =
        FileFacts("/music/evening.mp3", "Evening Paper.mp3", 10000, modified, "Evening Paper", artist, uri = "content://media/42")

    @Test fun oldTitleOnlyIsOfferedUntilThePersonChooses() {
        val machine = machine()
        val old = file()
        val effects = machine.on(SessionEvent.IndexReady(listOf(old), emptyMap()), time)
        assertFalse(effects.any { it is SessionEffect.Probe })
        assertEquals(listOf(old), machine.state.held)
        machine.on(SessionEvent.Tick, time.copy(nanos = time.nanos + 60_000_000_000))
        assertTrue(machine.state.running)
        assertEquals(listOf(SessionEffect.Probe(old)), machine.on(SessionEvent.UseFile(old.path), time))
    }

    @Test fun recentGuideDefinesTheDownloadTimeline() {
        val guide = time.copy(nanos = time.nanos - 30_000_000_000, wallMs = time.wallMs - 30_000)
        assertEquals(guide, sessionBeginning(guide, time))
        assertEquals(time, sessionBeginning(guide.copy(nanos = time.nanos - 1_800_000_000_001), time))
        assertEquals(time, sessionBeginning(null, time))
    }

    @Test fun aHeaderlessDecodeStillChecksTheIndexedDuration() {
        val old = file().copy(durationMs = 180_000)
        val probe = ProbeFacts(true, 60_000.0, null, sizeBytes = old.sizeBytes, modifiedWallMs = old.modifiedWallMs)
        assertEquals(Verdict.TRUNCATED, CandidateRules.judgeDecoded(old, probe))
        // With neither full-length metadata nor a header, a frame-aligned truncation is unknowable.
        assertEquals(Verdict.READY, CandidateRules.judgeDecoded(old.copy(durationMs = null), probe))
    }
    @Test fun allRowsAreRankedBeforeProbingAndRetryCanChooseAnotherFile() {
        val machine = machine()
        val older = file("Cloud Quartet").copy(path = "/music/older.mp3")
        val newer = file("Cloud Quartet", 80_000)
        val weak = file().copy(path = "/music/weak.mp3")
        val rows = listOf(weak, older, newer)
        assertEquals(listOf(SessionEffect.Probe(newer)), machine.on(SessionEvent.IndexReady(rows, mapOf(42L to older.path)), time))
        machine.on(SessionEvent.Probed(newer, ProbeFacts(true, 10000.0, sizeBytes = newer.sizeBytes, modifiedWallMs = newer.modifiedWallMs)), time)
        val retry = machine.on(SessionEvent.Retry(Stage.AUDIO), time)
        assertTrue(SessionEffect.ResetSync in retry)
        assertTrue(SessionEffect.Forget(42) in retry)
        assertEquals(listOf(SessionEffect.Probe(older)), machine.on(SessionEvent.IndexReady(rows, emptyMap()), time))
    }

}
