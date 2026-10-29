package io.ensomi.android.play

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ensomi.android.R
import io.ensomi.android.settings.OutputRoute
import io.ensomi.android.settings.PlaySettings
import io.ensomi.android.ui.ActionButton
import io.ensomi.android.ui.DarkSystemBars
import io.ensomi.android.ui.theme.Brand
import io.ensomi.android.ui.theme.GameTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The end of a game, in the game's dark presentation: accuracy, the judgement counts in words and colour, the longest
 * combo, how early or late the hits were, and the runtime's suggested audio offset change.
 */
@Composable
fun ResultsScreen(
    result: PlayResult,
    settings: PlaySettings,
    route: OutputRoute,
    onChange: ((PlaySettings) -> PlaySettings) -> Unit,
    onPlayAgain: () -> Unit,
    onDone: () -> Unit,
) {
    GameTheme {
        DarkSystemBars()
        Box(Modifier.fillMaxSize().background(Brand.Plum), contentAlignment = Alignment.TopCenter) {
            // Wider than tall (the game's landscape, which the results keep): the score on the left, the timing on the
            // right, each scrolling on its own if it must, and the actions held at the bottom right, on the first screen.
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val wide = maxWidth > maxHeight && maxWidth >= 600.dp
                val header: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.results_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = Brand.Lavender,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(result.title, style = MaterialTheme.typography.headlineSmall, color = Brand.Text)
                        Text(result.detail, style = MaterialTheme.typography.bodyMedium, color = Brand.MutedText)
                    }
                }
                val accuracy: @Composable (Modifier) -> Unit = { modifier ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
                        Text(
                            formatAccuracy(result.accuracy),
                            style = (if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium)
                                .copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                            color = Brand.Text,
                        )
                        Text(stringResource(R.string.play_accuracy), style = MaterialTheme.typography.titleSmall, color = Brand.MutedText)
                    }
                }
                val counts: @Composable () -> Unit = {
                    val tiers = listOf(
                        Triple(stringResource(R.string.judgement_perfect), result.perfect, Brand.Perfect),
                        Triple(stringResource(R.string.judgement_good), result.good, Brand.Good),
                        Triple(stringResource(R.string.judgement_miss), result.miss, Brand.Miss),
                    )
                    // Three tiles side by side at normal text sizes; one row per tier at large ones, so no word breaks.
                    if (LocalDensity.current.fontScale <= 1.3f) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            tiers.forEach { (label, count, color) -> CountTile(label, count, color, Modifier.weight(1f)) }
                        }
                    } else {
                        Panel {
                            tiers.forEach { (label, count, color) -> CountRow(label, count, color) }
                        }
                    }
                }
                val combo: @Composable () -> Unit = {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.results_max_combo),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Brand.MutedText,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                result.maxCombo.toString(),
                                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                                color = Brand.Text,
                            )
                        }
                    }
                }
                val details: @Composable () -> Unit = {
                    Panel {
                        Text(stringResource(R.string.results_timing), style = MaterialTheme.typography.titleSmall, color = Brand.Text)
                        Text(timingSummary(result.meanOffsetMs), style = MaterialTheme.typography.bodyMedium, color = Brand.MutedText)
                        TimingHistogram(result.histogram, Modifier.fillMaxWidth().height(72.dp).padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.results_early), style = MaterialTheme.typography.labelSmall, color = Brand.Lavender, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.results_late), style = MaterialTheme.typography.labelSmall, color = Brand.Rose, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                        }
                    }
                    val adjustment = result.suggestedAdjustmentMs
                    if (adjustment != null && abs(adjustment) >= MIN_SUGGESTION_MS) {
                        Panel {
                            // The game was played at the offset the output had when the results opened.
                            val playedAt = rememberSaveable(route.key) { settings.audioOffsetMs }
                            val proposed = (playedAt + adjustment).coerceIn(PlaySettings.OFFSETS)
                            if (settings.audioOffsetMs == proposed) {
                                Text(
                                    stringResource(R.string.results_offset_used, route.name, PlaySettings.formatOffset(proposed)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Brand.Text,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            } else {
                                Text(
                                    stringResource(
                                        R.string.results_offset_suggestion,
                                        route.name,
                                        PlaySettings.formatOffset(settings.audioOffsetMs),
                                        PlaySettings.formatOffset(proposed),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Brand.Text,
                                )
                                TextButton(onClick = { onChange { it.copy(audioOffsetMs = proposed) } }) {
                                    Text(stringResource(R.string.results_offset_apply), color = Brand.Lavender)
                                }
                            }
                        }
                    }
                    if (result.sample) {
                        Text(stringResource(R.string.results_sample_note), style = MaterialTheme.typography.bodySmall, color = Brand.MutedText)
                    }
                }
                val playAgain: @Composable (Modifier) -> Unit = { modifier ->
                    ActionButton(stringResource(R.string.results_play_again), onClick = onPlayAgain, icon = R.drawable.ic_refresh, modifier = modifier)
                }
                val done: @Composable (Modifier) -> Unit = { modifier ->
                    OutlinedButton(onClick = onDone, modifier = modifier.height(48.dp)) {
                        Text(stringResource(R.string.results_done), color = Brand.Text)
                    }
                }
                if (wide) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 1000.dp)
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                header()
                                // Accuracy and the longest combo side by side, so the score fits the short side.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    accuracy(Modifier.weight(1f))
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                        Text(
                                            result.maxCombo.toString(),
                                            style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"),
                                            color = Brand.Text,
                                        )
                                        Text(stringResource(R.string.results_max_combo), style = MaterialTheme.typography.titleSmall, color = Brand.MutedText)
                                    }
                                }
                                counts()
                            }
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) { details() }
                        }
                        // Under the right column; across the screen at large text sizes, so the labels fit.
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (LocalDensity.current.fontScale <= 1.3f) Spacer(Modifier.weight(1f))
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                done(Modifier.weight(1f))
                                playAgain(Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    Column(
                        // The insets pad the content inside the scroll, so it scrolls behind the system bars.
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        header()
                        accuracy(Modifier.fillMaxWidth())
                        counts()
                        combo()
                        details()
                        Spacer(Modifier.height(4.dp))
                        playAgain(Modifier.fillMaxWidth())
                        done(Modifier.fillMaxWidth())
                    }
                }
            }
            // The content scrolls behind the status bar; an opaque plum band hides it there, under the clock and icons.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(Brand.Plum),
            )
        }
    }
}

@Composable
private fun timingSummary(meanMs: Double?): String = when {
    meanMs == null -> stringResource(R.string.results_timing_none)
    abs(meanMs) < 0.5 -> stringResource(R.string.results_timing_centred)
    meanMs < 0 -> stringResource(R.string.results_timing_early, String.format(Locale.ROOT, "%.0f", -meanMs))
    else -> stringResource(R.string.results_timing_late, String.format(Locale.ROOT, "%.0f", meanMs))
}

@Composable
private fun CountTile(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Brand.Panel,
        border = BorderStroke(1.dp, Brand.PanelBorder),
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label $count" },
    ) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            }
            Text(
                count.toString(),
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                color = Brand.Text,
            )
        }
    }
}

@Composable
private fun CountRow(label: String, count: Int, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label $count" },
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = Brand.Text)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 8.dp),
        color = Brand.Panel,
        border = BorderStroke(1.dp, Brand.PanelBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/** Hits per 10 ms of error, early in lavender on the left, late in rose on the right, a line at zero. */
@Composable
private fun TimingHistogram(bins: List<Int>, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.results_histogram_description)
    Canvas(modifier.semantics { contentDescription = description }) {
        val max = (bins.maxOrNull() ?: 0).coerceAtLeast(1)
        val barWidth = size.width / bins.size
        bins.forEachIndexed { index, count ->
            if (count == 0) return@forEachIndexed
            val height = size.height * count / max
            val early = index < bins.size / 2
            drawRoundRect(
                color = if (early) Brand.Lavender else Brand.Rose,
                topLeft = Offset(index * barWidth + 1f, size.height - height),
                size = Size((barWidth - 2f).coerceAtLeast(1f), height),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
        drawLine(Brand.Text.copy(alpha = 0.6f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2f)
    }
}

private const val MIN_SUGGESTION_MS = 3

/** Fixed numbers for the results scenario of the prototype. */
val SAMPLE_RESULT = PlayResult(
    title = "Lantern Walk",
    detail = "Mira Sato · 3.5 stars · seed 2914",
    accuracy = 0.9784,
    perfect = 1102,
    good = 119,
    miss = 8,
    maxCombo = 642,
    histogram = listOf(0, 0, 0, 1, 1, 2, 4, 6, 11, 19, 33, 61, 98, 141, 170, 182, 160, 128, 92, 60, 37, 22, 12, 6, 3, 2, 1, 0, 0, 0),
    meanOffsetMs = 6.4,
    suggestedAdjustmentMs = (-6.4).roundToInt(),
    sample = false,
)
