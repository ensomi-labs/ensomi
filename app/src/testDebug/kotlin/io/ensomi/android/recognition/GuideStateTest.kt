package io.ensomi.android.recognition

import org.junit.Assert.*
import org.junit.Test

class GuideStateTest {
    private val text = "分享Artist的单曲《Song》: https://music.163.com/song?id=42 (来自@网易云音乐)"

    @Test fun everyPrerequisiteIsRequiredAndLiveChangesTakeEffect() {
        for (model in listOf(false, true)) for (mic in listOf(false, true)) for (music in listOf(false, true)) {
            val state = GuideState(model, mic, music)
            assertEquals(model && mic && music, state.ready)
            val expected = when {
                !model -> ShareDecision.MODEL
                !mic || !music -> ShareDecision.PERMISSIONS
                else -> ShareDecision.START
            }
            assertEquals(expected, shareDecision(ShareParser.parse(text), state))
        }
        val allowed = GuideState(true, true, true)
        assertEquals(ShareDecision.PERMISSIONS, shareDecision(ShareParser.parse(text), allowed.copy(microphone = false)))
        assertEquals(ShareDecision.PERMISSIONS, shareDecision(ShareParser.parse(text), allowed.copy(music = false)))
    }

    @Test fun malformedEmptyOversizedAndNonSongSharesNeverStartWork() {
        for (text in listOf("", "hello", "https://music.163.com/playlist?id=42", "x".repeat(8193))) {
            for (ready in listOf(GuideState(true, true, true), GuideState(false, false, false))) {
                assertEquals(ShareDecision.NOT_SONG, shareDecision(ShareParser.parse(text), ready))
            }
        }
    }

    @Test fun aReadyShareHandsIdentityToTheSessionAndStartsItsTenSecondWait() {
        val shared = ShareParser.parse(text)
        assertEquals(ShareDecision.START, shareDecision(shared, GuideState(true, true, true)))
        val now = SessionNow(1_000_000_000, 1000)
        val (machine, initial) = SessionMachine.start("guide", true, now) { _, _ -> }
        assertEquals(listOf(SessionEffect.StartListening, SessionEffect.BuildIndex), initial)
        machine.on(SessionEvent.ShareReceived(text, shared, null), now)
        assertEquals("Song", machine.state.song?.title)
        assertNull(machine.state.audioWaitSinceNanos)
        machine.on(SessionEvent.IndexReady(emptyList(), emptyMap()), now)
        assertEquals(now.nanos, machine.state.audioWaitSinceNanos)
        machine.on(SessionEvent.Tick, SessionNow(now.nanos + 9_999_000_000, 10_999))
        assertTrue(machine.state.running)
        machine.on(SessionEvent.Tick, SessionNow(now.nanos + 10_000_000_000, 11_000))
        assertEquals(FailureKind.NO_FILE, machine.state.failure?.kind)
        assertEquals("audio_wait_timeout", machine.state.ended)
    }
}
