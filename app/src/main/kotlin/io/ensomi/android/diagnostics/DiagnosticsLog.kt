package io.ensomi.android.diagnostics

import android.util.Log

/**
 * Writes the Diagnostics JSON to logcat under [TAG] at info level, for scripts:
 * `adb logcat -d -s EnsomiDiagnostics:I -v raw`.
 *
 * Logcat cuts a message at about 4 KB. JSON that fits is one line, starting with `{`. Longer JSON is
 * split into lines `part i/n <piece>`; strip each prefix and join the pieces in order.
 */
object DiagnosticsLog {
    const val TAG = "EnsomiDiagnostics"
    const val MAX_LINE_BYTES = 4000
    private const val PREFIX_BYTES = 32

    fun write(json: String) {
        for (line in lines(json)) Log.i(TAG, line)
    }

    fun lines(json: String, maxLineBytes: Int = MAX_LINE_BYTES): List<String> {
        if (utf8Length(json) <= maxLineBytes) return listOf(json)
        val budget = maxLineBytes - PREFIX_BYTES
        val pieces = mutableListOf<String>()
        var start = 0
        var bytes = 0
        var index = 0
        while (index < json.length) {
            // Whole code points only, so a surrogate pair is never split across lines.
            val codePoint = json.codePointAt(index)
            val size = utf8Length(codePoint)
            if (bytes + size > budget) {
                pieces += json.substring(start, index)
                start = index
                bytes = 0
            }
            bytes += size
            index += Character.charCount(codePoint)
        }
        pieces += json.substring(start)
        return pieces.mapIndexed { i, piece -> "part ${i + 1}/${pieces.size} $piece" }
    }

    private fun utf8Length(text: String): Int {
        var total = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            total += utf8Length(codePoint)
            index += Character.charCount(codePoint)
        }
        return total
    }

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
