package io.ensomi.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import io.ensomi.android.RuntimeSelfCheck
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ensomi.android.settings.EnsomiSettings
import io.ensomi.android.settings.SettingsKeys

/**
 * S5 Settings as a sheet. Keyboard, Developer and the chart model are absent because this build has no
 * keys to bind, no inspector and no engine; the probe entry stands in for the developer surface the
 * probe build needs.
 */
@Composable
fun SettingsSheet(
    settings: EnsomiSettings,
    onOpenProbe: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Gameplay", style = MaterialTheme.typography.titleSmall)

                LabeledValue("Scroll speed", "%.1f".format(settings.scrollSpeed))
                Slider(
                    value = settings.scrollSpeed,
                    onValueChange = { settings.updateScrollSpeed(it) },
                    valueRange = SettingsKeys.MIN_SCROLL_SPEED..SettingsKeys.MAX_SCROLL_SPEED,
                )

                Text("Judge difficulty", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SettingsKeys.DIFFICULTIES.forEach { difficulty ->
                        val selected = settings.judgeDifficulty == difficulty
                        if (selected) {
                            Button(onClick = { settings.updateJudgeDifficulty(difficulty) }) { Text(difficulty) }
                        } else {
                            OutlinedButton(onClick = { settings.updateJudgeDifficulty(difficulty) }) {
                                Text(difficulty)
                            }
                        }
                    }
                }

                Text("Sound and timing", style = MaterialTheme.typography.titleSmall)

                LabeledValue("Music volume", "%.2f".format(settings.musicVolume))
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

                Text(
                    "The audio offset is per output device and needs the calibration sheet, which needs " +
                        "the engine; until then only the visual offset is stored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Runtime", style = MaterialTheme.typography.titleSmall)
                RuntimeCheck()

                Text("Developer", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onOpenProbe) { Text("Open probe") }
            }
        },
    )
}

/**
 * Loads the packaged runtime and parses a beatmap the shell can spell out. It answers one question —
 * does the binding reach the native core on this device — and nothing about generation, which needs ONNX
 * Runtime and a model this build does not ship.
 */
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
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
