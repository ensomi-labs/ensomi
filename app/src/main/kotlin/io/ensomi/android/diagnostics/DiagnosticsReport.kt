package io.ensomi.android.diagnostics

import io.ensomi.android.json.Json

/** What the Diagnostics screen reads at launch. Null means the device did not say. */
data class DiagnosticsReport(
    val collectedAt: String,
    val app: AppFacts,
    val device: DeviceFacts,
    val cpu: CpuFacts,
    val memory: MemoryFacts,
    val display: DisplayFacts,
    val audio: AudioFacts,
    val thermal: ThermalFacts,
    val runtime: RuntimeFacts,
) {
    /** One compact JSON object; the copy action and logcat both carry this string. */
    fun toJson(): String = Json.encode(
        linkedMapOf(
            "schema" to SCHEMA_VERSION,
            "collectedAt" to collectedAt,
            "app" to linkedMapOf(
                "applicationId" to app.applicationId,
                "versionName" to app.versionName,
                "versionCode" to app.versionCode,
            ),
            "device" to linkedMapOf(
                "manufacturer" to device.manufacturer,
                "model" to device.model,
                "socManufacturer" to device.socManufacturer,
                "socModel" to device.socModel,
                "androidRelease" to device.androidRelease,
                "sdkInt" to device.sdkInt,
                "vendorOs" to device.vendorOs,
                "vendorOsProperties" to device.vendorOsProperties,
            ),
            "cpu" to linkedMapOf(
                "cores" to cpu.cores.map { linkedMapOf("index" to it.index, "maxFreqKHz" to it.maxFreqKHz) },
            ),
            "memory" to linkedMapOf(
                "pageSizeBytes" to memory.pageSizeBytes,
                "totalRamBytes" to memory.totalRamBytes,
                "advertisedRamBytes" to memory.advertisedRamBytes,
            ),
            "display" to linkedMapOf(
                "currentMode" to display.currentMode?.toJsonMap(),
                "supportedModes" to display.supportedModes.map { it.toJsonMap() },
            ),
            "audio" to linkedMapOf(
                "lowLatency" to audio.lowLatency,
                "pro" to audio.pro,
                "outputSampleRateHz" to audio.outputSampleRateHz,
                "outputFramesPerBuffer" to audio.outputFramesPerBuffer,
                "unprocessedCapture" to audio.unprocessedCapture,
            ),
            "thermal" to linkedMapOf(
                "status" to thermal.status,
                "headroom10s" to thermal.headroom10s,
            ),
            "runtime" to linkedMapOf(
                "loaded" to runtime.loaded,
                "initMillis" to runtime.initMillis,
                "noticeFiles" to runtime.noticeFiles,
                "checkPackage" to runtime.checkPackage,
                "error" to runtime.error,
            ),
        ),
    )

    private fun DisplayMode.toJsonMap() = linkedMapOf(
        "id" to id,
        "width" to width,
        "height" to height,
        "refreshRateHz" to refreshRateHz,
    )

    companion object {
        /** Bumped when a key changes meaning or disappears, so scripts reading the log can tell. */
        const val SCHEMA_VERSION = 1
    }
}

data class AppFacts(val applicationId: String, val versionName: String?, val versionCode: Long)

data class DeviceFacts(
    val manufacturer: String,
    val model: String,
    val socManufacturer: String,
    val socModel: String,
    val androidRelease: String,
    val sdkInt: Int,
    /** "HyperOS …", "MIUI …" or "unknown". */
    val vendorOs: String,
    /** The raw system properties [vendorOs] was read from, when the device exposes them. */
    val vendorOsProperties: Map<String, String>,
)

data class CpuCore(val index: Int, val maxFreqKHz: Long?)

data class CpuFacts(val cores: List<CpuCore>)

data class MemoryFacts(val pageSizeBytes: Long, val totalRamBytes: Long, val advertisedRamBytes: Long?)

data class DisplayMode(val id: Int, val width: Int, val height: Int, val refreshRateHz: Float)

data class DisplayFacts(val currentMode: DisplayMode?, val supportedModes: List<DisplayMode>)

data class AudioFacts(
    val lowLatency: Boolean,
    val pro: Boolean,
    val outputSampleRateHz: Int?,
    val outputFramesPerBuffer: Int?,
    val unprocessedCapture: Boolean,
)

/** [status] is the lower-case name of `PowerManager.THERMAL_STATUS_*`; [headroom10s] is null when unsupported. */
data class ThermalFacts(val status: String, val headroom10s: Float?)

/**
 * The runtime check: [loaded] says whether the native library loaded through the AAR's Kotlin API.
 * [noticeFiles] and [checkPackage] are the results of model-free calls; [checkPackage] holds the error an
 * empty manifest is expected to raise. [error] is set when anything failed unexpectedly.
 */
data class RuntimeFacts(
    val loaded: Boolean,
    val initMillis: Double?,
    val noticeFiles: List<String>?,
    val checkPackage: String?,
    val error: String?,
)
