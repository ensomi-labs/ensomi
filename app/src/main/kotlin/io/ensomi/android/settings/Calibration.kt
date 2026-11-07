package io.ensomi.android.settings

import android.content.Context
import android.media.AudioDeviceInfo
import android.util.Log
import io.ensomi.android.audio.AudioPlayClock
import io.ensomi.android.audio.ClickTrack
import io.ensomi.android.audio.GameAudio
import io.ensomi.android.play.InputSource
import io.ensomi.android.play.JudgedPress
import io.ensomi.android.play.PlayClock
import java.util.Locale
import kotlin.math.abs

/** The click track's own arithmetic: where a press heard at a song time falls against the nearest click. */
object CalibrationTaps {
    /**
     * The error of a press heard at [songMs] from the nearest click, in milliseconds, or null for one more than half a
     * period before the first click. It is the error the runtime gives with no latency; the calibration's log keeps it
     * beside the runtime's, so a check can tell the two apart by the latencies alone.
     */
    fun errorMs(songMs: Double): Double? {
        val k = Math.round((songMs - ClickTrack.FIRST_CLICK_MS) / ClickTrack.PERIOD_MS)
        if (k < 0) return null
        val error = songMs - (ClickTrack.FIRST_CLICK_MS + k * ClickTrack.PERIOD_MS)
        return error.takeIf { abs(it) <= ClickTrack.PERIOD_MS / 2 }
    }
}

/**
 * One hit on the calibration's field: its method, the runtime's error (`JudgementEvent.offsetMs`, press time less note
 * time, positive for late), and the audio latency and its method's input latency, A + I, it was judged with.
 */
data class CalibrationHit(val method: InputSource, val errorMs: Double, val judgedWithMs: Double) {
    init {
        require(method.isMethod) { "The autoplay presses nothing on the calibration" }
    }
}

/**
 * The readings (latency.md, Timing calibration): the last [HITS] hits of each method, oldest first, each shown as it
 * would be judged with the values now. A hit made before A or its method's I changed moves by that change: its error less
 * the rise in A + I since, as a press is judged at its song time less A and I. So the audio latency moves every reading,
 * and an input latency only its own method's. Nothing here chooses or applies a value.
 */
data class CalibrationReadings(val hits: List<CalibrationHit> = emptyList()) {
    /** With [hit] the newest of its method's, keeping that method's last [HITS]. */
    fun add(hit: CalibrationHit): CalibrationReadings {
        val all = hits + hit
        if (all.count { it.method == hit.method } <= HITS) return copy(hits = all)
        val oldest = all.indexOfFirst { it.method == hit.method }
        return copy(hits = all.filterIndexed { i, _ -> i != oldest })
    }

    fun count(method: InputSource): Int = hits.count { it.method == method }

    /** [method]'s hits, oldest first, as judged with [latencyMs], A + I[method] now. */
    fun shown(method: InputSource, latencyMs: Double): List<Double> =
        hits.filter { it.method == method }.map { it.errorMs - (latencyMs - it.judgedWithMs) }

    /** The average of [shown], or null before [method]'s first hit. */
    fun averageMs(method: InputSource, latencyMs: Double): Double? =
        shown(method, latencyMs).takeIf { it.isNotEmpty() }?.average()

    /** Empty: Clear hits, or a new output. It changes no value. */
    fun cleared(): CalibrationReadings = CalibrationReadings()

    companion object {
        const val HITS = 16

        /**
         * The reading [press] gives, or null: a press that hit nothing, or whose error says nothing of where it landed,
         * since the session judged it late at its clock, or a pause or its lane's last input held it later ([JudgedPress.exact]).
         */
        fun hitOf(press: JudgedPress): CalibrationHit? {
            val error = press.offsetMs ?: return null
            val judgedWith = press.stampedWithMs ?: return null
            if (!press.exact || !press.source.isMethod) return null
            return CalibrationHit(press.source, error, judgedWith)
        }
    }
}

/**
 * What the calibration's controls do to the settings (latency.md, Timing calibration). Every change is kept at once in
 * the slot it edits, through the settings store, with nothing to confirm: the audio value is the active audio slot's,
 * the input value the slot of the method chosen (O3), and choosing an audio slot here chooses the active slot, as in
 * Settings. Nothing is ever applied that the player did not move.
 */
object CalibrationEdits {
    /** The method whose input slot is edited as the screen opens: Touch, or Keyboard when a keyboard is connected. */
    fun firstMethod(keyboardConnected: Boolean): InputSource = if (keyboardConnected) InputSource.KEYBOARD else InputSource.TOUCH

    /** The input slot [method] uses. */
    fun inputSlot(latency: InputLatency, method: InputSource): Int = when (method) {
        InputSource.TOUCH -> latency.touch
        InputSource.KEYBOARD -> latency.keyboard
        InputSource.AUTOPLAY -> throw IllegalArgumentException("The autoplay has no input slot")
    }

    fun inputMs(settings: PlaySettings, method: InputSource): Int =
        settings.inputLatency.slots[inputSlot(settings.inputLatency, method)].ms

    /** A + I[method] with [settings]: what [method]'s readings are shown at. */
    fun latencyMs(settings: PlaySettings, method: InputSource): Double =
        (settings.audioLatencyMs + inputMs(settings, method)).toDouble()

    fun audioMs(settings: PlaySettings, ms: Int): PlaySettings = settings.withAudioLatencyMs(ms)

    fun audioStep(settings: PlaySettings, step: Int): PlaySettings = audioMs(settings, settings.audioLatencyMs + step)

    fun audioSlot(settings: PlaySettings, slot: Int): PlaySettings =
        settings.copy(audioLatency = settings.audioLatency.withActive(slot))

    /** [method]'s slot set to [ms]: a method sharing that slot changes with it. */
    fun inputMs(settings: PlaySettings, method: InputSource, ms: Int): PlaySettings =
        settings.copy(inputLatency = settings.inputLatency.withMs(inputSlot(settings.inputLatency, method), ms))

    fun inputStep(settings: PlaySettings, method: InputSource, step: Int): PlaySettings =
        inputMs(settings, method, inputMs(settings, method) + step)

    /** [method] uses input slot [slot] from now on. */
    fun inputSlot(settings: PlaySettings, method: InputSource, slot: Int): PlaySettings = settings.copy(
        inputLatency = when (method) {
            InputSource.TOUCH -> settings.inputLatency.withTouch(slot)
            InputSource.KEYBOARD -> settings.inputLatency.withKeyboard(slot)
            InputSource.AUTOPLAY -> throw IllegalArgumentException("The autoplay has no input slot")
        },
    )
}

/**
 * The calibration's lines under `EnsomiCalibration` (latency.md, Diagnostics): each press with its event time, method,
 * slots, song and chart time and the runtime's error, and each change of a value or a slot, so a check can recompute the
 * errors from the event times, the time of click 0 and the values. Slots are numbers; names are never logged.
 */
object CalibrationLog {
    /**
     * "Press: touch on input slot 0 +8 ms, audio slot 1 +142 ms; event 123 ns, song 5023.40 ms, chart 4873.40 ms, from
     * the nearest click +23.40 ms; error +23.40 ms (p1)". A press that hit nothing says so; one that cannot be a reading
     * says why.
     */
    fun press(press: JudgedPress): String {
        val timing = press.timing
        val method = press.source.name.lowercase()
        val slot = if (press.source == InputSource.KEYBOARD) timing.keyboardSlot else timing.touchSlot
        val input = timing.inputLatencyMs(press.source)
        val song = press.songMs
        val parts = mutableListOf(
            String.format(
                Locale.ROOT,
                "Press: %s on input slot %s %+.0f ms, audio slot %s %+.0f ms",
                method, slot?.toString() ?: "none", input, timing.audioSlot?.toString() ?: "none", timing.audioLatencyMs,
            ),
            buildString {
                append(String.format(Locale.ROOT, "event %d ns, song ", press.eventNanos))
                append(song?.let { String.format(Locale.ROOT, "%.2f ms", it) } ?: "none")
                append(String.format(Locale.ROOT, ", chart %.2f ms", press.chartMs))
                song?.let(CalibrationTaps::errorMs)?.let { append(String.format(Locale.ROOT, ", from the nearest click %+.2f ms", it)) }
            },
            press.offsetMs?.let { String.format(Locale.ROOT, "error %+.2f ms (%s)", it, press.tier) } ?: "no hit",
        )
        when {
            press.late -> parts += "late: judged at the session's clock, not a reading"
            !press.exact -> parts += "held to a pause or its lane's last input, not a reading"
        }
        return parts.joinToString("; ")
    }

    /** What changed from [old] to [new], one line for each value, slot choice or name. */
    fun changes(old: PlaySettings, new: PlaySettings): List<String> {
        val lines = mutableListOf<String>()
        if (old.audioLatency.active != new.audioLatency.active) {
            lines += "Audio latency: slot ${new.audioLatency.active} active, was ${old.audioLatency.active}"
        }
        slots("Audio latency", old.audioLatency.slots, new.audioLatency.slots, lines)
        slots("Input latency", old.inputLatency.slots, new.inputLatency.slots, lines)
        if (old.inputLatency.touch != new.inputLatency.touch) {
            lines += "Touch uses input slot ${new.inputLatency.touch}, was ${old.inputLatency.touch}"
        }
        if (old.inputLatency.keyboard != new.inputLatency.keyboard) {
            lines += "Keyboard uses input slot ${new.inputLatency.keyboard}, was ${old.inputLatency.keyboard}"
        }
        return lines
    }

    /** The method whose input slot the screen edits, and whose readings it shows. */
    fun method(method: InputSource): String = "Editing ${method.name.lowercase()}'s input slot; readings of ${method.name.lowercase()}"

    private fun slots(kind: String, old: List<LatencySlot>, new: List<LatencySlot>, lines: MutableList<String>) {
        old.zip(new).forEachIndexed { i, (before, after) ->
            if (before.ms != after.ms) {
                lines += String.format(Locale.ROOT, "%s slot %d: %+d ms, was %+d ms", kind, i, after.ms, before.ms)
            }
            if (before.name != after.name) lines += "$kind slot $i: ${if (after.name.isEmpty()) "name cleared" else "named"}"
        }
    }
}

/** A click track playing: its clock, on which click k is heard at `1000 + 500 k` ms. Close it on any thread. */
interface ClickPlayer : AutoCloseable {
    val clock: PlayClock
}

/** Opens the calibration's click track; [onRoute] hears the device it plays on, on the main thread. */
fun interface ClickTracks {
    fun open(context: Context, onRoute: (AudioDeviceInfo?) -> Unit): ClickPlayer
}

/**
 * The click track through the play output: the same [GameAudio] path and [AudioPlayClock] as a game, so the clicks
 * are timed as a song's notes are. The track's gain is full; the phone's media volume sets how loud the clicks are.
 */
object AudioClickTracks : ClickTracks {
    override fun open(context: Context, onRoute: (AudioDeviceInfo?) -> Unit): ClickPlayer {
        var clock: AudioPlayClock? = null
        var deviceId: Int? = null
        val audio = GameAudio(
            context,
            ClickTrack.song(),
            leadInMs = 0.0,
            volume = 1f,
            onRoute = { device ->
                if (device != null && device.id != deviceId) {
                    // A change after the first route: the clock reads the new output from its start.
                    if (deviceId != null) clock?.outputChanged(System.nanoTime())
                    deviceId = device.id
                    Log.i(TAG, "Clicks play on ${device.type}/${device.productName} (id ${device.id})")
                    onRoute(device)
                }
            },
            loopFromFrame = ClickTrack.loopFromFrame(),
        )
        val audioClock = AudioPlayClock(audio, audio.sampleRate, leadInFrames = 0) { Log.i(TAG, it) }
        clock = audioClock
        return object : ClickPlayer {
            override val clock: PlayClock = audioClock

            override fun close() {
                audio.close()
                Log.i(TAG, "Clicks closed: ${audioClock.reads} timestamps read, ${audioClock.stepsBack} steps back held")
            }
        }
    }

    const val TAG = "EnsomiCalibration"
}
