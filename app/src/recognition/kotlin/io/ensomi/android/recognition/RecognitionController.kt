package io.ensomi.android.recognition

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.ensomi.android.AppStores
import io.ensomi.android.audio.DecodedSong
import io.ensomi.android.model.ModelState
import io.ensomi.android.play.AmbientGame
import io.ensomi.android.play.PlayResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * drivers and screens may call in from any thread.
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
    private var aligner: AmbientAligner? = null
    private var syncRun: SyncRun? = null
    private var syncGeneration = 0
    private var decoded: Pair<String, DecodedSong>? = null

    @Volatile
    private var listening: MediaListening? = null

    private val stores: AppStores by lazy { AppStores.get(appContext) }
    private val charts by lazy { SessionCharts(appContext, stores, scope) { post { feed(it) } } }

    init {
        HostDrivers.sink = Reports()
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
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                post { feed(SessionEvent.Tick) }
            }
        }
        post { restore() }
    }

    fun start(path: LaunchPath) {
        updateOptions { it.copy(path = path) }
        post { begin(path, byShare = false) }
    }

    fun stop() = post {
        feed(SessionEvent.Stop("person"))
        mutableGame.value = null
    }

    fun retry(stage: Stage) = post { feed(SessionEvent.Retry(stage, modelInstalled())) }

    fun useFile(path: String) = post { feed(SessionEvent.UseFile(path)) }

    /** A share reached the app, or the person pasted a link. Starts a session when there is none for this song. */
    fun share(text: String, source: String?) {
        mutableOpens.value = true
        post {
            val shared = ShareParser.parse(text)
            if (startsNewSession(machine?.state, shared?.takeIf { it.namesSong }?.identity)) {
                begin(mutableOptions.value.path, byShare = true)
            }
            feed(SessionEvent.ShareReceived(text, shared, source))
        }
    }

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

    private suspend fun modelInstalled(): Boolean {
        // At a cold start the store is still looking at what is installed.
        withTimeoutOrNull(MODEL_CHECK_MS) { stores.model.state.first { it !is ModelState.Checking } }
        return stores.model.state.value is ModelState.Installed
    }

    private suspend fun begin(path: LaunchPath, byShare: Boolean) {
        if (machine?.state?.running == true) feed(SessionEvent.Stop("replaced"))
        closeSync()
        trace?.close()
        val time = now()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(time.wallMs))
        val cid = "$stamp-${UUID.randomUUID().toString().take(CID_SUFFIX)}"
        val log = SessionTrace(storage.traceFile(cid), cid, SystemClock::elapsedRealtimeNanos)
        trace = log
        val installed = modelInstalled()
        logDevice(log)
        val (started, effects) = SessionMachine.start(cid, path, byShare, installed, time) { event, fields -> log.log(event, fields) }
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
                "driver" to HostDrivers.current.name,
                "capture" to options.capture.name,
                "stars" to options.stars,
                "ambient_offset_ms" to options.ambientOffsetMs,
                // The trace's clock and the play path's, read together, for converting between them.
                "elapsed_ns_now" to SystemClock.elapsedRealtimeNanos(),
                "mono_ns_now" to System.nanoTime(),
            ),
        )
    }

    private suspend fun restore() {
        val saved = storage.readSnapshot() ?: return
        if (!saved.running) return
        val log = SessionTrace(storage.traceFile(saved.cid), saved.cid, SystemClock::elapsedRealtimeNanos)
        trace = log
        val (restored, effects) = SessionMachine.restored(saved, now()) { event, fields -> log.log(event, fields) }
        machine = restored
        published(restored)
        effects.forEach { carryOut(it) }
        if (restored.state.running) mutableOpens.value = true
    }

    private suspend fun feed(event: SessionEvent) {
        val current = machine ?: return
        val effects = current.on(event, now())
        published(current)
        effects.forEach { carryOut(it) }
    }

    private fun published(current: SessionMachine) {
        val state = current.state
        mutableSession.value = state
        val saved = state.persistable()
        if (saved == lastSaved) return
        lastSaved = saved
        if (state.running) storage.writeSnapshot(state) else storage.clearSnapshot()
    }

    private suspend fun carryOut(effect: SessionEffect) {
        when (effect) {
            SessionEffect.StartListening -> startListening()
            SessionEffect.BuildIndex -> buildIndex()
            is SessionEffect.Launch -> launchHost(effect.path)
            is SessionEffect.Perform -> {
                val path = machine?.state?.path ?: return
                HostDrivers.current.perform(effect.step, path, HostDrivers.reports)
            }
            SessionEffect.CancelDriver -> HostDrivers.current.cancel()
            is SessionEffect.Refresh -> scope.launch {
                val found = listening?.look(effect.path)
                post { feed(if (found != null) SessionEvent.FileSeen(found, Evidence.POLL) else SessionEvent.FileGone(effect.path)) }
            }
            is SessionEffect.Probe -> scope.launch {
                val (facts, song) = probe.probe(effect.file)
                post {
                    if (song != null) decoded = effect.file.path to song
                    feed(SessionEvent.Probed(effect.file, facts))
                }
            }
            is SessionEffect.LookUp -> scope.launch {
                val lookup = ShortLinks.lookUp(effect.url)
                post { feed(SessionEvent.LinkLookedUp(effect.url, lookup.songId, lookup.outcome)) }
            }
            is SessionEffect.Remember -> storage.remember(effect.songId, effect.path)
            is SessionEffect.MakeChart -> charts.make(effect.audio, mutableOptions.value.stars)
            SessionEffect.StopChart -> charts.stop()
            is SessionEffect.BuildReference -> buildReference(effect.audio)
            SessionEffect.StartCapture -> startCapture()
            SessionEffect.StopCapture -> stopCapture()
            is SessionEffect.Join -> join(effect.lock)
            SessionEffect.StopListening -> {
                listening?.stop()
                listening = null
                closeSync()
                decoded = null
            }
        }
    }

    private fun startListening() {
        val state = machine?.state ?: return
        listening?.stop()
        val listener = object : FileListener {
            override fun seen(file: FileFacts, evidence: Evidence) = post { feed(SessionEvent.FileSeen(file, evidence)) }

            override fun gone(path: String) = post { feed(SessionEvent.FileGone(path)) }
        }
        listening = MediaListening(appContext, scope, state.startedWallMs, listener) { event, fields ->
            trace?.log(event, fields)
        }.also { it.start() }
    }

    private fun buildIndex() {
        val source = listening ?: return
        scope.launch {
            val entries = source.scan()
            val known = storage.knownSongs()
            post { feed(SessionEvent.IndexReady(entries, known)) }
        }
    }

    private suspend fun launchHost(path: LaunchPath) {
        val result = withContext(Dispatchers.Main) { HostLauncher.launch(appContext, path) }
        feed(
            when (result) {
                is LaunchResult.Started -> SessionEvent.HostLaunched(result.how)
                LaunchResult.NotInstalled -> SessionEvent.HostNotInstalled
                is LaunchResult.Failed -> SessionEvent.HostLaunchFailed(result.reason)
            },
        )
    }

    private fun buildReference(audio: ReadyAudio) {
        closeSync()
        val generation = ++syncGeneration
        val kept = decoded?.takeIf { it.first == audio.file.path }?.second
        scope.launch {
            val started = SystemClock.elapsedRealtimeNanos()
            try {
                val song = kept ?: probe.decode(audio.file)
                val opened = RuntimeSync.port.open(song.samples, song.sampleRate)
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
            } catch (failure: Exception) {
                Log.w(TAG, "The reference could not be built", failure)
                val reason = failure.message ?: failure.javaClass.simpleName
                post { feed(SessionEvent.ReferenceFailed(reason)) }
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
        stopCapture()
        aligner?.close()
        aligner = null
    }

    private fun join(lock: LockAnchor) {
        closeSync()
        val state = machine?.state ?: return
        val audio = state.audio ?: return
        val chart = state.chart ?: return
        val options = mutableOptions.value
        mutableOpens.value = true
        scope.launch {
            val event = try {
                plan(lock, chart, audio, options)
            } catch (failure: Exception) {
                Log.w(TAG, "The game could not be joined", failure)
                SessionEvent.GameFailed(failure.message ?: failure.javaClass.simpleName)
            }
            post { feed(event) }
        }
    }

    /** The game from where the music has got to now: the lock's time is the anchor, this is only when to come in. */
    private fun plan(lock: LockAnchor, facts: ChartFacts, audio: ReadyAudio, options: SessionOptions): SessionEvent {
        val chart = charts.load(facts, audio) ?: return SessionEvent.GameFailed("The chart could not be read.")
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
        if (plan == null) return SessionEvent.JoinRefused("heard ${heard.toLong()} ms of ${chart.durationMs} ms")
        mutableGame.value = AmbientGame(plan.chart, clock, plan.joinMs, options.ambientOffsetMs.toDouble(), options.autoplay)
        return SessionEvent.Joined(JoinFacts(plan.joinMs, heard, plan.chart.notes.size, plan.notesSkipped))
    }

    /**
     * One run of capture into the aligner, pushed from the capture thread, as the runtime asks: `AudioRecord`'s own
     * buffer holds the few milliseconds a search takes. Pushing stops at the first final lock.
     */
    private inner class SyncRun(private val target: AmbientAligner, private val capture: CapturePort) : CaptureSink {
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
                post { feed(SessionEvent.SyncUpdate(reading)) }
            } catch (failure: Exception) {
                Log.w(TAG, "The aligner failed", failure)
                done = true
                val reading = SyncReading(SyncState.FAILED, detail = failure.message ?: failure.javaClass.simpleName)
                post { feed(SessionEvent.SyncUpdate(reading)) }
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
            post { feed(SessionEvent.CaptureLevel(rmsDb, peakDb)) }
        }

        override fun failed(reason: String) {
            post { feed(SessionEvent.CaptureFailed(reason)) }
        }
    }

    /** What the host reports goes to the session like everything else. */
    private inner class Reports : HostReports {
        override fun stepStarted(step: HostStep, detail: String?) = post { feed(SessionEvent.StepStarted(step, detail)) }

        override fun stepDone(step: HostStep, detail: String?) = post { feed(SessionEvent.StepDone(step, detail)) }

        override fun handToPerson(step: HostStep, reason: String) = post { feed(SessionEvent.HandedToPerson(step, reason)) }

        override fun resultSeen(title: String, artist: String?, durationMs: Long?) =
            post { feed(SessionEvent.ResultSeen(title, artist, durationMs)) }

        override fun recognitionFailed(reason: String) = post { feed(SessionEvent.RecognitionFailed(reason)) }

        override fun downloadRefused(reason: String) = post { feed(SessionEvent.DownloadRefused(reason)) }
    }

    companion object {
        private const val TAG = "EnsomiRecognition"
        private const val TICK_MS = 1_000L
        private const val MODEL_CHECK_MS = 5_000L
        private const val CID_SUFFIX = 6

        /**
         * How far ahead of the heard position a game joins, at least: the playfield has to open and the player to see the
         * count-in and get their hands on the glass. The scroll time is the other floor, so the first notes scroll in.
         */
        private const val JOIN_LEAD_MS = 4_000.0

        @Volatile
        private var instance: RecognitionController? = null

        fun get(context: Context): RecognitionController =
            instance ?: synchronized(this) { instance ?: RecognitionController(context).also { instance = it } }
    }
}
