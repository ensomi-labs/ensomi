package io.ensomi.android.recognition

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import io.ensomi.android.AppStores
import io.ensomi.android.audio.DecodeException
import io.ensomi.android.audio.DecodedSong
import io.ensomi.android.audio.PcmChannels
import io.ensomi.android.audio.SongDecoder
import io.ensomi.android.chart.ChartRequest
import io.ensomi.android.chart.MakeState
import io.ensomi.android.chart.RuntimeChartMaker
import io.ensomi.android.chart.StoredChart
import io.ensomi.android.library.AddResult
import io.ensomi.android.library.Song
import io.ensomi.android.library.SongLibrary
import io.ensomi.android.play.PlayChart
import io.ensomi.runtime.OsuException
import io.ensomi.runtime.parseOsu
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The URI a file is read through: its MediaStore row when it has one, which needs no access to its folder. */
fun FileFacts.readUri(): Uri = uri?.toUri() ?: Uri.fromFile(File(path))

/**
 * A file's decoding, to the end, with the app's own decoder: the proof that it is whole, and, since the same PCM builds
 * Sync's reference, the only time a song is decoded twice over. A `.ncm` never gets here ([CandidateRules]).
 */
class AudioProbe(context: Context) {
    private val appContext = context.applicationContext

    /** Blocking. The song comes back when it decoded. */
    fun probe(file: FileFacts): Pair<ProbeFacts, DecodedSong?> {
        val uri = file.readUri()
        val started = System.nanoTime()
        return try {
            val song = SongDecoder.decode(appContext, uri, PcmChannels.MONO)
            val hash = sha256(uri)
            val disk = File(file.path)
            ProbeFacts(
                decoded = true,
                decodedMs = song.durationMs,
                declaredMs = declaredMs(uri),
                sampleRate = song.sampleRate,
                mime = song.mime,
                codec = song.codec,
                tookMs = (System.nanoTime() - started) / 1_000_000,
                sha256 = hash,
                sizeBytes = disk.length(),
                modifiedWallMs = disk.lastModified(),
            ) to song
        } catch (failure: DecodeException) {
            failed(file, started, failure)
        } catch (failure: IOException) {
            failed(file, started, failure)
        } catch (failure: IllegalStateException) {
            // MediaCodec's own failures.
            failed(file, started, failure)
        }
    }

    /** The song decoded again, when the process has lost it. Blocking. */
    fun decode(file: FileFacts): DecodedSong = SongDecoder.decode(appContext, file.readUri(), PcmChannels.MONO)

    private fun failed(file: FileFacts, started: Long, failure: Exception): Pair<ProbeFacts, DecodedSong?> {
        val disk = File(file.path)
        return ProbeFacts(
            decoded = false,
            tookMs = (System.nanoTime() - started) / 1_000_000,
            failure = failure.message ?: failure.javaClass.simpleName,
            sizeBytes = disk.length(),
            modifiedWallMs = disk.lastModified(),
        ) to null
    }

    private fun declaredMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun sha256(uri: Uri): String {
        val input: InputStream = appContext.contentResolver.openInputStream(uri) ?: throw IOException("No stream for $uri")
        val digest = MessageDigest.getInstance("SHA-256")
        input.use {
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }
}

/** The one song a session makes a chart of: the chart maker reads songs from a library, and this one holds no others. */
private class SessionSongLibrary : SongLibrary {
    private val state = MutableStateFlow<List<Song>>(emptyList())
    override val songs: StateFlow<List<Song>> = state.asStateFlow()

    fun put(song: Song) {
        state.value = listOf(song)
    }

    override suspend fun add(documents: List<Uri>) = AddResult(0, 0, emptyList())

    override suspend fun findAgain(id: String, document: Uri) = false

    override fun remove(id: String) = Unit

    override fun refresh() = Unit

    override fun markMissing(id: String) = Unit
}

/**
 * The session's chart, on the app's own path: [RuntimeChartMaker] on the session's song, kept in the same [ChartStore]
 * as every other chart, so that a chart already made from the same bytes, in a session or from the library, is used
 * as it is. The whole chart is made before Sync starts, however long that takes: the music in the room moves on
 * meanwhile, and the join goes to wherever it has got to.
 */
class SessionCharts(
    context: Context,
    private val stores: AppStores,
    private val scope: CoroutineScope,
    private val emit: (SessionEvent) -> Unit,
) {
    private val appContext = context.applicationContext
    private val library = SessionSongLibrary()

    // A maker of its own, with its own worker: the app's maker reads songs from the player's library.
    private val maker by lazy {
        RuntimeChartMaker(appContext, stores.scope, library, stores.model, stores.chartStore, stores.charts.lowMemory)
    }
    private var job: Job? = null

    @Volatile
    private var making: String? = null

    fun make(audio: ReadyAudio, stars: Double) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            try {
                run(audio, stars)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.w(TAG, "The session's chart failed", failure)
                emit(SessionEvent.ChartFailed(failure.message ?: failure.javaClass.simpleName))
            }
        }
    }

    fun stop() {
        job?.cancel()
        making?.let(maker::stop)
    }

    /** The chart to play, on a song the length of the decoded audio, as a game of the player's own song has it. Blocking. */
    fun load(chart: ChartFacts, audio: ReadyAudio): PlayChart? {
        val osu = stores.chartStore.osu(chart.songId) ?: return null
        return try {
            PlayChart.of(audio.title, parseOsu(osu).chart, ceil(audio.decodedMs).toInt())
        } catch (failure: OsuException) {
            Log.w(TAG, "The session's chart could not be read: ${failure.message}")
            null
        }
    }

    private suspend fun run(audio: ReadyAudio, stars: Double) {
        val started = System.nanoTime()
        stored(audio.sha256)?.let { chart ->
            emit(SessionEvent.ChartReady(ChartFacts(chart.songId, chart.noteCount, stored = true), 0.0))
            return
        }
        val id = "session-" + (audio.sha256?.take(ID_HASH_LENGTH) ?: audio.file.sizeBytes.toString())
        library.put(
            Song(
                id = id,
                title = audio.title,
                artist = audio.artist,
                durationMs = audio.decodedMs.toLong(),
                fileName = audio.file.name,
                uri = audio.file.readUri(),
            ),
        )
        // The library's background work on another song would slow this down, and nobody is waiting for it.
        stores.charts.quiet()
        making = id
        maker.make(id, ChartRequest(stars, seed = null, sections = emptyList()))
        val outcome = maker.states.map { it[id] }.distinctUntilChanged().first { state ->
            if (state is MakeState.Making) emit(SessionEvent.ChartPhase(state.phase.name))
            state is MakeState.Made || state is MakeState.Failed || state is MakeState.Stopped
        }
        making = null
        val seconds = (System.nanoTime() - started) / 1e9
        when (outcome) {
            is MakeState.Made -> emit(SessionEvent.ChartReady(ChartFacts(id, outcome.chart.noteCount, stored = false), seconds))
            is MakeState.Failed -> emit(SessionEvent.ChartFailed(outcome.message, modelMissing = stores.model.directory == null))
            else -> Unit
        }
    }

    private fun stored(sha256: String?): StoredChart? {
        if (sha256 == null) return null
        return stores.chartStore.songIds.value.firstNotNullOfOrNull { id ->
            stores.chartStore.chart(id)?.takeIf { it.sha256 == sha256 }
        }
    }

    private companion object {
        const val TAG = "EnsomiRecognition"
        const val ID_HASH_LENGTH = 32
    }
}
