package com.htmlrenderstudio.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.htmlrenderstudio.data.AppStorage
import com.htmlrenderstudio.data.HistoryEntry
import com.htmlrenderstudio.render.RenderOutput
import java.io.File
import java.io.IOException
import java.util.UUID

class StorageManager(private val context: Context, private val storage: AppStorage) {

    /** Memindahkan hasil render dari folder sementara ke folder renders/ dan membuat entri riwayat. */
    fun commitRender(tmp: File, projectId: String, projectName: String, out: RenderOutput): HistoryEntry {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val dest = File(storage.rendersRoot, "$id.mp4")
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        val entry = HistoryEntry(
            id = id,
            projectId = projectId,
            projectName = projectName,
            fileName = dest.name,
            createdAt = System.currentTimeMillis(),
            width = out.width,
            height = out.height,
            fps = out.fps,
            frames = out.frames,
            durationMs = out.durationMs,
            renderMs = out.renderMs,
            sizeBytes = dest.length(),
        )
        storage.addHistory(entry)
        return entry
    }

    fun suggestedName(e: HistoryEntry): String {
        val safe = e.projectName.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "video" }
        return "${safe}_${e.width}x${e.height}_${e.fps}fps.mp4"
    }

    /** Simpan ke galeri (Movies/HTMLRenderStudio) lewat MediaStore; tidak butuh izin storage di Android 10+. */
    fun saveToGallery(e: HistoryEntry): Uri {
        val src = storage.renderFile(e)
        if (!src.isFile) throw IOException("File video tidak ditemukan.")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, suggestedName(e))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HTMLRenderStudio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Gagal membuat entri galeri.")
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Gagal membuka tujuan simpan.")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }

    /** Simpan ke lokasi yang dipilih pengguna lewat Storage Access Framework (CreateDocument). */
    fun copyToUri(e: HistoryEntry, dest: Uri) {
        val src = storage.renderFile(e)
        if (!src.isFile) throw IOException("File video tidak ditemukan.")
        context.contentResolver.openOutputStream(dest)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            ?: throw IOException("Gagal membuka tujuan simpan.")
    }

    fun shareIntent(e: HistoryEntry): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", storage.renderFile(e))
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
