package io.ensomi.android.diagnostics

import io.ensomi.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    private val report = DiagnosticsReport(
        collectedAt = "2026-10-28T12:00:00Z",
        app = AppFacts("io.ensomi.android", "0.1.0", 1),
        device = DeviceFacts("Google", "sdk_gphone64_arm64", "Google", "unknown", "16", 36, VendorOs.UNKNOWN, emptyMap()),
        cpu = CpuFacts(listOf(CpuCore(0, 2_265_600), CpuCore(1, null))),
        memory = MemoryFacts(pageSizeBytes = 16_384, totalRamBytes = 4_100_000_000, advertisedRamBytes = null),
        display = DisplayFacts(
            currentMode = DisplayMode(1, 1080, 2424, 60f),
            supportedModes = listOf(DisplayMode(1, 1080, 2424, 60f)),
        ),
        audio = AudioFacts(lowLatency = true, pro = false, outputSampleRateHz = 48_000, outputFramesPerBuffer = 960, unprocessedCapture = false),
        thermal = ThermalFacts("none", headroom10s = null),
        runtime = RuntimeFacts(loaded = true, initMillis = 41.5, noticeFiles = emptyList(), checkPackage = "Model: bad manifest", error = null),
    )

    @Test
    fun jsonHasTheAgreedKeys() {
        val json = report.toJson()
        assertTrue(json.startsWith("""{"schema":1,"collectedAt":"2026-10-28T12:00:00Z","""))
        listOf(
            """"cores":[{"index":0,"maxFreqKHz":2265600},{"index":1,"maxFreqKHz":null}]""",
            """"pageSizeBytes":16384""",
            """"currentMode":{"id":1,"width":1080,"height":2424,"refreshRateHz":60.0}""",
            """"outputSampleRateHz":48000""",
            """"thermal":{"status":"none","headroom10s":null}""",
            """"runtime":{"loaded":true,"initMillis":41.5,"noticeFiles":[],"checkPackage":"Model: bad manifest","error":null}""",
        ).forEach { assertTrue(it, it in json) }
    }

    @Test
    fun sectionsShowUnknownForMissingValues() {
        val sections = report.sections()
        val cpu = sections.single { it.title == R.string.section_cpu }
        assertEquals(DiagnosticsRow(R.string.label_core, DiagnosticsValue.Unknown, 1), cpu.rows.last())
        assertEquals(2, cpu.rows.size)
        val runtime = sections.single { it.title == R.string.section_runtime }
        assertTrue(runtime.rows.none { it.label == R.string.label_runtime_error })
        assertEquals(
            DiagnosticsValue.None,
            runtime.rows.single { it.label == R.string.label_runtime_notice_files }.value,
        )
        val failed = report.copy(runtime = RuntimeFacts(false, null, null, null, "java.lang.UnsatisfiedLinkError: x"))
            .sections().single { it.title == R.string.section_runtime }
        assertEquals(DiagnosticsValue.Problem("java.lang.UnsatisfiedLinkError: x"), failed.rows.last().value)
    }

    @Test
    fun formatsValues() {
        assertEquals("16 KiB", Format.bytes(16_384))
        assertEquals("4 KiB", Format.bytes(4_096))
        assertEquals("7.5 GiB", Format.bytes(8_053_063_680))
        assertEquals("3.30 GHz", Format.frequency(3_302_400))
        assertEquals("800 MHz", Format.frequency(800_000))
        assertEquals("2 kHz", Format.frequency(2))
        assertEquals("8 GB", Format.gigabytes(8_000_000_000))
        assertEquals("8 GB", Format.gigabytes(8_011_000_000))
        assertEquals("7.5 GB", Format.gigabytes(7_500_000_000))
        assertEquals("1080 × 2424, 120 Hz", Format.mode(DisplayMode(2, 1080, 2424, 120.00001f)))
        assertEquals("59.94", Format.refreshRate(59.94f))
    }
}
