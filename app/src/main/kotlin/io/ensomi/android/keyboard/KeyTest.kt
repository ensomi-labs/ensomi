package io.ensomi.android.keyboard

import android.view.KeyEvent
import io.ensomi.android.play.KeyInput
import io.ensomi.android.play.LaneHolder
import io.ensomi.android.settings.KeySlot
import io.ensomi.android.settings.ResolvedKeys

/**
 * The Keyboard screen's key test, as plain values (keyboard.md, Settings › Keyboard, item 4): which lanes the app sees
 * held, from any keyboard, the other keys held, and the most lane keys held together. It shows what arrives, which is all
 * the app can know: a keyboard may not report every key held at once (rollover), or report one that is not (ghosting).
 *
 * A lane is held while any key of it is down, on any keyboard, so the same lane on two keyboards is one. The most held
 * together counts lanes, as the game's log does. Once every lane key has been pressed at some point, a most under four
 * is a limit of the keyboard's, said in words ([limit]); it is said only once the player has held more than one, the
 * number the screen shows.
 *
 * While it runs, every key from a keyboard is the test's, so Space or Enter cannot press a button under it, apart from
 * Back, which stops it through the activity's Back, and Escape, which stops it unless it plays a lane. Keys still down
 * when it stops keep their repeats and ups. Keys from devices that are not keyboards go on as ever.
 */
data class KeyTest(
    val testing: Boolean = false,
    /** The lane keys held now, each with its lane, 0 to 3. */
    private val laneKeys: Map<LaneHolder.Key, Int> = emptyMap(),
    /** The other keys held now, in the order they went down. */
    val others: List<LaneHolder.Key> = emptyList(),
    /** The most lanes whose keys were held at once in this test, 0 to 4. */
    val mostHeld: Int = 0,
    /** The lanes whose keys went down at some point in this test. */
    val pressed: Set<Int> = emptySet(),
    /** Keys down when the test stopped, until their ups. */
    private val spent: Set<LaneHolder.Key> = emptySet(),
) {
    enum class Action {
        /** Not the test's: Compose and the system get it. */
        PASS_ON,
        TAKE,

        /** Taken, and the test stopped: Escape that plays no lane. */
        STOP,
    }

    data class Step(val test: KeyTest, val action: Action)

    /** Whether lane [lane], 0 to 3, is held now. */
    fun held(lane: Int): Boolean = lane in laneKeys.values

    /** The most held together, once the player has held more than one: what the screen shows. */
    val shownMostHeld: Int? get() = mostHeld.takeIf { it > 1 }

    /**
     * The most lane keys this keyboard reported at once, when that is a limit: every lane key has been pressed in this
     * test and the most held together, shown, is under four. Null otherwise.
     */
    val limit: Int? get() = shownMostHeld?.takeIf { pressed.size == LANES && it < LANES }

    /** Whether there is anything to show from a test: the screen offers to test again. */
    val hasResult: Boolean get() = shownMostHeld != null

    /** A new test, nothing held and nothing counted yet. */
    fun start(): KeyTest = KeyTest(testing = true, spent = spent)

    /** The test ends; what it found stays to be read, and keys still down keep their ups. */
    fun stop(): KeyTest =
        if (!testing) this else copy(testing = false, laneKeys = emptyMap(), others = emptyList(), spent = spent + laneKeys.keys + others)

    /**
     * [key], with [keysOf] giving each keyboard's map, the key code each slot's key produces on it, and null for a
     * device that is no keyboard.
     */
    fun key(key: KeyInput, keysOf: (deviceId: Int) -> ResolvedKeys?): Step {
        if (key.keyCode == KeyEvent.KEYCODE_BACK) return Step(this, Action.PASS_ON)
        val holder = LaneHolder.Key(key.deviceId, key.keyCode)
        if (holder in spent && (key.action == KeyEvent.ACTION_UP || key.repeatCount > 0)) {
            return Step(if (key.action == KeyEvent.ACTION_UP) copy(spent = spent - holder) else this, Action.TAKE)
        }
        if (!testing) return Step(this, Action.PASS_ON)
        val keys = keysOf(key.deviceId) ?: return Step(this, Action.PASS_ON)
        if (key.flags and NEVER_PRESSED != 0) return Step(this, Action.TAKE)
        return when (key.action) {
            KeyEvent.ACTION_UP -> Step(copy(laneKeys = laneKeys - holder, others = others - holder), Action.TAKE)
            KeyEvent.ACTION_DOWN -> down(key, holder, keys)
            else -> Step(this, Action.TAKE)
        }
    }

    private fun down(key: KeyInput, holder: LaneHolder.Key, keys: ResolvedKeys): Step {
        if (key.repeatCount > 0) return Step(this, Action.TAKE)
        val lane = keys.slotOf(key.keyCode)?.lane
        if (lane != null) {
            val held = laneKeys + (holder to lane)
            val together = held.values.toSet().size
            return Step(copy(laneKeys = held, mostHeld = maxOf(mostHeld, together), pressed = pressed + lane), Action.TAKE)
        }
        if (key.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            val stopped = stop()
            return Step(stopped.copy(spent = stopped.spent + holder), Action.STOP)
        }
        return Step(if (holder in others) this else copy(others = others + holder), Action.TAKE)
    }

    companion object {
        private val LANES = KeySlot.LANES.size
        private val NEVER_PRESSED = KeyEvent.FLAG_FALLBACK or KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_VIRTUAL_HARD_KEY

        /** A test as a screenshot scenario shows it: [held] lanes and [others] keys of the virtual keyboard down. */
        fun sample(testing: Boolean, held: List<Int>, others: List<Int>, mostHeld: Int, pressed: Set<Int>): KeyTest =
            KeyTest(
                testing = testing,
                laneKeys = held.associateBy { LaneHolder.Key(SAMPLE_DEVICE, SAMPLE_KEY_BASE + it) },
                others = others.map { LaneHolder.Key(SAMPLE_DEVICE, it) },
                mostHeld = mostHeld,
                pressed = pressed,
            )

        /** The virtual keyboard (`KeyCharacterMap.VIRTUAL_KEYBOARD`), whose keys the scenarios' samples stand for. */
        private const val SAMPLE_DEVICE = -1

        /** Sample lane holders need only be distinct. */
        private const val SAMPLE_KEY_BASE = 10_000
    }
}
