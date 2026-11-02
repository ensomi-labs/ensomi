package io.ensomi.android.diagnostics

/** Bound cause cycles; messages are suppressed because arbitrary prose can contain unquoted song titles. */
internal fun exceptionContext(failure: Throwable): Map<String, String> = buildMap {
    put("class", failure.javaClass.name)
    put("message", Redact.detail("message", failure.message ?: ""))
    generateSequence(failure) { it.cause }.take(5).forEachIndexed { index, cause ->
        put("cause${index}Class", cause.javaClass.name)
        put("cause${index}Message", Redact.detail("message", cause.message ?: ""))
    }
    failure.stackTrace.take(15).forEachIndexed { index, frame ->
        put("frame$index", "${frame.className}.${frame.methodName}:${frame.lineNumber}")
    }
}
