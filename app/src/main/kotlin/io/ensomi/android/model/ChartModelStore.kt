package io.ensomi.android.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the chart model stands on this device. Sizes are bytes. */
sealed interface ModelState {
    /** Nothing installed. [freeBytes] is what the app's storage can allocate. */
    data class Absent(val downloadBytes: Long, val freeBytes: Long) : ModelState {
        val fits: Boolean get() = freeBytes >= downloadBytes
    }

    data class Downloading(val doneBytes: Long, val totalBytes: Long) : ModelState

    /** A download that stopped: cancelled, or the connection dropped. The files so far are kept for a resume. */
    data class Stopped(val doneBytes: Long, val totalBytes: Long, val failure: String?) : ModelState

    data class Installed(
        /** The model's identity, from its manifest, such as "Ensomi R2 r2-1.0". */
        val name: String,
        /** The package tag the download came from, such as "r2-1.0.1". */
        val revision: String,
        val sizeBytes: Long,
        /** The SPDX expression the manifest gives. */
        val license: String,
    ) : ModelState
}

/**
 * The Ensomi R2 model in app storage: download from Hugging Face at the pinned tag, check each file against the
 * package manifest (`checkPackage`, sizes, SHA-256), resume a stopped download, remove.
 */
interface ChartModelStore {
    val state: StateFlow<ModelState>

    fun download()

    fun cancel()

    fun remove()
}

/**
 * Not wired: a fixed state for the prototype. The real store (plan step 4) downloads, checks and installs the
 * package.
 */
class SampleChartModelStore(initial: ModelState) : ChartModelStore {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<ModelState> = mutableState.asStateFlow()

    override fun download() = Unit

    override fun cancel() = Unit

    override fun remove() = Unit

    companion object {
        /** The package at the pinned tag: 30 files, about 120 MB by its manifest. */
        const val PACKAGE_BYTES = 120_400_000L
        const val PINNED_REVISION = "r2-1.0.1"

        val INSTALLED = ModelState.Installed("Ensomi R2 r2-1.0", PINNED_REVISION, PACKAGE_BYTES, "MIT")
    }
}
