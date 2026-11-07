package io.ensomi.android.settings

import android.media.AudioDeviceInfo

/** An output device as `AudioDeviceInfo` describes it: its type (`TYPE_*`) and product name. */
data class OutputDevice(val type: Int, val productName: String)

/** What kind of output a device is, for its name on screen. */
enum class OutputKind { SPEAKER, WIRED, USB, BLUETOOTH, HEARING_AID, HDMI, OTHER }

/**
 * Outputs, and which one play would use now. Each device is keyed by its type and product name, so a given pair of
 * Bluetooth headphones is apart from the speaker and from other headphones; 0.1.x kept an audio offset under each key,
 * which the latency slots took over (latency.md, Storage and migration). Until a game's `AudioTrack` says where it
 * plays, the output is the media route Android picks by default: Bluetooth, then wired or USB, then HDMI, then the
 * speaker.
 */
object OutputRoutes {
    /** The type's name and the product name, such as `bluetooth-a2dp/WH-1000XM4`. */
    fun key(device: OutputDevice): String = "${typeName(device.type)}/${device.productName.trim()}"

    /** The device [key] names, or null for a key not made by [key]. */
    fun device(key: String): OutputDevice? {
        val slash = key.indexOf('/')
        if (slash < 0) return null
        val name = key.substring(0, slash)
        val type = TYPE_NAMES.entries.firstOrNull { it.value == name }?.key
            ?: name.removePrefix("type-").takeIf { name.startsWith("type-") }?.toIntOrNull()
            ?: return null
        return OutputDevice(type, key.substring(slash + 1))
    }

    fun kind(type: Int): OutputKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_DOCK,
        -> OutputKind.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        -> OutputKind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> OutputKind.BLUETOOTH
        AudioDeviceInfo.TYPE_HEARING_AID -> OutputKind.HEARING_AID
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> OutputKind.HDMI
        else -> OutputKind.OTHER
    }

    /**
     * The device media plays on among [devices], as Android's default route picks it, or null when none can play
     * music (the earpiece, the phone line and the like are left out).
     */
    fun current(devices: List<OutputDevice>): OutputDevice? =
        devices.filter { rank(it.type) != null }.minByOrNull { rank(it.type)!! }

    /** Lower is preferred; null for outputs music never plays on by default. */
    private fun rank(type: Int): Int? = when (kind(type)) {
        OutputKind.BLUETOOTH, OutputKind.HEARING_AID -> 0
        OutputKind.USB, OutputKind.WIRED -> 1
        OutputKind.HDMI -> 2
        OutputKind.SPEAKER -> 3
        OutputKind.OTHER -> null
    }

    private fun typeName(type: Int): String = TYPE_NAMES[type] ?: "type-$type"

    private val TYPE_NAMES = mapOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "speaker",
        AudioDeviceInfo.TYPE_WIRED_HEADSET to "wired-headset",
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "wired-headphones",
        AudioDeviceInfo.TYPE_LINE_ANALOG to "line-analog",
        AudioDeviceInfo.TYPE_LINE_DIGITAL to "line-digital",
        AudioDeviceInfo.TYPE_AUX_LINE to "aux-line",
        AudioDeviceInfo.TYPE_DOCK to "dock",
        AudioDeviceInfo.TYPE_USB_HEADSET to "usb-headset",
        AudioDeviceInfo.TYPE_USB_DEVICE to "usb-device",
        AudioDeviceInfo.TYPE_USB_ACCESSORY to "usb-accessory",
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "bluetooth-a2dp",
        AudioDeviceInfo.TYPE_BLE_HEADSET to "ble-headset",
        AudioDeviceInfo.TYPE_BLE_SPEAKER to "ble-speaker",
        AudioDeviceInfo.TYPE_BLE_BROADCAST to "ble-broadcast",
        AudioDeviceInfo.TYPE_HEARING_AID to "hearing-aid",
        AudioDeviceInfo.TYPE_HDMI to "hdmi",
        AudioDeviceInfo.TYPE_HDMI_ARC to "hdmi-arc",
        AudioDeviceInfo.TYPE_HDMI_EARC to "hdmi-earc",
    )
}
