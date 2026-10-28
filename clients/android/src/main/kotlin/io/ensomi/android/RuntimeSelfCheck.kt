package io.ensomi.android

import uniffi.ensomi_runtime_ffi.ChartGenerator
import uniffi.ensomi_runtime_ffi.ExecutionProvider
import uniffi.ensomi_runtime_ffi.GeneratorOptions
import uniffi.ensomi_runtime_ffi.parseOsu
import java.io.File

/**
 * Answers two questions on the device: does the packaged runtime binding reach the native core, and can
 * the generator find ONNX Runtime behind it? Parsing needs neither a model nor ONNX Runtime, so a failure
 * there is the binding or the packaged library; the generator check names whichever of the two is missing.
 */
object RuntimeSelfCheck {
    fun run(): Result<String> = runCatching {
        val beatmap = parseOsu(BEATMAP)
        "parse_osu ok: \"${beatmap.title ?: "untitled"}\" by ${beatmap.artist ?: "unknown"}, " +
            "${beatmap.chart.objects.size} objects, ${beatmap.chart.durationMs} ms"
    }

    /** Whether the packaged ONNX Runtime can be loaded at all, before any model is involved. */
    fun loadOnnxRuntime(): Result<String> = runCatching {
        System.loadLibrary("onnxruntime")
        "onnxruntime loaded"
    }

    /**
     * Opens the generator against its model directory. With the package absent the error says whether ONNX
     * Runtime was reachable and the model was not; with the package installed it opens.
     */
    fun openGenerator(directory: File): Result<String> = runCatching {
        ChartGenerator.open(directory.absolutePath, GeneratorOptions(provider = ExecutionProvider.CPU)).use {
            "generator opened"
        }
    }

    /** The smallest chart that still exercises the parser: one note, one timing point. */
    private val BEATMAP = """
        osu file format v14

        [General]
        AudioFilename: check.mp3
        Mode: 3

        [Metadata]
        Title:Runtime Check
        Artist:Ensomi

        [Difficulty]
        CircleSize:4

        [TimingPoints]
        0,500,4,2,0,60,1,0

        [HitObjects]
        256,192,1000,1,0,0:0:0:0:
    """.trimIndent()
}
