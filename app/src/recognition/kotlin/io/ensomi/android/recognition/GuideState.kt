package io.ensomi.android.recognition

/** Live prerequisites, read again whenever the guide resumes. */
data class GuideState(val modelReady: Boolean, val microphone: Boolean, val music: Boolean) {
    val ready: Boolean get() = modelReady && microphone && music
}

enum class ShareDecision { START, MODEL, PERMISSIONS, NOT_SONG }

/** Untrusted shares never start work until the song and the local prerequisites are known. */
fun shareDecision(song: SharedSong?, guide: GuideState): ShareDecision = when {
    song?.namesSong != true -> ShareDecision.NOT_SONG
    !guide.modelReady -> ShareDecision.MODEL
    !guide.microphone || !guide.music -> ShareDecision.PERMISSIONS
    else -> ShareDecision.START
}
