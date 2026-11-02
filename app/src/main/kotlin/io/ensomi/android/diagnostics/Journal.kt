package io.ensomi.android.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import io.ensomi.android.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import kotlinx.serialization.json.*

/** Process-wide, best effort, synchronous at durable boundaries. Never initialise app stores here. */
object Journal {
    private var engine: JournalEngine? = null
    var runId: String = ""
        private set

    @Synchronized fun start(context: Context) {
        if (engine != null) return
        try {
            Redact.configure(mapOf(context.filesDir.path to "<files>", context.noBackupFilesDir.path to "<nobackup>",
                context.cacheDir.path to "<cache>", context.applicationInfo.dataDir to "<files>"))
            runId = java.lang.Long.toUnsignedString(SecureRandom().nextLong(), 16).padStart(16, '0')
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val now = System.currentTimeMillis()
            val up = SystemClock.elapsedRealtime()
            val start = Process.getStartElapsedRealtime()
            engine = JournalEngine(FileJournalSink(directory(context)), runId, System::currentTimeMillis, SystemClock::elapsedRealtime)
            engine?.record("run", "start", linkedMapOf(
                "pid" to Process.myPid().toString(), "startElapsedRealtime" to start.toString(),
                "startWall" to (now - (up - start)).toString(), "wall" to now.toString(), "elapsed" to up.toString(),
                "versionName" to (info.versionName ?: "unknown"), "versionCode" to info.longVersionCode.toString(),
                "sdkIntFull" to (if (Build.VERSION.SDK_INT >= 36) Build.VERSION.SDK_INT_FULL.toString() else "unavailable"),
                "sdkInt" to Build.VERSION.SDK_INT.toString(), "incremental" to Build.VERSION.INCREMENTAL,
                "securityPatch" to Build.VERSION.SECURITY_PATCH, "fingerprint" to Build.FINGERPRINT,
                "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
            ), true)
        } catch (_: Throwable) { /* Diagnostics must never prevent process startup. */ }
    }

    @Synchronized fun record(event: String, phase: String, detail: Map<String, String> = emptyMap(), durable: Boolean = true) {
        try { engine?.record(event, phase, detail, durable) } catch (_: Throwable) { }
    }
    @Synchronized fun flush() { try { engine?.flush() } catch (_: Throwable) { } }
    @Synchronized internal fun snapshot(runs: Int = 8): List<Map<String, Any?>> =
        try { engine?.snapshot(runs).orEmpty() } catch (_: Throwable) { emptyList() }
    internal fun directory(context: Context) = File(context.noBackupFilesDir, "diagnostics")
}

internal interface JournalSink {
    fun read(): List<Map<String, Any?>>
    fun append(bytes: ByteArray, durable: Boolean)
    fun replace(records: List<Map<String, Any?>>)
    fun sync()
}

internal class JournalEngine(
    private val sink: JournalSink, private val run: String,
    private val wall: () -> Long, private val elapsed: () -> Long,
) {
    private var seq = 0L
    private val pending = StringBuilder()
    init {
        val old = sink.read()
        val keep = old.mapNotNull { it["run"] as? String }.distinct().takeLast(7).toSet()
        sink.replace(old.filter { it["run"] in keep })
    }
    @Synchronized fun record(event: String, phase: String, detail: Map<String, String>, durable: Boolean) {
        val record = linkedMapOf("v" to 1, "t" to wall(), "up" to elapsed(), "run" to run, "seq" to ++seq,
            "ev" to Redact.text(event, 80), "ph" to Redact.text(phase, 80),
            "ctx" to detail.entries.take(24).associate { Redact.text(it.key, 64) to Redact.detail(it.key, it.value).take(512) })
        pending.append(Json.encode(record)).append('\n')
        if (durable || pending.length >= 8192) writePending(durable)
    }
    private fun writePending(durable: Boolean) {
        if (pending.isNotEmpty()) {
            sink.append(pending.toString().toByteArray(Charsets.UTF_8), durable)
            pending.setLength(0)
        } else if (durable) sink.sync()
    }
    @Synchronized fun flush() = writePending(true)
    @Synchronized fun snapshot(runs: Int): List<Map<String, Any?>> {
        flush()
        val rows = sink.read()
        val ids = rows.mapNotNull { it["run"] as? String }.distinct().takeLast(runs).toSet()
        return rows.filter { it["run"] in ids }
    }
}

internal class FileJournalSink(private val directory: File) : JournalSink {
    private val current = File(directory, "journal-1.jsonl")
    private val previous = File(directory, "journal-0.jsonl")
    init { directory.mkdirs() }
    override fun read(): List<Map<String, Any?>> = (readLines(previous) + readLines(current))
        .distinctBy { it["run"] to it["seq"] }
    override fun append(bytes: ByteArray, durable: Boolean) {
        // Append one complete line at a time, so a segment never exceeds the byte limit.
        for (line in bytes.toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }) {
            val data = (line + "\n").toByteArray(Charsets.UTF_8)
            if (data.size > LIMIT) continue
            if (current.length() + data.size > LIMIT) {
                sync()
                check(!previous.exists() || previous.delete())
                check(current.renameTo(previous))
            }
            FileOutputStream(current, true).use { it.write(data); if (durable) it.fd.sync() }
        }
    }
    override fun replace(records: List<Map<String, Any?>>) {
        // Atomic per segment; a crash during pruning can lose old context, never invent a match.
        val lines = records.map { (Json.encode(it) + "\n").toByteArray() }
        val newest = mutableListOf<ByteArray>()
        val older = mutableListOf<ByteArray>()
        var size = 0
        var second = false
        for (line in lines.asReversed()) {
            if (line.size > LIMIT) continue
            if (size + line.size > LIMIT) { if (second) break; second = true; size = 0 }
            (if (second) older else newest).add(line); size += line.size
        }
        atomicWrite(previous, older.asReversed().fold(byteArrayOf()) { a, b -> a + b })
        atomicWrite(current, newest.asReversed().fold(byteArrayOf()) { a, b -> a + b })
    }
    override fun sync() { if (current.exists()) FileOutputStream(current, true).use { it.fd.sync() } }
    companion object { const val LIMIT = 64 * 1024 }
}

internal fun atomicWrite(file: File, data: ByteArray) {
    val temp = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(temp).use { it.write(data); it.fd.sync() }
    check(temp.renameTo(file))
}
internal fun readLines(file: File): List<Map<String, Any?>> = if (!file.exists()) emptyList() else parseLines(file.readText())
internal fun parseLines(text: String): List<Map<String, Any?>> = text.split('\n').dropLast(1).mapNotNull {
    runCatching {
        @Suppress("UNCHECKED_CAST")
        (kotlinx.serialization.json.Json.parseToJsonElement(it).plain() as? Map<String, Any?>)
    }.getOrNull()
}
internal fun JsonElement.plain(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> entries.associate { it.key to it.value.plain() }
    is JsonArray -> map { it.plain() }
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull
}
