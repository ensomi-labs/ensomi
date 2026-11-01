package io.ensomi.android.recognition

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ensomi.android.R

/** The person follows these steps; opening NetEase is only ever a button callback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionGuide(controller: RecognitionController, modelReady: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    fun readState() = GuideState(modelReady,
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED)
    var state by remember(modelReady) { mutableStateOf(readState()) }
    var launchMessage by remember { mutableStateOf<Int?>(null) }
    val notice by controller.shareNotice.collectAsStateWithLifecycle()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state = readState() }
    LifecycleResumeEffect(modelReady) {
        state = readState()
        onPauseOrDispose { }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.recognition_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.guide_intro), style = MaterialTheme.typography.bodyLarge) }
            notice?.let { decision ->
                val message = when {
                    decision == ShareDecision.RESTORE -> R.string.guide_restored
                    decision == ShareDecision.NOT_SONG -> R.string.guide_not_song
                    state.ready -> R.string.guide_share_again
                    !state.modelReady -> null // The model card below supplies this explanation once.
                    else -> R.string.guide_share_permissions
                }
                if (message != null) item { Text(stringResource(message), color = MaterialTheme.colorScheme.primary) }
            }
            if (!state.modelReady) item {
                Text(stringResource(R.string.guide_model))
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.guide_library)) }
            }
            item { PermissionCard(R.string.guide_microphone, R.string.guide_mic_reason, state.microphone) {
                permissions.launch(Manifest.permission.RECORD_AUDIO)
            } }
            item { PermissionCard(R.string.guide_music, R.string.guide_music_reason, state.music) {
                permissions.launch(Manifest.permission.READ_MEDIA_AUDIO)
            } }
            if (!state.microphone || !state.music) item {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri())) }) { Text(stringResource(R.string.guide_settings)) }
            }
            item {
                Text(stringResource(R.string.guide_step_open), style = MaterialTheme.typography.titleMedium)
                Button(enabled = state.ready, onClick = {
                    launchMessage = when (HostLauncher.launch(context)) {
                        is LaunchResult.Started -> null
                        LaunchResult.NotInstalled -> R.string.guide_no_host
                        is LaunchResult.Failed -> R.string.guide_launch_failed
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.guide_open)) }
                launchMessage?.let { Text(stringResource(it)) }
            }
            item { GuideStep(R.string.guide_step_recognise, R.string.guide_recognise) }
            item { GuideStep(R.string.guide_step_download, R.string.guide_download) }
            item { GuideStep(R.string.guide_step_share, R.string.guide_after_share) }
        }
    }
}

@Composable
private fun PermissionCard(title: Int, reason: Int, granted: Boolean, onAllow: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(reason))
            if (granted) Text(stringResource(R.string.guide_allowed), color = MaterialTheme.colorScheme.primary)
            else OutlinedButton(onClick = onAllow) { Text(stringResource(R.string.guide_allow)) }
        }
    }
}

@Composable
private fun GuideStep(title: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(body))
    }
}
