package io.ensomi.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ensomi_runtime_ffi.ChartGenerator
import uniffi.ensomi_runtime_ffi.ExecutionProvider
import uniffi.ensomi_runtime_ffi.GenerateChartRequest
import uniffi.ensomi_runtime_ffi.GeneratorOptions
import uniffi.ensomi_runtime_ffi.exportOsz
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * S3's `.osz` output: the archive the runtime builds, not one each client zips itself. The check reads the
 * entries back, so it fails if the archive is not a zip, if a name is missing, or if the `.osu` inside is
 * not the chart that was generated.
 */
@RunWith(AndroidJUnit4::class)
class OszArchiveTest {
    @Test
    fun theArchiveHoldsTheChartTheAudioAndTheReceipt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.getExternalFilesDir(null), "model")
        assertTrue("the model package is installed", File(model, "ensomi-package.json").isFile)

        ChartGenerator.open(model.absolutePath, GeneratorOptions(provider = ExecutionProvider.CPU)).use { generator ->
            val result = generator.generate(
                pcm = pcm().toList(),
                sampleRate = SAMPLE_RATE.toDouble(),
                request = GenerateChartRequest(
                    star = generator.capabilities().minStar,
                    seed = 3uL,
                    title = "Archive",
                    artist = "Ensomi",
                    audioName = "check.wav",
                ),
                listener = null,
                cancellation = null,
            )

            val audio = ByteArray(4_096) { (it % 251).toByte() }
            val archive = exportOsz(result.osu, audio, result.receipt.receiptJson)

            assertTrue("an archive starts with the zip signature", archive.size > 4 && archive[0] == 'P'.code.toByte() && archive[1] == 'K'.code.toByte())

            val entries = mutableMapOf<String, Int>()
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    var bytes = 0
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        bytes += read
                    }
                    entries[entry.name] = bytes
                }
            }

            assertEquals("three entries: the chart, the audio and the receipt", 3, entries.size)
            assertTrue("the chart is inside", entries.keys.any { it.endsWith(".osu") })
            // The archive names the audio the way the `.osu` does: `audio.<extension>` from the request's
            // audio name, so the extension is what survives, not the source file name.
            val audioEntry = entries.entries.firstOrNull { it.key.endsWith(".wav") }
            assertTrue("the audio entry is named from the chart: ${entries.keys}", audioEntry != null)
            assertEquals("the audio is stored whole", audio.size, audioEntry!!.value)
            assertTrue("the receipt is inside", entries.containsKey("receipt.json"))

            // The archive is deterministic: the same inputs give the same bytes.
            val again = exportOsz(result.osu, audio, result.receipt.receiptJson)
            assertTrue("two builds of the same inputs agree", archive.contentEquals(again))
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
