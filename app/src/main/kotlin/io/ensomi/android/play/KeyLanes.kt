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
)

/**
 * Lane keys into lane presses and releases, from every keyboard at once (keyboard.md, Which events play a lane). A key
 * belongs to its slot by the key code its place produces on that keyboard, in the layout Android uses for it:
 * [keyboards] gives each connected keyboard with letter keys its [ResolvedKeys], and null for any other device, whose
 * keys play nothing. The keys `adb shell input` injects come from the virtual keyboard, where every place produces its
 * own key code. A fresh down presses its lane and an up releases it, canceled or not, so press and release stay paired;
 * a repeat presses nothing. Modifiers held at the same time do not matter. Used on the main thread.
 */
class KeyLanes(
    map: KeyMap,
    private val keyboards: (deviceId: Int) -> ResolvedKeys?,
    private val sender: LaneSender,
) {
    private val virtual = map.resolve { it }

    /** Whether [deviceId] is a keyboard the game reads keys from. */
    fun isKeyboard(deviceId: Int): Boolean = keys(deviceId) != null

    /**
     * The slot [key] belongs to, or null: from a device that is not a keyboard, a key the map does not hold, or an event
     * that never plays a lane or pauses (a fallback action, an input method's key, a capacitive button, `ACTION_MULTIPLE`).
     */
    fun slotOf(key: KeyInput): KeySlot? {
        if (key.action != KeyEvent.ACTION_DOWN && key.action != KeyEvent.ACTION_UP) return null
        if (key.flags and NEVER_PLAYS != 0) return null
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
            key.action == KeyEvent.ACTION_UP -> sender.release(holder, eventNanos)
            pressing && key.repeatCount == 0 -> sender.press(holder, lane, eventNanos)
            else -> false
        }
    }

    private fun keys(deviceId: Int): ResolvedKeys? =
        if (deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) virtual else keyboards(deviceId)

    private companion object {
        val NEVER_PLAYS = KeyEvent.FLAG_FALLBACK or KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_VIRTUAL_HARD_KEY
        const val NANOS_PER_MS = 1_000_000L
    }
}
