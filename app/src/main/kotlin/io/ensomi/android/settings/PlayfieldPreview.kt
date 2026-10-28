package io.ensomi.android.settings

import android.animation.ValueAnimator
import android.app.Activity
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.ensomi.android.R
import io.ensomi.android.play.Autoplay
import io.ensomi.android.play.FakePlayClock
import io.ensomi.android.play.PlayController
import io.ensomi.android.play.PlayTiming
import io.ensomi.android.play.PlayfieldView
import io.ensomi.android.play.SampleCharts
import io.ensomi.android.play.toRuntime
import kotlin.math.max
import kotlin.math.min

/**
 * The playfield as play will draw it, only smaller: the sample chart playing itself on the game's own renderer, in the
 * orientation chosen above, following the lean and the scroll speed while they are dragged. The surface is the phone's
 * whole screen in that orientation, which the system scales down to the box (`SurfaceHolder.setFixedSize`), so what is
 * shown is the game's layout at the game's proportions. It takes no touches.
 */
@Composable
fun PlayfieldPreview(settings: PlaySettings, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val screen = remember(view) {
        val bounds = (view.context as? Activity)?.windowManager?.maximumWindowMetrics?.bounds
        val width = bounds?.width() ?: view.resources.displayMetrics.widthPixels
        val height = bounds?.height() ?: view.resources.displayMetrics.heightPixels
        max(width, height) to min(width, height)
    }
    val landscape = settings.orientation == PlayOrientation.LANDSCAPE
    val size = if (landscape) screen else screen.second to screen.first
    val reduceMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    val description = stringResource(
        R.string.settings_playfield_preview_description,
        stringResource(if (landscape) R.string.settings_orientation_landscape else R.string.settings_orientation_portrait),
        leanText(settings.lean(settings.orientation)),
    )
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .then(if (landscape) Modifier.fillMaxWidth() else Modifier.height(PORTRAIT_PREVIEW_HEIGHT.dp))
                .aspectRatio(size.first.toFloat() / size.second)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(4.dp))
                .padding(1.dp)
                .semantics { contentDescription = description },
        ) {
            // A new surface when the orientation changes, since its buffer's shape does.
            key(landscape) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayfieldView(context).apply {
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                            previewSize = size
                            controllerFactory = { hud, sequence ->
                                PlayController(
                                    chart = SampleCharts.PREVIEW,
                                    judge = settings.judge.toRuntime(),
                                    clock = FakePlayClock(),
                                    timing = PlayTiming(0.0, settings.visualOffsetMs.toDouble(), settings.scrollTimeMs),
                                    script = Autoplay.inputs(SampleCharts.PREVIEW),
                                    loop = true,
                                    leadInMs = PREVIEW_LEAD_IN_MS,
                                    hud = hud,
                                    nextSequence = sequence,
                                )
                            }
                        }
                    },
                    update = {
                        it.landscapeFarScale = PlaySettings.farScale(settings.landscapeLean)
                        it.portraitFarScale = PlaySettings.farScale(settings.portraitLean)
                        it.scrollTimeMs = settings.scrollTimeMs
                        it.reduceMotion = reduceMotion
                    },
                )
            }
        }
    }
}

/** "Flat" for no lean, otherwise its angle: "40°". */
@Composable
fun leanText(lean: Int): String =
    if (lean == 0) stringResource(R.string.settings_lean_flat) else stringResource(R.string.settings_lean_value, lean)

/** Short: the notes start falling almost at once. */
private const val PREVIEW_LEAD_IN_MS = 300.0
private const val PORTRAIT_PREVIEW_HEIGHT = 300
