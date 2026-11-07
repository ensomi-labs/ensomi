package io.ensomi.android.settings

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import io.ensomi.android.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The settings kept with Jetpack DataStore in `settings.json` under the app's files. [route] is the output play would use
 * now, which the screens name; it chooses no setting.
 *
 * The screens read and change the settings in memory at once, so a slider moves the preview without waiting for the
 * disk; the newest value is written behind them, skipping values a newer one has replaced. A change made before the
 * file has been read is applied to what the file holds.
 */
class DataStorePlaySettingsStore(
    private val dataStore: DataStore<StoredSettings>,
    override val route: StateFlow<OutputRoute>,
    scope: CoroutineScope,
) : PlaySettingsStore {
    private val lock = Any()
    private var stored = StoredSettings()
    private var loaded = false
    private val early = mutableListOf<(StoredSettings) -> StoredSettings>()
    private val state = MutableStateFlow(stored.toPlaySettings())
    private val toWrite = MutableStateFlow<StoredSettings?>(null)

    override val settings: StateFlow<PlaySettings> = state.asStateFlow()

    init {
        scope.launch {
            val first = try {
                dataStore.data.first()
            } catch (e: IOException) {
                Log.w(TAG, "Could not read the settings; using the defaults", e)
                StoredSettings()
            }
            synchronized(lock) {
                stored = early.fold(first) { value, change -> change(value) }
                loaded = true
                if (early.isNotEmpty()) toWrite.value = stored
                early.clear()
                state.value = stored.toPlaySettings()
            }
            // A StateFlow hands a slow collector only its newest value.
            toWrite.filterNotNull().collect { value ->
                try {
                    dataStore.updateData { value }
                } catch (e: IOException) {
                    Log.w(TAG, "Could not write the settings", e)
                }
            }
        }
    }

    override fun update(change: (PlaySettings) -> PlaySettings) {
        val apply = { value: StoredSettings -> value.with(change(value.toPlaySettings())) }
        synchronized(lock) {
            stored = apply(stored)
            if (loaded) toWrite.value = stored else early += apply
            state.value = stored.toPlaySettings()
        }
    }

    companion object {
        private const val TAG = "EnsomiSettings"

        /**
         * The one DataStore of the settings file; DataStore allows one per file in a process. A file from before the
         * latency slots is moved to them on its first read, with [outputs] naming the outputs and the one in use.
         */
        fun dataStore(context: Context, scope: CoroutineScope, outputs: SystemOutputRoute): DataStore<StoredSettings> =
            DataStoreFactory.create(
                serializer = StoredSettingsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { StoredSettings() },
                migrations = listOf(
                    LatencySlotsMigration(
                        currentKey = { outputs.route.value.key },
                        nameOf = outputs::nameOf,
                        log = { Log.i(TAG, it) },
                    ),
                ),
                scope = scope,
                produceFile = { File(context.filesDir, "settings.json") },
            )
    }
}

/**
 * The output play would use now. A game's track says which device it plays on ([playingOn]), and that is the output
 * from then on; until a game has played, and after devices come or go, it is the device Android would pick by default
 * among those it reports ([OutputRoutes.current]).
 */
class SystemOutputRoute(context: Context) : PlayOutputs {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val state = MutableStateFlow(read())
    val route: StateFlow<OutputRoute> = state.asStateFlow()

    override fun routeOf(device: AudioDeviceInfo?): OutputRoute {
        if (device == null) return read()
        val output = OutputDevice(device.type, device.productName?.toString().orEmpty())
        return OutputRoute(OutputRoutes.key(output), name(output))
    }

    override fun playingOn(device: AudioDeviceInfo?) {
        if (device != null) state.value = routeOf(device)
    }

    /** The name the screens give the output keyed [key] ([OutputRoutes.key]): what a migrated audio slot is named. */
    fun nameOf(key: String): String =
        OutputRoutes.device(key)?.let(::name) ?: appContext.getString(R.string.output_other)

    init {
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun refresh() {
        state.value = read()
    }

    private fun read(): OutputRoute {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { OutputDevice(it.type, it.productName?.toString().orEmpty()) }
        val device = OutputRoutes.current(devices) ?: OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "")
        return OutputRoute(OutputRoutes.key(device), name(device))
    }

    private fun name(device: OutputDevice): String {
        val product = device.productName.trim()
        val kind = OutputRoutes.kind(device.type)
        // The product name of a speaker or a headphone jack is the phone's own model name.
        if (product.isNotEmpty() && kind in NAMED_BY_PRODUCT) return product
        return appContext.getString(
            when (kind) {
                OutputKind.SPEAKER -> R.string.output_speaker
                OutputKind.WIRED -> R.string.output_wired
                OutputKind.USB -> R.string.output_usb
                OutputKind.BLUETOOTH -> R.string.output_bluetooth
                OutputKind.HEARING_AID -> R.string.output_hearing_aid
                OutputKind.HDMI -> R.string.output_hdmi
                OutputKind.OTHER -> R.string.output_other
            },
        )
    }

    private companion object {
        val NAMED_BY_PRODUCT = setOf(OutputKind.BLUETOOTH, OutputKind.HEARING_AID, OutputKind.USB)
    }
}
