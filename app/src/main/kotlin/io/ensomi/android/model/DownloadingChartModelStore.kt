package io.ensomi.android.model

import android.content.Context
import android.os.storage.StorageManager
import android.util.Log
import io.ensomi.android.R
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
 * [ModelSource] while the app is open. Free space comes from `StorageManager.getAllocatableBytes`, and a download
 * asks `allocateBytes` for what it still has to fetch, which frees the system's caches if it must.
 *
 * When the app starts with an install that is not the pinned tag or misses a file, it downloads the pinned tag over
 * it; the install stays in use until the download is in place. Otherwise a download runs only when asked: a stopped
 * one waits for Resume.
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
                    if (!found.upToDate(ModelSource.PINNED_REVISION)) {
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
            val previous = running
            val cancelled = AtomicBoolean(false)
            val job = scope.launch {
                previous?.job?.join()
                run(cancelled)
            }
            running = Running(job, cancelled)
        }
    }

    override fun cancel() {
        val current = synchronized(lock) { running } ?: return
        if (!current.cancelled.compareAndSet(false, true)) return
        // The state says it at once; the download notices between chunks.
        mutableState.value = stoppedState(failure = null)
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
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled")
            mutableState.value = stoppedState(failure = null)
        } catch (e: ModelDownloadException) {
            Log.w(TAG, "Download stopped: ${e.failure}", e)
            val failure = e.failure
            mutableState.value = if (failure is ModelFailure.NoSpace && synchronized(lock) { installed } == null) {
                ModelState.Absent(failure.neededBytes, failure.freeBytes)
            } else {
                stoppedState(describe(failure))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Download stopped", e)
            mutableState.value = stoppedState(e.message ?: appContext.getString(R.string.model_failure_network))
        } catch (e: RuntimeException) {
            Log.e(TAG, "Download failed", e)
            mutableState.value = stoppedState(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun installedState(model: InstalledModel, update: ModelUpdate?) = ModelState.Installed(
        name = "${model.pkg.modelName} ${model.pkg.modelVersion}",
        revision = model.record?.revision,
        sizeBytes = model.pkg.totalBytes,
        license = model.pkg.license,
        update = update ?: if (model.upToDate(ModelSource.PINNED_REVISION)) {
            null
        } else {
            ModelUpdate.Pending(ModelSource.PINNED_REVISION, failure = null, resumable = installer.staged() != null)
        },
    )

    private fun progressState(done: Long, total: Long): ModelState {
        val model = synchronized(lock) { installed }
        return if (model != null) installedState(model, ModelUpdate.Downloading(done, total)) else ModelState.Downloading(done, total)
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

    private fun idleState(failure: String?): ModelState {
        val staged = installer.staged()
        return if (staged != null) {
            ModelState.Stopped(staged.first, staged.second, failure)
        } else {
            ModelState.Absent(ModelSource.PACKAGE_BYTES, freeBytes())
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

    private companion object {
        const val TAG = "EnsomiModel"
    }
}
