package io.ensomi.android.diagnostics

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object CpuFeatures {
    fun read(): CpuFacts {
        val features = runCatching { File("/proc/cpuinfo").readLines().firstOrNull { it.substringBefore(':').trim() == "Features" }?.substringAfter(':')?.trim() }.getOrNull()
        val auxv = runCatching { parseAuxv(File("/proc/self/auxv").readBytes()) }.getOrDefault(emptyMap())
        val flags = features.orEmpty().split(Regex("\\s+"))
        return CpuFacts(CpuCores.read(), features, auxv[16], auxv[26], "sme" in flags, "sme2" in flags)
    }
    // The app is arm64-only. Preserve unsigned capability masks as hex strings.
    fun parseAuxv(bytes: ByteArray): Map<Long, String> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return buildMap {
            while (input.remaining() >= 16) {
                val key = input.long
                val value = input.long
                if (key == 0L) break
                if (key == 16L || key == 26L) put(key, "0x${java.lang.Long.toUnsignedString(value, 16)}")
            }
        }
    }
}
