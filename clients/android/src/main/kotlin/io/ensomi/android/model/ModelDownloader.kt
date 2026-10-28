package io.ensomi.android.model

import android.content.Context
import kotlinx.serialization.json.Json
import uniffi.ensomi_runtime_ffi.PackageFile
import uniffi.ensomi_runtime_ffi.checkPackage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Writes and reads the record beside the model files, in the name the Apple clients use. */
object ModelRecords {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    const val FILE = "ensomi-install.json"

    fun read(directory: File): InstalledModel? = runCatching {
        val file = File(directory, FILE)
        if (!file.isFile) null else json.decodeFromString(InstalledModel.serializer(), file.readText())
    }.getOrNull()

    fun write(directory: File, record: InstalledModel) {
        File(directory, FILE).writeText(json.encodeToString(InstalledModel.serializer(), record))
    }
}

/**
 * Downloads the published R2 package into the model directory.
 *
 * The package manifest lists every file with its size and SHA-256, so the downloader needs nothing but the
 * manifest and a base URL: fetch the manifest, let the runtime's `checkPackage` judge it, then fetch what
 * it lists and verify each file as it lands. A file already present at the right size is skipped, so a
 * dropped connection costs only what it dropped.
 */
class ModelDownloader(private val context: Context) {
    fun directory(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "model")

    fun download(onProgress: (InstallProgress) -> Unit): InstalledModel {
        val target = directory()
        target.mkdirs()

        onProgress(InstallProgress(running = true, message = "reading the manifest"))
        val manifestText = fetchText(BASE_URL + MANIFEST)
        val info = checkPackage(manifestText)

        val files = info.files
        val total = files.size
        var copied = 0
        var bytes = 0L

        for (file in files) {
            val destination = File(target, file.path)
            destination.parentFile?.mkdirs()

            if (destination.isFile && destination.length().toULong() == file.size) {
                copied += 1
                bytes += destination.length()
                onProgress(InstallProgress(true, copied, total, bytes))
                continue
            }

            downloadFileWithRetry(BASE_URL + file.path, destination, file)
            copied += 1
            bytes += destination.length()
            onProgress(InstallProgress(true, copied, total, bytes))
        }

        File(target, MANIFEST).writeText(manifestText)

        val record = InstalledModel(
            modelId = info.model.id,
            modelName = info.model.name,
            version = info.model.version,
            license = info.license,
            fileCount = copied,
            totalBytes = bytes,
            installedAtMs = System.currentTimeMillis(),
            source = BASE_URL,
        )
        ModelRecords.write(target, record)
        onProgress(InstallProgress(false, copied, total, bytes))
        return record
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.requestMethod = "GET"
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "$url answered ${connection.responseCode}"
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A phone on a mirror sees the occasional read timeout; a partial file is deleted and the file tried
     * again before the install is called failed.
     */
    private fun downloadFileWithRetry(url: String, destination: File, expected: PackageFile) {
        var lastError: Throwable? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                downloadFile(url, destination, expected)
                return
            } catch (error: Throwable) {
                lastError = error
                destination.delete()
                Thread.sleep(1_000L * (attempt + 1))
            }
        }
        throw IllegalStateException("${expected.path} failed after $ATTEMPTS attempts: ${lastError?.message}", lastError)
    }

    private fun downloadFile(url: String, destination: File, expected: PackageFile) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.requestMethod = "GET"
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "$url answered ${connection.responseCode}"
            }

            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                destination.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected.sha256) {
                destination.delete()
                error("${expected.path} does not match its SHA-256")
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /**
         * The published package, served by the mirror this network can reach; `huggingface.co` itself is
         * not resolvable here. The manifest is inside the package directory, so one base URL addresses
         * both it and every file it lists.
         */
        const val BASE_URL = "https://hf-mirror.com/sed-i/ensomi-r2/resolve/r2-1.0.1/onnx/"
        const val MANIFEST = "ensomi-package.json"
        const val ATTEMPTS = 3
    }
}
