package io.ensomi.android.checks

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ensomi.android.audio.PcmChannels
import io.ensomi.android.audio.SongDecoder
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic files only; neither decoding nor cancellation constructs an output track. */
@RunWith(AndroidJUnit4::class)
class FormatParityCheck {
    @Test fun compare() {
        assertFalse(PhoneCheck.debuggable)
        val directory = File(PhoneCheck.outDir, "decoder-formats")
        val files = directory.listFiles()!!.filter { it.extension in setOf("wav", "flac", "ogg", "opus", "m4a", "mp3", "aac") }.sortedBy { it.name }
        assertTrue(files.size >= 7)
        val rows = mutableListOf<Map<String, Any?>>()
        for (file in files) for (channels in PcmChannels.entries) {
            val a = ReferenceDecoder.decode(PhoneCheck.context, Uri.fromFile(file), channels)
            val b = SongDecoder.decode(PhoneCheck.context, Uri.fromFile(file), channels)
            val ah = pcmHash(a.samples); val bh = pcmHash(b.samples)
            rows += mapOf("file" to file.name, "channels" to channels.count, "codec" to b.codec,
                "samplesA" to a.samples.size, "samplesB" to b.samples.size, "pcmA" to ah, "pcmB" to bh)
            assertEquals(file.name, a.sampleRate, b.sampleRate)
            assertEquals(file.name, a.leadFrames, b.leadFrames)
            assertEquals(file.name, ah, bh)
        }
        val mp3 = files.first { it.extension == "mp3" }
        for (stopAt in listOf(1, 6, 100, 150)) {
            var calls = 0
            try {
                SongDecoder.decode(PhoneCheck.context, Uri.fromFile(mp3), PcmChannels.MONO) { ++calls >= stopAt }
                fail("Cancellation did not stop decoding")
            } catch (expected: CancellationException) { assertEquals(stopAt, calls) }
        }
        val metrics = mapOf("formats" to rows, "cancelChecks" to 4, "thermalAfter" to PhoneCheck.thermal())
        PhoneCheck.writeResult("decoder-formats-${PhoneCheck.label}.json", io.ensomi.android.json.Json.encode(metrics))
        PhoneCheck.report("decoder-formats", metrics)
    }
}
