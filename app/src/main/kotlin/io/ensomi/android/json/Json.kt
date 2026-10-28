package io.ensomi.android.json

/**
 * Encodes plain Kotlin values as compact JSON: maps with string keys (kept in iteration order), lists,
 * strings, booleans, numbers and null. Non-finite numbers become null, since JSON has no NaN or infinity.
 */
object Json {
    fun encode(value: Any?): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendString(value)
            is Boolean -> append(value)
            is Double -> if (value.isFinite()) append(value) else append("null")
            is Float -> if (value.isFinite()) append(value) else append("null")
            is Int, is Long, is Short, is Byte -> append(value)
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((key, item) in value) {
                    require(key is String) { "JSON object keys must be strings, not $key" }
                    if (!first) append(',')
                    first = false
                    appendString(key)
                    append(':')
                    appendValue(item)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                var first = true
                for (item in value) {
                    if (!first) append(',')
                    first = false
                    appendValue(item)
                }
                append(']')
            }
            else -> throw IllegalArgumentException("Not a JSON value: ${value::class.java.name}")
        }
    }

    private fun StringBuilder.appendString(text: String) {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else ->
                    if (char < ' ') {
                        append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
            }
        }
        append('"')
    }
}
