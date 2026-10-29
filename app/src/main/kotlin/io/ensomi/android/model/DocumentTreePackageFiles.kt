package io.ensomi.android.model

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A model package in a folder the player picked with the system's folder picker (`ACTION_OPEN_DOCUMENT_TREE`), read
 * through the document provider: the folder that holds `ensomi-package.json`, or the folder above it when the files
 * are in its `onnx` folder, as on Hugging Face. Files are found by the manifest's paths, folder by folder; nothing is
 * read but what the manifest lists. Blocking; call it off the main thread.
 */
class DocumentTreePackageFiles(private val resolver: ContentResolver, private val tree: Uri) : PackageFiles {
    private class Entry(val documentId: String, val directory: Boolean, val size: Long?)

    private val children = HashMap<String, Map<String, Entry>>()

    /** The document id of the folder that holds the manifest; the picked folder when none does. */
    private val root: String by lazy {
        val top = DocumentsContract.getTreeDocumentId(tree)
        if (children(top)[ModelInstaller.MANIFEST]?.directory == false) return@lazy top
        val onnx = children(top)[ONNX]?.takeIf { it.directory } ?: return@lazy top
        if (children(onnx.documentId)[ModelInstaller.MANIFEST]?.directory == false) onnx.documentId else top
    }

    override fun manifest(): ByteArray? {
        val entry = entry(ModelInstaller.MANIFEST) ?: return null
        // A manifest is a few KB; reading stops past the limit, and the installer refuses what it read.
        return open(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (out.size() <= MAX_MANIFEST_BYTES) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }

    override fun size(path: String): Long? = entry(path)?.let { it.size ?: -1L }

    override fun open(path: String): InputStream = open(entry(path) ?: throw FileNotFoundException("$path is not in the folder."))

    private fun open(entry: Entry): InputStream =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, entry.documentId))
            ?: throw FileNotFoundException("The document provider gave no stream.")

    private fun entry(path: String): Entry? {
        var folder = root
        val parts = path.split('/')
        for ((index, part) in parts.withIndex()) {
            val entry = children(folder)[part] ?: return null
            if (index == parts.lastIndex) return entry.takeIf { !it.directory }
            if (!entry.directory) return null
            folder = entry.documentId
        }
        return null
    }

    /** The folder's children by display name; the first of two with the same name wins. */
    private fun children(documentId: String): Map<String, Entry> = children.getOrPut(documentId) {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        val found = LinkedHashMap<String, Entry>()
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val directory = cursor.getString(2) == Document.MIME_TYPE_DIR
                val size = if (cursor.isNull(3)) null else cursor.getLong(3)
                found.putIfAbsent(name, Entry(id, directory, size))
            }
        }
        found
    }

    private companion object {
        const val ONNX = "onnx"
        const val MAX_MANIFEST_BYTES = 1 shl 20
    }
}
