package io.ensomi.android.probe

import android.os.FileObserver
import android.os.SystemClock
import java.io.File

/**
 * Watches one directory for completed writes. `CLOSE_WRITE` and `MOVED_TO` are the two events the
 * report accepts as "the downloader is done with this file"; the rest are reported so a trace shows
 * why a candidate was ignored.
 */
class DownloadWatcher(
    private val directory: File,
    private val onEvent: (WatchEvent) -> Unit,
) {
    private var observer: FileObserver? = null

    val isWatching: Boolean
        get() = observer != null

    fun start() {
        if (observer != null) return
        @Suppress("DEPRECATION")
        val created = object : FileObserver(
            directory.path,
            CREATE or MODIFY or CLOSE_WRITE or MOVED_TO or MOVED_FROM or DELETE,
        ) {
            override fun onEvent(event: Int, path: String?) {
                val name = path ?: return
                val kind = kindOf(event) ?: return
                onEvent(
                    WatchEvent(
                        kind = kind,
                        path = File(directory, name).absolutePath,
                        tMonoNs = SystemClock.elapsedRealtimeNanos(),
                        wallMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
        created.startWatching()
        observer = created
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    private fun kindOf(mask: Int): WatchKind? = when (mask and ALL_EVENTS) {
        CREATE -> WatchKind.CREATE
        MODIFY -> WatchKind.MODIFY
        CLOSE_WRITE -> WatchKind.CLOSE_WRITE
        MOVED_TO -> WatchKind.MOVED_TO
        MOVED_FROM -> WatchKind.MOVED_FROM
        DELETE -> WatchKind.DELETE
        else -> null
    }

    private companion object {
        const val CREATE = FileObserver.CREATE
        const val MODIFY = FileObserver.MODIFY
        const val CLOSE_WRITE = FileObserver.CLOSE_WRITE
        const val MOVED_TO = FileObserver.MOVED_TO
        const val MOVED_FROM = FileObserver.MOVED_FROM
        const val DELETE = FileObserver.DELETE
        const val ALL_EVENTS = FileObserver.ALL_EVENTS
    }
}
