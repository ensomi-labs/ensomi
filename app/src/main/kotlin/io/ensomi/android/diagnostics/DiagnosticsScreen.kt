package io.ensomi.android.diagnostics

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.ensomi.android.R
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val viewModel = viewModel { DiagnosticsViewModel(DiagnosticsCollector(appContext)) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DiagnosticsContent(uiState, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsContent(uiState: DiagnosticsUiState, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.diagnostics_clip_label)
    val json = (uiState as? DiagnosticsUiState.Ready)?.json
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                actions = {
                    // Android shows its own confirmation when the clipboard changes.
                    TextButton(
                        onClick = {
                            if (json != null) {
                                val clip = ClipData.newPlainText(clipLabel, json)
                                scope.launch { clipboard.setClipEntry(ClipEntry(clip)) }
                            }
                        },
                        enabled = json != null,
                    ) {
                        Text(stringResource(R.string.diagnostics_copy_json))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        when (uiState) {
            DiagnosticsUiState.Loading -> Loading(Modifier.padding(innerPadding))
            is DiagnosticsUiState.Failed -> Text(
                text = stringResource(R.string.diagnostics_failed, uiState.message),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(innerPadding).padding(16.dp),
            )
            is DiagnosticsUiState.Ready -> SectionList(uiState.sections, innerPadding)
        }
    }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.diagnostics_loading), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionList(sections: List<DiagnosticsSection>, scaffoldPadding: PaddingValues) {
    val direction = LocalLayoutDirection.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Scroll under the system bars, but start and end clear of them.
        contentPadding = PaddingValues(
            start = scaffoldPadding.calculateStartPadding(direction) + 16.dp,
            top = scaffoldPadding.calculateTopPadding() + 8.dp,
            end = scaffoldPadding.calculateEndPadding(direction) + 16.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(sections, key = { it.title }) { section -> SectionCard(section) }
    }
}

@Composable
private fun SectionCard(section: DiagnosticsSection) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(section.title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() },
        )
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                section.rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    ValueRow(row)
                }
            }
        }
    }
}

@Composable
private fun ValueRow(row: DiagnosticsRow) {
    val label = row.labelArgument?.let { stringResource(row.label, it) } ?: stringResource(row.label)
    val value = when (val rowValue = row.value) {
        is DiagnosticsValue.Text -> rowValue.text
        is DiagnosticsValue.Problem -> rowValue.text
        is DiagnosticsValue.Flag -> stringResource(if (rowValue.on) R.string.value_yes else R.string.value_no)
        DiagnosticsValue.Unknown -> stringResource(R.string.value_unknown)
    }
    val valueColor = when (row.value) {
        is DiagnosticsValue.Problem -> MaterialTheme.colorScheme.error
        DiagnosticsValue.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            // Tabular figures keep numbers aligned from row to row.
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}
