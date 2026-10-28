package io.ensomi.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ensomi.android.probe.DeviceRecord
import io.ensomi.android.probe.LinkEvidence
import io.ensomi.android.probe.NetEaseProbe

@Composable
fun ProbeScreen(state: ProbeState, actions: ProbeActions) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(state)
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            DeviceSection(state, actions)
            NetEaseSection(state, actions)
            WatchSection(state, actions)
            CaptureSection(state, actions)
            TraceSection(state)
        }
    }
}

@Composable
private fun Header(state: ProbeState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Ensomi · Android probe", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Capture, deep link and download evidence for the ambient-music flow.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MonoText(state.traceFile.absolutePath)
    }
}

@Composable
private fun DeviceSection(state: ProbeState, actions: ProbeActions) {
    Section(
        title = "Device",
        subtitle = "The report's first phase: freeze the build and the NetEase version every result belongs to.",
    ) {
        val device = state.device
        if (device == null) {
            Text("not read yet", style = MaterialTheme.typography.bodySmall)
        } else {
            DeviceFacts(device)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions::freezeDevice) { Text("Freeze record") }
            OutlinedButton(onClick = actions::requestMicrophone) {
                Text(if (state.microphoneGranted) "Microphone ✓" else "Microphone")
            }
            OutlinedButton(onClick = actions::requestAllFiles) {
                Text(if (state.allFilesGranted) "All files ✓" else "All files")
            }
        }
    }
}

@Composable
private fun DeviceFacts(device: DeviceRecord) {
    FactRow("device", "${device.manufacturer} ${device.model} (${device.device})")
    FactRow("android", "${device.release} / sdk ${device.sdk}")
    FactRow("build", device.buildIncremental)
    MonoText(device.fingerprint)

    val netEase = device.netEase
    if (netEase == null) {
        FactRow("netease", device.netEaseError ?: "not installed")
        return
    }

    FactRow("netease", "${netEase.versionName} (${netEase.versionCode}), targetSdk ${netEase.targetSdk}")
    FactRow("activities", "${netEase.activityCount} total, ${netEase.exportedActivities.size} exported")
    MonoText(netEase.sourceDir)

    if (netEase.recognitionComponents.isNotEmpty()) {
        Text("Recognition components", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        netEase.recognitionComponents.forEach { component ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (component.exported) "exported" else "private",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (component.exported) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                MonoText(component.name, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NetEaseSection(state: ProbeState, actions: ProbeActions) {
    Section(
        title = "Deep links",
        subtitle = "What a third-party app may start, and what this device has actually shown.",
    ) {
        NetEaseProbe.links.forEach { link ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(link.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    EvidenceTag(link.evidence)
                }
                MonoText(link.uri)
                Text(
                    link.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions::probeDeepLinks) { Text("Resolve all") }
            OutlinedButton(onClick = actions::openRecognition) { Text("Open 听歌识曲") }
            OutlinedButton(onClick = actions::openSong) { Text("Open song") }
        }

        if (state.netEaseLines.isNotEmpty()) {
            state.netEaseLines.takeLast(10).forEach { MonoText(it) }
        }
    }
}

@Composable
private fun EvidenceTag(evidence: LinkEvidence) {
    val (label, color) = when (evidence) {
        LinkEvidence.DEVICE_CONFIRMED -> "device-confirmed" to MaterialTheme.colorScheme.primary
        LinkEvidence.RESOLVES_ONLY -> "resolves only" to MaterialTheme.colorScheme.tertiary
        LinkEvidence.UNSUPPORTED -> "unsupported" to MaterialTheme.colorScheme.error
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun WatchSection(state: ProbeState, actions: ProbeActions) {
    Section(
        title = "Download watcher",
        subtitle = "CLOSE_WRITE / MOVED_TO plus the correlation rules, the report's audio_ready test.",
    ) {
        OutlinedTextField(
            value = state.watchDirectory,
            onValueChange = { state.watchDirectory = it },
            label = { Text("directory") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions::toggleWatching) {
                Text(if (state.watching) "Stop watching" else "Start watching")
            }
            Text(
                if (state.watching) "watching" else "idle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.watchLines.takeLast(12).forEach { MonoText(it) }
    }
}

@Composable
private fun CaptureSection(state: ProbeState, actions: ProbeActions) {
    Section(
        title = "Capture",
        subtitle = "5 s of 44.1 kHz mono, kept as a WAV with its monotonic bounds and its AudioTimestamp.",
    ) {
        Button(onClick = { actions.capture(5) }, enabled = !state.capturing) {
            Text(if (state.capturing) "Recording…" else "Record 5 s")
        }

        state.captures.forEach { capture ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FactRow("duration", "${capture.durationMs} ms")
                FactRow("rms / peak", "${"%.1f".format(capture.rms)} / ${capture.peak}")
                FactRow(
                    "monotonic",
                    "${capture.firstReadNanos} → ${capture.lastReadNanos}",
                )
                FactRow(
                    "audio stamp",
                    if (capture.timestampFramePosition == null) {
                        "unavailable"
                    } else {
                        "frame ${capture.timestampFramePosition} at ${capture.timestampNanos} ns"
                    },
                )
                MonoText(capture.wavPath)
                MonoText(capture.sha256)
            }
        }
    }
}

@Composable
private fun TraceSection(state: ProbeState) {
    Section(
        title = "Trace",
        subtitle = "One JSON object per line, in the report's field names. Pull it with adb.",
    ) {
        FactRow("events", state.traceLineCount.toString())
        MonoText(state.traceFile.absolutePath)
        Text(
            "adb pull ${state.traceFile.absolutePath}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MonoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
