package io.ensomi.android.ui

import android.os.SystemClock
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.ensomi.android.audio.LibraryPlayer
import io.ensomi.android.audio.PlayerState
import io.ensomi.android.clock.AnchorAudioClock
import io.ensomi.android.clock.AudioAnchor
import io.ensomi.android.library.LibraryEntry
import io.ensomi.android.library.LibraryStore
import io.ensomi.android.settings.EnsomiSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * S3 Play setup. The audio side is real: a library song can be chosen, played, and its heard position
 * read back through the audio clock. Everything downstream of the star target needs the device engine,
 * which this build does not link yet, so those controls report why they are unavailable.
 */
@Composable
fun PlayScreen(library: LibraryStore, settings: EnsomiSettings) {
    val entries = library.index.entries
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = entries.firstOrNull { it.uri == selectedUri }

    val context = LocalContext.current
    val player = remember { LibraryPlayer(context) }
    var clockPositionMs by remember { mutableStateOf<Double?>(null) }

    DisposableEffect(Unit) {
        onDispose { player.stop() }
    }

    // The displayed position is the audio clock's, not the player's last reading: between readings the
    // clock extrapolates, which is what the session will do once it exists.
    LaunchedEffect(player.state, selectedUri) {
        clockPositionMs = null
        if (player.state != PlayerState.PLAYING) return@LaunchedEffect
        var clock: AnchorAudioClock? = null
        while (currentCoroutineContext().isActive && player.isActive) {
            val position = player.positionMs()
            if (position != null) {
                val now = SystemClock.elapsedRealtimeNanos()
                clock = clock?.apply { onReading(AudioAnchor(position, now)) }
                    ?: AnchorAudioClock(AudioAnchor(position, now))
                clockPositionMs = clock.positionMsAt(SystemClock.elapsedRealtimeNanos())
            }
            delay(80)
        }
    }

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
                    "Generate a Chart - create a chart from a local song",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    selected?.let { "${it.displayTitle} - ${formatLength(it.durationMs)}" }
                        ?: "Choose a song below",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (player.isActive) {
                        OutlinedButton(onClick = { player.stop() }) { Text("Stop") }
                    } else {
                        Button(
                            onClick = { selected?.let { player.play(android.net.Uri.parse(it.uri)) } },
                            enabled = selected != null && selected.failure == null,
                        ) { Text("Play") }
                    }
                    Text(
                        when (player.state) {
                            PlayerState.IDLE -> "idle"
                            PlayerState.LOADING -> "loading"
                            PlayerState.PLAYING -> "playing"
                            PlayerState.ENDED -> "ended"
                            PlayerState.FAILED -> "failed"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    "Heard ${formatLength(clockPositionMs?.toLong())} - audio clock, not the decoder's position",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                player.failure?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
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
                        onClick = {
                            player.stop()
                            selectedUri = entry.uri
                        },
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
                    OutlinedButton(onClick = {}, enabled = false) { Text("Save .osu...") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Session", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Scroll speed %.1f - judge %s".format(settings.scrollSpeed, settings.judgeDifficulty),
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
    val label = "${entry.displayTitle} - ${formatLength(entry.durationMs)}"
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
