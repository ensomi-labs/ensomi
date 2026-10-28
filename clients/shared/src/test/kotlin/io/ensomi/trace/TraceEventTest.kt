package io.ensomi.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceEventTest {
    @Test
    fun `serializes the report field names and keeps nulls`() {
        val event = TraceEvent(
            cid = "01J",
            seq = 12,
            event = "recognition.capture.end",
            tMonoNs = 78_123_456_789_012,
            wallMs = 1_793_069_012_345,
            process = "ensomi",
            neteaseVersionCode = "9002045",
            hyperosBuild = "OS3.0.307.0.WOKCNXM",
            songId = null,
            detail = null,
        )

        val json = Json.parseToJsonElement(TraceJson.encodeToString(TraceEvent.serializer(), event)).jsonObject

        assertEquals("01J", json.getValue("cid").jsonPrimitive.content)
        assertEquals("12", json.getValue("seq").jsonPrimitive.content)
        assertEquals("78123456789012", json.getValue("t_mono_ns").jsonPrimitive.content)
        assertEquals("1793069012345", json.getValue("wall_ms").jsonPrimitive.content)
        assertEquals("9002045", json.getValue("netease_version_code").jsonPrimitive.content)
        assertEquals("OS3.0.307.0.WOKCNXM", json.getValue("hyperos_build").jsonPrimitive.content)
        assertTrue(json.containsKey("song_id"))
        assertTrue(json.getValue("song_id").toString() == "null")
        assertNull(event.detail)
    }

    @Test
    fun `writes one line per event and continues the sequence`() {
        val file = File.createTempFile("trace", ".jsonl").also { it.deleteOnExit() }
        file.delete()

        TraceWriter(file, process = "ensomi", neteaseVersionCode = "9002045").use { writer ->
            writer.open()
            writer.event("capture.start", tMonoNs = 1_000, wallMs = 2_000)
            writer.event("capture.end", tMonoNs = 3_000, wallMs = 4_000, captureDurationMs = 2_000)
        }

        val lines = file.readLines()
        assertEquals(2, lines.size)

        val first = Json.parseToJsonElement(lines[0]).jsonObject
        val second = Json.parseToJsonElement(lines[1]).jsonObject
        assertEquals("1", first.getValue("seq").jsonPrimitive.content)
        assertEquals("2", second.getValue("seq").jsonPrimitive.content)
        assertEquals(first.getValue("cid"), second.getValue("cid"))
        assertEquals("2000", second.getValue("capture_duration_ms").jsonPrimitive.content)
    }
}
