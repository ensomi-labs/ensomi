package io.ensomi.android.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.ensomi.android.ui.theme.EnsomiTheme

/** Independent recovery entry: no AppStores, model or native runtime on launch or export. */
class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("EnsomiRecovery", "DiagnosticsActivity started")
        enableEdgeToEdge()
        setContent { EnsomiTheme { DiagnosticsScreen(onBack = ::finish) } }
        DiagnosticsFaults.run(this)
    }
    companion object {
        /** Also used by the model card's recovery button. Does not initialise normal navigation. */
        fun intent(context: Context): Intent = Intent(context, DiagnosticsActivity::class.java)
    }
}
