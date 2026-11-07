package io.ensomi.android.settings

/**
 * One latency slot (latency.md): a name the player may give it, empty for none, and its value in milliseconds,
 * positive for later.
 */
data class LatencySlot(val name: String = "", val ms: Int = 0)

/**
 * The audio latency slots and the one the player chose by hand, [active], slot 0 by default; the output device
 * chooses nothing. The active slot's value is a game's audio latency A: how much later the sound is heard than the
 * picture would show its note, so the game holds the picture and the judging back by it. A Bluetooth pair that needed
 * an audio offset of −120 ms needs +120 ms here.
 */
data class AudioLatency(val slots: List<LatencySlot> = Latency.DEFAULT_SLOTS, val active: Int = 0) {
    init {
        require(slots.size == Latency.SLOTS) { "Audio latency has ${Latency.SLOTS} slots, not ${slots.size}" }
        require(active in Latency.INDICES) { "No audio latency slot $active" }
    }

    /** The active slot's value: the audio latency a game starts with. */
    val activeMs: Int get() = slots[active].ms

    /** The active slot set to [ms], within [Latency.RANGE]. */
    fun withActiveMs(ms: Int): AudioLatency = copy(slots = Latency.withMs(slots, active, ms))
}

/**
 * The input latency slots and the one each input method uses: touch slot 0 and the keyboard slot 1 by default (O1), and
 * every keyboard the keyboard's. A method's value is its input latency I: how late its presses and releases reach their
 * timestamps, the player's habit included, so they are judged that much earlier. Both methods may use one slot.
 */
data class InputLatency(
    val slots: List<LatencySlot> = Latency.DEFAULT_SLOTS,
    val touch: Int = 0,
    val keyboard: Int = 1,
) {
    init {
        require(slots.size == Latency.SLOTS) { "Input latency has ${Latency.SLOTS} slots, not ${slots.size}" }
        require(touch in Latency.INDICES && keyboard in Latency.INDICES) { "No input latency slot $touch or $keyboard" }
    }

    val touchMs: Int get() = slots[touch].ms
    val keyboardMs: Int get() = slots[keyboard].ms

    /** Slot [slot] set to [ms], within [Latency.RANGE]. */
    fun withMs(slot: Int, ms: Int): InputLatency = copy(slots = Latency.withMs(slots, slot, ms))
}

/** The rules both kinds of slot keep. */
object Latency {
    const val SLOTS = 3
    val INDICES = 0 until SLOTS

    /** Both kinds, in whole milliseconds: the range the audio and visual offsets had. */
    val RANGE = -300..300

    /** The most characters a slot's name keeps. */
    const val NAME_LENGTH = 24

    val DEFAULT_SLOTS = List(SLOTS) { LatencySlot() }

    /** A name as a slot keeps it: trimmed and cut to [NAME_LENGTH] characters, empty for none. */
    fun name(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.codePointCount(0, trimmed.length) <= NAME_LENGTH) return trimmed
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, NAME_LENGTH)).trimEnd()
    }

    fun ms(raw: Int): Int = raw.coerceIn(RANGE)

    /** [slots] with slot [index] set to [value], within the range. */
    fun withMs(slots: List<LatencySlot>, index: Int, value: Int): List<LatencySlot> =
        slots.mapIndexed { i, slot -> if (i == index) slot.copy(ms = ms(value)) else slot }
}

/**
 * The move from a 0.1.x settings file to latency slots (latency.md, Storage and migration; O5, option a), so that touch
 * play on each output a tester set up stays as it was, by the identity `A = −(audioOffset + visualOffset)`,
 * `I[touch] = visualOffset`:
 *
 * - touch's slot 0 takes the visual offset V; input slots 1 and 2 stay at 0;
 * - the audio slots take the outputs that have a stored offset, in the order the file lists them (the order their
 *   offsets were first set), up to three: slot k gets `−(A_o + V)` and the name the screens give output o. Outputs past
 *   the third are dropped. With no stored output and V not 0, slot 0 gets −V, the latency of an output without an
 *   offset;
 * - the active slot is the slot of the output Android would play on now, or 0.
 *
 * Values are read within the old range, as the old build read them, and kept within [Latency.RANGE].
 */
object LatencyMigration {
    class Result(val audio: AudioLatency, val input: InputLatency)

    /**
     * [offsets] is the file's `audioOffsets` by output key, in the file's order; [visualMs] its `visualOffsetMs`;
     * [currentKey] the key of the output Android would play on now; [nameOf] the name the screens give an output key.
     */
    fun of(offsets: Map<String, Int>, visualMs: Int, currentKey: String?, nameOf: (String) -> String): Result {
        val visual = Latency.ms(visualMs)
        val carried = offsets.entries.take(Latency.SLOTS)
        val audio = Latency.DEFAULT_SLOTS.toMutableList()
        if (carried.isEmpty()) {
            if (visual != 0) audio[0] = LatencySlot(ms = Latency.ms(-visual))
        } else {
            carried.forEachIndexed { k, (key, offsetMs) ->
                audio[k] = LatencySlot(Latency.name(nameOf(key)), Latency.ms(-(Latency.ms(offsetMs) + visual)))
            }
        }
        val active = carried.indexOfFirst { it.key == currentKey }.takeIf { it >= 0 } ?: 0
        val input = Latency.DEFAULT_SLOTS.toMutableList().also { it[0] = LatencySlot(ms = visual) }
        return Result(AudioLatency(audio, active), InputLatency(input))
    }
}
