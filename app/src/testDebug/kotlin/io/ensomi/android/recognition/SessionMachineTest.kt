package io.ensomi.android.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plays a session on a clock of its own, keeping what it asked for and what it traced. */
private class Bench(
    modelInstalled: Boolean = true,
    private val limits: SessionLimits = SessionLimits(),
    shared: SharedSong = SharedSong(songId = 186016, title = "晴天", artist = "周杰伦"),
) {
    var nanos = 10_000_000_000L
    var wallMs = 1_700_000_000_000L
    val traced = ArrayList<Pair<String, Map<String, Any?>>>()
    var machine: SessionMachine
    var effects: List<SessionEffect>

    init {
        val (started, first) = SessionMachine.start("cid", modelInstalled, now(), limits, trace = ::traceIt)
        machine = started
        effects = first + machine.on(SessionEvent.ShareReceived(shared.title ?: "song link", shared, "test"), now())
    }

    private fun traceIt(event: String, fields: Map<String, Any?>) {
        traced += event to fields
    }

    fun now() = SessionNow(nanos, wallMs)

    val state get() = machine.state

    fun on(event: SessionEvent): List<SessionEffect> {
        effects = machine.on(event, now())
        return effects
    }

    fun advance(ms: Long) {
        nanos += ms * 1_000_000
        wallMs += ms
    }

    /** One second at a time, each with its tick, as the controller does. */
    fun run(ms: Long): List<SessionEffect> {
        val all = ArrayList<SessionEffect>()
        var left = ms
        while (left > 0) {
            val step = minOf(left, 1_000L)
            advance(step)
            left -= step
            all += on(SessionEvent.Tick)
        }
        return all
    }

    fun status(stage: Stage) = state.stage(stage).status

    fun events(name: String) = traced.filter { it.first == name }.map { it.second }

    fun restoredFrom(state: SessionState): List<SessionEffect> {
        val (restored, effects) = SessionMachine.restored(state, now(), limits, trace = ::traceIt)
        machine = restored
        this.effects = effects
        return effects
    }
}

class SessionMachineTest {
    private val share = "分享周杰伦的单曲《晴天》: https://music.163.com/song?id=186016 (来自@网易云音乐)"
    private val shared = SessionEvent.ShareReceived(share, ShareParser.parse(share), "com.netease.cloudmusic")

    private fun Bench.song(
        name: String = "周杰伦 - 晴天.mp3",
        size: Long = 8_000_000,
        modified: Long = wallMs,
    ) = FileFacts("/music/$name", name, size, modified)

    private fun Bench.probed(file: FileFacts, decodedMs: Double = 269_000.0) =
        SessionEvent.Probed(file, ProbeFacts(true, decodedMs, decodedMs.toLong(), 44_100, sha256 = "abc", sizeBytes = file.sizeBytes, modifiedWallMs = file.modifiedWallMs))

    private fun Bench.listening() {
        on(SessionEvent.IndexReady(emptyList(), emptyMap()))
    }

    /** From a share and a finished file to the audio being ready. */
    private fun Bench.audioReady(file: FileFacts = song(modified = wallMs - 5_000)) {
        on(shared)
        val effects = on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE))
        assertTrue(effects.toString(), SessionEffect.Probe(file) in effects)
        on(probed(file))
    }

    private fun Bench.chartReady() {
        on(SessionEvent.ChartReady(ChartFacts("song", 900, stored = false), 60.0))
    }

    private fun lock(referenceMs: Double = 61_000.0, captureNanos: Long = 5_000) = SessionEvent.SyncUpdate(
        SyncReading(SyncState.LOCKED, finalLock = true, referenceMs = referenceMs, captureNanos = captureNanos),
    )

    @Test
    fun listeningComesBeforeAnythingElse() {
        val bench = Bench()
        assertEquals(listOf(SessionEffect.StartListening, SessionEffect.BuildIndex), bench.effects)
        assertEquals(StageStatus.RUNNING, bench.status(Stage.LISTEN))
        assertEquals("session.start", bench.traced.first().first)
    }

    @Test
    fun aWholeSessionInOrderByHand() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.on(shared)
        assertEquals(SongIdentity(186016, "晴天", "周杰伦"), bench.state.song)
        assertEquals(StageStatus.DONE, bench.status(Stage.SHARE))
        assertEquals(StageStatus.RUNNING, bench.status(Stage.AUDIO))
        assertEquals(listOf(Ask.DOWNLOAD_HELP), bench.state.asks)

        val file = bench.song()
        assertTrue(bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE)).isEmpty())
        assertEquals("NOT_QUIET", bench.events("audio.candidate").last()["verdict"])
        assertTrue(SessionEffect.Refresh(file.path) in bench.run(1_100))
        assertEquals(listOf<SessionEffect>(SessionEffect.Probe(file)), bench.on(SessionEvent.FileSeen(file, Evidence.POLL)))
        val ready = bench.on(bench.probed(file))
        assertEquals(SessionEffect.Remember(186016, file.path), ready[0])
        assertTrue(ready[1] is SessionEffect.MakeChart)
        assertEquals(StageStatus.DONE, bench.status(Stage.AUDIO))
        assertEquals(StageStatus.RUNNING, bench.status(Stage.CHART))
        assertTrue(bench.state.asks.isEmpty())

        bench.on(SessionEvent.ChartPhase("TIMING"))
        assertEquals("TIMING", bench.state.stage(Stage.CHART).detail)
        val sync = bench.on(SessionEvent.ChartReady(ChartFacts("song", 900, stored = false), 60.0))
        assertTrue(sync.isEmpty())
        assertTrue(ready.any { it is SessionEffect.BuildReference })
        assertEquals(listOf<SessionEffect>(SessionEffect.StartCapture), bench.on(SessionEvent.ReferenceReady("runtime", 1.0)))
        bench.on(SessionEvent.SyncUpdate(SyncReading(SyncState.LISTENING)))
        bench.on(SessionEvent.SyncUpdate(SyncReading(SyncState.LOCKING)))
        val join = bench.on(lock())
        assertEquals(listOf(SessionEffect.StopCapture, SessionEffect.Join(LockAnchor(61_000.0, 5_000))), join)
        assertEquals(StageStatus.DONE, bench.status(Stage.SYNC))
        assertEquals(StageStatus.RUNNING, bench.status(Stage.PLAY))

        bench.on(SessionEvent.Joined(JoinFacts(70_000.0, 66_000.0, 500, 400)))
        assertEquals(66_000.0, bench.events("game.join").single()["heard_ms"])
        val end = bench.on(SessionEvent.GameEnded(GameSummary(0.9, 400, 90, 10, 200, 3.0)))
        assertTrue(SessionEffect.StopListening in end)
        assertEquals("finished", bench.state.ended)
        assertEquals(StageStatus.DONE, bench.status(Stage.PLAY))
    }

    @Test
    fun theFileBeforeTheShare() {
        val bench = Bench()
        val file = bench.song(modified = bench.wallMs - 60_000)
        bench.on(shared) // Production starts at the share, after this download finished.
        val effects = bench.on(SessionEvent.IndexReady(listOf(file), emptyMap()))
        assertTrue(effects.toString(), SessionEffect.Probe(file) in effects)
        val ready = bench.on(bench.probed(file))
        assertTrue(ready.any { it is SessionEffect.MakeChart })
        assertEquals(StageStatus.DONE, bench.status(Stage.SHARE))
        assertEquals(StageStatus.DONE, bench.status(Stage.AUDIO))
        assertTrue(bench.state.held.isEmpty())
    }

    @Test
    fun aFileThatArrivesInPiecesWithAChangeOfName() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        bench.advance(2_000)
        val partial = bench.song("周杰伦 - 晴天.mp3.tmp", size = 1_000_000)
        assertTrue(bench.on(SessionEvent.FileSeen(partial, Evidence.WRITING)).isEmpty())
        assertEquals("TEMPORARY", bench.events("audio.candidate").last()["verdict"])
        bench.advance(2_000)
        val whole = partial.copy(sizeBytes = 8_000_000, modifiedWallMs = bench.wallMs)
        assertTrue(bench.on(SessionEvent.FileSeen(whole, Evidence.CLOSE_WRITE)).isEmpty())
        bench.on(SessionEvent.FileGone(whole.path))
        bench.advance(100)
        val named = bench.song(size = 8_000_000)
        assertTrue(bench.on(SessionEvent.FileSeen(named, Evidence.MOVED_TO)).isEmpty())
        assertEquals("NOT_QUIET", bench.events("audio.candidate").last()["verdict"])
        assertTrue(SessionEffect.Refresh(named.path) in bench.run(1_100))
        assertEquals(SessionEffect.Probe(named), bench.on(SessionEvent.FileSeen(named, Evidence.POLL)).single())
        bench.on(bench.probed(named))
        assertEquals(named.path, bench.state.audio?.file?.path)
    }

    @Test
    fun aFileThatGrowsUnderTheFinalNameWaitsForItToStop() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        val partial = bench.song(size = 1_000_000)
        bench.on(SessionEvent.FileSeen(partial, Evidence.WRITING))
        assertEquals("UNFINISHED", bench.events("audio.candidate").last()["verdict"])
        bench.advance(3_000)
        val more = partial.copy(sizeBytes = 2_000_000, modifiedWallMs = bench.wallMs)
        bench.on(SessionEvent.FileSeen(more, Evidence.WRITING))
        assertEquals(0, bench.effects.count { it is SessionEffect.Probe })
        bench.advance(1_500)
        val done = more.copy(sizeBytes = 8_000_000, modifiedWallMs = bench.wallMs - 1_200)
        assertEquals(0, bench.on(SessionEvent.FileSeen(done, Evidence.CLOSE_WRITE)).count { it is SessionEffect.Probe })
    }

    @Test
    fun anExistingFileMatchedByIdentityNeedsNoDownload() {
        val bench = Bench()
        val existing = FileFacts("/music/周杰伦 - 晴天.mp3", "周杰伦 - 晴天.mp3", 8_000_000, bench.wallMs - 86_400_000)
        val effects = bench.on(SessionEvent.IndexReady(listOf(existing), emptyMap()))
        assertTrue(effects.toString(), SessionEffect.Probe(existing) in effects)
        assertEquals("PROBE", bench.events("audio.candidate").last()["verdict"])
        assertEquals("INDEX", bench.events("audio.candidate").last()["evidence"])
    }

    @Test
    fun aFileRememberedForTheSongIdIsFoundWithoutItsName() {
        val bench = Bench(shared = SharedSong(songId = 186016))
        val existing = FileFacts("/music/track.mp3", "track.mp3", 8_000_000, bench.wallMs - 86_400_000)
        assertTrue(SessionEffect.Probe(existing) in bench.on(SessionEvent.IndexReady(listOf(existing), mapOf(186016L to existing.path))))
        assertEquals("SONG_ID", bench.events("audio.candidate").last()["match"])
    }

    @Test
    fun theIndexLeavesTheTraceWithOnlyWhatCouldBeTheSong() {
        val bench = Bench()
        val others = List(50) { FileFacts("/music/$it.mp3", "$it.mp3", 1_000, bench.wallMs - 86_400_000) }
        bench.on(SessionEvent.IndexReady(others, emptyMap()))
        bench.on(shared)
        assertTrue(bench.events("audio.candidate").isEmpty())
    }

    @Test
    fun anOldFileThatIsNotTheSongLeavesNoLineHoweverItIsSeen() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        val other = FileFacts("/music/other.mp3", "other.mp3", 1_000, bench.wallMs - 86_400_000)
        bench.on(SessionEvent.FileSeen(other, Evidence.POLL))
        assertTrue(bench.events("audio.candidate").isEmpty())
    }

    @Test
    fun aNewerIndexOverrulesWhatACandidateRemembers() {
        val bench = Bench()
        val row = bench.song(modified = bench.wallMs - 5_000).copy(pending = true)
        bench.on(SessionEvent.IndexReady(listOf(row), emptyMap()))
        bench.on(shared)
        assertEquals("UNFINISHED", bench.events("audio.candidate").last()["verdict"])
        val done = row.copy(pending = false)
        val effects = bench.on(SessionEvent.IndexReady(listOf(done), emptyMap()))
        assertTrue(effects.toString(), SessionEffect.Probe(done) in effects)
    }

    @Test
    fun aShortLinkIsLookedUpAndItsFailureStopsNothing() {
        val text = "分享周杰伦的单曲《晴天》: https://163cn.tv/zoIxm3 (来自@网易云音乐)"
        val bench = Bench(shared = ShareParser.parse(text)!!)
        val effects = bench.effects
        bench.listening()
        assertTrue(SessionEffect.LookUp("https://163cn.tv/zoIxm3") in effects)
        bench.on(SessionEvent.LinkLookedUp("https://163cn.tv/zoIxm3", null, "failed: timeout"))
        assertEquals("failed: timeout", bench.events("share.link").single()["outcome"])
        assertNull(bench.state.failure)
        assertNull(bench.state.song?.songId)
        val file = bench.song(modified = bench.wallMs - 5_000)
        assertTrue(SessionEffect.Probe(file) in bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE)))
    }

    @Test
    fun aLookedUpIdFindsARememberedFile() {
        val text = "https://163cn.tv/zoIxm3"
        val bench = Bench(shared = ShareParser.parse(text)!!)
        val existing = FileFacts("/music/track.mp3", "track.mp3", 8_000_000, bench.wallMs - 86_400_000)
        bench.on(SessionEvent.IndexReady(listOf(existing), mapOf(5L to existing.path)))
        bench.on(SessionEvent.ShareReceived(text, ShareParser.parse(text), null))
        assertTrue(SessionEffect.Probe(existing) in bench.on(SessionEvent.LinkLookedUp(text, 5, "resolved")))
    }

    @Test
    fun aNcmIsRefusedAndNeverDecoded() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        val ncm = bench.song("周杰伦 - 晴天.ncm", modified = bench.wallMs - 5_000)
        val effects = bench.on(SessionEvent.FileSeen(ncm, Evidence.CLOSE_WRITE))
        assertTrue(effects.none { it is SessionEffect.Probe })
        assertEquals(FailureKind.PROTECTED_TRACK, bench.state.failure?.kind)
        assertEquals(Stage.AUDIO, bench.state.failure?.stage)
        assertEquals(StageStatus.FAILED, bench.status(Stage.AUDIO))
        assertEquals("PROTECTED", bench.events("audio.candidate").last()["verdict"])
        assertTrue(bench.state.held.isEmpty())
        // The person's fingers do not make it a route.
        bench.on(SessionEvent.UseFile(ncm.path))
        assertTrue(bench.effects.none { it is SessionEffect.Probe })
    }

    @Test
    fun aPurchasedMp3AfterANcmGoesOn() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        bench.on(SessionEvent.FileSeen(bench.song("周杰伦 - 晴天.ncm", modified = bench.wallMs - 5_000), Evidence.CLOSE_WRITE))
        val mp3 = bench.song(modified = bench.wallMs - 5_000)
        assertTrue(SessionEffect.Probe(mp3) in bench.on(SessionEvent.FileSeen(mp3, Evidence.CLOSE_WRITE)))
        bench.on(bench.probed(mp3))
        assertNull(bench.state.failure)
        assertEquals(StageStatus.DONE, bench.status(Stage.AUDIO))
    }

    @Test
    fun theSharedIdentityMatchesANcmSeenAfterward() {
        val bench = Bench()
        bench.listening()
        bench.on(SessionEvent.FileSeen(bench.song("周杰伦 - 晴天.ncm", modified = bench.wallMs + 1_000), Evidence.CLOSE_WRITE))
        assertEquals(FailureKind.PROTECTED_TRACK, bench.state.failure?.kind)
    }

    @Test
    fun aFileThatDoesNotDecodeIsPassedOverForTheNext() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        val bad = bench.song("晴天 - 周杰伦.mp3", modified = bench.wallMs - 5_000)
        val good = bench.song(modified = bench.wallMs - 5_000)
        bench.on(SessionEvent.FileSeen(bad, Evidence.CLOSE_WRITE))
        assertTrue(bench.on(SessionEvent.FileSeen(good, Evidence.CLOSE_WRITE)).isEmpty())
        val next = bench.on(SessionEvent.Probed(bad, ProbeFacts(false, failure = "no decoder", sizeBytes = bad.sizeBytes, modifiedWallMs = bad.modifiedWallMs)))
        assertEquals(listOf<SessionEffect>(SessionEffect.Probe(good)), next)
        assertEquals("UNDECODABLE", bench.events("audio.candidate")[bench.events("audio.candidate").size - 1]["verdict"])
    }

    @Test
    fun aTruncatedFileWaitsForMoreAndIsOfferedToThePerson() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        val file = bench.song(modified = bench.wallMs - 5_000)
        bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE))
        val short = ProbeFacts(true, 100_000.0, 269_000, 44_100, sizeBytes = file.sizeBytes, modifiedWallMs = file.modifiedWallMs)
        bench.on(SessionEvent.Probed(file, short))
        assertEquals("TRUNCATED", bench.events("audio.candidate").last()["verdict"])
        assertNull(bench.state.audio)
        assertEquals(listOf(file), bench.state.held)
        val grown = file.copy(sizeBytes = 9_000_000, modifiedWallMs = bench.wallMs - 2_000)
        bench.advance(2_000)
        assertTrue(bench.on(SessionEvent.FileSeen(grown, Evidence.CLOSE_WRITE)).isEmpty())
        assertEquals("NOT_QUIET", bench.events("audio.candidate").last()["verdict"])
    }

    @Test
    fun thePersonCanTakeAHeldFile() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        val other = bench.song("mystery.mp3", modified = bench.wallMs + 1_000)
        bench.advance(3_000)
        bench.on(SessionEvent.FileSeen(other, Evidence.CLOSE_WRITE))
        assertEquals("NO_MATCH", bench.events("audio.candidate").last()["verdict"])
        assertEquals(listOf(other), bench.state.held)
        assertTrue(SessionEffect.Probe(other) in bench.on(SessionEvent.UseFile(other.path)))
        assertEquals("USE_FILE", bench.events("manual_intervention").single()["step"])
        bench.on(bench.probed(other))
        assertEquals(other.path, bench.state.audio?.file?.path)
    }

    @Test
    fun noFileByFifteenSecondsEndsWithAnExplanationAndCleanup() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        assertEquals(listOf(Ask.DOWNLOAD_HELP), bench.state.asks) // No tick or delay after indexing.
        assertNull(bench.state.failure)
        bench.run(14_999)
        assertTrue(bench.state.running)
        val effects = bench.run(1)
        assertEquals(FailureKind.NO_FILE, bench.state.failure?.kind)
        assertEquals(StageStatus.FAILED, bench.status(Stage.AUDIO))
        assertEquals("audio_wait_timeout", bench.state.ended)
        assertEquals("audio_wait_timeout", bench.events("abort").single()["reason"])
        assertEquals("AUDIO", bench.events("abort").single()["stage"])
        assertTrue(SessionEffect.StopListening in effects)
        assertFalse(bench.state.capturing)
        assertTrue(bench.state.asks.isEmpty())
        assertNull(bench.state.audioWaitSinceNanos)
        val ended = bench.state
        val traced = bench.traced.toList()
        assertTrue(bench.run(60_000).isEmpty())
        assertTrue(bench.on(SessionEvent.FileSeen(bench.song(), Evidence.CLOSE_WRITE)).isEmpty())
        assertEquals(ended, bench.state)
        assertEquals(traced, bench.traced)
    }

    @Test
    fun anIndexedMp3CanJoinWithoutWaitingForATick() {
        val bench = Bench()
        bench.listening()
        bench.on(SessionEvent.Frontmost(true))
        val file = bench.song()
        bench.advance(2_000)
        val before = bench.nanos
        assertTrue(SessionEffect.Probe(file) in bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE)))
        bench.on(bench.probed(file))
        assertNull(bench.state.audioWaitSinceNanos)
        bench.chartReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
        assertTrue(bench.on(lock()).any { it is SessionEffect.Join })
        assertEquals(before, bench.nanos)
        bench.run(20_000)
        assertNull(bench.state.failure)
    }

    @Test
    fun anMp3ArrivingAtTwelveSecondsIsUsedEvenIfItNeedsQuietAndDecodingTime() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        bench.run(12_000)
        assertEquals(listOf(Ask.DOWNLOAD_HELP), bench.state.asks)
        val file = bench.song()
        bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE))
        assertEquals(listOf(Ask.WAIT_FOR_DOWNLOAD), bench.state.asks)
        assertTrue(SessionEffect.Refresh(file.path) in bench.run(1_000))
        assertTrue(bench.state.running)
        assertTrue(SessionEffect.Probe(file) in bench.on(SessionEvent.FileSeen(file, Evidence.POLL)))
        bench.run(20_000) // Arrival timeout must not interrupt a decoder already reading the file.
        assertTrue(bench.state.running)
        assertFalse(Ask.DOWNLOAD_HELP in bench.state.asks)
        bench.on(bench.probed(file))
        assertEquals(file, bench.state.audio?.file)
        assertNull(bench.state.failure)
        assertNull(bench.state.audioWaitSinceNanos)
    }

    @Test
    fun aProtectedFilenameMakesTheTimeoutExplanationFirm() {
        val bench = Bench()
        bench.listening()
        val ncm = bench.song("周杰伦 - 晴天.ncm")
        bench.on(SessionEvent.FileSeen(ncm, Evidence.WRITING))
        bench.on(shared)
        bench.run(9_000)
        // Even a protected file growing cannot prolong the wait for usable audio.
        assertTrue(bench.on(SessionEvent.FileSeen(ncm.copy(sizeBytes = ncm.sizeBytes + 100), Evidence.WRITING))
            .none { it is SessionEffect.Probe })
        bench.on(SessionEvent.FileGone(ncm.path))
        bench.run(6_000)
        assertFalse(bench.state.running)
        assertEquals(FailureKind.PROTECTED_TRACK, bench.state.failure?.kind)
        assertEquals(ncm.name, bench.state.failure?.detail)
        assertEquals("audio_wait_timeout", bench.state.ended)
    }

    @Test
    fun unrelatedProtectedNamesAndTheirTagsDoNotMakeTheMessageFirm() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        val unrelated = bench.song("Other - Song.ncm").copy(title = "晴天", artist = "周杰伦")
        bench.on(SessionEvent.FileSeen(unrelated, Evidence.CLOSE_WRITE))
        bench.run(15_000)
        assertEquals(FailureKind.NO_FILE, bench.state.failure?.kind)
        assertNull(bench.state.protectedFileName)
    }

    @Test
    fun aGrowingDownloadGetsFifteenSecondsFromItsLastSizeIncrease() {
        for (name in listOf("周杰伦 - 晴天.mp3", "周杰伦 - 晴天.mp3.part")) {
            val bench = Bench()
            bench.listening()
            bench.on(shared)
            var file = bench.song(name, size = 1_000)
            bench.on(SessionEvent.FileSeen(file, Evidence.WRITING))
            repeat(3) {
                bench.run(9_000)
                file = file.copy(sizeBytes = file.sizeBytes + 1_000, modifiedWallMs = bench.wallMs)
                bench.on(SessionEvent.FileSeen(file, Evidence.WRITING))
                assertTrue(bench.state.running)
                assertTrue(bench.effects.none { it is SessionEffect.Probe })
            }
            bench.run(9_000)
            // Neither a repeated sighting nor only a timestamp change is growth.
            bench.on(SessionEvent.FileSeen(file.copy(modifiedWallMs = bench.wallMs), Evidence.WRITING))
            bench.run(6_000)
            assertEquals("audio_wait_timeout", bench.state.ended)
        }
    }

    @Test
    fun matchingGrowthHidesTheMessageAndRenewsTheStopWhileRestorationKeepsTheClock() {
        val shared = SharedSong(title = "Paper Aurora", artist = "Mira Vale")
        val bench = Bench(shared = shared)
        bench.listening()
        bench.run(10_000)
        assertEquals(listOf(Ask.DOWNLOAD_HELP), bench.state.asks)
        var file = bench.song("Mira Vale - Paper Aurora.mp3.part", size = 1000)
        bench.on(SessionEvent.FileSeen(file, Evidence.WRITING))
        assertEquals(listOf(Ask.WAIT_FOR_DOWNLOAD), bench.state.asks)
        bench.run(10_000)
        assertEquals(listOf(Ask.WAIT_FOR_DOWNLOAD), bench.state.asks)
        file = file.copy(sizeBytes = 2000)
        bench.on(SessionEvent.FileSeen(file, Evidence.WRITING))
        assertEquals(listOf(Ask.WAIT_FOR_DOWNLOAD), bench.state.asks)
        bench.run(9_999)
        val saved = bench.state
        bench.restoredFrom(saved)
        bench.listening()
        assertEquals(saved.audioWaitSinceNanos, bench.state.audioWaitSinceNanos)
        bench.run(1)
        assertEquals(listOf(Ask.DOWNLOAD_HELP), bench.state.asks)
        bench.run(4_999)
        assertTrue(bench.state.running)
        bench.run(1)
        assertEquals("audio_wait_timeout", bench.state.ended)
    }

    @Test
    fun aPendingMediaRowCanProveGrowthButOnlyACompleteRowCanBeProbed() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        var file = bench.song(size = 1_000).copy(pending = true)
        bench.on(SessionEvent.FileSeen(file, Evidence.WRITING))
        bench.run(7_000)
        file = file.copy(sizeBytes = 2_000, modifiedWallMs = bench.wallMs)
        assertTrue(bench.on(SessionEvent.FileSeen(file, Evidence.WRITING)).none { it is SessionEffect.Probe })
        bench.run(9_000)
        assertTrue(bench.state.running)
        val finished = file.copy(pending = false)
        assertTrue(SessionEffect.Probe(finished) in bench.on(SessionEvent.FileSeen(finished, Evidence.MEDIA_STORE)))
        bench.on(bench.probed(finished))
        assertEquals(finished, bench.state.audio?.file)
    }

    @Test
    fun theAudioWaitOptionHasADefaultAndCanBeChangedWithoutCode() {
        val json = kotlinx.serialization.json.Json
        assertEquals(15_000L, json.decodeFromString(SessionOptions.serializer(), "{}").audioWaitMs)
        val options = json.decodeFromString(SessionOptions.serializer(), "{\"audioWaitMs\":2000}")
        val bench = Bench(limits = SessionLimits(audioWaitMs = options.audioWaitMs))
        bench.listening()
        bench.on(shared)
        bench.run(2_000)
        assertEquals("audio_wait_timeout", bench.state.ended)
    }

    @Test
    fun chartStoredAlreadySkipsGeneration() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.on(SessionEvent.ChartReady(ChartFacts("song", 900, stored = true), 0.0))
        assertEquals("stored", bench.state.stage(Stage.CHART).detail)
        assertEquals(true, bench.events("chart.ready").single()["stored"])
    }

    @Test
    fun aChartThatFailsCanBeTriedAgain() {
        val bench = Bench()
        bench.listening()
        bench.audioReady()
        bench.on(SessionEvent.ChartFailed("star_out_of_range"))
        assertEquals(FailureKind.CHART_FAILED, bench.state.failure?.kind)
        val again = bench.on(SessionEvent.Retry(Stage.CHART))
        assertTrue(again.any { it is SessionEffect.MakeChart })
        assertNull(bench.state.failure)
        assertEquals(StageStatus.RUNNING, bench.status(Stage.CHART))
    }

    @Test
    fun noModelStopsBeforeAnyFileOrCaptureWorkStarts() {
        val bench = Bench(modelInstalled = false)
        assertEquals(FailureKind.MODEL_MISSING, bench.state.failure?.kind)
        assertFalse(bench.state.running)
        assertFalse(SessionEffect.StartListening in bench.effects)
        assertFalse(SessionEffect.BuildIndex in bench.effects)
        assertFalse(SessionEffect.StartCapture in bench.effects)
    }

    @Test
    fun captureAndFinalLockCanFinishWhileTheChartIsStillRunning() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        assertTrue(bench.effects.any { it is SessionEffect.MakeChart })
        assertTrue(bench.effects.any { it is SessionEffect.BuildReference })
        assertEquals(listOf<SessionEffect>(SessionEffect.StartCapture), bench.on(SessionEvent.ReferenceReady("runtime", 0.2)))
        val capturedAt = bench.nanos
        assertEquals(listOf<SessionEffect>(SessionEffect.StopCapture), bench.on(lock(12_000.0, capturedAt)))
        assertEquals(StageStatus.RUNNING, bench.status(Stage.CHART))
        assertEquals(StageStatus.DONE, bench.status(Stage.SYNC))
        assertEquals(StageStatus.WAITING, bench.status(Stage.PLAY))
        bench.advance(40_000)
        bench.chartReady()
        val anchor = LockAnchor(12_000.0, capturedAt)
        assertEquals(listOf<SessionEffect>(SessionEffect.Join(anchor)), bench.effects)
        assertEquals(52_000.0, AmbientPlayClock(anchor).heardMs(bench.nanos), 1e-9)
        // A duplicate completion must not open another game or restart capture.
        bench.chartReady()
        assertTrue(bench.effects.isEmpty())
    }

    @Test
    fun chartCompletionDoesNotTurnAProvisionalPositionIntoALock() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 0.2))
        bench.on(SessionEvent.SyncUpdate(SyncReading(SyncState.LOCKING, referenceMs = 12_000.0, captureNanos = bench.nanos)))
        bench.chartReady()
        assertTrue(bench.effects.isEmpty())
        assertTrue(bench.state.capturing)
        assertNull(bench.state.lock)
        assertTrue(bench.on(lock()).any { it is SessionEffect.Join })
    }

    @Test
    fun anEarlyLockSurvivesRestoreAndWaitsForTheChartAndForeground() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 0.2))
        bench.on(lock())
        val effects = bench.restoredFrom(bench.state)
        assertTrue(effects.any { it is SessionEffect.MakeChart })
        assertTrue(effects.none { it is SessionEffect.BuildReference })
        bench.chartReady()
        assertTrue(bench.effects.isEmpty())
        assertEquals(listOf<SessionEffect>(SessionEffect.Join(LockAnchor(61_000.0, 5_000))), bench.on(SessionEvent.Frontmost(true)))
    }

    @Test
    fun chartRetryRestartsBothWorkersAndDiscardsTheOldAnchor() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 0.2))
        bench.on(lock())
        bench.on(SessionEvent.ChartFailed("generation failed"))
        val effects = bench.on(SessionEvent.Retry(Stage.CHART))
        assertTrue(effects.any { it is SessionEffect.MakeChart })
        assertTrue(effects.any { it is SessionEffect.BuildReference })
        assertNull(bench.state.lock)
        bench.chartReady()
        assertTrue(bench.effects.isEmpty())
    }

    @Test
    fun captureWaitsForEnsomiToBeInFront() {
        val bench = Bench()
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        assertTrue(bench.on(SessionEvent.ReferenceReady("runtime", 1.0)).isEmpty())
        assertFalse(bench.state.capturing)
        assertEquals(listOf(Ask.RETURN_TO_ENSOMI), bench.state.asks)
        assertTrue(SessionEffect.StartCapture in bench.on(SessionEvent.Frontmost(true)))
        assertTrue(bench.state.capturing)
        assertTrue(bench.state.asks.isEmpty())
        assertEquals(listOf<SessionEffect>(SessionEffect.StopCapture), bench.on(SessionEvent.Frontmost(false)))
        assertFalse(bench.state.capturing)
    }

    @Test
    fun silenceIsNoticed() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
        repeat(15) { bench.on(SessionEvent.CaptureLevel(-80.0, -70.0)) }
        assertTrue(Ask.MICROPHONE_SILENT in bench.state.asks)
        bench.on(SessionEvent.CaptureLevel(-30.0, -10.0))
        assertFalse(Ask.MICROPHONE_SILENT in bench.state.asks)
        assertEquals(-80.0, bench.events("capture.level").first()["rms_db"])
    }

    @Test
    fun syncThatNeverLocksSaysSoAndNeverJoins() {
        val bench = Bench(limits = SessionLimits(syncMs = 30_000))
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
        bench.on(SessionEvent.SyncUpdate(SyncReading(SyncState.LOCKING, referenceMs = 5_000.0, captureNanos = 1)))
        val effects = bench.run(31_000)
        assertTrue(SessionEffect.StopCapture in effects)
        assertTrue(effects.none { it is SessionEffect.Join })
        assertEquals(FailureKind.SYNC_NEVER_LOCKED, bench.state.failure?.kind)
        assertEquals(StageStatus.FAILED, bench.status(Stage.SYNC))
        assertNull(bench.state.lock)
        // A lock that comes after the session gave up is not taken.
        assertTrue(bench.on(lock()).isEmpty())
        val again = bench.on(SessionEvent.Retry(Stage.SYNC))
        assertTrue(again.any { it is SessionEffect.BuildReference })
        assertEquals(0L, bench.state.listenedMs)
    }

    @Test
    fun onlyTheTimeListeningCounts() {
        val bench = Bench(limits = SessionLimits(syncMs = 30_000))
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
        bench.run(20_000)
        bench.on(SessionEvent.Frontmost(false))
        bench.run(60_000)
        assertNull(bench.state.failure)
        bench.on(SessionEvent.Frontmost(true))
        bench.run(11_000)
        assertEquals(FailureKind.SYNC_NEVER_LOCKED, bench.state.failure?.kind)
    }

    @Test
    fun aProvisionalLockIsNotAFinalOne() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
        val provisional = bench.on(
            SessionEvent.SyncUpdate(SyncReading(SyncState.CONFIRMED, finalLock = false, referenceMs = 5_000.0, captureNanos = 1)),
        )
        assertTrue(provisional.isEmpty())
        assertEquals(StageStatus.RUNNING, bench.status(Stage.SYNC))
    }

    @Test
    fun anAlignerThatFailsStopsCapture() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
        val effects = bench.on(SessionEvent.SyncUpdate(SyncReading(SyncState.FAILED, detail = "bad input")))
        assertEquals(listOf<SessionEffect>(SessionEffect.StopCapture), effects)
        assertEquals(FailureKind.SYNC_FAILED, bench.state.failure?.kind)
    }

    @Test
    fun aReferenceThatCannotBeBuiltIsSaid() {
        val bench = Bench()
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        bench.on(SessionEvent.ReferenceFailed("the song is too short to align to"))
        assertEquals(FailureKind.SYNC_FAILED, bench.state.failure?.kind)
        assertEquals("the song is too short to align to", bench.state.failure?.detail)
    }

    @Test
    fun theTraceSaysWhyNoPositionIsHeld() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
        bench.on(SessionEvent.SyncUpdate(SyncReading(SyncState.LISTENING, detail = "INSUFFICIENT_DURATION")))
        bench.on(SessionEvent.SyncUpdate(SyncReading(SyncState.LISTENING, detail = "INSUFFICIENT_DURATION")))
        bench.on(SessionEvent.SyncUpdate(SyncReading(SyncState.LISTENING, confidence = 0.4, detail = "WEAK_ALIGNMENT_PEAK")))
        assertEquals(listOf("INSUFFICIENT_DURATION", "WEAK_ALIGNMENT_PEAK"), bench.events("sync.state").map { it["detail"] })
        // The screen shows it as it stands.
        assertEquals("WEAK_ALIGNMENT_PEAK", bench.state.syncDetail)
        assertEquals(0.4, bench.state.syncConfidence!!, 0.0)
        assertEquals("LISTENING · WEAK_ALIGNMENT_PEAK", bench.state.stage(Stage.SYNC).detail)
    }

    @Test
    fun theRoomsSongEndingBeforeTheJoinIsSaid() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
        bench.on(lock())
        bench.on(SessionEvent.JoinRefused("heard 268000 ms of 269000 ms"))
        assertEquals(FailureKind.SONG_ENDED, bench.state.failure?.kind)
        assertEquals(StageStatus.FAILED, bench.status(Stage.PLAY))
        assertEquals(Stage.SYNC, bench.state.failure?.kind?.retryFrom)
    }

    @Test
    fun aGameLeftCanJoinAgainFromTheSameLock() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
        bench.on(lock())
        bench.on(SessionEvent.Joined(JoinFacts(70_000.0, 66_000.0, 500, 400)))
        bench.on(SessionEvent.GameQuit)
        assertEquals(FailureKind.GAME_QUIT, bench.state.failure?.kind)
        assertNull(bench.state.joined)
        val again = bench.on(SessionEvent.Retry(Stage.PLAY))
        assertEquals(listOf<SessionEffect>(SessionEffect.Join(LockAnchor(61_000.0, 5_000))), again)
    }

    @Test
    fun retryingAudioLooksAgainWithTheSongKept() {
        val bench = Bench()
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        val effects = bench.on(SessionEvent.Retry(Stage.AUDIO))
        assertTrue(SessionEffect.BuildIndex in effects)
        assertNull(bench.state.audio)
        assertNull(bench.state.chart)
        assertEquals("晴天", bench.state.song?.title)
        assertEquals(StageStatus.RUNNING, bench.status(Stage.AUDIO))
        assertEquals(StageStatus.WAITING, bench.status(Stage.CHART))
        assertEquals(StageStatus.WAITING, bench.status(Stage.SYNC))
    }

    @Test
    fun stoppingEndsEverythingAndSaysWhy() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
        val effects = bench.on(SessionEvent.Stop("person"))
        assertTrue(SessionEffect.StopCapture in effects)
        assertTrue(SessionEffect.StopListening in effects)
        assertEquals("person", bench.state.ended)
        assertEquals("person", bench.events("abort").single()["reason"])
        assertEquals("SYNC", bench.events("abort").single()["stage"])
        assertTrue(bench.on(shared).isEmpty())
        assertTrue(bench.state.asks.isEmpty())
    }

    @Test
    fun aCreditedShareFindsAnOldPlainTitleMp3() {
        val bench = Bench(shared = SharedSong(title = "lettRE (ゲーム「トリノライン」EDテーマ)", artist = "Artist"))
        val file = bench.song("Artist - lettRE.mp3", modified = bench.wallMs - 60_000)
        assertTrue(SessionEffect.Probe(file) in bench.on(SessionEvent.IndexReady(listOf(file), emptyMap())))
        bench.on(bench.probed(file))
        assertEquals(file, bench.state.audio?.file)
    }

    @Test
    fun aDifferentSongSharedStartsANewSession() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        assertFalse(startsNewSession(bench.state, SongIdentity(186016, "晴天", "周杰伦")))
        assertFalse(startsNewSession(bench.state, SongIdentity(title = "晴天")))
        assertTrue(startsNewSession(bench.state, SongIdentity(99)))
        assertTrue(startsNewSession(bench.state, SongIdentity(title = "夜曲")))
        assertFalse(startsNewSession(bench.state, null))
        assertTrue(startsNewSession(null, null))
        bench.on(SessionEvent.Stop("person"))
        assertTrue(startsNewSession(bench.state, SongIdentity(186016)))
    }

    @Test
    fun aSessionInSyncRebuildsItsReference() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
        val effects = bench.restoredFrom(bench.state)
        assertTrue(effects.any { it is SessionEffect.BuildReference })
        assertFalse(bench.state.capturing)
    }

    @Test
    fun aSessionWaitingForItsFileGoesOnWaitingAndKeepsItsClock() {
        val bench = Bench(limits = SessionLimits(audioWaitMs = 60_000))
        bench.listening()
        bench.on(shared)
        bench.run(30_000)
        bench.restoredFrom(bench.state)
        bench.on(SessionEvent.IndexReady(emptyList(), emptyMap()))
        bench.run(31_000)
        assertEquals(FailureKind.NO_FILE, bench.state.failure?.kind)
    }

    @Test
    fun whatIsSavedIsAWholeSession() {
        val bench = Bench()
        bench.listening()
        bench.audioReady()
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val text = json.encodeToString(SessionState.serializer(), bench.state)
        assertEquals(bench.state, json.decodeFromString(SessionState.serializer(), text))
    }

    @Test
    fun theRoutineSecondsDoNotCountAsAChangeToSave() {
        val bench = Bench()
        bench.listening()
        val before = bench.state.persistable()
        val after = bench.state.copy(listenedMs = 5_000, rmsDb = -20.0, silentSeconds = 3).persistable()
        assertEquals(before, after)
    }

    @Test
    fun everyStageAppearsOnceInOrder() {
        val state = Bench().state
        assertEquals(Stage.entries.toList(), state.stages.map { it.stage })
        assertNotNull(state.stage(Stage.PLAY))
    }

    @Test
    fun recognitionTimeDoesNotCountAndAShareDoesNotRestartTheWait() {
        val bench = Bench()
        bench.run(120_000) // Indexing, after the share, has not finished yet.
        assertFalse(Ask.DOWNLOAD_HELP in bench.state.asks)
        assertNull(bench.state.audioWaitSinceNanos)
        bench.listening()
        val started = bench.nanos
        assertEquals(started, bench.state.audioWaitSinceNanos)
        bench.run(9_000)
        bench.on(shared)
        assertEquals(started, bench.state.audioWaitSinceNanos)
        assertTrue(bench.state.running)
        bench.run(6_000)
        assertEquals(FailureKind.NO_FILE, bench.state.failure?.kind)
        assertFalse(bench.state.running)
    }

    @Test
    fun aShareDuringAGrowingDownloadKeepsTheProgressClockAndUsesTheMp3() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        bench.run(8_000)
        val file = bench.song()
        bench.on(SessionEvent.FileSeen(file, Evidence.WRITING))
        val progress = bench.state.audioWaitSinceNanos
        bench.run(5_000)
        bench.on(shared)
        assertEquals(progress, bench.state.audioWaitSinceNanos)
        assertTrue(SessionEffect.Probe(file) in bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE)))
        bench.on(bench.probed(file))
        assertEquals(file, bench.state.audio?.file)
    }

    @Test
    fun stopDuringTheWaitCancelsItAndKeepsThePersonsReason() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        bench.run(9_000)
        val effects = bench.on(SessionEvent.Stop("person"))
        assertTrue(SessionEffect.StopListening in effects)
        assertEquals("person", bench.state.ended)
        assertNull(bench.state.failure)
        assertTrue(bench.run(10_000).isEmpty())
        assertEquals(1, bench.events("session.end").size)
    }

    @Test
    fun aCreditOnlyShareKeepsTheRecognizedAudioChartAndReference() {
        for ((plain, credited) in listOf(
            "lettRE" to "lettRE (ゲーム「トリノライン」EDテーマ)",
            "春日影" to "春日影 (TV动画「BanG Dream! It's MyGO!!!!!」#3 插曲)",
        )) {
            val bench = Bench(shared = SharedSong(title = plain, artist = "Artist"))
            bench.listening()
            val file = bench.song("Artist - $plain.mp3", modified = bench.wallMs - 5_000)
            assertTrue(SessionEffect.Probe(file) in bench.on(SessionEvent.FileSeen(file, Evidence.INDEX)))
            bench.on(bench.probed(file))
            bench.chartReady()
            bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
            val before = bench.state
            val shared = SharedSong(title = credited, artist = "Artist")
            assertFalse(startsNewSession(before, shared.identity))
            bench.on(SessionEvent.ShareReceived(credited, shared, null))
            assertEquals(before.cid, bench.state.cid)
            assertEquals(before.audio, bench.state.audio)
            assertEquals(before.chart, bench.state.chart)
            assertTrue(bench.state.referenceReady)
            assertTrue(bench.state.running)
            assertEquals(StageStatus.DONE, bench.status(Stage.SHARE))
            assertFalse(bench.effects.any { it is SessionEffect.MakeChart || it is SessionEffect.BuildReference })
            assertFalse(SessionEffect.StopChart in bench.effects)
            assertFalse(SessionEffect.StopListening in bench.effects)
            assertTrue(bench.events("abort").isEmpty())
        }
    }

    @Test
    fun aDifferentShareTitleStillReplacesARecognizedSession() {
        val bench = Bench(shared = SharedSong(title = "lettRE", artist = "Artist"))
        bench.listening()
        assertTrue(startsNewSession(bench.state, SongIdentity(title = "春日影", artist = "Artist")))
        assertTrue(startsNewSession(bench.state, SongIdentity(title = "lettRE (Live)", artist = "Artist")))
    }

    @Test
    fun aSessionPicksUpAfterTheProcessDied() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        assertEquals(StageStatus.RUNNING, bench.status(Stage.CHART))
        val saved = bench.state
        bench.advance(30_000)
        val effects = bench.restoredFrom(saved)
        assertTrue(SessionEffect.StartListening in effects)
        assertTrue(SessionEffect.BuildIndex in effects)
        assertTrue(effects.any { it is SessionEffect.MakeChart })
        assertTrue(effects.any { it is SessionEffect.BuildReference })
        assertFalse(bench.state.foreground)
        assertEquals(1, bench.events("session.restored").size)
        assertEquals("晴天", bench.state.song?.title)
    }

    @Test
    fun theSnapshotKeepsOnlyTheLatestSixtyFourUncertainFiles() {
        val bench = Bench()
        repeat(100) { index ->
            bench.on(SessionEvent.FileSeen(bench.song("Candidate $index.mp3"), Evidence.CLOSE_WRITE))
        }
        assertEquals(64, bench.state.held.size)
        assertEquals("Candidate 99.mp3", bench.state.held.last().name)
    }

    @Test
    fun aPublishedMediaStoreUriRetriesAnUnreadableFolderFileWithoutGrowth() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        val file = bench.song(modified = bench.wallMs - 5000)
        assertEquals(listOf<SessionEffect>(SessionEffect.Probe(file)), bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE)))
        bench.on(SessionEvent.Probed(file, ProbeFacts(false, failure = "not readable yet", sizeBytes = file.sizeBytes, modifiedWallMs = file.modifiedWallMs)))
        assertTrue(bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE)).isEmpty())
        val published = file.copy(uri = "content://media/external/audio/media/42")
        assertEquals(listOf<SessionEffect>(SessionEffect.Probe(published)), bench.on(SessionEvent.FileSeen(published, Evidence.MEDIA_STORE)))
        bench.on(bench.probed(published))
        assertEquals(published, bench.state.audio?.file)
        assertEquals("MEDIA_STORE", bench.events("audio.ready").single()["evidence"])
    }

    @Test
    fun mediaAccessArrivingDuringTheFilesystemProbeIsNotLost() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        val file = bench.song(modified = bench.wallMs - 5000)
        bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE))
        val published = file.copy(uri = "content://media/external/audio/media/43")
        assertTrue(bench.on(SessionEvent.FileSeen(published, Evidence.MEDIA_STORE)).isEmpty())
        val retry = bench.on(SessionEvent.Probed(file, ProbeFacts(false, failure = "not readable yet", sizeBytes = file.sizeBytes, modifiedWallMs = file.modifiedWallMs)))
        assertEquals(listOf<SessionEffect>(SessionEffect.Probe(published)), retry)
        bench.on(bench.probed(published))
        assertEquals(published, bench.state.audio?.file)
    }

}
