package io.ensomi.android.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile

/**
 * Walks the granted folders and indexes the audio it finds. It runs on its own thread and publishes
 * progress as Compose state, so a large folder does not stall the page that started it.
 */
class LibraryScanner(
    private val context: Context,
    private val store: LibraryStore,
) {
    private val main = Handler(Looper.getMainLooper())

    var progress by mutableStateOf(ScanProgress())
        private set

    fun scan(onFinished: () -> Unit = {}) {
        if (progress.running) return
        progress = ScanProgress(running = true)
        Thread({ runScan(onFinished) }, "ensomi-library-scan").start()
    }

    private fun runScan(onFinished: () -> Unit) {
        val folders = store.index.folders
        val entries = mutableListOf<LibraryEntry>()
        var discovered = 0
        var indexed = 0
        var failed = 0
        var message: String? = null

        for (folder in folders) {
            val tree = runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
            }.getOrNull()

            if (tree == null || !tree.canRead()) {
                message = "Access to ${folder.displayName} was revoked. Remove it or grant it again."
                continue
            }

            walk(tree, folder, entries) { counted, ok ->
                discovered += 1
                if (ok) indexed += 1 else failed += 1
                if (counted % 8 == 0) {
                    publish(discovered, indexed, failed, message)
                }
            }
        }

        store.replaceEntries(entries.sortedBy { it.displayTitle.lowercase() }, System.currentTimeMillis())
        main.post {
            progress = ScanProgress(
                running = false,
                discovered = discovered,
                indexed = indexed,
                failed = failed,
                message = message,
            )
            onFinished()
        }
    }

    private fun walk(
        directory: DocumentFile,
        folder: LibraryFolder,
        entries: MutableList<LibraryEntry>,
        onFile: (count: Int, indexed: Boolean) -> Unit,
    ) {
        val children = runCatching { directory.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            if (child.isDirectory) {
                walk(child, folder, entries, onFile)
                continue
            }
            val name = child.name ?: continue
            if (!AudioFiles.isAudio(name, child.type)) continue

            val entry = readEntry(child, name, folder)
            entries += entry
            onFile(entries.size, entry.failure == null)
        }
    }

    private fun readEntry(file: DocumentFile, name: String, folder: LibraryFolder): LibraryEntry {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, file.uri)
            LibraryEntry(
                uri = file.uri.toString(),
                name = name,
                treeUri = folder.treeUri,
                sizeBytes = file.length(),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                indexedAtMs = System.currentTimeMillis(),
            )
        } catch (error: Exception) {
            LibraryEntry(
                uri = file.uri.toString(),
                name = name,
                treeUri = folder.treeUri,
                sizeBytes = file.length(),
                durationMs = null,
                title = null,
                artist = null,
                album = null,
                indexedAtMs = System.currentTimeMillis(),
                failure = error.message ?: "the decoder could not read this file",
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun publish(discovered: Int, indexed: Int, failed: Int, message: String?) {
        main.post {
            progress = ScanProgress(
                running = true,
                discovered = discovered,
                indexed = indexed,
                failed = failed,
                message = message,
            )
        }
    }

    /** Whether the app still holds a persisted grant for a folder it lists. */
    fun hasGrant(treeUri: String): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.isReadPermission && it.uri.toString() == treeUri
        }
}
