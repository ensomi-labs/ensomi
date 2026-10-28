package io.ensomi.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ensomi.android.audio.toLeBytes
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ensomi_runtime_ffi.ChartGenerator
import uniffi.ensomi_runtime_ffi.ExecutionProvider
import uniffi.ensomi_runtime_ffi.GenerateChartRequest
import uniffi.ensomi_runtime_ffi.GeneratorOptions
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * What a full-length song costs. The signal is three minutes of a beat with a tone over it, sent the way the
 * client sends it - as little-endian `f32` bytes - so the measurement is the one a user waits for, not a
 * harness's own idea of the work.
 */
@RunWith(AndroidJUnit4::class)
class LongGenerationTest {
    @Test
    fun threeMinutesAreGeneratedInOneRun() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.getExternalFilesDir(null), "model")
        assertTrue("the model package is installed", File(model, "ensomi-package.json").isFile)

        val samples = pcm(SECONDS)
        val bytes = samples.toLeBytes()
        assertTrue("three minutes are ${bytes.size / 1024} KiB of samples", bytes.size == SECONDS * SAMPLE_RATE * 4)

        ChartGenerator.open(model.absolutePath, GeneratorOptions(provider = ExecutionProvider.CPU)).use { generator ->
            val startedAt = System.currentTimeMillis()
            val result = generator.generatePcmF32(
                pcmLeBytes = bytes,
                sampleRate = SAMPLE_RATE.toDouble(),
                request = GenerateChartRequest(
                    star = generator.capabilities().minStar,
                    seed = 2uL,
                    title = "Long",
                    artist = "Ensomi",
                    audioName = "long.wav",
                ),
                listener = null,
                cancellation = null,
            )
            val elapsed = System.currentTimeMillis() - startedAt

            // The numbers are the point of the test: they are printed so a run leaves a record of what a
            // full song costs on this device.
            println(
                "LONG_GENERATION seconds=$SECONDS objects=${result.chart.objects.size} " +
                    "bytes=${bytes.size} elapsedMs=$elapsed",
            )

            assertTrue("a three-minute song produces a chart", result.chart.objects.isNotEmpty())
            assertTrue("the chart ends near the song's end", result.chart.durationMs.toInt() > SECONDS * 1000 - 5_000)
        }
    }

    private fun pcm(seconds: Int): FloatArray {
        val samples = FloatArray(seconds * SAMPLE_RATE)
        for (index in samples.indices) {
            val time = index.toDouble() / SAMPLE_RATE
            val beat = if (index % (SAMPLE_RATE / 2) < 300) 0.7 else 0.0
            val bass = 0.3 * sin(2 * PI * 110.0 * time)
            samples[index] = (0.2 * sin(2 * PI * 440.0 * time) + bass + beat * sin(2 * PI * 1760.0 * time)).toFloat()
        }
        return samples
    }

    private companion object {
        const val SECONDS = 181
        const val SAMPLE_RATE = 22_050
    }
}
