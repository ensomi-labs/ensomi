package io.ensomi.android.play

import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.ResolvedKeys

/**
 * A key event as plain values, so the rules for keys run without Android's `KeyEvent`: its action, key code, repeat
 * count, flags and `InputDevice` id, and its event time in milliseconds on `SystemClock.uptimeMillis()`, the base of
 * `System.nanoTime()`.
 */
data class KeyInput(
    val action: Int,
    val keyCode: Int,
    val repeatCount: Int,
    val flags: Int,
    val deviceId: Int,
    val eventTimeMs: Long,
) {
    companion object {
        fun of(event: KeyEvent) =
            KeyInput(event.action, event.keyCode, event.repeatCount, event.flags, event.deviceId, event.eventTime)
    }
}

/**
 * Lane keys into lane presses and releases, from every keyboard at once (keyboard.md, Which events play a lane). A key
 * belongs to its slot by the key code its place produces on that keyboard, in the layout Android uses for it:
 * [keyboards] gives each connected keyboard with letter keys its [ResolvedKeys], and null for any other device, whose
 * keys play nothing. The keys `adb shell input` injects come from the virtual keyboard, where every place produces its
 * own key code. A fresh down presses its lane and an up releases it, canceled or not, so press and release stay paired;
 * a repeat presses nothing. Modifiers held at the same time do not matter. Used on the main thread.
 *
 * A lane key whose down was seen belongs to its lane until its up, even if its keyboard's keys no longer resolve by then
 * (the keyboard went, or its layout changed). Every lane key held, whether its down pressed or not, counts towards
 * [mostLaneKeysHeld], the most lanes whose keys were down at once: what the keyboard reported, for the game's log.
 */
class KeyLanes(
    map: KeyMap,
    private val keyboards: (deviceId: Int) -> ResolvedKeys?,
    private val sender: LaneSender,
) {
    private val virtual = map.resolve { it }

    /** The lane keys down now, with their lanes. */
    private val down = HashMap<LaneHolder.Key, Int>()
    private val downInLane = IntArray(KeySlot.LANES.size)

    /** The most lanes whose keys were held down at once so far, 0 to 4. */
    var mostLaneKeysHeld = 0
        private set

    /** Whether [deviceId] is a keyboard the game reads keys from. */
    fun isKeyboard(deviceId: Int): Boolean = keys(deviceId) != null

    /**
     * The slot [key] belongs to, or null: from a device that is not a keyboard, a key the map does not hold, or an event
     * that never plays a lane or pauses (a fallback action, an input method's key, a capacitive button, `ACTION_MULTIPLE`).
     * The up of a lane key held belongs to the lane its down did.
     */
    fun slotOf(key: KeyInput): KeySlot? {
        if (key.action != KeyEvent.ACTION_DOWN && key.action != KeyEvent.ACTION_UP) return null
        if (key.flags and NEVER_PLAYS != 0) return null
        if (key.action == KeyEvent.ACTION_UP) down[LaneHolder.Key(key.deviceId, key.keyCode)]?.let { return KeySlot.LANES[it] }
        return keys(key.deviceId)?.slotOf(key.keyCode)
    }

    /**
     * An event of a key that plays [lane]: a fresh down presses the lane when [pressing], an up releases it whatever the
     * game shows by then, and a repeat does nothing. True when a press or release was sent.
     */
    fun play(key: KeyInput, lane: Int, pressing: Boolean): Boolean {
        val holder = LaneHolder.Key(key.deviceId, key.keyCode)
        val eventNanos = key.eventTimeMs * NANOS_PER_MS
        return when {
            key.action == KeyEvent.ACTION_UP -> {
                letGo(holder)
                sender.release(holder, eventNanos)
            }
            key.repeatCount == 0 -> {
                held(holder, lane)
                pressing && sender.press(holder, lane, eventNanos)
            }
            else -> false
        }
    }

    /**
     * Keyboard [deviceId] went, at [eventNanos]: its keys let go of their lanes. Android sends canceled ups for them
     * itself, before or after; whichever comes first releases, and the other finds nothing held.
     */
    fun keyboardGone(deviceId: Int, eventNanos: Long) {
        down.keys.filter { it.deviceId == deviceId }.forEach(::letGo)
        sender.releaseKeys(deviceId, eventNanos)
    }

    private fun held(holder: LaneHolder.Key, lane: Int) {
        down.put(holder, lane)?.let { downInLane[it]-- }
        downInLane[lane]++
        mostLaneKeysHeld = maxOf(mostLaneKeysHeld, downInLane.count { it > 0 })
    }

    private fun letGo(holder: LaneHolder.Key) {
        down.remove(holder)?.let { downInLane[it]-- }
    }

    private fun keys(deviceId: Int): ResolvedKeys? =
        if (deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) virtual else keyboards(deviceId)

    private companion object {
        val NEVER_PLAYS = KeyEvent.FLAG_FALLBACK or KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_VIRTUAL_HARD_KEY
        const val NANOS_PER_MS = 1_000_000L
    }
}
