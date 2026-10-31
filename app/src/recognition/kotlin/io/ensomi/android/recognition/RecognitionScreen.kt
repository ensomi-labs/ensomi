@file:OptIn(ExperimentalMaterial3Api::class)

package io.ensomi.android.recognition

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ensomi.android.R
import io.ensomi.android.settings.PlaySettings
import io.ensomi.android.ui.ActionButton
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The recognition session's test surface: start it, see each stage with its state and time, read what to do by hand
 * from arm's length, see what failed and try again from there. It is a screen for testing, in plain Material 3, not a
 * designed part of the game.
 */
@Composable
fun RecognitionScreen(controller: RecognitionController, onBack: () -> Unit) {
    val state by controller.session.collectAsStateWithLifecycle()
    val options by controller.options.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(controller) {
        controller.setFrontmost(true)
        onPauseOrDispose { controller.setFrontmost(false) }
    }
    // A screen that sleeps stops the microphone, and Sync may take minutes with nobody touching it.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // A request or a failure comes in above whatever is in view, and it is what has to be read.
    val list = rememberLazyListState()
    LaunchedEffect(state?.failure, state?.asks) {
        val session = state
        if (session != null && (session.failure != null || session.asks.isNotEmpty())) list.animateScrollToItem(0)
    }
    var granted by remember { mutableStateOf(permissionsMissing(context).isEmpty()) }
    LifecycleResumeEffect(context) {
        granted = permissionsMissing(context).isEmpty()
        onPauseOrDispose { }
    }
    var afterPermissions by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = permissionsMissing(context).isEmpty()
        afterPermissions?.invoke()
        afterPermissions = null
    }
    fun askThen(then: () -> Unit) {
        val missing = permissionsMissing(context)
        if (missing.isEmpty()) {
            then()
        } else {
            afterPermissions = then
            permissions.launch(missing.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recognition_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = list,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val current = state
                current?.takeIf { it.asks.isNotEmpty() }?.let { session ->
                    item(key = "asks") { AsksCard(session.asks) }
                }
                current?.failure?.let { failure ->
                    item(key = "failure") {
                        FailureCard(failure, canRetry = current?.running == true, onRetry = { controller.retry(failure.kind.retryFrom) })
                    }
                }
                item(key = "controls") {
                    Controls(
                        options = options,
                        running = current?.running == true,
                        onPath = { path -> controller.updateOptions { it.copy(path = path) } },
                        onStart = { askThen { controller.start(options.path) } },
                        onStop = controller::stop,
                        onPaste = { clipboardText(context)?.let { controller.share(it, source = "clipboard") } },
                    )
                }
                if (!granted) {
                    item(key = "permissions") {
                        OutlinedButton(onClick = { askThen { } }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.recognition_allow))
                        }
                    }
                }
                current?.result?.let { result -> item(key = "result") { ResultCard(result) } }
                if (current != null) {
                    item(key = "song") { SongFacts(current) }
                    item(key = "stages") { StagesCard(current, onRetry = controller::retry) }
                    if (current.held.isNotEmpty()) {
                        item(key = "held-title") { SectionTitle(R.string.recognition_held) }
                        items(current.held, key = { it.path }) { file ->
                            ListItem(
                                headlineContent = { Text(file.name) },
                                trailingContent = {
                                    TextButton(onClick = { controller.useFile(file.path) }) {
                                        Text(stringResource(R.string.recognition_use_file))
                                    }
                                },
                            )
                        }
                    }
                }
                item(key = "options") { OptionsCard(controller, options) }
                item(key = "paths") { Paths(controller, context) }
            }
        }
    }
}

private val REQUIRED = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_MEDIA_AUDIO)

private fun permissionsMissing(context: Context): List<String> =
    REQUIRED.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

private fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

/** What the person must do now: in both languages, large enough to read with the tablet on a table. */
@Composable
private fun AsksCard(asks: List<Ask>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            asks.forEachIndexed { index, ask ->
                val prefix = if (asks.size > 1) "${index + 1}. " else ""
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(prefix + stringResource(ask.chinese()), fontSize = 34.sp, lineHeight = 42.sp, style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(ask.english()), fontSize = 22.sp, lineHeight = 28.sp, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun FailureCard(failure: Failure, canRetry: Boolean, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.recognition_failed_at, stringResource(failure.stage.label())), style = MaterialTheme.typography.titleMedium)
            }
            val (chinese, english) = failure.kind.words()
            Text(stringResource(chinese), fontSize = 24.sp, lineHeight = 30.sp, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(english), fontSize = 18.sp, lineHeight = 24.sp, style = MaterialTheme.typography.bodyLarge)
            failure.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (canRetry) {
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.recognition_retry_from, stringResource(failure.kind.retryFrom.label())))
                }
            }
        }
    }
}

@Composable
private fun Controls(
    options: SessionOptions,
    running: Boolean,
    onPath: (LaunchPath) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LaunchPath.entries.forEachIndexed { index, path ->
                SegmentedButton(
                    selected = options.path == path,
                    onClick = { onPath(path) },
                    shape = SegmentedButtonDefaults.itemShape(index, LaunchPath.entries.size),
                    enabled = !running,
                    icon = {},
                ) { Text(stringResource(path.label())) }
            }
        }
        if (running) {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recognition_stop)) }
        } else {
            ActionButton(
                stringResource(R.string.recognition_start),
                onClick = onStart,
                icon = R.drawable.ic_play,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recognition_paste)) }
    }
}

@Composable
private fun SongFacts(state: SessionState) {
    val song = state.song
    val audio = state.audio
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (song != null) {
            Text(listOfNotNull(song.title, song.artist).joinToString(" · ").ifEmpty { "—" }, style = MaterialTheme.typography.titleLarge)
            song.songId?.let { Text(stringResource(R.string.recognition_song_id, it.toString()), style = MaterialTheme.typography.bodyMedium) }
        }
        audio?.let { Text(it.file.path, style = MaterialTheme.typography.bodySmall) }
        state.syncState?.let { sync ->
            Text(
                stringResource(
                    R.string.recognition_sync_state,
                    sync,
                    state.syncDetail ?: "—",
                    state.syncConfidence?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—",
                    state.listenedMs / 1000,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.rmsDb?.let {
            Text(stringResource(R.string.recognition_level, String.format(Locale.ROOT, "%.1f", it)), style = MaterialTheme.typography.bodyMedium)
        }
        state.joined?.let {
            Text(
                stringResource(R.string.recognition_joined, seconds(it.joinMs), seconds(it.heardMs), it.notesKept, it.notesSkipped),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StagesCard(state: SessionState, onRetry: (Stage) -> Unit) {
    var nowNanos by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(state.running) {
        while (state.running) {
            delay(CLOCK_TICK_MS)
            nowNanos = SystemClock.elapsedRealtimeNanos()
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column {
            state.stages.forEachIndexed { index, view ->
                if (index > 0) HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(view.stage.label())) },
                    supportingContent = view.detail?.let { detail -> { Text(detail) } },
                    leadingContent = {
                        Icon(painterResource(view.status.icon()), contentDescription = null, modifier = Modifier.size(24.dp))
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(stringResource(view.status.label()), style = MaterialTheme.typography.labelLarge)
                                stageTime(view, nowNanos)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            if (state.running && view.stage in RETRY_STAGES && view.status != StageStatus.WAITING) {
                                val label = stringResource(view.stage.label())
                                IconButton(onClick = { onRetry(view.stage) }) {
                                    Icon(
                                        painterResource(R.drawable.ic_refresh),
                                        contentDescription = stringResource(R.string.recognition_retry_from, label),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/** How long a stage took, or has been running. */
private fun stageTime(view: StageView, nowNanos: Long): String? {
    val started = view.startedNanos ?: return null
    // The clock on the screen ticks, so a stage may have started after its last reading.
    val ended = maxOf(view.endedNanos ?: nowNanos, started)
    return String.format(Locale.ROOT, "%.1f s", (ended - started) / 1e9)
}

private fun seconds(ms: Double): String = String.format(Locale.ROOT, "%.1f s", ms / 1000)

@Composable
private fun ResultCard(result: GameSummary) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(String.format(Locale.ROOT, "%.2f%%", result.accuracy * 100), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.recognition_result, result.perfect, result.good, result.miss, result.maxCombo))
            result.meanOffsetMs?.let {
                Text(stringResource(R.string.recognition_mean_offset, String.format(Locale.ROOT, "%.1f", it)))
            }
        }
    }
}

@Composable
private fun OptionsCard(controller: RecognitionController, options: SessionOptions) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper(
                label = stringResource(R.string.recognition_ambient_offset),
                value = PlaySettings.formatOffset(options.ambientOffsetMs),
                onMinus = { controller.updateOptions { it.copy(ambientOffsetMs = it.ambientOffsetMs - OFFSET_STEP_MS) } },
                onPlus = { controller.updateOptions { it.copy(ambientOffsetMs = it.ambientOffsetMs + OFFSET_STEP_MS) } },
            )
            Stepper(
                label = stringResource(R.string.recognition_stars),
                value = String.format(Locale.ROOT, "%.1f", options.stars),
                onMinus = { controller.updateOptions { it.copy(stars = stepStars(it.stars, -1)) } },
                onPlus = { controller.updateOptions { it.copy(stars = stepStars(it.stars, 1)) } },
            )
            if (controller.replayAvailable) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CaptureChoice.entries.forEachIndexed { index, choice ->
                        SegmentedButton(
                            selected = options.capture == choice,
                            onClick = { controller.updateOptions { it.copy(capture = choice) } },
                            shape = SegmentedButtonDefaults.itemShape(index, CaptureChoice.entries.size),
                            icon = {},
                        ) { Text(stringResource(choice.label())) }
                    }
                }
            }
        }
    }
}

private fun stepStars(stars: Double, steps: Int): Double =
    (Math.round(stars * 10) + steps).coerceIn(MIN_STARS_TENTHS, MAX_STARS_TENTHS) / 10.0

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus) {
            Icon(painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.recognition_less, label))
        }
        Text(value, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onPlus) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.recognition_more, label))
        }
    }
}

@Composable
private fun Paths(controller: RecognitionController, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (Environment.isExternalStorageManager()) {
                stringResource(R.string.recognition_all_files_on)
            } else {
                stringResource(R.string.recognition_all_files_off, context.packageName)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        controller.tracePath?.let { Text(stringResource(R.string.recognition_trace, it), style = MaterialTheme.typography.bodySmall) }
    }
}

@StringRes
private fun Stage.label(): Int = when (this) {
    Stage.LISTEN -> R.string.recognition_stage_listen
    Stage.OPEN -> R.string.recognition_stage_open
    Stage.RESULT -> R.string.recognition_stage_result
    Stage.DOWNLOAD -> R.string.recognition_stage_download
    Stage.SHARE -> R.string.recognition_stage_share
    Stage.AUDIO -> R.string.recognition_stage_audio
    Stage.CHART -> R.string.recognition_stage_chart
    Stage.SYNC -> R.string.recognition_stage_sync
    Stage.PLAY -> R.string.recognition_stage_play
}

@StringRes
private fun StageStatus.label(): Int = when (this) {
    StageStatus.WAITING -> R.string.recognition_status_waiting
    StageStatus.RUNNING -> R.string.recognition_status_running
    StageStatus.NEEDS_PERSON -> R.string.recognition_status_person
    StageStatus.DONE -> R.string.recognition_status_done
    StageStatus.SKIPPED -> R.string.recognition_status_skipped
    StageStatus.FAILED -> R.string.recognition_status_failed
}

@DrawableRes
private fun StageStatus.icon(): Int = when (this) {
    StageStatus.WAITING -> R.drawable.ic_circle_outline
    StageStatus.RUNNING -> R.drawable.ic_refresh
    StageStatus.NEEDS_PERSON -> R.drawable.ic_info
    StageStatus.DONE -> R.drawable.ic_check_circle
    StageStatus.SKIPPED -> R.drawable.ic_chevron_right
    StageStatus.FAILED -> R.drawable.ic_warning
}

@StringRes
private fun LaunchPath.label(): Int = when (this) {
    LaunchPath.A -> R.string.recognition_path_a
    LaunchPath.B -> R.string.recognition_path_b
    LaunchPath.AUTO -> R.string.recognition_path_auto
}

@StringRes
private fun CaptureChoice.label(): Int = when (this) {
    CaptureChoice.MICROPHONE -> R.string.recognition_capture_mic
    CaptureChoice.REPLAY -> R.string.recognition_capture_replay
}

@StringRes
private fun Ask.chinese(): Int = when (this) {
    Ask.OPEN_RECOGNITION -> R.string.recognition_ask_open_zh
    Ask.TAP_DOWNLOAD -> R.string.recognition_ask_download_zh
    Ask.SHARE_TO_ENSOMI -> R.string.recognition_ask_share_zh
    Ask.WAIT_FOR_DOWNLOAD -> R.string.recognition_ask_wait_zh
    Ask.RETURN_TO_ENSOMI -> R.string.recognition_ask_return_zh
    Ask.MICROPHONE_SILENT -> R.string.recognition_ask_silent_zh
}

@StringRes
private fun Ask.english(): Int = when (this) {
    Ask.OPEN_RECOGNITION -> R.string.recognition_ask_open_en
    Ask.TAP_DOWNLOAD -> R.string.recognition_ask_download_en
    Ask.SHARE_TO_ENSOMI -> R.string.recognition_ask_share_en
    Ask.WAIT_FOR_DOWNLOAD -> R.string.recognition_ask_wait_en
    Ask.RETURN_TO_ENSOMI -> R.string.recognition_ask_return_en
    Ask.MICROPHONE_SILENT -> R.string.recognition_ask_silent_en
}

/** The failure in Chinese and in English. */
private fun FailureKind.words(): Pair<Int, Int> = when (this) {
    FailureKind.HOST_NOT_INSTALLED -> R.string.recognition_failure_no_host_zh to R.string.recognition_failure_no_host_en
    FailureKind.HOST_LAUNCH_FAILED -> R.string.recognition_failure_launch_zh to R.string.recognition_failure_launch_en
    FailureKind.RECOGNITION_FAILED -> R.string.recognition_failure_recognition_zh to R.string.recognition_failure_recognition_en
    FailureKind.NO_DOWNLOAD_RIGHT -> R.string.recognition_failure_no_right_zh to R.string.recognition_failure_no_right_en
    FailureKind.NO_SHARE -> R.string.recognition_failure_no_share_zh to R.string.recognition_failure_no_share_en
    FailureKind.NOT_A_SONG -> R.string.recognition_failure_not_song_zh to R.string.recognition_failure_not_song_en
    FailureKind.NO_FILE -> R.string.recognition_failure_no_file_zh to R.string.recognition_failure_no_file_en
    FailureKind.PROTECTED_TRACK -> R.string.recognition_failure_protected_zh to R.string.recognition_failure_protected_en
    FailureKind.MODEL_MISSING -> R.string.recognition_failure_no_model_zh to R.string.recognition_failure_no_model_en
    FailureKind.CHART_FAILED -> R.string.recognition_failure_chart_zh to R.string.recognition_failure_chart_en
    FailureKind.SYNC_FAILED -> R.string.recognition_failure_sync_zh to R.string.recognition_failure_sync_en
    FailureKind.SYNC_NEVER_LOCKED -> R.string.recognition_failure_no_lock_zh to R.string.recognition_failure_no_lock_en
    FailureKind.CAPTURE_FAILED -> R.string.recognition_failure_capture_zh to R.string.recognition_failure_capture_en
    FailureKind.SONG_ENDED -> R.string.recognition_failure_song_ended_zh to R.string.recognition_failure_song_ended_en
    FailureKind.GAME_FAILED -> R.string.recognition_failure_game_zh to R.string.recognition_failure_game_en
    FailureKind.GAME_QUIT -> R.string.recognition_failure_quit_zh to R.string.recognition_failure_quit_en
    FailureKind.SESSION_LOST -> R.string.recognition_failure_lost_zh to R.string.recognition_failure_lost_en
}

/** Where the session can start again from. */
private val RETRY_STAGES = setOf(Stage.OPEN, Stage.AUDIO, Stage.CHART, Stage.SYNC, Stage.PLAY)

private const val CLOCK_TICK_MS = 500L
private const val OFFSET_STEP_MS = 5
private const val MIN_STARS_TENTHS = 20L
private const val MAX_STARS_TENTHS = 59L
