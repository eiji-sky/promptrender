package com.htmlrenderstudio.preview

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Menyajikan file project dari folder lokal lewat https://appassets.androidplatform.net/p/...
 * - Tidak ada akses file:// dan tidak ada akses jaringan; semua selain host ini diblokir.
 * - Path traversal dicegah dengan cek canonical path.
 * - Untuk file HTML, disuntikkan meta viewport + shim kontrol waktu (assets/rs-shim.js) di awal <head>.
 */
class LocalProjectServer(
    baseDir: File,
    private val viewportW: Int,
    private val viewportH: Int,
    private val manual: Boolean,
    private val shim: String,
) {
    companion object {
        const val HOST = "appassets.androidplatform.net"

        fun urlFor(entry: String): String =
            "https://$HOST/p/" + entry.split('/').joinToString("/") { Uri.encode(it) }

        fun loadShim(context: Context): String =
            context.assets.open("rs-shim.js").bufferedReader().use { it.readText() }

        private val viewportRegex = Regex("<meta[^>]+name=[\"']viewport[\"'][^>]*>", RegexOption.IGNORE_CASE)
        private val headRegex = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
        private val htmlRegex = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    }

    private val baseCanon: File = baseDir.canonicalFile
    private val logLines = ArrayDeque<String>()

    @Volatile
    var onPageFinished: (() -> Unit)? = null

    val loader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(HOST)
        .addPathHandler("/p/", Handler())
        .build()

    fun log(line: String) {
        synchronized(logLines) {
            if (logLines.size >= 200) logLines.removeFirst()
            logLines.addLast(line)
        }
    }

    fun errorLines(): List<String> = synchronized(logLines) { logLines.filter { it.startsWith("ERROR") || it.startsWith("HTTP") } }

    private inner class Handler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val rel = path.trimStart('/')
            val file = try {
                File(baseCanon, rel).canonicalFile
            } catch (e: IOException) {
                return notFound()
            }
            if (!file.path.startsWith(baseCanon.path + File.separator) || !file.isFile) return notFound()

            val ext = file.extension.lowercase()
            return if (ext == "html" || ext == "htm") {
                val html = inject(file.readText(Charsets.UTF_8))
                WebResourceResponse("text/html", "UTF-8", 200, "OK", noCache(), ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
            } else {
                WebResourceResponse(mimeOf(ext), null, 200, "OK", noCache(), FileInputStream(file))
            }
        }
    }

    private fun inject(src: String): String {
        val cleaned = viewportRegex.replace(src, "")
        val block = "<meta name=\"viewport\" content=\"width=$viewportW, user-scalable=no\">" +
            "<script>window.__RS_MANUAL__=$manual;window.__RS_W=$viewportW;window.__RS_H=$viewportH;</script>" +
            "<script>$shim</script>"
        headRegex.find(cleaned)?.let { m ->
            val at = m.range.last + 1
            return cleaned.substring(0, at) + block + cleaned.substring(at)
        }
        htmlRegex.find(cleaned)?.let { m ->
            val at = m.range.last + 1
            return cleaned.substring(0, at) + "<head>$block</head>" + cleaned.substring(at)
        }
        return "<head>$block</head>$cleaned"
    }

    private fun noCache() = mapOf("Cache-Control" to "no-store")

    private fun notFound() = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    private fun mimeOf(ext: String): String = when (ext) {
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        "txt" -> "text/plain"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
