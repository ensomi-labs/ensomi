package io.ensomi.android.model

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Installing the model from files the player picked, with the runtime's manifest check stood in for. */
class ModelFilesInstallTest {
    private lateinit var root: File
    private lateinit var folder: File
    private val reserved = mutableListOf<Long>()
    private var verified: File? = null

    @Before
    fun setUp() {
        root = Files.createTempDirectory("models").toFile()
        folder = Files.createTempDirectory("picked").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        folder.deleteRecursively()
    }

    @Test
    fun installsThePinnedPackageWithARecordOfItsSource() {
        val manifest = writePackage(FILES)
        val installer = installer(pin = ModelInstaller.sha256(manifest))
        val progress = mutableListOf<Pair<Long, Long>>()
        val model = installer.installFrom(DirectoryPackageFiles(folder), { done, total -> progress += done to total }, { false })

        assertTrue(model.complete)
        assertEquals(InstallRecord("r2-1.0.1", ModelInstaller.sha256(manifest), InstallRecord.SOURCE_FILES), model.record)
        FILES.forEach { (path, bytes) -> assertArrayEquals(bytes, File(installer.installDirectory, path).readBytes()) }
        assertArrayEquals(manifest, File(installer.installDirectory, ModelInstaller.MANIFEST).readBytes())
        assertEquals(listOf(TOTAL), reserved)
        assertEquals(installer.copyDirectory.name, verified?.name)
        assertFalse(installer.copyDirectory.exists())
        assertEquals(TOTAL to TOTAL, progress.last())
        val found = installer.installed()!!
        assertEquals(InstallRecord.SOURCE_FILES, found.record?.source)
        assertTrue(found.upToDate("r2-1.0.1", ModelInstaller.sha256(manifest)))
        assertFalse(found.upToDate("r2-1.0.1", "0".repeat(64)))
    }

    @Test
    fun anotherPackageThanThePinnedOneIsRefusedBeforeAnythingIsCopied() {
        val manifest = writePackage(FILES)
        val installer = installer(pin = "0".repeat(64))
        val failure = failureOf { installer.installFrom(DirectoryPackageFiles(folder), { _, _ -> }, { false }) }
        assertEquals(ModelFailure.NotPinned(ModelInstaller.sha256(manifest)), failure)
        assertTrue(reserved.isEmpty())
        assertFalse(installer.copyDirectory.exists())
        assertNull(installer.installed())
    }

    @Test
    fun aFolderWithoutTheManifestIsRefused() {
        writePackage(FILES)
        File(folder, ModelInstaller.MANIFEST).delete()
        val failure = failureOf { installer().installFrom(DirectoryPackageFiles(folder), { _, _ -> }, { false }) }
        assertEquals(ModelFailure.NoManifest, failure)
    }

    @Test
    fun aMissingFileIsNamedAndNothingIsCopied() {
        writePackage(FILES)
        File(folder, "arrays/small.bin").delete()
        val installer = installer()
        val failure = failureOf { installer.installFrom(DirectoryPackageFiles(folder), { _, _ -> }, { false }) }
        assertEquals(ModelFailure.Missing("arrays/small.bin"), failure)
        assertTrue(reserved.isEmpty())
        assertFalse(installer.copyDirectory.exists())
    }

    @Test
    fun aFileOfAnotherSizeIsRefusedBeforeAnythingIsCopied() {
        writePackage(FILES)
        File(folder, "big.onnx").appendBytes(byteArrayOf(1))
        val installer = installer()
        assertEquals(
            ModelFailure.Mismatch("big.onnx"),
            failureOf { installer.installFrom(DirectoryPackageFiles(folder), { _, _ -> }, { false }) },
        )
        assertTrue(reserved.isEmpty())
    }

    @Test
    fun aFileWithOtherContentLeavesTheInstalledModelAsItWas() {
        val manifest = writePackage(FILES)
        val installer = installer(pin = ModelInstaller.sha256(manifest))
        installer.installFrom(DirectoryPackageFiles(folder), { _, _ -> }, { false })
        val before = File(installer.installDirectory, "big.onnx").readBytes()

        // The same size, other bytes: only the SHA-256 tells.
        File(folder, "big.onnx").writeBytes(ByteArray(before.size) { 3 })
        val failure = failureOf { installer.installFrom(DirectoryPackageFiles(folder), { _, _ -> }, { false }) }
        assertEquals(ModelFailure.Mismatch("big.onnx"), failure)
        assertFalse(installer.copyDirectory.exists())
        assertArrayEquals(before, File(installer.installDirectory, "big.onnx").readBytes())
        assertTrue(installer.installed()!!.complete)
    }

    @Test
    fun aCopyCutShortLeavesNoHalfInstalledModel() {
        writePackage(FILES)
        val installer = installer()
        val cutShort = object : PackageFiles by DirectoryPackageFiles(folder) {
            override fun open(path: String): InputStream {
                val input = File(folder, path).inputStream()
                if (path != "big.onnx") return input
                return object : FilterInputStream(input) {
                    var read = 0
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (read > 100_000) throw IOException("The provider went away.")
                        return super.read(b, off, len).also { if (it > 0) read += it }
                    }
                }
            }
        }
        val failure = failureOf { installer.installFrom(cutShort, { _, _ -> }, { false }) }
        assertEquals(ModelFailure.Unreadable("The provider went away."), failure)
        assertFalse(installer.copyDirectory.exists())
        assertFalse(installer.installDirectory.exists())
        assertNull(installer.installed())
    }

    @Test
    fun aFileLongerThanListedIsRefusedWhileItIsCopied() {
        writePackage(FILES)
        val installer = installer()
        // The folder says the listed size, but the stream goes on.
        val longer = object : PackageFiles by DirectoryPackageFiles(folder) {
            override fun open(path: String): InputStream =
                if (path == "small.bin") (File(folder, path).readBytes() + ByteArray(10)).inputStream() else File(folder, path).inputStream()
        }
        assertEquals(ModelFailure.Mismatch("small.bin"), failureOf { installer.installFrom(longer, { _, _ -> }, { false }) })
        assertFalse(installer.copyDirectory.exists())
    }

    @Test
    fun cancellingDeletesTheCopy() {
        writePackage(FILES)
        val installer = installer()
        var seen = 0L
        try {
            installer.installFrom(DirectoryPackageFiles(folder), { done, _ -> seen = done }, { seen > 1_000 })
            fail("The install should have stopped")
        } catch (e: CancellationException) {
            // Expected.
        }
        assertFalse(installer.copyDirectory.exists())
        assertNull(installer.installed())
    }

    @Test
    fun theRuntimeMustOpenTheCopyBeforeItIsInstalled() {
        writePackage(FILES)
        val installer = installer(verify = { throw IllegalStateException("graph missing") })
        assertEquals(
            ModelFailure.NotOpened("graph missing"),
            failureOf { installer.installFrom(DirectoryPackageFiles(folder), { _, _ -> }, { false }) },
        )
        assertFalse(installer.copyDirectory.exists())
        assertNull(installer.installed())
    }

    @Test
    fun noSpaceStopsBeforeAnyFileIsCopied() {
        writePackage(FILES)
        val installer = installer(reserve = { throw ModelDownloadException(ModelFailure.NoSpace(it, 10)) })
        assertEquals(
            ModelFailure.NoSpace(TOTAL, 10),
            failureOf { installer.installFrom(DirectoryPackageFiles(folder), { _, _ -> }, { false }) },
        )
        assertFalse(installer.copyDirectory.exists())
    }

    @Test
    fun aRecordWrittenBeforeSourcesWereRecordedStillReads() {
        val manifest = writePackage(FILES)
        val installer = installer()
        installer.installFrom(DirectoryPackageFiles(folder), { _, _ -> }, { false })
        File(installer.installDirectory, ModelInstaller.RECORD)
            .writeText("{\"revision\":\"r2-1.0.1\",\"manifest_sha256\":\"${ModelInstaller.sha256(manifest)}\"}")
        val record = installer.installed()!!.record!!
        assertNull(record.source)
        assertEquals("r2-1.0.1", record.revision)
    }

    private fun installer(
        pin: String? = null,
        verify: (File) -> Unit = { verified = it },
        reserve: (Long) -> Unit = { reserved += it },
    ) = ModelInstaller(
        root = root,
        baseUrl = "http://127.0.0.1:9/onnx/",
        revision = "r2-1.0.1",
        checkManifest = ::check,
        pinnedManifestSha256 = pin,
        verify = verify,
        reserve = reserve,
    )

    private fun failureOf(block: () -> Unit): ModelFailure {
        try {
            block()
        } catch (e: ModelDownloadException) {
            return e.failure
        }
        throw AssertionError("The install should have failed")
    }

    /** Writes [files] and their manifest into the picked folder; returns the manifest's bytes. */
    private fun writePackage(files: Map<String, ByteArray>): ByteArray {
        files.forEach { (path, bytes) -> File(folder, path).apply { parentFile?.mkdirs() }.writeBytes(bytes) }
        val manifest = Manifest(
            format = "ensomi-package/1",
            license = "MIT",
            notices = listOf("NOTICE.txt"),
            files = files.map { (path, bytes) -> ManifestFile(path, bytes.size.toLong(), ModelInstaller.sha256(bytes)) },
        )
        val bytes = JSON.encodeToString(Manifest.serializer(), manifest).toByteArray()
        File(folder, ModelInstaller.MANIFEST).writeBytes(bytes)
        return bytes
    }

    private fun check(json: String): ModelPackage {
        val manifest = JSON.decodeFromString(Manifest.serializer(), json)
        val files = manifest.files.map { ModelFile(it.path, it.size, it.sha256) }.sortedBy { it.path }
        return ModelPackage("Ensomi R2", "r2-1.0", manifest.license, manifest.notices, files, files.sumOf { it.size })
    }

    @Serializable
    private data class ManifestFile(val path: String, val size: Long, val sha256: String)

    @Serializable
    private data class Manifest(val format: String, val license: String, val notices: List<String>, val files: List<ManifestFile>)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val FILES = mapOf(
            "NOTICE.txt" to "MIT licence".toByteArray(),
            "runtime.json" to "{\"model\":\"r2\"}".toByteArray(),
            "big.onnx" to ByteArray(200_000) { (it * 7 % 251).toByte() },
            "arrays/small.bin" to ByteArray(4_096) { it.toByte() },
            "small.bin" to ByteArray(1_000) { (255 - it % 256).toByte() },
        )
        val TOTAL = FILES.values.sumOf { it.size.toLong() }
    }
}
