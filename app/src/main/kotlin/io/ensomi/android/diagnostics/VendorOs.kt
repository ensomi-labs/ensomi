package io.ensomi.android.diagnostics

import java.util.concurrent.TimeUnit

/**
 * The vendor OS version (HyperOS or MIUI), read from system properties through `getprop`, the shell tool
 * any app may run. Best effort: properties the app may not read are simply absent.
 */
object VendorOs {
    const val UNKNOWN = "unknown"

    /** Properties worth recording; the first three are HyperOS, the last two MIUI. */
    val PROPERTIES = listOf(
        "ro.mi.os.version.name",
        "ro.mi.os.version.code",
        "ro.mi.os.version.incremental",
        "ro.miui.ui.version.name",
        "ro.miui.ui.version.code",
    )

    /** Runs `getprop` once and keeps [PROPERTIES]; an empty map when it fails. */
    fun readProperties(): Map<String, String> =
        try {
            val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
            parseGetprop(output).filterKeys { it in PROPERTIES }
        } catch (_: Exception) {
            emptyMap()
        }

    /** Parses `getprop` output, one `[name]: [value]` per line; other lines are skipped. */
    fun parseGetprop(output: String): Map<String, String> =
        output.lineSequence()
            .mapNotNull { GETPROP_LINE.matchEntire(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** "HyperOS 3.0.307.0.WNCCNXM", "MIUI V140" or [UNKNOWN]. */
    fun label(properties: Map<String, String>): String {
        val hyperOs = listOf("ro.mi.os.version.incremental", "ro.mi.os.version.name")
            .firstNotNullOfOrNull { properties[it]?.trim()?.takeIf(String::isNotEmpty) }
        if (hyperOs != null) return "HyperOS ${hyperOs.removePrefix("OS")}"
        val miui = properties["ro.miui.ui.version.name"]?.trim()?.takeIf(String::isNotEmpty)
        if (miui != null) return "MIUI $miui"
        return UNKNOWN
    }

    private val GETPROP_LINE = Regex("""\[([^\]]+)]: \[(.*)]""")
}
