package io.ensomi.android.checks

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ensomi.android.audio.PcmChannels
import io.ensomi.android.audio.SongDecoder
import io.ensomi.android.chart.*
import io.ensomi.android.library.Song
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises model-scope cleanup through cancellation, retry and the prepared-song cache, without playback. */
@RunWith(AndroidJUnit4::class)
class ChartLifetimeCheck {
    @Test fun lifetime(): Unit = runBlocking {
        assertFalse(PhoneCheck.debuggable)
        val file = checkSong().file
        val audio = SongDecoder.decode(PhoneCheck.context, Uri.fromFile(file), PcmChannels.MONO)
        val hash = PhoneCheck.sha256(file)
        val installed = installedModel()!!
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val id = "lifetime-song"
        val songs = OneSongLibrary(Song(id, "Check audio", null, 0, "Check audio", uri = Uri.fromFile(file)))
        val model = InstalledModelStore(installed.first, installed.second!!.revision)
        val scratch = File(PhoneCheck.context.cacheDir, "chart-lifetime-${PhoneCheck.label}")
        require(!scratch.exists())
        val store = ChartStore(File(scratch, "charts"))
        val request = ChartRequest(stars=4.0, seed=20261029uL, sections=emptyList())
        val maker = RuntimeChartMaker(PhoneCheck.context, scope, songs, model, store, false)
        suspend fun finish(): MakeState = withTimeout(90_000) {
            maker.states.first { states -> states[id].let { it is MakeState.Made || it is MakeState.Stopped || it is MakeState.Failed } }.getValue(id)
        }
        try {
            withTimeout(90_000) { maker.capabilities.filterNotNull().first() }
            maker.makeFromDecoded(id, request, audio, hash)
            withTimeout(90_000) { maker.states.first { (it[id] as? MakeState.Making)?.phase == MakePhase.TIMING } }
            delay(PhoneCheck.argInt("cancelDelayMs", 2_000).toLong())
            val preparationStopped = System.nanoTime()
            maker.stop(id)
            assertTrue(finish() is MakeState.Stopped)
            val preparationStopSeconds = (System.nanoTime() - preparationStopped) / 1e9
            assertTrue("Preparation cancellation took $preparationStopSeconds seconds", preparationStopSeconds < 5.0)
            assertNull(store.osu(id))
            maker.makeFromDecoded(id, request, audio, hash)
            withTimeout(90_000) { maker.states.first { (it[id] as? MakeState.Making)?.phase == MakePhase.ROWS } }
            delay(75)
            val chartStopped = System.nanoTime()
            maker.stop(id)
            assertTrue(finish() is MakeState.Stopped)
            val chartStopSeconds = (System.nanoTime() - chartStopped) / 1e9
            assertTrue("Chart cancellation took $chartStopSeconds seconds", chartStopSeconds < 5.0)
            assertNull(store.osu(id))
            maker.makeFromDecoded(id, request, audio, hash)
            assertTrue(finish() is MakeState.Made)
            val first = store.osu(id)!!
            maker.makeFromDecoded(id, request, audio, hash)
            assertTrue(finish() is MakeState.Made)
            assertEquals(first, store.osu(id))
            val metrics = mapOf("cancelledPreparation" to true, "cancelledChart" to true,
                "preparationStopSeconds" to preparationStopSeconds, "chartStopSeconds" to chartStopSeconds, "retryMade" to true, "cachedChartIdentical" to true,
                "osuSha256" to PhoneCheck.sha256(first.toByteArray()), "thermalAfter" to PhoneCheck.thermal())
            PhoneCheck.writeResult("chart-lifetime-${PhoneCheck.label}.json", io.ensomi.android.json.Json.encode(metrics))
            PhoneCheck.report("chart-lifetime", metrics)
        } finally { scope.cancel() }
    }
}
