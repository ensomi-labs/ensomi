package io.ensomi.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.ensomi.android.prototype.AppGraph
import io.ensomi.android.prototype.Scenario
import io.ensomi.android.ui.theme.EnsomiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val scenario = Scenario.from(intent, applicationInfo)
        val graph = Graphs.forScenario(scenario)
        setContent {
            EnsomiTheme(dynamicColor = scenario.dynamicColor) {
                EnsomiApp(scenario, graph)
            }
        }
    }
}

/** Keeps the sample graph across configuration changes, as an app-wide container will keep the real one. */
private object Graphs {
    private var scenario: Scenario? = null
    private var graph: AppGraph? = null

    fun forScenario(next: Scenario): AppGraph {
        val current = graph
        if (current != null && scenario == next) return current
        return next.graph().also {
            scenario = next
            graph = it
        }
    }
}
