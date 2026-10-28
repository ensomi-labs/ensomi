package io.ensomi.android.generation

import android.content.Context
import android.net.Uri
import android.util.Log
import io.ensomi.android.audio.AudioDecoder

import io.ensomi.android.audio.DecodedAudio
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
    ): GeneratedChart = generate(
        open = { AudioDecoder.decodeMono(context, uri) },
        requestedStar = requestedStar,
        seed = seed,
        title = title,
        artist = artist,
        audioName = audioName,
        onPhase = onPhase,
    )

    /** Generates from a file the app can already read, which is what a cached take or a test holds. */
    fun generate(
        file: File,
        requestedStar: Double,
        seed: ULong = 0uL,
        title: String? = null,
        artist: String? = null,
        audioName: String? = null,
        onPhase: (GenerationPhase) -> Unit = {},
    ): GeneratedChart = generate(
        open = { AudioDecoder.decodeMono(file) },
        requestedStar = requestedStar,
        seed = seed,
        title = title,
        artist = artist,
        audioName = audioName,
        onPhase = onPhase,
    )

    private fun generate(
        open: () -> DecodedAudio,
        requestedStar: Double,
        seed: ULong,
        title: String?,
        artist: String?,
        audioName: String?,
        onPhase: (GenerationPhase) -> Unit,
    ): GeneratedChart {
        val audio = open()
        val directory = modelDirectory()
        check(isModelInstalled()) { "the model is not installed in ${directory.absolutePath}" }

        val startedAtMs = System.currentTimeMillis()
        ChartGenerator.open(directory.absolutePath, GeneratorOptions(provider = ExecutionProvider.CPU)).use { generator ->
            // The accepted range comes from the model, not from the page: star is clamped into it rather
            // than guessed, and the value used is reported back.
            val capabilities = generator.capabilities()
            val star = requestedStar.coerceIn(
                capabilities.minStar,
                Math.nextDown(capabilities.maxStarExclusive),
            )

            // The decoder already produced little-endian bytes: a List<Float> would box one object per
            // sample, which is four seconds of audio per megabyte of heap, and a FloatArray beside the bytes
            // would double the peak on a full song.
            val result = generator.generatePcmF32(
                pcmLeBytes = audio.leBytes,
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

            Log.i(
                "EnsomiGenerate",
                "generated ${result.chart.objects.size} notes from ${audio.durationMs} ms of audio in " +
                    "${System.currentTimeMillis() - startedAtMs} ms (star %.1f, seed %d)".format(star, result.receipt.seed.toLong()),
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
