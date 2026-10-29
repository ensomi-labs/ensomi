package io.ensomi.android.checks

import android.net.Uri
import io.ensomi.android.chart.ChartRequest
import io.ensomi.android.chart.ChartStore
import io.ensomi.android.chart.GeneratorChoices
import io.ensomi.android.chart.MakePhase
import io.ensomi.android.chart.MakeState
import io.ensomi.android.chart.RuntimeChartMaker
import io.ensomi.android.library.AddResult
import io.ensomi.android.library.Song
import io.ensomi.android.library.SongLibrary
import io.ensomi.android.model.ChartModelStore
import io.ensomi.android.model.InstallRecord
import io.ensomi.android.model.ModelInstaller
import io.ensomi.android.model.ModelState
import io.ensomi.android.model.Notice
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/** The installed model, or null: `noBackupFilesDir/models/ensomi-r2` with its manifest and record. */
fun installedModel(): Pair<File, InstallRecord?>? {
    val directory = File(PhoneCheck.context.noBackupFilesDir, "models/ensomi-r2")
    if (!File(directory, ModelInstaller.MANIFEST).isFile) return null
    val record = File(directory, ModelInstaller.RECORD).takeIf { it.isFile }?.let {
        runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(InstallRecord.serializer(), it.readText()) }.getOrNull()
    }
    return directory to record
}

/** The song a generation check uses: a file the script pushed, or a synthetic one when none was. */
class CheckSong(val file: File, val name: String, val synthetic: Boolean)

/**
 * The song for a generation check: the pushed song named by the `song` argument, else the first pushed song, else a
 * synthetic four-minute song (beats, a bass line and chords at 128 BPM), so the check measures something on any phone.
 */
fun checkSong(): CheckSong {
    val pushed = PhoneCheck.pushedFiles(PhoneCheck.songsDir)
    val wanted = PhoneCheck.arg("song", "")
    val name = if (wanted.isNotEmpty()) wanted.takeIf { it in pushed } else pushed.firstOrNull()
    if (name != null) return CheckSong(PhoneCheck.copyPushed(PhoneCheck.songsDir, name), name, synthetic = false)
    val file = File(File(PhoneCheck.context.cacheDir, "phone-checks").also { it.mkdirs() }, "synthetic-240s.wav")
    if (!file.isFile) writeSyntheticSong(file, seconds = 240)
    return CheckSong(file, file.name, synthetic = true)
}

/** A 44.1 kHz 16-bit stereo WAV: a kick on every beat, a hi-hat on the off-beats, a bass line and chords. */
private fun writeSyntheticSong(file: File, seconds: Int) {
    val rate = 44_100
    val frames = rate * seconds
    val beat = 60.0 / 128
    val random = Random(20261029)
    val roots = doubleArrayOf(110.0, 87.31, 130.81, 98.0)
    DataOutputStream(file.outputStream().buffered(1 shl 16)).use { out ->
        fun le32(v: Int) = out.writeInt(Integer.reverseBytes(v))
        fun le16(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
        out.writeBytes("RIFF"); le32(36 + frames * 4); out.writeBytes("WAVE")
        out.writeBytes("fmt "); le32(16); le16(1); le16(2); le32(rate); le32(rate * 4); le16(4); le16(16)
        out.writeBytes("data"); le32(frames * 4)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val inBeat = t % beat
            val bar = (t / (beat * 4)).toInt()
            val root = roots[bar % roots.size]
            val kick = if (inBeat < 0.15) sin(2 * PI * (50 + 80 * exp(-inBeat * 30)) * inBeat) * exp(-inBeat * 18) else 0.0
            val offBeat = (t + beat / 2) % beat
            val hat = if (offBeat < 0.04) (random.nextDouble() * 2 - 1) * exp(-offBeat * 120) * 0.3 else 0.0
            val bass = sin(2 * PI * root * t) * 0.25 * (1 - inBeat / beat)
            val chord = (sin(2 * PI * root * 2 * t) + sin(2 * PI * root * 2.5198 * t) + sin(2 * PI * root * 2.9966 * t)) * 0.06
            val left = ((kick * 0.6 + hat + bass + chord) * 0.8).coerceIn(-1.0, 1.0)
            val right = ((kick * 0.6 + hat * 0.7 + bass + chord * 1.2) * 0.8).coerceIn(-1.0, 1.0)
            le16((left * 32767).toInt())
            le16((right * 32767).toInt())
        }
    }
}

/** The `lowMemory` a check asks for: the `lowMemory` argument, `on`, `off` or `device` (the app's own choice). */
fun checkLowMemory(): Boolean = when (PhoneCheck.arg("lowMemory", "device")) {
    "on", "true" -> true
    "off", "false" -> false
    else -> GeneratorChoices.lowMemory(PhoneCheck.memoryInfo().totalMem, PhoneCheck.lowRamDevice())
}

/** What one chart made through the app's path took. */
class Generation(
    val state: MakeState,
    /** Milliseconds from Make chart to the start of each phase the maker reported, and to the end. */
    val phaseStartMs: Map<String, Double>,
    val totalMs: Double,
    /** The process's largest resident memory seen in each phase, sampled every 50 ms, in bytes. */
    val peakRssByPhase: Map<String, Long>,
    val peakRss: Long,
    val rssBefore: Long,
    val minAvailBytes: Long,
    val systemLowMemorySeen: Boolean,
    val osu: String?,
    val receipt: String?,
    val modelOpenMs: Double,
)

/**
 * Makes one chart the way the app does, through [RuntimeChartMaker]: the song decoded by `SongDecoder`, its SHA-256,
 * `prepare` on the runtime's worker, then the whole-chart scheduler, the result kept in a scratch [ChartStore]. The
 * song library and the model store are stand-ins holding only [song] and the installed model; a new maker has an
 * empty cache of prepared songs, so every run does the whole work.
 */
suspend fun generateThroughTheApp(
    song: CheckSong,
    modelDirectory: File,
    revision: String,
    lowMemory: Boolean,
    stars: Double,
    seed: ULong,
    scratch: File,
): Generation {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val songId = "check-song"
    val songs = OneSongLibrary(Song(songId, song.name, null, 0, song.name, uri = Uri.fromFile(song.file)))
    val model = InstalledModelStore(modelDirectory, revision)
    scratch.deleteRecursively()
    val store = ChartStore(File(scratch, "charts"))
    try {
        val openStarted = System.nanoTime()
        val maker = RuntimeChartMaker(PhoneCheck.context, scope, songs, model, store, lowMemory)
        // The maker opens the model once to read its capabilities; that is done before the measurement starts.
        withTimeout(120_000) { maker.capabilities.filterNotNull().first() }
        val modelOpenMs = (System.nanoTime() - openStarted) / 1e6

        val phase = AtomicReference("start")
        val peaks = LinkedHashMap<String, Long>()
        val minAvail = AtomicLong(Long.MAX_VALUE)
        var lowSeen = false
        val rssBefore = PhoneCheck.residentBytes()
        val sampler = Thread({
            var tick = 0
            while (!Thread.currentThread().isInterrupted) {
                val rss = PhoneCheck.residentBytes()
                synchronized(peaks) { peaks[phase.get()] = maxOf(peaks[phase.get()] ?: 0L, rss) }
                if (tick++ % 5 == 0) {
                    val info = PhoneCheck.memoryInfo()
                    minAvail.set(minOf(minAvail.get(), info.availMem))
                    if (info.lowMemory) lowSeen = true
                }
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "PhoneCheckMemory")

        val phaseStart = java.util.concurrent.ConcurrentHashMap<String, Double>()
        val started = System.nanoTime()
        sampler.start()
        val watcher = scope.launch {
            maker.states.collect { states ->
                val state = states[songId]
                val name = when (state) {
                    is MakeState.Making -> state.phase.name.lowercase()
                    null, MakeState.Idle -> return@collect
                    else -> "done"
                }
                if (phase.getAndSet(name) != name) phaseStart.putIfAbsent(name, (System.nanoTime() - started) / 1e6)
            }
        }
        maker.make(songId, ChartRequest(stars = stars, seed = seed, sections = emptyList()))
        val end = withTimeout(30 * 60_000L) {
            maker.states.first { states ->
                val state = states[songId]
                state is MakeState.Made || state is MakeState.Failed || state is MakeState.Stopped
            }.getValue(songId)
        }
        val totalMs = (System.nanoTime() - started) / 1e6
        sampler.interrupt()
        sampler.join()
        watcher.cancel()
        val record = File(scratch, "charts")
        val osu = store.osu(songId)
        val receipt = File(record, "$songId/receipt.json").takeIf { it.isFile }?.readText()
            ?: record.walkTopDown().firstOrNull { it.name == "receipt.json" }?.readText()
        val byPhase = synchronized(peaks) { LinkedHashMap(peaks) }
        return Generation(
            state = end,
            phaseStartMs = PHASES.mapNotNull { name -> phaseStart[name]?.let { name to it } }.toMap(),
            totalMs = totalMs,
            peakRssByPhase = byPhase,
            peakRss = byPhase.values.maxOrNull() ?: -1,
            rssBefore = rssBefore,
            minAvailBytes = minAvail.get(),
            systemLowMemorySeen = lowSeen,
            osu = osu,
            receipt = receipt,
            modelOpenMs = modelOpenMs,
        )
    } finally {
        scope.cancel()
    }
}

/** The phase names in the order the maker reports them, for the report. */
val PHASES = MakePhase.entries.map { it.name.lowercase() } + "done"

/** A library of one song, a file in the app's cache. */
private class OneSongLibrary(song: Song) : SongLibrary {
    override val songs: StateFlow<List<Song>> = MutableStateFlow(listOf(song)).asStateFlow()

    override suspend fun add(documents: List<Uri>) = AddResult(0, 0, emptyList())

    override suspend fun findAgain(id: String, document: Uri) = false

    override fun remove(id: String) = Unit

    override fun refresh() = Unit

    override fun markMissing(id: String) = Unit
}

/** The installed model as the app's store reports it, without downloading anything. */
private class InstalledModelStore(directory: File, revision: String) : ChartModelStore {
    override val state: StateFlow<ModelState> =
        MutableStateFlow<ModelState>(ModelState.Installed("Ensomi R2", revision, 0, "MIT")).asStateFlow()
    override val directory: File = directory
    override val notices: List<Notice> = emptyList()

    override fun download() = Unit

    override fun installFromFolder(folder: Uri) = Unit

    override fun cancel() = Unit

    override fun remove() = Unit
}
