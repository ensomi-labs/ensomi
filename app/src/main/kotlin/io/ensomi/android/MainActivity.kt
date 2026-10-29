package io.ensomi.android

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.ensomi.android.diagnostics.DecodeCheck
import io.ensomi.android.prototype.AppGraph
import io.ensomi.android.prototype.DebugTools
import io.ensomi.android.prototype.Scenario
import io.ensomi.android.ui.theme.EnsomiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val scenario = Scenario.from(intent, DebugTools.enabled(this))
        val graph = Graphs.forScenario(scenario) { AppStores.get(applicationContext) }
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && savedInstanceState == null && intent.getBooleanExtra(DecodeCheck.EXTRA, false)) {
            DecodeCheck.start(applicationContext, AppStores.get(applicationContext))
        }
        // Debug builds: the model download from Hugging Face, as the Download button starts it, for the phone checks'
        // script (`--ez download-model true`); its outcome is logged under EnsomiModelInstall.
        if (debuggable && savedInstanceState == null && intent.getBooleanExtra(DOWNLOAD_MODEL_EXTRA, false)) {
            AppStores.get(applicationContext).model.download()
        }
        setContent {
            EnsomiTheme(dynamicColor = scenario.dynamicColor) {
                EnsomiApp(scenario, graph)
            }
        }
    }
}

private const val DOWNLOAD_MODEL_EXTRA = "download-model"

/** Keeps a scenario's graph across configuration changes; the app's own stores are one per process ([AppStores]). */
private object Graphs {
    private var scenario: Scenario? = null
    private var graph: AppGraph? = null

    fun forScenario(next: Scenario, stores: () -> AppStores): AppGraph {
        val current = graph
        if (current != null && scenario == next) return current
        return next.graph(stores).also {
            scenario = next
            graph = it
        }
    }
}
