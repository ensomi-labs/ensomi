package io.ensomi.android

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import io.ensomi.android.diagnostics.DecodeCheck
import io.ensomi.android.keyboard.HardwareKeys
import io.ensomi.android.keyboard.LocalHardwareKeys
import io.ensomi.android.prototype.AppGraph
import io.ensomi.android.prototype.DebugTools
import io.ensomi.android.prototype.Scenario
import io.ensomi.android.ui.theme.EnsomiTheme

class MainActivity : ComponentActivity() {
    /** Keys before the views see them, for the screens that take them (keyboard.md, Where key events arrive). */
    private val hardwareKeys = HardwareKeys { window }
    private var debugTools = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        debugTools = DebugTools.enabled(this)
        // The keyboard restart check (keyboard.md, K2): a keyboard connecting creates no second activity.
        if (debugTools) Log.i(KEYS_TAG, "MainActivity created, ${++created} in this process; ${keyboardConfiguration(resources.configuration)}")
        val scenario = Scenario.from(intent, debugTools)
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
            CompositionLocalProvider(LocalHardwareKeys provides hardwareKeys) {
                EnsomiTheme(dynamicColor = scenario.dynamicColor) {
                    EnsomiApp(scenario, graph)
                }
            }
        }
    }

    /**
     * A keyboard coming or going changes the configuration's keyboard and navigation, which the manifest declares, so
     * the activity and its game carry on (keyboard.md, D4).
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (debugTools) Log.i(KEYS_TAG, "Configuration changed in place: ${keyboardConfiguration(newConfig)}")
    }

    /**
     * A screen that takes keys sees them first, the newest first; what none takes goes to the views as before.
     *
     * `Activity.dispatchKeyEvent` is the framework's public hook "to intercept all key events before they are
     * dispatched to the window" (keyboard.md, Where key events arrive). androidx.core's `ComponentActivity` overrides it
     * with `@RestrictTo`, to keep its own key dispatcher to the library, and lint reports any subclass's override and
     * super call as `RestrictedApi`. The super call keeps that dispatcher's behaviour for every key no screen takes.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = hardwareKeys.dispatch(event) || super.dispatchKeyEvent(event)

    /**
     * An Escape no view handled is Back on every screen but the Library (keyboard.md, O2), on its up, as Android tracks
     * Back, so an up whose down went elsewhere does nothing.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && hardwareKeys.escapeGoesBack) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.isTracking && !event.isCanceled) {
            if (hardwareKeys.escapeGoesBack) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private companion object {
        const val KEYS_TAG = "EnsomiKeys"

        /** The activities this process made, for the restart check. */
        var created = 0

        fun keyboardConfiguration(configuration: Configuration): String =
            "keyboard ${configuration.keyboard}, keyboardHidden ${configuration.keyboardHidden}, " +
                "hardKeyboardHidden ${configuration.hardKeyboardHidden}, navigation ${configuration.navigation}"
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
