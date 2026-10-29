package io.ensomi.android.play

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ensomi.android.R
import io.ensomi.android.audio.DecodeException
import io.ensomi.android.audio.DecodedSong
import io.ensomi.android.audio.PcmChannels
import io.ensomi.android.audio.PlayAudioCache
import io.ensomi.android.audio.SongDecoder
import io.ensomi.android.chart.ChartMaker
import io.ensomi.android.chart.StoredChart
import io.ensomi.android.library.Song
import io.ensomi.android.library.SongLibrary
import io.ensomi.runtime.OsuException
import io.ensomi.runtime.parseOsu
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a game of the player's song needs before it starts: its last chart and its sound, decoded for play. */
sealed interface GameLoad {
    data object Loading : GameLoad

    class Ready(val chart: PlayChart, val audio: DecodedSong, val record: StoredChart) : GameLoad

    /** [message] is a string resource, said in place of the game. */
    data class Failed(val message: Int) : GameLoad
}

/**
 * Loads a game off the main thread: the song's last chart from app storage, read back by the runtime's `.osu` parser,
 * and the song decoded to stereo at its own rate (or the sound kept from the game before). Work nobody waits for, such
 * as a song's preparation, stops first ([ChartMaker.quiet]), so the game has the processor.
 */
class GameLoader(
    context: Context,
    private val song: Song,
    private val charts: ChartMaker,
    private val songs: SongLibrary,
    private val cache: PlayAudioCache?,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val mutableLoad = MutableStateFlow<GameLoad>(GameLoad.Loading)
    val load: StateFlow<GameLoad> = mutableLoad.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { mutableLoad.value = loadGame() }
    }

    private fun loadGame(): GameLoad {
        charts.quiet()
        val uri = song.uri ?: return GameLoad.Failed(R.string.play_failed_missing)
        val last = charts.lastChart(song.id) ?: return GameLoad.Failed(R.string.play_failed_no_chart)
        val beatmap = try {
            parseOsu(last.osu)
        } catch (failure: OsuException) {
            Log.w(TAG, "The chart of ${song.id} could not be read: ${failure.message}")
            return GameLoad.Failed(R.string.play_failed_chart)
        }
        val audio = cache?.get(song.id, uri) ?: try {
            val started = System.nanoTime()
            SongDecoder.decode(appContext, uri, PcmChannels.STEREO).also {
                Log.i(TAG, "Decoded ${song.fileName} for play in ${(System.nanoTime() - started) / 1_000_000} ms")
                cache?.put(song.id, uri, it)
            }
        } catch (failure: DecodeException) {
            if (failure.missing) songs.markMissing(song.id)
            return GameLoad.Failed(if (failure.missing) R.string.play_failed_missing else R.string.play_failed_decode)
        } catch (failure: IllegalStateException) {
            // MediaCodec's own failures.
            Log.w(TAG, "Decoding ${song.fileName} for play failed", failure)
            return GameLoad.Failed(R.string.play_failed_decode)
        }
        val chart = PlayChart.of(song.title, beatmap.chart, ceil(audio.durationMs).toInt())
        return GameLoad.Ready(chart, audio, last.record)
    }

    private companion object {
        const val TAG = "EnsomiPlay"
    }
}
