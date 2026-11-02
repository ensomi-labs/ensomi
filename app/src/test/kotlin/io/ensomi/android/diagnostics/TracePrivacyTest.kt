package io.ensomi.android.diagnostics

import org.junit.Assert.*
import org.junit.Test

class TracePrivacyTest {
    private fun number(value: Int): ByteArray {
        var n = value
        val out = mutableListOf<Byte>()
        do { val b = n and 127; n = n ushr 7; out += (b or if (n != 0) 128 else 0).toByte() } while (n != 0)
        return out.toByteArray()
    }
    private fun scalar(id: Int, value: Int) = number(id shl 3) + number(value)
    private fun blob(id: Int, value: ByteArray) = number((id shl 3) or 2) + number(value.size) + value
    private fun text(id: Int, value: String) = blob(id, value.toByteArray())

    @Test fun nativeProjectionOmitsMemoryMessagesAndFilesButKeepsSymbolizationFacts() {
        val buildId = "1234567890abcdef".repeat(4)
        val frame = scalar(1, 1234) + text(4, "invented::open") + text(6, "/data/private/Aster.mp3") + text(8, buildId)
        val thread = scalar(1, 42) + text(2, "Aster Song") + blob(4, frame) + text(5, "invented.person@example.invalid")
        val bytes = scalar(5, 42) + blob(10, scalar(1, 6)) + text(14, "Aster Song") + blob(16, scalar(1, 42) + blob(2, thread)) + text(17, "secret")
        val output = TracePrivacy.sanitise(bytes, true)
        assertTrue(output.contains("pid=42"))
        assertTrue(output.contains("signal=6"))
        assertTrue(output.contains("frame pc=0x4d2"))
        assertTrue(output.contains("symbol=invented::open"))
        assertTrue(output.contains("buildId=$buildId"))
        for (private in listOf("Aster", "secret", "/data", "example.invalid")) assertFalse(output, output.contains(private))
    }
    @Test fun tornProtobufStillKeepsCompleteLeadingFieldsAndLabelsRemainder() {
        val output = TracePrivacy.sanitise(scalar(5, 42) + byteArrayOf(0x80.toByte()), true)
        assertTrue(output.contains("pid=42"))
        assertTrue(output.contains("truncated or unsupported"))
    }
    @Test fun cappedThreadStillExposesItsCompleteLeadingFrames() {
        val thread = scalar(1, 42) + blob(4, scalar(1, 1234)) + text(5, "private memory ".repeat(100))
        val bytes = scalar(5, 42) + blob(16, scalar(1, 42) + blob(2, thread))
        val output = TracePrivacy.sanitise(bytes.copyOf(40), true)
        assertTrue(output.contains("pid=42"))
        assertTrue(output.contains("frame pc=0x4d2"))
        assertFalse(output.contains("private memory"))
    }
    @Test fun anrProjectionKeepsTechnicalHeadersButDropsPrivateMessages() {
        val output = TracePrivacy.sanitise("\"Aster Song\" tid=42\nmessage: Aster Song\n  at invented.Type.run(Type.kt:42)\n".toByteArray(), false)
        assertFalse(output.contains("Aster"))
        assertFalse(output.contains("Type.kt"))
        assertTrue(output.contains("invented.Type.run"))
    }
    @Test fun sixtyThreadsPutCrashingThreadLastInInputAndFirstInProjection() {
        val build = "1234567890abcdef".repeat(4)
        val symbol = "kai_run_matmul_clamp_f32_bf16p8x4_bf16p12x4b_8x12_neon_mmla_".repeat(3)
        fun thread(id: Int, count: Int): ByteArray {
            var body = scalar(1, id) + text(2, if (id == 99) "main" else "worker-$id")
            repeat(count) { pc -> body += blob(4, scalar(1, pc + id * 1000) + text(6, "/data/app/private/base.apk!lib/arm64-v8a/libX.so") + text(8, build) + text(4, symbol)) }
            return blob(16, scalar(1, id) + blob(2, body))
        }
        var input = scalar(5, 42) + scalar(6, 99) + blob(10, scalar(1, 4) + scalar(3, 0))
        repeat(59) { input += thread(it + 1, 100) }
        input += thread(99, 120)
        assertTrue(input.size > 256 * 1024)
        val output = TracePrivacy.sanitise(input, true)
        assertTrue(output.startsWith("projection=2\n"))
        assertTrue(output.indexOf("thread=99 name=main crashing") < output.indexOf("thread=1 name=worker-1"))
        val crash = output.substringAfter("thread=99 name=main crashing").substringBefore("thread=1 name=worker-1")
        assertEquals(120, crash.lineSequence().count { it.startsWith("frame ") })
        assertTrue(crash.contains("module=libX.so"))
        assertTrue(crash.contains("buildId=$build"))
        assertTrue(crash.contains(symbol))
        assertFalse(output.contains("/data/app"))
        assertEquals(120 + 59 * 16, output.lineSequence().count { it.startsWith("frame ") })
    }

    @Test fun anrKeepsMainThreadAndNativeModuleBuildId() {
        val build = "abcdef0123456789".repeat(4)
        val output = TracePrivacy.sanitise(("\"main\" prio=5 tid=1 Native\n" +
            "  native: #00 pc 001234 /data/app/private/base.apk!lib/arm64-v8a/libX.so (native_call+4) (BuildId: $build)\n").toByteArray(), false)
        assertTrue(output.contains("\"main\" prio=5 tid=1"))
        assertTrue(output.contains("module=libX.so buildId=$build"))
        assertFalse(output.contains("/data/app"))
    }

}
