package com.htmlrenderstudio.importer

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object ZipLimits {
    const val MAX_ZIP_BYTES = 300L * 1024 * 1024
    const val MAX_EXTRACTED_BYTES = 800L * 1024 * 1024
    const val MAX_ENTRIES = 20_000
}

class ZipImportException(message: String) : Exception(message)

data class ExtractResult(val fileCount: Int, val totalBytes: Long)

object ZipExtractor {
    /**
     * Mengekstrak ZIP ke [destDir] sambil membaca stream (tanpa menyalin ZIP dulu).
     * - Menolak Zip Slip / path traversal (cek canonical path).
     * - Membatasi jumlah entri dan total byte hasil ekstraksi (yang dihitung dari byte yang benar-benar ditulis,
     *   bukan dari ukuran yang diklaim header, supaya zip bomb tetap tertangkap).
     */
    fun extract(input: InputStream, destDir: File): ExtractResult {
        destDir.mkdirs()
        val destCanon = destDir.canonicalFile
        val prefix = destCanon.path + File.separator
        var files = 0
        var total = 0L
        var entries = 0

        ZipInputStream(BufferedInputStream(input)).use { zis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val entry = zis.nextEntry ?: break
                entries++
                if (entries > ZipLimits.MAX_ENTRIES) {
                    throw ZipImportException("ZIP berisi terlalu banyak file (maks ${ZipLimits.MAX_ENTRIES}).")
                }
                val name = entry.name.replace('\\', '/')
                val leaf = name.substringAfterLast('/')
                if (name.startsWith("__MACOSX/") || leaf.startsWith("._") || leaf == ".DS_Store") {
                    zis.closeEntry()
                    continue
                }
                val out = File(destCanon, name).canonicalFile
                if (!out.path.startsWith(prefix)) {
                    throw ZipImportException("ZIP ditolak: path berbahaya terdeteksi (${entry.name}).")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    zis.closeEntry()
                    continue
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos ->
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > ZipLimits.MAX_EXTRACTED_BYTES) {
                            throw ZipImportException(
                                "Hasil ekstraksi melebihi batas ${ZipLimits.MAX_EXTRACTED_BYTES / (1024 * 1024)} MB."
                            )
                        }
                        fos.write(buf, 0, n)
                    }
                }
                files++
                zis.closeEntry()
            }
        }
        if (files == 0) throw ZipImportException("ZIP kosong atau bukan file ZIP yang valid.")
        return ExtractResult(files, total)
    }
}
