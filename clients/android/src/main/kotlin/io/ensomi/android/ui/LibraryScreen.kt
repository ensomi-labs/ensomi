package io.ensomi.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ensomi.android.library.LibraryScanner
import io.ensomi.android.library.LibraryStore

/**
 * The Library tab, in the shape the iOS client gives it: a grouped form of Music Folders, Scan Status and
 * Library Tools. Android adds the platform's own music index to the count, because asking someone to find a
 * folder is work the system has already done.
 */
@Composable
fun LibraryScreen(
    library: LibraryStore,
    scanner: LibraryScanner,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
    onRefresh: () -> Unit,
    songCount: Int,
) {
    val index = library.index
    val progress = scanner.progress

    Column(
        Modifier
            .fillMaxSize()
            .background(EnsomiStyle.backdrop)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text("Library", color = EnsomiStyle.textPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                "Your music, ready to chart",
                color = EnsomiStyle.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Group("Music Folders") {
            if (index.folders.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Add Your Music", color = EnsomiStyle.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Choose a folder of audio files to use for recognition and play.",
                        color = EnsomiStyle.textSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = onPickFolder,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EnsomiStyle.action,
                            contentColor = EnsomiStyle.actionInk,
                        ),
                    ) { Text("Add Music Folder...", fontWeight = FontWeight.Bold) }
                }
            } else {
                index.folders.forEach { folder ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(folder.displayName, color = EnsomiStyle.textPrimary, fontSize = 15.sp)
                        }
                        TextButton(onClick = { library.removeFolder(folder.treeUri); onRefresh() }) {
                            Text("Remove", color = EnsomiStyle.error, fontSize = 13.sp)
                        }
                    }
                    HorizontalDivider(color = EnsomiStyle.border)
                }
                TextButton(onClick = onPickFolder) {
                    Text("Add Music Folder...", color = EnsomiStyle.accent, fontSize = 14.sp)
                }
            }
            HelpText("Removing a folder from the library keeps its audio files on disk.")
        }

        Group("Scan Status") {
            StatusRow("Songs on this phone", songCount.toString())
            StatusRow("Indexed from folders", index.entries.size.toString())
            StatusRow("Discovered", progress.discovered.toString())
            StatusRow("Failed", progress.failed.toString())
            StatusRow("Last scan", index.lastScanMs?.let { "done" } ?: "Never")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRescan, enabled = !progress.running) {
                    Text("Rescan Library", color = EnsomiStyle.accent, fontSize = 14.sp)
                }
                TextButton(onClick = onRefresh) {
                    Text("Refresh Status", color = EnsomiStyle.accent, fontSize = 14.sp)
                }
            }
        }

        Group("Library Tools") {
            Text(
                "Manual resolution is not on Android yet: it needs the resolver the Apple clients ship, and the " +
                    "fixture recorder behind it is Apple-only.",
                color = EnsomiStyle.textSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

/** The iOS grouped-form section: a plum block with an uppercase header inside it. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(EnsomiStyle.panel, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title.uppercase(),
            color = EnsomiStyle.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        content()
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = EnsomiStyle.textPrimary, fontSize = 14.sp)
        Text(value, color = EnsomiStyle.textSecondary, fontSize = 14.sp)
    }
}
