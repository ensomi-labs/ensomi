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
 *
 * A pause keeps every holder (keyboard.md, O7): until [resume] nothing is pressed, and a lane whose holders all let go
 * is kept for the resume instead of being released, so that the game hears of it only when play goes on.
 */
class LaneHolds(private val lanes: Int = PlayfieldGeometry.LANES) {
    private val laneByHolder = HashMap<LaneHolder, Int>()
    private val holdersInLane = IntArray(lanes)

    /** While paused, the source of the holder that let go last of each lane no holder holds any more, else null. */
    private val letGo = arrayOfNulls<InputSource>(lanes)

    var isPaused = false
        private set

    /** [holder] went down in [lane]. True when that lane was not held before: a press to send. Never while paused. */
    fun press(holder: LaneHolder, lane: Int): Boolean {
        if (isPaused || lane !in 0 until lanes || laneByHolder.containsKey(holder)) return false
        laneByHolder[holder] = lane
        return holdersInLane[lane]++ == 0
    }

    /**
     * [holder] let go. The lane it held, when no other holder still holds it: a release to send. While paused that lane
     * is kept for [resume] instead, and this is null.
     */
    fun release(holder: LaneHolder): Int? {
        val lane = laneByHolder.remove(holder) ?: return null
        if (--holdersInLane[lane] != 0) return null
        if (!isPaused) return lane
        letGo[lane] = holder.source
        return null
    }

    /**
     * Every holder that [letsGo] lets go at once: all pointers when the system takes the gesture, one keyboard's keys
     * when it goes. The lanes no holder holds any more, from lane 1 to 4: releases to send. While paused they are kept
     * for [resume] instead, and this is empty.
     */
    fun releaseAll(letsGo: (LaneHolder) -> Boolean): List<Int> {
        val held = BooleanArray(lanes) { holdersInLane[it] > 0 }
        val lastSource = arrayOfNulls<InputSource>(lanes)
        val each = laneByHolder.entries.iterator()
        while (each.hasNext()) {
            val (holder, lane) = each.next()
            if (!letsGo(holder)) continue
            each.remove()
            holdersInLane[lane]--
            lastSource[lane] = holder.source
        }
        val freed = (0 until lanes).filter { held[it] && holdersInLane[it] == 0 }
        if (!isPaused) return freed
        for (lane in freed) letGo[lane] = lastSource[lane]
        return emptyList()
    }

    fun isHeld(lane: Int): Boolean = holdersInLane.getOrElse(lane) { 0 } > 0

    /** The game paused: every holder stays, and until [resume] presses take nothing and lanes let go are kept. */
    fun pause() {
        if (isPaused) return
        isPaused = true
        letGo.fill(null)
    }

    /**
     * Play goes on: the lanes whose holders all let go during the pause, from lane 1 to 4, each with the source of the
     * holder that let go last: releases to send now. A holder still down holds its lane on, and nothing is sent for it.
     */
    fun resume(): List<Pair<Int, InputSource>> {
        if (!isPaused) return emptyList()
        isPaused = false
        val released = (0 until lanes).mapNotNull { lane -> letGo[lane]?.let { lane to it } }
        letGo.fill(null)
        return released
    }

    /** Every holder and every lane let go is forgotten, so nothing reaches another game; a pause stays a pause. */
    fun clear() {
        laneByHolder.clear()
        holdersInLane.fill(0)
        letGo.fill(null)
    }
}

/**
 * Where every source's presses and releases meet, on the main thread: [LaneHolds] decides which reach the game, and each
 * that does takes the next number of [nextSequence], the counter the autoplay's inputs share, and goes to [send] with its
 * holder's source. A source only turns its own events into holders and lanes: touch through the lanes at the line, keys
 * through [KeyLanes].
 *
 * Between [pause] and [resume] nothing is sent: presses are dropped, and the lanes let go are released at the resume,
 * marked [LaneInput.atPause] so that the game stamps them at the song time it paused at, since its clock was frozen.
 */
class LaneSender(private val nextSequence: () -> Long, private val send: (LaneInput) -> Unit) {
    private val holds = LaneHolds()

    /** [holder] went down in [lane] at [eventNanos]. True when that sent a press; never while paused. */
    fun press(holder: LaneHolder, lane: Int, eventNanos: Long): Boolean {
        if (!holds.press(holder, lane)) return false
        send(LaneInput(lane, press = true, eventNanos, nextSequence(), holder.source))
        return true
    }

    /** [holder] let go at [eventNanos]. True when that sent a release; while paused the lane waits for [resume]. */
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

    /** The game paused: every holder is kept and nothing is sent, so the game hears nothing of the pause. */
    fun pause() = holds.pause()

    /**
     * Play goes on at [eventNanos]: each lane whose holders all let go during the pause is released, at the song time
     * the game paused at ([LaneInput.atPause]). The lanes released, from lane 1 to 4.
     */
    fun resume(eventNanos: Long): List<Int> = holds.resume().map { (lane, source) ->
        send(LaneInput(lane, press = false, eventNanos, nextSequence(), source, atPause = true))
        lane
    }

    /** The lanes held now, from lane 1 to 4. */
    fun heldLanes(): List<Int> = (0 until PlayfieldGeometry.LANES).filter(holds::isHeld)

    /** Every holder is forgotten and nothing is sent: the playfield closed, and nothing may reach another game. */
    fun clear() = holds.clear()
}
