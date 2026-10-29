package io.ensomi.android.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.ensomi.android.R
import io.ensomi.android.chart.ChartRequestRules
import io.ensomi.android.model.ModelPanel
import io.ensomi.android.model.ModelState

/**
 * The home screen: the songs the player added, each opening the screen that makes its chart. Until the chart model is
 * installed, its download panel comes first, so a first run reads as two steps: get the model, add songs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    songs: List<Song>,
    model: ModelState,
    onAddSongs: () -> Unit,
    onOpenSong: (Song) -> Unit,
    onFindAgain: (Song) -> Unit,
    onRemoveSong: (Song) -> Unit,
    onOpenSettings: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings_title))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (songs.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.library_add_songs)) },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    onClick = onAddSongs,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (model !is ModelState.Installed && model !is ModelState.Checking) {
                    item(key = "model") {
                        ModelPanel(
                            state = model,
                            onDownload = onDownloadModel,
                            onCancel = onCancelDownload,
                            onRemove = {},
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (songs.isEmpty()) {
                    item(key = "empty") { EmptySongs(onAddSongs) }
                } else {
                    item(key = "header") {
                        Text(
                            stringResource(R.string.library_songs),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                                .semantics { heading() },
                        )
                    }
                    items(songs, key = { it.id }) { song ->
                        if (song.missing) {
                            MissingSongRow(song, onFindAgain = { onFindAgain(song) }, onRemove = { onRemoveSong(song) })
                        } else {
                            SongRow(song) { onOpenSong(song) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, onClick: () -> Unit) {
    val length = ChartRequestRules.formatDuration(song.durationMs)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(song.title) },
        supportingContent = {
            Text(if (song.artist != null) stringResource(R.string.library_song_detail, song.artist, length) else length)
        },
        leadingContent = {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_music_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        trailingContent = {
            if (song.chartCount > 0) {
                Text(
                    pluralStringResource(R.plurals.library_chart_count, song.chartCount, song.chartCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** A song whose file has gone: it says so and offers to pick the file anew or to forget the song. */
@Composable
private fun MissingSongRow(song: Song, onFindAgain: () -> Unit, onRemove: () -> Unit) {
    Column {
        ListItem(
            headlineContent = { Text(song.title) },
            supportingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(R.string.library_file_not_found, song.fileName),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            leadingContent = {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_music_note),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
        Row(
            Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onFindAgain) { Text(stringResource(R.string.library_find_again)) }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.library_remove)) }
        }
    }
}

@Composable
private fun EmptySongs(onAddSongs: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.library_empty_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAddSongs, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.library_add_songs), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
