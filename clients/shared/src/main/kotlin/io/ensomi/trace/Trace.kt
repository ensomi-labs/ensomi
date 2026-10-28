package io.ensomi.trace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.util.UUID

/**
 * One JSON object of the event trace, in the shape `deep-research-report.md` prints under "Event trace
 * 格式". Every latency in the report is measured from [tMonoNs], never the wall clock, so a trace from
 * one process compares directly with a trace from another on the same device.
 */
@Serializable
data class TraceEvent(
    val cid: String,
    val seq: Int,
    val event: String,
    @SerialName("t_mono_ns") val tMonoNs: Long,
    @SerialName("wall_ms") val wallMs: Long,
    val process: String,
    @SerialName("netease_version_code") val neteaseVersionCode: String? = null,
    @SerialName("hyperos_build") val hyperosBuild: String? = null,
    @SerialName("song_id") val songId: Long? = null,
    val source: String? = null,
    @SerialName("start_time_raw") val startTimeRaw: Long? = null,
    @SerialName("capture_duration_ms") val captureDurationMs: Long? = null,
    val detail: JsonObject? = null,
)

/** Nulls stay in the output: the report's samples show `"song_id": null`, and a reader keys on that. */
val TraceJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

/**
 * Appends [TraceEvent]s as JSONL. The caller owns the clock: it passes the monotonic reading taken at
 * the event, so the writer never fabricates a timestamp.
 */
class TraceWriter(
    val file: File,
    private val process: String,
    private val neteaseVersionCode: String? = null,
    private val hyperosBuild: String? = null,
    val cid: String = UUID.randomUUID().toString(),
) : AutoCloseable {
    private var seq = 0
    private var writer: Writer? = null
    private var closed = false

    /** Opens the file for appending, which is what a probe that survives a process restart wants. */
    fun open(): TraceWriter {
        check(!closed) { "writer is closed" }
        if (writer != null) return this
        file.parentFile?.mkdirs()
        writer = FileWriter(file, true)
        return this
    }

    /**
     * Writes one event and returns it. [tMonoNs] is `SystemClock.elapsedRealtimeNanos()` at the event;
     * [wallMs] is only for a human reading the file afterwards.
     */
    fun event(
        name: String,
        tMonoNs: Long,
        wallMs: Long,
        songId: Long? = null,
        source: String? = null,
        startTimeRaw: Long? = null,
        captureDurationMs: Long? = null,
        detail: JsonObject? = null,
    ): TraceEvent {
        val event = TraceEvent(
            cid = cid,
            seq = ++seq,
            event = name,
            tMonoNs = tMonoNs,
            wallMs = wallMs,
            process = process,
            neteaseVersionCode = neteaseVersionCode,
            hyperosBuild = hyperosBuild,
            songId = songId,
            source = source,
            startTimeRaw = startTimeRaw,
            captureDurationMs = captureDurationMs,
            detail = detail,
        )
        write(event)
        return event
    }

    fun write(event: TraceEvent) {
        val out = checkNotNull(writer) { "open() first" }
        out.write(TraceJson.encodeToString(TraceEvent.serializer(), event))
        out.write("\n")
        out.flush()
    }

    override fun close() {
        closed = true
        writer?.close()
        writer = null
    }
}
