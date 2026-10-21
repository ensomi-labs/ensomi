package io.ensomi.windows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Ensomi") {
        EnsomiWindow()
    }
}

/**
 * The first window: a placeholder that proves the toolchain reaches the desktop
 * and that the client can draw at all. The workbench arrives with the screens in
 * [the screen specification](../../../../docs/platform-screens.md).
 */
@Composable
private fun EnsomiWindow() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Ensomi for Windows",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}
