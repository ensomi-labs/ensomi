package io.ensomi.android.checks

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ensomi.android.model.DirectoryPackageFiles
import io.ensomi.android.model.InstallRecord
import io.ensomi.android.model.ModelDownloadException
import io.ensomi.android.model.ModelFailure
import io.ensomi.android.model.ModelFile
import io.ensomi.android.model.ModelInstaller
import io.ensomi.android.model.ModelPackage
import io.ensomi.android.model.ModelSource
import io.ensomi.android.model.PackageFiles
import io.ensomi.runtime.GeneratorException
import io.ensomi.runtime.RuntimeWorker
import io.ensomi.runtime.checkPackage
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The model the app installed, checked again on the phone: the pinned package, how it came (downloaded or from files),
 * every file's SHA-256 against the manifest, and the time the runtime takes to open it. Then the install from files,
 * the way the app does it for a picked folder but from the installed model's directory, with the runtime's own
 * `checkPackage` and generator: a whole install into a scratch folder, and the refusals that must leave nothing behind.
 */
@RunWith(AndroidJUnit4::class)
class ModelCheck {
    private val context get() = PhoneCheck.context
    private val worker = RuntimeWorker()
    private lateinit var scratch: File

    @Before
    fun setUp() {
        scratch = File(context.cacheDir, "model-check").also { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
        worker.close()
    }

    @Test
    fun theInstalledModelIsThePinnedPackage() = runBlocking {
        val installer = installer(File(context.noBackupFilesDir, "models"))
        val model = installer.installed()
        PhoneCheck.report("model-installed", linkedMapOf("installed" to (model != null)))
        assumeTrue("No model installed: the script installs one first", model != null)
        model!!
        val started = System.nanoTime()
        val wrong = model.pkg.files.filter { file ->
            val local = File(installer.installDirectory, file.path)
            local.length() != file.size || PhoneCheck.sha256(local) != file.sha256
        }
        val hashSeconds = (System.nanoTime() - started) / 1e9
        val manifestSha = PhoneCheck.sha256(File(installer.installDirectory, ModelInstaller.MANIFEST).readBytes())
        val openStarted = System.nanoTime()
        val capabilities = worker.withGenerator(context, installer.installDirectory) { capabilities() }
        val openMs = (System.nanoTime() - openStarted) / 1e6
        val pinned = manifestSha == ModelSource.PINNED_MANIFEST_SHA256
        PhoneCheck.report(
            "model-integrity",
            linkedMapOf(
                "model" to "${model.pkg.modelName} ${model.pkg.modelVersion}",
                "revision" to model.record?.revision,
                "source" to model.record?.source,
                "manifestSha256" to manifestSha,
                "pinned" to pinned,
                "files" to model.pkg.files.size,
                "bytes" to model.pkg.totalBytes,
                "filesNotMatching" to wrong.map { it.path },
                "hashSeconds" to PhoneCheck.round(hashSeconds, 2),
                "hashMBps" to PhoneCheck.round(model.pkg.totalBytes / 1e6 / hashSeconds, 1),
                "openMs" to PhoneCheck.round(openMs, 1),
                "starRange" to listOf(capabilities.minStar, capabilities.maxStarExclusive),
                "ok" to (pinned && wrong.isEmpty() && model.complete),
            ),
        )
        assertTrue("The installed manifest is not the pinned one: $manifestSha", pinned)
        assertTrue("Files not matching the manifest: $wrong", wrong.isEmpty())
    }

    @Test
    fun installsFromFilesWithTheRuntimesChecks() = runBlocking {
        val installed = File(context.noBackupFilesDir, "models/ensomi-r2")
        assumeTrue("No model installed to install from", File(installed, ModelInstaller.MANIFEST).isFile)
        val installer = installer(scratch)
        val files = DirectoryPackageFiles(installed)

        // A manifest that is not the pinned one is refused before anything is copied.
        val edited = object : PackageFiles by files {
            override fun manifest(): ByteArray? = files.manifest()?.plus(' '.code.toByte())
        }
        val notPinned = failure { installer.installFrom(edited, { _, _ -> }, { false }) }
        assertTrue("$notPinned", notPinned is ModelFailure.NotPinned)
        assertFalse(installer.copyDirectory.exists())

        // A copy cut short, as a provider that goes away, leaves no model and no copy.
        val cutShort = object : PackageFiles by files {
            override fun open(path: String): InputStream = files.open(path).let { input ->
                if (path != "beatthis.onnx") input else object : FilterInputStream(input) {
                    var read = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (read > 10_000_000) throw IOException("The provider went away.")
                        return super.read(b, off, len).also { if (it > 0) read += it }
                    }
                }
            }
        }
        val interrupted = failure { installer.installFrom(cutShort, { _, _ -> }, { false }) }
        assertTrue("$interrupted", interrupted is ModelFailure.Unreadable)
        assertFalse(installer.copyDirectory.exists())
        assertFalse(installer.installDirectory.exists())

        // The whole package: copied, checked, opened by the runtime, recorded as from files.
        val started = System.nanoTime()
        val model = installer.installFrom(files, { _, _ -> }, { false })
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(InstallRecord.SOURCE_FILES, model.record?.source)
        assertEquals(ModelSource.PINNED_MANIFEST_SHA256, model.record?.manifestSha256)
        assertNotNull(installer.installed())
        assertFalse(installer.copyDirectory.exists())

        // A file whose bytes differ, at the same size, is refused, and the model installed before stays whole.
        val flipped = object : PackageFiles by files {
            override fun open(path: String): InputStream = files.open(path).let { input ->
                if (path != "runtime.json") input else object : FilterInputStream(input) {
                    var first = true
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                        if (first && n > 0) {
                            b[off] = (b[off].toInt() xor 1).toByte()
                            first = false
                        }
                    }
                }
            }
        }
        val mismatch = failure { installer.installFrom(flipped, { _, _ -> }, { false }) }
        assertEquals(ModelFailure.Mismatch("runtime.json"), mismatch)
        assertFalse(installer.copyDirectory.exists())
        assertTrue(installer.installed()!!.complete)

        PhoneCheck.report(
            "model-install-from-files",
            linkedMapOf(
                "seconds" to PhoneCheck.round(seconds, 2),
                "MBps" to PhoneCheck.round(model.pkg.totalBytes / 1e6 / seconds, 1),
                "notPinned" to notPinned.toString(),
                "interrupted" to interrupted.toString(),
                "mismatch" to mismatch.toString(),
                "ok" to true,
            ),
        )
    }

    private fun installer(root: File) = ModelInstaller(
        root = root,
        baseUrl = ModelSource.BASE_URL,
        revision = ModelSource.PINNED_REVISION,
        checkManifest = ::check,
        pinnedManifestSha256 = ModelSource.PINNED_MANIFEST_SHA256,
        verify = { directory -> runBlocking { worker.withGenerator(context, directory) { capabilities() } } },
    )

    /** The runtime's `checkPackage` on its worker, as the app's store calls it. */
    private fun check(json: String): ModelPackage = try {
        val info = runBlocking { worker.run { checkPackage(json) } }
        val files = info.files.map { ModelFile(it.path, it.size.toLong(), it.sha256) }
        ModelPackage(info.model.name, info.model.version, info.license, info.notices, files, info.totalBytes.toLong())
    } catch (e: GeneratorException) {
        throw ModelDownloadException(ModelFailure.Refused(e.message ?: e.javaClass.simpleName), e)
    }

    private fun failure(block: () -> Unit): ModelFailure {
        try {
            block()
        } catch (e: ModelDownloadException) {
            return e.failure
        }
        throw AssertionError("The install should have been refused")
    }
}
