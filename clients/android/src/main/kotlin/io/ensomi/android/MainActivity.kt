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

        // One line in logcat says whether the packaged runtime reaches the core on this build.
        RuntimeSelfCheck.run()
            .onSuccess { Log.i("EnsomiRuntime", it) }
            .onFailure { Log.w("EnsomiRuntime", "parse_osu failed", it) }

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