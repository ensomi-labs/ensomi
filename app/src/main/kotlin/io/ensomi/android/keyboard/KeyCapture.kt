package io.ensomi.android.keyboard

import android.view.KeyEvent
import io.ensomi.android.play.KeyInput
import io.ensomi.android.play.LaneHolder
import io.ensomi.android.settings.BindableKey
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.KeyTable

/** What the wait for a key last said, besides its prompt (keyboard.md, Settings › Keyboard, item 2). */
sealed interface CaptureNote {
    /**
     * [slot] took [key], which [other] held, and [other] took [slot]'s old key, [otherKey], or none (D3). A swap ends
     * the wait, so the player sees what moved.
     */
    data class Swapped(val slot: KeySlot, val key: BindableKey, val other: KeySlot, val otherKey: BindableKey?) : CaptureNote

    /** A key no slot may take: Enter, Tab, Backspace, a modifier, a system key. The wait stays open. */
    data object Refused : CaptureNote
}

/**
 * The Keyboard screen's wait for a key, as plain values (keyboard.md, Settings › Keyboard, item 2). Pressing a slot's
 * button waits for its key; the next key from the key table binds there, and a key already in another slot swaps the
 * two. A bind that swapped nothing moves the wait to the next lane, lane 1 to 4: never on to Pause, and it ends after
 * lane 4 and after Pause. A swap ends the wait. Back, Cancel or a touch elsewhere end it with nothing changed; Escape is
 * a key like any other and binds (O9). Clear empties the slot.
 *
 * While it waits, every key from a keyboard is the capture's, so Space or Enter cannot press a button under it, apart
 * from Back, which ends the wait through the activity's Back. A key whose down the capture took keeps its repeats and
 * its up, even once the wait is over, so the up of the key that bound lane 4 reaches nothing. Keys from devices that
 * are not keyboards, such as the phone's own volume buttons, go on as ever.
 */
data class KeyCapture(
    /** The slot waiting for its key, or null. */
    val waiting: KeySlot? = null,
    val note: CaptureNote? = null,
    /** Keys whose downs the capture took, until their ups. */
    private val spent: Set<LaneHolder.Key> = emptySet(),
) {
    /** What one key did: the capture after it, whether it was taken, and the map it made, if it changed the map. */
    data class Step(val capture: KeyCapture, val taken: Boolean, val map: KeyMap? = null)

    /** [slot] waits for its key; pressing the waiting slot's button again ends the wait. */
    fun start(slot: KeySlot): KeyCapture = if (waiting == slot) cancel() else copy(waiting = slot, note = null)

    /** Back, Cancel or a touch elsewhere: the wait ends and the map is as it was. */
    fun cancel(): KeyCapture = if (waiting == null) this else copy(waiting = null, note = null)

    /** Nothing waits and nothing is said: after Use default keys, or the test starting. */
    fun quiet(): KeyCapture = copy(waiting = null, note = null)

    /** Clear: the waiting slot gives up its key and the wait ends. Null when nothing waits. */
    fun clear(map: KeyMap): Pair<KeyCapture, KeyMap>? {
        val slot = waiting ?: return null
        return copy(waiting = null, note = null) to map.clear(slot)
    }

    /**
     * [key] while the map is [map]. [layoutOf] gives a keyboard's layout, the key code each place on it produces
     * (`InputDevice.getKeyCodeForKeyLocation`; the virtual keyboard of `adb shell input` produces every place's own),
     * and null for a device that is no keyboard.
     */
    fun key(key: KeyInput, map: KeyMap, layoutOf: (deviceId: Int) -> ((Int) -> Int)?): Step {
        if (key.keyCode == KeyEvent.KEYCODE_BACK) return Step(this, taken = false)
        val holder = LaneHolder.Key(key.deviceId, key.keyCode)
        if (holder in spent && (key.action == KeyEvent.ACTION_UP || key.repeatCount > 0)) {
            return Step(if (key.action == KeyEvent.ACTION_UP) copy(spent = spent - holder) else this, taken = true)
        }
        val slot = waiting ?: return Step(this, taken = false)
        val layout = layoutOf(key.deviceId) ?: return Step(this, taken = false)
        if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount != 0 || key.flags and NEVER_BINDS != 0) {
            return Step(this, taken = true)
        }
        val taken = spent + holder
        val bindable = KeyTable.producing(key.keyCode, layout)
            ?: return Step(copy(note = CaptureNote.Refused, spent = taken), taken = true)
        val bind = map.bind(slot, bindable)
        val note = bind.swapped?.let { CaptureNote.Swapped(slot, bindable, it, map[slot]) }
        return Step(KeyCapture(waiting = bind.next, note = note, spent = taken), taken = true, map = bind.map)
    }

    private companion object {
        /** A fallback action, an input method's key or a capacitive button: never a key someone pressed to bind. */
        val NEVER_BINDS =KeyEvent.FLAG_FALLBACK or KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_VIRTUAL_HARD_KEY
    }
}
