package io.ensomi.android.keyboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ensomi.android.R
import io.ensomi.android.play.KeyInput
import io.ensomi.android.play.LaneHolder
import io.ensomi.android.play.PlayPalette
import io.ensomi.android.settings.BindableKey
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.ResolvedKeys
import io.ensomi.android.ui.SectionHeader
import io.ensomi.android.ui.WarningLine

/**
 * Settings › Keyboard (keyboard.md, Settings › Keyboard): the one place the key map is set. The keyboards connected,
 * with links to Android's Bluetooth and keyboard layout settings; the five slots, each waiting for its key when pressed
 * ([KeyCapture]); the empty lane's mark and notice; Use default keys; and the key test ([KeyTest]). Chrome, in one
 * column centred at 640 dp at most; on a window wider than tall, two columns at 840 dp at most, the slots on the left
 * and the keyboards and the test on the right, so that the four lanes keep one row and the slots open the screen.
 *
 * [map] is the key map the screen shows; [currentMap] reads the settings' map as it is now and [onMap] changes it, so
 * that keys pressed in quick succession bind in turn, before the screen has drawn the first. While a slot waits or the
 * test runs, the window is no input method target and every key from a keyboard is theirs; otherwise keys go on as on
 * any screen, Escape going Back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardScreen(
    map: KeyMap,
    currentMap: () -> KeyMap,
    onMap: (KeyMap) -> Unit,
    keyboards: Keyboards,
    onBack: () -> Unit,
    /** Where the screen opens, for the prototype's scenarios: a slot waiting, a note, a test under way. */
    initialCapture: KeyCapture = KeyCapture(),
    initialTest: KeyTest = KeyTest(),
) {
    val connected by keyboards.keyboards.collectAsStateWithLifecycle()
    // The first keyboard connected gives the labels.
    val labelling = connected.firstOrNull()
    val canUseKeys = connected.isNotEmpty()
    var capture by remember { mutableStateOf(initialCapture) }
    var test by remember { mutableStateOf(initialTest) }
    val words = keyboardWords()
    // Each device's layout, asked of the system when one of its keys first comes; again once the keyboards change.
    val devices = remember(connected) { DeviceKeys() }

    HardwareKeyHandler { event ->
        val key = KeyInput.of(event)
        val step = capture.key(key, currentMap(), devices::layout)
        capture = step.capture
        step.map?.let(onMap)
        if (step.taken) return@HardwareKeyHandler true
        val tested = test.key(key) { deviceId -> devices.keys(deviceId, currentMap()) }
        test = tested.test
        if (tested.action != KeyTest.Action.PASS_ON && key.action == KeyEvent.ACTION_DOWN) devices.note(key)
        tested.action != KeyTest.Action.PASS_ON
    }
    if (capture.waiting != null || test.testing) KeepInputMethodAway()
    BackHandler(enabled = capture.waiting != null) { capture = capture.cancel() }
    BackHandler(enabled = test.testing) { test = test.stop() }

    val startSlot = { slot: KeySlot ->
        test = test.stop()
        capture = capture.start(slot)
    }
    val toggleTest = {
        capture = capture.quiet()
        test = if (test.testing) test.stop() else test.start()
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            // A touch anywhere that no control takes ends the wait for a key, as Cancel does.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    if (waitForUpOrCancellation(PointerEventPass.Final) != null) capture = capture.cancel()
                }
            },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.keyboard_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val wideWindow = maxWidth > maxHeight
            val direction = LocalLayoutDirection.current
            Column(
                Modifier
                    .padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction))
                    .widthIn(max = if (wideWindow) 840.dp else 640.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                val keysSection: @Composable (Boolean) -> Unit = { topOfColumn ->
                    KeysSection(
                        first = topOfColumn,
                        map = map,
                        keyboard = labelling,
                        words = words,
                        capture = capture,
                        canWait = canUseKeys,
                        onSlot = startSlot,
                        onClear = { capture.clear(currentMap())?.let { (next, cleared) -> capture = next; onMap(cleared) } },
                        onCancel = { capture = capture.cancel() },
                        onDefaults = {
                            capture = capture.quiet()
                            onMap(KeyMap.DEFAULT)
                        },
                    )
                }
                val testSection: @Composable () -> Unit = {
                    TestSection(map, labelling, words, test, devices, canTest = canUseKeys, onToggle = toggleTest)
                }
                if (wideWindow) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) { keysSection(true) }
                        Column(Modifier.weight(1f)) {
                            Status(connected)
                            testSection()
                        }
                    }
                } else {
                    Status(connected)
                    keysSection(false)
                    testSection()
                }
            }
        }
    }
}

/** The keyboards connected by name, or how to connect one; and the links to Android's own settings that exist here. */
@Composable
private fun Status(connected: List<Keyboard>) {
    val context = LocalContext.current
    val bluetooth = remember(context) { systemSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS) }
    val layout = remember(context) { systemSettings(context, Settings.ACTION_HARD_KEYBOARD_SETTINGS) }
    val keyboardIcon: @Composable () -> Unit = { Icon(painterResource(R.drawable.ic_keyboard), contentDescription = null) }
    // The text under a row's headline: 16 dp of padding, the icon and its 16 dp gap.
    val textStart = Modifier.padding(start = 56.dp, end = 16.dp)
    if (connected.isEmpty()) {
        ListItem(
            leadingContent = keyboardIcon,
            headlineContent = { Text(stringResource(R.string.settings_keyboard_none_connected)) },
            supportingContent = { Text(stringResource(R.string.keyboard_pair)) },
        )
        if (bluetooth != null) {
            OutlinedButton(onClick = { openSettings(context, bluetooth) }, modifier = textStart.padding(bottom = 8.dp)) {
                Text(stringResource(R.string.keyboard_bluetooth_settings))
                Spacer(Modifier.width(8.dp))
                Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    } else {
        connected.forEach { keyboard ->
            ListItem(
                leadingContent = keyboardIcon,
                headlineContent = { Text(keyboard.name) },
                supportingContent = { Text(stringResource(R.string.keyboard_connected)) },
            )
        }
        Text(
            stringResource(R.string.keyboard_layout_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = textStart,
        )
        if (layout != null) {
            // A text button's label sits 12 dp in from its edge: the label lines up with the text above.
            TextButton(onClick = { openSettings(context, layout) }, modifier = Modifier.padding(start = 44.dp, bottom = 4.dp)) {
                Text(stringResource(R.string.keyboard_layout))
                Spacer(Modifier.width(8.dp))
                Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The five slots, what the wait for a key says, the empty lanes' notice, and the actions. */
@Composable
private fun KeysSection(
    /** The first section of its column, with no divider above it. */
    first: Boolean,
    map: KeyMap,
    keyboard: Keyboard?,
    words: KeyboardWords,
    capture: KeyCapture,
    canWait: Boolean,
    onSlot: (KeySlot) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onDefaults: () -> Unit,
) {
    SectionHeader(stringResource(R.string.keyboard_keys), first)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SlotGrid(map, keyboard, words, capture.waiting, canWait, onSlot)
        // With one lane empty and a keyboard to press it with, the notice says how, and the hint would say it twice.
        val lone = map.emptyLanes.singleOrNull()?.takeIf { canWait }
        KeyboardText.emptyLanes(map, words)?.let { sentence ->
            val how = lone?.let { " " + stringResource(R.string.keyboard_lane_empty_how, KeyboardText.number(it)) }.orEmpty()
            WarningLine(sentence + how)
        }
        val waiting = capture.waiting
        val note = capture.note
        when {
            waiting != null && note == CaptureNote.Refused -> WarningLine(stringResource(R.string.keyboard_refused))
            waiting != null -> Message(prompt(waiting), MaterialTheme.colorScheme.onSurface)
            note is CaptureNote.Swapped -> Message(KeyboardText.swapped(note, keyboard, words), MaterialTheme.colorScheme.onSurface)
            !canWait -> Message(stringResource(R.string.keyboard_hint_no_keyboard), MaterialTheme.colorScheme.onSurfaceVariant)
            lone == null -> Message(stringResource(R.string.keyboard_hint), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // Text buttons' labels sit 12 dp in from their edges: these line up with the slots.
    Row(Modifier.padding(horizontal = 4.dp)) {
        val waiting = capture.waiting
        if (waiting != null) {
            TextButton(onClick = onClear, enabled = map[waiting] != null) { Text(stringResource(R.string.keyboard_clear)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.keyboard_cancel)) }
        } else {
            TextButton(onClick = onDefaults, enabled = map != KeyMap.DEFAULT) { Text(stringResource(R.string.keyboard_defaults)) }
        }
    }
}

@Composable
private fun prompt(slot: KeySlot): String = when (val lane = slot.lane) {
    null -> stringResource(R.string.keyboard_prompt_pause)
    else -> stringResource(R.string.keyboard_prompt_lane, lane + 1)
}

/** One line of what the screen says, a polite live region. */
@Composable
private fun Message(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * Lane 1 to 4 left to right, and Pause beside them when five fit with room to spare, under them otherwise. At large
 * text sizes, when a lane's name or the word "Space" would not fit four across, the lanes go two by two; nothing is cut.
 */
@Composable
private fun SlotGrid(
    map: KeyMap,
    keyboard: Keyboard?,
    words: KeyboardWords,
    waiting: KeySlot?,
    canWait: Boolean,
    onSlot: (KeySlot) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val titleStyle = MaterialTheme.typography.labelLarge
        val wordStyle = MaterialTheme.typography.titleMedium
        val widestTitle = stringResource(R.string.keyboard_lane, 4)
        val needed = remember(measurer, density, titleStyle, wordStyle, widestTitle, words) {
            with(density) {
                val widest = maxOf(
                    measurer.measure(widestTitle, titleStyle).size.width + WARNING_ROOM.roundToPx(),
                    measurer.measure(words.space, wordStyle).size.width,
                    measurer.measure(words.none, wordStyle).size.width,
                )
                widest.toDp() + SLOT_PADDING * 2
            }
        }
        val fourAcross = (maxWidth - GAP * 3) / 4 >= needed
        val laneColumns = if (fourAcross) 4 else 2
        val laneWidth = (maxWidth - GAP * (laneColumns - 1)) / laneColumns
        val fiveWidth = (maxWidth - GAP * 3 - PAUSE_GAP) / 5
        val pauseBeside = fourAcross && fiveWidth >= maxOf(needed, ROOMY_SLOT)
        val slot: @Composable (KeySlot, Dp) -> Unit = { which, width ->
            SlotButton(
                slot = which,
                key = map[which],
                label = KeyboardText.cap(map[which], keyboard, words),
                spoken = map[which]?.let { key -> KeyboardText.spoken(key, keyboard, words) },
                waiting = waiting == which,
                enabled = canWait,
                onClick = { onSlot(which) },
                modifier = Modifier.width(width).fillMaxHeight(),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
            if (pauseBeside) {
                Row(Modifier.height(IntrinsicSize.Max)) {
                    KeySlot.LANES.forEachIndexed { i, lane ->
                        if (i > 0) Spacer(Modifier.width(GAP))
                        slot(lane, fiveWidth)
                    }
                    Spacer(Modifier.width(PAUSE_GAP))
                    slot(KeySlot.PAUSE, fiveWidth)
                }
            } else {
                KeySlot.LANES.chunked(laneColumns).forEach { row ->
                    Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        row.forEach { slot(it, laneWidth) }
                    }
                }
                Row(Modifier.height(IntrinsicSize.Max)) { slot(KeySlot.PAUSE, laneWidth) }
            }
        }
    }
}

/**
 * One slot's button: its name, and its key's label in large tabular type, or "Press a key" while it waits. An empty
 * lane takes the error colour, outline and label, with a warning symbol, never colour alone; an empty Pause is not
 * marked. Without a keyboard the buttons only show the map: flat, without an outline, and they cannot wait.
 */
@Composable
private fun SlotButton(
    slot: KeySlot,
    key: BindableKey?,
    label: String,
    spoken: String?,
    waiting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val emptyLane = key == null && slot.lane != null
    val title = slot.lane?.let { stringResource(R.string.keyboard_lane, it + 1) } ?: stringResource(R.string.keyboard_pause)
    val container = when {
        waiting -> scheme.primaryContainer
        enabled -> scheme.surfaceContainerLow
        else -> scheme.surfaceContainerHigh
    }
    val content = when {
        waiting -> scheme.onPrimaryContainer
        emptyLane -> scheme.error
        else -> scheme.onSurface
    }
    val border = when {
        waiting -> BorderStroke(2.dp, scheme.primary)
        emptyLane -> BorderStroke(2.dp, scheme.error)
        enabled -> BorderStroke(1.dp, scheme.outline)
        else -> null
    }
    val description = stringResource(R.string.keyboard_slot_description, title, spoken ?: stringResource(R.string.keyboard_slot_none))
    val state = when {
        waiting -> stringResource(R.string.keyboard_slot_waiting)
        emptyLane -> stringResource(R.string.keyboard_lane_empty, KeyboardText.number(slot))
        else -> null
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
        border = border,
        modifier = modifier.semantics {
            contentDescription = description
            if (state != null) stateDescription = state
        },
    ) {
        Column(
            Modifier
                .padding(horizontal = SLOT_PADDING, vertical = 10.dp)
                .clearAndSetSemantics { },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (emptyLane) {
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (waiting || emptyLane) content else scheme.onSurfaceVariant,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = with(LocalDensity.current) { LABEL_AREA.toDp() }),
                contentAlignment = Alignment.Center,
            ) {
                val text = if (waiting) stringResource(R.string.keyboard_press_a_key) else label
                val style = when {
                    waiting -> MaterialTheme.typography.labelLarge
                    text.length == 1 -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium)
                    else -> MaterialTheme.typography.titleMedium
                }
                Text(text, style = style.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * The key test: four tiles in the game's palette, lit while their keys are held, so the test looks like the tray it
 * predicts; the other keys held; the most lane keys held together; the keyboard's limit, when there is one; and the
 * button that starts and stops it.
 */
@Composable
private fun TestSection(
    map: KeyMap,
    keyboard: Keyboard?,
    words: KeyboardWords,
    test: KeyTest,
    devices: DeviceKeys,
    canTest: Boolean,
    onToggle: () -> Unit,
) {
    SectionHeader(stringResource(R.string.keyboard_test))
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Tiles(map, keyboard, words, test)
        Text(
            stringResource(if (test.testing) R.string.keyboard_test_running else R.string.keyboard_test_idle),
            style = MaterialTheme.typography.bodyMedium,
            color = if (test.testing) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (test.testing && test.others.isNotEmpty()) {
            val names = test.others.map { devices.name(it, words) }.distinct()
            Text(
                stringResource(R.string.keyboard_test_also_held, names.joinToString(words.listComma)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        test.shownMostHeld?.let { most ->
            Text(
                stringResource(R.string.keyboard_test_most, most.toString()),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                // Spoken when the number changes, which is when the text does.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        test.limit?.let { WarningLine(stringResource(R.string.keyboard_test_limit, it.toString())) }
        // A keyboard leaving mid-test leaves the test to stop.
        FilledTonalButton(onClick = onToggle, enabled = canTest || test.testing) {
            Text(
                stringResource(
                    when {
                        test.testing -> R.string.keyboard_test_stop
                        test.hasResult -> R.string.keyboard_test_again
                        else -> R.string.keyboard_test_start
                    },
                ),
            )
        }
        Text(
            stringResource(R.string.keyboard_test_ghost),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.keyboard_test_bounce),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The four lanes as the tray draws them (keyboard.md, Visual language): caps on the tray, labelled with their keys as a
 * game's caps are while a keyboard is connected, and lit lavender while held, which makes a cap nearly white, its label
 * then in the ground's ink. A lane without a key has a cap without a label. Each tile is one element for TalkBack.
 */
@Composable
private fun Tiles(map: KeyMap, keyboard: Keyboard?, words: KeyboardWords, test: KeyTest) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Every tile as tall as one holding a key's character at the text size set, so the four stay even.
    val tileHeight = maxOf(64.dp, with(density) { GLYPH_SIZE.toDp() } * 1.4f + 24.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(PlayPalette.KEY_TRAY))
            .padding(10.dp),
    ) {
        val tileWidth = (maxWidth - TILE_GAP * 3) / 4
        val room = with(density) { (tileWidth - 8.dp).toPx() }
        Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            KeySlot.LANES.forEach { slot ->
                val lane = slot.lane ?: 0
                val held = test.testing && test.held(lane)
                val description = stringResource(if (held) R.string.keyboard_tile_held else R.string.keyboard_tile_not_held, lane + 1)
                Box(
                    Modifier
                        .width(tileWidth)
                        .height(tileHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (held) LIT_CAP else Color(PlayPalette.SURFACE))
                        .clearAndSetSemantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    val key = map[slot]
                    if (key != null) {
                        val label = KeyboardText.cap(key, keyboard, words)
                        val base = TextStyle(fontWeight = FontWeight.Bold, fontSize = if (label.length == 1) GLYPH_SIZE else WORD_SIZE)
                        // A word too wide for its cap is narrowed, as on the game's caps, never cut.
                        val width = remember(label, base, measurer) { measurer.measure(label, base).size.width }
                        val style = if (width > room && room > 0f) base.copy(fontSize = base.fontSize * (room / width)) else base
                        Text(
                            label,
                            style = style,
                            color = if (held) Color(PlayPalette.GROUND) else Color(PlayPalette.MUTED_TEXT),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The screen's words from the string resources, for [KeyboardText], with the names of the keys the test lists. */
@Composable
private fun keyboardWords(): KeyboardWords {
    val names = listOf(
        KeyEvent.KEYCODE_SHIFT_LEFT to R.string.key_shift, KeyEvent.KEYCODE_SHIFT_RIGHT to R.string.key_shift,
        KeyEvent.KEYCODE_CTRL_LEFT to R.string.key_ctrl, KeyEvent.KEYCODE_CTRL_RIGHT to R.string.key_ctrl,
        KeyEvent.KEYCODE_ALT_LEFT to R.string.key_alt, KeyEvent.KEYCODE_ALT_RIGHT to R.string.key_alt,
        KeyEvent.KEYCODE_META_LEFT to R.string.key_meta, KeyEvent.KEYCODE_META_RIGHT to R.string.key_meta,
        KeyEvent.KEYCODE_CAPS_LOCK to R.string.key_caps_lock, KeyEvent.KEYCODE_TAB to R.string.key_tab,
        KeyEvent.KEYCODE_ENTER to R.string.key_enter, KeyEvent.KEYCODE_DEL to R.string.key_backspace,
        KeyEvent.KEYCODE_FORWARD_DEL to R.string.key_delete, KeyEvent.KEYCODE_FUNCTION to R.string.key_fn,
    ).associate { (code, id) -> code to stringResource(id) }
    return keyboardWords(names)
}

/** [KeyboardWords] with the given names of keys outside the key table; also the Settings row's words. */
@Composable
fun keyboardWords(keyNames: Map<Int, String>): KeyboardWords = KeyboardWords(
    space = stringResource(R.string.key_space),
    escape = stringResource(R.string.key_escape),
    escapeName = stringResource(R.string.key_escape_name),
    none = stringResource(R.string.value_none),
    rowLanes = stringResource(R.string.settings_keyboard_lanes),
    rowPause = stringResource(R.string.settings_keyboard_pause),
    rowConnected = stringResource(R.string.settings_keyboard_connected),
    rowNoKeyboard = stringResource(R.string.settings_keyboard_none_connected),
    rowNoLaneKey = stringResource(R.string.settings_keyboard_no_lane_key),
    rowNoPauseKey = stringResource(R.string.settings_keyboard_no_pause_key),
    separator = stringResource(R.string.summary_separator),
    listComma = stringResource(R.string.list_comma),
    listAnd = stringResource(R.string.list_and),
    laneEmpty = stringResource(R.string.keyboard_lane_empty),
    lanesEmpty = stringResource(R.string.keyboard_lanes_empty),
    swapLanes = stringResource(R.string.keyboard_swap_lanes),
    swapLaneAndPause = stringResource(R.string.keyboard_swap_lane_pause),
    swapPauseAndLane = stringResource(R.string.keyboard_swap_pause_lane),
    swapLaneAndEmptyLane = stringResource(R.string.keyboard_swap_lane_empty_lane),
    swapPauseAndEmptyLane = stringResource(R.string.keyboard_swap_pause_empty_lane),
    swapLaneAndEmptyPause = stringResource(R.string.keyboard_swap_lane_empty_pause),
    keyNames = keyNames,
)

/**
 * What the screen asks the system about the devices whose keys reach it, once per device: its layout, the key code each
 * place produces (`InputDevice.getKeyCodeForKeyLocation`, a binder call), and the names of the keys the test lists. The
 * virtual keyboard of `adb shell input` produces every place's own key code. A device that is no keyboard has none.
 */
private class DeviceKeys {
    private val layouts = HashMap<Int, ((Int) -> Int)?>()
    private val resolved = HashMap<Pair<Int, KeyMap>, ResolvedKeys?>()
    private val labels = HashMap<LaneHolder.Key, Char>()

    fun layout(deviceId: Int): ((Int) -> Int)? = layouts.getOrPut(deviceId) {
        if (deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return@getOrPut OWN_PLACES
        val device = InputDevice.getDevice(deviceId) ?: return@getOrPut null
        if (device.isVirtual || device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return@getOrPut null
        val produced = HashMap<Int, Int>()
        val layout: (Int) -> Int = { place -> produced.getOrPut(place) { device.getKeyCodeForKeyLocation(place) } }
        layout
    }

    fun keys(deviceId: Int, map: KeyMap): ResolvedKeys? =
        resolved.getOrPut(deviceId to map) { layout(deviceId)?.let(map::resolve) }

    /** Keeps the character [key] produces on its keyboard, for [name], as it goes down. */
    fun note(key: KeyInput) {
        val holder = LaneHolder.Key(key.deviceId, key.keyCode)
        if (holder in labels) return
        val characters = InputDevice.getDevice(key.deviceId)?.keyCharacterMap ?: KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        labels[holder] = characters.getDisplayLabel(key.keyCode)
    }

    /** A key outside the lanes as the test lists it: a word, its character, or Android's name for it. */
    fun name(key: LaneHolder.Key, words: KeyboardWords): String =
        KeyboardText.otherKey(key.keyCode, labels[key] ?: KeyLabels.NONE, words)
            ?: KeyEvent.keyCodeToString(key.keyCode).removePrefix("KEYCODE_").replace('_', ' ')
}

/** [intent] for one of Android's settings pages, or null when no activity here handles it (its reference warns of this). */
private fun systemSettings(context: Context, action: String): Intent? {
    val intent = Intent(action)
    val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
    return intent.takeIf { context.packageManager.resolveActivity(it, flags) != null }
}

private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Gone since the screen looked: nothing to open.
    }
}

private val GAP = 8.dp

/** Between the lanes and Pause, when it sits beside them: Pause is apart from the lanes, as it is on the keyboard. */
private val PAUSE_GAP = 16.dp

/** The narrowest a slot may be for Pause to sit beside the lanes: below it, five would crowd one row. */
private val ROOMY_SLOT = 80.dp
private val SLOT_PADDING = 8.dp

/** The warning symbol and its gap before an empty lane's name. */
private val WARNING_ROOM = 20.dp

/** The key label's height: one line of large type, or two of "Press a key". */
private val LABEL_AREA = 40.sp

/** A cap lit to the full: the cap's colour with the lavender added, as the renderer adds it. */
private val LIT_CAP = Color(0xFFDFD3FF)

private val TILE_GAP = 10.dp

/** The tiles' labels: a key's character, as large as the portrait caps', and a word, smaller. */
private val GLYPH_SIZE = 24.sp
private val WORD_SIZE = 18.sp

/** The virtual keyboard's layout: every place produces its own key code. */
private val OWN_PLACES: (Int) -> Int = { place -> place }
