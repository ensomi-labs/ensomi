package io.ensomi.android.recognition

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ensomi.android.R
import io.ensomi.android.play.GameOrientation
import io.ensomi.android.play.PlayScreen
import io.ensomi.android.settings.PlaySettings

/**
 * What the app's main code sees of the recognition session, in the debug and checks builds that have it. A release
 * build compiles src/release's version instead, which is empty, so none of this is in it.
 */
object RecognitionEntry {
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

    @Composable
    fun LibraryAction(onOpen: () -> Unit) {
        IconButton(onClick = onOpen) {
            Icon(painterResource(R.drawable.ic_music_note), contentDescription = stringResource(R.string.recognition_title))
        }
    }

    /** The session's screen, or its game once it has joined the music. */
    @Composable
    fun Content(settings: PlaySettings, onBack: () -> Unit) {
        val context = LocalContext.current
        val controller = remember(context) { RecognitionController.get(context) }
        val game by controller.game.collectAsStateWithLifecycle()
        val session by controller.session.collectAsStateWithLifecycle()
        val current = game
        if (current == null) {
            RecognitionScreen(controller, onBack)
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
                ambient = current,
            )
        }
    }
}
