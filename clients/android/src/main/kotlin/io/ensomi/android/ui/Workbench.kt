package io.ensomi.android.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import io.ensomi.android.library.LibraryEntry
import io.ensomi.android.library.LibraryFolder
import io.ensomi.android.library.LibraryScanner
import io.ensomi.android.library.LibraryStore
import io.ensomi.android.settings.EnsomiSettings

/** The two destinations S1 gives the bottom bar. */
enum class Destination(val title: String) {
    LIBRARY("Library"),
    PLAY("Play"),
}

/** What the workbench holds: the settings, the library and the scan that fills it. */
class WorkbenchState(context: Context) {
    val settings = EnsomiSettings(context)
    val library = LibraryStore(context)
    val scanner = LibraryScanner(context, library)
}

/**
 * S1 Workbench: a bottom bar over Library and Play, and a Settings action in the top bar. The sheet
 * and the folder picker live here because both destinations reach them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Workbench(
    state: WorkbenchState,
    onPickFolder: () -> Unit,
    onOpenProbe: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.LIBRARY) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var scene by remember { mutableStateOf<LibraryEntry?>(null) }

    val playing = scene
    if (playing != null) {
        PlayScene(entry = playing, settings = state.settings, onExit = { scene = null })
        return
    }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            TopAppBar(
                title = { Text("Ensomi") },
                actions = {
                    TextButton(onClick = { settingsOpen = true }) { Text("Settings") }
                },
            )
        },
        bottomBar = {
            TabRow(
                selectedTabIndex = destination.ordinal,
                // MIUI's gesture strip sits over the last few dp of the window; keep the tabs clear of it.
                modifier = Modifier.padding(bottom = 56.dp),
            ) {
                Destination.entries.forEach { entry ->
                    Tab(
                        selected = destination == entry,
                        onClick = { destination = entry },
                        text = { Text(entry.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (destination) {
                Destination.LIBRARY -> LibraryScreen(
                    library = state.library,
                    scanner = state.scanner,
                    onPickFolder = onPickFolder,
                    onRescan = { state.scanner.scan() },
                    onRefresh = { state.scanner.refreshStatus() },
                )

                Destination.PLAY -> PlayScreen(
                    library = state.library,
                    settings = state.settings,
                    onPlayScene = { scene = it },
                )
            }
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            settings = state.settings,
            onOpenProbe = onOpenProbe,
            onDismiss = { settingsOpen = false },
        )
    }
}

/** Turns a tree URI the picker returned into the folder the library keeps. */
fun folderFromUri(context: Context, uri: android.net.Uri): LibraryFolder {
    val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
        .getOrNull()
        ?: uri.lastPathSegment.orEmpty()
    return LibraryFolder(
        treeUri = uri.toString(),
        displayName = name.ifBlank { "Music folder" },
        addedAtMs = System.currentTimeMillis(),
    )
}
