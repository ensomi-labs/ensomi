package io.ensomi.android

import uniffi.ensomi_runtime_ffi.parseOsu

/**
 * Answers one question on the device: does the packaged runtime binding reach the native core? It
 * parses a beatmap the shell spells out, which needs no model and no ONNX Runtime, so a failure here is
 * the binding or the packaged library, not the generator.
 */
object RuntimeSelfCheck {
    fun run(): Result<String> = runCatching {
        val beatmap = parseOsu(BEATMAP)
        "parse_osu ok: \"${beatmap.title ?: "untitled"}\" by ${beatmap.artist ?: "unknown"}, " +
            "${beatmap.chart.objects.size} objects, ${beatmap.chart.durationMs} ms"
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
