package io.ensomi.android.recognition

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ensomi.android.keyboard.Keyboards
import io.ensomi.android.play.GameOrientation
import io.ensomi.android.play.PlayScreen
import io.ensomi.android.settings.PlaySettings

/** The public recognition entry and its shared-song session. */
object RecognitionEntry {
    fun openGuide(context: Context) = RecognitionController.get(context).showGuide()

    /** Calls [open] each time something wants the session's screen: a share, a session picked up, a game to join. */
    suspend fun followOpens(context: Context, open: () -> Unit) {
        val controller = RecognitionController.get(context)
        controller.opens.collect { wanted ->
            if (wanted) {
                controller.opened()
                open()
            }
        }
    }

    /** The session's screen, or its game once it has joined the music. */
    @Composable
    fun Content(
        settings: PlaySettings,
        keyboards: Keyboards,
        modelReady: Boolean,
        waitingForLibrary: Boolean = false,
        onBack: () -> Unit,
    ) {
        val context = LocalContext.current
        val controller = remember(context) { RecognitionController.get(context) }
        val game by controller.game.collectAsStateWithLifecycle()
        val session by controller.session.collectAsStateWithLifecycle()
        val current = game
        if (current == null) {
            if (session == null) RecognitionGuide(controller, modelReady, onBack)
            else RecognitionScreen(controller, onBack, waitingForLibrary)
        } else {
            GameOrientation(settings.orientation)
            // Before the game's own: it takes Back to pause, and this is the Back after that.
            BackHandler(onBack = controller::gameQuit)
            PlayScreen(
                title = session?.audio?.title.orEmpty(),
                detail = session?.audio?.artist.orEmpty(),
                settings = settings,
                loop = false,
                onFinished = controller::gameFinished,
                onQuit = controller::gameQuit,
                keyboards = keyboards,
                ambient = current,
            )
        }
    }
}
