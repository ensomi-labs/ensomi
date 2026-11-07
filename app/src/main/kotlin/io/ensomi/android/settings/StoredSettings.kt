package io.ensomi.android.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The settings as they are kept on the device: [PlaySettings] with the audio offset of every output that has one,
 * by [OutputRoutes.key]. An output without one plays at 0 ms. A file from before the key map has no [keys] and reads
 * the default map.
 */
@Serializable
data class StoredSettings(
    val scrollSpeed: Double = PlaySettings.DEFAULT_SCROLL_SPEED,
    val judge: JudgeDifficulty = JudgeDifficulty.C,
    val musicVolume: Float = PlaySettings().musicVolume,
    val audioOffsets: Map<String, Int> = emptyMap(),
    val visualOffsetMs: Int = 0,
    val orientation: PlayOrientation = PlayOrientation.LANDSCAPE,
    val landscapeLean: Int = PlaySettings.DEFAULT_LANDSCAPE_LEAN,
    val portraitLean: Int = PlaySettings.DEFAULT_PORTRAIT_LEAN,
    val landscapeWidth: Float? = null,
    val portraitWidth: Float? = null,
    val keys: StoredKeys = StoredKeys(),
) {
    /** The settings play reads on the output keyed [outputKey], each value within its range. */
    fun forOutput(outputKey: String): PlaySettings = PlaySettings(
        scrollSpeed = scrollSpeed.coerceIn(PlaySettings.SCROLL_SPEEDS),
        judge = judge,
        musicVolume = musicVolume.coerceIn(0f, 1f),
        audioOffsetMs = (audioOffsets[outputKey] ?: 0).coerceIn(PlaySettings.OFFSETS),
        visualOffsetMs = visualOffsetMs.coerceIn(PlaySettings.OFFSETS),
        orientation = orientation,
        landscapeLean = landscapeLean.coerceIn(PlaySettings.LEANS),
        portraitLean = portraitLean.coerceIn(PlaySettings.LEANS),
        landscapeWidth = landscapeWidth?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        portraitWidth = portraitWidth?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        keys = keys.toMap(),
    )

    /** These settings changed to [settings], whose audio offset is the one of the output keyed [outputKey]. */
    fun with(settings: PlaySettings, outputKey: String): StoredSettings = copy(
        scrollSpeed = settings.scrollSpeed,
        judge = settings.judge,
        musicVolume = settings.musicVolume,
        audioOffsets = if ((audioOffsets[outputKey] ?: 0) == settings.audioOffsetMs) {
            audioOffsets
        } else {
            audioOffsets + (outputKey to settings.audioOffsetMs)
        },
        visualOffsetMs = settings.visualOffsetMs,
        orientation = settings.orientation,
        landscapeLean = settings.landscapeLean,
        portraitLean = settings.portraitLean,
        landscapeWidth = settings.landscapeWidth,
        portraitWidth = settings.portraitWidth,
        keys = StoredKeys.of(settings.keys),
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
