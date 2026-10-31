package io.ensomi.android.recognition

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What a listening source found. */
interface FileListener {
    fun seen(file: FileFacts, evidence: Evidence)

    fun gone(path: String)
}

/**
 * Three ways of seeing a song's file arrive, all running at once, because nobody can say today which of them works on
 * the phone in the room: a `FileObserver` on the host's music folder (it needs all-files access, and whether it fires
 * through the FUSE layer is not known), MediaStore's content observer (with `READ_MEDIA_AUDIO`), and a slow poll of both
 * as the last resort. Whichever fires first counts, and the session judges each sighting by what it proves ([Evidence]).
 *
 * A `.ncm` is only ever looked at from the outside: its name, size and time. Nothing opens it.
 */
class MediaListening(
    context: Context,
    private val scope: CoroutineScope,
    private val sinceWallMs: Long,
    private val listener: FileListener,
    private val trace: (String, Map<String, Any?>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val folder = File(Environment.getExternalStorageDirectory(), HOST_MUSIC_FOLDER)
    private val thread = HandlerThread("EnsomiMedia")
    private lateinit var handler: Handler
    private var observer: ContentObserver? = null
    private var folderObserver: FileObserver? = null
    private var poll: Job? = null

    /** What each source last reported of a path, on [handler]'s thread or the poll's: a change is news, a repeat is not. */
    private val reported = HashMap<String, Pair<Long, Long>>()
    private val lastWrite = HashMap<String, Long>()

    fun start() {
        thread.start()
        handler = Handler(thread.looper)
        val rows = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(readRows)
                handler.postDelayed(readRows, ROWS_DEBOUNCE_MS)
            }
        }
        val registered = runCatching { resolver.registerContentObserver(AUDIO, true, rows) }
        if (registered.isSuccess) observer = rows
        watchFolder()
        poll = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_MS)
                handler.post(pollOnce)
            }
        }
        trace(
            "watch.start",
            mapOf(
                "folder" to folder.path,
                "folder_exists" to folder.isDirectory,
                "all_files_access" to Environment.isExternalStorageManager(),
                "media_store" to registered.isSuccess,
                "media_store_failure" to registered.exceptionOrNull()?.message,
                "file_observer" to (folderObserver != null),
            ),
        )
    }

    fun stop() {
        poll?.cancel()
        observer?.let(resolver::unregisterContentObserver)
        observer = null
        folderObserver?.stopWatching()
        folderObserver = null
        thread.quitSafely()
    }

    /** Every audio file the device shows: MediaStore's rows, and what can be listed in the host's folder. Blocking. */
    fun scan(): List<FileFacts> {
        val entries = LinkedHashMap<String, FileFacts>()
        for (row in rows(null)) entries[row.path] = row
        for (file in folder.listFiles().orEmpty()) {
            if (file.isFile && file.path !in entries) entries[file.path] = stat(file, withTags = true)
        }
        return entries.values.toList()
    }

    /** The file at [path] as it is now, or null when it has gone. Blocking. */
    fun look(path: String): FileFacts? {
        val file = File(path)
        return if (file.isFile) stat(file, withTags = true) else null
    }

    private fun watchFolder() {
        if (folderObserver != null || !folder.isDirectory) return
        val watcher = object : FileObserver(folder, MASK) {
            override fun onEvent(event: Int, path: String?) {
                val name = path ?: return
                val file = File(folder, name)
                when (event and ALL_EVENTS) {
                    CLOSE_WRITE -> handler.post { report(file, Evidence.CLOSE_WRITE) }
                    MOVED_TO -> handler.post { report(file, Evidence.MOVED_TO) }
                    CREATE, MODIFY -> handler.post { written(file) }
                    MOVED_FROM, DELETE -> handler.post { forget(file.path) }
                }
            }
        }
        watcher.startWatching()
        folderObserver = watcher
        trace("watch.folder", mapOf("folder" to folder.path))
    }

    /** A file being written: once a second is plenty. */
    private fun written(file: File) {
        val now = System.currentTimeMillis()
        val last = lastWrite[file.path] ?: 0
        if (now - last < WRITE_REPORT_MS) return
        lastWrite[file.path] = now
        report(file, Evidence.WRITING)
    }

    private fun report(file: File, evidence: Evidence) {
        if (!file.isFile) return
        val facts = stat(file, withTags = evidence != Evidence.WRITING)
        // A file being written is not yet news to the others: a row or the poll may be what says it finished.
        if (evidence.finished) reported[facts.path] = facts.sizeBytes to facts.modifiedWallMs
        listener.seen(facts, evidence)
    }

    private fun forget(path: String) {
        reported.remove(path)
        lastWrite.remove(path)
        listener.gone(path)
    }

    private val readRows = Runnable { newRows() }

    private fun newRows() {
        val since = sinceWallMs / 1000 - ROW_TIME_SLACK_SECONDS
        for (row in rows("${MediaStore.Audio.Media.DATE_MODIFIED} >= $since")) {
            val state = row.sizeBytes to row.modifiedWallMs
            if (reported[row.path] == state) continue
            reported[row.path] = state
            listener.seen(row, if (row.pending) Evidence.WRITING else Evidence.MEDIA_STORE)
        }
    }

    private val pollOnce = Runnable {
        watchFolder()
        newRows()
        for (file in folder.listFiles().orEmpty()) {
            if (!file.isFile) continue
            val state = file.length() to file.lastModified()
            if (reported[file.path] == state) continue
            reported[file.path] = state
            listener.seen(stat(file, withTags = true), Evidence.POLL)
        }
    }

    private fun rows(selection: String?): List<FileFacts> {
        val arguments = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val found = ArrayList<FileFacts>()
        try {
            resolver.query(AUDIO, PROJECTION, arguments, null)?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val data = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val name = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val modified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val pending = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PENDING)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(data) ?: continue
                    // The file's own size and time, as the other sources see them: a row's time is in whole seconds,
                    // and two sources must not make one file look as if it keeps changing.
                    val disk = File(path)
                    found += FileFacts(
                        path = path,
                        name = cursor.getString(name) ?: disk.name,
                        sizeBytes = disk.length().takeIf { it > 0 } ?: cursor.getLong(size),
                        modifiedWallMs = disk.lastModified().takeIf { it > 0 } ?: (cursor.getLong(modified) * 1000),
                        title = cursor.getString(title)?.takeIf { it.isNotBlank() },
                        artist = cursor.getString(artist)?.takeIf { it.isNotBlank() && it != UNKNOWN_ARTIST },
                        durationMs = cursor.getLong(duration).takeIf { it > 0 },
                        uri = ContentUris.withAppendedId(AUDIO, cursor.getLong(id)).toString(),
                        pending = cursor.getInt(pending) != 0,
                    )
                }
            }
        } catch (failure: SecurityException) {
            trace("watch.failed", mapOf("source" to "media_store", "reason" to failure.message))
        }
        return found
    }

    private fun stat(file: File, withTags: Boolean): FileFacts {
        val facts = FileFacts(file.path, file.name, file.length(), file.lastModified())
        if (!withTags || !CandidateRules.isAudio(file.name) || CandidateRules.isTemporary(file.name) || facts.sizeBytes == 0L) {
            return facts
        }
        return tagged(facts, Uri.fromFile(file))
    }

    private fun tagged(facts: FileFacts, uri: Uri): FileFacts {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            facts.copy(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            )
        } catch (_: RuntimeException) {
            // Not readable as media yet: a file still arriving, or none at all. The decoder decides.
            facts
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        /** Where the host keeps its downloads, under the shared storage's root. */
        const val HOST_MUSIC_FOLDER = "Download/netease/cloudmusic/Music"

        private val AUDIO: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        private const val UNKNOWN_ARTIST = "<unknown>"
        private const val POLL_MS = 5_000L
        private const val ROWS_DEBOUNCE_MS = 500L
        private const val WRITE_REPORT_MS = 1_000L

        /** `DATE_MODIFIED` is in whole seconds, and a scan may lag the write. */
        private const val ROW_TIME_SLACK_SECONDS = 2L
        private const val MASK = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.CLOSE_WRITE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.DELETE
        private val PROJECTION = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.IS_PENDING,
        )
    }
}
