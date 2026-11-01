package io.ensomi.android.checks

/** Untimed evidence: the float32 little-endian samples sent to the runtime, before resampling. */
internal fun pcmHash(samples: FloatArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (sample in samples) {
        bytes.putFloat(sample)
        if (!bytes.hasRemaining()) {
            digest.update(bytes.array())
            bytes.clear()
        }
    }
    digest.update(bytes.array(), 0, bytes.position())
    return digest.digest().joinToString("") { "%02x".format(it) }
}
