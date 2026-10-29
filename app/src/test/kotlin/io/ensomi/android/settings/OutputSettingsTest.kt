package io.ensomi.android.settings

import android.media.AudioDeviceInfo
import androidx.datastore.core.DataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputSettingsTest {
    private val speaker = OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel 9")
    private val earpiece = OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Pixel 9")
    private val wired = OutputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Pixel 9")
    private val buds = OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Galaxy Buds2 ")
    private val otherBuds = OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "WH-1000XM4")
    private val leBuds = OutputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET, "Galaxy Buds2")

    @Test
    fun anOutputIsKeyedByItsTypeAndProductName() {
        assertEquals("speaker/Pixel 9", OutputRoutes.key(speaker))
        assertEquals("wired-headphones/Pixel 9", OutputRoutes.key(wired))
        assertEquals("bluetooth-a2dp/Galaxy Buds2", OutputRoutes.key(buds))
        assertNotEquals(OutputRoutes.key(buds), OutputRoutes.key(otherBuds))
        // The same headphones over LE Audio have another latency, so another offset.
        assertNotEquals(OutputRoutes.key(buds), OutputRoutes.key(leBuds))
        assertEquals("type-999/x", OutputRoutes.key(OutputDevice(999, "x")))
    }

    @Test
    fun musicGoesToBluetoothThenWiredThenTheSpeaker() {
        assertEquals(buds, OutputRoutes.current(listOf(speaker, earpiece, wired, buds)))
        assertEquals(wired, OutputRoutes.current(listOf(earpiece, speaker, wired)))
        assertEquals(speaker, OutputRoutes.current(listOf(earpiece, speaker)))
        assertNull(OutputRoutes.current(listOf(earpiece)))
        assertEquals(OutputKind.BLUETOOTH, OutputRoutes.kind(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertEquals(OutputKind.USB, OutputRoutes.kind(AudioDeviceInfo.TYPE_USB_HEADSET))
    }

    @Test
    fun eachOutputKeepsItsOwnAudioOffsetAndTheRestIsShared() {
        val a = OutputRoutes.key(speaker)
        val b = OutputRoutes.key(buds)
        var stored = StoredSettings()
        stored = stored.with(stored.forOutput(a).copy(audioOffsetMs = -12, visualOffsetMs = 4), a)
        assertEquals(-12, stored.forOutput(a).audioOffsetMs)
        assertEquals(0, stored.forOutput(b).audioOffsetMs)
        assertEquals(4, stored.forOutput(b).visualOffsetMs)
        stored = stored.with(stored.forOutput(b).copy(audioOffsetMs = 85), b)
        assertEquals(-12, stored.forOutput(a).audioOffsetMs)
        assertEquals(85, stored.forOutput(b).audioOffsetMs)
        // A change of something else adds no offset for an output that has none.
        val c = OutputRoutes.key(wired)
        assertEquals(setOf(a, b), stored.with(stored.forOutput(c).copy(judge = JudgeDifficulty.E), c).audioOffsets.keys)
    }

    @Test
    fun theFileReadsBackAndToleratesOtherVersions() {
        val stored = StoredSettings(
            scrollSpeed = 31.5,
            judge = JudgeDifficulty.A,
            musicVolume = 0.35f,
            audioOffsets = mapOf("speaker/Pixel 9" to -7),
            visualOffsetMs = 9,
            vibrateOnTouch = true,
            orientation = PlayOrientation.PORTRAIT,
            landscapeLean = 22,
            portraitLean = 10,
        )
        val text = StoredSettings.JSON.encodeToString(StoredSettings.serializer(), stored)
        assertEquals(stored, StoredSettings.JSON.decodeFromString(StoredSettings.serializer(), text))
        val newer = """{"judge":"Z","landscapeLean":90,"someday":true,"audioOffsets":{"k":999}}"""
        val read = StoredSettings.JSON.decodeFromString(StoredSettings.serializer(), newer).forOutput("k")
        assertEquals(JudgeDifficulty.C, read.judge)
        assertEquals(PlaySettings.LEANS.last, read.landscapeLean)
        assertEquals(PlaySettings.OFFSETS.last, read.audioOffsetMs)
    }

    @Test
    fun theStoreWritesBehindAndKeepsTheOffsetOfTheRouteInUse() {
        val directory = Files.createTempDirectory("settings").toFile()
        val file = File(directory, "settings.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = DataStoreFactory.create(StoredSettingsSerializer, scope = scope, produceFile = { file })
            val route = MutableStateFlow(OutputRoute(OutputRoutes.key(speaker), "Phone speaker"))
            val store = DataStorePlaySettingsStore(dataStore, route, scope)
            store.update { it.copy(audioOffsetMs = -20, landscapeLean = 30) }
            assertEquals(-20, store.settings.value.audioOffsetMs)
            route.value = OutputRoute(OutputRoutes.key(buds), "Galaxy Buds2")
            waitFor { store.settings.value.audioOffsetMs == 0 }
            store.update { it.copy(audioOffsetMs = 110) }
            assertEquals(30, store.settings.value.landscapeLean)
            waitFor {
                file.isFile && runCatching {
                    StoredSettings.JSON.decodeFromString(StoredSettings.serializer(), file.readText())
                }.getOrNull()?.audioOffsets == mapOf(OutputRoutes.key(speaker) to -20, OutputRoutes.key(buds) to 110)
            }
            route.value = OutputRoute(OutputRoutes.key(speaker), "Phone speaker")
            waitFor { store.settings.value.audioOffsetMs == -20 }
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000
        while (!condition()) {
            if (System.nanoTime() > until) throw AssertionError("Timed out")
            Thread.sleep(10)
        }
    }
}
