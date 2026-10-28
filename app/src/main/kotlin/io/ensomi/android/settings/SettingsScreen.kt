package io.ensomi.android.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ensomi.android.R
import io.ensomi.android.model.ModelPanel
import io.ensomi.android.model.ModelState
import io.ensomi.android.ui.LocalNotWired
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings, reached from the library and the song screens, never during a game, so nothing here changes while one is
 * in progress. Sections: gameplay, touch, sound and timing (with calibration), the chart model, and about (the
 * playfield preview and Diagnostics).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: PlaySettings,
    route: OutputRoute,
    model: ModelState,
    versionName: String,
    onChange: ((PlaySettings) -> PlaySettings) -> Unit,
    onBack: () -> Unit,
    onCalibrate: () -> Unit,
    onPreview: () -> Unit,
    onDiagnostics: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onRemoveModel: () -> Unit,
) {
    val notWired = LocalNotWired.current
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
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                item { Header(R.string.settings_gameplay) }
                item {
                    val speed = PlaySettings.formatSpeed(settings.scrollSpeed)
                    SliderSetting(
                        title = stringResource(R.string.settings_scroll_speed),
                        valueText = speed,
                        supporting = stringResource(
                            R.string.settings_scroll_speed_help,
                            String.format(Locale.ROOT, "%.2f", settings.scrollTimeMs / 1000),
                        ),
                        value = settings.scrollSpeed.toFloat(),
                        range = PlaySettings.SCROLL_SPEEDS.start.toFloat()..PlaySettings.SCROLL_SPEEDS.endInclusive.toFloat(),
                        steps = ((PlaySettings.SCROLL_SPEEDS.endInclusive - PlaySettings.SCROLL_SPEEDS.start) * 2).roundToInt() - 1,
                    ) { value -> onChange { it.copy(scrollSpeed = (value * 2).roundToInt() / 2.0) } }
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.settings_judge), style = MaterialTheme.typography.bodyLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            JudgeDifficulty.entries.forEachIndexed { index, judge ->
                                SegmentedButton(
                                    selected = settings.judge == judge,
                                    onClick = { onChange { it.copy(judge = judge) } },
                                    shape = SegmentedButtonDefaults.itemShape(index, JudgeDifficulty.entries.size),
                                    icon = {},
                                ) { Text(judge.name) }
                            }
                        }
                        Text(
                            stringResource(R.string.settings_judge_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item { Header(R.string.settings_touch) }
                item {
                    ListItem(
                        modifier = Modifier.clickable { onChange { it.copy(vibrateOnTouch = !it.vibrateOnTouch) } },
                        headlineContent = { Text(stringResource(R.string.settings_vibrate)) },
                        supportingContent = { Text(stringResource(R.string.settings_vibrate_help)) },
                        trailingContent = {
                            Switch(checked = settings.vibrateOnTouch, onCheckedChange = { on -> onChange { it.copy(vibrateOnTouch = on) } })
                        },
                    )
                }

                item { Header(R.string.settings_sound_timing) }
                item {
                    val percent = "${(settings.musicVolume * 100).roundToInt()}%"
                    SliderSetting(
                        title = stringResource(R.string.settings_music_volume),
                        valueText = percent,
                        supporting = stringResource(R.string.settings_music_volume_help),
                        value = settings.musicVolume,
                        range = 0f..1f,
                        steps = 19,
                    ) { value -> onChange { it.copy(musicVolume = (value * 20).roundToInt() / 20f) } }
                }
                item {
                    OffsetSetting(
                        title = stringResource(R.string.settings_audio_offset, route.name),
                        supporting = stringResource(R.string.settings_audio_offset_help),
                        value = settings.audioOffsetMs,
                    ) { value -> onChange { it.copy(audioOffsetMs = value) } }
                }
                item {
                    OffsetSetting(
                        title = stringResource(R.string.settings_visual_offset),
                        supporting = stringResource(R.string.settings_visual_offset_help),
                        value = settings.visualOffsetMs,
                    ) { value -> onChange { it.copy(visualOffsetMs = value) } }
                }
                item {
                    LinkRow(
                        title = stringResource(R.string.settings_calibrate),
                        supporting = stringResource(R.string.settings_calibrate_help),
                        icon = R.drawable.ic_tune,
                        onClick = onCalibrate,
                    )
                }

                item { Header(R.string.settings_chart_model) }
                item {
                    ModelPanel(
                        state = model,
                        onDownload = onDownloadModel,
                        onCancel = onCancelDownload,
                        onRemove = onRemoveModel,
                        inSettings = true,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (model is ModelState.Installed) {
                    item {
                        LinkRow(
                            title = stringResource(R.string.settings_model_licence, model.license),
                            supporting = stringResource(R.string.settings_model_card),
                            icon = R.drawable.ic_open_in_new,
                            onClick = { notWired("Opening the model card") },
                        )
                    }
                }
                item {
                    LinkRow(
                        title = stringResource(R.string.settings_notices),
                        supporting = stringResource(R.string.settings_notices_help),
                        icon = R.drawable.ic_description,
                        onClick = { notWired("The notice texts (plan step 4)") },
                    )
                }
                item {
                    Text(
                        stringResource(R.string.settings_model_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                item { Header(R.string.settings_about) }
                item {
                    LinkRow(
                        title = stringResource(R.string.settings_preview),
                        supporting = stringResource(R.string.settings_preview_help),
                        icon = R.drawable.ic_play,
                        onClick = onPreview,
                    )
                }
                item {
                    LinkRow(
                        title = stringResource(R.string.diagnostics_title),
                        supporting = stringResource(R.string.settings_diagnostics_help),
                        icon = R.drawable.ic_info,
                        onClick = onDiagnostics,
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_version)) },
                        supportingContent = { Text(versionName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(title: Int) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                .semantics { heading() },
        )
    }
}

@Composable
private fun SliderSetting(
    title: String,
    valueText: String,
    supporting: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = valueText
            },
        )
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An offset in whole milliseconds, nudged a millisecond at a time; the stepper sits under the text, so the title
 * keeps the width at large font sizes. */
@Composable
private fun OffsetSetting(title: String, supporting: String, value: Int, onChange: (Int) -> Unit) {
    val decrease = stringResource(R.string.settings_offset_decrease)
    val increase = stringResource(R.string.settings_offset_increase)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((value - 1).coerceIn(PlaySettings.OFFSETS)) }) {
                Icon(painterResource(R.drawable.ic_remove), contentDescription = "$decrease $title")
            }
            Text(
                PlaySettings.formatOffset(value),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.widthIn(min = 72.dp),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { onChange((value + 1).coerceIn(PlaySettings.OFFSETS)) }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "$increase $title")
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, supporting: String, icon: Int, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
    )
}
