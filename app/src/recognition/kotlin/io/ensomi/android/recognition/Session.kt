package io.ensomi.android.recognition

import kotlinx.serialization.Serializable

/** The pipeline's stages, in the order they normally finish. Each is shown on the session's screen. */
@Serializable
enum class Stage(val hostStep: HostStep? = null) {
    /** Media listening registered and the local audio index built. */
    LISTEN,
    OPEN(HostStep.OPEN_RECOGNITION),
    RESULT(HostStep.READ_RESULT),
    DOWNLOAD(HostStep.TAP_DOWNLOAD),
    SHARE(HostStep.SHARE_TO_ENSOMI),

    /** A complete, decodable file of the song. */
    AUDIO,
    CHART,
    SYNC,
    PLAY,
}

@Serializable
enum class StageStatus { WAITING, RUNNING, NEEDS_PERSON, DONE, SKIPPED, FAILED }

@Serializable
data class StageView(
    val stage: Stage,
    val status: StageStatus = StageStatus.WAITING,
    /** On the session's `elapsedRealtimeNanos` clock. */
    val startedNanos: Long? = null,
    val endedNanos: Long? = null,
    val detail: String? = null,
)

/** Why the session is stuck. [retryFrom] is where trying again starts. Only [ended] sessions are over. */
@Serializable
enum class FailureKind(val retryFrom: Stage) {
    HOST_NOT_INSTALLED(Stage.OPEN),
    HOST_LAUNCH_FAILED(Stage.OPEN),
    RECOGNITION_FAILED(Stage.OPEN),
    NO_DOWNLOAD_RIGHT(Stage.OPEN),
    NO_SHARE(Stage.OPEN),
    NOT_A_SONG(Stage.OPEN),
    NO_FILE(Stage.AUDIO),
    PROTECTED_TRACK(Stage.AUDIO),
    MODEL_MISSING(Stage.CHART),
    CHART_FAILED(Stage.CHART),
    SYNC_FAILED(Stage.SYNC),
    SYNC_NEVER_LOCKED(Stage.SYNC),
    CAPTURE_FAILED(Stage.SYNC),
    SONG_ENDED(Stage.SYNC),
    GAME_FAILED(Stage.PLAY),
    GAME_QUIT(Stage.PLAY),
    SESSION_LOST(Stage.OPEN),
}

@Serializable
data class Failure(val kind: FailureKind, val stage: Stage, val detail: String? = null)

/** What the person must do by hand right now, or know. */
@Serializable
enum class Ask {
    OPEN_RECOGNITION,
    TAP_DOWNLOAD,
    SHARE_TO_ENSOMI,

    /** The share arrived and no file has. */
    WAIT_FOR_DOWNLOAD,

    /** The session needs the microphone, and Ensomi is not in front. */
    RETURN_TO_ENSOMI,

    /** The microphone has heard nothing for a while. */
    MICROPHONE_SILENT,
}

/** The song's file, once it is known to be complete and decodable. */
@Serializable
data class ReadyAudio(
    val file: FileFacts,
    val decodedMs: Double,
    val sampleRate: Int,
    val sha256: String?,
    val title: String,
    val artist: String?,
)

@Serializable
data class ChartFacts(val songId: String, val noteCount: Int, val stored: Boolean)

/** The final lock: [referenceMs] of song time is what was captured at [captureNanos] on `CLOCK_MONOTONIC`. */
@Serializable
data class LockAnchor(val referenceMs: Double, val captureNanos: Long)

@Serializable
data class JoinFacts(val joinMs: Double, val heardMs: Double, val notesKept: Int, val notesSkipped: Int)

@Serializable
data class GameSummary(
    val accuracy: Double,
    val perfect: Int,
    val good: Int,
    val miss: Int,
    val maxCombo: Int,
    val meanOffsetMs: Double?,
)

/** Everything the session knows, which is also what survives a process death. */
@Serializable
data class SessionState(
    val cid: String,
    val path: LaunchPath,
    val startedNanos: Long,
    val startedWallMs: Long,
    /** A share started it: the person is already past the host app's steps. */
    val byShare: Boolean,
    val modelInstalled: Boolean,
    val stages: List<StageView> = Stage.entries.map { StageView(it) },
    val launched: Boolean = false,
    val song: SongIdentity? = null,
    val audio: ReadyAudio? = null,
    val chart: ChartFacts? = null,
    val lock: LockAnchor? = null,
    val joined: JoinFacts? = null,
    val result: GameSummary? = null,
    val asks: List<Ask> = emptyList(),
    val failure: Failure? = null,
    /** Files that may be the song, for the person to choose when nothing else can say. */
    val held: List<FileFacts> = emptyList(),
    /** The step a driver is working on, and since when. */
    val driverStep: HostStep? = null,
    val driverSinceNanos: Long? = null,
    /** The first step the person was asked to do; the steps after it are the person's too. */
    val personFrom: HostStep? = null,
    val shareNanos: Long? = null,
    val foreground: Boolean = false,
    val referenceReady: Boolean = false,
    val capturing: Boolean = false,
    /** How long capture has run in this sync. */
    val listenedMs: Long = 0,
    val syncState: String? = null,
    /** Why the aligner holds no position, in its words. */
    val syncDetail: String? = null,
    val syncConfidence: Double? = null,
    val rmsDb: Double? = null,
    val silentSeconds: Int = 0,
    /** Why the session is over; null while it goes on. */
    val ended: String? = null,
) {
    fun stage(stage: Stage): StageView = stages[stage.ordinal]

    val running: Boolean get() = ended == null

    /** What a snapshot needs: the parts that change every second are left out of the comparison. */
    fun persistable(): SessionState = copy(listenedMs = 0, rmsDb = null, silentSeconds = 0, syncConfidence = null)
}

/** The time limits of a session: generous, since a person is in the loop. */
data class SessionLimits(
    /** How long a driver may take over a step before the person is asked; recognition itself takes a while. */
    val driverStepMs: Long = 30_000,
    val readResultMs: Long = 90_000,
    /** From the launch, how long until a share or a file must have arrived. */
    val hostMs: Long = 600_000,
    /** From the share, how long the file has to arrive. */
    val fileMs: Long = 900_000,
    /** How long capture may run without a final lock. */
    val syncMs: Long = 180_000,
    /** A session this much older than its last snapshot is not picked up again. */
    val restoreMs: Long = 1_800_000,
    /** Below this level, for [silentAfterSeconds], the screen says the microphone hears nothing. */
    val silentDb: Double = -60.0,
    val silentAfterSeconds: Int = 15,
)

/** A moment on both of the session's clocks. */
data class SessionNow(val nanos: Long, val wallMs: Long)

/** What happened. Each is an observation; the session goes on from what it sees, in whatever order. */
sealed interface SessionEvent {
    data class IndexReady(val entries: List<FileFacts>, val known: Map<Long, String>) : SessionEvent

    data class HostLaunched(val how: String) : SessionEvent

    data object HostNotInstalled : SessionEvent

    data class HostLaunchFailed(val reason: String) : SessionEvent

    data class StepStarted(val step: HostStep, val detail: String?) : SessionEvent

    data class StepDone(val step: HostStep, val detail: String?) : SessionEvent

    data class HandedToPerson(val step: HostStep, val reason: String) : SessionEvent

    data class ResultSeen(val title: String, val artist: String?, val durationMs: Long?) : SessionEvent

    data class RecognitionFailed(val reason: String) : SessionEvent

    data class DownloadRefused(val reason: String) : SessionEvent

    /** [song] is null when the text says nothing of a song. [source] is the sharing app, or how the text got here. */
    data class ShareReceived(val text: String, val song: SharedSong?, val source: String?) : SessionEvent

    /** A short link's lookup, which only adds what it found. */
    data class LinkLookedUp(val url: String, val songId: Long?, val outcome: String) : SessionEvent

    data class FileSeen(val file: FileFacts, val evidence: Evidence) : SessionEvent

    data class FileGone(val path: String) : SessionEvent

    data class Probed(val file: FileFacts, val probe: ProbeFacts) : SessionEvent

    /** The person says this file is the song. */
    data class UseFile(val path: String) : SessionEvent

    data class ChartPhase(val phase: String) : SessionEvent

    data class ChartReady(val chart: ChartFacts, val seconds: Double) : SessionEvent

    data class ChartFailed(val message: String, val modelMissing: Boolean = false) : SessionEvent

    data class ReferenceReady(val backend: String, val seconds: Double) : SessionEvent

    data class ReferenceFailed(val reason: String) : SessionEvent

    data class CaptureStarted(val source: String) : SessionEvent

    data class CaptureFailed(val reason: String) : SessionEvent

    data class CaptureLevel(val rmsDb: Double, val peakDb: Double) : SessionEvent

    data class SyncUpdate(val reading: SyncReading) : SessionEvent

    data class Joined(val join: JoinFacts) : SessionEvent

    data class JoinRefused(val reason: String) : SessionEvent

    data class GameFailed(val reason: String) : SessionEvent

    data class GameEnded(val summary: GameSummary) : SessionEvent

    data object GameQuit : SessionEvent

    data class Frontmost(val front: Boolean) : SessionEvent

    data object Tick : SessionEvent

    /** Try again from [stage]; [modelInstalled] is as it stands now. */
    data class Retry(val stage: Stage, val modelInstalled: Boolean = true) : SessionEvent

    data class Stop(val reason: String) : SessionEvent
}

/** What the session wants done. The controller carries these out and reports back with [SessionEvent]s. */
sealed interface SessionEffect {
    data object StartListening : SessionEffect

    data object BuildIndex : SessionEffect

    data class Launch(val path: LaunchPath) : SessionEffect

    data class Perform(val step: HostStep) : SessionEffect

    data object CancelDriver : SessionEffect

    /** Look at this file again. */
    data class Refresh(val path: String) : SessionEffect

    data class Probe(val file: FileFacts) : SessionEffect

    data class LookUp(val url: String) : SessionEffect

    data class Remember(val songId: Long, val path: String) : SessionEffect

    data class MakeChart(val audio: ReadyAudio) : SessionEffect

    data object StopChart : SessionEffect

    data class BuildReference(val audio: ReadyAudio) : SessionEffect

    data object StartCapture : SessionEffect

    data object StopCapture : SessionEffect

    data class Join(val lock: LockAnchor) : SessionEffect

    data object StopListening : SessionEffect
}

/** Whether a share belongs to a new session rather than to [state]. */
fun startsNewSession(state: SessionState?, shared: SongIdentity?): Boolean = when {
    state == null || !state.running -> true
    shared == null || state.song == null -> false
    else -> !state.song.sameSongAs(shared)
}

/**
 * One recognition session, as plain state and rules: it takes events, keeps what they say, and answers with what to do
 * next, so that the order things happen in cannot be got wrong by the code around it. It holds facts rather than a
 * position in a sequence: a share reaches it before or after a file, a driver's report or none at all, and each fact
 * moves on whatever it unblocks. A failure stops nothing: the session goes on listening until it is stopped, and what
 * arrives late still counts.
 *
 * Not thread-safe; the controller feeds it from one coroutine.
 */
class SessionMachine private constructor(
    initial: SessionState,
    private val trace: (String, Map<String, Any?>) -> Unit,
    private val limits: SessionLimits,
) {
    var state: SessionState = initial
        private set

    private val effects = ArrayList<SessionEffect>()
    private var now = SessionNow(initial.startedNanos, initial.startedWallMs)
    private var lastTickNanos = initial.startedNanos
    private var index: List<FileFacts> = emptyList()
    private var known: Map<Long, String> = emptyMap()
    private val candidates = LinkedHashMap<String, Candidate>()
    private var probing: String? = null

    /** What the session remembers of one file. */
    private class Candidate(
        val file: FileFacts,
        val evidence: Evidence,
        val lastChangeWallMs: Long,
        val verdict: Verdict,
        val match: MatchStrength,
        val person: Boolean,
        /** When to look at it again; null when nothing but a change would alter the verdict. */
        val recheckNanos: Long?,
    )

    private fun begin(): List<SessionEffect> = collect {
        log(
            "session.start",
            "path" to state.path.name,
            "by_share" to state.byShare,
            "model_installed" to state.modelInstalled,
            "wall_ms" to state.startedWallMs,
        )
        if (!state.modelInstalled) fail(FailureKind.MODEL_MISSING, Stage.CHART, null)
        setStage(Stage.LISTEN, StageStatus.RUNNING)
        effects += SessionEffect.StartListening
        effects += SessionEffect.BuildIndex
    }

    /** Takes [event] at [time]; returns what is to be done, in order. */
    fun on(event: SessionEvent, time: SessionNow): List<SessionEffect> = collect {
        now = time
        if (state.running || event is SessionEvent.GameQuit) handle(event)
    }

    private inline fun collect(block: () -> Unit): List<SessionEffect> {
        effects.clear()
        block()
        state = state.copy(asks = asks())
        return effects.toList()
    }

    private fun handle(event: SessionEvent) {
        when (event) {
            is SessionEvent.IndexReady -> indexReady(event)
            is SessionEvent.HostLaunched -> {
                log("host.launch", "how" to event.how)
                askDriver(HostStep.OPEN_RECOGNITION)
            }
            SessionEvent.HostNotInstalled -> {
                log("host.launch", "how" to "none", "failure" to "not_installed")
                fail(FailureKind.HOST_NOT_INSTALLED, Stage.OPEN, null)
            }
            is SessionEvent.HostLaunchFailed -> {
                log("host.launch", "how" to "none", "failure" to event.reason)
                fail(FailureKind.HOST_LAUNCH_FAILED, Stage.OPEN, event.reason)
            }
            is SessionEvent.StepStarted -> {
                log("driver.step", "step" to event.step.name, "state" to "started", "detail" to event.detail)
                markStep(event.step, StageStatus.RUNNING, event.detail)
            }
            is SessionEvent.StepDone -> stepDone(event)
            is SessionEvent.HandedToPerson -> handToPerson(event.step, event.reason)
            is SessionEvent.ResultSeen -> resultSeen(event)
            is SessionEvent.RecognitionFailed -> {
                log("driver.step", "step" to HostStep.READ_RESULT.name, "state" to "no_result", "reason" to event.reason)
                fail(FailureKind.RECOGNITION_FAILED, Stage.RESULT, event.reason)
            }
            is SessionEvent.DownloadRefused -> {
                log("driver.step", "step" to HostStep.TAP_DOWNLOAD.name, "state" to "refused", "reason" to event.reason)
                fail(FailureKind.NO_DOWNLOAD_RIGHT, Stage.DOWNLOAD, event.reason)
            }
            is SessionEvent.ShareReceived -> shareReceived(event)
            is SessionEvent.LinkLookedUp -> linkLookedUp(event)
            is SessionEvent.FileSeen -> consider(event.file, event.evidence)
            is SessionEvent.FileGone -> fileGone(event.path)
            is SessionEvent.Probed -> probed(event)
            is SessionEvent.UseFile -> useFile(event.path)
            is SessionEvent.ChartPhase -> {
                log("chart.phase", "phase" to event.phase)
                setStage(Stage.CHART, StageStatus.RUNNING, event.phase)
            }
            is SessionEvent.ChartReady -> chartReady(event)
            is SessionEvent.ChartFailed -> {
                log("chart.failed", "message" to event.message, "model_missing" to event.modelMissing)
                fail(if (event.modelMissing) FailureKind.MODEL_MISSING else FailureKind.CHART_FAILED, Stage.CHART, event.message)
            }
            is SessionEvent.ReferenceReady -> {
                log("sync.reference", "backend" to event.backend, "seconds" to event.seconds)
                state = state.copy(referenceReady = true)
                setStage(Stage.SYNC, StageStatus.RUNNING, event.backend)
                captureIfWanted()
            }
            is SessionEvent.ReferenceFailed -> {
                log("sync.reference", "failure" to event.reason)
                fail(FailureKind.SYNC_FAILED, Stage.SYNC, event.reason)
            }
            is SessionEvent.CaptureStarted -> log("capture.start", "source" to event.source)
            is SessionEvent.CaptureFailed -> {
                log("capture.failed", "reason" to event.reason)
                state = state.copy(capturing = false)
                fail(FailureKind.CAPTURE_FAILED, Stage.SYNC, event.reason)
            }
            is SessionEvent.CaptureLevel -> captureLevel(event)
            is SessionEvent.SyncUpdate -> syncUpdate(event.reading)
            is SessionEvent.Joined -> {
                log(
                    "game.join",
                    "join_ms" to event.join.joinMs,
                    "heard_ms" to event.join.heardMs,
                    "notes_kept" to event.join.notesKept,
                    "notes_skipped" to event.join.notesSkipped,
                )
                state = state.copy(joined = event.join)
            }
            is SessionEvent.JoinRefused -> {
                log("game.join", "refused" to event.reason)
                fail(FailureKind.SONG_ENDED, Stage.PLAY, event.reason)
            }
            is SessionEvent.GameFailed -> {
                log("game.failed", "reason" to event.reason)
                fail(FailureKind.GAME_FAILED, Stage.PLAY, event.reason)
            }
            is SessionEvent.GameEnded -> gameEnded(event.summary)
            SessionEvent.GameQuit -> gameQuit()
            is SessionEvent.Frontmost -> frontmost(event.front)
            SessionEvent.Tick -> tick()
            is SessionEvent.Retry -> retry(event.stage, event.modelInstalled)
            is SessionEvent.Stop -> end(event.reason, abort = true)
        }
    }

    private fun indexReady(event: SessionEvent.IndexReady) {
        index = event.entries
        known = event.known
        log("index.ready", "entries" to event.entries.size, "known_songs" to event.known.size)
        setStage(Stage.LISTEN, StageStatus.DONE, event.entries.size.toString())
        launchIfDue()
        considerEverything()
    }

    /** The host starts once listening is in place, unless a share started the session or nothing could come of it. */
    private fun launchIfDue() {
        if (state.launched || state.byShare || !state.modelInstalled || state.shareNanos != null) return
        state = state.copy(launched = true)
        setStage(Stage.OPEN, StageStatus.RUNNING)
        effects += SessionEffect.Launch(state.path)
    }

    private fun askDriver(step: HostStep) {
        val stage = stageOf(step)
        if (state.shareNanos != null || state.stage(stage).status == StageStatus.DONE) return
        state = state.copy(driverStep = step, driverSinceNanos = now.nanos)
        setStage(stage, StageStatus.RUNNING)
        log("driver.step", "step" to step.name, "state" to "asked")
        effects += SessionEffect.Perform(step)
    }

    private fun markStep(step: HostStep, status: StageStatus, detail: String?) {
        val stage = stageOf(step)
        if (state.stage(stage).status != StageStatus.DONE) setStage(stage, status, detail)
    }

    private fun stepDone(event: SessionEvent.StepDone) {
        log("driver.step", "step" to event.step.name, "state" to "done", "detail" to event.detail)
        markStep(event.step, StageStatus.DONE, event.detail)
        if (state.driverStep == event.step) state = state.copy(driverStep = null, driverSinceNanos = null)
        // Reports from the driver mean it can act again, whoever did the step before.
        if (state.personFrom == event.step) state = state.copy(personFrom = null)
        nextStep(event.step)?.let(::askDriver)
    }

    private fun nextStep(step: HostStep): HostStep? = when (step) {
        HostStep.OPEN_RECOGNITION -> HostStep.READ_RESULT
        HostStep.READ_RESULT -> HostStep.TAP_DOWNLOAD
        HostStep.TAP_DOWNLOAD -> HostStep.SHARE_TO_ENSOMI
        HostStep.SHARE_TO_ENSOMI -> null
    }.takeIf { state.shareNanos == null }

    private fun handToPerson(step: HostStep, reason: String) {
        log("manual_intervention", "step" to step.name, "reason" to reason)
        if (state.shareNanos != null) return
        val first = state.personFrom?.takeIf { it < step } ?: step
        state = state.copy(personFrom = first, driverStep = null, driverSinceNanos = null)
        markStep(step, StageStatus.NEEDS_PERSON, reason)
        effects += SessionEffect.CancelDriver
    }

    private fun resultSeen(event: SessionEvent.ResultSeen) {
        log("driver.step", "step" to HostStep.READ_RESULT.name, "state" to "seen", "title" to event.title, "artist" to event.artist)
        learn(SongIdentity(title = event.title, artist = event.artist, durationMs = event.durationMs))
        markStep(HostStep.OPEN_RECOGNITION, StageStatus.DONE, null)
        markStep(HostStep.READ_RESULT, StageStatus.DONE, event.title)
        clearFailure(Stage.OPEN, Stage.RESULT)
        state = state.copy(personFrom = null, driverStep = null, driverSinceNanos = null)
        nextStep(HostStep.READ_RESULT)?.let(::askDriver)
        startAudio()
    }

    private fun shareReceived(event: SessionEvent.ShareReceived) {
        val song = event.song
        log(
            "share.received",
            "text" to event.text,
            "source" to event.source,
            "song_id" to song?.songId,
            "title" to song?.title,
            "artist" to song?.artist,
            "url" to song?.url,
            "short_link" to song?.shortLink,
            "other_kind" to song?.otherKind,
        )
        if (song == null || !song.namesSong) {
            fail(FailureKind.NOT_A_SONG, Stage.SHARE, song?.otherKind ?: event.text.take(TEXT_IN_FAILURE))
            return
        }
        clearFailure(Stage.OPEN, Stage.RESULT, Stage.DOWNLOAD, Stage.SHARE)
        learn(song.identity)
        for (stage in listOf(Stage.OPEN, Stage.RESULT, Stage.DOWNLOAD)) {
            if (state.stage(stage).status !in CLOSED) setStage(stage, StageStatus.SKIPPED, NOT_OBSERVED)
        }
        setStage(Stage.SHARE, StageStatus.DONE, song.title ?: song.songId?.toString())
        val driving = state.driverStep != null
        state = state.copy(shareNanos = state.shareNanos ?: now.nanos, personFrom = null, driverStep = null, driverSinceNanos = null)
        if (driving) effects += SessionEffect.CancelDriver
        if (song.songId == null && song.shortLink != null) effects += SessionEffect.LookUp(song.shortLink)
        startAudio()
    }

    private fun linkLookedUp(event: SessionEvent.LinkLookedUp) {
        log("share.link", "url" to event.url, "song_id" to event.songId, "outcome" to event.outcome)
        if (event.songId == null) return
        learn(SongIdentity(songId = event.songId))
        considerEverything()
    }

    private fun learn(identity: SongIdentity) {
        val song = state.song?.merged(identity) ?: identity
        if (song != state.song) log("song.identity", "song_id" to song.songId, "title" to song.title, "artist" to song.artist)
        state = state.copy(song = song)
    }

    private fun startAudio() {
        if (state.audio != null) return
        if (state.stage(Stage.AUDIO).status != StageStatus.FAILED) setStage(Stage.AUDIO, StageStatus.RUNNING)
        considerEverything()
    }

    /** What is on the device and what has been seen, judged again: the song or its identity has changed. */
    private fun considerEverything() {
        if (state.audio != null) return
        val seen = candidates.values.toList()
        for (entry in index) if (entry.path !in candidates) consider(entry, Evidence.INDEX)
        for (candidate in seen) reconsider(candidate)
        probeNext()
    }

    private fun reconsider(candidate: Candidate) {
        if (candidate.verdict in FINAL_FOR_THIS_FILE && candidate.match != MatchStrength.NONE) return
        consider(candidate.file, candidate.evidence)
    }

    private fun consider(file: FileFacts, evidence: Evidence) {
        if (state.audio != null) return
        val previous = candidates[file.path]
        val song = state.song
        val person = previous?.person == true
        val isNew = file.modifiedWallMs >= state.startedWallMs
        val match = when {
            person -> MatchStrength.PERSON
            song != null -> SongMatch.strength(song, file, song.songId?.let { known[it] }).let {
                // A share that gives only a link has no name to compare: a file that appears after it is all there is.
                if (it == MatchStrength.NONE && song.title == null && isNew) MatchStrength.NEW_FILE else it
            }
            else -> MatchStrength.NONE
        }
        // The index holds every song on the device: only those that could be this one are worth a line of the trace.
        if (evidence == Evidence.INDEX && previous == null && match == MatchStrength.NONE && !isNew) return
        val changed = previous != null &&
            (previous.file.sizeBytes != file.sizeBytes || previous.file.modifiedWallMs != file.modifiedWallMs)
        if (previous != null && !changed && previous.verdict in FINAL_FOR_THIS_FILE && previous.match == match) return
        val strongest = when {
            previous == null || changed -> evidence
            evidence.rank >= previous.evidence.rank -> evidence
            else -> previous.evidence
        }
        val merged = if (previous == null) file else file.copy(
            title = file.title ?: previous.file.title,
            artist = file.artist ?: previous.file.artist,
            durationMs = file.durationMs ?: previous.file.durationMs,
            uri = file.uri ?: previous.file.uri,
        )
        val lastChange = when {
            previous == null -> merged.modifiedWallMs
            changed -> now.wallMs
            else -> previous.lastChangeWallMs
        }
        val verdict = CandidateRules.judge(merged, strongest, song, match, state.startedWallMs, lastChange, now.wallMs)
        remember(previous, merged, strongest, lastChange, verdict, match, person, changed)
        when (verdict) {
            Verdict.PROTECTED -> refuseProtected(merged)
            Verdict.NO_SONG_YET, Verdict.NO_MATCH -> hold(merged)
            Verdict.PROBE -> probeNext()
            else -> Unit
        }
    }

    private fun remember(
        previous: Candidate?,
        file: FileFacts,
        evidence: Evidence,
        lastChange: Long,
        verdict: Verdict,
        match: MatchStrength,
        person: Boolean,
        changed: Boolean,
    ) {
        val recheck = when (verdict) {
            Verdict.NOT_QUIET -> now.nanos + quietNanos(evidence)
            Verdict.UNFINISHED, Verdict.TRUNCATED -> now.nanos + RECHECK_NANOS
            else -> null
        }
        candidates[file.path] = Candidate(file, evidence, lastChange, verdict, match, person, recheck)
        if (previous == null || changed || previous.verdict != verdict || previous.match != match) {
            logCandidate(file, evidence, verdict, match)
        }
    }

    private fun quietNanos(evidence: Evidence): Long =
        (if (evidence.finished) CandidateRules.QUIET_MS else CandidateRules.POLL_QUIET_MS) * 1_000_000

    private fun logCandidate(file: FileFacts, evidence: Evidence, verdict: Verdict, match: MatchStrength) {
        log(
            "audio.candidate",
            "path" to file.path,
            "size" to file.sizeBytes,
            "modified_wall_ms" to file.modifiedWallMs,
            "evidence" to evidence.name,
            "match" to match.name,
            "verdict" to verdict.name,
        )
    }

    private fun refuseProtected(file: FileFacts) {
        fail(FailureKind.PROTECTED_TRACK, Stage.AUDIO, file.name)
    }

    private fun hold(file: FileFacts) {
        if (!CandidateRules.isAudio(file.name) || state.held.any { it.path == file.path }) return
        state = state.copy(held = state.held + file)
    }

    private fun fileGone(path: String) {
        if (path !in candidates) return
        log("audio.gone", "path" to path)
        candidates.remove(path)
        state = state.copy(held = state.held.filterNot { it.path == path })
        if (probing == path) probing = null
    }

    private fun useFile(path: String) {
        val file = state.held.firstOrNull { it.path == path } ?: candidates[path]?.file ?: return
        log("manual_intervention", "step" to "USE_FILE", "reason" to "person", "path" to path)
        val previous = candidates[path]
        candidates[path] = Candidate(
            file,
            previous?.evidence ?: Evidence.INDEX,
            previous?.lastChangeWallMs ?: file.modifiedWallMs,
            Verdict.NO_MATCH,
            MatchStrength.PERSON,
            person = true,
            recheckNanos = null,
        )
        if (state.stage(Stage.AUDIO).status == StageStatus.WAITING) setStage(Stage.AUDIO, StageStatus.RUNNING)
        consider(file, previous?.evidence ?: Evidence.INDEX)
    }

    /** One decoder at a time: the surest candidate first. */
    private fun probeNext() {
        if (probing != null || state.audio != null) return
        val next = candidates.values.filter { it.verdict == Verdict.PROBE }.maxByOrNull { it.match.ordinal } ?: return
        probing = next.file.path
        effects += SessionEffect.Probe(next.file)
    }

    private fun probed(event: SessionEvent.Probed) {
        val probe = event.probe
        val file = event.file
        if (probing == file.path) probing = null
        log(
            "decode",
            "path" to file.path,
            "decoded" to probe.decoded,
            "decoded_ms" to probe.decodedMs,
            "declared_ms" to probe.declaredMs,
            "sample_rate" to probe.sampleRate,
            "mime" to probe.mime,
            "codec" to probe.codec,
            "took_ms" to probe.tookMs,
            "failure" to probe.failure,
        )
        if (state.audio != null) return
        val candidate = candidates[file.path]
        if (candidate == null) {
            probeNext()
            return
        }
        val verdict = CandidateRules.judgeDecoded(file, probe, state.song?.durationMs, candidate.person)
        val recheck = when (verdict) {
            Verdict.TRUNCATED, Verdict.CHANGED_WHILE_PROBED -> now.nanos + RECHECK_NANOS
            else -> null
        }
        candidates[file.path] = Candidate(
            candidate.file, candidate.evidence, candidate.lastChangeWallMs, verdict, candidate.match, candidate.person, recheck,
        )
        logCandidate(file, candidate.evidence, verdict, candidate.match)
        if (verdict == Verdict.TRUNCATED || verdict == Verdict.DURATION_DIFFERS) hold(file)
        if (verdict == Verdict.READY) audioReady(candidate, probe) else probeNext()
    }

    private fun audioReady(candidate: Candidate, probe: ProbeFacts) {
        val file = candidate.file
        val song = state.song
        val audio = ReadyAudio(
            file = file,
            decodedMs = probe.decodedMs,
            sampleRate = probe.sampleRate,
            sha256 = probe.sha256,
            title = song?.title ?: file.title ?: file.name.substringBeforeLast('.'),
            artist = song?.artist ?: file.artist,
        )
        log(
            "audio.ready",
            "path" to file.path,
            "match" to candidate.match.name,
            "evidence" to candidate.evidence.name,
            "decoded_ms" to probe.decodedMs,
            "sha256" to probe.sha256,
        )
        clearFailure(Stage.AUDIO)
        state = state.copy(audio = audio, held = emptyList())
        setStage(Stage.AUDIO, StageStatus.DONE, file.name)
        song?.songId?.let { effects += SessionEffect.Remember(it, file.path) }
        startChart(audio)
    }

    private fun startChart(audio: ReadyAudio) {
        if (!state.modelInstalled) {
            fail(FailureKind.MODEL_MISSING, Stage.CHART, null)
            return
        }
        setStage(Stage.CHART, StageStatus.RUNNING)
        effects += SessionEffect.MakeChart(audio)
    }

    private fun chartReady(event: SessionEvent.ChartReady) {
        log(
            "chart.ready",
            "song_id" to event.chart.songId,
            "notes" to event.chart.noteCount,
            "stored" to event.chart.stored,
            "seconds" to event.seconds,
        )
        clearFailure(Stage.CHART)
        state = state.copy(chart = event.chart)
        setStage(Stage.CHART, StageStatus.DONE, if (event.chart.stored) STORED else event.chart.noteCount.toString())
        startSync()
    }

    private fun startSync() {
        val audio = state.audio ?: return
        state = state.copy(
            referenceReady = false,
            listenedMs = 0,
            syncState = null,
            syncDetail = null,
            syncConfidence = null,
            silentSeconds = 0,
        )
        setStage(Stage.SYNC, StageStatus.RUNNING)
        effects += SessionEffect.BuildReference(audio)
    }

    private fun captureIfWanted() {
        val wanted = state.referenceReady && state.stage(Stage.SYNC).status == StageStatus.RUNNING && state.foreground
        if (wanted && !state.capturing) {
            state = state.copy(capturing = true)
            effects += SessionEffect.StartCapture
        }
    }

    private fun stopCapture() {
        if (!state.capturing) return
        state = state.copy(capturing = false)
        effects += SessionEffect.StopCapture
    }

    private fun captureLevel(event: SessionEvent.CaptureLevel) {
        val silent = if (event.rmsDb < limits.silentDb) state.silentSeconds + 1 else 0
        log("capture.level", "rms_db" to event.rmsDb, "peak_db" to event.peakDb)
        state = state.copy(rmsDb = event.rmsDb, silentSeconds = silent)
    }

    private fun syncUpdate(reading: SyncReading) {
        if (state.stage(Stage.SYNC).status != StageStatus.RUNNING) return
        val name = reading.state.name
        if (name != state.syncState || reading.detail != state.syncDetail || reading.finalLock) {
            log(
                "sync.state",
                "state" to name,
                "final_lock" to reading.finalLock,
                "reference_ms" to reading.referenceMs,
                "capture_ns" to reading.captureNanos,
                "confidence" to reading.confidence,
                "detail" to reading.detail,
            )
            setStage(Stage.SYNC, StageStatus.RUNNING, listOfNotNull(name, reading.detail).joinToString(" · "))
        }
        state = state.copy(syncState = name, syncDetail = reading.detail, syncConfidence = reading.confidence)
        if (reading.finalLock) {
            val referenceMs = reading.referenceMs
            val captureNanos = reading.captureNanos
            if (referenceMs == null || captureNanos == null) return
            val lock = LockAnchor(referenceMs, captureNanos)
            clearFailure(Stage.SYNC)
            state = state.copy(lock = lock)
            setStage(Stage.SYNC, StageStatus.DONE, name)
            stopCapture()
            join(lock)
        } else if (reading.state == SyncState.FAILED) {
            stopCapture()
            fail(FailureKind.SYNC_FAILED, Stage.SYNC, reading.detail)
        }
    }

    private fun join(lock: LockAnchor) {
        setStage(Stage.PLAY, StageStatus.RUNNING)
        effects += SessionEffect.Join(lock)
    }

    private fun gameEnded(summary: GameSummary) {
        log(
            "game.end",
            "accuracy" to summary.accuracy,
            "perfect" to summary.perfect,
            "good" to summary.good,
            "miss" to summary.miss,
            "max_combo" to summary.maxCombo,
            "mean_offset_ms" to summary.meanOffsetMs,
        )
        state = state.copy(result = summary)
        setStage(Stage.PLAY, StageStatus.DONE)
        end("finished", abort = false)
    }

    private fun gameQuit() {
        log("game.quit")
        if (!state.running) return
        state = state.copy(joined = null)
        fail(FailureKind.GAME_QUIT, Stage.PLAY, null)
    }

    private fun frontmost(front: Boolean) {
        if (front == state.foreground) return
        log("foreground", "front" to front)
        state = state.copy(foreground = front)
        if (front) {
            captureIfWanted()
            // Events are not delivered to a frozen process: what happened meanwhile is looked for.
            if (state.audio == null && state.stage(Stage.LISTEN).status == StageStatus.DONE) effects += SessionEffect.BuildIndex
        } else {
            stopCapture()
        }
    }

    private fun tick() {
        val elapsed = (now.nanos - lastTickNanos) / 1_000_000
        lastTickNanos = now.nanos
        val step = state.driverStep
        val since = state.driverSinceNanos
        if (step != null && since != null && millisSince(since) > driverLimit(step)) handToPerson(step, "driver_timeout")
        if (state.audio == null && state.failure == null) {
            val share = state.shareNanos
            if (share == null && state.launched && millisSince(state.startedNanos) > limits.hostMs) {
                fail(FailureKind.NO_SHARE, Stage.SHARE, null)
            } else if (share != null && millisSince(share) > limits.fileMs) {
                fail(FailureKind.NO_FILE, Stage.AUDIO, null)
            }
        }
        if (state.capturing) {
            val listened = state.listenedMs + elapsed
            state = state.copy(listenedMs = listened)
            if (listened > limits.syncMs) {
                stopCapture()
                fail(FailureKind.SYNC_NEVER_LOCKED, Stage.SYNC, state.syncState)
            }
        }
        for (candidate in candidates.values.toList()) {
            val due = candidate.recheckNanos ?: continue
            if (now.nanos >= due && state.audio == null) {
                val path = candidate.file.path
                candidates[path] = Candidate(
                    candidate.file, candidate.evidence, candidate.lastChangeWallMs, candidate.verdict, candidate.match, candidate.person, null,
                )
                effects += SessionEffect.Refresh(path)
            }
        }
    }

    private fun driverLimit(step: HostStep): Long =
        if (step == HostStep.READ_RESULT) limits.readResultMs else limits.driverStepMs

    private fun millisSince(nanos: Long): Long = (now.nanos - nanos) / 1_000_000

    private fun retry(stage: Stage, modelInstalled: Boolean) {
        log("retry", "stage" to stage.name, "model_installed" to modelInstalled)
        state = state.copy(failure = null, modelInstalled = modelInstalled)
        if (!modelInstalled) {
            fail(FailureKind.MODEL_MISSING, Stage.CHART, null)
            return
        }
        when (stage) {
            Stage.LISTEN, Stage.OPEN, Stage.RESULT, Stage.DOWNLOAD, Stage.SHARE -> {
                stopWork()
                resetFrom(Stage.OPEN)
                candidates.clear()
                state = state.copy(
                    song = null,
                    audio = null,
                    chart = null,
                    lock = null,
                    joined = null,
                    held = emptyList(),
                    shareNanos = null,
                    personFrom = null,
                    launched = false,
                    byShare = false,
                )
                probing = null
                effects += SessionEffect.BuildIndex
                launchIfDue()
            }
            Stage.AUDIO -> {
                stopWork()
                resetFrom(Stage.AUDIO)
                candidates.clear()
                probing = null
                state = state.copy(audio = null, chart = null, lock = null, joined = null, held = emptyList())
                if (state.shareNanos != null) state = state.copy(shareNanos = now.nanos)
                setStage(Stage.AUDIO, StageStatus.RUNNING)
                effects += SessionEffect.BuildIndex
            }
            Stage.CHART -> {
                val audio = state.audio
                if (audio == null) {
                    launchIfDue()
                } else {
                    stopWork()
                    resetFrom(Stage.CHART)
                    state = state.copy(chart = null, lock = null, joined = null)
                    startChart(audio)
                }
            }
            Stage.SYNC -> {
                stopCapture()
                resetFrom(Stage.SYNC)
                state = state.copy(lock = null, joined = null)
                startSync()
            }
            Stage.PLAY -> {
                val lock = state.lock
                resetFrom(Stage.PLAY)
                state = state.copy(joined = null)
                if (lock != null) join(lock) else startSync()
            }
        }
    }

    /** Whatever is under way stops; the stages it belonged to are reset by the caller. */
    private fun stopWork() {
        if (state.stage(Stage.CHART).status == StageStatus.RUNNING) effects += SessionEffect.StopChart
        stopCapture()
        if (state.driverStep != null) {
            state = state.copy(driverStep = null, driverSinceNanos = null)
            effects += SessionEffect.CancelDriver
        }
    }

    private fun resetFrom(first: Stage) {
        state = state.copy(stages = state.stages.map { if (it.stage >= first) StageView(it.stage) else it })
    }

    private fun end(reason: String, abort: Boolean) {
        if (abort) log("abort", "reason" to reason, "stage" to currentStage()?.name)
        log("session.end", "reason" to reason)
        if (state.stage(Stage.CHART).status == StageStatus.RUNNING) effects += SessionEffect.StopChart
        stopCapture()
        if (state.driverStep != null) effects += SessionEffect.CancelDriver
        effects += SessionEffect.StopListening
        state = state.copy(ended = reason, driverStep = null, driverSinceNanos = null, personFrom = null)
    }

    private fun currentStage(): Stage? = state.stages.lastOrNull { it.status !in setOf(StageStatus.WAITING) }?.stage

    private fun fail(kind: FailureKind, stage: Stage, detail: String?) {
        log("failure", "kind" to kind.name, "stage" to stage.name, "detail" to detail)
        state = state.copy(failure = Failure(kind, stage, detail))
        setStage(stage, StageStatus.FAILED, detail)
    }

    private fun clearFailure(vararg stages: Stage) {
        if (state.failure?.stage in stages) state = state.copy(failure = null)
    }

    private fun setStage(stage: Stage, status: StageStatus, detail: String? = null) {
        val current = state.stage(stage)
        val active = status == StageStatus.RUNNING || status == StageStatus.NEEDS_PERSON
        val closed = status in CLOSED
        val next = current.copy(
            status = status,
            startedNanos = current.startedNanos ?: now.nanos.takeIf { active || closed },
            endedNanos = if (closed) current.endedNanos.takeIf { status == current.status } ?: now.nanos else null,
            detail = detail ?: current.detail.takeIf { status == current.status },
        )
        if (next == current) return
        if (status != current.status) log("stage", "stage" to stage.name, "status" to status.name, "detail" to detail)
        state = state.copy(stages = state.stages.map { if (it.stage == stage) next else it })
    }

    private fun stageOf(step: HostStep): Stage = Stage.entries.first { it.hostStep == step }

    /** What the person has to do now, worked out from the facts rather than kept in step with them. */
    private fun asks(): List<Ask> = buildList {
        if (!state.running) return@buildList
        val from = state.personFrom
        if (from != null && state.shareNanos == null && state.audio == null) {
            if (from <= HostStep.READ_RESULT) add(Ask.OPEN_RECOGNITION)
            if (from <= HostStep.TAP_DOWNLOAD) add(Ask.TAP_DOWNLOAD)
            add(Ask.SHARE_TO_ENSOMI)
        }
        val waitingForFile = state.audio == null && state.shareNanos != null
        if (waitingForFile && state.failure?.kind != FailureKind.PROTECTED_TRACK) add(Ask.WAIT_FOR_DOWNLOAD)
        if (state.stage(Stage.SYNC).status == StageStatus.RUNNING && !state.foreground) add(Ask.RETURN_TO_ENSOMI)
        if (state.capturing && state.silentSeconds >= limits.silentAfterSeconds) add(Ask.MICROPHONE_SILENT)
    }

    private fun log(event: String, vararg fields: Pair<String, Any?>) = trace(event, mapOf(*fields))

    /** A session the process died with: it carries on from what it knew, or says it cannot. */
    private fun restore(): List<SessionEffect> = collect {
        log("session.restored", "stage" to currentStage()?.name, "age_ms" to millisSince(state.startedNanos))
        val inGame = state.stage(Stage.PLAY).status == StageStatus.RUNNING
        // A session from before a reboot has a start time ahead of this clock.
        val age = millisSince(state.startedNanos)
        val tooOld = age < 0 || age > limits.restoreMs
        if (inGame || tooOld) {
            val detail = if (inGame) "game" else "too_old"
            fail(FailureKind.SESSION_LOST, Stage.PLAY.takeIf { inGame } ?: Stage.LISTEN, detail)
            end("lost", abort = true)
            return@collect
        }
        state = state.copy(capturing = false, referenceReady = false, foreground = false, driverStep = null, driverSinceNanos = null)
        lastTickNanos = now.nanos
        setStage(Stage.LISTEN, StageStatus.RUNNING)
        effects += SessionEffect.StartListening
        effects += SessionEffect.BuildIndex
        val audio = state.audio
        when {
            audio == null -> Unit
            state.stage(Stage.CHART).status == StageStatus.RUNNING -> startChart(audio)
            state.stage(Stage.SYNC).status == StageStatus.RUNNING -> startSync()
        }
    }

    companion object {
        private const val RECHECK_NANOS = 2_000_000_000L
        private const val TEXT_IN_FAILURE = 120
        private const val NOT_OBSERVED = "not observed"
        private const val STORED = "stored"
        private val CLOSED = setOf(StageStatus.DONE, StageStatus.SKIPPED, StageStatus.FAILED)

        /** Verdicts only a change to the file can alter. */
        private val FINAL_FOR_THIS_FILE = setOf(
            Verdict.NOT_AUDIO, Verdict.TEMPORARY, Verdict.UNDECODABLE, Verdict.DURATION_DIFFERS, Verdict.TRUNCATED,
        )

        fun start(
            cid: String,
            path: LaunchPath,
            byShare: Boolean,
            modelInstalled: Boolean,
            time: SessionNow,
            limits: SessionLimits = SessionLimits(),
            trace: (String, Map<String, Any?>) -> Unit,
        ): Pair<SessionMachine, List<SessionEffect>> {
            val state = SessionState(cid, path, time.nanos, time.wallMs, byShare, modelInstalled)
            val machine = SessionMachine(state, trace, limits)
            return machine to machine.begin()
        }

        fun restored(
            saved: SessionState,
            time: SessionNow,
            limits: SessionLimits = SessionLimits(),
            trace: (String, Map<String, Any?>) -> Unit,
        ): Pair<SessionMachine, List<SessionEffect>> {
            val machine = SessionMachine(saved, trace, limits)
            machine.now = time
            return machine to machine.restore()
        }
    }
}
