package io.ensomi.android.chart

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.TopAppBarDefaults
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
    onInstallModelFromFolder: (android.net.Uri) -> Unit,
    /** The maker's own [MakeChartViewModel.make] and [MakeChartViewModel.stop] once it is wired. */
    onMake: () -> Unit = viewModel::make,
    onStop: () -> Unit = viewModel::stop,
) {
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val makerState by viewModel.state.collectAsStateWithLifecycle()
    val state = makerState.takeIf { it.songId == viewModel.song.id } ?: MakeState.Idle
    val making = state is MakeState.Making
    val song = viewModel.song
    // CONFIRM when a chart being made is ready, REJECT when it fails; a chart read back from storage says nothing.
    val view = LocalView.current
    var wasMaking by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        when {
            state is MakeState.Making -> wasMaking = true
            wasMaking && state is MakeState.Made -> view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            wasMaking && state is MakeState.Failed -> view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        }
        if (state !is MakeState.Making) wasMaking = false
    }
    // Why Make chart is disabled, said next to it.
    val blocker = when {
        model !is ModelState.Installed -> R.string.make_needs_model
        !ChartRequestRules.isSeedValid(viewModel.seedText) -> R.string.make_blocked_seed
        capabilities?.let(viewModel::issues)?.isNotEmpty() == true -> R.string.make_blocked_sections
        else -> null
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(song.title, maxLines = 1) },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            MakeBar(
                state = state,
                blocker = blocker,
                canMake = blocker == null && capabilities != null,
                onMake = onMake,
                onStop = onStop,
                onPlay = onPlay,
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
                    ModelPanel(
                        model,
                        onDownload = onDownloadModel,
                        onCancel = onCancelDownload,
                        onRemove = {},
                        onInstallFromFolder = onInstallModelFromFolder,
                    )
                }
                StatusCard(state, onUseSeed = viewModel::useSeed)
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
    // The value sent, even outside the slider's range (a debug launch's --ef stars), so the screen never shows
    // another number than the request carries.
    val starsText = String.format(Locale.ROOT, "%.1f", viewModel.stars)
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

/**
 * What happened last, at the top of the screen: the phases while a chart is made, the chart with its seed, a stop or a
 * failure. Nothing before the first Make chart.
 */
@Composable
private fun StatusCard(state: MakeState, onUseSeed: (ULong) -> Unit) {
    if (state == MakeState.Idle) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                MakeState.Idle -> Unit
                is MakeState.Making -> Phases(state.phase)
                is MakeState.Made -> Made(state.chart, onUseSeed)
                is MakeState.Stopped -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.make_stopped), style = MaterialTheme.typography.bodyMedium)
                }
                is MakeState.Failed -> {
                    Problem(stringResource(R.string.make_failed))
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The screen's actions, always in the same place: Make chart, Stop, or Play. */
@Composable
private fun MakeBar(
    state: MakeState,
    @StringRes blocker: Int?,
    canMake: Boolean,
    onMake: () -> Unit,
    onStop: () -> Unit,
    onPlay: (MadeChart) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    is MakeState.Making -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.make_stop), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    is MakeState.Made -> {
                        val play: @Composable (Modifier) -> Unit = { modifier ->
                            ActionButton(stringResource(R.string.make_play), { onPlay(state.chart) }, icon = R.drawable.ic_play, modifier = modifier)
                        }
                        val another: @Composable (Modifier) -> Unit = { modifier ->
                            OutlinedButton(onClick = onMake, modifier = modifier.heightIn(min = 56.dp)) {
                                Text(stringResource(R.string.make_another), textAlign = TextAlign.Center)
                            }
                        }
                        // Side by side at normal text sizes; stacked at large ones, so neither label is cut.
                        if (LocalDensity.current.fontScale <= 1.3f) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                another(Modifier.weight(1f))
                                play(Modifier.weight(1.4f))
                            }
                        } else {
                            play(Modifier.fillMaxWidth())
                            another(Modifier.fillMaxWidth())
                        }
                    }
                    else -> {
                        if (blocker != null) {
                            Text(stringResource(blocker), style = MaterialTheme.typography.bodySmall)
                        }
                        val label = if (state is MakeState.Failed) R.string.make_try_again else R.string.make_chart
                        val icon = if (state is MakeState.Failed) R.drawable.ic_refresh else R.drawable.ic_auto_awesome
                        ActionButton(stringResource(label), onMake, icon = icon, enabled = canMake, modifier = Modifier.fillMaxWidth())
                    }
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
