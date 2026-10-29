package io.ensomi.android.model

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.util.Log
import io.ensomi.android.R
import io.ensomi.android.json.Json
import io.ensomi.runtime.GeneratorException
import io.ensomi.runtime.RuntimeWorker
import io.ensomi.runtime.checkPackage
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The chart model in app storage under `noBackupFilesDir/models`, installed by [ModelInstaller] from
 * [ModelSource] while the app is open, or from the package's files in a folder the player picked. Free space comes
 * from `StorageManager.getAllocatableBytes`, and an install asks `allocateBytes` for what it still has to write, which
 * frees the system's caches if it must.
 *
 * When the app starts with an install that is not the pinned package or misses a file, it downloads the pinned tag
 * over it; the install stays in use until the download is in place. Otherwise a download runs only when asked: a
 * stopped one waits for Resume.
 *
 * Each install's outcome, and its start, goes to logcat as one JSON line under [INSTALL_TAG], for scripts.
 */
class DownloadingChartModelStore(context: Context, private val scope: CoroutineScope) : ChartModelStore {
    private val appContext = context.applicationContext
    private val root = File(appContext.noBackupFilesDir, "models")
    private val storage = appContext.getSystemService(StorageManager::class.java)
    private val worker = RuntimeWorker()
    private val installer = ModelInstaller(
        root = root,
        baseUrl = ModelSource.BASE_URL,
        revision = ModelSource.PINNED_REVISION,
        checkManifest = ::check,
        pinnedManifestSha256 = ModelSource.PINNED_MANIFEST_SHA256,
        verify = ::open,
        reserve = ::reserve,
        log = { Log.i(TAG, it) },
    )
    private val mutableState = MutableStateFlow<ModelState>(ModelState.Checking)
    private val lock = Any()
    private var installed: InstalledModel? = null
    private var running: Running? = null

    private class Running(val job: Job, val cancelled: AtomicBoolean)

    override val state: StateFlow<ModelState> = mutableState.asStateFlow()

    override val directory: File?
        get() = installer.installDirectory.takeIf { mutableState.value is ModelState.Installed }

    override val notices: List<Notice>
        get() = installed?.pkg?.notices.orEmpty().map { Notice(it, asset = false) } + ONNX_RUNTIME_NOTICES

    init {
        scope.launch {
            val found = installer.installed()
            synchronized(lock) { installed = found }
            when {
                found != null -> {
                    mutableState.value = installedState(found, update = null)
                    if (!upToDate(found)) {
                        Log.i(TAG, "The install is not ${ModelSource.PINNED_REVISION} with every file: updating it")
                        download()
                    }
                }
                else -> mutableState.value = idleState(failure = null)
            }
        }
    }

    override fun download() {
        synchronized(lock) {
            if (running?.job?.isActive == true && running?.cancelled?.get() == false) return
            launch { run(it) }
        }
    }

    override fun installFromFolder(folder: Uri) {
        synchronized(lock) {
            // The player picked files, so the download under way, if any, gives way.
            running?.cancelled?.set(true)
            launch { copy(folder, it) }
        }
    }

    /** Runs [work] after the install before it has ended. Call it holding [lock]. */
    private fun launch(work: (AtomicBoolean) -> Unit) {
        val previous = running
        val cancelled = AtomicBoolean(false)
        val job = scope.launch {
            previous?.job?.join()
            work(cancelled)
        }
        running = Running(job, cancelled)
    }

    override fun cancel() {
        val current = synchronized(lock) { running } ?: return
        if (!current.cancelled.compareAndSet(false, true)) return
        // The state says so before the download notices, between chunks; what is staged is read off the main thread.
        scope.launch { mutableState.value = stoppedState(failure = null) }
    }

    override fun remove() {
        val current = synchronized(lock) { running }
        current?.cancelled?.set(true)
        scope.launch {
            current?.job?.join()
            installer.remove()
            synchronized(lock) { installed = null }
            mutableState.value = ModelState.Absent(ModelSource.PACKAGE_BYTES, freeBytes())
        }
    }

    private fun run(cancelled: AtomicBoolean) {
        if (cancelled.get()) return
        val started = System.nanoTime()
        logInstall(InstallRecord.SOURCE_HUGGING_FACE, "started", started)
        try {
            val result = installer.download(
                progress = { done, total ->
                    if (!cancelled.get()) mutableState.value = progressState(done, total)
                },
                isCancelled = { cancelled.get() },
            )
            synchronized(lock) { installed = result }
            if (!cancelled.get()) mutableState.value = installedState(result, update = null)
            Log.i(TAG, "Installed ${result.pkg.modelName} ${result.pkg.modelVersion}, package ${result.record?.revision}")
            logInstall(InstallRecord.SOURCE_HUGGING_FACE, "installed", started, result)
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled")
            logInstall(InstallRecord.SOURCE_HUGGING_FACE, "cancelled", started)
            mutableState.value = stoppedState(failure = null)
        } catch (e: ModelDownloadException) {
            Log.w(TAG, "Download stopped: ${e.failure}", e)
            logInstall(InstallRecord.SOURCE_HUGGING_FACE, "stopped", started, failure = describe(e.failure))
            val failure = e.failure
            mutableState.value = if (failure is ModelFailure.NoSpace && synchronized(lock) { installed } == null) {
                ModelState.Absent(failure.neededBytes, failure.freeBytes)
            } else {
                stoppedState(describe(failure))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Download stopped", e)
            val failure = e.message ?: appContext.getString(R.string.model_failure_network)
            logInstall(InstallRecord.SOURCE_HUGGING_FACE, "stopped", started, failure = failure)
            mutableState.value = stoppedState(failure)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Download failed", e)
            val failure = e.message ?: e.javaClass.simpleName
            logInstall(InstallRecord.SOURCE_HUGGING_FACE, "stopped", started, failure = failure)
            mutableState.value = stoppedState(failure)
        }
    }

    /** Installs from the picked [folder]; on any stop the state is what it was before, with the reason. */
    private fun copy(folder: Uri, cancelled: AtomicBoolean) {
        if (cancelled.get()) return
        val started = System.nanoTime()
        logInstall(InstallRecord.SOURCE_FILES, "started", started)
        val failure: String? = try {
            val result = installer.installFrom(
                DocumentTreePackageFiles(appContext.contentResolver, folder),
                progress = { done, total ->
                    if (!cancelled.get()) mutableState.value = progressState(done, total, fromFiles = true)
                },
                isCancelled = { cancelled.get() },
            )
            synchronized(lock) { installed = result }
            mutableState.value = installedState(result, update = null)
            Log.i(TAG, "Installed ${result.pkg.modelName} ${result.pkg.modelVersion} from files, package ${result.record?.revision}")
            logInstall(InstallRecord.SOURCE_FILES, "installed", started, result)
            return
        } catch (e: CancellationException) {
            Log.i(TAG, "Install from files cancelled")
            logInstall(InstallRecord.SOURCE_FILES, "cancelled", started)
            null
        } catch (e: ModelDownloadException) {
            Log.w(TAG, "Install from files stopped: ${e.failure}", e)
            describe(e.failure).also { logInstall(InstallRecord.SOURCE_FILES, "stopped", started, failure = it) }
        } catch (e: IOException) {
            Log.w(TAG, "Install from files stopped", e)
            (e.message ?: e.javaClass.simpleName).also { logInstall(InstallRecord.SOURCE_FILES, "stopped", started, failure = it) }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Install from files failed", e)
            (e.message ?: e.javaClass.simpleName).also { logInstall(InstallRecord.SOURCE_FILES, "stopped", started, failure = it) }
        }
        val model = synchronized(lock) { installed }
        mutableState.value = if (model != null) {
            val update = if (upToDate(model)) {
                null
            } else {
                ModelUpdate.Pending(ModelSource.PINNED_REVISION, failure, resumable = installer.staged() != null)
            }
            installedState(model, update)
        } else {
            idleState(failure = null, filesFailure = failure)
        }
    }

    /** One JSON line per install event under [INSTALL_TAG]: how it is installing, what happened, and when. */
    private fun logInstall(source: String, result: String, startedNanos: Long, model: InstalledModel? = null, failure: String? = null) {
        val line = linkedMapOf<String, Any?>(
            "source" to source,
            "result" to result,
            "seconds" to Math.round((System.nanoTime() - startedNanos) / 1e7) / 100.0,
            "pinnedRevision" to ModelSource.PINNED_REVISION,
        )
        if (model != null) {
            line["revision"] = model.record?.revision
            line["manifestSha256"] = model.record?.manifestSha256
            line["files"] = model.pkg.files.size
            line["bytes"] = model.pkg.totalBytes
        }
        if (failure != null) line["failure"] = failure
        Log.i(INSTALL_TAG, Json.encode(line))
    }

    private fun upToDate(model: InstalledModel) =
        model.upToDate(ModelSource.PINNED_REVISION, ModelSource.PINNED_MANIFEST_SHA256)

    private fun installedState(model: InstalledModel, update: ModelUpdate?) = ModelState.Installed(
        name = "${model.pkg.modelName} ${model.pkg.modelVersion}",
        revision = model.record?.revision,
        sizeBytes = model.pkg.totalBytes,
        license = model.pkg.license,
        update = update ?: if (upToDate(model)) {
            null
        } else {
            ModelUpdate.Pending(ModelSource.PINNED_REVISION, failure = null, resumable = installer.staged() != null)
        },
    )

    private fun progressState(done: Long, total: Long, fromFiles: Boolean = false): ModelState {
        val model = synchronized(lock) { installed }
        return if (model != null) {
            installedState(model, ModelUpdate.Downloading(done, total, fromFiles))
        } else {
            ModelState.Downloading(done, total, fromFiles)
        }
    }

    /** A download that stopped: over an install, the install with the update pending; else Stopped, or Absent. */
    private fun stoppedState(failure: String?): ModelState {
        val model = synchronized(lock) { installed }
        if (model != null) {
            val pending = ModelUpdate.Pending(ModelSource.PINNED_REVISION, failure, resumable = installer.staged() != null)
            return installedState(model, pending)
        }
        return idleState(failure)
    }

    private fun idleState(failure: String?, filesFailure: String? = null): ModelState {
        val staged = installer.staged()
        return if (staged != null) {
            ModelState.Stopped(staged.first, staged.second, failure, filesFailure)
        } else {
            ModelState.Absent(ModelSource.PACKAGE_BYTES, freeBytes(), filesFailure)
        }
    }

    private fun describe(failure: ModelFailure): String = when (failure) {
        is ModelFailure.Network -> appContext.getString(R.string.model_failure_network)
        is ModelFailure.Http -> appContext.getString(R.string.model_failure_http, failure.code)
        is ModelFailure.Refused -> appContext.getString(R.string.model_failure_refused, failure.reason)
        is ModelFailure.Mismatch -> appContext.getString(R.string.model_failure_mismatch, failure.path)
        is ModelFailure.NotOpened -> appContext.getString(R.string.model_failure_not_opened, failure.reason)
        is ModelFailure.NoSpace -> appContext.getString(
            R.string.model_no_space,
            megabytes(failure.neededBytes),
            megabytes(failure.freeBytes),
        )
        is ModelFailure.NotPinned -> appContext.getString(R.string.model_failure_not_pinned, ModelSource.PINNED_REVISION)
        ModelFailure.NoManifest -> appContext.getString(R.string.model_failure_no_manifest)
        is ModelFailure.Missing -> appContext.getString(R.string.model_failure_missing, failure.path)
        is ModelFailure.Unreadable -> appContext.getString(R.string.model_failure_unreadable, failure.detail ?: "")
    }

    private fun freeBytes(): Long = try {
        root.mkdirs()
        storage.getAllocatableBytes(storage.getUuidForPath(root))
    } catch (e: IOException) {
        0
    }

    /** Asks the system for [bytes] on the model's volume, freeing caches if it must. */
    private fun reserve(bytes: Long) {
        try {
            root.mkdirs()
            storage.allocateBytes(storage.getUuidForPath(root), bytes)
        } catch (e: IOException) {
            throw ModelDownloadException(ModelFailure.NoSpace(bytes, freeBytes()), e)
        }
    }

    /** The runtime's `checkPackage`, on its worker. */
    private fun check(manifestJson: String): ModelPackage = try {
        val info = runBlocking { worker.run { checkPackage(manifestJson) } }
        ModelPackage(
            modelName = info.model.name,
            modelVersion = info.model.version,
            license = info.license,
            notices = info.notices,
            files = info.files.map { ModelFile(it.path, it.size.toLong(), it.sha256) },
            totalBytes = info.totalBytes.toLong(),
        )
    } catch (e: GeneratorException) {
        throw ModelDownloadException(ModelFailure.Refused(e.message ?: e.javaClass.simpleName), e)
    }

    /** Whether the runtime's generator opens the downloaded directory, as it must before it is installed. */
    private fun open(directory: File) {
        try {
            runBlocking { worker.withGenerator(appContext, directory) { capabilities() } }
        } catch (e: GeneratorException) {
            throw ModelDownloadException(ModelFailure.NotOpened(e.message ?: e.javaClass.simpleName), e)
        }
    }

    companion object {
        private const val TAG = "EnsomiModel"

        /** Each install's start and outcome as one JSON line (README), read by `scripts/phone-checks.sh`. */
        const val INSTALL_TAG = "EnsomiModelInstall"
    }
}
