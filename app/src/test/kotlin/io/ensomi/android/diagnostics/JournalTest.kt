package io.ensomi.android.diagnostics

import io.ensomi.android.json.Json
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class JournalTest {
    private class Sink : JournalSink {
        var data = ""
        var syncs = 0
        var writes = 0
        override fun read() = parseLines(data)
        override fun append(bytes: ByteArray, durable: Boolean) { data += bytes.toString(Charsets.UTF_8); writes++; if (durable) sync() }
        override fun replace(records: List<Map<String, Any?>>) { data = records.joinToString("") { Json.encode(it) + "\n" } }
        override fun sync() { syncs++ }
    }
    @Test fun durableWritesSyncBeforeReturningAndBatchPendingRecords() {
        val sink = Sink()
        val journal = JournalEngine(sink, "0123456789abcdef", { 1000 }, { 100 })
        journal.record("install", "begin", emptyMap(), true)
        assertEquals(1, sink.syncs)
        journal.record("progress", "waiting", emptyMap(), false)
        journal.record("progress", "waiting", emptyMap(), false)
        assertEquals(1, sink.writes)
        journal.record("install", "end", emptyMap(), true)
        assertEquals(2, sink.syncs)
        assertEquals(listOf(1L, 2L, 3L, 4L), sink.read().map { it["seq"] })
        journal.record("progress", "waiting", emptyMap(), false)
        journal.flush()
        assertEquals(3, sink.syncs)
        assertEquals(5, sink.read().size)
    }
    @Test fun concurrentRecordsKeepSequenceOrder() {
        val sink = Sink()
        val journal = JournalEngine(sink, "0123456789abcdef", { 1000 }, { 100 })
        val executor = Executors.newFixedThreadPool(4)
        repeat(100) { executor.submit { journal.record("install", "step", emptyMap(), true) } }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals((1L..100L).toList(), sink.read().map { it["seq"] })
    }
    @Test fun rotationBoundsBytesAndSurvivesRestartAndTornTail() {
        val dir = Files.createTempDirectory("journal-test").toFile()
        try {
            val sink = FileJournalSink(dir)
            val journal = JournalEngine(sink, "0123456789abcdef", { 1000 }, { 100 })
            repeat(1800) { journal.record("install", "step", mapOf("technical" to "data ".repeat(100)), false) }
            journal.flush()
            val segments = dir.listFiles()!!.filter { it.extension == "jsonl" }
            assertEquals(2, segments.size)
            assertTrue(segments.all { it.length() <= 65536 })
            assertTrue(segments.sumOf { it.length() } <= 131072)
            val rows = sink.read()
            assertEquals(1800L, rows.last()["seq"])
            assertEquals(rows.map { it["seq"] }.sortedBy { it as Long }, rows.map { it["seq"] })
            File(dir, "journal-1.jsonl").appendText("{\"v\":1,\"t\":")
            assertEquals(rows, sink.read())
            val restarted = JournalEngine(FileJournalSink(dir), "1123456789abcdef", { 1001 }, { 101 })
            restarted.record("run", "start", emptyMap(), true)
            assertEquals("1123456789abcdef", restarted.snapshot(8).last()["run"])
        } finally { dir.deleteRecursively() }
    }
    @Test fun ignoresUnknownKeysAndIncompleteLastLine() {
        val rows = parseLines("{\"v\":1,\"run\":\"a\",\"seq\":1,\"future\":{\"x\":true}}\n{broken")
        assertEquals(1, rows.size)
        assertEquals("a", rows.single()["run"])
    }
    @Test fun retainsAtMostEightRunsAndExportsThree() {
        val sink = Sink()
        repeat(12) { run ->
            JournalEngine(sink, run.toString(), { run.toLong() }, { run.toLong() })
                .record("run", "start", emptyMap(), true)
        }
        assertEquals((4..11).map(Int::toString), sink.read().map { it["run"] })
        val journal = JournalEngine(sink, "12", { 12 }, { 12 })
        journal.record("run", "start", emptyMap(), true)
        assertEquals(listOf("10", "11", "12"), journal.snapshot(3).map { it["run"] })
    }
}
