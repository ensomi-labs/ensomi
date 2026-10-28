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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.ensomi.android.library.LibraryEntry
import io.ensomi.android.library.LibraryStore
import io.ensomi.android.settings.EnsomiSettings

/**
 * S3 Play setup. The audio side works: a library song can be chosen and its length read. Everything
 * downstream of the star target needs the device engine, which this build does not link yet, so those
 * controls report why they are unavailable instead of pretending to run.
 */
@Composable
fun PlayScreen(library: LibraryStore, settings: EnsomiSettings) {
    val entries = library.index.entries
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = entries.firstOrNull { it.uri == selectedUri }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ensomi", style = MaterialTheme.typography.headlineSmall)
                    Text("4K", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Choose how you want to play",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (entries.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No audio yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add a music folder in Library, then rescan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Audio", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Generate a Chart · create a chart from a local song",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    selected?.let { "${it.displayTitle} · ${formatLength(it.durationMs)}" }
                        ?: "Choose a song below",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Songs", style = MaterialTheme.typography.titleMedium)
                // A plain column: the page already scrolls, and a LazyColumn inside a vertical scroll
                // is measured with an infinite height, which Compose refuses.
                entries.forEach { entry ->
                    SongRow(
                        entry = entry,
                        selected = entry.uri == selectedUri,
                        onClick = { selectedUri = entry.uri },
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Chart", style = MaterialTheme.typography.titleMedium)
                Text(
                    "osu!mania star difficulty",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Unavailable until the device engine answers with its range.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    enabled = false,
                    label = { Text("Seed") },
                    placeholder = { Text("Blank makes a new chart each time") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "The device engine is not linked in this build: the chart model, the star range and " +
                        "generation all arrive with the runtime binding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {}, enabled = false) { Text("Generate") }
                    OutlinedButton(onClick = {}, enabled = false) { Text("Save .osu…") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Session", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Scroll speed ${"%.1f".format(settings.scrollSpeed)} · judge ${settings.judgeDifficulty}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SongRow(entry: LibraryEntry, selected: Boolean, onClick: () -> Unit) {
    val label = "${entry.displayTitle} · ${formatLength(entry.durationMs)}"
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}

/** The `m:ss` the setup shows, and "unknown" when the file would not read. */
internal fun formatLength(durationMs: Long?): String {
    if (durationMs == null || durationMs <= 0) return "unknown"
    val totalSeconds = durationMs / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
