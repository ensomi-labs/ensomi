package io.ensomi.android.chart

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreparedStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "prepared")
    private fun PreparedStore.put(key: String, value: String) = write(key) { it.write(value.toByteArray()) }
    private fun PreparedStore.get(key: String) = read(key) { String(it) }

    @Test fun hitSurvivesAStoreRestartAndKeysIncludeRevision() {
        PreparedStore(root).put("audio/model-a", "stages")
        val again = PreparedStore(root)
        assertEquals("stages", again.get("audio/model-a"))
        assertNull(again.get("audio/model-b"))
        assertNull(again.get("different-audio/model-a"))
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test fun missingDirectoryIsAMiss() {
        assertNull(PreparedStore(root).get("missing"))
    }

    @Test fun corruptFileIsDeleted() {
        val store = PreparedStore(root)
        store.put("key", "stages")
        root.listFiles()!!.single().writeBytes(byteArrayOf())
        assertNull(store.get("key"))
        assertFalse(store.contains("key"))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun runtimeRefusalIsAMissAndDeletesTheFile() {
        val store = PreparedStore(root)
        store.put("key", "old format or model")
        assertNull(store.read<String>("key") { throw IllegalArgumentException("refused by runtime") })
        assertFalse(store.contains("key"))
        assertTrue(store.put("key", "new stages"))
        assertEquals("new stages", store.get("key"))
    }

    @Test fun partialWriteCannotReplaceACompleteFile() {
        val store = PreparedStore(root)
        store.put("key", "before")
        assertFalse(store.write("key") {
            it.write("partial".toByteArray())
            // A second reader sees the old file until the atomic rename.
            assertEquals("before", store.get("key"))
            throw IOException("disk full")
        })
        assertEquals("before", store.get("key"))
        assertEquals(1, root.listFiles()!!.size)
        assertTrue(store.put("key", "after"))
        assertEquals("after", PreparedStore(root).get("key"))
    }

    @Test fun abandonedTemporaryFileIsNeverRead() {
        root.mkdirs()
        File(root, "interrupted.tmp").writeText("partial")
        val store = PreparedStore(root)
        assertNull(store.get("interrupted"))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun evictsLeastRecentlyUsedIncludingReadsAndSurvivesRestart() {
        val store = PreparedStore(root, limitBytes = 6)
        store.put("a", "aaa")
        store.put("b", "bbb")
        assertEquals("aaa", store.get("a"))
        store.put("c", "ccc")
        val again = PreparedStore(root, limitBytes = 6)
        assertEquals("aaa", again.get("a"))
        assertNull(again.get("b"))
        assertEquals("ccc", again.get("c"))
        assertEquals(6, root.listFiles()!!.sumOf { it.length() }.toInt())
    }

    @Test fun oversizedWriteLeavesPreviousFileIntact() {
        val store = PreparedStore(root, limitBytes = 3)
        store.put("a", "old")
        assertFalse(store.put("a", "too large"))
        assertEquals("old", store.get("a"))
    }
}
