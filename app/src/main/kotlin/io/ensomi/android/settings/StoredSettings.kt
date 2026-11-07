@file:OptIn(ExperimentalSerializationApi::class)

package io.ensomi.android.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The settings as they are kept on the device: [PlaySettings] with the latency slots (latency.md, Storage and
 * migration). A file from before the key map has no [keys] and reads the default map. A file from before the slots has
 * no [audioLatency]; [migrated] fills the slots from its audio offsets and visual offset, once, and those two are read
 * for that only and left out of every later write.
 */
@Serializable
data class StoredSettings(
    val scrollSpeed: Double = PlaySettings.DEFAULT_SCROLL_SPEED,
    val judge: JudgeDifficulty = JudgeDifficulty.C,
    val musicVolume: Float = PlaySettings().musicVolume,
    val orientation: PlayOrientation = PlayOrientation.LANDSCAPE,
    val landscapeLean: Int = PlaySettings.DEFAULT_LANDSCAPE_LEAN,
    val portraitLean: Int = PlaySettings.DEFAULT_PORTRAIT_LEAN,
    val landscapeWidth: Float? = null,
    val portraitWidth: Float? = null,
    val keys: StoredKeys = StoredKeys(),
    /** Null only in a file from before the slots, until [migrated]. */
    val audioLatency: StoredAudioLatency? = null,
    val inputLatency: StoredInputLatency = StoredInputLatency(),
    /** 0.1.x: the audio offset of every output that had one, by [OutputRoutes.key], in the order first set. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val audioOffsets: Map<String, Int>? = null,
    /** 0.1.x: the one visual offset. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val visualOffsetMs: Int? = null,
) {
    /** The settings play reads, each value within its range. */
    fun toPlaySettings(): PlaySettings = PlaySettings(
        scrollSpeed = scrollSpeed.coerceIn(PlaySettings.SCROLL_SPEEDS),
        judge = judge,
        musicVolume = musicVolume.coerceIn(0f, 1f),
        orientation = orientation,
        landscapeLean = landscapeLean.coerceIn(PlaySettings.LEANS),
        portraitLean = portraitLean.coerceIn(PlaySettings.LEANS),
        landscapeWidth = landscapeWidth?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        portraitWidth = portraitWidth?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        keys = keys.toMap(),
        audioLatency = (audioLatency ?: StoredAudioLatency()).toLatency(),
        inputLatency = inputLatency.toLatency(),
    )

    /** These settings changed to [settings]. */
    fun with(settings: PlaySettings): StoredSettings = copy(
        scrollSpeed = settings.scrollSpeed,
        judge = settings.judge,
        musicVolume = settings.musicVolume,
        orientation = settings.orientation,
        landscapeLean = settings.landscapeLean,
        portraitLean = settings.portraitLean,
        landscapeWidth = settings.landscapeWidth,
        portraitWidth = settings.portraitWidth,
        keys = StoredKeys.of(settings.keys),
        audioLatency = StoredAudioLatency.of(settings.audioLatency),
        inputLatency = StoredInputLatency.of(settings.inputLatency),
        audioOffsets = null,
        visualOffsetMs = null,
    )

    /** Whether this is a file from before the slots, which [migrated] moves to them. */
    val needsLatencySlots: Boolean get() = audioLatency == null

    /**
     * A file from before the slots with its offsets moved into them ([LatencyMigration]), the output keyed [currentKey]
     * being the one Android would play on now and [nameOf] naming an output key as the screens do; the old fields are
     * gone, so the next write leaves them out. A file that has slots is returned as it is.
     */
    fun migrated(currentKey: String?, nameOf: (String) -> String): StoredSettings =
        if (needsLatencySlots) movedTo(latencyMove(currentKey, nameOf)) else this

    /** [LatencyMigration] of this file's old fields. */
    fun latencyMove(currentKey: String?, nameOf: (String) -> String): LatencyMigration.Result =
        LatencyMigration.of(audioOffsets.orEmpty(), visualOffsetMs ?: 0, currentKey, nameOf)

    /** This file with the slots of [move] in place of its old fields. */
    fun movedTo(move: LatencyMigration.Result): StoredSettings = copy(
        audioLatency = StoredAudioLatency.of(move.audio),
        inputLatency = StoredInputLatency.of(move.input),
        audioOffsets = null,
        visualOffsetMs = null,
    )

    companion object {
        /** Unknown keys are skipped and missing ones take their defaults, so an older or newer file still reads. */
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}

/** A latency slot as the settings file keeps it: `{"name": "", "ms": 0}`. */
@Serializable
data class StoredLatencySlot(val name: String = "", val ms: Int = 0) {
    fun toSlot() = LatencySlot(Latency.name(name), Latency.ms(ms))

    companion object {
        fun of(slot: LatencySlot) = StoredLatencySlot(slot.name, slot.ms)

        /** Three slots, whatever [slots] holds: filled with empty ones or cut. */
        fun three(slots: List<StoredLatencySlot>): List<LatencySlot> =
            List(Latency.SLOTS) { i -> slots.getOrNull(i)?.toSlot() ?: LatencySlot() }
    }
}

/**
 * The audio latency slots as the settings file keeps them:
 * `{"active": 0, "slots": [{"name": "", "ms": 0}, {"name": "", "ms": 0}, {"name": "", "ms": 0}]}` by default. An index
 * outside 0..2 reads slot 0.
 */
@Serializable
data class StoredAudioLatency(
    val active: Int = 0,
    val slots: List<StoredLatencySlot> = List(Latency.SLOTS) { StoredLatencySlot() },
) {
    fun toLatency() = AudioLatency(StoredLatencySlot.three(slots), active.takeIf { it in Latency.INDICES } ?: 0)

    companion object {
        fun of(latency: AudioLatency) = StoredAudioLatency(latency.active, latency.slots.map(StoredLatencySlot::of))
    }
}

/**
 * The input latency slots and each method's slot as the settings file keeps them:
 * `{"slots": [...], "touch": 0, "keyboard": 1}` by default. An index outside 0..2 reads that method's default.
 */
@Serializable
data class StoredInputLatency(
    val slots: List<StoredLatencySlot> = List(Latency.SLOTS) { StoredLatencySlot() },
    val touch: Int = InputLatency().touch,
    val keyboard: Int = InputLatency().keyboard,
) {
    fun toLatency(): InputLatency {
        val defaults = InputLatency()
        return InputLatency(
            StoredLatencySlot.three(slots),
            touch = touch.takeIf { it in Latency.INDICES } ?: defaults.touch,
            keyboard = keyboard.takeIf { it in Latency.INDICES } ?: defaults.keyboard,
        )
    }

    companion object {
        fun of(latency: InputLatency) =
            StoredInputLatency(latency.slots.map(StoredLatencySlot::of), latency.touch, latency.keyboard)
    }
}

/**
 * The move to latency slots, run by DataStore the first time it reads a file without them, which it writes back before
 * anything else reads it: [currentKey] is the output Android would play on then, [nameOf] names an output key as the
 * screens do. [log] hears what moved, by slot number; names are never logged.
 */
class LatencySlotsMigration(
    private val currentKey: () -> String?,
    private val nameOf: (String) -> String,
    private val log: (String) -> Unit = {},
) : DataMigration<StoredSettings> {
    override suspend fun shouldMigrate(currentData: StoredSettings): Boolean = currentData.needsLatencySlots

    override suspend fun migrate(currentData: StoredSettings): StoredSettings {
        val offsets = currentData.audioOffsets.orEmpty()
        val key = currentKey()
        val move = currentData.latencyMove(key, nameOf)
        val moved = currentData.movedTo(move)
        val audio = moved.toPlaySettings().audioLatency
        val note = when {
            offsets.isEmpty() && (currentData.visualOffsetMs ?: 0) == 0 -> "nothing to carry"
            key == null -> "the output in use not known"
            key in offsets -> "the output in use had a stored offset"
            else -> "the output in use had no stored offset"
        }
        log(
            "Settings moved to latency slots: ${offsets.size - move.dropped} stored outputs carried, " +
                "${move.dropped} dropped, " +
                "visual offset ${currentData.visualOffsetMs ?: 0} ms " +
                "to touch's slot 0; audio slots " +
                audio.slots.mapIndexed { i, slot -> "$i ${PlaySettings.formatOffset(slot.ms)}" }.joinToString() +
                ", slot ${audio.active} active; $note",
        )
        return moved
    }

    override suspend fun cleanUp() = Unit
}

/**
 * The key map as the settings file keeps it (keyboard.md, Storage): each slot's key by its name in [KeyTable], null for
 * a slot without one, `{"lanes": ["Z", "X", "PERIOD", "SLASH"], "pause": "ESCAPE"}` by default. A map that names a key
 * not in the table, names one key in two slots or has other than four lanes reads as the default map; an empty slot
 * stays empty.
 */
@Serializable
data class StoredKeys(
    val lanes: List<String?> = KeyMap.DEFAULT.lanes.map { it?.name },
    val pause: String? = KeyMap.DEFAULT.pause?.name,
) {
    fun toMap(): KeyMap {
        if (lanes.size != KeySlot.LANES.size) return KeyMap.DEFAULT
        val keys = (lanes + pause).map { name -> name?.let { KeyTable.named(it) ?: return KeyMap.DEFAULT } }
        val bound = keys.filterNotNull()
        if (bound.size != bound.toSet().size) return KeyMap.DEFAULT
        return KeyMap(keys.subList(0, KeySlot.LANES.size).toList(), keys[KeySlot.PAUSE.ordinal])
    }

    companion object {
        fun of(map: KeyMap) = StoredKeys(map.lanes.map { it?.name }, map.pause?.name)
    }
}

/** [StoredSettings] as the JSON file DataStore keeps. A file that does not parse is reported as corrupt. */
object StoredSettingsSerializer : Serializer<StoredSettings> {
    override val defaultValue = StoredSettings()

    override suspend fun readFrom(input: InputStream): StoredSettings = try {
        StoredSettings.JSON.decodeFromString(StoredSettings.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("The settings file does not parse.", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("The settings file does not parse.", e)
    }

    override suspend fun writeTo(t: StoredSettings, output: OutputStream) {
        output.write(StoredSettings.JSON.encodeToString(StoredSettings.serializer(), t).encodeToByteArray())
    }
}
