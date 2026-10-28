package io.ensomi.acquisition

import kotlinx.serialization.Serializable

/**
 * What a recognizer's position value means. The report (`deep-research-report.md`, "识曲位置复用")
 * lists six candidate readings of NetEase's `startTime`; until the calibration experiment on the
 * target device says which one holds, a client must treat it as [UNKNOWN] and never use it as a
 * playback position. Naming it after the field would otherwise hide the guess in product code.
 */
@Serializable
enum class PositionSemantics {
    UNKNOWN,
    CAPTURE_START,
    CAPTURE_MIDPOINT,
    CAPTURE_END,
    ALIGNMENT_OFFSET,
}

/**
 * How far a claim has been checked. The report distinguishes public reverse-engineering sources from
 * what the installed APK and the target device actually do, and the difference is what decides which
 * acquisition level a client may take.
 */
@Serializable
enum class EvidenceLevel {
    /** An assumption nothing has checked yet. */
    UNVERIFIED,

    /** A public implementation or response sample shows it, but not this device. */
    PUBLIC_SOURCE,

    /** Observed on the target device and APK. */
    DEVICE_CONFIRMED,
}

/**
 * The fallback ladder, highest capability first. A client picks the deepest level its capability probe
 * passes, and never a deeper one on the strength of an unverified assumption.
 */
enum class AcquisitionLevel(val depth: Int, val summary: String) {
    EXISTING_LOCAL_AUDIO(0, "already-indexed local audio; no download"),
    DIRECT_RECOGNITION_ROOT_DOWNLOAD(1, "direct recognition + root download bridge, fully headless"),
    DIRECT_RECOGNITION_DEEP_LINK_DOWNLOAD(2, "direct recognition + song deep link + UI download"),
    NETEASE_UI_RECOGNITION(3, "NetEase recognition UI, then result and download"),
    MANUAL(4, "manual NetEase interaction, diagnostics only"),
}

/** The result of recognizing an audio window, with what is known about its position reading. */
@Serializable
data class RecognitionResult(
    val songId: Long?,
    val title: String? = null,
    val artist: String? = null,
    val recordingDurationMs: Long? = null,
    val rawPositionValue: Long? = null,
    val positionSemantics: PositionSemantics = PositionSemantics.UNKNOWN,
    val evidence: EvidenceLevel = EvidenceLevel.UNVERIFIED,
) {
    /**
     * Whether [rawPositionValue] may anchor playback. False until the semantics are measured: this is
     * the rule the report states as "在实验完成前必须是 UNKNOWN".
     */
    val positionIsUsable: Boolean
        get() = rawPositionValue != null && positionSemantics != PositionSemantics.UNKNOWN
}
