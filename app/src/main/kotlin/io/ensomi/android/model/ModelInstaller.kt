package io.ensomi.android.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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

/**
 * What `ensomi-install.json` records: the tag the files came from, the manifest's SHA-256 and how they came:
 * [SOURCE_HUGGING_FACE] or [SOURCE_FILES] (null in records written before the app installed from files).
 */
@Serializable
data class InstallRecord(
    val revision: String,
    @SerialName("manifest_sha256") val manifestSha256: String,
    val source: String? = null,
) {
    companion object {
        const val SOURCE_HUGGING_FACE = "huggingface"
        const val SOURCE_FILES = "files"
    }
}

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

    /** The manifest is not the pinned package's: its SHA-256 is [sha256], not the pinned one. */
    data class NotPinned(val sha256: String) : ModelFailure

    /** The picked folder holds no `ensomi-package.json`. */
    data object NoManifest : ModelFailure

    /** The picked folder lacks a file the manifest lists. */
    data class Missing(val path: String) : ModelFailure

    /** A picked file could not be read. */
    data class Unreadable(val detail: String?) : ModelFailure
}

class ModelDownloadException(val failure: ModelFailure, cause: Throwable? = null) :
    Exception(failure.toString(), cause)

/** An installed model: its manifest, its record (null for an install without one), whether every file is there. */
data class InstalledModel(val pkg: ModelPackage, val record: InstallRecord?, val complete: Boolean) {
    /**
     * Whether the install is the pinned tag, with the pinned manifest when [manifestSha256] is given, and every file:
     * else it is brought up to date when the app starts.
     */
    fun upToDate(revision: String, manifestSha256: String? = null) = complete && record?.revision == revision &&
        (manifestSha256 == null || record.manifestSha256 == manifestSha256)
}

/**
 * The files of a package to install from, where the player put them: a folder picked with the system's picker
 * ([DocumentTreePackageFiles]) or, in tests, a directory ([DirectoryPackageFiles]). Paths are the manifest's.
 */
interface PackageFiles {
    /** The manifest's bytes, or null when there is none. */
    fun manifest(): ByteArray?

    /** The size of the file at [path], -1 when the folder does not say, or null when there is no such file. */
    fun size(path: String): Long?

    fun open(path: String): InputStream
}

/** A package's files in a directory. */
class DirectoryPackageFiles(private val directory: File) : PackageFiles {
    override fun manifest(): ByteArray? = File(directory, ModelInstaller.MANIFEST).takeIf { it.isFile }?.readBytes()

    override fun size(path: String): Long? = File(directory, path).takeIf { it.isFile }?.length()

    override fun open(path: String): InputStream = File(directory, path).inputStream()
}

/**
 * Installs a model package into `<root>/ensomi-r2`, the way the Swift app's solo-device mode does: fetch the
 * manifest (`ensomi-package.json`) at [baseUrl], check its SHA-256 against [pinnedManifestSha256] and its content with
 * [checkManifest] (the runtime's `checkPackage`), fetch exactly the files it lists into `<root>/.download-ensomi-r2`
 * and check each one's size and SHA-256, have [verify] open the result (the runtime's generator), then add
 * `ensomi-install.json` and move the directory into place. [installFrom] does the same with files the player picked,
 * copied into `<root>/.files-ensomi-r2`.
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
    /** The SHA-256 the manifest must have; null takes any manifest the runtime accepts (tests). */
    private val pinnedManifestSha256: String? = null,
    private val verify: (File) -> Unit = {},
    private val reserve: (Long) -> Unit = {},
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    /** Each request and its answer, for the log. */
    private val log: (String) -> Unit = {},
) {
    val installDirectory = File(root, "ensomi-r2")
    val stagingDirectory = File(root, ".download-ensomi-r2")

    /** Where an install from picked files copies them; it never outlives the install, done or failed. */
    val copyDirectory = File(root, ".files-ensomi-r2")

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
        val pkg = checkedPackage(manifestBytes)
        val paths = pkg.files.map { it.path }
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

        verifyOpens(stagingDirectory)
        val record = writeRecord(stagingDirectory, manifestBytes, InstallRecord.SOURCE_HUGGING_FACE)
        replaceInstall(stagingDirectory)
        return InstalledModel(pkg, record, complete = true)
    }

    /**
     * Installs the package in [files], as the player picked them, with the download's checks: the manifest's pinned
     * SHA-256 and the runtime's `checkPackage`, every listed file there before anything is copied, the space reserved,
     * each file's size and SHA-256 as it is copied into [copyDirectory], the runtime opening the copy, then the record
     * (source [InstallRecord.SOURCE_FILES]) and the move into place. Whatever stops it, a refusal, a file that does not
     * match, [isCancelled] or an error, the copy is deleted: an installed model is either the old one or the whole new
     * one. Calls [progress] with the bytes copied and the total.
     */
    fun installFrom(files: PackageFiles, progress: (Long, Long) -> Unit, isCancelled: () -> Boolean): InstalledModel {
        val manifestBytes = readable { files.manifest() } ?: throw ModelDownloadException(ModelFailure.NoManifest)
        if (manifestBytes.size > MAX_MANIFEST_BYTES) throw ModelDownloadException(ModelFailure.NotPinned(sha256(manifestBytes)))
        val pkg = checkedPackage(manifestBytes)
        for (file in pkg.files) {
            val size = readable { files.size(file.path) } ?: throw ModelDownloadException(ModelFailure.Missing(file.path))
            if (size >= 0 && size != file.size) throw ModelDownloadException(ModelFailure.Mismatch(file.path))
        }
        var installed = false
        copyDirectory.deleteRecursively()
        try {
            reserve(pkg.totalBytes)
            copyDirectory.mkdirs()
            File(copyDirectory, MANIFEST).writeBytes(manifestBytes)
            var done = 0L
            progress(0L, pkg.totalBytes)
            for (file in pkg.files) {
                if (isCancelled()) throw CancellationException("The install was cancelled.")
                val target = File(copyDirectory, file.path)
                target.parentFile?.mkdirs()
                val sha = readable { copyChecked(files, file, target, isCancelled) { progress(done + it, pkg.totalBytes) } }
                if (sha != file.sha256) throw ModelDownloadException(ModelFailure.Mismatch(file.path))
                done += file.size
                progress(done, pkg.totalBytes)
            }
            verifyOpens(copyDirectory)
            val record = writeRecord(copyDirectory, manifestBytes, InstallRecord.SOURCE_FILES)
            replaceInstall(copyDirectory)
            installed = true
            return InstalledModel(pkg, record, complete = true)
        } finally {
            if (!installed) copyDirectory.deleteRecursively()
        }
    }

    /** Copies [file] from [files] to [target], returning its SHA-256; a file longer or shorter than listed is refused. */
    private fun copyChecked(
        files: PackageFiles,
        file: ModelFile,
        target: File,
        isCancelled: () -> Boolean,
        progress: (Long) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        files.open(file.path).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(CHUNK)
                var reported = 0L
                while (true) {
                    if (isCancelled()) throw CancellationException("The install was cancelled.")
                    val n = input.read(buffer)
                    if (n < 0) break
                    written += n
                    if (written > file.size) throw ModelDownloadException(ModelFailure.Mismatch(file.path))
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                    if (written - reported >= REPORT_BYTES) {
                        progress(written)
                        reported = written
                    }
                }
            }
        }
        if (written != file.size) throw ModelDownloadException(ModelFailure.Mismatch(file.path))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Deletes the installed model, any stopped download and any copy left by a stopped process. */
    fun remove() {
        stagingDirectory.deleteRecursively()
        copyDirectory.deleteRecursively()
        installDirectory.deleteRecursively()
        File(installDirectory.parentFile, OLD).deleteRecursively()
    }

    /** The manifest, if it is the pinned one and the runtime accepts it and its paths stay inside the model. */
    private fun checkedPackage(manifestBytes: ByteArray): ModelPackage {
        pinnedManifestSha256?.let { pin ->
            val found = sha256(manifestBytes)
            if (found != pin) throw ModelDownloadException(ModelFailure.NotPinned(found))
        }
        val pkg = checkManifest(manifestBytes.decodeToString())
        val paths = pkg.files.map { it.path }
        if (paths.any { it.startsWith("/") || it.split('/').any { part -> part == ".." || part.isEmpty() } }) {
            throw ModelDownloadException(ModelFailure.Refused("The manifest lists a path outside the model."))
        }
        return pkg
    }

    private fun verifyOpens(directory: File) {
        try {
            verify(directory)
        } catch (e: ModelDownloadException) {
            throw e
        } catch (e: Exception) {
            throw ModelDownloadException(ModelFailure.NotOpened(e.message ?: e.javaClass.simpleName), e)
        }
    }

    private fun writeRecord(directory: File, manifestBytes: ByteArray, source: String): InstallRecord {
        val record = InstallRecord(revision, sha256(manifestBytes), source)
        File(directory, RECORD).writeText(JSON.encodeToString(InstallRecord.serializer(), record))
        return record
    }

    private fun replaceInstall(from: File) {
        val old = File(installDirectory.parentFile, OLD)
        old.deleteRecursively()
        if (installDirectory.exists() && !installDirectory.renameTo(old)) {
            throw IOException("Could not move the old model aside.")
        }
        if (!from.renameTo(installDirectory)) {
            old.renameTo(installDirectory)
            throw IOException("Could not move the model into place.")
        }
        old.deleteRecursively()
    }

    /** Reading the player's files: an error says it could not read them, not that a connection dropped. */
    private inline fun <T> readable(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw ModelDownloadException(ModelFailure.Unreadable(e.message), e)
    } catch (e: SecurityException) {
        throw ModelDownloadException(ModelFailure.Unreadable(e.message), e)
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

        /** A manifest is a few KB; a larger "manifest" is not the package's and is not read further. */
        private const val MAX_MANIFEST_BYTES = 1 shl 20
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
