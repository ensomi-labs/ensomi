package io.ensomi.android.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The activity's key router and the input method guard (keyboard.md, Where key events arrive; D6). */
class HardwareKeysTest {
    private val router = KeyRouter<String>()
    private val seen = ArrayList<String>()

    /** A handler named [name] that takes the events in [takes]. */
    private fun handler(name: String, vararg takes: String): (String) -> Boolean = { event ->
        seen += "$name:$event"
        event in takes
    }

    @Test
    fun theNewestHandlerSeesEachEventFirst() {
        router.register(handler("play", "Z"))
        router.register(handler("results", "X"))
        assertTrue(router.dispatch("X"))
        assertEquals("The newest took it, and the older never saw it", listOf("results:X"), seen)
        seen.clear()
        assertTrue(router.dispatch("Z"))
        assertEquals(listOf("results:Z", "play:Z"), seen)
    }

    @Test
    fun anEventNoHandlerTakesGoesOn() {
        router.register(handler("play", "Z"))
        assertFalse(router.dispatch("TAB"))
        assertEquals(listOf("play:TAB"), seen)
        assertFalse("Nothing registered: everything goes on", KeyRouter<String>().dispatch("Z"))
    }

    @Test
    fun aHandlerLetGoSeesNothingMoreAndTheOthersKeepTheirOrder() {
        val first = router.register(handler("a"))
        val second = router.register(handler("b"))
        router.register(handler("c"))
        second()
        router.dispatch("Z")
        assertEquals(listOf("c:Z", "a:Z"), seen)
        first()
        first()
        assertEquals("Letting go twice lets go once", 1, router.size)
    }

    @Test
    fun theSameHandlerRegisteredTwiceIsTwoRegistrations() {
        val shared = handler("shared")
        val one = router.register(shared)
        router.register(shared)
        one()
        router.dispatch("Z")
        assertEquals(listOf("shared:Z"), seen)
        assertEquals(1, router.size)
    }

    @Test
    fun aHandlerRegisteredDuringAnEventSeesTheNextOne() {
        router.register { event ->
            seen += "old:$event"
            if (event == "open") router.register(handler("new"))
            false
        }
        router.dispatch("open")
        router.dispatch("Z")
        assertEquals(listOf("old:open", "new:Z", "old:Z"), seen)
    }

    @Test
    fun theWindowIsNoInputMethodTargetWhileAnyScreenAsks() {
        val applied = ArrayList<Boolean>()
        val guard = InputMethodGuard { applied += it }
        val play = guard.keepAway()
        assertEquals(listOf(true), applied)
        // Results ask before the play screen lets go, as one replaces the other.
        val results = guard.keepAway()
        play()
        assertTrue(guard.isAway)
        assertEquals("The flag stays on across the change", listOf(true), applied)
        results()
        assertEquals(listOf(true, false), applied)
        results()
        assertFalse(guard.isAway)
        assertEquals("A release is counted once", listOf(true, false), applied)
        guard.keepAway()
        assertEquals(listOf(true, false, true), applied)
    }
}
