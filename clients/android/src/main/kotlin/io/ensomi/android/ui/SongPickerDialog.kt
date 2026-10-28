package io.ensomi.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.ensomi.android.library.LibraryEntry

/**
 * The iOS client's "Audio" row opens the system's file importer. Android has the music already indexed, so
 * the row opens this list first and keeps a file picker for anything outside it.
 */
@Composable
fun SongPickerDialog(
    songs: List<LibraryEntry>,
    onPick: (LibraryEntry) -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .background(EnsomiStyle.panel, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose a song", color = EnsomiStyle.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (songs.isEmpty()) {
                    "No songs are indexed yet. Pick an audio file instead."
                } else {
                    "${songs.size} songs on this phone."
                },
                color = EnsomiStyle.textSecondary,
                fontSize = 13.sp,
            )

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(songs, key = { it.uri }) { song ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(song) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.displayTitle,
                                color = EnsomiStyle.textPrimary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val second = listOfNotNull(
                                song.artist?.takeIf { it.isNotBlank() && it != "<unknown>" },
                                song.durationMs?.let { formatSongLength(it) },
                            ).joinToString(" - ")
                            if (second.isNotBlank()) {
                                Text(second, color = EnsomiStyle.textSecondary, fontSize = 12.sp)
                            }
                        }
                        Text("Use", color = EnsomiStyle.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPickFile) { Text("Choose a file...", color = EnsomiStyle.accent) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = EnsomiStyle.textSecondary) }
            }
        }
    }
}
