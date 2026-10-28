package io.ensomi.android.diagnostics

import java.io.File

/** Reads each core's maximum frequency from sysfs, best effort: an unreadable core gets null. */
object CpuCores {
    val sysfsRoot = File("/sys/devices/system/cpu")

    fun read(root: File = sysfsRoot): List<CpuCore> =
        coreIndices(root).map { index ->
            CpuCore(index, parseKiloHertz(readOrNull(File(root, "cpu$index/cpufreq/cpuinfo_max_freq"))))
        }

    /** Cores from `possible` (for example "0-7"), else from the `cpuN` directories. */
    fun coreIndices(root: File): List<Int> =
        readOrNull(File(root, "possible"))?.let(::parseCpuList)?.takeIf { it.isNotEmpty() }
            ?: root.listFiles()
                .orEmpty()
                .mapNotNull { CORE_DIRECTORY.matchEntire(it.name)?.groupValues?.get(1)?.toInt() }
                .sorted()

    /** Parses a kernel CPU list such as "0-3,6,8-9". Returns an empty list when the text is not one. */
    fun parseCpuList(text: String): List<Int> {
        val indices = sortedSetOf<Int>()
        for (part in text.trim().split(',')) {
            val bounds = part.trim().split('-')
            val first = bounds[0].toIntOrNull() ?: return emptyList()
            val last = when (bounds.size) {
                1 -> first
                2 -> bounds[1].toIntOrNull() ?: return emptyList()
                else -> return emptyList()
            }
            if (last < first) return emptyList()
            indices.addAll(first..last)
        }
        return indices.toList()
    }

    /** `cpuinfo_max_freq` holds kHz as a decimal integer. */
    fun parseKiloHertz(text: String?): Long? = text?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    private fun readOrNull(file: File): String? =
        try {
            file.readText()
        } catch (_: Exception) {
            null
        }

    private val CORE_DIRECTORY = Regex("cpu(\\d+)")
}
