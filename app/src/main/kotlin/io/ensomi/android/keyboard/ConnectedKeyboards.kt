package io.ensomi.android.keyboard

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import io.ensomi.android.R
import io.ensomi.android.prototype.DebugTools
import io.ensomi.android.settings.BindableKey
import io.ensomi.android.settings.KeyMap
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.KeyTable
import io.ensomi.android.settings.ResolvedKeys
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * A keyboard the game reads keys from: a device with letter keys that is not virtual (keyboard.md, Who is connected).
 * [keys] is the key map on it, the key code each slot's key produces in the layout Android uses for this keyboard, and
 * [labels] each slot's label on its caps, by [KeySlot], null for an empty slot; both were resolved with [map].
 */
data class Keyboard(
    val deviceId: Int,
    /** Free text, for the screens only: never logged, journalled or exported (keyboard.md, Diagnostics). */
    val name: String,
    val vendorId: Int,
    val productId: Int,
    /** USB or Bluetooth, unless its configuration says otherwise (`InputDevice.isExternal`). */
    val external: Boolean,
    val keys: ResolvedKeys,
    val labels: List<String?>,
    val map: KeyMap = KeyMap.DEFAULT,
) {
    /** The four lanes' labels, lane 1 to 4. */
    val laneLabels: List<String?> get() = labels.subList(0, KeySlot.LANES.size)

    /**
     * [key]'s label on this keyboard, when [map] holds it: right whichever map the screen shows, since a swap keeps the
     * map's keys. Null for a key [map] does not hold, such as one bound a moment ago, before this keyboard was resolved
     * with the new map.
     */
    fun labelOf(key: BindableKey): String? = map.slotOf(key)?.let { labels[it.ordinal] }

    /** Vendor and product id in hexadecimal, as the logs name a keyboard: `046d:b342`. */
    val ids: String get() = String.format(Locale.ROOT, "%04x:%04x", vendorId, productId)

    /** Without the name, so that no log can take it by accident. */
    override fun toString(): String = "Keyboard($deviceId, $ids, external=$external)"
}

/** The keyboards connected, as the screens and the game read them; the app's is [ConnectedKeyboards]. */
interface Keyboards {
    /** In the order they connected: the first gives the caps their labels. */
    val keyboards: StateFlow<List<Keyboard>>

    /** Keyboard [deviceId]'s map, read per key event without asking the system; null for a device that is none. */
    fun keys(deviceId: Int): ResolvedKeys?

    /** Every keyboard resolved again: at each `ON_START`, since its layout may have changed meanwhile. */
    fun refresh()
}

/**
 * Who is connected, from what the input system says, as plain values (keyboard.md, Key identity and the map): [describe]
 * asks the system about one device and resolves [KeyMap] on it, binder calls into the system, so it runs only when a
 * device connects ([added]) or changes ([changed]), at every start ([started]) and when the map changes, never per key.
 * [describe] returns null for a device that is no keyboard the game reads, which is remembered until it changes. A key
 * from a device not heard of yet, which can come before the input system's own word of it, resolves it once. Used on
 * the main thread.
 */
class KeyboardRoster(private val describe: (deviceId: Int, map: KeyMap) -> Keyboard?) {
    private val state = MutableStateFlow<List<Keyboard>>(emptyList())
    val keyboards: StateFlow<List<Keyboard>> = state.asStateFlow()
    private val notKeyboards = HashSet<Int>()
    private var map = KeyMap.DEFAULT

    fun added(deviceId: Int) = resolve(deviceId)

    fun changed(deviceId: Int) = resolve(deviceId)

    fun removed(deviceId: Int) {
        notKeyboards.remove(deviceId)
        if (state.value.any { it.deviceId == deviceId }) state.value = state.value.filter { it.deviceId != deviceId }
    }

    /**
     * The devices the system lists now, [deviceIds]: each resolved again, those already known keeping their place, new
     * ones after them in the system's order, and those gone dropped.
     */
    fun started(deviceIds: List<Int>) {
        val present = deviceIds.toSet()
        val known = state.value.map { it.deviceId }.filter { it in present }
        notKeyboards.clear()
        state.value = (known + deviceIds.filter { it !in known }).mapNotNull(::describeOrNote)
    }

    /** The map changed (Settings › Keyboard): every keyboard resolved again with it. */
    fun mapChanged(next: KeyMap) {
        if (next == map) return
        map = next
        state.value = state.value.mapNotNull { describeOrNote(it.deviceId) }
    }

    /** Per key: what is known, asking the system only about a device never heard of, once. */
    fun keys(deviceId: Int): ResolvedKeys? {
        find(deviceId)?.let { return it.keys }
        if (deviceId in notKeyboards) return null
        resolve(deviceId)
        return find(deviceId)?.keys
    }

    private fun find(deviceId: Int): Keyboard? = state.value.firstOrNull { it.deviceId == deviceId }

    /** Asks the system about [deviceId] again: a keyboard takes its place in the list, or the end; another leaves it. */
    private fun resolve(deviceId: Int) {
        notKeyboards.remove(deviceId)
        val keyboard = describeOrNote(deviceId)
        val list = state.value
        val at = list.indexOfFirst { it.deviceId == deviceId }
        if (keyboard == null) {
            if (at >= 0) state.value = list.filter { it.deviceId != deviceId }
        } else if (at < 0) {
            state.value = list + keyboard
        } else {
            state.value = list.toMutableList().also { it[at] = keyboard }
        }
    }

    private fun describeOrNote(deviceId: Int): Keyboard? =
        describe(deviceId, map).also { if (it == null) notKeyboards += deviceId }
}

/**
 * The label a cap shows for a key (keyboard.md, Play with a keyboard): the character the key produces in the keyboard's
 * layout, [displayLabel] from its key character map, or the key table's when the map has none; Space and Escape in
 * words, [space] and [escape]; the arrows as arrows. Null when nothing names the key.
 */
object KeyLabels {
    fun of(keyCode: Int, displayLabel: Char, space: String, escape: String): String? = when {
        keyCode == KeyEvent.KEYCODE_SPACE -> space
        keyCode == KeyEvent.KEYCODE_ESCAPE -> escape
        displayLabel != NONE && !displayLabel.isWhitespace() && !displayLabel.isISOControl() -> displayLabel.toString()
        else -> KeyTable.keys.firstOrNull { it.keyCode == keyCode }?.label
    }

    /** What `KeyCharacterMap.getDisplayLabel` gives a key without a label. */
    const val NONE = '\u0000'

    /** The labels of [keys]'s five slots on a keyboard whose key character map labels a key code [displayLabel]. */
    fun of(keys: ResolvedKeys, displayLabel: (Int) -> Char, space: String, escape: String): List<String?> =
        KeySlot.entries.map { slot -> keys.keyCode(slot)?.let { of(it, displayLabel(it), space, escape) } }
}

/**
 * The keyboards connected to the device, one for the process in `AppStores`: an `InputManager.InputDeviceListener` on
 * the main looper keeps a [KeyboardRoster] of every non-virtual device with letter keys, resolved with the key map that
 * [maps] gives (Settings' map). It needs no permission; pairing is Android's. Debug builds log each keyboard by vendor
 * and product id under `EnsomiKeys`, never by name.
 */
class ConnectedKeyboards(context: Context, maps: Flow<KeyMap>, scope: CoroutineScope) : Keyboards {
    private val appContext = context.applicationContext
    private val input = appContext.getSystemService(InputManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val logs = DebugTools.enabled(appContext)
    private val roster = KeyboardRoster(::describe)

    override val keyboards: StateFlow<List<Keyboard>> = roster.keyboards

    init {
        input.registerInputDeviceListener(
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) = logged("connected", deviceId) { roster.added(deviceId) }

                override fun onInputDeviceChanged(deviceId: Int) = roster.changed(deviceId)

                override fun onInputDeviceRemoved(deviceId: Int) = logged("removed", deviceId) { roster.removed(deviceId) }
            },
            main,
        )
        main.post { refresh() }
        scope.launch(Dispatchers.Main) { maps.distinctUntilChanged().collect { roster.mapChanged(it) } }
    }

    override fun keys(deviceId: Int): ResolvedKeys? = roster.keys(deviceId)

    override fun refresh() {
        roster.started(input.inputDeviceIds.toList())
        if (logs) Log.i(TAG, "Keyboards: ${keyboards.value.joinToString { "${it.ids} (device ${it.deviceId})" }.ifEmpty { "none" }}")
    }

    /** [change], logged in debug builds when it changed the keyboards: by vendor and product id only. */
    private fun logged(what: String, deviceId: Int, change: () -> Unit) {
        val before = keyboards.value.firstOrNull { it.deviceId == deviceId }
        change()
        val after = keyboards.value.firstOrNull { it.deviceId == deviceId }
        val keyboard = after ?: before ?: return
        if (logs) Log.i(TAG, "Keyboard $what: ${keyboard.ids}, device ${keyboard.deviceId}, external ${keyboard.external}")
    }

    private fun describe(deviceId: Int, map: KeyMap): Keyboard? {
        val device = input.getInputDevice(deviceId) ?: return null
        if (device.isVirtual || device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return null
        val keys = map.resolve(device::getKeyCodeForKeyLocation)
        val characters = device.keyCharacterMap
        val labels = KeyLabels.of(
            keys,
            characters::getDisplayLabel,
            appContext.getString(R.string.key_space),
            appContext.getString(R.string.key_escape),
        )
        return Keyboard(deviceId, device.name, device.vendorId, device.productId, device.isExternal, keys, labels, map)
    }

    private companion object {
        const val TAG = "EnsomiKeys"
    }
}
