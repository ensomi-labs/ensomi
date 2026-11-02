package io.ensomi.android.diagnostics

import android.app.ActivityManager
import android.content.Context

/** An infrequent, best-effort hint; the durable journal remains authoritative. */
object ProcessStage {
    enum class Stage(val label: String) { START("start"), MODEL_OPEN("model_open"), MODEL_PREPARE("model_prepare"), MODEL_CHART("model_chart"), IDLE("idle") }
    private var current: Stage? = null

    @Synchronized fun set(context: Context, stage: Stage) {
        if (current == stage) return
        // Fixed ASCII labels plus eight hex digits: at most 25 bytes, below the 32-byte budget.
        // Called only at startup and guarded open boundaries, never for progress or lifecycle events.
        current = stage
        try {
            context.getSystemService(ActivityManager::class.java)
                .setProcessStateSummary("e1|${Journal.runId.take(8)}|${stage.label}".toByteArray(Charsets.US_ASCII))
        } catch (_: RuntimeException) {
            // Android may throttle this API. Do not retry in a loop or prevent the model operation.
        }
    }
}
