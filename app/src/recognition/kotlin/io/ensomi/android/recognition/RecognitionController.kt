package io.ensomi.android.recognition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import io.ensomi.android.R
import io.ensomi.android.AppStores
import io.ensomi.android.audio.DecodedSong
import io.ensomi.android.model.ModelState
import io.ensomi.android.model.ready
import io.ensomi.android.model.awaitModelReady
import io.ensomi.android.play.AmbientGame
import io.ensomi.android.play.PlayResult
import io.ensomi.runtime.RuntimeWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one recognition session at a time for the process: feeds [SessionMachine] what the device reports and carries out
 * what it asks for. Everything that touches the machine runs on one coroutine, in the order it was posted, so listeners,
 * screens may call in from any thread.
 */
class RecognitionController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val storage = SessionStorage(appContext)
    private val probe = AudioProbe(appContext)

    private val mutableSession = MutableStateFlow<SessionState?>(null)
    private val mutableGame = MutableStateFlow<AmbientGame?>(null)
    private val mutableOptions = MutableStateFlow(storage.readOptions())
    private val mutableOpens = MutableStateFlow(false)
    private val mutableShareNotice = MutableStateFlow<ShareDecision?>(null)
    val shareNotice: StateFlow<ShareDecision?> = mutableShareNotice.asStateFlow()

    /** The session, also after it ended, until the next one. */
    val session: StateFlow<SessionState?> = mutableSession.asStateFlow()

    /** The game joined, while it lasts. */
    val game: StateFlow<AmbientGame?> = mutableGame.asStateFlow()
    val options: StateFlow<SessionOptions> = mutableOptions.asStateFlow()

    /** Something wants the session's screen in front: a share, a session picked up, a game to join. */
    val opens: StateFlow<Boolean> = mutableOpens.asStateFlow()

    /** The path of the current session's trace on the device. */
    val tracePath: String? get() = mutableSession.value?.let { storage.traceFile(it.cid).path }

    val replayAvailable: Boolean get() = storage.replayFile.isFile

    // From here to the functions below, on the one coroutine.
    private var machine: SessionMachine? = null
    private var trace: SessionTrace? = null
    private var lastSaved: SessionState? = null
    private var frontmost = false
    private var guideOpened: SessionNow? = storage.readGuideTime()
    private var pendingRestore: SessionState? = null
    private var aligner: AmbientAligner? = null
    private var syncRun: SyncRun? = null
    private var syncGeneration = 0
    private var gameGeneration = 0
    private val referenceWorker = RuntimeWorker()
    private data class ProbedAudio(val path: String, val sha256: String?, val song: DecodedSong)
    private var decoded: ProbedAudio? = null
    /** File work and the ticker belong to the session, not to the process. */
    private var workScope: CoroutineScope? = null

    @Volatile
    private var listening: MediaListening? = null

    private val stores: AppStores by lazy { AppStores.get(appContext) }
    private val charts by lazy { SessionCharts(appContext, stores, scope) }

    init {
        scope.launch {
            for (action in actions) {
                try {
                    action()
                } catch (failure: Exception) {
                    Log.e(TAG, "The session's step failed", failure)
                    trace?.log("controller.failure", mapOf("message" to failure.toString()))
                }
            }
        }
        post { restore() }
    }

    fun showGuide() = post {
        if (machine?.state?.running == true) return@post
        mutableShareNotice.value = mutableShareNotice.value?.takeIf { it == ShareDecision.RESTORE }
        val saved = pendingRestore
        pendingRestore = null
        if (saved != null && canResumeSnapshot(saved, now(), limits())) {
            resume(saved)
            return@post
        }
        storage.clearSnapshot()
        guideOpened = now()
        storage.writeGuideTime(guideOpened!!)
        machine = null
        mutableGame.value = null
        mutableSession.value = null
    }

    fun stop() = post {
        gameGeneration++
        feed(SessionEvent.Stop("person"))
        mutableGame.value = null
    }

    fun retry(stage: Stage) = post { gameGeneration++; feed(SessionEvent.Retry(stage, modelInstalled())) }

    fun useFile(path: String) = post { feed(SessionEvent.UseFile(path)) }

    /** A share reached the app, or the person pasted a link. Starts a session when there is none for this song. */
    fun share(text: String, source: String?) {
        val received = now()
        mutableOpens.value = true
        post {
            val shared = ShareParser.parse(text)
            if (shared?.namesSong != true && machine?.state?.running == true) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, R.string.guide_not_song, Toast.LENGTH_SHORT).show()
                }
                return@post
            }
            val decision = shareDecision(shared, guideState())
            if (decision != ShareDecision.START) {
                if (machine?.state?.running == true) feed(SessionEvent.Stop("share_needs_setup"))
                machine = null
                mutableSession.value = null
                mutableGame.value = null
                mutableShareNotice.value = decision
                return@post
            }
            pendingRestore = null
            mutableShareNotice.value = null
            if (startsNewSession(machine?.state, shared?.identity)) begin(received)
            else if (machine?.state?.failure?.kind in setOf(FailureKind.SYNC_FAILED, FailureKind.SYNC_NEVER_LOCKED)) {
                gameGeneration++
                feed(SessionEvent.Retry(Stage.AUDIO, modelInstalled()))
            }
            feed(SessionEvent.ShareReceived(text, shared, source), received)
        }
    }

    private suspend fun guideState() = GuideState(
        modelInstalled(),
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        appContext.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED,
    )

    /** Whether Ensomi's screen for the session is in front. */
    fun setFrontmost(front: Boolean) = post {
        frontmost = front
        feed(SessionEvent.Frontmost(front))
    }

    fun opened() {
        mutableOpens.value = false
    }

    fun updateOptions(change: (SessionOptions) -> SessionOptions) {
        mutableOptions.update(change)
        val options = mutableOptions.value
        post { storage.writeOptions(options) }
    }

    fun gameFinished(result: PlayResult) {
        mutableGame.value = null
        val summary = GameSummary(result.accuracy, result.perfect, result.good, result.miss, result.maxCombo, result.meanOffsetMs)
        post { feed(SessionEvent.GameEnded(summary)) }
    }

    fun gameQuit() {
        mutableGame.value = null
        post { feed(SessionEvent.GameQuit) }
    }

    private fun post(action: suspend () -> Unit) {
        actions.trySend(action)
    }

    private fun now() = SessionNow(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())

    private fun limits() = SessionLimits(
        syncMs = mutableOptions.value.syncLimitMs,
        audioWaitMs = mutableOptions.value.audioWaitMs.coerceAtLeast(1),
    )

    /** Ignore results already queued when their session stopped or was replaced. */
    private fun postFor(current: SessionMachine, action: suspend () -> Unit) = post {
        if (machine === current && current.state.running) action()
    }

    private suspend fun modelInstalled(): Boolean {
        // At a cold start the store is still looking at what is installed.
        return awaitModelReady(stores.model.state, timeoutMillis = MODEL_CHECK_MS)
    }

    private suspend fun begin(sharedAt: SessionNow) {
        gameGeneration++
        mutableGame.value = null
        if (machine?.state?.running == true) feed(SessionEvent.Stop("replaced"))
        closeSync()
        trace?.close()
        val time = now()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(time.wallMs))
        val cid = "$stamp-${UUID.randomUUID().toString().take(CID_SUFFIX)}"
        storage.pruneTraces(cid)
        val log = SessionTrace(storage.traceFile(cid), cid, SystemClock::elapsedRealtimeNanos)
        trace = log
        val installed = modelInstalled()
        logDevice(log)
        val fileStart = sessionBeginning(guideOpened, sharedAt).wallMs
        clearGuideTime()
        val (started, effects) = SessionMachine.start(cid, installed, time, limits(), fileStartWallMs = fileStart) { event, fields ->
            log.log(event, fields)
        }
        machine = started
        published(started)
        effects.forEach { carryOut(it) }
        feed(SessionEvent.Frontmost(frontmost))
    }

    /** What the trace's reader needs to know about the device and this run, once. */
    private fun logDevice(log: SessionTrace) {
        val packages = appContext.packageManager
        val host = runCatching { packages.getPackageInfo(HostLauncher.PACKAGE, PackageManager.PackageInfoFlags.of(0)) }.getOrNull()
        val options = mutableOptions.value
        log.log(
            "session.device",
            mapOf(
                "model" to Build.MODEL,
                "device" to Build.DEVICE,
                "sdk" to Build.VERSION.SDK_INT,
                "build" to Build.DISPLAY,
                "host_version" to host?.versionName,
                "host_version_code" to host?.longVersionCode,
                "capture" to options.capture.name,
                "stars" to options.stars,
                "ambient_offset_ms" to options.ambientOffsetMs,
                "audio_wait_ms" to limits().audioWaitMs,
                // The trace's clock and the play path's, read together, for converting between them.
                "elapsed_ns_now" to SystemClock.elapsedRealtimeNanos(),
                "mono_ns_now" to System.nanoTime(),
            ),
        )
    }

    private fun restore() {
        val saved = storage.readSnapshot() ?: return
        if (canResumeSnapshot(saved, now(), limits())) pendingRestore = saved
        else { storage.clearSnapshot(); clearGuideTime() }
        mutableShareNotice.value = ShareDecision.RESTORE
        // A launch is not consent to open a session or start its microphone.
    }

    private suspend fun resume(saved: SessionState) {
        val guide = guideState()
        if (!guide.ready) {
            mutableShareNotice.value = if (!guide.modelReady) ShareDecision.MODEL else ShareDecision.PERMISSIONS
            return
        }
        mutableShareNotice.value = null
        clearGuideTime()
        val log = SessionTrace(storage.traceFile(saved.cid), saved.cid, SystemClock::elapsedRealtimeNanos)
        trace = log
        val (restored, effects) = SessionMachine.restored(saved, now(), limits()) { event, fields -> log.log(event, fields) }
        machine = restored
        published(restored)
        effects.forEach { carryOut(it) }
    }

    private suspend fun feed(event: SessionEvent, time: SessionNow = now()) {
        val current = machine ?: return
        val effects = current.on(event, time)
        published(current)
        effects.forEach { carryOut(it) }
    }

    private fun clearGuideTime() {
        guideOpened = null
        storage.clearGuideTime()
    }

    private fun published(current: SessionMachine) {
        val state = current.state
        mutableSession.value = state
        val saved = state.persistable()
        if (saved == lastSaved) return
        lastSaved = saved
        if (state.running) storage.writeSnapshot(state) else { storage.clearSnapshot(); clearGuideTime() }
    }

    private suspend fun carryOut(effect: SessionEffect) {
        val current = machine ?: return
        when (effect) {
            SessionEffect.StartListening -> startListening()
            SessionEffect.BuildIndex -> buildIndex()
            is SessionEffect.Refresh -> workScope?.launch {
                val found = listening?.look(effect.path)
                postFor(current) { feed(if (found != null) SessionEvent.FileSeen(found, Evidence.POLL) else SessionEvent.FileGone(effect.path)) }
            }
            is SessionEffect.Probe -> workScope?.launch {
                val (facts, song) = try {
                    probe.probe(effect.file) { !isActive }
                } catch (_: OutOfMemoryError) {
                    postFor(current) { feed(SessionEvent.AudioTooLarge) }
                    return@launch
                }
                postFor(current) {
                    if (song != null) decoded = ProbedAudio(effect.file.path, facts.sha256, song)
                    feed(SessionEvent.Probed(effect.file, facts))
                }
            }
            is SessionEffect.LookUp -> workScope?.launch {
                val lookup = ShortLinks.lookUp(effect.url)
                postFor(current) { feed(SessionEvent.LinkLookedUp(effect.url, lookup.songId, lookup.outcome)) }
            }
            SessionEffect.ResetSync -> { closeSync(); decoded = null; mutableGame.value = null }
            is SessionEffect.Forget -> storage.forget(effect.songId)
            is SessionEffect.Remember -> storage.remember(effect.songId, effect.path)
            is SessionEffect.MakeChart -> charts.make(
                effect.audio, mutableOptions.value.stars,
                decoded?.takeIf { it.path == effect.audio.file.path && it.sha256 == effect.audio.sha256 }?.song,
            ) { event -> postFor(current) { feed(event) } }
            SessionEffect.StopChart -> charts.stop()
            is SessionEffect.BuildReference -> buildReference(effect.audio)
            SessionEffect.StartCapture -> startCapture()
            SessionEffect.StopCapture -> stopCapture()
            is SessionEffect.Join -> join(effect.lock)
            SessionEffect.StopListening -> {
                workScope?.cancel()
                workScope = null
                listening?.stop()
                listening = null
                charts.stop()
                closeSync()
                decoded = null
            }
        }
    }

    private fun startListening() {
        val current = machine ?: return
        val state = current.state
        workScope?.cancel()
        val work = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
        workScope = work
        work.launch {
            while (isActive) {
                delay(TICK_MS)
                postFor(current) { feed(SessionEvent.Tick) }
            }
        }
        listening?.stop()
        val listener = object : FileListener {
            override fun seen(file: FileFacts, evidence: Evidence) = postFor(current) { feed(SessionEvent.FileSeen(file, evidence)) }

            override fun gone(path: String) = postFor(current) { feed(SessionEvent.FileGone(path)) }
        }
        listening = MediaListening(appContext, work, state.startedWallMs, listener) { event, fields ->
            trace?.log(event, fields)
        }.also { it.start() }
    }

    private fun buildIndex() {
        val current = machine ?: return
        val source = listening ?: return
        workScope?.launch {
            val entries = source.scan()
            val known = storage.knownSongs()
            postFor(current) { feed(SessionEvent.IndexReady(entries, known)) }
        }
    }

    private fun buildReference(audio: ReadyAudio) {
        closeSync()
        val generation = syncGeneration
        val kept = decoded?.takeIf { it.path == audio.file.path && it.sha256 == audio.sha256 }?.song
        scope.launch {
            val started = SystemClock.elapsedRealtimeNanos()
            try {
                val song = kept ?: probe.decode(audio.file)
                val opened = referenceWorker.run { RuntimeSync.port.open(song.samples, song.sampleRate) }
                val seconds = (SystemClock.elapsedRealtimeNanos() - started) / 1e9
                post {
                    if (generation != syncGeneration) {
                        opened.close()
                    } else {
                        aligner = opened
                        decoded = null
                        feed(SessionEvent.ReferenceReady(opened.backend, seconds))
                    }
                }
            } catch (_: OutOfMemoryError) {
                post { if (generation == syncGeneration) feed(SessionEvent.AudioTooLarge) }
            } catch (failure: Exception) {
                Log.w(TAG, "The reference could not be built", failure)
                val reason = failure.message ?: failure.javaClass.simpleName
                post { if (generation == syncGeneration) feed(SessionEvent.ReferenceFailed(reason)) }
            }
        }
    }

    private suspend fun startCapture() {
        val current = aligner ?: return
        val options = mutableOptions.value
        val capture = when (options.capture) {
            CaptureChoice.MICROPHONE -> MicCapture(appContext)
            CaptureChoice.REPLAY -> WavReplayCapture(storage.replayFile, options.replayStartMs)
        }
        if (options.capture == CaptureChoice.REPLAY && !storage.replayFile.isFile) {
            feed(SessionEvent.CaptureFailed("No ${storage.replayFile.path}"))
            return
        }
        val run = SyncRun(current, capture)
        syncRun = run
        run.start()
        feed(SessionEvent.CaptureStarted(capture.description))
    }

    private fun stopCapture() {
        syncRun?.stop()
        syncRun = null
    }

    private fun closeSync() {
        syncGeneration++
        stopCapture()
        aligner?.close()
        aligner = null
    }

    private fun join(lock: LockAnchor) {
        closeSync()
        val current = machine ?: return
        val state = current.state
        val generation = gameGeneration
        val audio = state.audio ?: return
        val chart = state.chart ?: return
        val options = mutableOptions.value
        mutableOpens.value = true
        scope.launch {
            val planned = try {
                plan(lock, chart, audio, options)
            } catch (failure: Exception) {
                Log.w(TAG, "The game could not be joined", failure)
                SessionEvent.GameFailed(failure.message ?: failure.javaClass.simpleName) to null
            }
            postFor(current) {
                if (generation == gameGeneration) {
                    mutableGame.value = planned.second
                    feed(planned.first)
                }
            }
        }
    }

    /** The game from where the music has got to now: the lock's time is the anchor, this is only when to come in. */
    private fun plan(lock: LockAnchor, facts: ChartFacts, audio: ReadyAudio, options: SessionOptions): Pair<SessionEvent, AmbientGame?> {
        val chart = charts.load(facts, audio) ?: return SessionEvent.GameFailed("The chart could not be read.") to null
        val clock = AmbientPlayClock(lock)
        val scrollMs = stores.settings.settings.value.scrollTimeMs
        val monoNow = System.nanoTime()
        val heard = clock.heardMs(monoNow)
        val from = heard + maxOf(scrollMs, JOIN_LEAD_MS)
        val plan = JoinPoint.plan(chart, from)
        trace?.log(
            "game.plan",
            mapOf(
                "reference_ms" to lock.referenceMs,
                "capture_ns" to lock.captureNanos,
                "mono_ns_now" to monoNow,
                "lock_age_ms" to (monoNow - lock.captureNanos) / 1e6,
                "heard_ms" to heard,
                "from_ms" to from,
                "join_ms" to plan?.joinMs,
                "scroll_ms" to scrollMs,
                "offset_ms" to options.ambientOffsetMs,
                "song_ms" to chart.durationMs,
            ),
        )
        if (plan == null) return SessionEvent.JoinRefused("heard ${heard.toLong()} ms of ${chart.durationMs} ms") to null
        val game = AmbientGame(plan.chart, clock, plan.joinMs, options.ambientOffsetMs.toDouble(), options.autoplay)
        return SessionEvent.Joined(JoinFacts(plan.joinMs, heard, plan.chart.notes.size, plan.notesSkipped)) to game
    }

    /**
     * One run of capture into the aligner, pushed from the capture thread, as the runtime asks: `AudioRecord`'s own
     * buffer holds the few milliseconds a search takes. Pushing stops at the first final lock.
     */
    private inner class SyncRun(private val target: AmbientAligner, private val capture: CapturePort) : CaptureSink {
        private val runTrace = trace
        private var last: SyncReading? = null
        private var done = false

        fun start() {
            target.restart()
            capture.start(this)
        }

        fun stop() = capture.stop()

        override fun block(samples: FloatArray, count: Int, sampleRate: Int, captureNanos: Long) {
            if (done) return
            try {
                target.push(samples, count, sampleRate, captureNanos)
                val reading = target.read()
                if (reading == last) return
                last = reading
                if (reading.finalLock) {
                    done = true
                    logTruth(reading)
                }
                post { if (syncRun === this@SyncRun) feed(SessionEvent.SyncUpdate(reading)) }
            } catch (failure: Exception) {
                Log.w(TAG, "The aligner failed", failure)
                done = true
                val reading = SyncReading(SyncState.FAILED, detail = failure.message ?: failure.javaClass.simpleName)
                post { if (syncRun === this@SyncRun) feed(SessionEvent.SyncUpdate(reading)) }
            }
        }

        /** A replay knows where it is, so a lock on one can be checked against the file, to the sample. */
        private fun logTruth(reading: SyncReading) {
            val truth = reading.captureNanos?.let(capture::truthMs) ?: return
            val reference = reading.referenceMs ?: return
            trace?.log("sync.truth", mapOf("truth_ms" to truth, "reference_ms" to reference, "error_ms" to reference - truth))
        }

        override fun level(rmsDb: Double, peakDb: Double, stamps: String) {
            trace?.log("capture.stamps", mapOf("stamps" to stamps))
            post { if (syncRun === this@SyncRun) feed(SessionEvent.CaptureLevel(rmsDb, peakDb)) }
        }

        override fun stopped(stamps: String) {
            runTrace?.log("capture.stopped", mapOf("stamps" to stamps))
        }

        override fun failed(reason: String) {
            post { if (syncRun === this@SyncRun) feed(SessionEvent.CaptureFailed(reason)) }
        }
    }

    companion object {
        private const val TAG = "EnsomiRecognition"
        private const val TICK_MS = 1_000L
        private const val MODEL_CHECK_MS = 60_000L
        private const val CID_SUFFIX = 6

        /**
         * How far ahead of the heard position a game joins, at least: the playfield has to open and the player to see the
         * count-in and get their hands on the glass. The scroll time is the other floor, so the first notes scroll in.
         */
        private const val JOIN_LEAD_MS = 2_000.0

        @Volatile
        private var instance: RecognitionController? = null

        fun get(context: Context): RecognitionController =
            instance ?: synchronized(this) { instance ?: RecognitionController(context).also { instance = it } }
    }
}
