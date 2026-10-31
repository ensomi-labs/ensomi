package io.ensomi.android.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plays a session on a clock of its own, keeping what it asked for and what it traced. */
private class Bench(
    byShare: Boolean = false,
    modelInstalled: Boolean = true,
    private val limits: SessionLimits = SessionLimits(),
    path: LaunchPath = LaunchPath.AUTO,
) {
    var nanos = 10_000_000_000L
    var wallMs = 1_700_000_000_000L
    val traced = ArrayList<Pair<String, Map<String, Any?>>>()
    var machine: SessionMachine
    var effects: List<SessionEffect>

    init {
        val (started, first) = SessionMachine.start("cid", path, byShare, modelInstalled, now(), limits, ::traceIt)
        machine = started
        effects = first
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
        val (restored, effects) = SessionMachine.restored(state, now(), limits, ::traceIt)
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
        assertEquals(StageStatus.WAITING, bench.status(Stage.OPEN))
        assertEquals("session.start", bench.traced.first().first)
    }

    @Test
    fun theHostStartsOnlyOnceTheIndexIsBuilt() {
        val bench = Bench(path = LaunchPath.B)
        val effects = bench.on(SessionEvent.IndexReady(emptyList(), emptyMap()))
        assertEquals(listOf<SessionEffect>(SessionEffect.Launch(LaunchPath.B)), effects)
        assertEquals(StageStatus.DONE, bench.status(Stage.LISTEN))
        assertEquals(StageStatus.RUNNING, bench.status(Stage.OPEN))
        assertEquals(0, bench.events("index.ready").single()["entries"])
    }

    @Test
    fun aWholeSessionInOrderByHand() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        assertEquals(listOf<SessionEffect>(SessionEffect.Perform(HostStep.OPEN_RECOGNITION)), bench.on(SessionEvent.HostLaunched("deep_link")))
        bench.on(SessionEvent.HandedToPerson(HostStep.OPEN_RECOGNITION, "manual"))
        assertEquals(listOf(Ask.OPEN_RECOGNITION, Ask.TAP_DOWNLOAD, Ask.SHARE_TO_ENSOMI), bench.state.asks)
        assertEquals(StageStatus.NEEDS_PERSON, bench.status(Stage.OPEN))
        assertEquals("manual", bench.events("manual_intervention").single()["reason"])

        bench.on(shared)
        assertEquals(SongIdentity(186016, "晴天", "周杰伦"), bench.state.song)
        assertEquals(StageStatus.DONE, bench.status(Stage.SHARE))
        assertEquals(StageStatus.SKIPPED, bench.status(Stage.OPEN))
        assertEquals(StageStatus.SKIPPED, bench.status(Stage.DOWNLOAD))
        assertEquals(StageStatus.RUNNING, bench.status(Stage.AUDIO))
        assertEquals(listOf(Ask.WAIT_FOR_DOWNLOAD), bench.state.asks)

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
        assertTrue(sync.single() is SessionEffect.BuildReference)
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
        bench.listening()
        val file = bench.song(modified = bench.wallMs + 5_000)
        bench.advance(6_000)
        assertTrue(bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE)).isEmpty())
        assertEquals("NO_SONG_YET", bench.events("audio.candidate").single()["verdict"])
        assertEquals(listOf(file), bench.state.held)

        val effects = bench.on(shared)
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
        val bench = Bench(byShare = true)
        val existing = FileFacts("/music/周杰伦 - 晴天.mp3", "周杰伦 - 晴天.mp3", 8_000_000, bench.wallMs - 86_400_000)
        bench.on(SessionEvent.IndexReady(listOf(existing), emptyMap()))
        val effects = bench.on(shared)
        assertTrue(effects.toString(), SessionEffect.Probe(existing) in effects)
        assertEquals("PROBE", bench.events("audio.candidate").last()["verdict"])
        assertEquals("INDEX", bench.events("audio.candidate").last()["evidence"])
    }

    @Test
    fun aFileRememberedForTheSongIdIsFoundWithoutItsName() {
        val bench = Bench(byShare = true)
        val existing = FileFacts("/music/track.mp3", "track.mp3", 8_000_000, bench.wallMs - 86_400_000)
        bench.on(SessionEvent.IndexReady(listOf(existing), mapOf(186016L to existing.path)))
        assertTrue(SessionEffect.Probe(existing) in bench.on(shared))
        assertEquals("SONG_ID", bench.events("audio.candidate").last()["match"])
    }

    @Test
    fun theIndexLeavesTheTraceWithOnlyWhatCouldBeTheSong() {
        val bench = Bench(byShare = true)
        val others = List(50) { FileFacts("/music/$it.mp3", "$it.mp3", 1_000, bench.wallMs - 86_400_000) }
        bench.on(SessionEvent.IndexReady(others, emptyMap()))
        bench.on(shared)
        assertTrue(bench.events("audio.candidate").isEmpty())
    }

    @Test
    fun anOldFileThatIsNotTheSongLeavesNoLineHoweverItIsSeen() {
        val bench = Bench(byShare = true)
        bench.listening()
        bench.on(shared)
        val other = FileFacts("/music/other.mp3", "other.mp3", 1_000, bench.wallMs - 86_400_000)
        bench.on(SessionEvent.FileSeen(other, Evidence.POLL))
        assertTrue(bench.events("audio.candidate").isEmpty())
    }

    @Test
    fun aNewerIndexOverrulesWhatACandidateRemembers() {
        val bench = Bench(byShare = true)
        val row = bench.song(modified = bench.wallMs - 5_000).copy(pending = true)
        bench.on(SessionEvent.IndexReady(listOf(row), emptyMap()))
        bench.on(shared)
        assertEquals("UNFINISHED", bench.events("audio.candidate").last()["verdict"])
        val done = row.copy(pending = false)
        val effects = bench.on(SessionEvent.IndexReady(listOf(done), emptyMap()))
        assertTrue(effects.toString(), SessionEffect.Probe(done) in effects)
    }

    @Test
    fun aShareStartsASessionWithoutTheHost() {
        val bench = Bench(byShare = true)
        val effects = bench.on(SessionEvent.IndexReady(emptyList(), emptyMap()))
        assertTrue(effects.none { it is SessionEffect.Launch })
        bench.on(shared)
        assertEquals(StageStatus.SKIPPED, bench.status(Stage.OPEN))
        assertEquals(StageStatus.RUNNING, bench.status(Stage.AUDIO))
    }

    @Test
    fun aDriverWalksTheStepsAndEachReportMovesItOn() {
        val bench = Bench()
        bench.listening()
        bench.on(SessionEvent.HostLaunched("launcher"))
        val result = bench.on(SessionEvent.StepDone(HostStep.OPEN_RECOGNITION, null))
        assertEquals(listOf<SessionEffect>(SessionEffect.Perform(HostStep.READ_RESULT)), result)
        val download = bench.on(SessionEvent.ResultSeen("晴天", "周杰伦", 269_000))
        assertEquals(SessionEffect.Perform(HostStep.TAP_DOWNLOAD), download.first())
        assertEquals("晴天", bench.state.song?.title)
        assertEquals(StageStatus.DONE, bench.status(Stage.RESULT))
        assertEquals(StageStatus.RUNNING, bench.status(Stage.AUDIO))
        val share = bench.on(SessionEvent.StepDone(HostStep.TAP_DOWNLOAD, null))
        assertEquals(listOf<SessionEffect>(SessionEffect.Perform(HostStep.SHARE_TO_ENSOMI)), share)
        assertTrue(bench.state.asks.isEmpty())
        bench.on(shared)
        assertEquals(StageStatus.DONE, bench.status(Stage.DOWNLOAD))
        assertEquals(186016L, bench.state.song?.songId)
        assertNull(bench.state.driverStep)
    }

    @Test
    fun aStepTheDriverCannotDoGoesToThePersonAndTheTestGoesOn() {
        val bench = Bench()
        bench.listening()
        bench.on(SessionEvent.HostLaunched("deep_link"))
        bench.on(SessionEvent.StepDone(HostStep.OPEN_RECOGNITION, null))
        bench.on(SessionEvent.ResultSeen("晴天", "周杰伦", null))
        bench.on(SessionEvent.HandedToPerson(HostStep.TAP_DOWNLOAD, "no download button"))
        assertEquals(listOf(Ask.TAP_DOWNLOAD, Ask.SHARE_TO_ENSOMI), bench.state.asks)
        assertEquals(StageStatus.NEEDS_PERSON, bench.status(Stage.DOWNLOAD))
        assertEquals("no download button", bench.events("manual_intervention").single()["reason"])
        // The person taps, and the file arrives without any report.
        val file = bench.song(modified = bench.wallMs + 2_000)
        bench.advance(4_000)
        assertTrue(SessionEffect.Probe(file) in bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE)))
    }

    @Test
    fun aDriverThatGoesQuietHandsOverAfterItsTime() {
        val limits = SessionLimits(driverStepMs = 5_000, readResultMs = 20_000)
        val bench = Bench(limits = limits)
        bench.listening()
        bench.on(SessionEvent.HostLaunched("deep_link"))
        bench.run(4_000)
        assertTrue(bench.state.asks.isEmpty())
        assertTrue(SessionEffect.CancelDriver in bench.run(2_000))
        assertEquals("driver_timeout", bench.events("manual_intervention").single()["reason"])
        assertEquals(Ask.OPEN_RECOGNITION, bench.state.asks.first())
    }

    @Test
    fun readingTheResultMayTakeLongerThanOtherSteps() {
        val limits = SessionLimits(driverStepMs = 5_000, readResultMs = 20_000)
        val bench = Bench(limits = limits)
        bench.listening()
        bench.on(SessionEvent.HostLaunched("deep_link"))
        bench.on(SessionEvent.StepDone(HostStep.OPEN_RECOGNITION, null))
        bench.run(10_000)
        assertEquals(HostStep.READ_RESULT, bench.state.driverStep)
        bench.run(11_000)
        assertNull(bench.state.driverStep)
    }

    @Test
    fun aHostThatIsNotInstalledFailsItsStageAndAShareStillGoesOn() {
        val bench = Bench()
        bench.listening()
        bench.on(SessionEvent.HostNotInstalled)
        assertEquals(FailureKind.HOST_NOT_INSTALLED, bench.state.failure?.kind)
        assertEquals(StageStatus.FAILED, bench.status(Stage.OPEN))
        assertTrue(bench.state.running)
        bench.on(shared)
        assertNull(bench.state.failure)
        assertEquals(StageStatus.RUNNING, bench.status(Stage.AUDIO))
    }

    @Test
    fun recognitionThatFailsSaysSoAndCanStartAgain() {
        val bench = Bench()
        bench.listening()
        bench.on(SessionEvent.HostLaunched("deep_link"))
        bench.on(SessionEvent.RecognitionFailed("没有结果"))
        assertEquals(FailureKind.RECOGNITION_FAILED, bench.state.failure?.kind)
        assertEquals(StageStatus.FAILED, bench.status(Stage.RESULT))
        assertEquals(Stage.OPEN, bench.state.failure?.kind?.retryFrom)
        val again = bench.on(SessionEvent.Retry(Stage.OPEN))
        assertTrue(again.toString(), SessionEffect.Launch(LaunchPath.AUTO) in again)
        assertNull(bench.state.failure)
        assertEquals(StageStatus.RUNNING, bench.status(Stage.OPEN))
        assertEquals(StageStatus.WAITING, bench.status(Stage.RESULT))
    }

    @Test
    fun aSongWithNoDownloadRightSaysSo() {
        val bench = Bench()
        bench.listening()
        bench.on(SessionEvent.DownloadRefused("VIP"))
        assertEquals(FailureKind.NO_DOWNLOAD_RIGHT, bench.state.failure?.kind)
        assertEquals("VIP", bench.state.failure?.detail)
    }

    @Test
    fun aShareOfSomethingElseIsSaidAndWaitsForTheRealOne() {
        val bench = Bench()
        bench.listening()
        val text = "分享某人的歌单《夜跑》: https://music.163.com/playlist?id=1"
        bench.on(SessionEvent.ShareReceived(text, ShareParser.parse(text), null))
        assertEquals(FailureKind.NOT_A_SONG, bench.state.failure?.kind)
        assertEquals("playlist", bench.state.failure?.detail)
        bench.on(shared)
        assertNull(bench.state.failure)
        assertEquals("晴天", bench.state.song?.title)
    }

    @Test
    fun aShortLinkIsLookedUpAndItsFailureStopsNothing() {
        val text = "分享周杰伦的单曲《晴天》: https://163cn.tv/zoIxm3 (来自@网易云音乐)"
        val bench = Bench()
        bench.listening()
        val effects = bench.on(SessionEvent.ShareReceived(text, ShareParser.parse(text), null))
        assertEquals(SessionEffect.LookUp("https://163cn.tv/zoIxm3"), effects.first())
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
        val bench = Bench(byShare = true)
        val existing = FileFacts("/music/track.mp3", "track.mp3", 8_000_000, bench.wallMs - 86_400_000)
        bench.on(SessionEvent.IndexReady(listOf(existing), mapOf(5L to existing.path)))
        bench.on(SessionEvent.ShareReceived(text, ShareParser.parse(text), null))
        assertTrue(SessionEffect.Probe(existing) in bench.on(SessionEvent.LinkLookedUp(text, 5, "resolved")))
    }

    @Test
    fun aShareWithOnlyALinkTakesTheNewFile() {
        val text = "https://music.163.com/song?id=5"
        val bench = Bench(byShare = true)
        bench.listening()
        bench.on(SessionEvent.ShareReceived(text, ShareParser.parse(text), null))
        val file = bench.song("whatever.mp3", modified = bench.wallMs - 5).copy(modifiedWallMs = bench.wallMs + 3_000)
        bench.advance(5_000)
        assertTrue(SessionEffect.Probe(file) in bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE)))
        assertEquals("NEW_FILE", bench.events("audio.candidate").last()["match"])
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
    fun theIdentityCanComeAfterANcmWasSeen() {
        val bench = Bench()
        bench.listening()
        bench.on(SessionEvent.FileSeen(bench.song("周杰伦 - 晴天.ncm", modified = bench.wallMs + 1_000), Evidence.CLOSE_WRITE))
        assertNull(bench.state.failure)
        bench.advance(3_000)
        bench.on(shared)
        assertEquals(FailureKind.PROTECTED_TRACK, bench.state.failure?.kind)
    }

    @Test
    fun aFileThatDoesNotDecodeIsPassedOverForTheNext() {
        val bench = Bench()
        bench.listening()
        bench.on(shared)
        val bad = bench.song("周杰伦 - 晴天.flac", modified = bench.wallMs - 5_000)
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
    fun theFileNeverArriving() {
        val bench = Bench(limits = SessionLimits(fileMs = 60_000))
        bench.listening()
        bench.on(shared)
        bench.run(59_000)
        assertNull(bench.state.failure)
        bench.run(2_000)
        assertEquals(FailureKind.NO_FILE, bench.state.failure?.kind)
        assertEquals(StageStatus.FAILED, bench.status(Stage.AUDIO))
        assertTrue(bench.state.running)
        // What arrives late still counts.
        val file = bench.song(modified = bench.wallMs - 5_000)
        assertTrue(SessionEffect.Probe(file) in bench.on(SessionEvent.FileSeen(file, Evidence.CLOSE_WRITE)))
        bench.on(bench.probed(file))
        assertNull(bench.state.failure)
        assertEquals(StageStatus.DONE, bench.status(Stage.AUDIO))
    }

    @Test
    fun noShareArriving() {
        val bench = Bench(limits = SessionLimits(hostMs = 30_000))
        bench.listening()
        bench.on(SessionEvent.HostLaunched("deep_link"))
        bench.on(SessionEvent.HandedToPerson(HostStep.OPEN_RECOGNITION, "manual"))
        bench.run(31_000)
        assertEquals(FailureKind.NO_SHARE, bench.state.failure?.kind)
        bench.on(shared)
        assertNull(bench.state.failure)
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
    fun noModelStopsBeforeTheHostStarts() {
        val bench = Bench(modelInstalled = false)
        assertEquals(FailureKind.MODEL_MISSING, bench.state.failure?.kind)
        val effects = bench.on(SessionEvent.IndexReady(emptyList(), emptyMap()))
        assertTrue(effects.none { it is SessionEffect.Launch })
        // Still no model: the failure stands.
        assertTrue(bench.on(SessionEvent.Retry(Stage.CHART, modelInstalled = false)).none { it is SessionEffect.Launch })
        assertEquals(FailureKind.MODEL_MISSING, bench.state.failure?.kind)
        // Installed since: the session goes on from the start.
        val again = bench.on(SessionEvent.Retry(Stage.CHART, modelInstalled = true))
        assertTrue(again.any { it is SessionEffect.Launch })
        assertNull(bench.state.failure)
    }

    @Test
    fun noModelFoundAfterTheShareStopsAtTheChartAndGoesOnWhenItIsThere() {
        val bench = Bench(byShare = true, modelInstalled = false)
        bench.listening()
        bench.audioReady()
        assertEquals(FailureKind.MODEL_MISSING, bench.state.failure?.kind)
        assertTrue(bench.effects.none { it is SessionEffect.MakeChart })
        assertTrue(bench.on(SessionEvent.Retry(Stage.CHART)).any { it is SessionEffect.MakeChart })
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
    fun aFailureEndsWithItsSession() {
        val bench = Bench()
        bench.listening()
        bench.on(SessionEvent.HostNotInstalled)
        assertEquals(FailureKind.HOST_NOT_INSTALLED, bench.state.failure?.kind)
        bench.on(SessionEvent.Stop("person"))
        assertNull(bench.state.failure)
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
    fun aShareInASessionWithoutASongIsItsSong() {
        val bench = Bench()
        bench.listening()
        assertFalse(startsNewSession(bench.state, SongIdentity(99)))
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
        assertFalse(bench.state.foreground)
        assertEquals(1, bench.events("session.restored").size)
        assertEquals("晴天", bench.state.song?.title)
        // Listening comes back, and the host is not started twice.
        assertTrue(bench.on(SessionEvent.IndexReady(emptyList(), emptyMap())).none { it is SessionEffect.Launch })
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
        val bench = Bench(limits = SessionLimits(fileMs = 60_000))
        bench.listening()
        bench.on(shared)
        bench.run(30_000)
        bench.restoredFrom(bench.state)
        bench.on(SessionEvent.IndexReady(emptyList(), emptyMap()))
        bench.run(31_000)
        assertEquals(FailureKind.NO_FILE, bench.state.failure?.kind)
    }

    @Test
    fun aGameTheProcessDiedWithIsLost() {
        val bench = Bench()
        bench.on(SessionEvent.Frontmost(true))
        bench.listening()
        bench.audioReady()
        bench.chartReady()
        bench.on(SessionEvent.ReferenceReady("runtime", 1.0))
        bench.on(lock())
        bench.restoredFrom(bench.state)
        assertEquals("lost", bench.state.ended)
        assertEquals(FailureKind.SESSION_LOST, bench.state.failure?.kind)
    }

    @Test
    fun anOldSessionIsNotPickedUp() {
        val bench = Bench()
        bench.listening()
        val saved = bench.state
        bench.advance(SessionLimits().restoreMs + 1)
        bench.restoredFrom(saved)
        assertEquals("lost", bench.state.ended)
        assertEquals(FailureKind.SESSION_LOST, bench.state.failure?.kind)
    }

    @Test
    fun aSessionFromBeforeARebootIsNotPickedUp() {
        val bench = Bench()
        val saved = bench.state.copy(startedNanos = bench.nanos + 1_000_000_000)
        bench.restoredFrom(saved)
        assertEquals("lost", bench.state.ended)
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
}
