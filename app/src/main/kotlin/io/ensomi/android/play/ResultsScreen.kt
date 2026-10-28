package io.ensomi.android.play

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ensomi.android.R
import io.ensomi.android.settings.OutputRoute
import io.ensomi.android.settings.PlaySettings
import io.ensomi.android.ui.ActionButton
import io.ensomi.android.ui.DarkSystemBars
import io.ensomi.android.ui.LocalNotWired
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
    onPlayAgain: () -> Unit,
    onDone: () -> Unit,
) {
    GameTheme {
        DarkSystemBars()
        val notWired = LocalNotWired.current
        Box(Modifier.fillMaxSize().background(Brand.Plum), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
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

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        formatAccuracy(result.accuracy),
                        style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold,
                        color = Brand.Text,
                    )
                    Text(stringResource(R.string.play_accuracy), style = MaterialTheme.typography.titleSmall, color = Brand.MutedText)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    CountTile(stringResource(R.string.judgement_perfect), result.perfect, Brand.Perfect, Modifier.weight(1f))
                    CountTile(stringResource(R.string.judgement_good), result.good, Brand.Good, Modifier.weight(1f))
                    CountTile(stringResource(R.string.judgement_miss), result.miss, Brand.Miss, Modifier.weight(1f))
                }

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
                        val proposed = settings.audioOffsetMs + adjustment
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
                        TextButton(onClick = { notWired("Applying the suggested offset (plan step 1)") }) {
                            Text(stringResource(R.string.results_offset_apply), color = Brand.Lavender)
                        }
                    }
                }

                if (result.sample) {
                    Text(stringResource(R.string.results_sample_note), style = MaterialTheme.typography.bodySmall, color = Brand.MutedText)
                }

                Spacer(Modifier.height(4.dp))
                ActionButton(stringResource(R.string.results_play_again), onClick = onPlayAgain, icon = R.drawable.ic_refresh, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.results_done), color = Brand.Text)
                }
            }
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
