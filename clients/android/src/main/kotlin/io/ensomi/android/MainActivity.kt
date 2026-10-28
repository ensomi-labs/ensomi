package io.ensomi.android

import android.content.Intent
import android.util.Log
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import io.ensomi.android.ui.EnsomiTheme
import io.ensomi.android.ui.Workbench
import io.ensomi.android.ui.WorkbenchState
import io.ensomi.android.ui.folderFromUri

/**
 * S1 Workbench. The client opens on Library, the way the iOS client does, and the Settings sheet and
 * the system folder picker are reached from here.
 */
class MainActivity : ComponentActivity() {
    private lateinit var state: WorkbenchState

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                addFolder(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = WorkbenchState(this)

        // Three lines in logcat say how far the packaged runtime reaches on this build: the binding, the
        // ONNX Runtime behind the generator, and the generator's state without a model installed.
        RuntimeSelfCheck.run()
            .onSuccess { Log.i("EnsomiRuntime", it) }
            .onFailure { Log.w("EnsomiRuntime", "parse_osu failed", it) }
        RuntimeSelfCheck.loadOnnxRuntime()
            .onSuccess { Log.i("EnsomiRuntime", it) }
            .onFailure { Log.w("EnsomiRuntime", "onnxruntime load failed", it) }
        RuntimeSelfCheck.openGenerator(getExternalFilesDir("model") ?: java.io.File(filesDir, "model"))
            .onSuccess { Log.i("EnsomiRuntime", "generator: $it") }
            .onFailure { Log.i("EnsomiRuntime", "generator without a model: ${it.message}") }

        // A reinstall loses the persisted grants while the library keeps its folders, so the page says so
        // at once instead of failing when a song is played.
        state.scanner.refreshStatus()

        setContent {
            EnsomiTheme {
                Workbench(
                    state = state,
                    onPickFolder = { folderPicker.launch(null) },
                    onOpenProbe = { startActivity(Intent(this, ProbeActivity::class.java)) },
                )
            }
        }
    }

    /**
     * Keeps the grant, then indexes without a second action, which is S2's acceptance condition. A
     * provider that refuses the persistable grant still gets its files indexed for this session.
     */
    private fun addFolder(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        state.library.addFolder(folderFromUri(this, uri))
        state.scanner.scan()
    }
}