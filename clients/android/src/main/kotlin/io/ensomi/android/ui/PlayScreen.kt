package io.ensomi.android.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ensomi.android.generation.ChartGenerationService
import io.ensomi.android.generation.GeneratedChart
import io.ensomi.android.library.LibraryEntry
import io.ensomi.android.settings.EnsomiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.ensomi_runtime_ffi.Chart
import uniffi.ensomi_runtime_ffi.GenerationPhase
import uniffi.ensomi_runtime_ffi.exportOsz

/**
 * The Play tab, drawn the way the iOS client draws it: the brand's plum ground, an Ensomi header with a 4K
 * badge, a Game Mode panel holding the audio, the stars and the seed, the chart's state under the fields,
 * and a preview panel beside them.
 */
@Composable
fun PlayScreen(
    songs: List<LibraryEntry>,
    settings: EnsomiSettings,
    generation: ChartGenerationService,
    onPlayScene: (LibraryEntry, Chart) -> Unit,
    onPickAudioFile: () -> Unit,
) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }

    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = songs.firstOrNull { it.uri == selectedUri }

    var choosing by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<GenerationPhase?>(null) }
    var chart by remember { mutableStateOf<GeneratedChart?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var star by remember { mutableStateOf(3.5) }
    var seedText by remember { mutableStateOf("") }
    var starRange by remember { mutableStateOf<ClosedFloatingPointRange<Double>?>(null) }
    val modelInstalled = remember { generation.isModelInstalled() }

    LaunchedEffect(modelInstalled) {
        if (modelInstalled) {
            withContext(Dispatchers.IO) { generation.starRange() }?.let { range ->
                starRange = range
                star = star.coerceIn(range.start, range.endInclusive)
            }
        }
    }

    val saveOsu = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val made = chart
        if (destination != null && made != null) {
            message = runCatching {
                context.contentResolver.openOutputStream(destination)?.use { it.write(made.osu.toByteArray()) }
                    ?: error("the file could not be written")
                "Saved ${destination.lastPathSegment}"
            }.getOrElse { "Could not save the chart: ${it.message}" }
        }
    }
    val saveOsz = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val made = chart
        val song = selected
        if (destination != null && made != null && song != null) {
            message = "Saving the archive..."
            Thread({
                val outcome = runCatching {
                    val audio = context.contentResolver.openInputStream(Uri.parse(song.uri))?.use { it.readBytes() }
                        ?: error("the song could not be read")
                    val archive = exportOsz(made.osu, audio, made.receiptJson)
                    context.contentResolver.openOutputStream(destination)?.use { it.write(archive) }
                        ?: error("the file could not be written")
                    "Saved ${destination.lastPathSegment}"
                }
                main.post { message = outcome.getOrElse { "Could not save the chart: ${it.message}" } }
            }, "ensomi-osz").start()
        }
    }

    fun startGeneration() {
        val song = selected ?: return
        if (generating) return
        generating = true
        chart = null
        failure = null
        message = null
        Thread({
            val outcome = runCatching {
                generation.generate(
                    uri = Uri.parse(song.uri),
                    requestedStar = star,
                    seed = seedText.trim().toULongOrNull() ?: 0uL,
                    title = song.displayTitle,
                    artist = song.artist,
                    audioName = song.name,
                    onPhase = { reported -> main.post { phase = reported } },
                )
            }
            main.post {
                generating = false
                phase = null
                outcome.onSuccess { chart = it }
                outcome.onFailure { failure = it.message ?: "the chart could not be made" }
            }
        }, "ensomi-generate").start()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(EnsomiStyle.backdrop)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ensomi", color = EnsomiStyle.textPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Choose how you want to play",
                    color = EnsomiStyle.textSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Badge(
                if (chart != null) "4K  Chart ready" else "4K",
                if (chart != null) EnsomiStyle.ok else EnsomiStyle.border,
            )
        }

        Column(Modifier.fillMaxWidth().panel(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionTitle("Game Mode")

            Row(
                Modifier
                    .fillMaxWidth()
                    .controlFill()
                    .clickable { choosing = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = EnsomiStyle.accent)
                Column(Modifier.weight(1f)) {
                    Text("Audio", color = EnsomiStyle.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        pickedName ?: selected?.displayTitle ?: "Choose a song",
                        color = EnsomiStyle.textPrimary,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("Choose", color = EnsomiStyle.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            (selected?.durationMs ?: 0L).takeIf { it > 0 }?.let {
                Text("Song length: ${formatSongLength(it)}", color = EnsomiStyle.textSecondary, fontSize = 12.sp)
            }

            if (!modelInstalled) {
                Text(
                    "The chart model is missing. Download it in Settings; the model is about 115 MB and is needed once.",
                    color = EnsomiStyle.warn,
                    fontSize = 13.sp,
                )
            }

            val low = (starRange?.start ?: 1.0).toFloat()
            val high = (starRange?.endInclusive ?: 6.0).toFloat()
            FieldLabel("osu!mania star difficulty")
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { star = (star - 0.1).coerceIn(low.toDouble(), high.toDouble()); chart = null }) {
                    Text("-", color = EnsomiStyle.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "%.1f".format(star) + (starRange?.let { "  (model %.1f-%.1f)".format(it.start, it.endInclusive) } ?: ""),
                    color = EnsomiStyle.textPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { star = (star + 0.1).coerceIn(low.toDouble(), high.toDouble()); chart = null }) {
                    Text("+", color = EnsomiStyle.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Slider(
                value = star.toFloat().coerceIn(low, high),
                onValueChange = { star = it.toDouble(); chart = null },
                valueRange = low..high,
                colors = SliderDefaults.colors(
                    thumbColor = EnsomiStyle.accent,
                    activeTrackColor = EnsomiStyle.accent,
                    inactiveTrackColor = EnsomiStyle.control,
                ),
            )

            FieldLabel("Seed")
            OutlinedTextField(
                value = seedText,
                onValueChange = { typed -> seedText = typed.filter { it.isDigit() }.take(10) },
                singleLine = true,
                placeholder = { Text("Random", color = EnsomiStyle.textMuted) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EnsomiStyle.borderStrong,
                    unfocusedBorderColor = EnsomiStyle.border,
                    focusedContainerColor = EnsomiStyle.control,
                    unfocusedContainerColor = EnsomiStyle.control,
                    focusedTextColor = EnsomiStyle.textPrimary,
                    unfocusedTextColor = EnsomiStyle.textPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            HelpText("Blank makes a new chart each time. The same song, stars and seed make the same chart again.")

            when {
                generating -> PhaseList(current = phase)
                chart != null -> ResultBlock(made = chart!!, onUseSeed = { seedText = chart!!.seed.toString() })
                failure != null -> Text(
                    failure!!,
                    color = EnsomiStyle.error,
                    fontSize = 13.sp,
                )
            }

            when {
                generating -> OutlinedButton(
                    onClick = {
                        generation.cancel()
                        generating = false
                        failure = "Stopped. No chart was made."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop", color = EnsomiStyle.textSecondary) }

                chart != null -> {
                    Button(
                        onClick = { selected?.let { onPlayScene(it, chart!!.chart) } },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EnsomiStyle.action,
                            contentColor = EnsomiStyle.actionInk,
                        ),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Play", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { saveOsu.launch(osuName(selected?.displayTitle ?: "chart")) }) {
                            Text("Save .osu...", color = EnsomiStyle.accent, fontSize = 13.sp)
                        }
                        TextButton(onClick = { saveOsz.launch(oszName(selected?.displayTitle ?: "chart")) }) {
                            Text("Save .osz...", color = EnsomiStyle.accent, fontSize = 13.sp)
                        }
                        TextButton(onClick = { startGeneration() }) {
                            Text("Generate Again", color = EnsomiStyle.textSecondary, fontSize = 13.sp)
                        }
                    }
                }

                else -> Button(
                    onClick = { startGeneration() },
                    enabled = selected != null && modelInstalled,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EnsomiStyle.action,
                        contentColor = EnsomiStyle.actionInk,
                    ),
                ) { Text("Generate", fontWeight = FontWeight.Bold) }
            }

            message?.let { HelpText(it) }
        }

        Column(Modifier.fillMaxWidth().panel(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Preview")
            Row(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(EnsomiStyle.tap, EnsomiStyle.accent, EnsomiStyle.warn, EnsomiStyle.tap).forEach { colour ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(EnsomiStyle.ground, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(Modifier.fillMaxWidth().height(10.dp).background(colour.copy(alpha = 0.8f)))
                    }
                }
            }
            HelpText(
                if (chart != null) {
                    "The chart is ready: ${chart!!.chart.objects.size} notes, seed ${chart!!.seed}."
                } else {
                    "The lanes will fill once the chart is ready."
                },
            )
        }
    }

    if (choosing) {
        SongPickerDialog(
            songs = songs,
            onPick = { entry ->
                selectedUri = entry.uri
                pickedName = entry.displayTitle
                chart = null
                choosing = false
            },
            onPickFile = {
                choosing = false
                onPickAudioFile()
            },
            onDismiss = { choosing = false },
        )
    }
}

/** The phases the iOS client lists under the field, with the one under way marked. */
@Composable
private fun PhaseList(current: GenerationPhase?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GenerationPhase.entries.forEach { phase ->
            val done = current != null && phase.ordinal < current.ordinal
            val running = phase == current
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done) {
                    Icon(Icons.Filled.CheckCircle, null, tint = EnsomiStyle.ok, modifier = Modifier.size(18.dp))
                } else if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = EnsomiStyle.accent,
                    )
                } else {
                    Box(Modifier.size(14.dp).background(EnsomiStyle.textMuted, CircleShape))
                }
                Text(
                    phaseTitle(phase),
                    color = if (done || running) EnsomiStyle.textPrimary else EnsomiStyle.textMuted,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** The iOS client's phase titles, in a player's words. */
private fun phaseTitle(phase: GenerationPhase): String = when (phase) {
    GenerationPhase.LOADING -> "Loading the audio"
    GenerationPhase.TIMING -> "Finding the beat"
    GenerationPhase.ROWS -> "Placing the notes"
    GenerationPhase.ARRANGEMENT -> "Laying out the lanes"
    GenerationPhase.EXPORT -> "Writing the chart"
}

@Composable
private fun ResultBlock(made: GeneratedChart, onUseSeed: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().controlFill().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.CheckCircle, null, tint = EnsomiStyle.ok, modifier = Modifier.size(18.dp))
            Text("Chart ready", color = EnsomiStyle.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "${made.chart.objects.size} notes: ${made.taps} taps, ${made.holds} long notes",
            color = EnsomiStyle.textPrimary,
            fontSize = 14.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Seed ${made.seed}", color = EnsomiStyle.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            TextButton(onClick = onUseSeed) {
                Text("Use Seed", color = EnsomiStyle.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
