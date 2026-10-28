package io.ensomi.android.diagnostics

import androidx.annotation.StringRes
import io.ensomi.android.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A titled group of labelled values on the Diagnostics screen. */
data class DiagnosticsSection(@param:StringRes val title: Int, val rows: List<DiagnosticsRow>)

/** [labelArgument], when set, fills the label's format argument (a core or mode number). */
data class DiagnosticsRow(
    @param:StringRes val label: Int,
    val value: DiagnosticsValue,
    val labelArgument: Int? = null,
)

sealed interface DiagnosticsValue {
    data class Text(val text: String) : DiagnosticsValue
    data class Flag(val on: Boolean) : DiagnosticsValue

    /** Text shown as an error. */
    data class Problem(val text: String) : DiagnosticsValue

    data object Unknown : DiagnosticsValue
}

/** The screen's grouping: what decides the next steps first, the long per-core list last. */
fun DiagnosticsReport.sections(): List<DiagnosticsSection> = listOf(
    DiagnosticsSection(
        R.string.section_device,
        listOf(
            DiagnosticsRow(R.string.label_manufacturer, text(device.manufacturer)),
            DiagnosticsRow(R.string.label_model, text(device.model)),
            DiagnosticsRow(R.string.label_soc_manufacturer, text(device.socManufacturer)),
            DiagnosticsRow(R.string.label_soc_model, text(device.socModel)),
            DiagnosticsRow(R.string.label_android, text("${device.androidRelease} (API ${device.sdkInt})")),
            DiagnosticsRow(
                R.string.label_vendor_os,
                text(device.vendorOs.takeUnless { it == VendorOs.UNKNOWN }),
            ),
        ),
    ),
    DiagnosticsSection(
        R.string.section_runtime,
        buildList {
            add(DiagnosticsRow(R.string.label_runtime_loaded, DiagnosticsValue.Flag(runtime.loaded)))
            add(DiagnosticsRow(R.string.label_runtime_init, text(runtime.initMillis?.let(Format::millis))))
            add(
                DiagnosticsRow(
                    R.string.label_runtime_notice_files,
                    text(runtime.noticeFiles?.joinToString(prefix = "[", postfix = "]")),
                ),
            )
            add(DiagnosticsRow(R.string.label_runtime_check_package, text(runtime.checkPackage)))
            runtime.error?.let { add(DiagnosticsRow(R.string.label_runtime_error, DiagnosticsValue.Problem(it))) }
        },
    ),
    DiagnosticsSection(
        R.string.section_memory,
        listOf(
            DiagnosticsRow(R.string.label_page_size, text(Format.bytes(memory.pageSizeBytes))),
            DiagnosticsRow(R.string.label_total_ram, text(Format.bytes(memory.totalRamBytes))),
            DiagnosticsRow(R.string.label_advertised_ram, text(memory.advertisedRamBytes?.let(Format::bytes))),
        ),
    ),
    DiagnosticsSection(
        R.string.section_display,
        listOf(DiagnosticsRow(R.string.label_current_mode, text(display.currentMode?.let(Format::mode)))) +
            display.supportedModes.map { DiagnosticsRow(R.string.label_mode, text(Format.mode(it)), it.id) },
    ),
    DiagnosticsSection(
        R.string.section_audio,
        listOf(
            DiagnosticsRow(R.string.label_low_latency, DiagnosticsValue.Flag(audio.lowLatency)),
            DiagnosticsRow(R.string.label_pro_audio, DiagnosticsValue.Flag(audio.pro)),
            DiagnosticsRow(R.string.label_output_sample_rate, text(audio.outputSampleRateHz?.let { "$it Hz" })),
            DiagnosticsRow(R.string.label_frames_per_buffer, text(audio.outputFramesPerBuffer?.toString())),
            DiagnosticsRow(R.string.label_unprocessed_capture, DiagnosticsValue.Flag(audio.unprocessedCapture)),
        ),
    ),
    DiagnosticsSection(
        R.string.section_thermal,
        listOf(
            DiagnosticsRow(R.string.label_thermal_status, text(thermal.status)),
            DiagnosticsRow(R.string.label_thermal_headroom, text(thermal.headroom10s?.let(Format::headroom))),
        ),
    ),
    DiagnosticsSection(
        R.string.section_cpu,
        listOf(DiagnosticsRow(R.string.label_cores, text(cpu.cores.size.toString()))) +
            cpu.cores.map { core ->
                DiagnosticsRow(R.string.label_core, text(core.maxFreqKHz?.let(Format::frequency)), core.index)
            },
    ),
)

private fun text(value: String?): DiagnosticsValue =
    if (value.isNullOrBlank()) DiagnosticsValue.Unknown else DiagnosticsValue.Text(value)

/** Value formatting for the screen; the JSON keeps raw numbers. */
object Format {
    private const val KIB = 1024L
    private const val MIB = 1024L * KIB
    private const val GIB = 1024L * MIB

    /** Whole multiples of KiB and MiB stay exact (a 16 KiB page); RAM gets one decimal in GiB. */
    fun bytes(bytes: Long): String = when {
        bytes >= GIB -> String.format(Locale.ROOT, "%.1f GiB", bytes.toDouble() / GIB)
        bytes >= MIB && bytes % MIB == 0L -> "${bytes / MIB} MiB"
        bytes >= KIB && bytes % KIB == 0L -> "${bytes / KIB} KiB"
        else -> "$bytes B"
    }

    fun frequency(kiloHertz: Long): String =
        if (kiloHertz >= 1_000_000) {
            String.format(Locale.ROOT, "%.2f GHz", kiloHertz / 1e6)
        } else {
            "${(kiloHertz / 1e3).roundToInt()} MHz"
        }

    /** "1080 × 2424, 120 Hz"; a rate within 0.05 Hz of a whole number is shown whole. */
    fun mode(mode: DisplayMode): String = "${mode.width} × ${mode.height}, ${refreshRate(mode.refreshRateHz)} Hz"

    fun refreshRate(hertz: Float): String {
        val whole = hertz.roundToInt()
        return if (abs(hertz - whole) < 0.05f) whole.toString() else String.format(Locale.ROOT, "%.2f", hertz)
    }

    fun millis(milliseconds: Double): String = String.format(Locale.ROOT, "%.1f ms", milliseconds)

    fun headroom(headroom: Float): String = String.format(Locale.ROOT, "%.2f", headroom)
}
