package io.ensomi.android.diagnostics

import android.app.ActivityManager
import android.content.Context
import io.ensomi.android.json.Json
import java.io.File
import java.util.concurrent.CountDownLatch

internal data class JournalRun(val id: String, val pid: Int, val start: Long, val last: Long)
internal fun journalRuns(rows: List<Map<String, Any?>>): List<JournalRun> = rows.groupBy { it["run"] as? String }.mapNotNull { (id, events) ->
    val header = events.firstOrNull { it["ev"] == "run" && it["ph"] == "start" } ?: return@mapNotNull null
    val ctx = header["ctx"] as? Map<*, *> ?: return@mapNotNull null
    val pid = ctx["pid"]?.toString()?.toIntOrNull() ?: return@mapNotNull null
    val start = ctx["startWall"]?.toString()?.toLongOrNull() ?: return@mapNotNull null
    val last = events.mapNotNull { (it["t"] as? Number)?.toLong() }.maxOrNull() ?: start
    id?.let { JournalRun(it, pid, start, last) }
}

/** Refuse ambiguous PID reuse, clock changes and a mismatched summary, even when one match looks likely. */
internal fun matchExit(pid: Int, timestamp: Long, summary: String?, runs: List<JournalRun>): String? {
    val summaryRun = summary?.let { Regex("^e1\\|([0-9a-f]{8}|[0-9a-f]{16})\\|").find(it)?.groupValues?.get(1) }
    return runs.filter {
        it.pid == pid && if (summaryRun != null) it.id.startsWith(summaryRun)
        else it.last >= it.start && timestamp >= it.start && timestamp <= it.last + 600_000
    }.singleOrNull()?.id
}

/** The marker belongs to the first lifetime ending after it, not a later reuse of the same PID. */
internal fun markerExitReason(rows: List<Map<String, Any?>>, pid: Int, after: Long): Int? =
    rows.filter { (it["pid"] as? Number)?.toInt() == pid &&
        ((it["timestamp"] as? Number)?.toLong() ?: Long.MIN_VALUE) >= after }
        .minByOrNull { (it["timestamp"] as Number).toLong() }
        ?.get("reasonCode").let { (it as? Number)?.toInt() }

internal fun validExitRows(rows: List<Map<String, Any?>>) = rows.filter { it["timestamp"] is Number }

internal fun reasonName(reason: Int): String = when (reason) {
    0 -> "REASON_UNKNOWN"; 1 -> "REASON_EXIT_SELF"; 2 -> "REASON_SIGNALED"; 3 -> "REASON_LOW_MEMORY"
    4 -> "REASON_CRASH"; 5 -> "REASON_CRASH_NATIVE"; 6 -> "REASON_ANR"; 7 -> "REASON_INITIALIZATION_FAILURE"
    8 -> "REASON_PERMISSION_CHANGE"; 9 -> "REASON_EXCESSIVE_RESOURCE_USAGE"; 10 -> "REASON_USER_REQUESTED"
    11 -> "REASON_USER_STOPPED"; 12 -> "REASON_DEPENDENCY_DIED"; 13 -> "REASON_OTHER"; 14 -> "REASON_FREEZER"
    15 -> "REASON_PACKAGE_STATE_CHANGE"; 16 -> "REASON_PACKAGE_UPDATED"
    17 -> "REASON_MEMORY_LIMITER"; 18 -> "REASON_ANOMALY"; else -> "REASON_$reason"
}
internal fun signalName(status: Int): String? = when (status) {
    1 -> "SIGHUP"; 2 -> "SIGINT"; 3 -> "SIGQUIT"; 4 -> "SIGILL"; 5 -> "SIGTRAP"; 6 -> "SIGABRT"
    7 -> "SIGBUS"; 8 -> "SIGFPE"; 9 -> "SIGKILL"; 10 -> "SIGUSR1"; 11 -> "SIGSEGV"; 12 -> "SIGUSR2"
    13 -> "SIGPIPE"; 14 -> "SIGALRM"; 15 -> "SIGTERM"; 16 -> "SIGSTKFLT"; 17 -> "SIGCHLD"
    18 -> "SIGCONT"; 19 -> "SIGSTOP"; 20 -> "SIGTSTP"; 21 -> "SIGTTIN"; 22 -> "SIGTTOU"
    23 -> "SIGURG"; 24 -> "SIGXCPU"; 25 -> "SIGXFSZ"; 26 -> "SIGVTALRM"; 27 -> "SIGPROF"
    28 -> "SIGWINCH"; 29 -> "SIGIO"; 30 -> "SIGPWR"; 31 -> "SIGSYS"; else -> null
}

/** One background read per process. Export waits for it; launching the recovery screen cannot race the read. */
object ExitRecords {
    private val ready = CountDownLatch(1)
    @Volatile var collection: String = "pending"
        private set
    fun start(context: Context) {
        Thread({
            try { collect(context); collection = "complete" }
            catch (failure: Throwable) { collection = "unavailable: ${Redact.text(failure.javaClass.name)}" }
            finally { ready.countDown() }
        }, "EnsomiExitHistory").start()
    }
    internal fun await() { ready.await() }
    fun reasonFor(context: Context, pid: Int, after: Long): Int? {
        await()
        return markerExitReason(read(context), pid, after)
    }
    @Synchronized internal fun read(context: Context): List<Map<String, Any?>> =
        runCatching { readLines(File(Journal.directory(context), "exits.jsonl")) }.getOrDefault(emptyList())

    @Synchronized private fun collect(context: Context) {
        val dir = Journal.directory(context).also { it.mkdirs() }
        val file = File(dir, "exits.jsonl")
        val records = validExitRows(read(context)).toMutableList()
        val seen = records.map { it["pid"].toString() to it["timestamp"].toString() }.toSet()
        val runs = journalRuns(Journal.snapshot(Int.MAX_VALUE))
        val history = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 0)
        for (exit in history.sortedBy { it.timestamp }) {
            if (exit.pid.toString() to exit.timestamp.toString() in seen) continue
            val summary = exit.processStateSummary?.toString(Charsets.UTF_8)?.let { Redact.text(it, 128) }
            val run = matchExit(exit.pid, exit.timestamp, summary, runs)
            val trace = if (exit.reason == 5 || exit.reason == 6) {
                try {
                    exit.traceInputStream?.use { input ->
                        val bytes = input.readNBytes(TracePrivacy.INPUT_LIMIT)
                        val name = "trace-${exit.timestamp}-${exit.pid}.txt"
                        val safe = TracePrivacy.sanitise(bytes, exit.reason == 5)
                        atomicWrite(File(dir, name), safe.toByteArray().take(TracePrivacy.LIMIT).toByteArray())
                        name
                    } ?: "unavailable"
                } catch (_: Exception) { "unavailable" }
            } else "not_requested"
            records.add(linkedMapOf("v" to 1, "pid" to exit.pid, "timestamp" to exit.timestamp,
                "reason" to reasonName(exit.reason), "reasonCode" to exit.reason, "status" to exit.status,
                "signal" to (if (exit.reason == 2 || exit.reason == 5) signalName(exit.status) else null),
                "description" to exit.description?.let { Redact.text(it) }, "importance" to exit.importance,
                "pss" to exit.pss, "rss" to exit.rss, "processStateSummary" to summary,
                "match" to (if (run == null) "unmatched" else "matched"), "run" to run, "trace" to trace))
        }
        val kept = validExitRows(records).sortedBy { (it["timestamp"] as? Number)?.toLong() ?: 0L }.takeLast(32)
        val traces = kept.mapNotNull { it["trace"] as? String }.filter { it.startsWith("trace-") }.takeLast(3).toSet()
        dir.listFiles().orEmpty().filter { it.name.startsWith("trace-") && it.name !in traces }.forEach { it.delete() }
        val final = kept.map { row ->
            if ((row["trace"] as? String)?.startsWith("trace-") == true && row["trace"] !in traces)
                row + ("trace" to "expired") else row
        }
        Journal.prune(final.mapNotNull { it["run"] as? String }.toSet())
        atomicWrite(file, final.joinToString("", transform = { Json.encode(it) + "\n" }).toByteArray())
    }
}
