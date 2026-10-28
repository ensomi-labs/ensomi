package io.ensomi.android.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import io.ensomi.android.generation.ChartGenerationService
import io.ensomi.android.library.LibraryEntry
import io.ensomi.android.library.LibraryFolder
import io.ensomi.android.library.LibraryScanner
import io.ensomi.android.library.LibraryStore
import io.ensomi.android.library.MediaStoreSongs
import io.ensomi.android.settings.EnsomiSettings
import uniffi.ensomi_runtime_ffi.Chart

/** The two destinations the iOS client's tab bar has. */
enum class Destination(val title: String) {
    LIBRARY("Library"),
    PLAY("Play"),
}

/** What the app holds while it runs: settings, the folder library, the scan, and the platform's songs. */
class WorkbenchState(context: Context) {
    val settings = EnsomiSettings(context)
    val library = LibraryStore(context)
    val scanner = LibraryScanner(context, library)
    val generation = ChartGenerationService(context)
}

/**
 * The workbench the iOS client presents: a tab bar over Library and Play, a settings action in the top bar,
 * and the scene over both while a run is on. The platform's music index fills the Play tab's song list, so a
 * newcomer can pick a song before learning anything about folders.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun Workbench(
    state: WorkbenchState,
    onPickFolder: () -> Unit,
    onPickAudioFile: () -> Unit,
    onOpenProbe: () -> Unit,
) {
    val context = LocalContext.current
    var destination by rememberSaveable { mutableStateOf(Destination.PLAY) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var scene by remember { mutableStateOf<Pair<LibraryEntry, Chart>?>(null) }

    var songs by remember { mutableStateOf<List<LibraryEntry>>(emptyList()) }
    var hasMusicPermission by remember { mutableStateOf(MediaStoreSongs.hasPermission(context)) }

    fun reload() {
        songs = MediaStoreSongs.query(context) + state.library.index.entries
    }

    LaunchedEffect(hasMusicPermission, state.library.index.entries.size) { reload() }

    val askForMusic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMusicPermission = granted
        reload()
    }

    LaunchedEffect(hasMusicPermission) {
        if (!hasMusicPermission) askForMusic.launch(MediaStoreSongs.permission)
    }

    val playing = scene
    if (playing != null) {
        PlayScene(entry = playing.first, chart = playing.second, settings = state.settings, onExit = { scene = null })
        return
    }

    Scaffold(
        containerColor = EnsomiStyle.backgroundTop,
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            TopAppBar(
                title = { Text(destination.title, color = EnsomiStyle.textPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = EnsomiStyle.backgroundTop),
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, "Settings", tint = EnsomiStyle.textSecondary)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = EnsomiStyle.panel,
                // MIUI's gesture strip sits over the last few dp of the window; keep the tabs clear of it.
                modifier = Modifier.padding(bottom = 48.dp),
            ) {
                Destination.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = destination == entry,
                        onClick = { destination = entry },
                        icon = {
                            Icon(
                                if (entry == Destination.LIBRARY) Icons.Filled.List else Icons.Filled.PlayArrow,
                                contentDescription = entry.title,
                            )
                        },
                        label = { Text(entry.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EnsomiStyle.actionInk,
                            selectedTextColor = EnsomiStyle.accent,
                            indicatorColor = EnsomiStyle.accent,
                            unselectedIconColor = EnsomiStyle.textSecondary,
                            unselectedTextColor = EnsomiStyle.textSecondary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().background(EnsomiStyle.backdrop).padding(padding)) {
            when (destination) {
                Destination.LIBRARY -> LibraryScreen(
                    library = state.library,
                    scanner = state.scanner,
                    onPickFolder = onPickFolder,
                    onRescan = { state.scanner.scan() },
                    onRefresh = { state.scanner.refreshStatus(); reload() },
                    songCount = songs.size,
                )

                Destination.PLAY -> PlayScreen(
                    songs = songs,
                    settings = state.settings,
                    generation = state.generation,
                    onPlayScene = { entry, chart -> scene = entry to chart },
                    onPickAudioFile = onPickAudioFile,
                )
            }
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            settings = state.settings,
            library = state.library,
            scanner = state.scanner,
            generation = state.generation,
            onPickFolder = onPickFolder,
            onOpenProbe = onOpenProbe,
            onModelChanged = { },
            onDismiss = {
                settingsOpen = false
                reload()
            },
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
        displayName = name.substringAfterLast('/').substringAfterLast(':').ifBlank { name },
        addedAtMs = System.currentTimeMillis(),
    )
}
