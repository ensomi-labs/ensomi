package io.ensomi.android.diagnostics

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DiagnosticsUiState {
    data object Loading : DiagnosticsUiState

    /** [json] is what the copy action puts on the clipboard. */
    data class Ready(val sections: List<DiagnosticsSection>, val json: String) : DiagnosticsUiState

    data class Failed(val message: String) : DiagnosticsUiState
}

/** Collects the report once per process and writes its JSON to logcat once. */
class DiagnosticsViewModel(private val collector: DiagnosticsCollector) : ViewModel() {
    private val state = MutableStateFlow<DiagnosticsUiState>(DiagnosticsUiState.Loading)
    val uiState: StateFlow<DiagnosticsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            state.value = try {
                val report = collector.collect()
                val json = report.toJson()
                DiagnosticsLog.write(json)
                DiagnosticsUiState.Ready(report.sections(), json)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Not under DiagnosticsLog.TAG, which carries only the JSON.
                Log.e("Ensomi", "Diagnostics failed", failure)
                DiagnosticsUiState.Failed(failure.toString())
            }
        }
    }
}
