package io.ensomi.android.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads and writes the library document and publishes it as Compose state. Every mutation writes the
 * file, so a process death cannot leave the UI showing a library the next launch will not have.
 */
class LibraryStore(context: Context) {
    private val file = File(context.filesDir, "library.json")

    var index by mutableStateOf(load())
        private set

    fun addFolder(folder: LibraryFolder) {
        if (index.folders.any { it.treeUri == folder.treeUri }) return
        update { it.copy(folders = it.folders + folder) }
    }

    fun removeFolder(treeUri: String) {
        // The files stay on disk; only the grant and the entries this folder produced go away.
        update { current ->
            current.copy(
                folders = current.folders.filterNot { it.treeUri == treeUri },
                entries = current.entries.filterNot { it.treeUri == treeUri },
            )
        }
    }

    fun replaceEntries(entries: List<LibraryEntry>, scannedAtMs: Long) {
        update { it.copy(entries = entries, lastScanMs = scannedAtMs) }
    }

    fun entriesFor(treeUri: String): List<LibraryEntry> = index.entries.filter { it.treeUri == treeUri }

    private fun update(transform: (LibraryIndex) -> LibraryIndex) {
        val next = transform(index)
        index = next
        file.writeText(json.encodeToString(LibraryIndex.serializer(), next))
    }

    private fun load(): LibraryIndex {
        if (!file.exists()) return LibraryIndex()
        return runCatching {
            json.decodeFromString(LibraryIndex.serializer(), file.readText())
        }.getOrDefault(LibraryIndex())
    }

    private companion object {
        /** A document this client alone writes; unknown keys stay readable across versions. */
        val json = Json {
            encodeDefaults = true
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}
