package io.ensomi.android.checks

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ensomi.android.audio.PcmChannels
import io.ensomi.android.audio.SongDecoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compares every decoded float bit with the original decoder, without playback. */
@RunWith(AndroidJUnit4::class)
class DecoderCorpusCheck {
    @Test fun compare() {
        assertFalse(PhoneCheck.debuggable)
        val file = checkSong().file
        val before = PhoneCheck.thermal()
        assertEquals(0, before["thermalStatus"])
        val channels = PcmChannels.valueOf(PhoneCheck.arg("channels", "MONO"))
        val start = System.nanoTime()
        val a = ReferenceDecoder.decode(PhoneCheck.context, Uri.fromFile(file), channels)
        val middle = System.nanoTime()
        val b = SongDecoder.decode(PhoneCheck.context, Uri.fromFile(file), channels)
        val end = System.nanoTime()
        var unequal = 0
        var maximum = 0.0
        for (i in 0 until minOf(a.samples.size, b.samples.size)) {
            if (a.samples[i].toRawBits() != b.samples[i].toRawBits()) unequal++
            maximum = maxOf(maximum, kotlin.math.abs(a.samples[i].toDouble()-b.samples[i]))
        }
        val metrics = linkedMapOf<String, Any?>(
            "referenceSeconds" to (middle-start)/1e9, "candidateSeconds" to (end-middle)/1e9,
            "channels" to channels.count, "samplesA" to a.samples.size, "samplesB" to b.samples.size, "unequalSamples" to unequal, "maxError" to maximum,
            "pcmA" to pcmHash(a.samples), "pcmB" to pcmHash(b.samples), "sampleRate" to a.sampleRate, "lead" to a.leadFrames,
            "header" to a.header.toString(), "codec" to a.codec, "audioSha256" to PhoneCheck.sha256(file),
            "thermalBefore" to before, "thermalAfter" to PhoneCheck.thermal())
        PhoneCheck.writeResult("decoder-corpus-${PhoneCheck.label}.json", io.ensomi.android.json.Json.encode(metrics))
        PhoneCheck.report("decoder-corpus", metrics)
        assertEquals(a.sampleRate, b.sampleRate)
        assertEquals(a.leadFrames, b.leadFrames)
        assertEquals(a.samples.size, b.samples.size)
        assertEquals(0, unequal)
    }
}
