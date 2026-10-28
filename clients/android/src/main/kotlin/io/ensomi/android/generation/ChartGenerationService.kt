package io.ensomi.android.generation

import android.content.Context
import android.net.Uri
import io.ensomi.android.audio.AudioDecoder
import uniffi.ensomi_runtime_ffi.Chart
import uniffi.ensomi_runtime_ffi.ChartGenerator
import uniffi.ensomi_runtime_ffi.ExecutionProvider
import uniffi.ensomi_runtime_ffi.GenerateChartRequest
import uniffi.ensomi_runtime_ffi.GenerationListener
import uniffi.ensomi_runtime_ffi.GenerationPhase
import uniffi.ensomi_runtime_ffi.GeneratorOptions
import java.io.File

/** A chart the device generated, with what S3's result panel shows. */
data class GeneratedChart(
    val chart: Chart,
    val osu: String,
    val receiptJson: String,
    val seed: ULong,
    val star: Double,
) {
    val taps: Int get() = chart.objects.count { it.endMs == null }
    val holds: Int get() = chart.objects.size - taps
}

/**
 * Runs the device engine: decode the file, ask the generator for a chart, hand back what the setup page
 * needs. It blocks and is meant to be called from a background thread; the caller owns the progress UI.
 */
class ChartGenerationService(private val context: Context) {
    fun modelDirectory(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "model")

    fun isModelInstalled(): Boolean = File(modelDirectory(), "ensomi-package.json").isFile

    /** The star range the model accepts, read once: opening the generator is not free. */
    fun starRange(): ClosedFloatingPointRange<Double>? = cachedRange ?: runCatching {
        ChartGenerator.open(modelDirectory().absolutePath, GeneratorOptions(provider = ExecutionProvider.CPU)).use {
            val capabilities = it.capabilities()
            capabilities.minStar..Math.nextDown(capabilities.maxStarExclusive)
        }
    }.getOrNull()?.also { cachedRange = it }

    private var cachedRange: ClosedFloatingPointRange<Double>? = null

    fun generate(
        uri: Uri,
        requestedStar: Double,
        seed: ULong = 0uL,
        title: String? = null,
        artist: String? = null,
        audioName: String? = null,
        onPhase: (GenerationPhase) -> Unit = {},
    ): GeneratedChart {
        val directory = modelDirectory()
        check(isModelInstalled()) { "the model is not installed in ${directory.absolutePath}" }

        ChartGenerator.open(directory.absolutePath, GeneratorOptions(provider = ExecutionProvider.CPU)).use { generator ->
            // The accepted range comes from the model, not from the page: star is clamped into it rather
            // than guessed, and the value used is reported back.
            val capabilities = generator.capabilities()
            val star = requestedStar.coerceIn(
                capabilities.minStar,
                Math.nextDown(capabilities.maxStarExclusive),
            )

            val audio = AudioDecoder.decodeMono(context, uri)
            val result = generator.generate(
                pcm = audio.samples.toList(),
                sampleRate = audio.sampleRate.toDouble(),
                request = GenerateChartRequest(
                    star = star,
                    seed = seed,
                    title = title,
                    artist = artist,
                    audioName = audioName,
                ),
                listener = object : GenerationListener {
                    override fun `progress`(phase: GenerationPhase) {
                        onPhase(phase)
                    }
                },
                cancellation = null,
            )

            return GeneratedChart(
                chart = result.chart,
                osu = result.osu,
                receiptJson = result.receipt.receiptJson,
                seed = result.receipt.seed,
                star = result.receipt.star,
            )
        }
    }
}
