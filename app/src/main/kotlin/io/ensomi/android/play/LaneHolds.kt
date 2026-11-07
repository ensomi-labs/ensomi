package io.ensomi.android.play

/**
 * What holds a lane down: a finger on the glass or a key on a keyboard. Another kind of input would be another holder;
 * none is planned (keyboard.md, Lanes from several sources).
 */
sealed interface LaneHolder {
    /** The source of the presses and releases this holder makes. */
    val source: InputSource

    /** A pointer on the playfield, by its `MotionEvent` pointer id. */
    data class Pointer(val id: Int) : LaneHolder {
        override val source: InputSource get() = InputSource.TOUCH
    }

    /** A key, by the `InputDevice` id of its keyboard and the key code it produces there. */
    data class Key(val deviceId: Int, val keyCode: Int) : LaneHolder {
        override val source: InputSource get() = InputSource.KEYBOARD
    }
}

/**
 * Which holders hold which lane. A holder belongs to the lane it pressed until it lets go; sliding into another lane,
 * or pressing again, does nothing meanwhile. A lane is held while any holder holds it, finger or key, so the press goes
 * out with its first holder and the release with its last, and a press of a held lane sends nothing.
 */
class LaneHolds(private val lanes: Int = PlayfieldGeometry.LANES) {
    private val laneByHolder = HashMap<LaneHolder, Int>()
    private val holdersInLane = IntArray(lanes)

    /** [holder] went down in [lane]. True when that lane was not held before: a press to send. */
    fun press(holder: LaneHolder, lane: Int): Boolean {
        if (lane !in 0 until lanes || laneByHolder.containsKey(holder)) return false
        laneByHolder[holder] = lane
        return holdersInLane[lane]++ == 0
    }

    /** [holder] let go. The lane it held, when no other holder still holds it: a release to send. */
    fun release(holder: LaneHolder): Int? {
        val lane = laneByHolder.remove(holder) ?: return null
        return if (--holdersInLane[lane] == 0) lane else null
    }

    /**
     * Every holder that [letsGo] lets go at once: all pointers when the system takes the gesture, one keyboard's keys
     * when it goes. The lanes no holder holds any more, from lane 1 to 4: releases to send.
     */
    fun releaseAll(letsGo: (LaneHolder) -> Boolean): List<Int> {
        val held = BooleanArray(lanes) { holdersInLane[it] > 0 }
        val each = laneByHolder.entries.iterator()
        while (each.hasNext()) {
            val (holder, lane) = each.next()
            if (!letsGo(holder)) continue
            each.remove()
            holdersInLane[lane]--
        }
        return (0 until lanes).filter { held[it] && holdersInLane[it] == 0 }
    }

    fun isHeld(lane: Int): Boolean = holdersInLane.getOrElse(lane) { 0 } > 0
}

/**
 * Where every source's presses and releases meet, on the main thread: [LaneHolds] decides which reach the game, and each
 * that does takes the next number of [nextSequence], the counter the autoplay's inputs share, and goes to [send] with its
 * holder's source. A source only turns its own events into holders and lanes: touch through the lanes at the line, keys
 * through [KeyLanes].
 */
class LaneSender(private val nextSequence: () -> Long, private val send: (LaneInput) -> Unit) {
    private val holds = LaneHolds()

    /** [holder] went down in [lane] at [eventNanos]. True when that sent a press. */
    fun press(holder: LaneHolder, lane: Int, eventNanos: Long): Boolean {
        if (!holds.press(holder, lane)) return false
        send(LaneInput(lane, press = true, eventNanos, nextSequence(), holder.source))
        return true
    }

    /** [holder] let go at [eventNanos]. True when that sent a release. */
    fun release(holder: LaneHolder, eventNanos: Long): Boolean {
        val lane = holds.release(holder) ?: return false
        send(LaneInput(lane, press = false, eventNanos, nextSequence(), holder.source))
        return true
    }

    /** The system took the gesture (`ACTION_CANCEL`): every finger lets go at [eventNanos]; keys stay held. */
    fun releasePointers(eventNanos: Long) {
        for (lane in holds.releaseAll { it is LaneHolder.Pointer }) {
            send(LaneInput(lane, press = false, eventNanos, nextSequence(), InputSource.TOUCH))
        }
    }

    /** Keyboard [deviceId] went: its keys let go at [eventNanos]; fingers and other keyboards stay. */
    fun releaseKeys(deviceId: Int, eventNanos: Long) {
        for (lane in holds.releaseAll { it is LaneHolder.Key && it.deviceId == deviceId }) {
            send(LaneInput(lane, press = false, eventNanos, nextSequence(), InputSource.KEYBOARD))
        }
    }

    /** Every holder is forgotten and nothing is sent: what a pause does now, without telling the session. */
    fun forget() {
        holds.releaseAll { true }
    }
}
