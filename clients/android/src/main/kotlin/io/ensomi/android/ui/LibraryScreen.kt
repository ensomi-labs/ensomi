package io.ensomi.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ensomi.android.library.LibraryFolder
import io.ensomi.android.library.LibraryScanner
import io.ensomi.android.library.LibraryStore
import java.text.DateFormat
import java.util.Date

/**
 * S2 Library: the folders the user granted, what the last scan found, and the way to run it again.
 * The fixture recorder and manual resolution are Apple-only or not ported yet, so they are absent.
 */
@Composable
fun LibraryScreen(
    library: LibraryStore,
    scanner: LibraryScanner,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
) {
    val index = library.index
    val progress = scanner.progress

    // While a scan runs its counters are the truth; between scans the stored index is, so the page
    // still reports what it found after the process came back.
    val discovered = if (progress.running) progress.discovered else index.entries.size
    val indexed = if (progress.running) progress.indexed else index.entries.count { it.failure == null }
    val failed = if (progress.running) progress.failed else index.entries.count { it.failure != null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Music Folders", style = MaterialTheme.typography.titleMedium)

                if (index.folders.isEmpty()) {
                    Text("Add Your Music", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Choose a folder of audio files to use for recognition and play.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    index.folders.forEach { folder ->
                        FolderRow(folder, onRemove = { library.removeFolder(folder.treeUri) })
                    }
                }

                Button(onClick = onPickFolder) { Text("Add Music Folder...") }
            }
        }

        if (progress.message != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Library Error", style = MaterialTheme.typography.titleMedium)
                    Text(
                        progress.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scan Status", style = MaterialTheme.typography.titleMedium)

                StatRow("Discovered", discovered.toString())
                StatRow("Indexed", indexed.toString())
                StatRow("Failed", failed.toString())
                StatRow(
                    "Last scan",
                    index.lastScanMs?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Never",
                )

                if (progress.running) {
                    Text(
                        "Scanning ${indexed} of ${discovered}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRescan, enabled = index.folders.isNotEmpty() && !progress.running) {
                        Text("Rescan Library")
                    }
                    OutlinedButton(onClick = onRescan, enabled = index.folders.isNotEmpty() && !progress.running) {
                        Text("Refresh Status")
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: LibraryFolder, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(folder.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                middleTruncate(folder.treeUri, 44),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.4f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** The path S2 truncates in the middle, because the tail is what identifies a SAF tree. */
internal fun middleTruncate(value: String, max: Int): String {
    if (value.length <= max) return value
    val half = (max - 1) / 2
    return value.take(half) + "..." + value.takeLast(half)
}
