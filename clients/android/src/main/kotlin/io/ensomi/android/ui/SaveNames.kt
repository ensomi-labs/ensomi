package io.ensomi.android.ui

/** A name the save picker accepts: characters the platform dislikes become underscores. */
internal fun osuName(title: String): String =
    title.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifBlank { "chart" } + " (Ensomi).osu"

/** The archive keeps the song's name, so it sits beside the .osu in a folder of charts. */
internal fun oszName(title: String): String =
    title.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifBlank { "chart" } + " (Ensomi).osz"

/** The `m:ss` a setup shows, and "unknown" when the file would not read. */
internal fun formatLength(durationMs: Long?): String {
    if (durationMs == null || durationMs <= 0) return "unknown"
    val totalSeconds = durationMs / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** A song's length as a listener says it: `3:07`, never milliseconds. */
fun formatSongLength(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    return "${totalSeconds / 60}:%02d".format(totalSeconds % 60)
}
