package io.ensomi.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ensomi.android.generation.ChartGenerationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ensomi_runtime_ffi.GenerationPhase
import java.io.File
import java.util.Collections

/**
 * S3's real path, end to end and headless: decode a three-minute mp3 the way the picker hands it over, then
 * generate. The picker and the taps are the only parts this skips, so a regression in the decoder or in the
 * service shows up here instead of on the device.
 */
@RunWith(AndroidJUnit4::class)
class LongSongServiceTest {
    @Test
    fun aThreeMinuteMp3IsDecodedAndCharted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "fish-3min.mp3")
        assertTrue("the track is staged at ${file.absolutePath}", file.isFile)

        val service = ChartGenerationService(context)
        assertTrue("the model package is installed", service.isModelInstalled())

        val phases: MutableList<GenerationPhase> = Collections.synchronizedList(mutableListOf())
        val startedAt = System.currentTimeMillis()
        val generated = service.generate(
            file = file,
            requestedStar = service.starRange()?.start ?: 2.0,
            seed = 3uL,
            title = "Long song",
            artist = "Ensomi",
            audioName = file.name,
            onPhase = { phases += it },
        )
        val elapsed = System.currentTimeMillis() - startedAt

        println(
            "SERVICE_LONG_PATH objects=${generated.chart.objects.size} taps=${generated.taps} " +
                "holds=${generated.holds} durationMs=${generated.chart.durationMs} elapsedMs=$elapsed " +
                "phases=${phases.map { it.name }}",
        )

        assertEquals("the song's length survives the decode", 181_000.0, generated.chart.durationMs.toDouble(), 2_000.0)
        assertTrue("the chart has notes", generated.chart.objects.isNotEmpty())
        assertTrue("the chart fits the song", generated.chart.durationMs.toLong() >= 176_000L)
        assertEquals("every phase reached the page", GenerationPhase.entries.toSet(), phases.toSet())
    }
}
