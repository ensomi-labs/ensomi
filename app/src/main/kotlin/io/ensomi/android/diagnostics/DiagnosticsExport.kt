package io.ensomi.android.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.ensomi.android.R
import io.ensomi.android.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DiagnosticsExport {
    fun create(context: Context, report: DiagnosticsReport): File {
        val dir = File(context.cacheDir, "diagnostics-export").also { it.mkdirs() }
        // A stable name keeps the cache bounded; the provider exposes this directory alone.
        val file = File(dir, "ensomi-diagnostics.zip")
        val temp = File(dir, "bundle.tmp")
        ZipOutputStream(temp.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("summary.json", report.toJson().toByteArray())
            entry("journal.jsonl", report.journal.joinToString("") { Json.encode(it) + "\n" }.toByteArray())
            entry("exits.jsonl", report.exits.joinToString("") { Json.encode(it) + "\n" }.toByteArray())
            report.exits.mapNotNull { it["trace"] as? String }.filter { it.matches(Regex("trace-[0-9]+-[0-9]+\\.txt")) }
                .distinct().takeLast(3).forEach { name ->
                    val trace = File(Journal.directory(context), name)
                    if (trace.isFile) entry(name, trace.readBytes().take(TracePrivacy.LIMIT).toByteArray())
                }
        }
        check(temp.renameTo(file))
        return file
    }
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, context.getString(R.string.diagnostics_title), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.crash_share)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
