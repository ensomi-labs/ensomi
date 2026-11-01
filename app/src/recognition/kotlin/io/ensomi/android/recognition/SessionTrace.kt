package io.ensomi.android.recognition

import io.ensomi.android.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * One session's trace: a JSONL file, every line with `elapsed_ns` (`SystemClock.elapsedRealtimeNanos`), `cid`, `seq` and
 * `event` first, then the event's fields. It is written and flushed line by line, so a crash loses nothing, and picked
 * up where it stopped after a process death. This file is what is read after a test, so what the session saw and
 * decided goes here, not only to the log. It stays on the device until someone pulls it.
 */
class SessionTrace(val file: File, val cid: String, private val elapsedNanos: () -> Long) {
    private var seq: Long
    private val out: FileOutputStream?
    private var bytes = file.length()
    private var closed = false

    init {
        file.parentFile?.mkdirs()
        seq = runCatching { if (file.isFile) file.useLines { lines -> lines.count().toLong() } else 0 }.getOrDefault(0)
        out = runCatching { FileOutputStream(file, true) }.getOrNull()
    }

    @Synchronized
    fun log(event: String, fields: Map<String, Any?>) {
        if (closed || out == null || bytes >= MAX_BYTES) return
        val line = LinkedHashMap<String, Any?>(fields.size + 4)
        line["elapsed_ns"] = elapsedNanos()
        line["cid"] = cid
        line["seq"] = seq++
        line["event"] = event
        line.putAll(fields)
        val encoded = (Json.encode(line) + "\n").toByteArray(Charsets.UTF_8)
        if (bytes + encoded.size > MAX_BYTES) return
        try {
            out.write(encoded)
            bytes += encoded.size
            out.flush()
        } catch (_: java.io.IOException) {
            close()
        }
    }

    @Synchronized
    fun close() {
        if (!closed) runCatching { out?.close() }
        closed = true
    }
    private companion object { const val MAX_BYTES = 1_048_576L }
}
