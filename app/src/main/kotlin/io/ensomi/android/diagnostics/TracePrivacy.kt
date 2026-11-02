package io.ensomi.android.diagnostics

/**
 * Never store raw tombstones: they contain memory, log buffers, open file names and command arguments.
 * Project the capped protobuf onto numeric signal/stack data and redacted symbols. Unknown fields are skipped.
 * Field numbers: AOSP system/core/debuggerd/proto/tombstone.proto. ANRs retain only stack lines.
 */
internal object TracePrivacy {
    const val LIMIT = 256 * 1024
    fun sanitise(bytes: ByteArray, native: Boolean): String = buildString {
        appendLine("Redacted trace; input capped at 256 KiB. Memory, filenames, messages and logs omitted.")
        if (native) {
            try {
                fields(bytes).forEach { field -> when (field.id) {
                    -1 -> appendLine("Remaining trace unavailable: truncated or unsupported protobuf.")
                    5 -> appendLine("pid=${field.number}")
                    6 -> appendLine("tid=${field.number}")
                    10 -> fields(field.bytes).filter { it.id in setOf(1, 3, 9) }.forEach { appendLine("signal.${it.id}=${it.number}") }
                    16 -> fields(field.bytes).firstOrNull { it.id == 2 }?.let { thread ->
                        fields(thread.bytes).forEach { item -> when (item.id) {
                            1 -> appendLine("thread=${item.number}")
                            4 -> {
                                val frame = fields(item.bytes)
                                appendLine(frame.filter { it.id in setOf(1, 5, 7) }.joinToString(" ") { "frame.${it.id}=${it.number}" })
                                frame.firstOrNull { it.id == 4 }?.let { appendLine("symbol=${Redact.text(it.bytes.toString(Charsets.UTF_8), 512)}") }
                                // A build ID is a structured binary identity, not a free-text token.
                                frame.firstOrNull { it.id == 8 }?.bytes?.toString(Charsets.UTF_8)?.takeIf { it.matches(Regex("[0-9a-fA-F]{1,128}")) }
                                    ?.let { appendLine("buildId=$it") }
                            }
                        } }
                    }
                } }
            } catch (_: Exception) { appendLine("Remaining trace unavailable: truncated or unsupported protobuf.") }
        } else {
            bytes.toString(Charsets.UTF_8).lineSequence().filter { it.trimStart().startsWith("at ") || it.trimStart().startsWith("native: #") }
                .take(500).forEach { appendLine(Redact.text(it, 512)) }
        }
    }.take(LIMIT)

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
