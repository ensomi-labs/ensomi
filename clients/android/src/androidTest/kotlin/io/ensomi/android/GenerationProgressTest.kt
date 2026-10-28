package io.ensomi.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ensomi_runtime_ffi.ChartGenerator
import uniffi.ensomi_runtime_ffi.ExecutionProvider
import uniffi.ensomi_runtime_ffi.GenerateChartRequest
import uniffi.ensomi_runtime_ffi.GenerationListener
import uniffi.ensomi_runtime_ffi.GenerationPhase
import uniffi.ensomi_runtime_ffi.GeneratorOptions
import java.io.File
import java.util.Collections
import kotlin.math.PI
import kotlin.math.sin

/**
 * The generator reports what it is doing, and the app shows it: for a three-minute song the run is tens of
 * seconds, and a button that only says "Generating" for that long is not feedback. This checks the
 * listener is called, starts at the first phase and ends at the last.
 */
@RunWith(AndroidJUnit4::class)
class GenerationProgressTest {
    @Test
    fun everyPhaseIsReportedInOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.getExternalFilesDir(null), "model")
        assertTrue("the model package is installed", File(model, "ensomi-package.json").isFile)

        val phases: MutableList<GenerationPhase> = Collections.synchronizedList(mutableListOf())

        ChartGenerator.open(model.absolutePath, GeneratorOptions(provider = ExecutionProvider.CPU)).use { generator ->
            generator.generate(
                pcm = pcm().toList(),
                sampleRate = SAMPLE_RATE.toDouble(),
                request = GenerateChartRequest(
                    star = generator.capabilities().minStar,
                    seed = 5uL,
                    title = "Progress",
                    artist = "Ensomi",
                    audioName = "check.wav",
                ),
                listener = object : GenerationListener {
                    override fun `progress`(phase: GenerationPhase) {
                        phases += phase
                    }
                },
                cancellation = null,
            )
        }

        assertTrue("the listener heard at least one phase", phases.isNotEmpty())
        assertTrue("the run starts by loading", phases.first() == GenerationPhase.LOADING)
        assertTrue("the run ends by exporting", phases.last() == GenerationPhase.EXPORT)
        assertTrue("the phases arrive in pipeline order", phases == phases.sortedBy { it.ordinal })
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
