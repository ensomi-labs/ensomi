package io.ensomi.android.recognition

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ensomi.android.play.AmbientGame
import io.ensomi.android.play.PlayChart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real serialized controller on Android, without capture, playback or a live song. */
@RunWith(AndroidJUnit4::class)
class RecognitionControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storage = SessionStorage(context)
    private val controllers = mutableListOf<RecognitionController>()
    private fun now() = SessionNow(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())

    // Reflection keeps test fixture injection out of the shipping controller API.
    private fun field(name: String) = RecognitionController::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private suspend fun serialized(controller: RecognitionController, action: () -> Unit = {}) {
        val done = CompletableDeferred<Unit>()
        val queue = field("actions").get(controller) as Channel<suspend () -> Unit>
        queue.send { try { action(); done.complete(Unit) } catch (error: Throwable) { done.completeExceptionally(error) } }
        withTimeout(10_000) { done.await() }
    }
    private suspend fun create(): RecognitionController {
        val constructor = RecognitionController::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        return constructor.newInstance(context).also { controllers += it; serialized(it) }
    }
    private fun saved(waitAgeMs: Long = 0): SessionState {
        val time = now()
        return SessionState("controller-fixture", time.nanos - 60_000_000_000, time.wallMs - 60_000, true,
            song = SongIdentity(999999881, "Paper Lantern Test", "Cloud Quartet"),
            shareNanos = time.nanos - waitAgeMs * 1_000_000,
            audioWaitSinceNanos = time.nanos - waitAgeMs * 1_000_000)
    }
    @After fun cleanup() = runBlocking {
        for (controller in controllers) {
            controller.stop()
            serialized(controller)
            (field("scope").get(controller) as CoroutineScope).cancel()
        }
        storage.clearSnapshot()
    }

    @Test fun unrelatedSharesPreserveTheSessionAndTheJoinedGame() = runBlocking {
        storage.clearSnapshot()
        val controller = create()
        val time = now()
        val machine = SessionMachine.start("live", true, time) { _, _ -> }.first
        machine.on(SessionEvent.ShareReceived("song", SharedSong(title = "Paper Lantern Test", artist = "Cloud Quartet"), null), time)
        val state = machine.state
        val game = AmbientGame(PlayChart("test", 1000, emptyList()), AmbientPlayClock(LockAnchor(0.0, time.nanos)), 0.0, 0.0)
        serialized(controller) {
            field("machine").set(controller, machine)
            @Suppress("UNCHECKED_CAST")
            (field("mutableSession").get(controller) as MutableStateFlow<SessionState?>).value = state
            @Suppress("UNCHECKED_CAST")
            (field("mutableGame").get(controller) as MutableStateFlow<AmbientGame?>).value = game
        }
        for (text in listOf("", "a stray paste", "https://music.163.com/playlist?id=42")) {
            controller.share(text, "test")
            serialized(controller)
            assertEquals(state, controller.session.value)
            assertSame(game, controller.game.value)
        }
        controller.showGuide()
        serialized(controller)
        assertEquals(state, controller.session.value)
        assertSame(game, controller.game.value)
    }

    @Test fun processRestorationWaitsForThePersonAndDoesNotOpenTheScreen() = runBlocking {
        val saved = saved()
        storage.writeSnapshot(saved)
        val controller = create()
        assertNull(controller.session.value)
        assertFalse(controller.opens.value)
        assertEquals(saved, field("pendingRestore").get(controller))
        assertEquals(ShareDecision.RESTORE, controller.shareNotice.value)
        assertNull(field("workScope").get(controller))
        assertNull(field("aligner").get(controller))
        assertNull(field("syncRun").get(controller))
        assertEquals(saved, storage.readSnapshot())
    }

    @Test fun anExpiredSnapshotIsDroppedAndItsNoticeSurvivesOpeningTheGuide() = runBlocking {
        storage.writeSnapshot(saved(10_001))
        val controller = create()
        assertNull(field("pendingRestore").get(controller))
        controller.showGuide()
        serialized(controller)
        assertNull(controller.session.value)
        assertEquals(ShareDecision.RESTORE, controller.shareNotice.value)
        assertFalse(controller.opens.value)
    }

    @Test fun aSameSongShareAfterProcessDeathStartsAFreshWait() = runBlocking {
        val saved = saved(9_000)
        storage.writeSnapshot(saved)
        val controller = create()
        controller.share("分享Cloud Quartet的单曲《Paper Lantern Test》: https://music.163.com/song?id=999999881", "test")
        withTimeout(15_000) {
            while (controller.session.value?.audioWaitSinceNanos == null) delay(20)
        }
        val state = controller.session.value!!
        assertTrue(state.running)
        assertNotEquals(saved.cid, state.cid)
        assertTrue(state.audioWaitSinceNanos!! > saved.audioWaitSinceNanos!!)
        assertNull(field("pendingRestore").get(controller))
    }
}
