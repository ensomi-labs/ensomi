package io.ensomi.android.settings

import android.animation.ValueAnimator
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.ensomi.android.R
import io.ensomi.android.play.FakePlayClock
import io.ensomi.android.play.PlayController
import io.ensomi.android.play.PlayTiming
import io.ensomi.android.play.PlayfieldView
import io.ensomi.android.play.SampleCharts
import io.ensomi.android.play.toRuntime
import io.ensomi.android.ui.ActionButton
import io.ensomi.android.ui.DarkSystemBars
import io.ensomi.android.ui.LocalNotWired
import io.ensomi.android.ui.theme.Brand
import io.ensomi.android.ui.theme.GameTheme
import kotlin.math.roundToInt

enum class CalibrationStep { SOUND, PICTURE }

/**
 * Offset calibration in two steps, in the game's dark presentation. Sound: a click every 500 ms, the player taps on
 * it, and the median of the last 16 taps suggests the audio offset for the output in use. Picture: the clicks go on
 * while notes fall onto the line, and the player moves the visual offset until they cross it on the click, without
 * tapping, so touch latency stays out of the visual offset.
 *
 * Prototype: the taps and the suggestion are fixed sample values and there is no click; the picture step draws with
 * the play renderer on a clock without sound. Plan step 8 builds the click track and the tap pad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    settings: PlaySettings,
    route: OutputRoute,
    initialStep: CalibrationStep,
    onChange: ((PlaySettings) -> PlaySettings) -> Unit,
    onBack: () -> Unit,
) {
    GameTheme {
        DarkSystemBars()
        var step by rememberSaveable { mutableStateOf(initialStep) }
        Scaffold(
            containerColor = Brand.Plum,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    title = { Text(stringResource(R.string.calibration_title)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand.Plum),
                )
            },
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                val wide = maxWidth > maxHeight
                Column(
                    Modifier.widthIn(max = if (wide) 900.dp else 560.dp).fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        CalibrationStep.entries.forEachIndexed { index, entry ->
                            SegmentedButton(
                                selected = step == entry,
                                onClick = { step = entry },
                                shape = SegmentedButtonDefaults.itemShape(index, CalibrationStep.entries.size),
                            ) {
                                Text(stringResource(if (entry == CalibrationStep.SOUND) R.string.calibration_sound else R.string.calibration_picture))
                            }
                        }
                    }
                    when (step) {
                        CalibrationStep.SOUND -> SoundStep(settings, route, wide)
                        CalibrationStep.PICTURE -> PictureStep(settings, wide, onChange)
                    }
                }
            }
        }
    }
}

@Composable
private fun SoundStep(settings: PlaySettings, route: OutputRoute, wide: Boolean) {
    val notWired = LocalNotWired.current
    val taps = SAMPLE_TAP_ERRORS_MS
    val median = taps.sorted().let { (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0 }
    val suggestion = settings.audioOffsetMs - median.roundToInt()
    val info: @Composable () -> Unit = {
        Text(stringResource(R.string.calibration_sound_help), style = MaterialTheme.typography.bodyMedium, color = Brand.MutedText)
        Text(
            stringResource(R.string.calibration_for_output, route.name, PlaySettings.formatOffset(settings.audioOffsetMs)),
            style = MaterialTheme.typography.bodySmall,
            color = Brand.MutedText,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.calibration_suggested), style = MaterialTheme.typography.labelLarge, color = Brand.Lavender)
                Text(
                    PlaySettings.formatOffset(suggestion),
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    color = Brand.Text,
                )
            }
            Text(
                stringResource(R.string.calibration_taps, taps.size, CALIBRATION_TAPS),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = Brand.MutedText,
            )
        }
        TapStrip(taps)
    }
    // The pad: the real one is a View, so taps carry their MotionEvent time to the nanosecond.
    val pad: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier
                .background(Brand.Panel, RoundedCornerShape(28.dp))
                .border(BorderStroke(1.dp, Brand.PanelBorder), RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.calibration_tap_here), style = MaterialTheme.typography.titleLarge, color = Brand.MutedText)
        }
    }
    val actions: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp),
        ) {
            TextButton(onClick = { notWired("Starting the taps over (plan step 8)") }) {
                Text(stringResource(R.string.calibration_start_over), color = Brand.Lavender)
            }
            ActionButton(
                stringResource(R.string.calibration_apply, PlaySettings.formatOffset(suggestion)),
                onClick = { notWired("Applying the calibration (plan step 8)") },
                icon = R.drawable.ic_check_circle,
                modifier = Modifier.weight(1f),
            )
        }
    }
    // Wider than tall: the pad beside the readings, so it keeps its size in landscape.
    if (wide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                info()
                actions()
            }
            pad(Modifier.weight(1f).fillMaxHeight().padding(bottom = 12.dp))
        }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            info()
            pad(Modifier.fillMaxWidth().weight(1f))
            actions()
        }
    }
}

/** The last taps on a line from 100 ms early to 100 ms late, the newest brightest. */
@Composable
private fun TapStrip(errorsMs: List<Int>) {
    val description = stringResource(R.string.calibration_strip_description)
    Column {
        Canvas(Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = description }) {
            val mid = size.width / 2
            drawLine(Brand.PanelBorder, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
            drawLine(Brand.Text.copy(alpha = 0.7f), Offset(mid, 4f), Offset(mid, size.height - 4f), strokeWidth = 2f)
            errorsMs.forEachIndexed { index, error ->
                val x = mid + (error.coerceIn(-100, 100) / 100f) * mid
                val alpha = 0.35f + 0.65f * (index + 1) / errorsMs.size
                drawCircle(if (error < 0) Brand.Lavender else Brand.Rose, radius = 6f * density, center = Offset(x, size.height / 2), alpha = alpha)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.results_early), style = MaterialTheme.typography.labelSmall, color = Brand.Lavender, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.results_late), style = MaterialTheme.typography.labelSmall, color = Brand.Rose, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PictureStep(settings: PlaySettings, wide: Boolean, onChange: ((PlaySettings) -> PlaySettings) -> Unit) {
    val notWired = LocalNotWired.current
    val reduceMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    // The play renderer, drawing a note on every click; nothing is judged. Beside the controls when the window is
    // wider than tall, so the field keeps its height in landscape.
    val playfield: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier
                .border(BorderStroke(1.dp, Brand.PanelBorder), RoundedCornerShape(20.dp)),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(1.dp),
                factory = { context ->
                    PlayfieldView(context).apply {
                        hudHeightDp = 12f
                        controllerFactory = { hud, sequence ->
                            PlayController(
                                chart = SampleCharts.CALIBRATION,
                                judge = settings.judge.toRuntime(),
                                clock = FakePlayClock(),
                                timing = PlayTiming(0.0, settings.visualOffsetMs.toDouble(), settings.scrollTimeMs),
                                judging = false,
                                loop = true,
                                leadInMs = 0.0,
                                hud = hud,
                                nextSequence = sequence,
                            )
                        }
                    }
                },
                update = {
                    it.reduceMotion = reduceMotion
                    it.visualOffsetMs = settings.visualOffsetMs.toDouble()
                    // The player's lean, so notes cross the line as they will in play.
                    it.landscapeFarScale = PlaySettings.farScale(settings.landscapeLean)
                    it.portraitFarScale = PlaySettings.farScale(settings.portraitLean)
                },
            )
        }
    }
    val controls: @Composable () -> Unit = {
        val visual = stringResource(R.string.settings_visual_offset)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(visual, style = MaterialTheme.typography.bodyLarge, color = Brand.Text, modifier = Modifier.weight(1f))
            Text(
                PlaySettings.formatOffset(settings.visualOffsetMs),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = Brand.Text,
            )
        }
        Slider(
            value = settings.visualOffsetMs.toFloat(),
            onValueChange = { value -> onChange { it.copy(visualOffsetMs = value.roundToInt()) } },
            valueRange = -100f..100f,
            modifier = Modifier.semantics {
                contentDescription = visual
                stateDescription = PlaySettings.formatOffset(settings.visualOffsetMs)
            },
        )
        ActionButton(
            stringResource(R.string.calibration_done),
            onClick = { notWired("Saving the visual offset (plan step 1)") },
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp),
        )
    }
    if (wide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            playfield(Modifier.weight(1f).fillMaxHeight().padding(bottom = 12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.calibration_picture_help), style = MaterialTheme.typography.bodyMedium, color = Brand.MutedText)
                controls()
            }
        }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.calibration_picture_help), style = MaterialTheme.typography.bodyMedium, color = Brand.MutedText)
            playfield(Modifier.fillMaxWidth().weight(1f))
            controls()
        }
    }
}

/** Sample taps for the prototype: about 22 ms late, as a speaker's unreported latency might be. */
private val SAMPLE_TAP_ERRORS_MS = listOf(18, 25, 21, 30, 16, 22, 27, 19, 24, 20, 23, 26)
private const val CALIBRATION_TAPS = 16
