package io.ensomi.android.checks

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ensomi.android.chart.GeneratorChoices
import io.ensomi.android.chart.MakeState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Time and peak memory of making a chart of a whole song through the app's own path (plan step 5's phone check, the
 * stack report's T10), with `lowMemory` as the `lowMemory` argument says. Each run belongs in a process of its own
 * (`am instrument` once per value), so one run's memory does not count in the next.
 *
 * Arguments: `song` (a pushed file; else the first pushed song, else a synthetic four-minute song), `lowMemory`
 * (`on`, `off`, `device`), `stars` (4), `seed` (20261029).
 */
@RunWith(AndroidJUnit4::class)
class GenerationCheck {
    @Test
    fun aWholeSongThroughTheAppsPath() = runBlocking {
        val (modelDirectory, record) = installedModel() ?: run {
            PhoneCheck.report("generation", linkedMapOf("skipped" to "no model installed"))
            assumeTrue("No model installed", false)
            return@runBlocking
        }
        val song = checkSong()
        val lowMemory = checkLowMemory()
        val stars = PhoneCheck.argDouble("stars", STARS)
        val seed = PhoneCheck.arg("seed", SEED.toString()).toULong()
        val memory = PhoneCheck.memoryInfo()
        val before = PhoneCheck.thermal()
        val run = generateThroughTheApp(
            song, modelDirectory, record?.revision ?: "unknown", lowMemory, stars, seed,
            File(PhoneCheck.context.cacheDir, "generation-check"),
        )
        val made = run.state as? MakeState.Made
        val starts = run.phaseStartMs
        fun span(from: String, to: String): Double? {
            val a = starts[from] ?: return null
            val b = starts[to] ?: return null
            return PhoneCheck.round((b - a) / 1000, 2)
        }
        PhoneCheck.report(
            "generation",
            linkedMapOf(
                "song" to song.name,
                "synthetic" to song.synthetic,
                "songBytes" to song.file.length(),
                "songSha256" to PhoneCheck.sha256(song.file),
                "lowMemory" to lowMemory,
                "lowMemoryArg" to PhoneCheck.arg("lowMemory", "device"),
                "deviceChoosesLowMemory" to GeneratorChoices.lowMemory(memory.totalMem, PhoneCheck.lowRamDevice()),
                "totalMemGiB" to PhoneCheck.round(memory.totalMem / 1073741824.0, 2),
                "stars" to stars,
                "seed" to seed.toString(),
                "result" to run.state.javaClass.simpleName,
                "failure" to (run.state as? MakeState.Failed)?.message,
                "notes" to made?.chart?.noteCount,
                "holds" to made?.chart?.holdCount,
                "modelOpenMs" to PhoneCheck.round(run.modelOpenMs, 1),
                // Loading is the song's SHA-256 and its decoding; timing is the runtime's prepare (most of the work).
                "decodeSeconds" to span("loading", "timing"),
                "prepareSeconds" to span("timing", "rows"),
                "chartSeconds" to span("rows", "done"),
                "totalSeconds" to PhoneCheck.round(run.totalMs / 1000, 2),
                "rssBeforeMB" to PhoneCheck.mb(run.rssBefore),
                "peakRssMB" to PhoneCheck.mb(run.peakRss),
                "peakRssByPhaseMB" to run.peakRssByPhase.mapValues { PhoneCheck.mb(it.value) },
                "minAvailMB" to PhoneCheck.mb(run.minAvailBytes),
                "systemLowMemorySeen" to run.systemLowMemorySeen,
                "osuSha256" to run.osu?.let { PhoneCheck.sha256(it.toByteArray()) },
                "thermalBefore" to before,
                "thermalAfter" to PhoneCheck.thermal(),
                "ok" to (made != null),
            ),
        )
        assertTrue("No chart: ${run.state}", made != null)
    }

    companion object {
        const val STARS = 4.0
        const val SEED = 20261029uL
    }
}

/**
 * The same song, stars, seed and `lowMemory` make the same chart: two makers, each preparing the song afresh, must give
 * the same `.osu`. Its SHA-256 goes to the report so phones and the emulator can be compared too.
 */
@RunWith(AndroidJUnit4::class)
class DeterminismCheck {
    @Test
    fun theSameRequestMakesTheSameChart() = runBlocking {
        val (modelDirectory, record) = installedModel() ?: run {
            PhoneCheck.report("determinism", linkedMapOf("skipped" to "no model installed"))
            assumeTrue("No model installed", false)
            return@runBlocking
        }
        val song = checkSong()
        val lowMemory = checkLowMemory()
        val stars = PhoneCheck.argDouble("stars", GenerationCheck.STARS)
        val seed = PhoneCheck.arg("seed", GenerationCheck.SEED.toString()).toULong()
        val scratch = File(PhoneCheck.context.cacheDir, "determinism-check")
        val revision = record?.revision ?: "unknown"
        val first = generateThroughTheApp(song, modelDirectory, revision, lowMemory, stars, seed, File(scratch, "a"))
        val second = generateThroughTheApp(song, modelDirectory, revision, lowMemory, stars, seed, File(scratch, "b"))
        scratch.deleteRecursively()
        val a = first.osu?.let { PhoneCheck.sha256(it.toByteArray()) }
        val b = second.osu?.let { PhoneCheck.sha256(it.toByteArray()) }
        PhoneCheck.report(
            "determinism",
            linkedMapOf(
                "song" to song.name,
                "synthetic" to song.synthetic,
                "songSha256" to PhoneCheck.sha256(song.file),
                "modelRevision" to revision,
                "manifestSha256" to record?.manifestSha256,
                "lowMemory" to lowMemory,
                "stars" to stars,
                "seed" to seed.toString(),
                "firstOsuSha256" to a,
                "secondOsuSha256" to b,
                "notes" to (first.state as? MakeState.Made)?.chart?.noteCount,
                "firstSeconds" to PhoneCheck.round(first.totalMs / 1000, 2),
                "secondSeconds" to PhoneCheck.round(second.totalMs / 1000, 2),
                "same" to (a != null && a == b),
                "ok" to (a != null && a == b),
            ),
        )
        assertNotNull("No chart: ${first.state}", a)
        assertEquals("The same request made another chart", a, b)
    }
}
