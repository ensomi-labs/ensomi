package io.ensomi.android.probe

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager

/** How well a deep link's behavior is known. Mirrors the report's evidence ledger. */
enum class LinkEvidence { DEVICE_CONFIRMED, RESOLVES_ONLY, UNSUPPORTED }

/**
 * One NetEase URI worth probing. [evidence] records what this project has actually observed, so the
 * client never treats "resolveActivity answered" as "the screen this URI promises appears".
 */
data class DeepLink(
    val uri: String,
    val label: String,
    val evidence: LinkEvidence,
    val note: String,
)

data class Resolution(val uri: String, val component: String?, val error: String?)

data class Launch(val uri: String, val component: String?, val started: Boolean, val error: String?)

/**
 * Probes what a third-party app may do with the installed NetEase client. Every result carries the
 * caller it was measured with, because the report insists that "adb shell can start it" and "an app
 * can start it" are different facts.
 */
object NetEaseProbe {
    const val PACKAGE = DeviceProbe.NETEASE_PACKAGE

    /** The recognition entry the K80 opens with 听歌识曲 on screen. */
    const val IDENTIFY_URI = "orpheus://identify"

    fun song(recordingId: Long): String = "orpheus://song/$recordingId"

    val links: List<DeepLink> = listOf(
        DeepLink(
            uri = "orpheus://song/{songId}",
            label = "song page",
            evidence = LinkEvidence.DEVICE_CONFIRMED,
            note = "RedirectActivity opened the song on the target device",
        ),
        DeepLink(
            uri = IDENTIFY_URI,
            label = "recognition page",
            evidence = LinkEvidence.DEVICE_CONFIRMED,
            note = "opened 听歌识曲 directly; the report listed this as unsupported",
        ),
        DeepLink(
            uri = "orpheus://recognize",
            label = "alternate recognition spelling",
            evidence = LinkEvidence.UNSUPPORTED,
            note = "resolves to RedirectActivity but landed on an update dialog",
        ),
        DeepLink(
            uri = "orpheus://song/{songId}/download",
            label = "download action",
            evidence = LinkEvidence.UNSUPPORTED,
            note = "no download URI exists in NetEase 9.2.45",
        ),
    )

    fun resolve(context: Context, uri: String): Resolution {
        val intent = viewIntent(uri)
        return try {
            val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            Resolution(uri, info?.activityInfo?.let { "${it.packageName}/${it.name}" }, null)
        } catch (error: Exception) {
            Resolution(uri, null, "${error::class.java.simpleName}: ${error.message}")
        }
    }

    fun launch(context: Context, uri: String): Launch {
        val intent = viewIntent(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val component = resolve(context, uri).component
        return try {
            context.startActivity(intent)
            Launch(uri, component, true, null)
        } catch (error: ActivityNotFoundException) {
            Launch(uri, component, false, "no activity accepts $uri")
        } catch (error: SecurityException) {
            Launch(uri, component, false, "denied: ${error.message}")
        } catch (error: Exception) {
            Launch(uri, component, false, "${error::class.java.simpleName}: ${error.message}")
        }
    }

    private fun viewIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PACKAGE)
}
