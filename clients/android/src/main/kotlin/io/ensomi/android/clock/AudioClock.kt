package io.ensomi.android.clock

/** What the output device reports: the audio time heard, and the host time at which it was heard. */
data class AudioAnchor(val positionMs: Double, val hostNanos: Long)

/**
 * The audio clock contract from [platform clients](../../../../../../../docs/platform-clients.md): it
 * answers the audio time heard at a host time and the host time at which a given audio time was heard.
 *
 * A reading is what the listener hears, not how far the decoder has read; while the output device is
 * unchanged the clock is monotonic, and a device change replaces the clock rather than bending it.
 */
interface AudioClock {
    fun reading(): AudioAnchor

    fun positionMsAt(hostNanos: Long): Double

    fun hostNanosOf(positionMs: Double): Long
}

/**
 * Fits the audio timeline through the anchors the device reports. One anchor is enough to answer both
 * questions at the device's nominal rate; a second one, far enough away, measures the drift instead of
 * assuming it.
 */
class AnchorAudioClock(first: AudioAnchor) : AudioClock {
    private var anchor: AudioAnchor = first
    private var rate: Double = 1.0

    override fun reading(): AudioAnchor = anchor

    /**
     * Takes a fresh reading from the output device. A reading that arrived late, or that reports the
     * audio moving backwards, is ignored: the clock never runs backwards.
     */
    fun onReading(reading: AudioAnchor) {
        val hostDeltaMs = (reading.hostNanos - anchor.hostNanos) / 1_000_000.0
        val positionDeltaMs = reading.positionMs - anchor.positionMs
        if (hostDeltaMs <= 0 || positionDeltaMs < 0) return

        if (hostDeltaMs >= MIN_DRIFT_WINDOW_MS) {
            val measured = positionDeltaMs / hostDeltaMs
            if (measured in MIN_RATE..MAX_RATE) {
                rate = measured
            }
        }
        anchor = reading
    }

    override fun positionMsAt(hostNanos: Long): Double =
        anchor.positionMs + (hostNanos - anchor.hostNanos) / 1_000_000.0 * rate

    override fun hostNanosOf(positionMs: Double): Long =
        anchor.hostNanos + ((positionMs - anchor.positionMs) / rate * 1_000_000.0).toLong()

    private companion object {
        /** Below this, the two readings are too close together to measure a rate. */
        const val MIN_DRIFT_WINDOW_MS = 500.0

        /** A measured rate outside this band is a bad reading, not a clock, and is ignored. */
        const val MIN_RATE = 0.9
        const val MAX_RATE = 1.1
    }
}
