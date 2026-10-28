package io.ensomi.android.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ensomi.android.R
import io.ensomi.android.model.ModelPanel
import io.ensomi.android.model.ModelState
import io.ensomi.android.model.Problem
import io.ensomi.android.ui.ActionButton
import io.ensomi.android.ui.sliderColorsWithoutTicks
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/** The song a state belongs to, or null for [MakeState.Idle]. */
val MakeState.songId: String?
    get() = when (this) {
        MakeState.Idle -> null
        is MakeState.Making -> songId
        is MakeState.Made -> songId
        is MakeState.Stopped -> songId
        is MakeState.Failed -> songId
    }

/**
 * Making a chart for one song: stars, seed and optional sections, then Make chart. The bar at the bottom carries the
 * state: the primary action, the phases with Stop while the chart is made, the result with Play, or the failure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MakeChartScreen(
    viewModel: MakeChartViewModel,
    model: ModelState,
    onBack: () -> Unit,
    onPlay: (MadeChart) -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    /** The maker's own [MakeChartViewModel.make] and [MakeChartViewModel.stop] once it is wired. */
    onMake: () -> Unit = viewModel::make,
    onStop: () -> Unit = viewModel::stop,
) {
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val makerState by viewModel.state.collectAsStateWithLifecycle()
    val state = makerState.takeIf { it.songId == viewModel.song.id } ?: MakeState.Idle
    val making = state is MakeState.Making
    val song = viewModel.song
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(song.title, maxLines = 1) },
            )
        },
        bottomBar = {
            MakeBar(
                state = state,
                canMake = model is ModelState.Installed && viewModel.canMake(capabilities),
                modelMissing = model !is ModelState.Installed,
                onMake = onMake,
                onStop = onStop,
                onPlay = onPlay,
                onUseSeed = viewModel::useSeed,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    listOfNotNull(song.artist, ChartRequestRules.formatDuration(song.durationMs), song.fileName).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (model !is ModelState.Installed) {
                    ModelPanel(model, onDownload = onDownloadModel, onCancel = onCancelDownload, onRemove = {})
                }
                StarsCard(viewModel, capabilities, enabled = !making)
                SeedField(viewModel, enabled = !making)
                SectionsBlock(viewModel, capabilities, enabled = !making)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun StarsCard(viewModel: MakeChartViewModel, capabilities: ChartCapabilities?, enabled: Boolean) {
    val range = capabilities?.selectableStars ?: DEFAULT_STARS
    val stars = viewModel.stars.coerceIn(range)
    val starsText = String.format(Locale.ROOT, "%.1f", stars)
    val starsLabel = stringResource(R.string.make_stars)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    starsLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text(starsText, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
            }
            Slider(
                value = stars.toFloat(),
                onValueChange = { viewModel.stars = (it * 10).roundToInt() / 10.0 },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                steps = capabilities?.starSliderSteps ?: 0,
                colors = sliderColorsWithoutTicks(),
                enabled = enabled && capabilities != null,
                modifier = Modifier.semantics {
                    contentDescription = starsLabel
                    stateDescription = starsText
                },
            )
            Text(
                if (capabilities != null) {
                    stringResource(
                        R.string.make_stars_range,
                        capabilities.modelName,
                        String.format(Locale.ROOT, "%.1f", range.start),
                        String.format(Locale.ROOT, "%.1f", range.endInclusive),
                    )
                } else {
                    stringResource(R.string.make_stars_no_model)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SeedField(viewModel: MakeChartViewModel, enabled: Boolean) {
    val valid = ChartRequestRules.isSeedValid(viewModel.seedText)
    OutlinedTextField(
        value = viewModel.seedText,
        onValueChange = { viewModel.seedText = it.filter(Char::isDigit) },
        label = { Text(stringResource(R.string.make_seed)) },
        placeholder = { Text(stringResource(R.string.make_seed_random)) },
        supportingText = {
            Text(stringResource(if (valid) R.string.make_seed_help else R.string.make_seed_invalid))
        },
        isError = !valid,
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MakeBar(
    state: MakeState,
    canMake: Boolean,
    modelMissing: Boolean,
    onMake: () -> Unit,
    onStop: () -> Unit,
    onPlay: (MadeChart) -> Unit,
    onUseSeed: (ULong) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                MakeState.Idle -> {
                    if (modelMissing) {
                        Text(stringResource(R.string.make_needs_model), style = MaterialTheme.typography.bodySmall)
                    }
                    ActionButton(stringResource(R.string.make_chart), onMake, icon = R.drawable.ic_auto_awesome, enabled = canMake, modifier = Modifier.fillMaxWidth())
                }
                is MakeState.Making -> {
                    Phases(state.phase)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.make_stop), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                is MakeState.Made -> {
                    Made(state.chart, onUseSeed)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onMake, modifier = Modifier.weight(1f).height(56.dp)) {
                            Text(stringResource(R.string.make_another))
                        }
                        ActionButton(stringResource(R.string.make_play), { onPlay(state.chart) }, icon = R.drawable.ic_play, modifier = Modifier.weight(1.4f))
                    }
                }
                is MakeState.Stopped -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.make_stopped), style = MaterialTheme.typography.bodyMedium)
                    }
                    ActionButton(stringResource(R.string.make_chart), onMake, icon = R.drawable.ic_auto_awesome, enabled = canMake, modifier = Modifier.fillMaxWidth())
                }
                is MakeState.Failed -> {
                    Problem(stringResource(R.string.make_failed))
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ActionButton(stringResource(R.string.make_try_again), onMake, icon = R.drawable.ic_refresh, enabled = canMake, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** The five phases, each done, under way or to come, in words and a symbol. */
@Composable
private fun Phases(current: MakePhase) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        MakePhase.entries.forEach { phase ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    when {
                        phase < current -> Icon(
                            painterResource(R.drawable.ic_check_circle),
                            contentDescription = stringResource(R.string.make_phase_done),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        phase == current -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else -> Icon(
                            painterResource(R.drawable.ic_circle_outline),
                            contentDescription = stringResource(R.string.make_phase_to_come),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Text(
                    stringResource(phase.title),
                    style = if (phase == current) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (phase > current) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun Made(chart: MadeChart, onUseSeed: (ULong) -> Unit) {
    val format = NumberFormat.getIntegerInstance()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.make_ready),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Text(
            stringResource(
                R.string.make_ready_notes,
                format.format(chart.noteCount),
                format.format(chart.noteCount - chart.holdCount),
                format.format(chart.holdCount),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.make_ready_seed, chart.seed.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onUseSeed(chart.seed) }) { Text(stringResource(R.string.make_use_seed)) }
        }
    }
}

val MakePhase.title: Int
    get() = when (this) {
        MakePhase.LOADING -> R.string.phase_loading
        MakePhase.TIMING -> R.string.phase_timing
        MakePhase.ROWS -> R.string.phase_rows
        MakePhase.ARRANGEMENT -> R.string.phase_arrangement
        MakePhase.EXPORT -> R.string.phase_export
    }

/** The range shown before the model has answered. */
private val DEFAULT_STARS = 2.0..5.9
