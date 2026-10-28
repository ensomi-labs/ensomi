package io.ensomi.android.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CpuCoresTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun parsesCpuLists() {
        assertEquals(listOf(0, 1, 2, 3, 6, 8, 9), CpuCores.parseCpuList("0-3,6,8-9\n"))
        assertEquals(listOf(0), CpuCores.parseCpuList("0"))
        assertEquals(emptyList<Int>(), CpuCores.parseCpuList(""))
        assertEquals(emptyList<Int>(), CpuCores.parseCpuList("3-1"))
        assertEquals(emptyList<Int>(), CpuCores.parseCpuList("a-b"))
    }

    @Test
    fun parsesKiloHertz() {
        assertEquals(3_302_400L, CpuCores.parseKiloHertz("3302400\n"))
        assertNull(CpuCores.parseKiloHertz(""))
        assertNull(CpuCores.parseKiloHertz("0"))
        assertNull(CpuCores.parseKiloHertz(null))
    }

    @Test
    fun readsEachPossibleCoreAndToleratesMissingFiles() {
        val root = folder.root
        File(root, "possible").writeText("0-2\n")
        maxFreq(root, 0, "2265600\n")
        maxFreq(root, 2, "3302400\n")
        File(root, "cpu1").mkdirs() // offline core without cpufreq

        assertEquals(
            listOf(CpuCore(0, 2_265_600L), CpuCore(1, null), CpuCore(2, 3_302_400L)),
            CpuCores.read(root),
        )
    }

    @Test
    fun fallsBackToCoreDirectories() {
        val root = folder.root
        maxFreq(root, 10, "1000000")
        maxFreq(root, 2, "2000000")
        File(root, "cpufreq").mkdirs()
        File(root, "cpuidle").mkdirs()

        assertEquals(listOf(CpuCore(2, 2_000_000L), CpuCore(10, 1_000_000L)), CpuCores.read(root))
    }

    private fun maxFreq(root: File, core: Int, text: String) {
        val directory = File(root, "cpu$core/cpufreq").apply { mkdirs() }
        File(directory, "cpuinfo_max_freq").writeText(text)
    }
}
