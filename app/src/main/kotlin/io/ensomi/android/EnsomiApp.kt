package io.ensomi.android

import android.content.pm.PackageManager
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
    val model by graph.model.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val route by graph.settings.route.collectAsStateWithLifecycle()

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
                            onAddSongs = { notWired("Adding songs (plan step 2)") },
                            onOpenSong = { backStack.add(MakeChart(it.id)) },
                            onOpenSettings = { backStack.add(Settings) },
                            onDownloadModel = { notWired("Downloading the model (plan step 4)") },
                            onCancelDownload = { notWired("Cancelling the download (plan step 4)") },
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
                            onDownloadModel = { notWired("Downloading the model (plan step 4)") },
                            onCancelDownload = { notWired("Cancelling the download (plan step 4)") },
                            onMake = { notWired("Making charts (plan steps 3 and 5)") },
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
                            onDownloadModel = { notWired("Downloading the model (plan step 4)") },
                            onCancelDownload = { notWired("Cancelling the download (plan step 4)") },
                            onRemoveModel = { notWired("Removing the model (plan step 4)") },
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

private fun NavBackStack<NavKey>.replaceLast(key: NavKey) {
    removeLastOrNull()
    add(key)
}

private fun versionName(context: android.content.Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    return "${info.versionName} (${info.longVersionCode})"
}
