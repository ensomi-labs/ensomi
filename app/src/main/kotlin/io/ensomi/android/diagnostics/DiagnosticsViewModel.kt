package io.ensomi.android.diagnostics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DiagnosticsUiState {
    data object Loading : DiagnosticsUiState
    data class Ready(val sections: List<DiagnosticsSection>, val json: String, val exitCount: Int = 0) : DiagnosticsUiState
    data class Failed(val message: String) : DiagnosticsUiState
}

class DiagnosticsViewModel(private val collector: DiagnosticsCollector) : ViewModel() {
    private val state = MutableStateFlow<DiagnosticsUiState>(DiagnosticsUiState.Loading)
    val uiState: StateFlow<DiagnosticsUiState> = state.asStateFlow()
    private var runtime = RuntimeFacts(false, null, null, null, null)
    init { refresh(false) }

    fun probe() = refresh(true)
    private fun refresh(probe: Boolean) {
        state.value = DiagnosticsUiState.Loading
        viewModelScope.launch {
            try {
                if (probe) {
                    Journal.record("runtime_self_test", "begin")
                    runtime = RuntimeProbe.check()
                    Journal.record("runtime_self_test", "end", mapOf("observed" to runtime.observed))
                }
                val report = collector.collect(runtime)
                val json = report.toJson()
                DiagnosticsLog.write(json)
                state.value = DiagnosticsUiState.Ready(report.sections(), json, report.exits.size)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }
    suspend fun bundle(context: Context): File = withContext(Dispatchers.IO) {
        DiagnosticsExport.create(context, collector.collect(runtime))
    }
    fun fail(failure: Exception) { state.value = DiagnosticsUiState.Failed(Redact.text(failure.javaClass.simpleName)) }
}
