package io.ensomi.android.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The model download against a local HTTP server (the JDK's), with the runtime's manifest check stood in for. */
class ModelInstallerTest {
    private lateinit var server: HttpServer
    private lateinit var root: File
    private val published = Collections.synchronizedMap(mutableMapOf<String, ByteArray>())
    private val requests = Collections.synchronizedList(mutableListOf<String>())

    /** Paths whose next answer stops after this many bytes, as a dropped connection. */
    private val dropAfter = Collections.synchronizedMap(mutableMapOf<String, Int>())
    private var honourRange = true

    @Before
    fun start() {
        root = Files.createTempDirectory("models").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/onnx/") { exchange -> serve(exchange) }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        root.deleteRecursively()
    }

    @Test
    fun downloadsChecksAndInstallsWithARecordOfTheTag() {
        publish(FILES)
        val reserved = mutableListOf<Long>()
        val installer = installer(reserve = { reserved += it })
        val progress = mutableListOf<Pair<Long, Long>>()
        val model = installer.download({ done, total -> progress += done to total }, { false })

        assertTrue(model.complete)
        assertEquals("r2-1.0.1", model.record?.revision)
        assertEquals(ModelInstaller.sha256(published.getValue("onnx/${ModelInstaller.MANIFEST}")), model.record?.manifestSha256)
        FILES.forEach { (path, bytes) -> assertArrayEquals(bytes, File(installer.installDirectory, path).readBytes()) }
        assertTrue(File(installer.installDirectory, ModelInstaller.RECORD).isFile)
        assertFalse(installer.stagingDirectory.exists())
        assertEquals(listOf(TOTAL), reserved)
        assertEquals(TOTAL to TOTAL, progress.last())
        assertTrue(installer.installed()!!.upToDate("r2-1.0.1"))
        assertFalse(installer.installed()!!.upToDate("r2-1.0.2"))
    }

    @Test
    fun aDroppedConnectionKeepsThePartAndTheResumeAsksForTheRest() {
        publish(FILES)
        dropAfter["onnx/big.onnx"] = 70_000
        val installer = installer()
        val failure = failureOf { installer.download({ _, _ -> }, { false }) }
        assertTrue(failure is ModelFailure.Network)
        assertNull(installer.installed())
        val (done, total) = installer.staged()!!
        assertEquals(TOTAL, total)
        assertTrue("$done", done >= 70_000)

        requests.clear()
        installer.download({ _, _ -> }, { false })
        assertTrue(requests.toString(), "big.onnx range=70000" in requests)
        assertArrayEquals(FILES.getValue("big.onnx"), File(installer.installDirectory, "big.onnx").readBytes())
        // Files finished before the drop are not fetched again.
        assertFalse(requests.toString(), requests.any { it.startsWith("NOTICE.txt") })
    }

    @Test
    fun aServerThatIgnoresTheRangeRewritesTheFile() {
        publish(FILES)
        dropAfter["onnx/big.onnx"] = 50_000
        val installer = installer()
        failureOf { installer.download({ _, _ -> }, { false }) }
        honourRange = false
        installer.download({ _, _ -> }, { false })
        assertArrayEquals(FILES.getValue("big.onnx"), File(installer.installDirectory, "big.onnx").readBytes())
    }

    @Test
    fun aFileThatChangedSinceTheStopIsFetchedAgainWhole() {
        publish(FILES)
        dropAfter["onnx/big.onnx"] = 60_000
        val installer = installer()
        failureOf { installer.download({ _, _ -> }, { false }) }
        // A new tag publishes another big.onnx, and its manifest says so.
        val changed = FILES + ("big.onnx" to ByteArray(150_000) { (it * 31 % 253).toByte() })
        publish(changed)
        requests.clear()
        installer.download({ _, _ -> }, { false })
        assertEquals(listOf("big.onnx range=60000", "big.onnx range=0"), requests.filter { it.startsWith("big.onnx") })
        assertArrayEquals(changed.getValue("big.onnx"), File(installer.installDirectory, "big.onnx").readBytes())
    }

    @Test
    fun aFileThatDoesNotMatchItsManifestStopsTheDownload() {
        publish(FILES)
        published["onnx/runtime.json"] = "{\"changed\":true}".toByteArray()
        val failure = failureOf { installer().download({ _, _ -> }, { false }) }
        assertEquals(ModelFailure.Mismatch("runtime.json"), failure)
    }

    @Test
    fun aRefusedManifestFetchesNothingElse() {
        publish(FILES, format = "ensomi-package/9")
        val installer = installer()
        val failure = failureOf { installer.download({ _, _ -> }, { false }) }
        assertTrue(failure is ModelFailure.Refused)
        assertEquals(listOf("ensomi-package.json range=0"), requests)
        assertNull(installer.installed())
    }

    @Test
    fun theDownloadAcceptsOnlyThePinnedManifest() {
        publish(FILES)
        val manifest = published.getValue("onnx/${ModelInstaller.MANIFEST}")
        val refusing = installer(pin = "0".repeat(64))
        assertEquals(ModelFailure.NotPinned(ModelInstaller.sha256(manifest)), failureOf { refusing.download({ _, _ -> }, { false }) })
        assertEquals(listOf("ensomi-package.json range=0"), requests)
        assertNull(refusing.installed())

        val model = installer(pin = ModelInstaller.sha256(manifest)).download({ _, _ -> }, { false })
        assertEquals(InstallRecord.SOURCE_HUGGING_FACE, model.record?.source)
        assertTrue(model.upToDate("r2-1.0.1", ModelInstaller.sha256(manifest)))
    }

    @Test
    fun aMissingFileOnTheServerIsAnHttpFailure() {
        publish(FILES)
        published.remove("onnx/small.bin")
        assertEquals(ModelFailure.Http(404), failureOf { installer().download({ _, _ -> }, { false }) })
    }

    @Test
    fun downloadingAgainCopiesWhatTheInstallHasAndDropsWhatThePackageNoLongerLists() {
        publish(FILES)
        val installer = installer()
        installer.download({ _, _ -> }, { false })
        File(installer.installDirectory, "ensomi-model.json").writeText("{}")
        requests.clear()
        installer.download({ _, _ -> }, { false })
        assertEquals(listOf("ensomi-package.json range=0"), requests)
        assertFalse(File(installer.installDirectory, "ensomi-model.json").exists())
        assertTrue(installer.installed()!!.complete)
    }

    @Test
    fun cancellingKeepsThePartForAResume() {
        publish(FILES)
        val installer = installer()
        var seen = 0L
        try {
            installer.download({ done, _ -> seen = done }, { seen > 40_000 })
            fail("The download should have stopped")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // Expected.
        }
        assertNotNull(installer.staged())
        installer.download({ _, _ -> }, { false })
        assertTrue(installer.installed()!!.complete)
    }

    @Test
    fun noSpaceStopsBeforeAnyFileIsFetched() {
        publish(FILES)
        val installer = installer(reserve = { throw ModelDownloadException(ModelFailure.NoSpace(it, 10)) })
        assertEquals(ModelFailure.NoSpace(TOTAL, 10), failureOf { installer.download({ _, _ -> }, { false }) })
        assertEquals(listOf("ensomi-package.json range=0"), requests)
    }

    @Test
    fun fullyStagedPinnedPackageResumesWithoutAnyNetworkRequests() {
        publish(FILES)
        val manifest = published.getValue("onnx/${ModelInstaller.MANIFEST}")
        val installer = installer(pin = ModelInstaller.sha256(manifest))
        stage(installer, manifest)
        installer.download({ _, _ -> }, { false })
        assertTrue(requests.isEmpty())
        assertTrue(installer.installed()!!.complete)
    }

    @Test
    fun aDifferentStagedManifestIsFetchedAgain() {
        publish(FILES)
        val manifest = published.getValue("onnx/${ModelInstaller.MANIFEST}")
        val installer = installer(pin = ModelInstaller.sha256(manifest))
        stage(installer, "not the pin".toByteArray())
        installer.download({ _, _ -> }, { false })
        assertEquals(listOf("ensomi-package.json range=0"), requests)
    }

    @Test
    fun localWriteFailureIsStorageAndKeepsStaging() {
        publish(FILES)
        val installer = installer(output = { _, _ -> throw IOException("Disk full") })
        assertEquals(ModelFailure.Storage("Disk full"), failureOf { installer.download({ _, _ -> }, { false }) })
        assertTrue(File(installer.stagingDirectory, ModelInstaller.MANIFEST).exists())
    }

    @Test
    fun verificationErrorIsTypedAndKeepsEveryStagedFile() {
        publish(FILES)
        val installer = installer(onPhase = { phase, _, _ -> if (phase == InstallPhase.VERIFYING && File(root, ".download-ensomi-r2/big.onnx").exists()) throw AssertionError("Lumen verification") })
        assertEquals(ModelFailure.Stage(InstallPhase.VERIFYING, "Lumen verification"), failureOf { installer.download({ _, _ -> }, { false }) })
        FILES.forEach { (path, bytes) -> assertArrayEquals(bytes, File(installer.stagingDirectory, path).readBytes()) }
        assertNull(installer.installed())
    }

    @Test
    fun uniffiInternalExceptionInVerificationIsTypedAndRetainsStaging() {
        publish(FILES)
        var checking = false
        val installer = ModelInstaller(
            root, "http://127.0.0.1:${server.address.port}/onnx/", "lumen-1",
            checkManifest = { json ->
                if (checking) throw io.ensomi.runtime.InternalException("Lumen runtime error")
                check(json)
            },
            afterStaging = { checking = true },
        )
        assertEquals(ModelFailure.Stage(InstallPhase.VERIFYING, "Lumen runtime error"), failureOf { installer.download({ _, _ -> }, { false }) })
        assertEquals(TOTAL, FILES.keys.sumOf { File(installer.stagingDirectory, it).length() })
        assertFalse(installer.installDirectory.exists())
    }

    @Test
    fun cancelAlreadyRequestedAtTransferEndPreventsCommit() {
        publish(FILES)
        val installer = installer()
        var cancelled = false
        try {
            installer.download({ done, total -> if (done == total) cancelled = true }, { cancelled })
            fail("Cancelled transfer must not commit")
        } catch (_: kotlin.coroutines.cancellation.CancellationException) {
            assertNull(installer.installed())
            assertEquals(TOTAL to TOTAL, installer.staged())
        }
    }

    @Test
    fun cancelAfterCommitBeginsDoesNotContradictInstalledFiles() {
        publish(FILES)
        var cancelled = false
        val installer = installer(onPhase = { phase, _, _ -> if (phase == InstallPhase.COMMITTING) cancelled = true })
        val result = installer.download({ _, _ -> }, { cancelled })
        assertTrue(result.complete)
        assertTrue(installer.installed()!!.complete)
    }

    @Test
    fun downloadOnlyChecksThePackageAndNeverNeedsAnOpen() {
        publish(FILES)
        val phases = mutableListOf<InstallPhase>()
        val installer = installer(onPhase = { phase, _, _ -> phases += phase })
        assertTrue(installer.download({ _, _ -> }, { false }).complete)
        assertEquals(listOf(InstallPhase.STAGING, InstallPhase.VERIFYING, InstallPhase.COMMITTING), phases.distinct())
    }

    @Test
    fun replacementWaitsForTheSharedModelAccessGate() = kotlinx.coroutines.runBlocking {
        publish(FILES)
        val gate = kotlinx.coroutines.sync.Mutex(locked = true)
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        val installer = ModelInstaller(
            root, "http://127.0.0.1:${server.address.port}/onnx/", "lumen-1", ::check,
            commit = { work ->
                kotlinx.coroutines.runBlocking {
                    waiting.complete(Unit)
                    gate.lock()
                    try { work() } finally { gate.unlock() }
                }
            },
        )
        val result = async(kotlinx.coroutines.Dispatchers.IO) {
            installer.download({ _, _ -> }, { false })
        }
        kotlinx.coroutines.withTimeout(5_000) { waiting.await() }
        assertFalse(installer.installDirectory.exists())
        assertTrue(installer.stagingDirectory.exists())
        gate.unlock()
        assertTrue(kotlinx.coroutines.withTimeout(5_000) { result.await() }.complete)
    }

    @Test
    fun oversizedManifestStopsAtTheLimit() {
        published["onnx/${ModelInstaller.MANIFEST}"] = ByteArray(2 shl 20) { 32 }
        assertTrue(failureOf { installer().download({ _, _ -> }, { false }) } is ModelFailure.Refused)
    }

    private fun stage(installer: ModelInstaller, manifest: ByteArray) {
        installer.stagingDirectory.mkdirs()
        File(installer.stagingDirectory, ModelInstaller.MANIFEST).writeBytes(manifest)
        FILES.forEach { (path, bytes) -> File(installer.stagingDirectory, path).apply { parentFile?.mkdirs(); writeBytes(bytes) } }
    }

    private fun installer(
        reserve: (Long) -> Unit = {},
        pin: String? = null,
        onPhase: (InstallPhase, Int, Int) -> Unit = { _, _, _ -> },
        output: (File, Boolean) -> java.io.OutputStream = { file, append -> java.io.FileOutputStream(file, append) },
    ) = ModelInstaller(
        root = root,
        baseUrl = "http://127.0.0.1:${server.address.port}/onnx/",
        revision = "r2-1.0.1",
        checkManifest = ::check,
        pinnedManifestSha256 = pin,
        reserve = reserve,
        onPhase = onPhase,
        output = output,
    )

    private fun failureOf(block: () -> Unit): ModelFailure {
        try {
            block()
        } catch (e: ModelDownloadException) {
            return e.failure
        }
        throw AssertionError("The download should have failed")
    }

    private fun publish(files: Map<String, ByteArray>, format: String = "ensomi-package/1") {
        files.forEach { (path, bytes) -> published["onnx/$path"] = bytes }
        val manifest = Manifest(
            format = format,
            license = "MIT",
            notices = listOf("NOTICE.txt"),
            files = files.map { (path, bytes) -> ManifestFile(path, bytes.size.toLong(), ModelInstaller.sha256(bytes)) },
        )
        published["onnx/${ModelInstaller.MANIFEST}"] = JSON.encodeToString(Manifest.serializer(), manifest).toByteArray()
    }

    /** Stands in for the runtime's checkPackage: the format, then the files. */
    private fun check(json: String): ModelPackage {
        val manifest = JSON.decodeFromString(Manifest.serializer(), json)
        if (manifest.format != "ensomi-package/1") {
            throw ModelDownloadException(ModelFailure.Refused("unsupported format ${manifest.format}"))
        }
        val files = manifest.files.map { ModelFile(it.path, it.size, it.sha256) }.sortedBy { it.path }
        return ModelPackage("Ensomi R2", "r2-1.0", manifest.license, manifest.notices, files, files.sumOf { it.size })
    }

    private fun serve(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix("/")
        val range = exchange.requestHeaders.getFirst("Range")
        val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
        requests += "${path.removePrefix("onnx/")} range=$from"
        val bytes = published[path]
        try {
            when {
                bytes == null -> exchange.sendResponseHeaders(404, -1)
                range != null && honourRange && from >= bytes.size -> exchange.sendResponseHeaders(416, -1)
                else -> {
                    val start = if (range != null && honourRange) from.toInt() else 0
                    if (range != null && honourRange) {
                        exchange.responseHeaders.add("Content-Range", "bytes $start-${bytes.size - 1}/${bytes.size}")
                    }
                    val body = bytes.copyOfRange(start, bytes.size)
                    exchange.sendResponseHeaders(if (range != null && honourRange) 206 else 200, body.size.toLong())
                    val drop = dropAfter.remove(path)
                    exchange.responseBody.write(body, 0, if (drop != null) minOf(drop, body.size) else body.size)
                    exchange.responseBody.flush()
                }
            }
        } catch (e: IOException) {
            // The client went away.
        } finally {
            try {
                exchange.close()
            } catch (e: IOException) {
                // A dropped answer leaves bytes unwritten.
            }
        }
    }

    @Serializable
    private data class ManifestFile(val path: String, val size: Long, val sha256: String)

    @Serializable
    private data class Manifest(
        val format: String,
        val license: String,
        val notices: List<String>,
        val files: List<ManifestFile>,
    )

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
