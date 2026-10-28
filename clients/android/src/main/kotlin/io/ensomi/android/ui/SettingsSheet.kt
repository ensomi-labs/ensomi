package io.ensomi.android.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.ensomi.android.RuntimeSelfCheck
import io.ensomi.android.generation.ChartGenerationService
import io.ensomi.android.library.LibraryScanner
import io.ensomi.android.library.LibraryStore
import io.ensomi.android.model.InstallProgress
import io.ensomi.android.model.ModelDownloader
import io.ensomi.android.model.ModelInstaller
import io.ensomi.android.settings.EnsomiSettings
import io.ensomi.android.settings.SettingsKeys

/**
 * Settings, in the order a player needs it: how the game feels, then the two things that make it work (the
 * chart model and the music folders), then the runtime checks a developer might want.
 */
@Composable
fun SettingsSheet(
    settings: EnsomiSettings,
    library: LibraryStore,
    scanner: LibraryScanner,
    generation: ChartGenerationService,
    onPickFolder: () -> Unit,
    onOpenProbe: () -> Unit,
    onModelChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Section("Playing")

                LabeledValue("Scroll speed", "%.1f".format(settings.scrollSpeed))
                Slider(
                    value = settings.scrollSpeed,
                    onValueChange = { settings.updateScrollSpeed(it) },
                    valueRange = SettingsKeys.MIN_SCROLL_SPEED..SettingsKeys.MAX_SCROLL_SPEED,
                )

                LabeledValue("Music volume", "${(settings.musicVolume * 100).toInt()}%")
                Slider(
                    value = settings.musicVolume,
                    onValueChange = { settings.updateMusicVolume(it) },
                    valueRange = 0f..1f,
                )

                LabeledValue("Visual offset", "${settings.visualOffsetMs.toInt()} ms")
                Slider(
                    value = settings.visualOffsetMs,
                    onValueChange = { settings.updateVisualOffsetMs(it) },
                    valueRange = -500f..500f,
                )

                Text("Timing window", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SettingsKeys.DIFFICULTIES.forEach { difficulty ->
                        FilterChip(
                            selected = settings.judgeDifficulty == difficulty,
                            onClick = { settings.updateJudgeDifficulty(difficulty) },
                            label = { Text(difficulty) },
                        )
                    }
                }

                Section("Chart model")
                ChartModelSection(generation = generation, onChanged = onModelChanged)

                Section("Music folders")
                FolderSection(library = library, scanner = scanner, onPickFolder = onPickFolder)

                Section("Developer")
                RuntimeCheck()
                OutlinedButton(onClick = onOpenProbe) { Text("Open probe") }
            }
        },
    )
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

/**
 * The model the generator runs: what is installed, how to get it, and how to remove it. Downloading is the
 * path a phone takes; installing from a folder stays for a package that arrived another way.
 */
@Composable
private fun ChartModelSection(generation: ChartGenerationService, onChanged: () -> Unit) {
    val context = LocalContext.current
    val installer = remember { ModelInstaller(context) }
    val downloader = remember { ModelDownloader(context) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var status by remember { mutableStateOf(installer.status()) }
    var progress by remember { mutableStateOf<InstallProgress?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            progress = InstallProgress(running = true)
            Thread({
                val outcome = runCatching {
                    installer.install(uri, source = "folder") { update -> main.post { progress = update } }
                }
                main.post {
                    outcome
                        .onSuccess { status = installer.status(); progress = null; onChanged() }
                        .onFailure { progress = InstallProgress(message = it.message ?: "install failed") }
                }
            }, "ensomi-model-install").start()
        }
    }

    val installed = status.installed
    Text(
        if (installed == null) {
            "Not downloaded yet. The model is about 115 MB and is needed once."
        } else {
            "${installed.modelName} ${installed.version} - ${installed.fileCount} files, " +
                "${installed.totalBytes / (1024 * 1024)} MB, ${installed.license}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (installed == null) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (installed == null) {
            Button(
                onClick = {
                    progress = InstallProgress(running = true)
                    Thread({
                        val outcome = runCatching {
                            downloader.download { update -> main.post { progress = update } }
                        }
                        main.post {
                            outcome
                                .onSuccess { status = installer.status(); progress = null; onChanged() }
                                .onFailure { progress = InstallProgress(message = it.message ?: "download failed") }
                        }
                    }, "ensomi-model-download").start()
                },
                enabled = progress?.running != true,
            ) { Text("Download model") }
        } else {
            OutlinedButton(
                onClick = { installer.remove(); status = installer.status(); onChanged() },
                enabled = progress?.running != true,
            ) { Text("Remove model") }
        }

        OutlinedButton(
            onClick = { picker.launch(null) },
            enabled = progress?.running != true,
        ) { Text("Install from folder") }
    }

    progress?.let { update ->
        if (update.running) {
            Text(
                "${update.copied} of ${update.total} files (${update.bytes / (1024 * 1024)} MB)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        update.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The folder source, for music the system index does not carry. */
@Composable
private fun FolderSection(library: LibraryStore, scanner: LibraryScanner, onPickFolder: () -> Unit) {
    val index = library.index
    val progress = scanner.progress

    if (index.folders.isEmpty()) {
        Text(
            "Music from the system's own library is already listed. Add a folder only for files it does not index.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        index.folders.forEach { folder ->
            Text(folder.displayName, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "${index.entries.size} songs indexed" + (progress.message?.let { " - $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPickFolder) { Text("Add folder") }
        if (index.folders.isNotEmpty()) {
            OutlinedButton(onClick = { scanner.scan() }, enabled = !progress.running) {
                Text("Rescan")
            }
        }
    }
}

@Composable
private fun RuntimeCheck() {
    var result by remember { mutableStateOf<String?>(null) }

    OutlinedButton(
        onClick = {
            result = RuntimeSelfCheck.run().getOrElse { error ->
                "failed: ${error::class.java.simpleName}: ${error.message}"
            }
        },
    ) { Text("Check runtime") }

    result?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
