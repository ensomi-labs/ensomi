package io.ensomi.android.diagnostics

import java.io.ByteArrayOutputStream

/** Raw input exists only in memory. Projection prioritizes the crashing thread over map serialization order. */
internal object TracePrivacy {
    const val INPUT_LIMIT = 8 * 1024 * 1024
    const val LIMIT = 512 * 1024

    fun sanitise(bytes: ByteArray, native: Boolean): String {
        val output = ByteArrayOutputStream()
        fun line(value: String): Boolean {
            val data = (value + "\n").toByteArray(Charsets.UTF_8)
            if (output.size() + data.size > LIMIT) return false
            output.write(data)
            return true
        }
        line("projection=2")
        line("Redacted trace; input capped at 8 MiB; output capped at 512 KiB.")
        if (native) {
            val root = fields(bytes.copyOfRange(0, minOf(bytes.size, INPUT_LIMIT)))
            val tid = root.firstOrNull { it.id == 6 }?.number
            val signal = fields(root.firstOrNull { it.id == 10 }?.bytes ?: byteArrayOf())
            val threads = root.filter { it.id == 16 }.mapNotNull { entry ->
                fields(entry.bytes).firstOrNull { it.id == 2 }?.let { fields(it.bytes) }
            }
            val crashing = threads.firstOrNull { thread -> thread.firstOrNull { it.id == 1 }?.number == tid }
            line("pid=${root.firstOrNull { it.id == 5 }?.number} tid=$tid")
            line("signal=${signal.firstOrNull { it.id == 1 }?.number} code=${signal.firstOrNull { it.id == 3 }?.number ?: 0} faultAddress=${signal.firstOrNull { it.id == 9 }?.number}")
            val abort = root.firstOrNull { it.id == 14 }?.bytes?.toString(Charsets.UTF_8)
            // Arbitrary abort/exception prose can contain unquoted song titles.
            line("abortMessage=${abort?.let { Redact.detail("message", it) } ?: "unavailable"}")
            line("threadName=${crashing?.firstOrNull { it.id == 2 }?.bytes?.toString(Charsets.UTF_8)?.let(Redact::threadName) ?: "unavailable"}")
            val ordered = listOfNotNull(crashing) + threads.filter { it !== crashing }
            for (thread in ordered) {
                val id = thread.firstOrNull { it.id == 1 }?.number
                val name = thread.firstOrNull { it.id == 2 }?.bytes?.toString(Charsets.UTF_8)?.let(Redact::threadName)
                if (!line("thread=$id name=${name ?: "unavailable"}${if (thread === crashing) " crashing" else ""}")) break
                val frames = thread.filter { it.id == 4 }.let { if (thread === crashing) it else it.take(16) }
                for (item in frames) {
                    val frame = fields(item.bytes)
                    fun text(id: Int) = frame.firstOrNull { it.id == id }?.bytes?.toString(Charsets.UTF_8)
                    val pc = (frame.firstOrNull { it.id == 1 }?.number ?: 0).toULong().toString(16)
                    val build = text(8)?.takeIf { it.matches(Regex("[0-9a-fA-F]{1,128}")) } ?: "unavailable"
                    if (!line("frame pc=0x$pc module=${module(text(6) ?: "")} buildId=$build symbol=${Redact.symbol(text(4) ?: "unavailable")}")) break
                }
            }
            if (root.any { it.id == -1 } || bytes.size >= INPUT_LIMIT)
                line("Remaining trace unavailable: truncated or unsupported protobuf.")
        } else {
            for (raw in bytes.toString(Charsets.UTF_8).lineSequence()) {
                val value = raw.trimStart()
                val header = Regex("^\"([^\"]+)\"(.*)$").matchEntire(value)
                val safe = when {
                    header != null -> "\"${Redact.threadName(header.groupValues[1])}\"${Redact.text(header.groupValues[2], 512)}"
                    value.startsWith("at ") -> Redact.text(value, 512)
                    value.startsWith("native: #") -> {
                        val frame = Regex("native: #(\\d+)\\s+pc\\s+([0-9a-fA-F]+)\\s+(\\S+)(.*)").matchEntire(value)
                        if (frame == null) Redact.text(value, 512) else {
                            val build = Regex("BuildId: ([0-9a-fA-F]+)").find(frame.groupValues[4])?.groupValues?.get(1) ?: "unavailable"
                            "native: #${frame.groupValues[1]} pc=${frame.groupValues[2]} module=${module(frame.groupValues[3])} buildId=$build symbol=${Redact.symbol(frame.groupValues[4].substringBefore("(BuildId:"))}"
                        }
                    }
                    else -> continue
                }
                if (!line(safe)) break
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun module(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('!').substringBefore(" (deleted)")
        return if (name.matches(Regex("[A-Za-z0-9_.+-]+\\.(so|apk|oat|odex|vdex|art|jar|dex)|app_process64|\\[vdso\\]"))) name else "<module>"
    }

    private data class Field(val id: Int, val number: Long = 0, val bytes: ByteArray = byteArrayOf())
    private fun fields(bytes: ByteArray): List<Field> {
        var offset = 0
        fun varint(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(offset < bytes.size)
                val b = bytes[offset++].toInt() and 255
                result = result or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return result
            }
            error("varint")
        }
        return buildList {
            while (offset < bytes.size) {
                try {
                    val tag = varint().toInt()
                    val id = tag ushr 3
                    require(id > 0)
                    when (tag and 7) {
                        0 -> add(Field(id, varint()))
                        1 -> { require(offset + 8 <= bytes.size); offset += 8 }
                        2 -> {
                            val size = varint().toInt()
                            require(size >= 0)
                            val available = minOf(size, bytes.size - offset)
                            add(Field(id, bytes = bytes.copyOfRange(offset, offset + available))); offset += available
                            if (available < size) { add(Field(-1)); break }
                        }
                        5 -> { require(offset + 4 <= bytes.size); offset += 4 }
                        else -> error("wire type")
                    }
                } catch (_: Exception) { add(Field(-1)); break }
            }
        }
    }
}
