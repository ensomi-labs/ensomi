package io.ensomi.android.model

import android.net.Uri
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/** Where the chart model stands on this device. Sizes are bytes. */
sealed interface ModelState {
    /** Looking at what is installed, when the app starts. */
    data object Checking : ModelState

    /**
     * Nothing installed. [freeBytes] is what the app's storage can allocate. [failure] says why the last download
     * stopped before it had kept anything (no connection, Hugging Face unreachable), and [filesFailure] why the last
     * install from picked files stopped, if they did.
     */
    data class Absent(
        val downloadBytes: Long,
        val freeBytes: Long,
        val filesFailure: String? = null,
        val failure: String? = null,
    ) : ModelState {
        val fits: Boolean get() = freeBytes >= downloadBytes
    }

    /** Downloading, or copying the files the player picked when [fromFiles]. */
    data class Downloading(val doneBytes: Long, val totalBytes: Long, val fromFiles: Boolean = false) : ModelState

    /**
     * A download that stopped: cancelled, or the connection dropped. The files so far are kept for a resume.
     * [filesFailure] says why the last install from picked files stopped, if it did.
     */
    data class Stopped(
        val doneBytes: Long,
        val totalBytes: Long,
        val failure: String?,
        val filesFailure: String? = null,
    ) : ModelState

    data class Installed(
        /** The model's identity, from its manifest, such as "Ensomi R2 r2-1.0". */
        val name: String,
        /** The package tag the download came from, such as "r2-1.0.1"; null for an install without a record. */
        val revision: String?,
        val sizeBytes: Long,
        /** The SPDX expression the manifest gives. */
        val license: String,
        /** A download over this install, which stays in use until the download is in place. */
        val update: ModelUpdate? = null,
    ) : ModelState
}

/** A download of the pinned tag over an installed model, or a copy of picked files when [Downloading.fromFiles]. */
sealed interface ModelUpdate {
    data class Downloading(val doneBytes: Long, val totalBytes: Long, val fromFiles: Boolean = false) : ModelUpdate

    /**
     * The install is not the pinned tag, or misses files, and the download to fix it stopped or has not run:
     * [failure] says why, [resumable] whether files are kept for a resume.
     */
    data class Pending(val pinnedRevision: String, val failure: String?, val resumable: Boolean) : ModelUpdate
}

/**
 * A licence notice to show with the model: a file of the installed model ([path] relative to its directory), or an
 * asset of the runtime's AAR.
 */
data class Notice(val path: String, val asset: Boolean)

/** ONNX Runtime's licence and third-party notices, which the runtime's AAR carries. */
val ONNX_RUNTIME_NOTICES: List<Notice> = io.ensomi.runtime.onnxRuntimeLicenseAssets.map { Notice(it, asset = true) }

/**
 * The Ensomi R2 model in app storage: download from Hugging Face at the pinned tag, or install from the package's files
 * the player picked; check the manifest against the pinned SHA-256 and the runtime's `checkPackage` and each file by
 * its size and SHA-256; resume a stopped download; remove.
 */
interface ChartModelStore {
    val state: StateFlow<ModelState>

    /** The installed model's directory, for the runtime's generator, or null when none is installed. */
    val directory: File?

    /** The licence notices of the model in use and of ONNX Runtime, which runs it. */
    val notices: List<Notice>

    /** Downloads the pinned tag, or resumes a stopped download. Checks free space first. */
    fun download()

    /**
     * Installs the pinned package from the files in [folder], a tree the player picked with the system's folder picker:
     * copied into app storage with the download's checks. A download under way stops first.
     */
    fun installFromFolder(folder: Uri)

    fun cancel()

    fun remove()
}
