package io.ensomi.android.keyboard

import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Handlers of events of type [E], the newest first: each says whether it took the event, and an event no handler takes
 * goes on (keyboard.md, Where key events arrive). Used on one thread, the main one for keys.
 */
class KeyRouter<E> {
    /** One registration: the same handler registered twice is two entries, each removed by its own [register] result. */
    private class Entry<E>(val handler: (E) -> Boolean)

    private val entries = ArrayList<Entry<E>>()

    /** [handler] sees events before every handler registered before it, until the returned function removes it. */
    fun register(handler: (E) -> Boolean): () -> Unit {
        val entry = Entry(handler)
        entries += entry
        return { entries.remove(entry) }
    }

    /**
     * [event] to each handler from the newest, until one takes it: true when one did. A handler that registers or removes
     * one while an event is handed round changes the next event's order, not this one's.
     */
    fun dispatch(event: E): Boolean {
        val now = entries.toTypedArray()
        for (i in now.indices.reversed()) if (now[i].handler(event)) return true
        return false
    }

    val size: Int get() = entries.size
}

/**
 * Keeps the window from being an input method target while any screen asks (keyboard.md, D6): [apply] turns the window's
 * `FLAG_ALT_FOCUSABLE_IM` on with the first ask and off when the last is let go. Counted, since the next screen can ask
 * before the one it replaces lets go. Used on the main thread.
 */
class InputMethodGuard(private val apply: (away: Boolean) -> Unit) {
    private var asks = 0

    /** Asks for the window to be no input method target, until the returned function is called, once. */
    fun keepAway(): () -> Unit {
        if (asks++ == 0) apply(true)
        var released = false
        return {
            if (!released) {
                released = true
                if (--asks == 0) apply(false)
            }
        }
    }

    val isAway: Boolean get() = asks > 0
}

/**
 * The activity's hardware keys, taken in `MainActivity.dispatchKeyEvent` before its views see them: screens register a
 * handler while they need keys ([HardwareKeyHandler]), the newest sees each event first, and an event none takes goes on
 * to the views and the system as before. It also keeps the window from being an input method target while the game's
 * screens are open ([KeepInputMethodAway]). One per activity, made with it.
 */
class HardwareKeys(window: () -> Window) {
    private val router = KeyRouter<KeyEvent>()

    private val guard = InputMethodGuard { away ->
        val flag = WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        if (away) window().addFlags(flag) else window().clearFlags(flag)
    }

    /**
     * Whether an Escape that no view handled is Back: on every screen but the Library (keyboard.md, O2). The game takes
     * its Escape before it gets that far.
     */
    var escapeGoesBack = false

    fun register(handler: (KeyEvent) -> Boolean): () -> Unit = router.register(handler)

    /** True when a screen took [event]. */
    fun dispatch(event: KeyEvent): Boolean = router.dispatch(event)

    fun keepInputMethodAway(): () -> Unit = guard.keepAway()
}

/** The activity's [HardwareKeys]; null outside `MainActivity`, where no screen takes keys. */
val LocalHardwareKeys = staticCompositionLocalOf<HardwareKeys?> { null }

/**
 * While this is in the composition and [enabled], [onKey] sees the activity's key events before the views do, and
 * before every handler registered earlier; it returns whether it took the event. The newest [onKey] is used.
 */
@Composable
fun HardwareKeyHandler(enabled: Boolean = true, onKey: (KeyEvent) -> Boolean) {
    val keys = LocalHardwareKeys.current ?: return
    val current by rememberUpdatedState(onKey)
    DisposableEffect(keys, enabled) {
        val unregister = if (enabled) keys.register { current(it) } else null
        onDispose { unregister?.invoke() }
    }
}

/**
 * While this is in the composition, the window is no input method target (`FLAG_ALT_FOCUSABLE_IM`), so the input method
 * never sees the keys and never starts word composition (keyboard.md, What stands before the app). For the game's
 * screens, which have no text field; a dialog over them is a window of its own and keeps its input method.
 */
@Composable
fun KeepInputMethodAway() {
    val keys = LocalHardwareKeys.current ?: return
    DisposableEffect(keys) {
        val release = keys.keepInputMethodAway()
        onDispose { release() }
    }
}
