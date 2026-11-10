package com.htmlrenderstudio.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import com.htmlrenderstudio.data.RenderSettings
import com.htmlrenderstudio.preview.LocalProjectServer
import com.htmlrenderstudio.preview.PageAnalysis
import com.htmlrenderstudio.preview.ProjectWebViewFactory
import com.htmlrenderstudio.preview.awaitVisualState
import com.htmlrenderstudio.preview.evalJs
import com.htmlrenderstudio.preview.parseJsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max

class RenderException(message: String) : Exception(message)

enum class Phase { LOADING, RENDERING, FINALIZING }

data class RenderProgress(
    val phase: Phase,
    val frame: Int,
    val totalFrames: Int,
    val elapsedMs: Long,
    val message: String,
)

data class RenderOutput(
    val file: File,
    val frames: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val durationMs: Long,
    val renderMs: Long,
    val warnings: List<String>,
)

/**
 * Alur: muat project di WebView (waktu virtual, mode manual) -> tunggu aset -> untuk tiap frame:
 * seek(t) -> tunggu renderer -> gambar WebView ke satu Bitmap yang dipakai ulang -> encode.
 *
 * Harus dipanggil dari main thread; [host] adalah FrameLayout yang sudah ada di layar
 * (WebView harus ter-attach ke window agar bisa menggambar).
 */
class RenderEngine(private val context: Context) {

    suspend fun render(
        host: FrameLayout,
        baseDir: File,
        entry: String,
        settings: RenderSettings,
        outFile: File,
        onProgress: (RenderProgress) -> Unit,
    ): RenderOutput {
        val t0 = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - t0

        val (w, h) = settings.dimensions()
        val fps = settings.fps
        val server = LocalProjectServer(baseDir, w, h, manual = true, shim = LocalProjectServer.loadShim(context))
        val web = ProjectWebViewFactory.create(context, server)
        val encoder = VideoEncoder(w, h, fps, outFile)
        var bitmap: Bitmap? = null
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit(host, web, w) }
        val warnings = mutableListOf<String>()

        try {
            onProgress(RenderProgress(Phase.LOADING, 0, 0, 0, "Memuat project..."))

            host.clipChildren = true
            web.pivotX = 0f
            web.pivotY = 0f
            host.addView(web, FrameLayout.LayoutParams(w, h))
            host.addOnLayoutChangeListener(layoutListener)
            fit(host, web, w)

            val loaded = CompletableDeferred<Unit>()
            server.onPageFinished = { loaded.complete(Unit) }
            web.loadUrl(LocalProjectServer.urlFor(entry))
            withTimeoutOrNull(30_000) { loaded.await() }
                ?: throw RenderException("Project tidak selesai dimuat dalam 30 detik.")

            // Tunggu font, gambar, dan resource lain siap.
            val readyDeadline = elapsed() + 15_000
            var ready = false
            while (elapsed() < readyDeadline) {
                val r = web.evalJs("window.__rs ? String(window.__rs.ready()) : 'noshim'") ?: ""
                if (r.contains("noshim")) throw RenderException("Gagal menyuntikkan kontrol timeline ke halaman.")
                if (r.contains("true")) { ready = true; break }
                delay(100)
            }
            if (!ready) warnings += "Sebagian aset belum selesai dimuat setelah 15 detik; video dirender apa adanya."

            // Atur animasi ke waktu awal, lalu baca hasil analisis.
            web.evalJs("window.__rs.seek(0)")
            repeat(2) { web.awaitVisualState() }
            val info = parseJsonObject(web.evalJs("window.__rs.info()"))
            val analysis = info?.let { PageAnalysis.from(it, server.errorLines()) }
            analysis?.notes()?.filter { it.level == PageAnalysis.Level.WARN }?.forEach { warnings += it.text }

            val durationMs: Long = if (settings.autoDuration) {
                analysis?.durationMs
                    ?: throw RenderException(
                        "Durasi otomatis tidak terdeteksi. Pilih Custom duration, atau set window.renderDurationMs di project."
                    )
            } else {
                (settings.customSeconds * 1000).toLong()
            }
            if (durationMs < 100) throw RenderException("Durasi terlalu pendek.")

            val total = max(1, ceil(durationMs * fps / 1000.0).toInt())

            val frameBitmap = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (oom: OutOfMemoryError) {
                throw RenderException("Memori tidak cukup untuk ${w}x$h. Coba resolusi lebih rendah.")
            }
            bitmap = frameBitmap
            val canvas = Canvas(frameBitmap)

            encoder.start()

            for (i in 0 until total) {
                coroutineContext.ensureActive()
                val tMs = i * 1000.0 / fps

                web.evalJs("window.__rs.seek($tMs)")
                repeat(if (i == 0) 3 else 1) { web.awaitVisualState() }

                canvas.drawColor(Color.WHITE)
                web.draw(canvas)

                val pts = i * 1_000_000L / fps
                withContext(Dispatchers.Default) { encoder.encode(frameBitmap, pts) }

                onProgress(RenderProgress(Phase.RENDERING, i + 1, total, elapsed(), "Encoding H.264"))
            }

            onProgress(RenderProgress(Phase.FINALIZING, total, total, elapsed(), "Menyelesaikan file MP4..."))
            withContext(Dispatchers.Default) { encoder.finish() }

            return RenderOutput(outFile, total, w, h, fps, durationMs, elapsed(), warnings)
        } finally {
            withContext(NonCancellable) {
                host.removeOnLayoutChangeListener(layoutListener)
                runCatching { host.removeView(web) }
                runCatching { web.stopLoading() }
                runCatching { web.destroy() }
                encoder.release()
                bitmap?.recycle()
            }
        }
    }

    /** WebView berukuran asli (w x h px); di layar ditampilkan diperkecil agar muat. Capture tidak terpengaruh skala. */
    private fun fit(host: FrameLayout, web: View, w: Int) {
        if (host.width > 0) {
            val s = host.width.toFloat() / w
            web.scaleX = s
            web.scaleY = s
        }
    }
}
