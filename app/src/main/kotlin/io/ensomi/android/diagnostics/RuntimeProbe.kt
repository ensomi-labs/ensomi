package io.ensomi.android.diagnostics

import android.util.Log
import io.ensomi.runtime.Cancellation
import io.ensomi.runtime.GeneratorException
import io.ensomi.runtime.RuntimeWorker
import io.ensomi.runtime.checkPackage
import io.ensomi.runtime.noticeFiles
import io.ensomi.runtime.uniffiEnsureInitialized
import kotlin.coroutines.cancellation.CancellationException

/** Loads the runtime's native library through the AAR's Kotlin API and makes calls that need no model. */
object RuntimeProbe {
    private const val TAG = "Ensomi"

    suspend fun check(): RuntimeFacts {
        var loaded = false
        var initMillis: Double? = null
        var notices: List<String>? = null
        var packageCheck: String? = null
        return try {
            RuntimeWorker().use { worker ->
                worker.run {
                    val start = System.nanoTime()
                    uniffiEnsureInitialized()
                    initMillis = Math.round((System.nanoTime() - start) / 1e5) / 10.0
                    loaded = true
                    Cancellation().use { cancellation ->
                        check(!cancellation.isCancelled()) { "A new Cancellation reports cancelled" }
                    }
                    notices = noticeFiles("{}")
                    // An empty manifest is refused; the refusal shows the runtime answered.
                    packageCheck = try {
                        checkPackage("{}")
                        "accepted"
                    } catch (error: GeneratorException.Model) {
                        "Model: ${error.message}"
                    }
                }
            }
            RuntimeFacts(loaded, initMillis, notices, packageCheck, error = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Throwable on purpose: a missing or broken native library surfaces as an Error.
            Log.w(TAG, "Runtime check failed", failure)
            RuntimeFacts(loaded, initMillis, notices, packageCheck, error = describe(failure))
        }
    }

    /** The exception and up to two causes, for example an ExceptionInInitializerError and its UnsatisfiedLinkError. */
    fun describe(failure: Throwable): String =
        generateSequence(failure) { it.cause }.take(3).joinToString(" / caused by ") { it.toString() }
}
