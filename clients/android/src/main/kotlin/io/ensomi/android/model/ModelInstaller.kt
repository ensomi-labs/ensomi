package io.ensomi.android.model

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uniffi.ensomi_runtime_ffi.checkPackage
import java.io.File
import java.security.MessageDigest

/** What the app recorded when it installed a model, in the name the Apple clients use. */
@Serializable
data class InstalledModel(
    val modelId: String,
    val modelName: String,
    val version: String,
    val license: String,
    val fileCount: Int,
    val totalBytes: Long,
    val installedAtMs: Long,
    val source: String,
)

data class ModelStatus(
    val installed: InstalledModel?,
    val directory: File,
)

data class InstallProgress(
    val running: Boolean = false,
    val copied: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0,
    val message: String? = null,
)

/**
 * Installs a model package from a folder the user picks.
 *
 * The manifest check is the runtime's (`checkPackage`), not a second implementation here: the package
 * format is a contract with the exporter, and a client that re-implements it will drift. This class only
 * copies what the manifest lists, verifies each file's SHA-256 while copying, and records the result
 * beside the files.
 */
class ModelInstaller(private val context: Context) {
    fun directory(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "model")

    private fun recordFile(): File = File(directory(), "ensomi-install.json")

    fun status(): ModelStatus {
        val directory = directory()
        val record = runCatching {
            if (!recordFile().isFile) return@runCatching null
            json.decodeFromString(InstalledModel.serializer(), recordFile().readText())
        }.getOrNull()
        return ModelStatus(installed = record, directory = directory)
    }

    /**
     * Copies the package rooted at [treeUri] into the model directory. [onProgress] is called on the
     * calling thread; the work itself blocks, so callers run it off the main thread.
     */
    fun install(treeUri: Uri, source: String, onProgress: (InstallProgress) -> Unit): InstalledModel {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("the folder could not be opened")
        check(root.canRead()) { "the folder is not readable; grant it again" }

        val manifest = findManifest(root) ?: error("no ensomi-package.json in ${root.name}")
        val info = checkPackage(manifest.readText(context) ?: error("the manifest could not be read"))

        val files = info.files
        val total = files.size
        onProgress(InstallProgress(running = true, copied = 0, total = total))

        val target = directory()
        target.mkdirs()

        var copied = 0
        var bytes = 0L
        for (file in files) {
            val sourceFile = find(root, file.path) ?: error("${file.path} is listed but missing")
            val destination = File(target, file.path)
            destination.parentFile?.mkdirs()

            val digest = MessageDigest.getInstance("SHA-256")
            destination.outputStream().use { out ->
                sourceFile.inputStream(context).use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        bytes += read
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == file.sha256) { "${file.path} does not match its SHA-256" }

            copied += 1
            onProgress(InstallProgress(running = true, copied = copied, total = total, bytes = bytes))
        }

        File(target, "ensomi-package.json").writeText(manifest.readText(context) ?: "")

        val record = InstalledModel(
            modelId = info.model.id,
            modelName = info.model.name,
            version = info.model.version,
            license = info.license,
            fileCount = copied,
            totalBytes = bytes,
            installedAtMs = System.currentTimeMillis(),
            source = source,
        )
        recordFile().writeText(json.encodeToString(InstalledModel.serializer(), record))
        onProgress(InstallProgress(running = false, copied = copied, total = total, bytes = bytes))
        return record
    }

    /** Removing the download leaves the library alone: it only deletes the model directory. */
    fun remove() {
        directory().deleteRecursively()
    }

    private fun findManifest(root: DocumentFile, depth: Int = 0): DocumentFile? {
        val children = runCatching { root.listFiles() }.getOrDefault(emptyArray())
        children.firstOrNull { it.isFile && it.name == MANIFEST }?.let { return it }
        if (depth >= MAX_DEPTH) return null
        for (child in children) {
            if (!child.isDirectory) continue
            findManifest(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** Resolves a `/`-separated package path below the picked folder. */
    private fun find(root: DocumentFile, path: String): DocumentFile? {
        var current: DocumentFile = root
        for (part in path.split('/')) {
            current = runCatching { current.findFile(part) }.getOrNull() ?: return null
        }
        return current
    }

    private fun DocumentFile.readText(context: Context): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    private fun DocumentFile.inputStream(context: Context) =
        context.contentResolver.openInputStream(uri) ?: error("${name ?: "a file"} could not be opened")

    private companion object {
        const val MANIFEST = "ensomi-package.json"
        const val MAX_DEPTH = 3
        val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
