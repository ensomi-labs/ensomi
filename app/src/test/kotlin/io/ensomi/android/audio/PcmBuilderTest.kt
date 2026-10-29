package io.ensomi.android.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmBuilderTest {
    private fun shorts(vararg values: Int): ByteBuffer =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putShort(it.toShort()) }
            flip()
        }

    private fun floats(vararg values: Float): ByteBuffer =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putFloat(it) }
            flip()
        }

    @Test
    fun monoIsTheMeanOfTheChannelsAndStereoKeepsThem() {
        val mono = PcmBuilder(channels = 1, estimatedFrames = 1)
        mono.add(shorts(16_384, 0, -32_768, -32_768), AudioFormat.ENCODING_PCM_16BIT, fileChannels = 2, skipFrames = 0)
        assertArrayEquals(floatArrayOf(0.25f, -1f), mono.build(), 1e-6f)

        val stereo = PcmBuilder(channels = 2, estimatedFrames = 8)
        stereo.add(floats(0.5f, -0.5f), AudioFormat.ENCODING_PCM_FLOAT, fileChannels = 2, skipFrames = 0)
        stereo.add(floats(0.25f), AudioFormat.ENCODING_PCM_FLOAT, fileChannels = 1, skipFrames = 0)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f, 0.25f, 0.25f), stereo.build(), 0f)
    }

    @Test
    fun aLeadIsSilenceFirstAndATrimSpansBuffers() {
        val lead = PcmBuilder(channels = 1, estimatedFrames = 2)
        lead.addSilence(3)
        lead.add(floats(1f), AudioFormat.ENCODING_PCM_FLOAT, fileChannels = 1, skipFrames = 0)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), lead.build(), 0f)

        val trim = PcmBuilder(channels = 1, estimatedFrames = 4)
        var left = trim.add(floats(1f, 2f), AudioFormat.ENCODING_PCM_FLOAT, fileChannels = 1, skipFrames = 3)
        assertEquals(1L, left)
        left = trim.add(floats(3f, 4f, 5f), AudioFormat.ENCODING_PCM_FLOAT, fileChannels = 1, skipFrames = left)
        assertEquals(0L, left)
        assertArrayEquals(floatArrayOf(4f, 5f), trim.build(), 0f)
    }

    @Test
    fun otherEncodingsConvertToFloat() {
        val builder = PcmBuilder(channels = 1, estimatedFrames = 4)
        val packed = ByteBuffer.wrap(byteArrayOf(0, 0, 0x40, 0, 0, 0x80.toByte()))
        builder.add(packed, AudioFormat.ENCODING_PCM_24BIT_PACKED, fileChannels = 1, skipFrames = 0)
        builder.add(ByteBuffer.wrap(byteArrayOf(0x80.toByte(), 0)), AudioFormat.ENCODING_PCM_8BIT, fileChannels = 1, skipFrames = 0)
        assertArrayEquals(floatArrayOf(0.5f, -1f, 0f, -1f), builder.build(), 1e-6f)
    }
}
