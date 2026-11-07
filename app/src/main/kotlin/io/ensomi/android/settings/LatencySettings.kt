package io.ensomi.android.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ensomi.android.R

/** The two kinds of latency slot (latency.md). */
enum class LatencyKind { AUDIO, INPUT }

/** A slot whose name is being given: its kind and its number. */
data class SlotNaming(val kind: LatencyKind, val slot: Int)

/**
 * Audio latency in Settings › Sound and timing (latency.md, Settings): the three slots in a segmented row, each with its
 * name under its number when it has one, the active slot chosen by hand; its value with −/+ steps of 1 ms, and Name. A
 * line names the output Android would play on now, [route], as a hint: it chooses nothing. At large text sizes a
 * segment keeps its number and drops a name it cannot fit; the slot's full name is in the stepper's words and its dialog.
 */
@Composable
internal fun AudioLatencySetting(
    latency: AudioLatency,
    route: OutputRoute,
    onChange: ((AudioLatency) -> AudioLatency) -> Unit,
    onName: (Int) -> Unit,
) {
    val labels = latency.slots.mapIndexed { i, slot -> latencySlotLabel(i, slot) }
    val descriptions = labels.map { stringResource(R.string.settings_audio_slot, it) }
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(R.string.settings_audio_latency),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        BoxWithConstraints(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val nameStyle = MaterialTheme.typography.labelSmall
            val density = LocalDensity.current
            // A segment's width less its own padding, and whether the text is set large.
            val room = with(density) { (maxWidth / Latency.SLOTS - SEGMENT_PADDING).toPx() }
            val large = density.fontScale > LARGE_TEXT
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                latency.slots.forEachIndexed { index, slot ->
                    val showName = slot.name.isNotEmpty() &&
                        (!large || measurer.measure(slot.name, nameStyle).size.width <= room)
                    SegmentedButton(
                        selected = latency.active == index,
                        onClick = { onChange { it.withActive(index) } },
                        shape = SegmentedButtonDefaults.itemShape(index, Latency.SLOTS),
                        icon = {},
                        modifier = Modifier.semantics { contentDescription = descriptions[index] },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clearAndSetSemantics { }) {
                            Text(
                                index.toString(),
                                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                            )
                            if (showName) {
                                Text(slot.name, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        val active = latency.active
        LatencyStepper(
            value = latency.activeMs,
            what = stringResource(R.string.settings_audio_latency_value, labels[active]),
            nameLabel = stringResource(R.string.latency_name_audio, active),
            onStep = { step -> onChange { it.withActiveMs(it.activeMs + step) } },
            onName = { onName(active) },
        )
        Supporting(stringResource(R.string.settings_audio_latency_help))
        Supporting(stringResource(R.string.settings_sound_now, route.name), Modifier.padding(top = 4.dp))
    }
}

/**
 * Input latency in Settings › Sound and timing (latency.md, Settings): one row per method, Touch and Keyboard, each with
 * the slot it uses, chosen from a menu of the three, that slot's value with −/+ steps of 1 ms, and Name. Methods on one
 * slot show its value in both rows and change it together.
 */
@Composable
internal fun InputLatencySetting(
    latency: InputLatency,
    onChange: ((InputLatency) -> InputLatency) -> Unit,
    onName: (Int) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(R.string.settings_input_latency),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        MethodSlot(
            method = stringResource(R.string.settings_input_touch),
            latency = latency,
            slot = latency.touch,
            onSlot = { slot -> onChange { it.withTouch(slot) } },
            onStep = { step -> onChange { it.withMs(it.touch, it.touchMs + step) } },
            onName = { onName(latency.touch) },
        )
        MethodSlot(
            method = stringResource(R.string.settings_input_keyboard),
            latency = latency,
            slot = latency.keyboard,
            onSlot = { slot -> onChange { it.withKeyboard(slot) } },
            onStep = { step -> onChange { it.withMs(it.keyboard, it.keyboardMs + step) } },
            onName = { onName(latency.keyboard) },
        )
        Supporting(stringResource(R.string.settings_input_latency_help))
    }
}

/** "Touch uses slot 0", a menu of the three slots with their values, then the slot's value with its steps and Name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MethodSlot(
    method: String,
    latency: InputLatency,
    slot: Int,
    onSlot: (Int) -> Unit,
    onStep: (Int) -> Unit,
    onName: () -> Unit,
) {
    val labels = latency.slots.mapIndexed { i, each -> latencySlotLabel(i, each) }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        OutlinedTextField(
            value = labels[slot],
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.settings_input_uses, method)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            labels.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = {
                        Text(
                            PlaySettings.formatOffset(latency.slots[i].ms),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        )
                    },
                    onClick = {
                        onSlot(i)
                        expanded = false
                    },
                )
            }
        }
    }
    LatencyStepper(
        value = latency.slots[slot].ms,
        what = stringResource(R.string.settings_input_latency_value, method, labels[slot]),
        nameLabel = stringResource(R.string.latency_name_input, slot),
        onStep = onStep,
        onName = onName,
    )
}

/**
 * A slot's value in whole milliseconds, "+120 ms", nudged a millisecond at a time within the range ([onStep] hears −1 or
 * +1), and its Name button at the row's end. [what] names the value for TalkBack, which hears each new value as it
 * changes.
 */
@Composable
private fun LatencyStepper(value: Int, what: String, nameLabel: String, onStep: (Int) -> Unit, onName: () -> Unit) {
    val decrease = stringResource(R.string.settings_offset_decrease)
    val increase = stringResource(R.string.settings_offset_increase)
    // Icon buttons' symbols sit 12 dp in from their edges: these line up with the text above.
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(-1) }, enabled = value > Latency.RANGE.first) {
            Icon(painterResource(R.drawable.ic_remove), contentDescription = "$decrease $what")
        }
        Text(
            PlaySettings.formatOffset(value),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 88.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        IconButton(onClick = { onStep(1) }, enabled = value < Latency.RANGE.last) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = "$increase $what")
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onName) {
            Icon(painterResource(R.drawable.ic_edit), contentDescription = nameLabel)
        }
    }
}

@Composable
private fun Supporting(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

/**
 * Naming a slot (latency.md, Naming a slot): "Name audio slot 1", a one-line field of at most [Latency.NAME_LENGTH]
 * characters with the placeholder "No name", and Clear name, Cancel and Save. Saving it empty leaves the slot without
 * a name; no screen asks for one. The dialog is a window of its own, so it keeps the input method whatever the screen
 * under it does with its own window.
 */
@Composable
internal fun NameSlotDialog(title: String, name: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(name, TextRange(name.length))) }
    val save = { onSave(field.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            val count = field.text.codePointCount(0, field.text.length)
            OutlinedTextField(
                value = field,
                onValueChange = { if (Latency.fitsName(it.text)) field = it },
                singleLine = true,
                label = { Text(stringResource(R.string.latency_name_field)) },
                placeholder = { Text(stringResource(R.string.latency_name_placeholder)) },
                supportingText = {
                    Text(
                        stringResource(R.string.latency_name_count, count, Latency.NAME_LENGTH),
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(focus) {
                focus.requestFocus()
                keyboard?.show()
            }
        },
        confirmButton = { TextButton(onClick = save) { Text(stringResource(R.string.latency_name_save)) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSave("") }, enabled = name.isNotEmpty()) {
                    Text(stringResource(R.string.latency_name_clear))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.latency_name_cancel)) }
            }
        },
    )
}

/** A segmented button's padding on each side, as Material lays it out. */
private val SEGMENT_PADDING = 24.dp

/** Font scales above this are large text, where a name that does not fit its segment is dropped. */
private const val LARGE_TEXT = 1.3f
