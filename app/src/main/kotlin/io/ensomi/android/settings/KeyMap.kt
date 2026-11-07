package io.ensomi.android.settings

import android.view.KeyEvent

/** The five things a key can do in a game: play lane 1, 2, 3 or 4, or pause. */
enum class KeySlot {
    LANE_1, LANE_2, LANE_3, LANE_4, PAUSE;

    /** The lane this slot plays, 0 to 3; null for Pause. */
    val lane: Int? get() = if (this == PAUSE) null else ordinal

    /** The lane that waits for a key after a bind of this slot that swapped nothing: none after lane 4 or Pause. */
    val nextLane: KeySlot? get() = when (this) {
        LANE_1 -> LANE_2
        LANE_2 -> LANE_3
        LANE_3 -> LANE_4
        LANE_4, PAUSE -> null
    }

    companion object {
        val LANES = listOf(LANE_1, LANE_2, LANE_3, LANE_4)
    }
}

/**
 * A key a slot may take, named for its place on the keyboard (keyboard.md, D1): [keyCode] is the key code a US QWERTY
 * keyboard produces there, and [name] Android's symbolic name for it without `KEYCODE_` (`Z`, `PERIOD`, `DPAD_LEFT`,
 * `ESCAPE`), as the settings file keeps it. [label] is the US QWERTY keycap, for when no keyboard is there to ask; null
 * for Space and Escape, which the screens name in words.
 */
data class BindableKey(val name: String, val keyCode: Int, val label: String?)

/**
 * The keys a slot may take, one table for all five (keyboard.md, O9): the letters, the main block's digits and
 * punctuation, Space, the four arrows and Escape. Everything else is refused: Back, Enter, Tab and Backspace, which move
 * around the app; the modifiers; volume and media keys, which are the system's; function keys, often media keys behind
 * Fn on tablet keyboards; the numeric keypad, whose keys turn into others with Num Lock off; and the editing block.
 */
object KeyTable {
    val keys: List<BindableKey> = buildList {
        for (i in 0 until 26) add(BindableKey(('A' + i).toString(), KeyEvent.KEYCODE_A + i, ('A' + i).toString()))
        for (i in 0..9) add(BindableKey(i.toString(), KeyEvent.KEYCODE_0 + i, i.toString()))
        add(BindableKey("GRAVE", KeyEvent.KEYCODE_GRAVE, "`"))
        add(BindableKey("MINUS", KeyEvent.KEYCODE_MINUS, "-"))
        add(BindableKey("EQUALS", KeyEvent.KEYCODE_EQUALS, "="))
        add(BindableKey("LEFT_BRACKET", KeyEvent.KEYCODE_LEFT_BRACKET, "["))
        add(BindableKey("RIGHT_BRACKET", KeyEvent.KEYCODE_RIGHT_BRACKET, "]"))
        add(BindableKey("BACKSLASH", KeyEvent.KEYCODE_BACKSLASH, "\\"))
        add(BindableKey("SEMICOLON", KeyEvent.KEYCODE_SEMICOLON, ";"))
        add(BindableKey("APOSTROPHE", KeyEvent.KEYCODE_APOSTROPHE, "'"))
        add(BindableKey("COMMA", KeyEvent.KEYCODE_COMMA, ","))
        add(BindableKey("PERIOD", KeyEvent.KEYCODE_PERIOD, "."))
        add(BindableKey("SLASH", KeyEvent.KEYCODE_SLASH, "/"))
        add(BindableKey("SPACE", KeyEvent.KEYCODE_SPACE, null))
        add(BindableKey("DPAD_UP", KeyEvent.KEYCODE_DPAD_UP, "↑"))
        add(BindableKey("DPAD_DOWN", KeyEvent.KEYCODE_DPAD_DOWN, "↓"))
        add(BindableKey("DPAD_LEFT", KeyEvent.KEYCODE_DPAD_LEFT, "←"))
        add(BindableKey("DPAD_RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT, "→"))
        add(BindableKey("ESCAPE", KeyEvent.KEYCODE_ESCAPE, null))
    }

    private val byName = keys.associateBy { it.name }

    /** The key the settings file names [name], or null for a name not in the table. */
    fun named(name: String): BindableKey? = byName[name]

    /**
     * The key code the place [location] produces on a keyboard whose layout gives [keyCodeAt] for each place
     * (`InputDevice.getKeyCodeForKeyLocation`). Where the layout cannot say (`KEYCODE_UNKNOWN`), the place's own US
     * QWERTY key code.
     */
    fun produced(location: Int, keyCodeAt: (Int) -> Int): Int =
        keyCodeAt(location).takeIf { it != KeyEvent.KEYCODE_UNKNOWN } ?: location

    /**
     * The key whose place produces [keyCode] on a keyboard whose layout is [keyCodeAt]: what a pressed key binds. Null
     * for a key no slot may take.
     */
    fun producing(keyCode: Int, keyCodeAt: (Int) -> Int): BindableKey? =
        keys.firstOrNull { produced(it.keyCode, keyCodeAt) == keyCode }
}

/**
 * The key map: a key, or none, for each of the five slots, by place on the keyboard (keyboard.md, D5), no key in two
 * slots. It is complete when the four lanes have keys; Pause may be empty, and a lane may be, in which case that lane
 * plays by touch only (O10).
 */
data class KeyMap(val lanes: List<BindableKey?>, val pause: BindableKey?) {
    init {
        require(lanes.size == KeySlot.LANES.size) { "A key map has four lanes, not ${lanes.size}" }
        val bound = slots().filterNotNull()
        require(bound.size == bound.toSet().size) { "A key map has a key in two slots" }
    }

    operator fun get(slot: KeySlot): BindableKey? = when (val lane = slot.lane) {
        null -> pause
        else -> lanes[lane]
    }

    /** Every lane has a key. */
    val isComplete: Boolean get() = lanes.all { it != null }

    /** The lanes without a key, in order: what the empty lane's notice names. */
    val emptyLanes: List<KeySlot> get() = KeySlot.LANES.filter { this[it] == null }

    /** The slot [key] is in, if any. */
    fun slotOf(key: BindableKey): KeySlot? = KeySlot.entries.firstOrNull { this[it] == key }

    /**
     * [slot] takes [key]. A key already in another slot swaps the two, so that slot takes [slot]'s key, or none if it
     * had none (D3); a swap ends the wait for a key, so the player sees what moved. A bind that swapped nothing moves
     * the wait to the next lane, lane 1 to 4: never on to Pause, and after lane 4 the wait ends.
     */
    fun bind(slot: KeySlot, key: BindableKey): KeyBind {
        val other = slotOf(key)?.takeIf { it != slot }
        val keys = slots().toMutableList()
        if (other != null) keys[other.ordinal] = this[slot]
        keys[slot.ordinal] = key
        return KeyBind(of(keys), swapped = other, next = if (other == null) slot.nextLane else null)
    }

    /** [slot] without a key. */
    fun clear(slot: KeySlot): KeyMap = of(slots().toMutableList().also { it[slot.ordinal] = null })

    /** The map on one keyboard, whose layout gives [keyCodeAt] for each place: the key code each slot's key produces. */
    fun resolve(keyCodeAt: (Int) -> Int): ResolvedKeys =
        ResolvedKeys(slots().map { key -> key?.let { KeyTable.produced(it.keyCode, keyCodeAt) } ?: KeyEvent.KEYCODE_UNKNOWN })

    /** The five slots' keys in [KeySlot] order. */
    private fun slots(): List<BindableKey?> = lanes + pause

    companion object {
        /** Z X . / for the lanes, the bottom row's two ends, one pair for each hand, and Escape for Pause (D5). */
        val DEFAULT = KeyMap(listOf("Z", "X", "PERIOD", "SLASH").map(KeyTable::named), KeyTable.named("ESCAPE"))

        private fun of(slots: List<BindableKey?>) =
            KeyMap(slots.subList(0, KeySlot.LANES.size).toList(), slots[KeySlot.PAUSE.ordinal])
    }
}

/**
 * What a bind did: the [map] after it; the slot that gave its key up in a swap and now holds the bound slot's old key,
 * or none ([swapped]); and the slot that waits for a key next, or null when the wait ends ([next]).
 */
data class KeyBind(val map: KeyMap, val swapped: KeySlot?, val next: KeySlot?)

/**
 * The key map on one keyboard: the key code each slot's key produces in that keyboard's layout, by [KeySlot], with
 * `KEYCODE_UNKNOWN` for an empty slot. Made when the keyboard connects or its layout may have changed, never per key.
 */
class ResolvedKeys(keyCodes: List<Int>) {
    private val keyCodes = keyCodes.toIntArray()

    /** The slot whose key produces [keyCode] here, or null. */
    fun slotOf(keyCode: Int): KeySlot? {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return null
        for (i in keyCodes.indices) if (keyCodes[i] == keyCode) return KeySlot.entries[i]
        return null
    }

    /** The key code [slot]'s key produces here; null for an empty slot. */
    fun keyCode(slot: KeySlot): Int? = keyCodes[slot.ordinal].takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
}
