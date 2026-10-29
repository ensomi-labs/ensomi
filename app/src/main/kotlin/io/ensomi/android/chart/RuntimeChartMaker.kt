package io.ensomi.android.chart

import android.content.Context
import android.net.Uri
import android.util.Log
import io.ensomi.android.audio.DecodeException
import io.ensomi.android.audio.PcmChannels
import io.ensomi.android.audio.SongDecoder
import io.ensomi.android.library.Song
import io.ensomi.android.library.SongLibrary
import io.ensomi.android.model.ChartModelStore
import io.ensomi.android.model.ModelState
import io.ensomi.runtime.Cancellation
import io.ensomi.runtime.ChartControls
import io.ensomi.runtime.ChartScheduler
import io.ensomi.runtime.ControlStatus
import io.ensomi.runtime.ExecutionProvider
import io.ensomi.runtime.GenerateChartRequest
import io.ensomi.runtime.GeneratorException
import io.ensomi.runtime.GeneratorOptions
import io.ensomi.runtime.Judge
import io.ensomi.runtime.LeadPolicy
import io.ensomi.runtime.ModelCapabilities
import io.ensomi.runtime.PatternKind
import io.ensomi.runtime.Prepared
import io.ensomi.runtime.Ruleset
import io.ensomi.runtime.RuntimeWorker
import io.ensomi.runtime.ScheduleMode
import io.ensomi.runtime.SectionRequest
import io.ensomi.runtime.SectionTarget
import io.ensomi.runtime.prepare
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Makes charts on this device with the installed model, as the Swift app does:
 *
 * - Opening a song starts the runtime's `prepare` (PCM preparation, the spectrogram, BeatThis, the features and the
 *   beat grid, most of the work) in the background. The [PREPARED_SONGS] latest results are kept in memory by the
 *   SHA-256 of the song's file and the model's revision, so Make chart often has only the chart stages left.
 * - Make chart runs a whole-chart `ChartScheduler` from those stages and keeps the result, `.osu` and receipt, as the
 *   song's last chart in [store], so it can be played after the app was closed.
 * - Stop signals the work under way from the calling thread: the preparation's `Cancellation`, or the scheduler's
 *   `stop()`. The runtime stops at its next graph run.
 *
 * The model is opened for each piece of work (`RuntimeWorker.withGenerator`), which takes a fraction of a second, and
 * closed after it, so it holds no memory while the player plays. All of it runs on one worker, one piece at a time.
 */
class RuntimeChartMaker(
    context: Context,
    private val scope: CoroutineScope,
    private val songs: SongLibrary,
    private val model: ChartModelStore,
    private val store: ChartStore,
    /**
     * `GeneratorOptions.lowMemory`; null leaves it to the runtime, which keeps it off outside iOS. A debug launch may
     * change it ([io.ensomi.android.prototype.Scenario]).
     */
    @Volatile var lowMemory: Boolean?,
) : ChartMaker {
    private val appContext = context.applicationContext
    private val worker = RuntimeWorker()
    private val prepared = RecentValues<String, Prepared>(PREPARED_SONGS) { it.close() }
    private val mutableStates = MutableStateFlow<Map<String, MakeState>>(emptyMap())
    private val mutableCapabilities = MutableStateFlow<ChartCapabilities?>(null)
    private val lock = Any()
    private val preparations = HashMap<String, Preparation>()
    private val makings = HashMap<String, Making>()

    override val states: StateFlow<Map<String, MakeState>> = mutableStates.asStateFlow()
    override val capabilities: StateFlow<ChartCapabilities?> = mutableCapabilities.asStateFlow()

    /** A song's audio stages under way, stopped through its [cancellation] or, while decoding, [stopped]. */
    private class Preparation(val songId: String) {
        val cancellation = Cancellation()
        val stopped = AtomicBoolean(false)
        private var closed = false

        /** A chart is waiting for it: [ChartMaker.quiet] leaves it running. */
        @Volatile
        var wanted = false

        @Volatile
        var phase = MakePhase.LOADING

        @Volatile
        var onPhase: ((MakePhase) -> Unit)? = null

        /** The key of [prepared] its result is kept under, once it is there. */
        @Volatile
        var key: String? = null

        @Volatile
        var sha256: String? = null

        @Volatile
        var failure: Exception? = null

        lateinit var job: Job

        fun report(next: MakePhase) {
            phase = next
            onPhase?.invoke(next)
        }

        fun stop() {
            stopped.set(true)
            synchronized(this) { if (!closed) cancellation.cancel() }
        }

        fun close() {
            synchronized(this) {
                closed = true
                cancellation.close()
            }
        }
    }

    /** A chart being made; [stop] reaches whichever part is under way. */
    private class Making {
        val stopped = AtomicBoolean(false)

        @Volatile
        var preparation: Preparation? = null
        private var scheduler: ChartScheduler? = null

        fun stop() {
            stopped.set(true)
            preparation?.stop()
            synchronized(this) { scheduler?.stop() }
        }

        /** Publishes the scheduler for [stop], or withdraws it before it is closed. */
        fun running(next: ChartScheduler?) {
            synchronized(this) {
                scheduler = next
                if (next != null && stopped.get()) next.stop()
            }
        }
    }

    init {
        scope.launch {
            model.state.map { (it as? ModelState.Installed)?.let { installed -> installed.revision ?: installed.name } }
                .distinctUntilChanged()
                .collectLatest { revision -> mutableCapabilities.value = revision?.let { readCapabilities() } }
        }
    }

    override fun open(songId: String) {
        scope.launch {
            val record = runCatching { store.chart(songId) }.getOrNull()
            mutableStates.update { states ->
                if (states[songId] is MakeState.Making) {
                    states
                } else if (record != null) {
                    states + (songId to MakeState.Made(songId, record.toMade()))
                } else {
                    states - songId
                }
            }
        }
        synchronized(lock) { startPreparation(songId) }
    }

    override fun make(songId: String, request: ChartRequest) {
        val making = Making()
        synchronized(lock) {
            if (makings[songId] != null) return
            makings[songId] = making
        }
        setState(songId, MakeState.Making(songId, MakePhase.LOADING))
        scope.launch {
            val state = try {
                makeChart(songId, request, making)
            } catch (failure: Exception) {
                failed(songId, failure, making)
            }
            synchronized(lock) { makings.remove(songId) }
            setState(songId, state)
        }
    }

    override fun stop(songId: String) {
        synchronized(lock) { makings[songId] }?.stop()
    }

    override fun quiet() {
        val idle = synchronized(lock) { preparations.values.filterNot { it.wanted } }
        idle.forEach {
            Log.i(TAG, "A game starts: the preparation of ${it.songId} stops")
            it.stop()
        }
    }

    override fun lastChart(songId: String): LastChart? {
        val record = store.chart(songId) ?: return null
        val osu = store.osu(songId) ?: return null
        return LastChart(record, osu)
    }

    /** The song was removed from the library: its chart goes too. */
    fun forget(songId: String) {
        stop(songId)
        scope.launch {
            store.remove(songId)
            mutableStates.update { it - songId }
        }
    }

    private suspend fun makeChart(songId: String, request: ChartRequest, making: Making): MakeState {
        val song = songs.song(songId) ?: return MakeState.Failed(songId, "The song is no longer in the library.")
        val directory = model.directory ?: return MakeState.Failed(songId, "The chart model is not installed.")
        // The song's audio stages: the background preparation's, once it is done, or a new one.
        var key: String? = null
        var sha256: String? = null
        for (attempt in 0 until 2) {
            val preparation = synchronized(lock) { startPreparation(songId) }
                ?: return MakeState.Failed(songId, "The chart model is not installed.")
            preparation.wanted = true
            making.preparation = preparation
            preparation.onPhase = { setState(songId, MakeState.Making(songId, it)) }
            setState(songId, MakeState.Making(songId, preparation.phase))
            if (making.stopped.get()) preparation.stop()
            preparation.job.join()
            if (making.stopped.get()) return MakeState.Stopped(songId)
            preparation.failure?.let { throw it }
            key = preparation.key
            sha256 = preparation.sha256
            // A preparation a game stopped (quiet) is made again for the chart, once.
            if (key != null || !preparation.stopped.get()) break
        }
        making.preparation = null
        val preparedKey = key ?: return MakeState.Failed(songId, "The song's audio could not be prepared.")
        setState(songId, MakeState.Making(songId, MakePhase.ROWS))
        val runtimeRequest = request.toRuntime(song)
        val started = System.nanoTime()
        PeakMemory.reset()
        val result = worker.withGenerator(appContext, directory, options()) {
            val stages = prepared.get(preparedKey) ?: return@withGenerator null
            ChartScheduler.fromPrepared(this, stages, runtimeRequest, ScheduleMode.WHOLE_CHART, NO_PLAY).use { scheduler ->
                making.running(scheduler)
                try {
                    scheduler.work()
                    scheduler.result()
                } finally {
                    making.running(null)
                }
            }
        }
        if (result == null) {
            return if (making.stopped.get()) MakeState.Stopped(songId) else MakeState.Failed(songId, "No chart was made.")
        }
        Log.i(
            TAG,
            String.format(
                Locale.ROOT,
                "Chart made for %s in %.1f s, peak RSS %s: %d objects, seed %s, star %.1f",
                song.fileName, (System.nanoTime() - started) / 1e9, PeakMemory.describe(),
                result.chart.objects.size, result.receipt.seed, result.receipt.star,
            ),
        )
        setState(songId, MakeState.Making(songId, MakePhase.EXPORT))
        val holds = result.chart.objects.count { it.endMs != null }
        val record = StoredChart(
            songId = songId,
            modelName = "${result.receipt.modelName} ${result.receipt.modelVersion}",
            seed = result.receipt.seed.toString(),
            stars = result.receipt.star,
            noteCount = result.chart.objects.size,
            holdCount = holds,
            durationMs = result.chart.durationMs.toInt(),
            sha256 = sha256,
            madeAtMs = System.currentTimeMillis(),
        )
        store.save(record, result.osu, result.receipt.receiptJson)
        return MakeState.Made(songId, record.toMade())
    }

    private fun failed(songId: String, failure: Exception, making: Making): MakeState = when {
        making.stopped.get() -> MakeState.Stopped(songId)
        failure is GeneratorException.Cancelled -> MakeState.Stopped(songId)
        failure is GeneratorException.Generation -> MakeState.Failed(songId, "${failure.code}: ${failure.message}")
        failure is GeneratorException -> MakeState.Failed(songId, failure.message ?: failure.javaClass.simpleName)
        failure is DecodeException -> {
            if (failure.missing) songs.markMissing(songId)
            MakeState.Failed(songId, failure.message ?: "The song could not be decoded.")
        }
        else -> {
            Log.w(TAG, "Making a chart failed", failure)
            MakeState.Failed(songId, failure.message ?: failure.javaClass.simpleName)
        }
    }.also { if (it is MakeState.Failed) Log.i(TAG, "Chart for $songId not made: ${it.message}") }

    /** The song's preparation under way, or a new one; null without a model. Call it holding [lock]. */
    private fun startPreparation(songId: String): Preparation? {
        preparations[songId]?.let { return it }
        if (model.directory == null) return null
        val preparation = Preparation(songId)
        preparations[songId] = preparation
        preparation.job = scope.launch {
            try {
                preparation.key = prepareSong(preparation)
            } catch (failure: Exception) {
                if (!preparation.stopped.get()) {
                    Log.w(TAG, "Preparing $songId failed: $failure")
                    preparation.failure = failure
                }
            } finally {
                synchronized(lock) { if (preparations[songId] === preparation) preparations.remove(songId) }
                preparation.close()
            }
        }
        return preparation
    }

    /** Runs the song's audio stages unless they are kept already; returns their key in [prepared]. */
    private suspend fun prepareSong(preparation: Preparation): String? {
        val song: Song = songs.song(preparation.songId) ?: return null
        val uri = song.uri ?: return null
        val directory = model.directory ?: return null
        val revision = (model.state.value as? ModelState.Installed)?.let { it.revision ?: it.name } ?: return null
        val sha256 = sha256(uri)
        preparation.sha256 = sha256
        val key = "$sha256/$revision"
        if (prepared.get(key) != null) return key
        preparation.report(MakePhase.LOADING)
        val started = System.nanoTime()
        val audio = SongDecoder.decode(appContext, uri, PcmChannels.MONO) { preparation.stopped.get() }
        if (preparation.stopped.get()) return null
        val decoded = System.nanoTime()
        preparation.report(MakePhase.TIMING)
        PeakMemory.reset()
        val done = worker.withGenerator(appContext, directory, options()) {
            if (preparation.stopped.get()) return@withGenerator false
            prepared.put(key, prepare(audio.samples, audio.sampleRate.toDouble(), preparation.cancellation))
            true
        }
        if (!done) return null
        Log.i(
            TAG,
            String.format(
                Locale.ROOT,
                "Prepared %s (%.1f s at %d Hz): decoded in %.1f s, audio stages in %.1f s, peak RSS %s, lowMemory %s",
                song.fileName, audio.durationMs / 1000, audio.sampleRate, (decoded - started) / 1e9,
                (System.nanoTime() - decoded) / 1e9, PeakMemory.describe(), lowMemory,
            ),
        )
        return key
    }

    private fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("No stream for $uri")
            input.use {
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } catch (failure: IOException) {
            throw DecodeException("The song's file could not be read.", missing = true, cause = failure)
        } catch (failure: SecurityException) {
            throw DecodeException("The song's file could not be read.", missing = true, cause = failure)
        }
        return digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    private suspend fun readCapabilities(): ChartCapabilities? {
        val directory = model.directory ?: return null
        return try {
            worker.withGenerator(appContext, directory, options()) { capabilities() }.toApp()
        } catch (failure: GeneratorException) {
            Log.w(TAG, "The model's capabilities could not be read: ${failure.message}")
            null
        }
    }

    private fun options() = GeneratorOptions(ExecutionProvider.CPU, lowMemory = lowMemory)

    private fun setState(songId: String, state: MakeState) {
        mutableStates.update { it + (songId to state) }
    }

    companion object {
        private const val TAG = "EnsomiCharts"

        /** How many songs' audio stages are kept, as in the Swift app: about 50 KB per second of song each. */
        const val PREPARED_SONGS = 4

        /** What the app asks for: the runtime's own choice, which is off on Android (plan step 5 says why). */
        val LOW_MEMORY: Boolean? = null

        /** No play starts from these schedulers, so their lead is never used. */
        private val NO_PLAY = LeadPolicy(Ruleset(Judge.C), renderLeadMs = 0.0, marginMs = 0.0)
    }
}

/** The capabilities the screens use: section kinds the model offers, in its order, without those marked not working. */
fun ModelCapabilities.toApp(): ChartCapabilities = ChartCapabilities(
    modelName = "$modelName $modelVersion",
    minStar = minStar,
    maxStarExclusive = maxStarExclusive,
    kinds = controls.mapNotNull { control ->
        val kind = sectionKind(control.name) ?: return@mapNotNull null
        if (control.status == ControlStatus.NOT_WORKING) return@mapNotNull null
        KindOption(kind, experimental = control.status == ControlStatus.EXPERIMENTAL, note = control.note)
    },
)

/** The runtime's name for a section control. */
fun sectionKind(name: String): SectionKind? = when (name) {
    "jumpstream" -> SectionKind.JUMPSTREAM
    "chordstream" -> SectionKind.CHORDSTREAM
    "stream" -> SectionKind.STREAM
    "jack" -> SectionKind.JACK
    "chordjack" -> SectionKind.CHORDJACK
    "section_star" -> SectionKind.STAR
    else -> null
}

/** The runtime's request: a random seed when none was given, and the song's tags and file name for the `.osu`. */
fun ChartRequest.toRuntime(song: Song, random: Random = Random.Default): GenerateChartRequest = GenerateChartRequest(
    star = stars,
    seed = seed ?: random.nextLong().toULong(),
    controls = ChartControls(longNotes = null, sections = sections.mapNotNull { it.toRuntime() }),
    title = song.title,
    artist = song.artist,
    audioName = song.fileName,
)

private fun SectionDraft.toRuntime(): SectionRequest? {
    val start = ChartRequestRules.parseTime(this.start) ?: return null
    val end = ChartRequestRules.parseTime(this.end) ?: return null
    val target = when (kind) {
        SectionKind.STAR -> SectionTarget.Star(star.toDouble())
        SectionKind.JUMPSTREAM -> SectionTarget.Pattern(PatternKind.JUMPSTREAM, strength.toDouble())
        SectionKind.CHORDSTREAM -> SectionTarget.Pattern(PatternKind.CHORDSTREAM, strength.toDouble())
        SectionKind.STREAM -> SectionTarget.Pattern(PatternKind.STREAM, strength.toDouble())
        SectionKind.JACK -> SectionTarget.Pattern(PatternKind.JACK, strength.toDouble())
        SectionKind.CHORDJACK -> SectionTarget.Pattern(PatternKind.CHORDJACK, strength.toDouble())
    }
    return SectionRequest(start.toUInt(), end.toUInt(), target)
}

/**
 * The process's peak resident memory (`VmHWM`), reset before each piece of work through `/proc/self/clear_refs`
 * where the system allows it; for the logs and the phone's measurement of time and peak memory per song.
 */
object PeakMemory {
    @Volatile
    private var resetWorks: Boolean? = null

    fun reset() {
        resetWorks = runCatching { File("/proc/self/clear_refs").writeText("5") }.isSuccess
    }

    fun describe(): String {
        val line = runCatching { File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") } }.getOrNull()
        val kb = line?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: return "unknown"
        val since = if (resetWorks == true) "since the reset" else "since the process started"
        return String.format(Locale.ROOT, "%.0f MB %s", kb / 1024.0, since)
    }
}
