package io.ensomi.android.checks

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ensomi.android.chart.MakeState
import io.ensomi.android.chart.SectionDraft
import io.ensomi.android.chart.SectionKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Silent, file-only gate: fresh preparation versus bytes reopened by a different maker, including decoded input. */
@RunWith(AndroidJUnit4::class)
class PreparedStoreCheck {
    @Test fun storedPreparationMakesTheIdenticalChart() = runBlocking {
        val installed = checkNotNull(installedModel()) { "Install the pinned model before running this gate" }
        val song = checkSong()
        val root = File(PhoneCheck.context.cacheDir, "prepared-store-check")
        root.deleteRecursively()
        val prepared = File(root, "prepared")
        val stars = PhoneCheck.argDouble("stars", GenerationCheck.STARS)
        val seed = PhoneCheck.arg("seed", GenerationCheck.SEED.toString()).toULong()
        val sections = if (PhoneCheck.argInt("sections", 0) == 1) {
            listOf(SectionDraft(1, "00:16", "00:32", SectionKind.STAR, star = 3.0f))
        } else emptyList()
        try {
            repeat(PhoneCheck.argInt("runs", 3)) { run ->
                prepared.deleteRecursively()
                suspend fun generate(name: String, decoded: Boolean = false) = generateThroughTheApp(
                    song, installed.first, installed.second?.revision ?: "unknown", checkLowMemory(), stars, seed,
                    File(root, name), preparedRoot = prepared, fromStoredDecoded = decoded, sections = sections,
                )
                val cold = generate("cold")
                assertTrue("Cold chart failed: ${cold.state}", cold.state is MakeState.Made)
                val size = prepared.listFiles().orEmpty().sumOf { it.length() }
                assertTrue("No stored preparation", size > 0)
                val stored = generate("stored")
                val decoded = generate("decoded", decoded = true)
                val hashes = listOf(cold, stored, decoded).map { it.osu?.let { osu -> PhoneCheck.sha256(osu.toByteArray()) } }
                val same = hashes[0] != null && hashes.all { it == hashes[0] }
                PhoneCheck.report("prepared-store", linkedMapOf(
                    "song" to song.name, "run" to run + 1,
                    "songSha256" to PhoneCheck.sha256(song.file), "stars" to stars, "seed" to seed.toString(),
                    "sections" to sections.size, "preparedBytes" to size,
                    "coldSeconds" to PhoneCheck.round(cold.totalMs / 1000),
                    "storedSeconds" to PhoneCheck.round(stored.totalMs / 1000),
                    "decodedStoredSeconds" to PhoneCheck.round(decoded.totalMs / 1000),
                    "coldPeakRssMB" to PhoneCheck.mb(cold.peakRss),
                    "storedPeakRssMB" to PhoneCheck.mb(stored.peakRss),
                    "decodedStoredPeakRssMB" to PhoneCheck.mb(decoded.peakRss),
                    "coldPhases" to cold.phaseStartMs.keys.toList(), "storedPhases" to stored.phaseStartMs.keys.toList(),
                    "osuSha256" to hashes, "ok" to same,
                ))
                assertTrue("STOP: stored preparation changed the .osu SHA-256: $hashes", same)
                assertFalse("Stored preparation ran audio stages", "timing" in stored.phaseStartMs)
                assertFalse("Decoded route ran audio stages", "timing" in decoded.phaseStartMs)
            }
        } finally { root.deleteRecursively() }
    }
}
