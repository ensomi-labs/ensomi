package io.ensomi.android.checks

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.ensomi.android.diagnostics.DiagnosticsLog
import io.ensomi.android.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

/**
 * What every phone check shares: its instrumentation arguments, where its results go, and reading files that
 * `scripts/phone-checks.sh` pushed for it.
 *
 * Each result is one JSON object with a `"check"` name, logged under [TAG] at info level (split into `part i/n` lines
 * like the Diagnostics JSON when it is longer than a logcat line) and appended to `results.jsonl` in [outDir], the
 * app's media folder on shared storage, which `adb pull` reads in any build. Larger data (every frame, every timestamp)
 * goes to CSV files beside it.
 */
object PhoneCheck {
    const val TAG = "EnsomiPhoneCheck"

    /** Where the script pushes the click tracks and songs, unless the arguments say otherwise. */
    private const val PUSHED = "/data/local/tmp/ensomi-checks"

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val arguments: Bundle get() = InstrumentationRegistry.getArguments()

    fun arg(name: String, default: String): String = arguments.getString(name)?.takeIf { it.isNotBlank() } ?: default

    fun argDouble(name: String, default: Double): Double = arguments.getString(name)?.toDoubleOrNull() ?: default

    fun argInt(name: String, default: Int): Int = arguments.getString(name)?.toIntOrNull() ?: default

    val clicksDir: String get() = arg("clicksDir", "$PUSHED/clicks")
    val songsDir: String get() = arg("songsDir", "$PUSHED/songs")

    /** A label the script gives a run, such as the output plugged in for the audio check; empty when none. */
    val label: String get() = arg("label", "")

    val debuggable: Boolean get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** `/sdcard/Android/media/io.ensomi.android/phone-checks`: the results the script pulls. */
    val outDir: File
        get() {
            val media = context.externalMediaDirs.firstOrNull { it != null } ?: context.getExternalFilesDir(null)!!
            return File(media, "phone-checks").also { it.mkdirs() }
        }

    /** Logs one result and keeps it in `results.jsonl`. */
    fun report(check: String, fields: Map<String, Any?>) {
        val line = LinkedHashMap<String, Any?>()
        line["check"] = check
        if (label.isNotEmpty()) line["label"] = label
        line["debuggable"] = debuggable
        line.putAll(fields)
        val json = Json.encode(line)
        for (piece in DiagnosticsLog.lines(json)) Log.i(TAG, piece)
        synchronized(this) { File(outDir, "results.jsonl").appendText(json + "\n") }
    }

    /** Writes a file of results, such as a CSV of every frame, beside `results.jsonl`; returns its name. */
    fun writeResult(name: String, text: String): String {
        File(outDir, name).writeText(text)
        return name
    }

    /** Runs [command] as the shell user (no shell syntax: no pipes, quotes or spaces in arguments); returns its output. */
    fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }

    /** Now, as logcat's `-T` takes a start time: seconds since the epoch with milliseconds. */
    fun logcatNow(): String {
        val ms = System.currentTimeMillis()
        return String.format(Locale.ROOT, "%d.%03d", ms / 1000, ms % 1000)
    }

    /** The raw lines logged under [tags] (`Tag:I` each) since [since] ([logcatNow]), without clearing the log. */
    fun logcatSince(since: String, vararg tags: String): List<String> =
        shell("logcat -d -v raw -T $since -s ${tags.joinToString(" ")}").lines()

    /** The files the script pushed into [dir] (names without spaces), sorted; empty when there are none. */
    fun pushedFiles(dir: String): List<String> =
        shell("ls -1 $dir").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("No such file") && !it.startsWith("ls:") }
            .sorted()

    /**
     * Copies a pushed file into the app's cache, read by the shell user (which can read `/data/local/tmp` on every
     * phone) and handed over as a stream; returns the copy.
     */
    fun copyPushed(dir: String, name: String): File {
        val target = File(File(context.cacheDir, "phone-checks").also { it.mkdirs() }, name)
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $dir/$name")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    /** The process's resident memory now, from `/proc/self/status` (VmRSS), in bytes; and its peak (VmHWM). */
    fun residentBytes(field: String = "VmRSS"): Long {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("$field:") } ?: return -1
        return (line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1) * 1024
    }

    /** The device's memory as `ActivityManager` sees it, and whether it counts as a low-RAM device. */
    fun memoryInfo(): ActivityManager.MemoryInfo {
        val activities = context.getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also { activities.getMemoryInfo(it) }
    }

    fun lowRamDevice(): Boolean = context.getSystemService(ActivityManager::class.java).isLowRamDevice

    /** The thermal status (`PowerManager.THERMAL_STATUS_*`) and the headroom forecast for 10 s, NaN where unknown. */
    fun thermal(): Map<String, Any?> {
        val power = context.getSystemService(PowerManager::class.java)
        return linkedMapOf(
            "thermalStatus" to power.currentThermalStatus,
            "thermalHeadroom10s" to power.getThermalHeadroom(10).toDouble(),
            "powerSave" to power.isPowerSaveMode,
        )
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    fun round(value: Double, places: Int = 3): Double {
        if (!value.isFinite()) return value
        var scale = 1.0
        repeat(places) { scale *= 10 }
        return Math.round(value * scale) / scale
    }

    fun mb(bytes: Long): Double = round(bytes / 1e6, 1)
}

/** Order statistics and a least-squares line, for the checks' summaries. */
object Stats {
    /** The value at quantile [q] of [sorted] (nearest rank), NaN when empty. */
    fun quantile(sorted: DoubleArray, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val index = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    fun mean(values: DoubleArray): Double = if (values.isEmpty()) Double.NaN else values.sum() / values.size

    fun sd(values: DoubleArray): Double {
        if (values.size < 2) return Double.NaN
        val m = mean(values)
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    /** Median, 99th percentile and largest of [values], rounded, under the given names' prefix. */
    fun summary(prefix: String, values: DoubleArray, places: Int = 3): Map<String, Any?> {
        val sorted = values.sortedArray()
        return linkedMapOf(
            "${prefix}P50" to PhoneCheck.round(quantile(sorted, 0.5), places),
            "${prefix}P99" to PhoneCheck.round(quantile(sorted, 0.99), places),
            "${prefix}Max" to PhoneCheck.round(sorted.lastOrNull() ?: Double.NaN, places),
        )
    }

    /** The line y = a + b·x through the points by least squares, and the residuals' standard deviation. */
    class Fit(val a: Double, val b: Double, val residualSd: Double, val maxAbsResidual: Double)

    fun fit(x: DoubleArray, y: DoubleArray): Fit? {
        val n = x.size
        if (n < 3) return null
        val mx = mean(x)
        val my = mean(y)
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            sxx += (x[i] - mx) * (x[i] - mx)
            sxy += (x[i] - mx) * (y[i] - my)
        }
        if (sxx == 0.0) return null
        val b = sxy / sxx
        val a = my - b * mx
        val residuals = DoubleArray(n) { y[it] - (a + b * x[it]) }
        return Fit(a, b, sd(residuals), residuals.maxOf { kotlin.math.abs(it) })
    }
}
