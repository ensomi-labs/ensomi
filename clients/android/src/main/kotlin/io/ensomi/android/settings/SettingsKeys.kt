package io.ensomi.android.settings

/**
 * The setting keys and their bounds. The keys keep the Apple clients' spelling, so a value written by
 * one client means the same here; the bounds are the ones S5 states.
 */
object SettingsKeys {
    const val STORE = "mania4k"

    const val SCROLL_SPEED = "mania4k.scrollSpeed"
    const val JUDGE_DIFFICULTY = "mania4k.judgeDifficulty"
    const val MUSIC_VOLUME = "mania4k.musicVolume"
    const val VISUAL_OFFSET_MS = "mania4k.visualOffsetMs"

    const val MIN_SCROLL_SPEED = 1.0f
    const val MAX_SCROLL_SPEED = 40.0f
    const val DEFAULT_SCROLL_SPEED = 8.0f
    const val DEFAULT_MUSIC_VOLUME = 1.0f
    const val DEFAULT_VISUAL_OFFSET_MS = 0f

    /** The judge difficulties the core names A through E. */
    val DIFFICULTIES = listOf("A", "B", "C", "D", "E")
    const val DEFAULT_DIFFICULTY = "B"
}

/** The arithmetic of the controls, kept out of the Compose layer so a test can state it. */
object SettingsRules {
    fun clampScrollSpeed(value: Float): Float =
        value.coerceIn(SettingsKeys.MIN_SCROLL_SPEED, SettingsKeys.MAX_SCROLL_SPEED)

    /** Round to the 0.1 step the control shows, so a stored value never disagrees with its display. */
    fun snapScrollSpeed(value: Float): Float =
        clampScrollSpeed(kotlin.math.round(value * 10f) / 10f)

    fun clampVolume(value: Float): Float = value.coerceIn(0f, 1f)

    /** A difficulty the core does not name falls back rather than reaching it. */
    fun difficultyOrFallback(value: String?): String =
        value?.takeIf { it in SettingsKeys.DIFFICULTIES } ?: SettingsKeys.DEFAULT_DIFFICULTY
}
