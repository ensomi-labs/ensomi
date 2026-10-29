package io.ensomi.android.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The songs the player picked, kept in `library.json` under the app's files: each document's URI, file name, length
 * and tags. Nothing is copied; a persistable read permission keeps each file readable across restarts, and is given
 * back when a song is removed. A file that can no longer be read is shown as missing until it is found again.
 */
class DocumentSongLibrary(context: Context, private val scope: CoroutineScope) : SongLibrary {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val file = AtomicFile(File(appContext.filesDir, "library.json"))
    private val mutex = Mutex()
    private var stored: List<StoredSong> = emptyList()
    private var missing: Set<String> = emptySet()
    private val state = MutableStateFlow<List<Song>>(emptyList())

    override val songs: StateFlow<List<Song>> = state.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                stored = runCatching { LibraryFile.decode(file.readFully().decodeToString()) }
                    .getOrDefault(StoredLibrary()).songs
                publish()
            }
            refresh()
        }
    }

    override suspend fun add(documents: List<Uri>): AddResult = withContext(Dispatchers.IO) {
        var added = 0
        var alreadyThere = 0
        val unreadable = mutableListOf<String>()
        val read = documents.distinct().mapNotNull { uri ->
            if (stored.any { it.uri == uri.toString() }) {
                alreadyThere++
                return@mapNotNull null
            }
            val song = readSong(uri, id = UUID.randomUUID().toString())
            if (song == null) {
                unreadable += displayName(uri) ?: uri.lastPathSegment.orEmpty()
                return@mapNotNull null
            }
            song
        }
        mutex.withLock {
            val known = stored.map { it.uri }.toSet()
            val fresh = read.filter { it.uri !in known }
            alreadyThere += read.size - fresh.size
            fresh.forEach { keepPermission(Uri.parse(it.uri)) }
            added = fresh.size
            stored = stored + fresh
            save()
            publish()
        }
        AddResult(added, alreadyThere, unreadable)
    }

    override suspend fun findAgain(id: String, document: Uri): Boolean = withContext(Dispatchers.IO) {
        val found = readSong(document, id) ?: return@withContext false
        mutex.withLock {
            val old = stored.firstOrNull { it.id == id } ?: return@withContext false
            keepPermission(document)
            if (old.uri != found.uri && stored.none { it.id != id && it.uri == old.uri }) {
                givePermissionBack(Uri.parse(old.uri))
            }
            stored = stored.map { if (it.id == id) found.copy(addedAtMs = old.addedAtMs) else it }
            missing = missing - id
            save()
            publish()
        }
        true
    }

    override fun remove(id: String) {
        scope.launch {
            mutex.withLock {
                val song = stored.firstOrNull { it.id == id } ?: return@withLock
                stored = stored - song
                missing = missing - id
                if (stored.none { it.uri == song.uri }) givePermissionBack(Uri.parse(song.uri))
                save()
                publish()
            }
        }
    }

    override fun refresh() {
        scope.launch {
            val songs = mutex.withLock { stored }
            val gone = songs.filterNot { isThere(Uri.parse(it.uri)) }.map { it.id }.toSet()
            mutex.withLock {
                missing = gone
                publish()
            }
        }
    }

    /** Marks a song missing when its file failed to open, as decoding finds out. */
    fun markMissing(id: String) {
        scope.launch {
            mutex.withLock {
                missing = missing + id
                publish()
            }
        }
    }

    private fun publish() {
        state.value = LibraryFile.sorted(stored).map { it.toSong(it.id in missing) }
    }

    private fun save() {
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the library", e)
            return
        }
        try {
            out.write(LibraryFile.encode(StoredLibrary(stored)).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            Log.w(TAG, "Could not write the library", e)
        }
    }

    /** The song in [uri]'s document, or null when it cannot be read as audio. */
    private fun readSong(uri: Uri, id: String): StoredSong? {
        val name = displayName(uri) ?: return null
        var title: String? = null
        var artist: String? = null
        var durationMs = 0L
        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(appContext, uri)
                if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == null) return null
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Not readable as audio: $name", e)
            return null
        } catch (e: IOException) {
            Log.w(TAG, "Not readable as audio: $name", e)
            return null
        }
        if (durationMs <= 0) durationMs = extractorDurationMs(uri) ?: return null
        return StoredSong(
            id = id,
            uri = uri.toString(),
            fileName = name,
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            artist = artist?.trim()?.takeIf { it.isNotEmpty() },
            durationMs = durationMs,
            addedAtMs = System.currentTimeMillis(),
        )
    }

    /** The length the container gives, for a file whose tags have none. */
    private fun extractorDurationMs(uri: Uri): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(appContext, uri, null)
            (0 until extractor.trackCount).asSequence().map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)?.div(1000)?.takeIf { it > 0 }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: RuntimeException) {
        null
    }

    /**
     * Whether the document is still there and readable: the permission is held and the provider still knows it. The
     * file is not opened, so a cloud provider does not fetch it.
     */
    private fun isThere(uri: Uri): Boolean {
        val held = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return held && displayName(uri) != null
    }

    private fun keepPermission(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // A provider that offers no persistable permission: the song reads until the app restarts.
            Log.w(TAG, "No persistable permission for $uri", e)
        }
    }

    private fun givePermissionBack(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to give back for $uri", e)
        }
    }

    private fun StoredSong.toSong(missing: Boolean) = Song(
        id = id,
        title = shownTitle,
        artist = artist,
        durationMs = durationMs,
        fileName = fileName,
        uri = Uri.parse(uri),
        missing = missing,
    )

    private companion object {
        const val TAG = "EnsomiLibrary"
    }
}
