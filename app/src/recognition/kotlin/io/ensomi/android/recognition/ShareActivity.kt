package io.ensomi.android.recognition

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.ensomi.android.MainActivity

/**
 * The share target: takes what was shared, hands it to the session (which starts one if there is none), and brings
 * Ensomi to the front, which is how the person gets back from the host app. It has no window of its own.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = listOfNotNull(
            intent?.getStringExtra(Intent.EXTRA_SUBJECT),
            intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        ).joinToString("\n")
        if (intent?.action == Intent.ACTION_SEND && text.isNotEmpty()) {
            RecognitionController.get(this).share(text, referrer?.host)
        }
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }
}
