package com.htmlrenderstudio.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.htmlrenderstudio.data.AppStorage
import com.htmlrenderstudio.data.Project
import java.io.File
import java.io.InputStream
import java.util.UUID

class ProjectImporter(private val context: Context, private val storage: AppStorage) {

    /** Hasil ekstraksi; [htmlFiles] diurutkan (yang paling dangkal dulu). Project belum disimpan ke daftar. */
    data class Draft(val project: Project, val htmlFiles: List<String>)

    fun importFromUri(uri: Uri): Draft {
        val (name, size) = describe(uri)
        if (size > ZipLimits.MAX_ZIP_BYTES) {
            throw ZipImportException("File ZIP terlalu besar (maks ${ZipLimits.MAX_ZIP_BYTES / (1024 * 1024)} MB).")
        }
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw ZipImportException("Tidak bisa membuka file yang dipilih.")
        return stream.use { extractInto(it, name, size.coerceAtLeast(0)) }
    }

    fun importSample(): Draft {
        val stream = context.assets.open("sample-project.zip")
        return stream.use { extractInto(it, "Sample 10 detik.zip", 0) }
    }

    fun describe(uri: Uri): Pair<String, Long> {
        var name = "project.zip"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = c.getString(ni) ?: name
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return name to size
    }

    private fun extractInto(stream: InputStream, displayName: String, zipBytes: Long): Draft {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val projectDir = File(storage.projectsRoot, id)
        val contentDir = File(projectDir, "content")
        try {
            val result = ZipExtractor.extract(stream, contentDir)
            val base = resolveBase(contentDir)
            val htmls = findHtml(base)
            if (htmls.isEmpty()) throw ZipImportException("Tidak ada file .html di dalam ZIP.")
            val entry = htmls.firstOrNull { it.equals("index.html", ignoreCase = true) } ?: htmls.first()
            val project = Project(
                id = id,
                name = displayName.substringBeforeLast('.').ifBlank { "Project" },
                contentRoot = base.relativeTo(projectDir).invariantSeparatorsPath,
                entry = entry,
                importedAt = System.currentTimeMillis(),
                zipBytes = zipBytes,
                extractedBytes = result.totalBytes,
                fileCount = result.fileCount,
            )
            return Draft(project, htmls)
        } catch (t: Throwable) {
            projectDir.deleteRecursively()
            throw t
        }
    }

    /** Kalau ZIP hanya berisi satu folder pembungkus, anggap folder itu sebagai root project. */
    private fun resolveBase(contentDir: File): File {
        var cur = contentDir
        while (true) {
            val kids = cur.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
            if (kids.size == 1 && kids[0].isDirectory) cur = kids[0] else return cur
        }
    }

    private fun findHtml(base: File): List<String> =
        base.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("html", "htm") }
            .map { it.relativeTo(base).invariantSeparatorsPath }
            .sortedWith(compareBy({ p -> p.count { it == '/' } }, { it }))
            .take(50)
            .toList()
}
