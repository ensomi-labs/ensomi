package io.ensomi.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ensomi.android.audio.toLeBytes
import org.junit.Assert.assertEquals
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
 * The bytes path and the list path are the same generation: a client that hands the generator little-endian
 * `f32` bytes must get the chart the boxed list produces, or the cheaper transport would change the output.
 */
@RunWith(AndroidJUnit4::class)
class PcmBytesPathTest {
    @Test
    fun bytesAndListGiveTheSameChart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.getExternalFilesDir(null), "model")
        assertTrue("the model package is installed", File(model, "ensomi-package.json").isFile)

        val samples = pcm()
        ChartGenerator.open(model.absolutePath, GeneratorOptions(provider = ExecutionProvider.CPU)).use { generator ->
            val request = GenerateChartRequest(
                star = generator.capabilities().minStar,
                seed = 11uL,
                title = "Bytes",
                artist = "Ensomi",
                audioName = "check.wav",
            )

            val fromList = generator.generate(
                pcm = samples.toList(),
                sampleRate = SAMPLE_RATE.toDouble(),
                request = request,
                listener = null,
                cancellation = null,
            )
            val fromBytes = generator.generatePcmF32(
                pcmLeBytes = samples.toLeBytes(),
                sampleRate = SAMPLE_RATE.toDouble(),
                request = request,
                listener = null,
                cancellation = null,
            )

            assertEquals("the transport must not change the chart", fromList.osu, fromBytes.osu)
            assertEquals(fromList.chart.objects.size, fromBytes.chart.objects.size)
            assertEquals(fromList.receipt.seed, fromBytes.receipt.seed)
        }
    }

    private fun pcm(): FloatArray {
        val samples = FloatArray(4 * SAMPLE_RATE)
        for (index in samples.indices) {
            val time = index.toDouble() / SAMPLE_RATE
            val beat = if (index % (SAMPLE_RATE / 2) < 400) 0.6 else 0.0
            samples[index] = (0.25 * sin(2 * PI * 220.0 * time) + beat * sin(2 * PI * 880.0 * time)).toFloat()
        }
        return samples
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
    }
}
