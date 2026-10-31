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
    private val out: FileOutputStream

    init {
        file.parentFile?.mkdirs()
        seq = if (file.isFile) file.useLines { lines -> lines.count().toLong() } else 0
        out = FileOutputStream(file, true)
    }

    @Synchronized
    fun log(event: String, fields: Map<String, Any?>) {
        val line = LinkedHashMap<String, Any?>(fields.size + 4)
        line["elapsed_ns"] = elapsedNanos()
        line["cid"] = cid
        line["seq"] = seq++
        line["event"] = event
        line.putAll(fields)
        out.write((Json.encode(line) + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    @Synchronized
    fun close() {
        out.close()
    }
}
