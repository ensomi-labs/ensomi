package io.ensomi.android.chart

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Disposable audio stages, keyed exactly like the memory cache. All access is off the main thread. */
class PreparedStore(private val root: File, private val limitBytes: Long = 512L * 1024 * 1024) {
    private val mutableEntries = MutableStateFlow<Set<String>>(emptySet())
    val entries = mutableEntries.asStateFlow()
    private var lastUse = 0L

    init {
        root.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        trim()
        lastUse = files().maxOfOrNull { it.lastModified() } ?: 0L
    }

    /** The runtime constructor is the authority on format, runtime and model compatibility. */
    @Synchronized
    fun <T> read(key: String, decode: (ByteArray) -> T): T? {
        val file = file(key)
        if (!file.isFile) return null
        return try {
            check(file.length() in 1..limitBytes)
            decode(file.readBytes()).also { touch(file) }
        } catch (_: Exception) {
            file.delete()
            publish()
            null
        }
    }

    /** A failed or interrupted replacement leaves the previous complete file intact. Cache failures are misses. */
    @Synchronized
    fun write(key: String, writeBytes: (OutputStream) -> Unit): Boolean {
        val file = file(key)
        val temporary = File(root, "${file.name}.tmp")
        return try {
            check(root.isDirectory || root.mkdirs())
            FileOutputStream(temporary).use { output ->
                writeBytes(output)
                output.fd.sync()
            }
            check(temporary.length() in 1..limitBytes)
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            touch(file)
            trim()
            true
        } catch (_: Exception) {
            false
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun used(key: String) { file(key).takeIf { it.isFile }?.let(::touch) }

    fun contains(key: String): Boolean = name(key) in entries.value

    private fun file(key: String) = File(root, name(key))

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".prepared"

    private fun touch(file: File) {
        lastUse = maxOf(System.currentTimeMillis(), lastUse + 1)
        file.setLastModified(lastUse)
    }

    private fun files() = root.listFiles()?.filter { it.isFile && it.name.endsWith(".prepared") }.orEmpty()

    private fun trim() {
        val files = files().sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= limitBytes) break
            val bytes = file.length()
            if (file.delete()) size -= bytes
        }
        publish()
    }

    private fun publish() { mutableEntries.value = files().map { it.name }.toSet() }

    companion object {
        private val stores = mutableMapOf<String, PreparedStore>()

        /** Library and recognition have separate makers but must share file locking and eviction notifications. */
        @Synchronized
        fun shared(root: File): PreparedStore = stores.getOrPut(root.canonicalPath) { PreparedStore(root) }
    }
}
