package io.ensomi.android.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One file of a model package, as its manifest lists it. */
data class ModelFile(val path: String, val size: Long, val sha256: String)

/** A model package's manifest as the runtime's `checkPackage` accepted it. */
data class ModelPackage(
    /** The model's identity, such as "Ensomi R2" and "r2-1.0". */
    val modelName: String,
    val modelVersion: String,
    /** An SPDX expression. */
    val license: String,
    /** The licence notices among [files]. */
    val notices: List<String>,
    /** Every file but the manifest, which is what is fetched and checked. */
    val files: List<ModelFile>,
    val totalBytes: Long,
)

/** What `ensomi-install.json` records: the tag the files came from and the manifest's SHA-256. */
@Serializable
data class InstallRecord(
    val revision: String,
    @SerialName("manifest_sha256") val manifestSha256: String,
)

/** Why a download stopped. */
sealed interface ModelFailure {
    /** The connection failed or dropped. */
    data class Network(val detail: String?) : ModelFailure

    /** The server answered with another status than the download asks for. */
    data class Http(val code: Int) : ModelFailure

    /** The runtime's `checkPackage` refused the manifest, with its reason. */
    data class Refused(val reason: String) : ModelFailure

    /** A file did not have its listed size and SHA-256, even fetched whole. */
    data class Mismatch(val path: String) : ModelFailure

    /** The files still to fetch need [neededBytes]; [freeBytes] can be allocated. */
    data class NoSpace(val neededBytes: Long, val freeBytes: Long) : ModelFailure

    /** The runtime would not open the downloaded model. */
    data class NotOpened(val reason: String) : ModelFailure
}

class ModelDownloadException(val failure: ModelFailure, cause: Throwable? = null) :
    Exception(failure.toString(), cause)

/** An installed model: its manifest, its record (null for an install without one), whether every file is there. */
data class InstalledModel(val pkg: ModelPackage, val record: InstallRecord?, val complete: Boolean) {
    /** Whether the install is the pinned tag with every file: else it is brought up to date when the app starts. */
    fun upToDate(revision: String) = complete && record?.revision == revision
}

/**
 * Installs a model package into `<root>/ensomi-r2`, the way the Swift app's solo-device mode does: fetch the
 * manifest (`ensomi-package.json`) at [baseUrl], check it with [checkManifest] (the runtime's `checkPackage`), fetch
 * exactly the files it lists into `<root>/.download-ensomi-r2` and check each one's size and SHA-256, have [verify]
 * open the result (the runtime's generator), then add `ensomi-install.json` and move the directory into place.
 *
 * A file already staged or installed with its listed size and SHA-256 is kept or copied instead of fetched. A
 * stopped download keeps its files, the one in progress as `<path>.part`, and resumes it with `Range: bytes=<n>-`: a
 * 206 answer is appended, a 200 answer rewrites the file. A resumed file that then fails its check is fetched again
 * whole once. Before fetching, [reserve] is asked for the bytes still to come (StorageManager on Android).
 */
class ModelInstaller(
    root: File,
    private val baseUrl: String,
    val revision: String,
    private val checkManifest: (String) -> ModelPackage,
    private val verify: (File) -> Unit = {},
    private val reserve: (Long) -> Unit = {},
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    /** Each request and its answer, for the log. */
    private val log: (String) -> Unit = {},
) {
    val installDirectory = File(root, "ensomi-r2")
    val stagingDirectory = File(root, ".download-ensomi-r2")

    /** The model in [installDirectory], or null when there is none or the runtime refuses its manifest. */
    fun installed(): InstalledModel? {
        val manifest = File(installDirectory, MANIFEST)
        if (!manifest.isFile) return null
        val pkg = try {
            checkManifest(manifest.readText())
        } catch (e: ModelDownloadException) {
            return null
        }
        val record = File(installDirectory, RECORD).takeIf { it.isFile }
            ?.let { runCatching { JSON.decodeFromString(InstallRecord.serializer(), it.readText()) }.getOrNull() }
        val complete = pkg.files.all { File(installDirectory, it.path).length() == it.size }
        return InstalledModel(pkg, record, complete)
    }

    /** The bytes a stopped download holds and the package's total, or null when nothing is staged. */
    fun staged(): Pair<Long, Long>? {
        val manifest = File(stagingDirectory, MANIFEST)
        if (!manifest.isFile) return null
        val pkg = runCatching { checkManifest(manifest.readText()) }.getOrNull() ?: return null
        val done = pkg.files.sumOf { file ->
            val whole = File(stagingDirectory, file.path)
            val part = File(stagingDirectory, file.path + PART)
            when {
                whole.length() == file.size -> file.size
                part.isFile -> minOf(part.length(), file.size)
                else -> 0L
            }
        }
        return done to pkg.totalBytes
    }

    /**
     * Downloads and installs the package, calling [progress] with the bytes done and the total as they grow. Stops
     * with a [CancellationException] when [isCancelled] turns true, or a [ModelDownloadException]; both keep what is
     * staged.
     */
    fun download(progress: (Long, Long) -> Unit, isCancelled: () -> Boolean): InstalledModel {
        val manifestBytes = fetchSmall(MANIFEST)
        val manifestText = manifestBytes.decodeToString()
        val pkg = checkManifest(manifestText)
        val paths = pkg.files.map { it.path }
        if (paths.any { it.startsWith("/") || it.split('/').any { part -> part == ".." || part.isEmpty() } }) {
            throw ModelDownloadException(ModelFailure.Refused("The manifest lists a path outside the model."))
        }
        stagingDirectory.mkdirs()
        File(stagingDirectory, MANIFEST).writeBytes(manifestBytes)

        val installed = installed()?.takeIf { it.pkg.files.isNotEmpty() }
        val done = pkg.files.associate { it.path to 0L }.toMutableMap()
        fun report() = progress(done.values.sum(), pkg.totalBytes)

        // What is already right stays; the rest is fetched or copied, so that much space is asked for.
        val todo = pkg.files.filter { file ->
            val staged = File(stagingDirectory, file.path)
            val right = staged.length() == file.size && sha256(staged) == file.sha256
            if (right) done[file.path] = file.size
            !right
        }
        val needed = todo.sumOf { file ->
            val part = File(stagingDirectory, file.path + PART)
            file.size - (if (part.isFile) minOf(part.length(), file.size) else 0L)
        }
        if (needed > 0) reserve(needed)
        report()

        for (file in todo) {
            if (isCancelled()) throw CancellationException("The download was cancelled.")
            val target = File(stagingDirectory, file.path)
            target.parentFile?.mkdirs()
            target.delete()
            val part = File(stagingDirectory, file.path + PART)
            val fromInstall = File(installDirectory, file.path)
            if (installed != null && fromInstall.length() == file.size && sha256(fromInstall) == file.sha256) {
                fromInstall.copyTo(part, overwrite = true)
            } else {
                fetch(file, part, isCancelled) { bytes ->
                    done[file.path] = bytes
                    report()
                }
            }
            if (!part.renameTo(target)) throw IOException("Could not move ${file.path} into place.")
            done[file.path] = file.size
            report()
        }

        // Files the package does not list, such as those of an earlier tag, are left out.
        val listed = paths.toSet() + MANIFEST
        stagingDirectory.walkBottomUp().filter { it.isFile }.forEach { staged ->
            if (staged.relativeTo(stagingDirectory).invariantSeparatorsPath !in listed) staged.delete()
        }

        try {
            verify(stagingDirectory)
        } catch (e: ModelDownloadException) {
            throw e
        } catch (e: Exception) {
            throw ModelDownloadException(ModelFailure.NotOpened(e.message ?: e.javaClass.simpleName), e)
        }
        val record = InstallRecord(revision, sha256(manifestBytes))
        File(stagingDirectory, RECORD).writeText(JSON.encodeToString(InstallRecord.serializer(), record))
        replaceInstall()
        return InstalledModel(pkg, record, complete = true)
    }

    /** Deletes the installed model and any stopped download. */
    fun remove() {
        stagingDirectory.deleteRecursively()
        installDirectory.deleteRecursively()
        File(installDirectory.parentFile, OLD).deleteRecursively()
    }

    private fun replaceInstall() {
        val old = File(installDirectory.parentFile, OLD)
        old.deleteRecursively()
        if (installDirectory.exists() && !installDirectory.renameTo(old)) {
            throw IOException("Could not move the old model aside.")
        }
        if (!stagingDirectory.renameTo(installDirectory)) {
            old.renameTo(installDirectory)
            throw IOException("Could not move the model into place.")
        }
        old.deleteRecursively()
    }

    private fun fetchSmall(path: String): ByteArray {
        val connection = open(path)
        try {
            val code = responseCode(connection)
            if (code != HttpURLConnection.HTTP_OK) throw ModelDownloadException(ModelFailure.Http(code))
            return network { connection.inputStream.use { it.readBytes() } }
        } finally {
            connection.disconnect()
        }
    }

    /** Fetches [file] into [part], resuming what [part] holds, until it has the listed size and SHA-256. */
    private fun fetch(file: ModelFile, part: File, isCancelled: () -> Boolean, progress: (Long) -> Unit) {
        var mayResume = true
        while (true) {
            if (part.isFile && (part.length() > file.size || !mayResume)) part.delete()
            val had = if (part.isFile) part.length() else 0L
            if (had < file.size) transfer(file.path, part, had, isCancelled, progress)
            // A body that ended before the file did: the connection closed early. The part is kept for a resume.
            if (part.length() < file.size) throw ModelDownloadException(ModelFailure.Network("The answer ended early."))
            if (part.length() == file.size && sha256(part) == file.sha256) return
            part.delete()
            // A resumed file that does not match, as when the published file changed in between: once more whole.
            if (had == 0L || !mayResume) throw ModelDownloadException(ModelFailure.Mismatch(file.path))
            mayResume = false
        }
    }

    /** Writes the file at [path] into [part] from byte [from] on: appended to a 206 answer, from the start on a 200. */
    private fun transfer(path: String, part: File, from: Long, isCancelled: () -> Boolean, progress: (Long) -> Unit) {
        var offset = from
        while (true) {
            val connection = open(path)
            try {
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val code = responseCode(connection)
                log("$path from byte $offset: HTTP $code")
                val append = when (code) {
                    HttpURLConnection.HTTP_OK -> false
                    HttpURLConnection.HTTP_PARTIAL -> {
                        if (!startsAt(connection, offset)) throw ModelDownloadException(ModelFailure.Http(code))
                        offset > 0
                    }
                    // The range is past the end: the file changed. Fetch it whole.
                    RANGE_NOT_SATISFIABLE -> if (offset > 0) {
                        offset = 0
                        continue
                    } else {
                        throw ModelDownloadException(ModelFailure.Http(code))
                    }
                    else -> throw ModelDownloadException(ModelFailure.Http(code))
                }
                network {
                    connection.inputStream.use { input ->
                        FileOutputStream(part, append).use { output ->
                            val buffer = ByteArray(CHUNK)
                            var written = if (append) offset else 0L
                            var reported = written
                            progress(written)
                            while (true) {
                                if (isCancelled()) throw CancellationException("The download was cancelled.")
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                written += n
                                if (written - reported >= REPORT_BYTES) {
                                    progress(written)
                                    reported = written
                                }
                            }
                            progress(written)
                        }
                    }
                }
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun startsAt(connection: HttpURLConnection, offset: Long): Boolean {
        val range = connection.getHeaderField("Content-Range") ?: return true
        // "bytes <first>-<last>/<total>"
        return range.substringAfter("bytes ", "").substringBefore('-').trim().toLongOrNull() == offset
    }

    private fun open(path: String): HttpURLConnection {
        val url = URL(baseUrl + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") })
        return network {
            connect(url).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
            }
        }
    }

    private fun responseCode(connection: HttpURLConnection): Int = network { connection.responseCode }

    private inline fun <T> network(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw ModelDownloadException(ModelFailure.Network(e.message), e)
    }

    companion object {
        const val MANIFEST = "ensomi-package.json"
        const val RECORD = "ensomi-install.json"
        private const val PART = ".part"
        private const val OLD = ".old-ensomi-r2"
        private const val RANGE_NOT_SATISFIABLE = 416
        private const val CHUNK = 64 * 1024
        private const val REPORT_BYTES = 256 * 1024L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Lowercase hex SHA-256 of [file], read in 1 MiB chunks. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
