package io.ensomi.android.diagnostics

import java.net.URI

/** Technical text only. Callers must never supply song titles or document names, even as event names. */
object Redact {
    @Volatile private var roots: Map<String, String> = emptyMap()
    fun configure(paths: Map<String, String>) { roots = paths.entries.sortedByDescending { it.key.length }.associate { it.toPair() } }

    fun text(value: String, cap: Int = 1024, tokens: Boolean = true): String {
        var result = value.take(16_384)
        // Quoted prose can contain an arbitrary title with no recognisable extension.
        result = result.replace(Regex("\"[^\"]*\"|'[^']*'"), "<redacted>")
        result = result.replace(Regex("content://[^\\s<>\"']+", RegexOption.IGNORE_CASE)) {
            val authority = runCatching { URI(it.value).host }.getOrNull()
            "content://${authority?.take(120) ?: "<authority>"}"
        }
        result = result.replace(Regex("[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s<>\"']+")) {
            if (it.value.startsWith("content://")) it.value else {
                val uri = runCatching { URI(it.value) }.getOrNull()
                val host = uri?.host?.lowercase()
                if (host != null && listOf("huggingface.co", "hf.co", "xethub.hf.co", "hfcdn.net").any { domain ->
                        host == domain || host.endsWith(".$domain")
                    }) "${uri.scheme.lowercase()}://$host" else "<url>"
            }
        }
        for ((path, label) in roots) {
            result = result.replace(Regex(Regex.escape(path) + "(?:/[^\\s<>\"']*)?"), label)
        }
        result = result.replace(Regex("/(?:storage|sdcard|mnt/media_rw)(?:/[^\\s<>\"']*)?"), "<ext>")
        result = result.replace(Regex("(?<![:/a-zA-Z0-9])/(?:[^\\s<>\"']+)"), "<path>")
        result = result.replace(Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "<email>")
        if (tokens) result = result.replace(Regex("(?i)\\b[0-9a-f]{32,}\\b|(?<![A-Za-z0-9])[A-Za-z0-9+/=_-]{48,}(?![A-Za-z0-9])"), "<token>")
        result = result.replace(Regex("(?i)(?:[\\p{L}\\p{N}_-]+[ ]+)*[\\p{L}\\p{N}_.-]+\\.(?:mp3|wav|flac|m4a|ogg|aac|opus|aiff|wma|mp4|zip|json|onnx|bin|txt|csv|pdf|jpg|png|kt|java|so|apk)(?![\\p{L}\\p{N}_$.])"), "<file>")
        return result.replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), " ").take(cap)
    }

    fun symbol(value: String): String = text(value, 512, tokens = false)
    fun threadName(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9_$.:/-]{1,128}"))) text(value, 128) else "<redacted>"

    fun detail(key: String, value: String): String =
        if (Regex("(?i)title|filename|file_name|song|document|message").containsMatchIn(key)) "<redacted>"
        else text(value)

    /** Recursively sanitise free text at the report boundary. IDs and schema literals are supplied separately. */
    fun values(value: Any?): Any? = when (value) {
        is String -> text(value)
        is Map<*, *> -> value.entries.associate { (key, item) -> key to values(item) }
        is Iterable<*> -> value.map(::values)
        else -> value
    }
}
