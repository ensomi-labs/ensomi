package io.ensomi.android

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.ensomi.android.diagnostics.DiagnosticsScreen
import kotlinx.serialization.Serializable

// Each screen is a key on the back stack. To add a screen, declare its key here and its entry below;
// a screen navigates through callbacks that push keys, so screens never refer to each other.

@Serializable
data object Diagnostics : NavKey

@Composable
fun EnsomiApp() {
    val backStack = rememberNavBackStack(Diagnostics)
    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            // Scopes each screen's ViewModel to its entry, cleared when the entry is popped.
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Diagnostics> { DiagnosticsScreen() }
        },
    )
}
