package io.ensomi.android.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File

class ModelRecordsTest {
    @Test
    fun `a record survives a write and a read`() {
        val directory = createTempDir()
        val record = InstalledModel(
            modelId = "ensomi-r2",
            modelName = "Ensomi R2",
            version = "r2-1.0",
            license = "MIT",
            fileCount = 30,
            totalBytes = 120_377_244,
            installedAtMs = 1_700_000_000_000,
            source = "folder",
        )

        ModelRecords.write(directory, record)
        val read = ModelRecords.read(directory)

        assertEquals(record, read)
        assertTrue(File(directory, ModelRecords.FILE).isFile)
    }

    @Test
    fun `a missing record is not an installed model`() {
        val directory = createTempDir()
        assertNull(ModelRecords.read(directory))
    }

    @Test
    fun `a corrupt record reads as absent rather than throwing`() {
        val directory = createTempDir()
        File(directory, ModelRecords.FILE).writeText("{ this is not json")

        assertNull(ModelRecords.read(directory))
    }

    @Test
    fun `a record written by a newer client keeps its known fields`() {
        val directory = createTempDir()
        File(directory, ModelRecords.FILE).writeText(
            """{"modelId":"ensomi-r2","modelName":"Ensomi R2","version":"r2-1.1","license":"MIT",""" +
                """"fileCount":31,"totalBytes":1,"installedAtMs":2,"source":"mirror","unknown":"ignored"}""",
        )

        val read = ModelRecords.read(directory)

        assertEquals("r2-1.1", read?.version)
        assertEquals(31, read?.fileCount)
    }

    private fun createTempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "ensomi-model-${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }
}
