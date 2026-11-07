package io.ensomi.android.keyboard

import io.ensomi.android.settings.BindableKey
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot

/**
 * The words the keyboard's screens use, from the app's string resources. The templates take `%1$s`-style arguments, as
 * `getString` does; numbers in sentences go in as text.
 */
data class KeyboardWords(
    /** A cap's word for Space and for Escape ("Space", "Esc"), and Escape in a sentence ("Escape"). */
    val space: String,
    val escape: String,
    val escapeName: String,
    /** An empty slot's label: "None". */
    val none: String,
    /** The Settings row: "Lanes: %1$s", "Pause: %1$s", "Connected: %1$s", "No keyboard connected". */
    val rowLanes: String,
    val rowPause: String,
    val rowConnected: String,
    val rowNoKeyboard: String,
    /** An empty lane among the row's keys ("—"), and an empty Pause ("none"). */
    val rowNoLaneKey: String,
    val rowNoPauseKey: String,
    /** " · " between the row's parts; ", " and "%1$s and %2$s" in lists. */
    val separator: String,
    val listComma: String,
    val listAnd: String,
    /** "Lane %1$s has no key.", "Lanes %1$s have no key." */
    val laneEmpty: String,
    val lanesEmpty: String,
    /** The swaps: [KeyboardText.swapped]. */
    val swapLanes: String,
    val swapLaneAndPause: String,
    val swapPauseAndLane: String,
    val swapLaneAndEmptyLane: String,
    val swapPauseAndEmptyLane: String,
    val swapLaneAndEmptyPause: String,
    /** Keys outside the key table named in words, by key code: Shift, Ctrl, Enter and the like. */
    val keyNames: Map<Int, String> = emptyMap(),
    /**
     * Labels TalkBack would not say, punctuation and arrows, in words, by label: "." is "period", "←" "left arrow". A
     * lone "." after a comma is read as the end of a sentence, so "Lane 3 key, ." would lose its key.
     */
    val symbolNames: Map<String, String> = emptyMap(),
)

/**
 * What the Keyboard screen and its Settings row say of the keys (keyboard.md, Settings › Keyboard): the keys' labels as
 * the first keyboard connected labels them, in the layout Android uses for it, or the US QWERTY keycaps when none is;
 * Space and Escape in words.
 */
object KeyboardText {
    /** [key]'s label on a cap or a slot's button; "None" for an empty slot. */
    fun cap(key: BindableKey?, keyboard: Keyboard?, words: KeyboardWords): String {
        if (key == null) return words.none
        keyboard?.labelOf(key)?.let { return it }
        return when (key.name) {
            SPACE -> words.space
            ESCAPE -> words.escape
            else -> key.label ?: key.name
        }
    }

    /** [key] in a sentence: as on its cap, with Escape spelled out. */
    fun spoken(key: BindableKey, keyboard: Keyboard?, words: KeyboardWords): String =
        if (key.name == ESCAPE) words.escapeName else cap(key, keyboard, words)

    /** [key] for TalkBack: as in a sentence, with punctuation and arrows in words ([KeyboardWords.symbolNames]). */
    fun said(key: BindableKey, keyboard: Keyboard?, words: KeyboardWords): String = say(spoken(key, keyboard, words), words)

    /** A label for TalkBack: punctuation and arrows in words, anything else as it is. */
    fun say(label: String, words: KeyboardWords): String = words.symbolNames[label] ?: label

    /**
     * The Settings row's supporting text: "Lanes: Z X . / · Pause: Esc · Connected: K380", or "… · No keyboard
     * connected". An empty lane shows as "—" among the lanes, an empty Pause as "none"; [emptyLanes] says which lanes.
     */
    fun row(map: KeyMap, keyboards: List<Keyboard>, words: KeyboardWords): String = row(map, keyboards, words, forTalkBack = false)

    /** The row as TalkBack reads it: the keys in words where their labels are symbols, "Lanes: Z, X, period, slash · …". */
    fun saidRow(map: KeyMap, keyboards: List<Keyboard>, words: KeyboardWords): String =
        row(map, keyboards, words, forTalkBack = true)

    private fun row(map: KeyMap, keyboards: List<Keyboard>, words: KeyboardWords, forTalkBack: Boolean): String {
        val first = keyboards.firstOrNull()
        val label = { key: BindableKey -> if (forTalkBack) said(key, first, words) else cap(key, first, words) }
        val gap = if (forTalkBack) words.listComma else " "
        val lanes = map.lanes.joinToString(gap) { key -> key?.let(label) ?: words.rowNoLaneKey }
        val pause = map.pause?.let(label) ?: words.rowNoPauseKey
        val connection = if (keyboards.isEmpty()) {
            words.rowNoKeyboard
        } else {
            words.rowConnected.format(keyboards.joinToString(words.listComma) { it.name })
        }
        return listOf(words.rowLanes.format(lanes), words.rowPause.format(pause), connection).joinToString(words.separator)
    }

    /** "Lane 3 has no key." or "Lanes 2 and 3 have no key."; null while every lane has a key. Pause is never named. */
    fun emptyLanes(map: KeyMap, words: KeyboardWords): String? {
        val lanes = map.emptyLanes.map { number(it) }
        return when (lanes.size) {
            0 -> null
            1 -> words.laneEmpty.format(lanes.single())
            else -> words.lanesEmpty.format(list(lanes, words))
        }
    }

    /** "1, 2 and 3". */
    fun list(items: List<String>, words: KeyboardWords): String =
        if (items.size < 2) items.joinToString() else words.listAnd.format(items.dropLast(1).joinToString(words.listComma), items.last())

    /**
     * What a swap moved: "X now plays lane 1, and Z lane 2.", "Escape now plays lane 4, and / pauses.", and with an empty
     * slot, "/ now pauses, and lane 4 has no key." [forTalkBack] names symbols in words: "Escape now plays lane 4, and
     * slash pauses."
     */
    fun swapped(note: CaptureNote.Swapped, keyboard: Keyboard?, words: KeyboardWords, forTalkBack: Boolean = false): String {
        val name = { key: BindableKey -> if (forTalkBack) said(key, keyboard, words) else spoken(key, keyboard, words) }
        val key = name(note.key)
        val otherKey = note.otherKey?.let(name)
        val lane = note.slot.lane?.let { (it + 1).toString() }
        val otherLane = note.other.lane?.let { (it + 1).toString() }
        return when {
            lane != null && otherLane != null && otherKey != null -> words.swapLanes.format(key, lane, otherKey, otherLane)
            lane != null && otherLane != null -> words.swapLaneAndEmptyLane.format(key, lane, otherLane)
            lane != null && otherKey != null -> words.swapLaneAndPause.format(key, lane, otherKey)
            lane != null -> words.swapLaneAndEmptyPause.format(key, lane)
            otherLane != null && otherKey != null -> words.swapPauseAndLane.format(key, otherKey, otherLane)
            otherLane != null -> words.swapPauseAndEmptyLane.format(key, otherLane)
            else -> key
        }
    }

    /**
     * A key outside the lanes, as the key test lists it ("Also held: Shift, A"): its word ([KeyboardWords.keyNames]),
     * the character it produces ([displayLabel], from the keyboard's key character map), or the key table's label;
     * null when nothing names it.
     */
    fun otherKey(keyCode: Int, displayLabel: Char, words: KeyboardWords): String? =
        words.keyNames[keyCode] ?: KeyLabels.of(keyCode, displayLabel, words.space, words.escape)

    /** A lane's number as people count, 1 to 4. */
    fun number(slot: KeySlot): String = ((slot.lane ?: 0) + 1).toString()

    private const val SPACE = "SPACE"
    private const val ESCAPE = "ESCAPE"
}
