package io.ensomi.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ensomi_runtime_ffi.ChartGenerator
import uniffi.ensomi_runtime_ffi.ExecutionProvider
import uniffi.ensomi_runtime_ffi.GenerateChartRequest
import uniffi.ensomi_runtime_ffi.GeneratorOptions
import uniffi.ensomi_runtime_ffi.parseOsu
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * The contract every client shares: the same model, PCM, star and seed produce the same chart, and the
 * request's star and seed are the ones the chart records. It runs on the device because that is where the
 * model and the graphs are, and it uses a synthesized signal so the check depends on nothing else.
 */
@RunWith(AndroidJUnit4::class)
class ChartDeterminismTest {
    @Test
    fun theSameInputAndSeedGiveTheSameChart() {
        withGenerator { generator ->
            val first = generate(generator, seed = 7uL)
            val second = generate(generator, seed = 7uL)

            assertEquals("the same seed gives the same .osu", first.osu, second.osu)
            assertEquals(first.chart.objects.size, second.chart.objects.size)
            assertEquals(first.receipt.seed, second.receipt.seed)

            // The generated beatmap has to survive the parser the other clients read it with. The
            // object count matches exactly; the duration comes back a few milliseconds short because the
            // parser derives it from the beatmap's timed content, so it is compared with a tolerance
            // rather than byte for byte.
            val parsed = parseOsu(first.osu)
            assertEquals(first.chart.objects.size, parsed.chart.objects.size)
            assertTrue(
                "duration ${parsed.chart.durationMs} should stay within 100 ms of ${first.chart.durationMs}",
                kotlin.math.abs(parsed.chart.durationMs.toLong() - first.chart.durationMs.toLong()) <= 100L,
            )
        }
    }

    @Test
    fun anotherSeedMakesAnotherChart() {
        withGenerator { generator ->
            val first = generate(generator, seed = 7uL)
            val other = generate(generator, seed = 8uL)

            assertNotEquals("a different seed has to change the chart", first.osu, other.osu)
            assertEquals(7uL, first.receipt.seed)
            assertEquals(8uL, other.receipt.seed)
        }
    }

    @Test
    fun theRequestedStarIsTheOneTheChartRecords() {
        withGenerator { generator ->
            val range = generator.capabilities().let { it.minStar..it.maxStarExclusive }
            val requested = (range.start + range.endInclusive) / 2
            val result = generate(generator, seed = 1uL, star = requested)

            assertEquals(requested, result.receipt.star, 1e-9)
        }
    }

    private fun withGenerator(block: (ChartGenerator) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.getExternalFilesDir(null), "model")
        assertTrue("the model package is installed in ${model.absolutePath}", File(model, "ensomi-package.json").isFile)
        ChartGenerator.open(model.absolutePath, GeneratorOptions(provider = ExecutionProvider.CPU)).use(block)
    }

    private fun generate(
        generator: ChartGenerator,
        seed: ULong,
        star: Double = generator.capabilities().minStar,
    ) = generator.generate(
        pcm = pcm().toList(),
        sampleRate = SAMPLE_RATE.toDouble(),
        request = GenerateChartRequest(
            star = star,
            seed = seed,
            title = "Determinism",
            artist = "Ensomi",
            audioName = "check.wav",
        ),
        listener = null,
        cancellation = null,
    )

    /** A fixed signal: a tone with a pulse every half second, so beats exist and the result is stable. */
    private fun pcm(): FloatArray {
        val samples = FloatArray(SECONDS * SAMPLE_RATE)
        for (index in samples.indices) {
            val time = index.toDouble() / SAMPLE_RATE
            val beat = if (index % (SAMPLE_RATE / 2) < 400) 0.6 else 0.0
            samples[index] = (0.25 * sin(2 * PI * 220.0 * time) + beat * sin(2 * PI * 880.0 * time)).toFloat()
        }
        return samples
    }

    private companion object {
        const val SECONDS = 6
        const val SAMPLE_RATE = 22_050
    }
}
