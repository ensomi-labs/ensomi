package io.ensomi.android.settings

import androidx.datastore.core.DataStoreFactory
import io.ensomi.android.play.InputSource
import io.ensomi.android.play.PlayTiming
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the calibration's controls do (latency.md, Timing calibration; O3): which slot each edits, the method choice,
 * that every change is kept at once with nothing to confirm, and that no order is asked for.
 */
class CalibrationEditsTest {
    private val touch = InputSource.TOUCH
    private val keys = InputSource.KEYBOARD

    /** Audio slot 1 active; touch on input slot 0, the keyboard on slot 1, as by default. */
    private val settings = PlaySettings(
        audioLatency = AudioLatency(listOf(LatencySlot("Buds", 142), LatencySlot("Phone speaker", -2), LatencySlot()), active = 1),
        inputLatency = InputLatency(listOf(LatencySlot(ms = 8), LatencySlot("K380", 30), LatencySlot()), touch = 0, keyboard = 1),
    )

    @Test
    fun keyboardIsChosenFirstOnlyWhenOneIsConnected() {
        assertEquals(touch, CalibrationEdits.firstMethod(keyboardConnected = false))
        assertEquals(keys, CalibrationEdits.firstMethod(keyboardConnected = true))
    }

    @Test
    fun theAudioValueIsTheActiveSlotsAndChoosingASlotHereMakesItActive() {
        val stepped = CalibrationEdits.audioStep(settings, 5)
        assertEquals(listOf(142, 3, 0), stepped.audioLatency.slots.map { it.ms })
        val chosen = CalibrationEdits.audioSlot(stepped, 0)
        assertEquals(0, chosen.audioLatency.active)
        assertEquals("Choosing moves no value", stepped.audioLatency.slots, chosen.audioLatency.slots)
        assertEquals(150, CalibrationEdits.audioMs(chosen, 150).audioLatencyMs)
        assertEquals("Steps reach the range's ends and stop", 300, CalibrationEdits.audioStep(CalibrationEdits.audioMs(chosen, 299), 5).audioLatencyMs)
        assertEquals(-300, CalibrationEdits.audioMs(chosen, -1_000).audioLatencyMs)
    }

    @Test
    fun theInputValueIsTheChosenMethodsSlot() {
        val keyed = CalibrationEdits.inputStep(settings, keys, 12)
        assertEquals("The keyboard's slot 1 only", listOf(8, 42, 0), keyed.inputLatency.slots.map { it.ms })
        val touched = CalibrationEdits.inputMs(keyed, touch, -6)
        assertEquals("Touch's slot 0 only", listOf(-6, 42, 0), touched.inputLatency.slots.map { it.ms })
        assertEquals(42, CalibrationEdits.inputMs(touched, keys))
        assertEquals("No input value touches the audio slots", settings.audioLatency, touched.audioLatency)
    }

    @Test
    fun aMethodsSlotMenuChoosesItsSlotAndMethodsSharingASlotChangeTogether() {
        val moved = CalibrationEdits.inputSlot(settings, keys, 2)
        assertEquals(2, moved.inputLatency.keyboard)
        assertEquals("Touch stays", 0, moved.inputLatency.touch)
        assertEquals(0, CalibrationEdits.inputMs(moved, keys))
        val shared = CalibrationEdits.inputSlot(settings, keys, 0)
        val stepped = CalibrationEdits.inputStep(shared, keys, 4)
        assertEquals(12, CalibrationEdits.inputMs(stepped, touch))
        assertEquals(12, CalibrationEdits.inputMs(stepped, keys))
    }

    /** The method chosen picks the slot edited and the readings shown, and never how a press is judged. */
    @Test
    fun everyPressIsJudgedWithItsOwnMethodsSlotWhicheverMethodIsChosen() {
        val timing = PlayTiming.game(settings)
        assertEquals(1_000.0 - (-2) - 8, timing.inputMs(1_000.0, touch), 1e-9)
        assertEquals(1_000.0 - (-2) - 30, timing.inputMs(1_000.0, keys), 1e-9)
        assertEquals("A + I of touch's readings", -2.0 + 8, CalibrationEdits.latencyMs(settings, touch), 1e-9)
        assertEquals("and of the keyboard's", -2.0 + 30, CalibrationEdits.latencyMs(settings, keys), 1e-9)
    }

    @Test
    fun noOrderIsAskedForTheValuesComeOutTheSameEitherWay() {
        val audioFirst = CalibrationEdits.inputStep(CalibrationEdits.audioStep(settings, 120), touch, -9)
        val inputFirst = CalibrationEdits.audioStep(CalibrationEdits.inputStep(settings, touch, -9), 120)
        assertEquals(audioFirst, inputFirst)
        assertEquals(118, audioFirst.audioLatencyMs)
        assertEquals(-1, CalibrationEdits.inputMs(audioFirst, touch))
    }

    /** Each change goes to `settings.json` through the app's store as it is made: no Use, no Done, nothing to confirm. */
    @Test
    fun everyChangeIsKeptAtOnceInTheSlotItEdits() {
        val directory = Files.createTempDirectory("calibration").toFile()
        val file = File(directory, "settings.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = DataStoreFactory.create(StoredSettingsSerializer, scope = scope, produceFile = { file })
            val store = DataStorePlaySettingsStore(dataStore, MutableStateFlow(OutputRoute("speaker", "Phone speaker")), scope)
            store.update { settings }
            // The input value first, then the audio value: the keyboard's slot, a slot chosen, its value; touch's.
            repeat(30) { store.update { CalibrationEdits.inputStep(it, keys, 1) } }
            store.update { CalibrationEdits.audioSlot(it, 2) }
            store.update { CalibrationEdits.audioMs(it, 142) }
            store.update { CalibrationEdits.inputMs(it, touch, -6) }
            store.update { it.copy(inputLatency = it.inputLatency.withName(2, "Desk")) }
            val expected = store.settings.value
            waitFor {
                val disk = runCatching { read(file) }.getOrNull()
                disk != null && disk.audioLatency == expected.audioLatency && disk.inputLatency == expected.inputLatency
            }
            val disk = read(file)
            assertEquals(2, disk.audioLatency.active)
            assertEquals(listOf(142, -2, 142), disk.audioLatency.slots.map { it.ms })
            assertEquals(listOf(-6, 60, 0), disk.inputLatency.slots.map { it.ms })
            assertEquals("Desk", disk.inputLatency.slots[2].name)
            assertTrue("The other settings stay", disk.keys == settings.keys && disk.judge == settings.judge)
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun aHeldStepReaches150MsInAboutThreeSecondsThroughEveryValue() {
        val waits = (1..149).map(StepRepeat::waitMs)
        assertTrue("Each wait no longer than the last", waits.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(StepRepeat.FASTEST_WAIT_MS, waits.last())
        val toReach150 = StepRepeat.HOLD_MS + waits.sum()
        assertTrue("$toReach150 ms", toReach150 in 3_000L..3_500L)
    }

    private fun read(file: File): PlaySettings =
        runBlocking { file.inputStream().use { StoredSettingsSerializer.readFrom(it) } }.toPlaySettings()

    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000
        while (!condition()) {
            if (System.nanoTime() > until) throw AssertionError("Timed out")
            Thread.sleep(10)
        }
    }
}
