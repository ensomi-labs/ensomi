package io.ensomi.android.recognition

/** Live prerequisites, read again whenever the guide resumes. */
data class GuideState(val modelReady: Boolean, val microphone: Boolean, val music: Boolean) {
    val ready: Boolean get() = modelReady && microphone && music
}

enum class ShareDecision { START, MODEL, PERMISSIONS, NOT_SONG, RESTORE }

/** Untrusted shares never start work until the song and the local prerequisites are known. */
fun shareDecision(song: SharedSong?, guide: GuideState): ShareDecision = when {
    song?.namesSong != true -> ShareDecision.NOT_SONG
    !guide.modelReady -> ShareDecision.MODEL
    !guide.microphone || !guide.music -> ShareDecision.PERMISSIONS
    else -> ShareDecision.START
}

/** The guide gives a recent download its proper place in this attempt. */
fun sessionBeginning(guide: SessionNow?, share: SessionNow): SessionNow =
    guide?.takeIf { share.nanos - it.nanos in 0..1_800_000_000_000L && share.wallMs - it.wallMs in 0..1_800_000L } ?: share

/** Restoration is dormant until a person opens Listen & Play; an expired wait is discarded. */
fun canResumeSnapshot(saved: SessionState, now: SessionNow, limits: SessionLimits): Boolean {
    if (!saved.running || saved.stage(Stage.PLAY).status == StageStatus.RUNNING) return false
    val age = (now.nanos - saved.startedNanos) / 1_000_000
    if (age !in 0..limits.restoreMs) return false
    val wait = saved.audioWaitSinceNanos
    return saved.audio != null || (saved.titleOfferPaths.isNotEmpty() && saved.titleOfferSinceNanos?.let {
        (now.nanos - it) / 1_000_000 in 0 until limits.titleOfferMs
    } == true) || (saved.titleOfferPaths.isEmpty() && (wait == null ||
        (now.nanos - wait) / 1_000_000 < limits.audioWaitMs))
}
