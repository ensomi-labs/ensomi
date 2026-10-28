package io.ensomi.android.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The settings the Android client can change today. It stores the Apple keys so a value means the same
 * thing across clients; the sections S5 lists for a device the client cannot offer yet are absent
 * rather than shown disabled.
 */
class EnsomiSettings(context: Context) {
    private val prefs = context.getSharedPreferences(SettingsKeys.STORE, Context.MODE_PRIVATE)

    var scrollSpeed by mutableStateOf(
        SettingsRules.snapScrollSpeed(
            prefs.getFloat(SettingsKeys.SCROLL_SPEED, SettingsKeys.DEFAULT_SCROLL_SPEED),
        ),
    )
        private set

    var judgeDifficulty by mutableStateOf(
        SettingsRules.difficultyOrFallback(prefs.getString(SettingsKeys.JUDGE_DIFFICULTY, null)),
    )
        private set

    var musicVolume by mutableStateOf(
        SettingsRules.clampVolume(prefs.getFloat(SettingsKeys.MUSIC_VOLUME, SettingsKeys.DEFAULT_MUSIC_VOLUME)),
    )
        private set

    var visualOffsetMs by mutableStateOf(
        prefs.getFloat(SettingsKeys.VISUAL_OFFSET_MS, SettingsKeys.DEFAULT_VISUAL_OFFSET_MS),
    )
        private set

    fun updateScrollSpeed(value: Float) {
        scrollSpeed = SettingsRules.snapScrollSpeed(value)
        prefs.edit().putFloat(SettingsKeys.SCROLL_SPEED, scrollSpeed).apply()
    }

    fun updateJudgeDifficulty(value: String) {
        judgeDifficulty = SettingsRules.difficultyOrFallback(value)
        prefs.edit().putString(SettingsKeys.JUDGE_DIFFICULTY, judgeDifficulty).apply()
    }

    fun updateMusicVolume(value: Float) {
        musicVolume = SettingsRules.clampVolume(value)
        prefs.edit().putFloat(SettingsKeys.MUSIC_VOLUME, musicVolume).apply()
    }

    fun updateVisualOffsetMs(value: Float) {
        visualOffsetMs = value.coerceIn(-500f, 500f)
        prefs.edit().putFloat(SettingsKeys.VISUAL_OFFSET_MS, visualOffsetMs).apply()
    }
}
