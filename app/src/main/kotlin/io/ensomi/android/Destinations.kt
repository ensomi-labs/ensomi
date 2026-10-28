package io.ensomi.android

import androidx.navigation3.runtime.NavKey
import io.ensomi.android.play.PlayResult
import kotlinx.serialization.Serializable

// Each screen is a key on the back stack. To add a screen, declare its key here and its entry in EnsomiApp.kt; a
// screen navigates through callbacks that push keys, so screens never refer to each other.

/** The songs, and the chart model until it is installed. The start destination. */
@Serializable
data object Library : NavKey

/** Making a chart for one song. */
@Serializable
data class MakeChart(val songId: String) : NavKey

/** A game. [loop] plays the chart again and again (the preview from Settings) instead of ending at results. */
@Serializable
data class Play(val songId: String?, val loop: Boolean) : NavKey

/** The end of a game; Back returns to the song, not into the finished game. */
@Serializable
data class Results(val songId: String?, val result: PlayResult) : NavKey

@Serializable
data object Settings : NavKey

@Serializable
data object Calibration : NavKey

@Serializable
data object Diagnostics : NavKey
