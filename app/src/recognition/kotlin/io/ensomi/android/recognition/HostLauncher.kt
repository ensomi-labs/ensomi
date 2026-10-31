package io.ensomi.android.recognition

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** How starting the host app went. */
sealed interface LaunchResult {
    data class Started(val how: String) : LaunchResult

    data object NotInstalled : LaunchResult

    data class Failed(val reason: String) : LaunchResult
}

/** Starts the host music app with ordinary intents. */
object HostLauncher {
    const val PACKAGE = "com.netease.cloudmusic"
    private const val RECOGNITION_LINK = "orpheus://identify"

    fun launch(context: Context, path: LaunchPath): LaunchResult {
        val launcher = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return LaunchResult.NotInstalled
        val link = Intent(Intent.ACTION_VIEW, RECOGNITION_LINK.toUri()).setPackage(PACKAGE)
        return when (path) {
            LaunchPath.A -> start(context, launcher, "launcher")
            LaunchPath.B -> start(context, link, "deep_link")
            LaunchPath.AUTO -> start(context, link, "deep_link").takeIf { it is LaunchResult.Started }
                ?: start(context, launcher, "launcher")
        }
    }

    private fun start(context: Context, intent: Intent, how: String): LaunchResult = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        LaunchResult.Started(how)
    } catch (failure: ActivityNotFoundException) {
        LaunchResult.Failed("$how: ${failure.message}")
    } catch (failure: SecurityException) {
        LaunchResult.Failed("$how: ${failure.message}")
    }
}

/** What a short link turned out to be. */
data class LinkLookup(val songId: Long?, val outcome: String)

/**
 * Follows a public share short link by its plain HTTP redirects, to the page it points at, without cookies or anything
 * of anyone's, and with a short timeout: the id is a help, not a need, since the share's title and artist match files
 * without it. Only the short link hosts and the public song pages are ever asked; a redirect anywhere else is read, not
 * followed.
 */
object ShortLinks {
    private const val TIMEOUT_MS = 3_000
    private const val MOST_REDIRECTS = 3
    private val HOSTS = setOf("163cn.tv", "163.tn", "music.163.com", "y.music.163.com")

    /** Blocking. */
    fun lookUp(url: String): LinkLookup {
        var next = if (url.contains("://")) url else "https://$url"
        return try {
            repeat(MOST_REDIRECTS) {
                val secure = next.replaceFirst("http://", "https://")
                val target = URL(secure)
                if (target.host.lowercase() !in HOSTS) return LinkLookup(null, "not_followed: ${target.host}")
                val location = location(target) ?: return LinkLookup(null, "no_redirect")
                ShareParser.parse(location)?.songId?.let { return LinkLookup(it, "resolved") }
                next = location
            }
            LinkLookup(null, "too_many_redirects")
        } catch (failure: IOException) {
            LinkLookup(null, "failed: ${failure.message}")
        }
    }

    private fun location(url: URL): String? {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.responseCode
            return connection.getHeaderField("Location")
        } finally {
            connection.disconnect()
        }
    }
}
