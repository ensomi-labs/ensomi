package io.ensomi.capture

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * The sidecar written beside a recorded take. Schema v1 is the Apple recorder's format; v2 keeps
 * every v1 field and adds what a recorded condition needs (capture source, device, distance,
 * bystanders, consent, latency). See `docs/sonalign-capture.md`.
 */
@Serializable
enum class CaptureSource {
    @SerialName("mic")
    MIC,

    @SerialName("loopback")
    LOOPBACK,

    @SerialName("micAndLoopback")
    MIC_AND_LOOPBACK,
}

@Serializable
data class TakeTargetAsset(
    val id: String,
    val displayPath: String,
    val fileName: String,
    val sha256: String,
    val durationMS: Int,
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val isrc: String? = null,
)

@Serializable
data class TakeManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val recordingID: String,
    val createdAt: String,
    val stoppedAt: String,
    val durationMS: Int,
    val audioFileName: String,
    val recordingGroup: String,
    val takeLabel: String,
    val sampleRate: Double,
    val channelCount: Int,
    val targetAsset: TakeTargetAsset,
    val notes: String? = null,
    val captureSource: CaptureSource,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val commit: String,
    val outputLatencyMS: Double? = null,
    val reportsLatency: Boolean? = null,
    val distanceM: Double? = null,
    val room: String? = null,
    val bystandersAudible: Boolean = false,
    val consentRecordedAt: String? = null,
    val consentExpiresAt: String? = null,
    val storageAlias: String,
) {
    companion object {
        const val SCHEMA_VERSION = 2
    }
}

/** The JSON spelling of a manifest. Unknown keys are ignored so a newer writer stays readable. */
val TakeManifestJson: Json = Json {
    encodeDefaults = true
    prettyPrint = true
    ignoreUnknownKeys = true
}

/**
 * Answers which fields a take is missing or contradicts. An empty list means the take can be kept;
 * the caller reports field names rather than writing metadata a reader would have to guess at.
 */
object TakeManifestValidator {
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun validate(manifest: TakeManifest): List<String> {
        val errors = mutableListOf<String>()

        if (manifest.schemaVersion != TakeManifest.SCHEMA_VERSION) {
            errors += "schemaVersion"
        }
        if (runCatching { UUID.fromString(manifest.recordingID) }.isFailure) {
            errors += "recordingID"
        }

        val created = parseInstant(manifest.createdAt)
        val stopped = parseInstant(manifest.stoppedAt)
        if (created == null) errors += "createdAt"
        if (stopped == null) errors += "stoppedAt"
        if (created != null && stopped != null && stopped.isBefore(created)) {
            errors += "stoppedAt"
        }

        if (manifest.durationMS <= 0) errors += "durationMS"
        if (manifest.audioFileName.isBlank() || hasPathSeparator(manifest.audioFileName)) {
            errors += "audioFileName"
        }
        if (manifest.recordingGroup.isBlank()) errors += "recordingGroup"
        if (manifest.takeLabel.isBlank()) errors += "takeLabel"
        if (manifest.sampleRate <= 0) errors += "sampleRate"
        if (manifest.channelCount <= 0) errors += "channelCount"

        if (manifest.captureSource == CaptureSource.MIC_AND_LOOPBACK && manifest.channelCount < 2) {
            errors += "channelCount"
        }
        if (manifest.deviceModel.isBlank()) errors += "deviceModel"
        if (manifest.osVersion.isBlank()) errors += "osVersion"
        if (manifest.appVersion.isBlank()) errors += "appVersion"
        if (manifest.commit.isBlank()) errors += "commit"
        if (manifest.storageAlias.isBlank()) errors += "storageAlias"

        if (runCatching { UUID.fromString(manifest.targetAsset.id) }.isFailure) {
            errors += "targetAsset.id"
        }
        if (manifest.targetAsset.fileName.isBlank()) errors += "targetAsset.fileName"
        if (!SHA256.matches(manifest.targetAsset.sha256)) errors += "targetAsset.sha256"
        if (manifest.targetAsset.durationMS <= 0) errors += "targetAsset.durationMS"

        manifest.outputLatencyMS?.let { if (it < 0) errors += "outputLatencyMS" }
        if (manifest.reportsLatency == true && manifest.outputLatencyMS == null) {
            errors += "outputLatencyMS"
        }
        manifest.distanceM?.let { if (it <= 0) errors += "distanceM" }

        val consent = parseInstant(manifest.consentRecordedAt)
        if (manifest.bystandersAudible && manifest.consentRecordedAt == null) {
            errors += "consentRecordedAt"
        }
        if (manifest.consentRecordedAt != null && consent == null) {
            errors += "consentRecordedAt"
        }
        val expiry = parseInstant(manifest.consentExpiresAt)
        if (manifest.consentExpiresAt != null && expiry == null) {
            errors += "consentExpiresAt"
        }
        if (consent != null && expiry != null && !expiry.isAfter(consent)) {
            errors += "consentExpiresAt"
        }

        return errors.distinct()
    }

    private fun parseInstant(value: String?): Instant? =
        value?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun hasPathSeparator(value: String): Boolean =
        value.contains('/') || value.contains('\\')
}
