package io.ensomi.android.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.ensomi.android.R
import io.ensomi.android.ui.ActionButton
import io.ensomi.android.ui.LocalNotWired
import java.util.Locale

/**
 * The chart model's state with the action that fits it: download (with the size and the free space), progress with
 * Cancel, resume after a stop, or not enough space. The library shows it until the model is installed; Settings shows
 * it always.
 */
@Composable
fun ModelPanel(
    state: ModelState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    /** In Settings the panel sits in a list and needs no card or introduction. */
    inSettings: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(if (inSettings) 0.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                is ModelState.Absent -> Absent(state, onDownload, inSettings)
                is ModelState.Downloading -> Downloading(state, onCancel)
                is ModelState.Stopped -> Stopped(state, onDownload)
                is ModelState.Installed -> Installed(state, onDownload, onRemove)
            }
        }
    }
    if (inSettings) {
        Column(modifier) { content() }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) { content() }
    }
}

@Composable
private fun Absent(state: ModelState.Absent, onDownload: () -> Unit, inSettings: Boolean) {
    val notWired = LocalNotWired.current
    if (!inSettings) {
        Text(stringResource(R.string.model_get_title), style = MaterialTheme.typography.titleLarge)
    }
    Text(stringResource(R.string.model_get_body, megabytes(state.downloadBytes)), style = MaterialTheme.typography.bodyMedium)
    if (state.fits) {
        Text(
            stringResource(R.string.model_space, megabytes(state.downloadBytes), gigabytes(state.freeBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (inSettings) {
            Button(onClick = onDownload) { Text(stringResource(R.string.model_download)) }
        } else {
            ActionButton(stringResource(R.string.model_download), onDownload, icon = R.drawable.ic_download, modifier = Modifier.fillMaxWidth())
        }
    } else {
        Problem(stringResource(R.string.model_no_space, megabytes(state.downloadBytes), megabytes(state.freeBytes)))
        Text(stringResource(R.string.model_no_space_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDownload) { Text(stringResource(R.string.model_try_again)) }
            // The system's screen for freeing space (StorageManager.ACTION_MANAGE_STORAGE), plan step 4.
            TextButton(onClick = { notWired("Opening the storage screen (plan step 4)") }) {
                Text(stringResource(R.string.model_free_space))
            }
        }
    }
}

@Composable
private fun Downloading(state: ModelState.Downloading, onCancel: () -> Unit) {
    val fraction = if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f
    Text(
        stringResource(R.string.model_downloading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(
                R.string.model_progress,
                (fraction * 100).toInt(),
                megabytes(state.doneBytes),
                megabytes(state.totalBytes),
            ),
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text(stringResource(R.string.model_cancel)) }
    }
    Text(stringResource(R.string.model_downloading_hint), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Stopped(state: ModelState.Stopped, onResume: () -> Unit) {
    val stopped = stringResource(R.string.model_stopped, megabytes(state.doneBytes), megabytes(state.totalBytes))
    Problem(if (state.failure != null) "$stopped ${state.failure}" else stopped)
    Text(stringResource(R.string.model_stopped_hint), style = MaterialTheme.typography.bodySmall)
    Button(onClick = onResume) { Text(stringResource(R.string.model_resume)) }
}

@Composable
private fun Installed(state: ModelState.Installed, onDownloadAgain: () -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(state.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.model_installed_detail, state.revision, megabytes(state.sizeBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDownloadAgain) { Text(stringResource(R.string.model_check_files)) }
        TextButton(onClick = onRemove) { Text(stringResource(R.string.model_remove)) }
    }
}

/** A failure in words with a symbol, never colour alone. */
@Composable
fun Problem(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)

fun gigabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
