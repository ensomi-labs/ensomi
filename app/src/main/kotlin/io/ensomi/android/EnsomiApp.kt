package io.ensomi.android

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.ensomi.android.chart.MakeChartScreen
import io.ensomi.android.chart.MakeChartViewModel
import io.ensomi.android.diagnostics.DiagnosticsScreen
import io.ensomi.android.library.LibraryScreen
import io.ensomi.android.model.Notice
import io.ensomi.android.model.NoticeTextScreen
import io.ensomi.android.model.NoticesScreen
import io.ensomi.android.play.GameOrientation
import io.ensomi.android.play.PlayScreen
import io.ensomi.android.play.ResultsScreen
import io.ensomi.android.prototype.AppGraph
import io.ensomi.android.prototype.Scenario
import io.ensomi.android.settings.CalibrationScreen
import io.ensomi.android.settings.SettingsScreen
import io.ensomi.android.ui.LocalNotWired
import io.ensomi.android.ui.NotWired
import kotlinx.coroutines.launch

/** The app's navigation: one back stack of keys (Destinations.kt), each with its entry below. */
@Composable
fun EnsomiApp(scenario: Scenario, graph: AppGraph) {
    val backStack = rememberNavBackStack(*scenario.backStack.toTypedArray())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val notWired = remember(resources) {
        NotWired { what -> scope.launch { snackbar.showSnackbar(resources.getString(R.string.not_wired, what)) } }
    }
    val songs by graph.songs.songs.collectAsStateWithLifecycle()
    val addSongs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { documents ->
        // Cancelling the picker is not an error and says nothing.
        if (documents.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val result = graph.songs.add(documents)
            val message = when {
                result.unreadable.size == 1 -> resources.getString(R.string.library_unreadable_one, result.unreadable.first())
                result.unreadable.size > 1 -> resources.getQuantityString(
                    R.plurals.library_unreadable_many,
                    result.unreadable.size,
                    result.unreadable.size,
                )
                result.added == 0 && result.alreadyThere > 0 -> resources.getString(R.string.library_already_there)
                else -> null
            }
            if (message != null) snackbar.showSnackbar(message)
        }
    }
    // The song whose file is being picked anew; saved, since the picker can outlive the activity.
    var findingSong by rememberSaveable { mutableStateOf<String?>(null) }
    val findAgain = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { document ->
        val id = findingSong
        findingSong = null
        if (document == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            if (!graph.songs.findAgain(id, document)) snackbar.showSnackbar(resources.getString(R.string.library_find_failed))
        }
    }
    // A file can go while the app is in the background.
    LifecycleStartEffect(graph.songs) {
        graph.songs.refresh()
        onStopOrDispose { }
    }
    val model by graph.model.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val route by graph.settings.route.collectAsStateWithLifecycle()

    // The game, from the count-in to the results, holds the play orientation; the other screens follow the phone.
    val inGame = backStack.lastOrNull().let { it is Play || it is Results }
    GameOrientation(if (inGame) settings.orientation else null)

    CompositionLocalProvider(LocalNotWired provides notWired) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = backStack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    // Scopes each screen's ViewModel to its entry, cleared when the entry is popped.
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<Library> {
                        LibraryScreen(
                            songs = songs,
                            model = model,
                            onAddSongs = { addSongs.launch(arrayOf(AUDIO)) },
                            onOpenSong = { backStack.add(MakeChart(it.id)) },
                            onFindAgain = { song ->
                                findingSong = song.id
                                findAgain.launch(arrayOf(AUDIO))
                            },
                            onRemoveSong = { graph.songs.remove(it.id) },
                            onOpenSettings = { backStack.add(Settings) },
                            onDownloadModel = graph.model::download,
                            onCancelDownload = graph.model::cancel,
                        )
                    }
                    entry<MakeChart> { key ->
                        val song = graph.songs.song(key.songId) ?: return@entry
                        val viewModel = viewModel {
                            MakeChartViewModel(song, graph.charts, scenario.sections, scenario.sectionsOpen)
                        }
                        MakeChartScreen(
                            viewModel = viewModel,
                            model = model,
                            onBack = { backStack.removeLastOrNull() },
                            onPlay = { backStack.add(Play(key.songId, loop = false)) },
                            onDownloadModel = graph.model::download,
                            onCancelDownload = graph.model::cancel,
                            onMake = { notWired("Making charts (plan step 5)") },
                            onStop = { notWired("Stopping a chart (plan step 5)") },
                        )
                    }
                    entry<Play> { key ->
                        val song = key.songId?.let(graph.songs::song)
                        PlayScreen(
                            title = song?.title ?: stringResource(R.string.play_preview_title),
                            detail = stringResource(R.string.play_sample_detail),
                            settings = settings,
                            loop = key.loop,
                            onFinished = { result -> backStack.replaceLast(Results(key.songId, result)) },
                            onQuit = { backStack.removeLastOrNull() },
                            autoPauseAfterMs = scenario.pauseAfterMs,
                            holdCountdown = scenario.holdCountdown,
                        )
                    }
                    entry<Results> { key ->
                        ResultsScreen(
                            result = key.result,
                            settings = settings,
                            route = route,
                            onChange = graph.settings::update,
                            onPlayAgain = { backStack.replaceLast(Play(key.songId, loop = false)) },
                            onDone = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<Settings> {
                        SettingsScreen(
                            settings = settings,
                            route = route,
                            model = model,
                            versionName = versionName(context),
                            onChange = graph.settings::update,
                            onBack = { backStack.removeLastOrNull() },
                            onCalibrate = { backStack.add(Calibration) },
                            onPreview = { backStack.add(Play(null, loop = true)) },
                            onDiagnostics = { backStack.add(Diagnostics) },
                            onDownloadModel = graph.model::download,
                            onCancelDownload = graph.model::cancel,
                            onRemoveModel = graph.model::remove,
                            onNotices = { backStack.add(Notices) },
                            firstItem = scenario.settingsItem,
                        )
                    }
                    entry<Calibration> {
                        CalibrationScreen(
                            settings = settings,
                            route = route,
                            initialStep = scenario.calibrationStep,
                            onChange = graph.settings::update,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<Notices> {
                        NoticesScreen(
                            notices = graph.model.notices,
                            onOpen = { backStack.add(NoticeText(it.path, it.asset)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<NoticeText> { key ->
                        NoticeTextScreen(
                            notice = Notice(key.path, key.asset),
                            modelDirectory = graph.model.directory,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<Diagnostics> { DiagnosticsScreen(onBack = { backStack.removeLastOrNull() }) }
                },
            )
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars),
            )
        }
    }
}

/** What the song pickers offer: any audio file. */
private const val AUDIO = "audio/*"

private fun NavBackStack<NavKey>.replaceLast(key: NavKey) {
    removeLastOrNull()
    add(key)
}

private fun versionName(context: android.content.Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    return "${info.versionName} (${info.longVersionCode})"
}
