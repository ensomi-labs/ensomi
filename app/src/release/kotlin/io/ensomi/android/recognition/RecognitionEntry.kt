package io.ensomi.android.recognition

import android.content.Context
import androidx.compose.runtime.Composable
import io.ensomi.android.settings.PlaySettings

/**
 * The recognition session is not in a release build (docs/recognition.md): nothing opens it and nothing shows. Debug
 * and checks builds have the real entry, in src/recognition.
 */
object RecognitionEntry {
    @Suppress("UNUSED_PARAMETER")
    suspend fun followOpens(context: Context, open: () -> Unit) = Unit

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun LibraryAction(onOpen: () -> Unit) = Unit

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun Content(settings: PlaySettings, onBack: () -> Unit) = Unit
}
