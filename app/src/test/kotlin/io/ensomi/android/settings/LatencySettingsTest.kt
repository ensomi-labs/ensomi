package io.ensomi.android.settings

import android.media.AudioDeviceInfo
import androidx.datastore.core.DataStoreFactory
import io.ensomi.android.play.InputSource
import io.ensomi.android.play.PlayTiming
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The latency slots in `settings.json` and the move from a 0.1.x file (latency.md, Storage and migration). */
class LatencySettingsTest {
    private val speaker = OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel 9")
    private val earpiece = OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Pixel 9")
    private val wired = OutputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Pixel 9")
    private val buds = OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Galaxy Buds2 ")
    private val otherBuds = OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "WH-1000XM4")
    private val leBuds = OutputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET, "Galaxy Buds2")

    private val speakerKey = OutputRoutes.key(speaker)
    private val wiredKey = OutputRoutes.key(wired)
    private val budsKey = OutputRoutes.key(otherBuds)
    private val usbKey = OutputRoutes.key(OutputDevice(AudioDeviceInfo.TYPE_USB_HEADSET, "Desk DAC"))

    /** As the screens name outputs: the speaker and the jack by kind, Bluetooth and USB by product. */
    private fun nameOf(key: String): String = when (OutputRoutes.device(key)?.let { OutputRoutes.kind(it.type) }) {
        OutputKind.SPEAKER -> "Phone speaker"
        OutputKind.WIRED -> "Wired headphones"
        OutputKind.BLUETOOTH, OutputKind.USB -> OutputRoutes.device(key)!!.productName
        else -> "Audio output"
    }

    private fun read(file: String): StoredSettings = runBlocking { StoredSettingsSerializer.readFrom(file.byteInputStream()) }

    private fun write(stored: StoredSettings): String =
        ByteArrayOutputStream().also { runBlocking { StoredSettingsSerializer.writeTo(stored, it) } }.toString()

    /** A 0.1.3 file, as that build writes it, read and moved to the slots with [current] the output in use. */
    private fun migrate(file: String, current: String? = speakerKey): PlaySettings =
        read(file).migrated(current, ::nameOf).toPlaySettings()

    private fun file013(offsets: String, visualMs: Int) =
        """{"scrollSpeed":31.5,"judge":"A","musicVolume":0.35,"audioOffsets":{$offsets},"visualOffsetMs":$visualMs,""" +
            """"orientation":"PORTRAIT","landscapeLean":22,"portraitLean":10,"landscapeWidth":0.98,"portraitWidth":null,""" +
            """"keys":{"lanes":["Z","X","PERIOD","SLASH"],"pause":"ESCAPE"}}"""

    @Test
    fun anOutputIsKeyedByItsTypeAndProductName() {
        assertEquals("speaker/Pixel 9", OutputRoutes.key(speaker))
        assertEquals("wired-headphones/Pixel 9", OutputRoutes.key(wired))
        assertEquals("bluetooth-a2dp/Galaxy Buds2", OutputRoutes.key(buds))
        assertNotEquals(OutputRoutes.key(buds), OutputRoutes.key(otherBuds))
        assertNotEquals(OutputRoutes.key(buds), OutputRoutes.key(leBuds))
        assertEquals("type-999/x", OutputRoutes.key(OutputDevice(999, "x")))
    }

    @Test
    fun aKeyReadsBackAsItsDeviceForTheSlotsName() {
        assertEquals(speaker, OutputRoutes.device(speakerKey))
        assertEquals(OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Galaxy Buds2"), OutputRoutes.device(OutputRoutes.key(buds)))
        assertEquals(OutputDevice(999, "x"), OutputRoutes.device("type-999/x"))
        assertEquals(OutputDevice(AudioDeviceInfo.TYPE_USB_DEVICE, "A/B"), OutputRoutes.device("usb-device/A/B"))
        assertNull(OutputRoutes.device("nonsense"))
        assertNull(OutputRoutes.device("hifi/x"))
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
    fun theDefaultsAreThreeSlotsOfEachKindAtZeroAudioSlotZeroAndTouchOnZeroKeysOnOne() {
        for (settings in listOf(PlaySettings(), StoredSettings().toPlaySettings())) {
            assertEquals(List(3) { LatencySlot("", 0) }, settings.audioLatency.slots)
            assertEquals(0, settings.audioLatency.active)
            assertEquals(List(3) { LatencySlot("", 0) }, settings.inputLatency.slots)
            assertEquals(0, settings.inputLatency.touch)
            assertEquals(1, settings.inputLatency.keyboard)
            assertEquals(0, settings.audioLatencyMs)
        }
        val written = write(StoredSettings().with(PlaySettings()))
        val slots = """[{"name":"","ms":0},{"name":"","ms":0},{"name":"","ms":0}]"""
        assertTrue(written, """"audioLatency":{"active":0,"slots":$slots}""" in written)
        assertTrue(written, """"inputLatency":{"slots":$slots,"touch":0,"keyboard":1}""" in written)
    }

    @Test
    fun aFileWithoutTheObjectsReadsTheDefaultsAndIsMovedOnce() {
        val stored = read("""{"judge":"B"}""")
        assertTrue(stored.needsLatencySlots)
        assertEquals(AudioLatency(), stored.toPlaySettings().audioLatency)
        assertEquals(InputLatency(), stored.toPlaySettings().inputLatency)
        val moved = stored.migrated(speakerKey, ::nameOf)
        assertFalse(moved.needsLatencySlots)
        assertEquals(JudgeDifficulty.B, moved.judge)
        assertEquals("Moved once: a file with slots stays as it is", moved, read(write(moved)).migrated(budsKey, ::nameOf))
        assertFalse(runBlocking { LatencySlotsMigration({ budsKey }, ::nameOf).shouldMigrate(read(write(moved))) })
    }

    @Test
    fun namesAreOptionalTrimmedAndCut() {
        val file = """{"audioLatency":{"active":1,"slots":[{"name":"  WH-1000XM4  ","ms":120},""" +
            """{"ms":-4},{"name":"Twenty-three characters and more","ms":0}]}}"""
        val slots = read(file).toPlaySettings().audioLatency.slots
        assertEquals(LatencySlot("WH-1000XM4", 120), slots[0])
        assertEquals("A slot without a name has none", LatencySlot("", -4), slots[1])
        assertEquals("Cut to 24 characters, and trimmed again", "Twenty-three characters", slots[2].name)
        assertEquals(24, Latency.name("x".repeat(30)).length)
        // Characters, not UTF-16 units: a name of emoji is cut whole.
        val emoji = Latency.name("🎧".repeat(30))
        assertEquals(24, emoji.codePointCount(0, emoji.length))
        assertEquals("", Latency.name("   "))
    }

    @Test
    fun valuesAreClampedListsMadeThreeAndAnIndexOutsideReadsItsDefault() {
        val file = """{"audioLatency":{"active":5,"slots":[{"ms":999},{"ms":-999}]},""" +
            """"inputLatency":{"slots":[{"ms":8},{"ms":30},{"ms":0},{"ms":77}],"touch":-1,"keyboard":7}}"""
        val settings = read(file).toPlaySettings()
        assertEquals(listOf(300, -300, 0), settings.audioLatency.slots.map { it.ms })
        assertEquals(0, settings.audioLatency.active)
        assertEquals(listOf(8, 30, 0), settings.inputLatency.slots.map { it.ms })
        assertEquals(0, settings.inputLatency.touch)
        assertEquals(1, settings.inputLatency.keyboard)
        assertEquals(300, PlaySettings().withAudioLatencyMs(1_000).audioLatencyMs)
        assertEquals(-300, InputLatency().withMs(2, -1_000).slots[2].ms)
    }

    @Test
    fun aRoundTripKeepsEverything() {
        val settings = PlaySettings(
            audioLatency = AudioLatency(listOf(LatencySlot("Speaker", -2), LatencySlot("", 0), LatencySlot("WH-1000XM4", 142)), 2),
            inputLatency = InputLatency(listOf(LatencySlot("", 8), LatencySlot("Keys", 30), LatencySlot("", -5)), 2, 2),
        )
        val stored = StoredSettings().with(settings)
        assertEquals(settings, read(write(stored)).toPlaySettings())
        assertEquals(142, settings.audioLatencyMs)
        assertEquals(-5, settings.inputLatency.touchMs)
        assertEquals("Both methods on one slot share its value", -5, settings.inputLatency.keyboardMs)
    }

    @Test
    fun aFileWithOneOutputGivesItSlotZero() {
        val settings = migrate(file013(""""$budsKey":-120""", 0), current = budsKey)
        assertEquals(listOf(LatencySlot("WH-1000XM4", 120), LatencySlot(), LatencySlot()), settings.audioLatency.slots)
        assertEquals(0, settings.audioLatency.active)
        assertEquals(InputLatency(), settings.inputLatency)
        // Every other setting is as the file had it.
        assertEquals(31.5, settings.scrollSpeed, 0.0)
        assertEquals(JudgeDifficulty.A, settings.judge)
        assertEquals(PlayOrientation.PORTRAIT, settings.orientation)
        assertEquals(0.98f, settings.landscapeWidth)
        assertEquals(KeyMap.DEFAULT, settings.keys)
    }

    /**
     * Each carried output, with its slot active, has the picture and the touch judging it had: the picture's difference
     * from the song time is `audioOffset + visualOffset` before and `−A` after, touch's is `audioOffset` before and
     * `−A − I[touch]` after.
     */
    @Test
    fun severalOutputsAndAVisualOffsetPlayAsBeforeOnEachOutput() {
        val old = linkedMapOf(speakerKey to -7, wiredKey to -30, budsKey to -120)
        val visualMs = 9
        val file = file013(old.entries.joinToString(",") { (key, ms) -> """"$key":$ms""" }, visualMs)
        val settings = migrate(file, current = wiredKey)
        assertEquals(listOf("Phone speaker", "Wired headphones", "WH-1000XM4"), settings.audioLatency.slots.map { it.name })
        assertEquals(listOf(-2, 21, 111), settings.audioLatency.slots.map { it.ms })
        assertEquals("The output in use is active", 1, settings.audioLatency.active)
        assertEquals(listOf(LatencySlot("", 9), LatencySlot(), LatencySlot()), settings.inputLatency.slots)
        assertEquals(0, settings.inputLatency.touch)
        assertEquals(1, settings.inputLatency.keyboard)
        old.values.forEachIndexed { slot, audioOffsetMs -> assertPlaysAsBefore(settings, slot, audioOffsetMs, visualMs) }
    }

    /**
     * The bad case of the first rule: a tester with only a Bluetooth pair at −120 ms, updating on the speaker, played the
     * speaker at +120 ms. The output in use takes a slot of its own at −V and is active, so it plays as before, and the
     * pair keeps its slot.
     */
    @Test
    fun theOutputInUseWithNoStoredOffsetTakesASlotOfItsOwnAndIsActive() {
        for (visualMs in listOf(0, 9)) {
            val settings = migrate(file013(""""$budsKey":-120""", visualMs), current = speakerKey)
            assertEquals(listOf("WH-1000XM4", "Phone speaker", ""), settings.audioLatency.slots.map { it.name })
            assertEquals(listOf(120 - visualMs, -visualMs, 0), settings.audioLatency.slots.map { it.ms })
            assertEquals("The output in use is active", 1, settings.audioLatency.active)
            assertEquals(visualMs, settings.inputLatency.touchMs)
            assertPlaysAsBefore(settings, 1, audioOffsetMs = 0, visualMs = visualMs)
            assertPlaysAsBefore(settings, 0, audioOffsetMs = -120, visualMs = visualMs)
        }
        // With three stored outputs, the output in use takes the third slot and the third stored output is dropped.
        val settings = migrate(file013(""""$speakerKey":-7,"$wiredKey":-30,"$budsKey":-120""", 0), current = usbKey)
        assertEquals(listOf("Phone speaker", "Wired headphones", "Desk DAC"), settings.audioLatency.slots.map { it.name })
        assertEquals(listOf(7, 30, 0), settings.audioLatency.slots.map { it.ms })
        assertEquals(2, settings.audioLatency.active)
    }

    @Test
    fun outputsPastTheThirdAreDroppedButNeverTheOutputInUse() {
        val file = file013(""""$speakerKey":-7,"$wiredKey":-30,"$budsKey":-120,"$usbKey":-15""", 9)
        val first = migrate(file, current = speakerKey)
        assertEquals(listOf("Phone speaker", "Wired headphones", "WH-1000XM4"), first.audioLatency.slots.map { it.name })
        assertEquals(listOf(-2, 21, 111), first.audioLatency.slots.map { it.ms })
        assertEquals(0, first.audioLatency.active)
        // The output in use as the fourth stored output takes the third slot, in the file's order, and is active.
        val fourth = migrate(file, current = usbKey)
        assertEquals(listOf("Phone speaker", "Wired headphones", "Desk DAC"), fourth.audioLatency.slots.map { it.name })
        assertEquals(listOf(-2, 21, 6), fourth.audioLatency.slots.map { it.ms })
        assertEquals(2, fourth.audioLatency.active)
        assertPlaysAsBefore(fourth, 2, audioOffsetMs = -15, visualMs = 9)
    }

    @Test
    fun withNoOutputKnownTheFirstThreeAreCarriedAndSlotZeroIsActive() {
        val file = file013(""""$speakerKey":-7,"$wiredKey":-30,"$budsKey":-120,"$usbKey":-15""", 0)
        val settings = migrate(file, current = null)
        assertEquals(listOf("Phone speaker", "Wired headphones", "WH-1000XM4"), settings.audioLatency.slots.map { it.name })
        assertEquals(listOf(7, 30, 120), settings.audioLatency.slots.map { it.ms })
        assertEquals(0, settings.audioLatency.active)
        val visualOnly = migrate(file013("", 9), current = null)
        assertEquals(listOf(LatencySlot("", -9), LatencySlot(), LatencySlot()), visualOnly.audioLatency.slots)
        assertEquals(0, visualOnly.audioLatency.active)
    }

    @Test
    fun aVisualOffsetWithNoStoredOutputGoesToTheOutputInUseAndTouch() {
        val settings = migrate(file013("", 9))
        assertEquals(listOf(LatencySlot("Phone speaker", -9), LatencySlot(), LatencySlot()), settings.audioLatency.slots)
        assertEquals(0, settings.audioLatency.active)
        assertEquals(9, settings.inputLatency.touchMs)
        val timing = PlayTiming.game(settings)
        assertEquals("The picture as before, at the visual offset", 9.0, timing.renderMs(0.0), 1e-9)
        assertEquals("Touch as before, at no audio offset", 0.0, timing.inputMs(0.0, InputSource.TOUCH), 1e-9)
    }

    /** Nothing to carry, a first start among them: no slot is named after the output in use. */
    @Test
    fun noOffsetsGiveTheDefaults() {
        for (file in listOf(file013("", 0), """{"scrollSpeed":31.5}""", "{}")) {
            for (current in listOf(speakerKey, budsKey, null)) {
                val settings = migrate(file, current)
                assertEquals(file, AudioLatency(), settings.audioLatency)
                assertEquals(file, InputLatency(), settings.inputLatency)
            }
        }
    }

    @Test
    fun theActiveSlotIsTheOutputInUseOrZero() {
        val file = file013(""""$speakerKey":-7,"$budsKey":-120""", 0)
        assertEquals(1, migrate(file, current = budsKey).audioLatency.active)
        assertEquals(0, migrate(file, current = speakerKey).audioLatency.active)
        val wiredInUse = migrate(file, current = wiredKey).audioLatency
        assertEquals("An output in use without an offset takes the next slot", 2, wiredInUse.active)
        assertEquals(LatencySlot("Wired headphones", 0), wiredInUse.slots[2])
        assertEquals(0, migrate(file, current = null).audioLatency.active)
    }

    @Test
    fun valuesStayInTheRangeAndALongProductNameIsCut() {
        val longKey = OutputRoutes.key(OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Headphones With A Very Long Product Name"))
        val settings = migrate(file013(""""$longKey":-300,"$speakerKey":250""", -200))
        assertEquals("Headphones With A Very L", settings.audioLatency.slots[0].name)
        assertEquals("−(−300 − 200) is past the range", 300, settings.audioLatency.slots[0].ms)
        assertEquals(-50, settings.audioLatency.slots[1].ms)
        assertEquals(-200, settings.inputLatency.touchMs)
        // A value the old build would have read within its range is read so here.
        assertEquals(-300, migrate(file013(""""$speakerKey":400""", 0)).audioLatency.slots[0].ms)
    }

    @Test
    fun theOldFieldsAreReadForTheMoveOnlyAndNotWrittenAgain() {
        val stored = read(file013(""""$speakerKey":-7""", 9))
        assertEquals(mapOf(speakerKey to -7), stored.audioOffsets)
        assertEquals(9, stored.visualOffsetMs)
        val written = write(stored.migrated(speakerKey, ::nameOf))
        assertFalse(written, "audioOffsets" in written)
        assertFalse(written, "visualOffsetMs" in written)
        assertTrue(written, """"audioLatency":{"active":0,"slots":[{"name":"Phone speaker","ms":-2}""" in written)
        // A change of a setting writes no old field either.
        assertFalse(write(StoredSettings().with(PlaySettings())).contains("Offset"))
    }

    @Test
    fun aFileFromTheBuildThatOfferedVibrationKeepsEveryOtherValue() {
        // As that build wrote it. A read that failed would be corruption, and the store would reset every setting.
        val file = """{"scrollSpeed":31.5,"judge":"A","musicVolume":0.35,"audioOffsets":{"speaker/Pixel 9":-7},""" +
            """"visualOffsetMs":9,"vibrateOnTouch":true,"orientation":"PORTRAIT","landscapeLean":22,"portraitLean":10}"""
        val settings = migrate(file)
        assertEquals(PlaySettings(31.5, JudgeDifficulty.A, 0.35f, PlayOrientation.PORTRAIT, 22, 10), settings.copy(
            audioLatency = AudioLatency(),
            inputLatency = InputLatency(),
        ))
        assertEquals(-2, settings.audioLatencyMs)
        assertEquals(9, settings.inputLatency.touchMs)
    }

    /**
     * The store over DataStore: a 0.1.3 file is moved on its first read and written back at once, a change is written
     * behind, and the output in use chooses nothing.
     */
    @Test
    fun theStoreMovesAnOldFileOnceAndTheOutputChoosesNothing() {
        val directory = Files.createTempDirectory("settings").toFile()
        val file = File(directory, "settings.json")
        file.writeText(file013(""""$speakerKey":-7,"$budsKey":-120""", 9))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val route = MutableStateFlow(OutputRoute(budsKey, "WH-1000XM4"))
            val logged = mutableListOf<String>()
            val dataStore = DataStoreFactory.create(
                StoredSettingsSerializer,
                migrations = listOf(LatencySlotsMigration({ route.value.key }, ::nameOf, { logged += it })),
                scope = scope,
                produceFile = { file },
            )
            val store = DataStorePlaySettingsStore(dataStore, route, scope)
            waitFor { store.settings.value.audioLatency.slots[1].ms == 111 }
            assertEquals(1, store.settings.value.audioLatency.active)
            assertEquals(9, store.settings.value.inputLatency.touchMs)
            waitFor { "audioLatency" in file.readText() }
            assertFalse(file.readText(), "audioOffsets" in file.readText())
            assertEquals(1, logged.size)
            assertTrue(logged.single(), "slot 1 active; the output in use had a stored offset" in logged.single())
            assertFalse("Names are never logged", "WH-1000XM4" in logged.single() || "Phone speaker" in logged.single())

            store.update { it.withAudioLatencyMs(130).copy(landscapeLean = 30) }
            assertEquals(130, store.settings.value.audioLatencyMs)
            route.value = OutputRoute(speakerKey, "Phone speaker")
            Thread.sleep(50)
            assertEquals("Another output changes no setting", 130, store.settings.value.audioLatencyMs)
            assertEquals(1, store.settings.value.audioLatency.active)
            waitFor { runCatching { read(file.readText()) }.getOrNull()?.toPlaySettings()?.audioLatencyMs == 130 }
            val disk = read(file.readText())
            assertFalse(disk.needsLatencySlots)
            assertEquals(30, disk.landscapeLean)
            assertEquals(LatencySlot("WH-1000XM4", 130), disk.toPlaySettings().audioLatency.slots[1])
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    /**
     * [settings] with [slot] active have the picture and the touch judging an output had with [audioOffsetMs] and
     * [visualMs]: the picture's difference from the song time is `audioOffset + visualOffset` before and `−A` after,
     * touch's is `audioOffset` before and `−A − I[touch]` after.
     */
    private fun assertPlaysAsBefore(settings: PlaySettings, slot: Int, audioOffsetMs: Int, visualMs: Int) {
        val timing = PlayTiming.game(settings.copy(audioLatency = settings.audioLatency.copy(active = slot)))
        val songMs = 12_345.0
        assertEquals("Picture, slot $slot", (audioOffsetMs + visualMs).toDouble(), timing.renderMs(songMs) - songMs, 1e-9)
        assertEquals("Touch, slot $slot", audioOffsetMs.toDouble(), timing.inputMs(songMs, InputSource.TOUCH) - songMs, 1e-9)
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000
        while (!condition()) {
            if (System.nanoTime() > until) throw AssertionError("Timed out")
            Thread.sleep(10)
        }
    }
}
