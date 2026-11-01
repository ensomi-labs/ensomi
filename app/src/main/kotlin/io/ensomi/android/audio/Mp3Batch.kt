package io.ensomi.android.audio

import java.nio.ByteBuffer

/** Version, layer, sample rate and channel count; don't batch a format change into one codec output. */
internal fun mp3FrameFormat(buffer: ByteBuffer, offset: Int, size: Int): Int? {
    if (size < 4) return null
    val sync = buffer.get(offset).toInt() and 0xff
    val versionLayer = buffer.get(offset + 1).toInt() and 0xff
    val rate = buffer.get(offset + 2).toInt() and 0xff
    if (sync != 0xff || versionLayer and 0xe0 != 0xe0 || versionLayer and 0x18 == 0x08 ||
        versionLayer and 0x06 != 0x02 || rate and 0x0c == 0x0c) return null
    val mono = if (buffer.get(offset + 3).toInt() and 0xc0 == 0xc0) 1 else 0
    return (versionLayer and 0x1e shl 8) or (rate and 0x0c) or mono
}
