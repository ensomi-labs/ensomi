package io.ensomi.android.model

import android.content.Context
import android.system.Os
import android.system.OsConstants
import io.ensomi.android.diagnostics.ExitRecords
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
class DownloadingChartModelStore(
    context: Context,
    private val scope: CoroutineScope,
    private val events: InstallEvents = InstallEvents.NONE,
) : ChartModelStore {
    private val appContext = context.applicationContext
    private val root = File(appContext.noBackupFilesDir, "models")
    private val storage = appContext.getSystemService(StorageManager::class.java)
    private val worker = RuntimeWorker()
    override val access = Mutex()
    override val openGuard = ModelOpenGuard(
        appContext.noBackupFilesDir,
        pid = { android.os.Process.myPid() },
        events = events,
        log = { Log.i(ModelOpenGuard.TAG, it) },
        afterMarker = { ModelInstallFaults.afterMarker(appContext) },
        exitReason = { pid, after -> ExitRecords.reasonFor(appContext, pid, after) },
        syncDirectory = { dir ->
            try {
                val fd = Os.open(dir.path, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY, 0)
                try { Os.fsync(fd) } finally { Os.close(fd) }
            } catch (failure: android.system.ErrnoException) {
                throw IOException("Could not sync model state directory.", failure)
            }
        },
        afterInferenceMarker = { ModelInstallFaults.afterInference(appContext) },
    )
    private val installer = ModelInstaller(
        root = root,
        baseUrl = ModelSource.BASE_URL,
        revision = ModelSource.PINNED_REVISION,
        checkManifest = ::check,
        pinnedManifestSha256 = ModelSource.PINNED_MANIFEST_SHA256,
        reserve = ::reserve,
        log = { Log.i(TAG, it) },
        events = events,
        onPhase = ::phaseChanged,
        afterStaging = { ModelInstallFaults.afterStaging(appContext) },
        commit = { work -> runBlocking { access.withLock { work() } } },
    )
    private val mutableState = MutableStateFlow<ModelState>(ModelState.Checking)
    private val lock = Any()
    private var installed: InstalledModel? = null
    private var running: Running? = null
    private var phase = InstallPhase.STAGING

    private class Running(val job: Job, val cancelled: AtomicBoolean)

    override val state: StateFlow<ModelState> = mutableState.asStateFlow()

    override val directory: File?
        get() = installer.installDirectory.takeIf {
            val state = mutableState.value as? ModelState.Installed
            state != null && state.update !is ModelUpdate.Finalizing
        }

    override val notices: List<Notice>
        get() = installed?.pkg?.notices.orEmpty().map { Notice(it, asset = false) } + ONNX_RUNTIME_NOTICES

    init {
        scope.launch {
            openGuard.state.collect {
                mutableState.update { state ->
                    if (state is ModelState.Installed) state.copy(openState = openGuard.stateFor(state.revision ?: state.name)) else state
                }
            }
        }
        scope.launch {
            try {
                val found = installer.installed()
                synchronized(lock) { installed = found }
                when {
                    found != null -> {
                        mutableState.value = installedState(found, update = null)
                        if (!upToDate(found)) download()
                    }
                    else -> mutableState.value = idleState(failure = null)
                }
            } catch (failure: Throwable) {
                publishBoundaryFailure("startup", failure)
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
        synchronized(lock) {
            if (phase != InstallPhase.STAGING) return
            val active = running ?: return
            if (!active.job.isActive) return
            active.cancelled.set(true)
            // No disk/native calls on the UI thread. The worker may still be unwinding its socket.
            val current = mutableState.value
            mutableState.value = when (current) {
                is ModelState.Downloading -> ModelState.Stopped(current.doneBytes, current.totalBytes, null)
                is ModelState.Stopped -> current.copy(failure = null)
                is ModelState.Installed -> current.copy(update = ModelUpdate.Pending(ModelSource.PINNED_REVISION, null, true))
                else -> ModelState.Stopped(0, ModelSource.PACKAGE_BYTES, null)
            }
        }
    }

    override fun remove() {
        val current = synchronized(lock) { running }
        current?.cancelled?.set(true)
        scope.launch {
            current?.job?.join()
            access.withLock {
                mutableState.value = ModelState.Checking
                try {
                    installer.remove()
                    openGuard.remove()
                    synchronized(lock) { installed = null }
                    mutableState.value = ModelState.Absent(ModelSource.PACKAGE_BYTES, freeBytes())
                } catch (failure: Throwable) {
                    publishBoundaryFailure("remove", failure)
                }
            }
        }
    }

    private fun publishBoundaryFailure(operation: String, failure: Throwable) {
        Log.e(TAG, "$operation failed", failure)
        events.record(operation, "fail", mapOf("reason" to failure.javaClass.simpleName))
        // Do not call installed()/staged() again while handling a runtime/storage failure.
        mutableState.value = ModelState.Stopped(0, ModelSource.PACKAGE_BYTES,
            stageFailure(failure.message ?: failure.javaClass.simpleName))
        if (failure is VirtualMachineError || failure is CancellationException) throw failure
    }

    private fun phaseChanged(next: InstallPhase, checked: Int, total: Int) {
        synchronized(lock) {
            if (running?.cancelled?.get() == true) throw CancellationException("The install was cancelled.")
            phase = next
        }
        if (next == InstallPhase.STAGING) return
        val model = synchronized(lock) { installed }
        mutableState.value = if (model == null) ModelState.Finalizing(next, checked, total)
            else installedState(model, ModelUpdate.Finalizing(next, checked, total))
        if (checked == 0) Log.i(TAG, "Install stage: $next ($total files)")
    }

    private fun run(cancelled: AtomicBoolean) {
        if (cancelled.get()) return
        val started = System.nanoTime()
        logInstall(InstallRecord.SOURCE_HUGGING_FACE, "started", started)
        events.record("download", "begin", mapOf(
            "source" to InstallRecord.SOURCE_HUGGING_FACE,
            "reason" to if (File(installer.stagingDirectory, ModelInstaller.MANIFEST).isFile) "resume" else "download",
        ))
        try {
            val result = installer.download(
                progress = { done, total ->
                    if (!cancelled.get() && done < total) mutableState.value = progressState(done, total)
                },
                isCancelled = { cancelled.get() },
            )
            synchronized(lock) {
                if (cancelled.get()) throw CancellationException("The install was cancelled.")
                installed = result
                mutableState.value = installedState(result, update = null)
            }
            Log.i(TAG, "Installed ${result.pkg.modelName} ${result.pkg.modelVersion}, package ${result.record?.revision}")
            logInstall(InstallRecord.SOURCE_HUGGING_FACE, "installed", started, result)
            events.record("download", "stop", mapOf("reason" to "committed"))
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled")
            logInstall(InstallRecord.SOURCE_HUGGING_FACE, "cancelled", started)
            events.record("download", "stop", mapOf("reason" to "cancelled"))
            mutableState.value = stoppedState(failure = null)
        } catch (e: ModelDownloadException) {
            Log.w(TAG, "Download stopped: ${e.failure}", e)
            logInstall(InstallRecord.SOURCE_HUGGING_FACE, "stopped", started, failure = describe(e.failure))
            events.record("download", "stop", mapOf("reason" to e.failure.javaClass.simpleName, "stage" to phase.name.lowercase()))
            val failure = e.failure
            mutableState.value = if (failure is ModelFailure.NoSpace && synchronized(lock) { installed } == null) {
                ModelState.Absent(failure.neededBytes, failure.freeBytes)
            } else {
                stoppedState(describeAtStage(failure))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Install failed during $phase", e)
            val failure = stageFailure(e.message ?: e.javaClass.simpleName)
            events.record("download", "stop", mapOf("reason" to e.javaClass.simpleName, "stage" to phase.name.lowercase()))
            logInstall(InstallRecord.SOURCE_HUGGING_FACE, "stopped", started, failure = failure)
            mutableState.value = stoppedState(failure)
            if (e is VirtualMachineError) throw e
        }
    }

    /** Installs from the picked [folder]; on any stop the state is what it was before, with the reason. */
    private fun copy(folder: Uri, cancelled: AtomicBoolean) {
        if (cancelled.get()) return
        val started = System.nanoTime()
        logInstall(InstallRecord.SOURCE_FILES, "started", started)
        events.record("download", "begin", mapOf("source" to InstallRecord.SOURCE_FILES, "reason" to "copy"))
        val failure: String? = try {
            val result = installer.installFrom(
                DocumentTreePackageFiles(appContext.contentResolver, folder),
                progress = { done, total ->
                    if (!cancelled.get() && done < total) mutableState.value = progressState(done, total, fromFiles = true)
                },
                isCancelled = { cancelled.get() },
            )
            synchronized(lock) {
                if (cancelled.get()) throw CancellationException("The install was cancelled.")
                installed = result
                mutableState.value = installedState(result, update = null)
            }
            Log.i(TAG, "Installed ${result.pkg.modelName} ${result.pkg.modelVersion} from files, package ${result.record?.revision}")
            logInstall(InstallRecord.SOURCE_FILES, "installed", started, result)
            events.record("download", "stop", mapOf("reason" to "committed"))
            return
        } catch (e: CancellationException) {
            Log.i(TAG, "Install from files cancelled")
            logInstall(InstallRecord.SOURCE_FILES, "cancelled", started)
            events.record("download", "stop", mapOf("reason" to "cancelled"))
            null
        } catch (e: ModelDownloadException) {
            Log.w(TAG, "Install from files stopped: ${e.failure}", e)
            events.record("download", "stop", mapOf("reason" to e.failure.javaClass.simpleName, "stage" to phase.name.lowercase()))
            describeAtStage(e.failure).also { logInstall(InstallRecord.SOURCE_FILES, "stopped", started, failure = it) }
        } catch (e: Throwable) {
            Log.e(TAG, "Install from files failed during $phase", e)
            val detail = stageFailure(e.message ?: e.javaClass.simpleName)
            events.record("download", "stop", mapOf("reason" to e.javaClass.simpleName, "stage" to phase.name.lowercase()))
            logInstall(InstallRecord.SOURCE_FILES, "stopped", started, failure = detail)
            if (e is VirtualMachineError) {
                mutableState.value = stoppedState(detail)
                throw e
            }
            detail
        }
        val model = synchronized(lock) { installed }
        mutableState.value = if (model != null) {
            val update = ModelUpdate.Pending(ModelSource.PINNED_REVISION, failure, resumable = installer.staged() != null)
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
        openState = openGuard.stateFor(model.record?.revision ?: "${model.pkg.modelName} ${model.pkg.modelVersion}"),
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
        val staged = try { installer.staged() } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            return ModelState.Stopped(0, ModelSource.PACKAGE_BYTES, failure ?: (e.message ?: e.javaClass.simpleName), filesFailure)
        }
        return if (staged != null) {
            ModelState.Stopped(staged.first, staged.second, failure, filesFailure)
        } else {
            ModelState.Absent(ModelSource.PACKAGE_BYTES, freeBytes(), filesFailure, failure)
        }
    }

    private fun describe(failure: ModelFailure): String = when (failure) {
        is ModelFailure.Network -> appContext.getString(R.string.model_failure_network)
        is ModelFailure.Http -> appContext.getString(R.string.model_failure_http, failure.code)
        is ModelFailure.Refused -> appContext.getString(R.string.model_failure_refused, failure.reason)
        is ModelFailure.Mismatch -> appContext.getString(R.string.model_failure_mismatch, failure.path)
        is ModelFailure.Storage -> appContext.getString(R.string.model_failure_storage)
        is ModelFailure.Stage -> stageFailure(failure.reason, failure.phase)
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

    private fun describeAtStage(failure: ModelFailure): String =
        if (failure is ModelFailure.Stage) describe(failure) else stageFailure(describe(failure))

    private fun stageFailure(reason: String, at: InstallPhase = phase): String = appContext.getString(
        when (at) {
            InstallPhase.STAGING -> R.string.model_failure_staging
            InstallPhase.VERIFYING -> R.string.model_failure_verifying
            InstallPhase.COMMITTING -> R.string.model_failure_committing
        },
        reason,
    )

    companion object {
        private const val TAG = "EnsomiModel"

        /** Each install's start and outcome as one JSON line (README), read by `scripts/phone-checks.sh`. */
        const val INSTALL_TAG = "EnsomiModelInstall"
    }
}
