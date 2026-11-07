package io.ensomi.android.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.ensomi.android.R
import io.ensomi.android.keyboard.Keyboard
import io.ensomi.android.keyboard.KeyboardText
import io.ensomi.android.keyboard.keyboardWords
import io.ensomi.android.play.PlayfieldGeometry
import io.ensomi.android.model.ModelPanel
import io.ensomi.android.model.ModelSource
import io.ensomi.android.model.ModelState
import io.ensomi.android.model.ready
import io.ensomi.android.ui.SectionHeader
import io.ensomi.android.ui.WarningLine
import io.ensomi.android.ui.sliderColorsWithoutTicks
import java.util.Locale
import kotlin.math.roundToInt

/** Where the Settings list opens: at its top, or at one of its sections (the prototype's scenarios). */
enum class SettingsSection { TOP, PLAYFIELD, KEYBOARD, SOUND }

/**
 * Settings, reached from the library, never during a game, so nothing here changes while one is
 * in progress. Sections: gameplay, the playfield, the keyboard (a row that opens Settings › Keyboard), sound and timing
 * (the latency slots, with calibration), the chart model, and about (the playfield preview and Diagnostics).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: PlaySettings,
    model: ModelState,
    versionName: String,
    /** The output Android would play on now, which the audio latency names as a hint. */
    route: OutputRoute,
    /** The keyboards connected, which the Keyboard row names. */
    keyboards: List<Keyboard>,
    onChange: ((PlaySettings) -> PlaySettings) -> Unit,
    onBack: () -> Unit,
    onKeyboard: () -> Unit,
    onCalibrate: () -> Unit,
    onPreview: () -> Unit,
    onDiagnostics: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onRemoveModel: () -> Unit,
    onInstallModelFromFolder: (android.net.Uri) -> Unit,
    onRetryModelOpen: () -> Unit = {},
    onShareModelDiagnostics: (() -> Unit)? = null,
    onNotices: () -> Unit,
    /** The section the list opens at (prototype scenarios). */
    firstSection: SettingsSection = SettingsSection.TOP,
    /** A slot whose name dialog is open as the screen opens (prototype scenarios). */
    initialNaming: SlotNaming? = null,
) {
    val uriHandler = LocalUriHandler.current
    var naming by remember { mutableStateOf(initialNaming) }
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
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val wideWindow = maxWidth > maxHeight
            // The playfield section is one item beside its preview in a wide window, four under each other otherwise.
            val playfieldItems = if (wideWindow) 1 else 4
            val firstIndex = when (firstSection) {
                SettingsSection.TOP -> 0
                SettingsSection.PLAYFIELD -> PLAYFIELD_HEADER
                SettingsSection.KEYBOARD -> PLAYFIELD_HEADER + 1 + playfieldItems
                SettingsSection.SOUND -> PLAYFIELD_HEADER + 3 + playfieldItems
            }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstIndex)
            // Landscape puts the navigation bar or a cutout at a side: the list keeps clear of it.
            val direction = LocalLayoutDirection.current
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction))
                    .widthIn(max = 640.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                item { Header(R.string.settings_gameplay, first = true) }
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

                // Keep PLAYFIELD_HEADER the index of this header, and the sections after it as firstIndex counts them.
                item { Header(R.string.settings_playfield) }
                // The preview stays in sight while a slider moves: under them in a tall window, beside them in a wide one.
                if (wideWindow) {
                    item {
                        Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                OrientationSetting(settings, onChange)
                                LeanSetting(settings, onChange)
                                WidthSetting(settings, onChange)
                            }
                            PlayfieldPreview(settings, Modifier.weight(1f))
                        }
                    }
                } else {
                    item { OrientationSetting(settings, onChange) }
                    item { LeanSetting(settings, onChange) }
                    item { WidthSetting(settings, onChange) }
                    item { PlayfieldPreview(settings, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) }
                }

                item { Header(R.string.settings_keyboard) }
                item { KeyboardRow(settings.keys, keyboards, onKeyboard) }

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
                    AudioLatencySetting(
                        latency = settings.audioLatency,
                        route = route,
                        onChange = { change -> onChange { it.copy(audioLatency = change(it.audioLatency)) } },
                        onName = { naming = SlotNaming(LatencyKind.AUDIO, it) },
                    )
                }
                item {
                    InputLatencySetting(
                        latency = settings.inputLatency,
                        onChange = { change -> onChange { it.copy(inputLatency = change(it.inputLatency)) } },
                        onName = { naming = SlotNaming(LatencyKind.INPUT, it) },
                    )
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
                        onInstallFromFolder = onInstallModelFromFolder,
                        onRetryOpen = onRetryModelOpen,
                        onShareDiagnostics = onShareModelDiagnostics,
                        inSettings = true,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (model is ModelState.Installed) {
                    item {
                        LinkRow(
                            title = stringResource(R.string.settings_model_licence, model.license),
                            supporting = stringResource(R.string.settings_model_card),
                            icon = R.drawable.ic_info,
                            external = true,
                            onClick = { uriHandler.openUri(ModelSource.MODEL_CARD) },
                        )
                    }
                }
                item {
                    LinkRow(
                        title = stringResource(R.string.settings_notices),
                        supporting = stringResource(R.string.settings_notices_help),
                        icon = R.drawable.ic_description,
                        onClick = onNotices,
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
    naming?.let { open ->
        val audio = open.kind == LatencyKind.AUDIO
        val slots = if (audio) settings.audioLatency.slots else settings.inputLatency.slots
        key(open) {
            NameSlotDialog(
                title = stringResource(if (audio) R.string.latency_name_audio else R.string.latency_name_input, open.slot),
                name = slots[open.slot].name,
                onSave = { name ->
                    onChange {
                        if (audio) {
                            it.copy(audioLatency = it.audioLatency.withName(open.slot, name))
                        } else {
                            it.copy(inputLatency = it.inputLatency.withName(open.slot, name))
                        }
                    }
                    naming = null
                },
                onDismiss = { naming = null },
            )
        }
    }
}

/** The index of the playfield section's header in the settings list. */
private const val PLAYFIELD_HEADER = 3

/**
 * The Keyboard row (keyboard.md, Settings › Keyboard): the lanes' keys, Pause's and who is connected, "Lanes: Z X . / ·
 * Pause: Esc · Connected: K380", and, while a lane has no key, which, with a warning symbol in the error colour. It opens
 * Settings › Keyboard, the one place the keys are set.
 */
@Composable
private fun KeyboardRow(map: KeyMap, keyboards: List<Keyboard>, onClick: () -> Unit) {
    val words = keyboardWords(emptyMap())
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(stringResource(R.string.settings_keyboard)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(KeyboardText.row(map, keyboards, words))
                KeyboardText.emptyLanes(map, words)?.let { WarningLine(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        leadingContent = { Icon(painterResource(R.drawable.ic_keyboard), contentDescription = null) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
    )
}

private val PlayOrientation.label: Int
    get() = when (this) {
        PlayOrientation.LANDSCAPE -> R.string.settings_orientation_landscape
        PlayOrientation.PORTRAIT -> R.string.settings_orientation_portrait
    }

@Composable
private fun OrientationSetting(settings: PlaySettings, onChange: ((PlaySettings) -> PlaySettings) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_orientation), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PlayOrientation.entries.forEachIndexed { index, orientation ->
                SegmentedButton(
                    selected = settings.orientation == orientation,
                    onClick = { onChange { it.copy(orientation = orientation) } },
                    shape = SegmentedButtonDefaults.itemShape(index, PlayOrientation.entries.size),
                    icon = {},
                ) { Text(stringResource(orientation.label)) }
            }
        }
        Text(
            stringResource(R.string.settings_orientation_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The lean of the orientation chosen; each orientation keeps its own. */
@Composable
private fun LeanSetting(settings: PlaySettings, onChange: ((PlaySettings) -> PlaySettings) -> Unit) {
    val orientation = settings.orientation
    SliderSetting(
        title = stringResource(
            if (orientation == PlayOrientation.LANDSCAPE) R.string.settings_lean_landscape else R.string.settings_lean_portrait,
        ),
        valueText = leanText(settings.lean),
        supporting = stringResource(R.string.settings_lean_help),
        value = settings.lean.toFloat(),
        range = PlaySettings.LEANS.first.toFloat()..PlaySettings.LEANS.last.toFloat(),
        steps = PlaySettings.LEANS.last - PlaySettings.LEANS.first - 1,
    ) { value -> onChange { it.withLean(orientation, PlaySettings.snapLean(value)) } }
}

/** A share of the window at the line, with the original geometry as the lower end. */
@Composable
private fun WidthSetting(settings: PlaySettings, onChange: ((PlaySettings) -> PlaySettings) -> Unit) {
    val orientation = settings.orientation
    val size = playfieldPreviewSize(orientation)
    val density = LocalView.current.resources.displayMetrics.density
    val width = size.first.toFloat()
    val minimum = PlayfieldGeometry.lineWidth(width, density, width > size.second) / width
    val share = PlayfieldGeometry.lineWidth(width, size.second.toFloat(), density, settings.laneWidth(orientation)) / width
    Column {
        SliderSetting(
            title = stringResource(R.string.settings_lane_width),
            valueText = stringResource(R.string.settings_width_value, (share * 100).roundToInt()),
            supporting = stringResource(R.string.settings_width_help),
            value = share,
            range = minimum..PlayfieldGeometry.MAX_WIDTH_SHARE,
            steps = 0,
        ) { value -> onChange { it.withLaneWidth(orientation, value) } }
        TextButton(
            onClick = { onChange { it.withLaneWidth(orientation, null) } },
            enabled = settings.laneWidth(orientation) != null,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(stringResource(R.string.settings_width_default)) }
    }
}

@Composable
private fun Header(@StringRes title: Int, first: Boolean = false) = SectionHeader(stringResource(title), first)

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
            colors = sliderColorsWithoutTicks(),
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = valueText
            },
        )
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A latency slot as the screens name it: its number, with its name after it when it has one ("1 · WH-1000XM4"). Names
 * are free text, for the screen only.
 */
@Composable
fun latencySlotLabel(index: Int, slot: LatencySlot): String = if (slot.name.isEmpty()) {
    stringResource(R.string.latency_slot, index)
} else {
    stringResource(R.string.latency_slot_named, index, slot.name)
}

/** A row that opens a screen (a chevron) or, when [external], a page outside the app. */
@Composable
private fun LinkRow(title: String, supporting: String, @DrawableRes icon: Int, onClick: () -> Unit, external: Boolean = false) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = {
            Icon(painterResource(if (external) R.drawable.ic_open_in_new else R.drawable.ic_chevron_right), contentDescription = null)
        },
    )
}
