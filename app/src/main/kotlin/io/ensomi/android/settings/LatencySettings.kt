package io.ensomi.android.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.ensomi.android.R
import io.ensomi.android.ui.sliderColorsWithoutTicks
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    val active = latency.active
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            stringResource(R.string.settings_audio_latency),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        AudioSlotChoice(
            latency = latency,
            onChoose = { slot -> onChange { it.withActive(slot) } },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        )
        LatencyStepper(
            value = latency.activeMs,
            what = stringResource(R.string.settings_audio_latency_value, latencySlotLabel(active, latency.slots[active])),
            nameLabel = stringResource(R.string.latency_name_audio, active),
            onStep = { step -> onChange { it.withActiveMs(it.activeMs + step) } },
            onName = { onName(active) },
            modifier = SETTINGS_STEPPER,
        )
        Supporting(stringResource(R.string.settings_audio_latency_help))
        Supporting(stringResource(R.string.settings_sound_now, route.name), Modifier.padding(top = 4.dp))
    }
}

/**
 * The three audio latency slots in a segmented row, "0", "1" and "2", each with its name under its number in small type,
 * the active slot selected; [onChoose] hears the slot chosen, which moves no value. While any segment shows a name the
 * others keep a line for one, so the three stay one height. At large text sizes a segment keeps its number and drops a
 * name it cannot fit, and the active slot's name then shows whole under the row; below that a long name ends in an
 * ellipsis, so it still shows its start.
 */
@Composable
internal fun AudioSlotChoice(latency: AudioLatency, onChoose: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = latency.slots.mapIndexed { i, slot -> latencySlotLabel(i, slot) }
    val descriptions = labels.map { stringResource(R.string.settings_audio_slot, it) }
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val nameStyle = MaterialTheme.typography.labelSmall
        val density = LocalDensity.current
        // A segment's width less its own padding, and whether the text is set large.
        val room = with(density) { (maxWidth / Latency.SLOTS - SEGMENT_PADDING).toPx() }
        val large = density.fontScale > LARGE_TEXT
        val shown = latency.slots.map { slot ->
            slot.name.isNotEmpty() && (!large || measurer.measure(slot.name, nameStyle).size.width <= room)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                latency.slots.forEachIndexed { index, slot ->
                    SegmentedButton(
                        selected = latency.active == index,
                        onClick = { onChoose(index) },
                        shape = SegmentedButtonDefaults.itemShape(index, Latency.SLOTS),
                        icon = {},
                        modifier = Modifier.semantics { contentDescription = descriptions[index] },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clearAndSetSemantics { },
                        ) {
                            Text(
                                index.toString(),
                                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                            )
                            // While any segment shows a name, each keeps a line for one, so the three stay one height.
                            if (shown.any { it }) {
                                Text(
                                    if (shown[index]) slot.name else " ",
                                    style = nameStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            // The active slot's name, when its segment had no room for it: still in sight, whole.
            val activeSlot = latency.slots[latency.active]
            if (activeSlot.name.isNotEmpty() && !shown[latency.active]) {
                Text(
                    labels[latency.active],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
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
    Column(Modifier.padding(top = 16.dp)) {
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
@Composable
private fun MethodSlot(
    method: String,
    latency: InputLatency,
    slot: Int,
    onSlot: (Int) -> Unit,
    onStep: (Int) -> Unit,
    onName: () -> Unit,
) {
    InputSlotMenu(
        label = stringResource(R.string.settings_input_uses, method),
        latency = latency,
        slot = slot,
        onSlot = onSlot,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
    LatencyStepper(
        value = latency.slots[slot].ms,
        what = stringResource(R.string.settings_input_latency_value, method, latencySlotLabel(slot, latency.slots[slot])),
        nameLabel = stringResource(R.string.latency_name_input, slot),
        onStep = onStep,
        onName = onName,
        modifier = SETTINGS_STEPPER,
    )
}

/** Icon buttons' symbols sit 12 dp in from their edges: in Settings' list these line up with the text above. */
private val SETTINGS_STEPPER = Modifier.fillMaxWidth().padding(horizontal = 4.dp)

/**
 * A menu field labelled [label], "Touch uses slot", whose value is [slot], "0" or "1 · K380": the app's exposed dropdown
 * menu, whose items show each input slot with its value. [onSlot] hears the slot chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InputSlotMenu(
    label: String,
    latency: InputLatency,
    slot: Int,
    onSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = latency.slots.mapIndexed { i, each -> latencySlotLabel(i, each) }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = labels[slot],
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            labels.forEachIndexed { i, each ->
                DropdownMenuItem(
                    text = { Text(each) },
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
}

/**
 * A slot's value in whole milliseconds, "+120 ms", nudged a millisecond at a time within the range ([onStep] hears how
 * many milliseconds, −1 or +1 for a tap), and its Name button at the row's end. A step held down repeats, faster the
 * longer it is held ([StepRepeat]), so a Bluetooth output's 150 ms or more is about two seconds away; a tap then puts it
 * on the millisecond. [what] names the value for TalkBack, which hears each new value as it changes.
 */
@Composable
internal fun LatencyStepper(
    value: Int,
    what: String,
    nameLabel: String,
    onStep: (Int) -> Unit,
    onName: () -> Unit,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val decrease = stringResource(R.string.settings_offset_decrease)
    val increase = stringResource(R.string.settings_offset_increase)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StepButton(R.drawable.ic_remove, "$decrease $what", enabled = value > Latency.RANGE.first) { steps -> onStep(-steps) }
        Text(
            PlaySettings.formatOffset(value),
            style = valueStyle.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 88.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        StepButton(R.drawable.ic_add, "$increase $what", enabled = value < Latency.RANGE.last) { steps -> onStep(steps) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onName) {
            Icon(painterResource(R.drawable.ic_edit), contentDescription = nameLabel)
        }
    }
}

/**
 * An icon button whose step comes on its click, and keeps coming while it is held ([StepRepeat]): by a finger, or by
 * Enter or Space while it has focus. [onStep] hears how many steps are due, 1 for a click. A press that repeated adds
 * nothing when it is let go. TalkBack's double tap is one click, one step.
 */
@Composable
private fun StepButton(@DrawableRes icon: Int, description: String, enabled: Boolean, onStep: (Int) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val step by rememberUpdatedState(onStep)
    val press = remember { HeldPress() }
    LaunchedEffect(source) {
        var repeating: Job? = null
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    press.repeated = false
                    repeating?.cancel()
                    repeating = launch {
                        // By the time held, not by the frames run: a slow frame makes the next step larger, not later.
                        val from = System.nanoTime()
                        var done = 0
                        delay(StepRepeat.HOLD_MS)
                        while (isActive) {
                            val due = StepRepeat.stepsAfter((System.nanoTime() - from) / 1_000_000) - done
                            if (due > 0) {
                                press.repeated = true
                                done += due
                                step(due)
                            }
                            delay(StepRepeat.TICK_MS)
                        }
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    repeating?.cancel()
                    repeating = null
                    // A cancelled press has no click to come.
                    if (interaction is PressInteraction.Cancel) press.repeated = false
                }
            }
        }
    }
    IconButton(
        onClick = {
            if (!press.repeated) step(1)
            press.repeated = false
        },
        enabled = enabled,
        interactionSource = source,
    ) {
        Icon(painterResource(icon), contentDescription = description)
    }
}

/** Whether the press of a [StepButton] being let go has repeated. Not state: nothing is drawn from it. */
private class HeldPress {
    var repeated = false
}

/**
 * How a held step repeats, by the time it has been held: nothing for [HOLD_MS], as a long press, then the first step,
 * and steps coming faster and faster, from [SLOWEST_PER_S] a second to [FASTEST_PER_S] over [SPEED_UP_MS], then that
 * fast. A step is 1 ms: from 0, +150 ms is 2.3 s away and +300 ms 3.8 s; letting go shows the value reached, and a tap
 * moves it by one. A slider takes a value most of the way faster still.
 */
object StepRepeat {
    const val HOLD_MS = 400L
    const val TICK_MS = 16L
    const val SLOWEST_PER_S = 10.0
    const val FASTEST_PER_S = 100.0
    const val SPEED_UP_MS = 800.0

    /** The steps due after the step has been held for [heldMs]: 0 before [HOLD_MS], then never fewer than before. */
    fun stepsAfter(heldMs: Long): Int {
        if (heldMs < HOLD_MS) return 0
        val x = (heldMs - HOLD_MS).toDouble()
        val ramp = minOf(x, SPEED_UP_MS)
        // The rate rises in a straight line over the ramp, so the steps are its integral, then the fastest rate's.
        val rising = (SLOWEST_PER_S * ramp + (FASTEST_PER_S - SLOWEST_PER_S) * ramp * ramp / (2 * SPEED_UP_MS)) / 1000
        val steady = FASTEST_PER_S * (x - ramp) / 1000
        return 1 + floor(rising + steady).toInt()
    }
}

/**
 * A latency value on a slider from [SLIDER_MS]'s −150 to +150 ms, in whole milliseconds, beside its steps, which reach
 * the whole range; a value beyond the slider's shows at its end. While it has focus the arrow keys move it 1 ms ([onStep]
 * hears ±1), Page Up and Page Down 10 ms, and a held key repeats as the keyboard repeats it. [what] names it for TalkBack,
 * which hears it with its value at one stop. As its text, not its content description: Compose moves a content
 * description of a node like the slider's, which merges its children, to a node of its own, and TalkBack stopped on
 * that one after the slider (L3's round 2).
 */
@Composable
internal fun LatencySlider(value: Int, what: String, onValue: (Int) -> Unit, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    val range = SLIDER_MS.first.toFloat()..SLIDER_MS.last.toFloat()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Slider(
        value = value.toFloat().coerceIn(range),
        onValueChange = { onValue(it.roundToInt()) },
        valueRange = range,
        colors = sliderColorsWithoutTicks(),
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val forward = if (rtl) -1 else 1
                val step = when (event.key) {
                    Key.DirectionUp -> 1
                    Key.DirectionDown -> -1
                    Key.DirectionRight -> forward
                    Key.DirectionLeft -> -forward
                    Key.PageUp -> PAGE_MS
                    Key.PageDown -> -PAGE_MS
                    else -> return@onPreviewKeyEvent false
                }
                onStep(step)
                true
            }
            .semantics {
                text = AnnotatedString(what)
                stateDescription = PlaySettings.formatOffset(value)
            },
    )
}

/** The slider's range; the steps reach [Latency.RANGE]. */
private val SLIDER_MS = -150..150

/** Page Up and Page Down on a focused slider. */
private const val PAGE_MS = 10

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

/**
 * The name dialog of the slot [open] names, over Settings or the calibration: Save keeps the name in that slot at once,
 * through [onChange], and both Save and Cancel close it ([onClose]).
 */
@Composable
internal fun NameSlot(
    open: SlotNaming,
    settings: PlaySettings,
    onChange: ((PlaySettings) -> PlaySettings) -> Unit,
    onClose: () -> Unit,
) {
    val audio = open.kind == LatencyKind.AUDIO
    val slots = if (audio) settings.audioLatency.slots else settings.inputLatency.slots
    androidx.compose.runtime.key(open) {
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
                onClose()
            },
            onDismiss = onClose,
        )
    }
}

/** A segmented button's padding on each side, as Material lays it out. */
private val SEGMENT_PADDING = 24.dp

/** Font scales above this are large text, where a name that does not fit its segment is dropped. */
private const val LARGE_TEXT = 1.3f
