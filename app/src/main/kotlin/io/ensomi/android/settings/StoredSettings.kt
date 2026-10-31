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
 * by [OutputRoutes.key]. An output without one plays at 0 ms.
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
