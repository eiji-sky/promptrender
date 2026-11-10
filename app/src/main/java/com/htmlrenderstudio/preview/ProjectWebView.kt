package com.htmlrenderstudio.preview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

object ProjectWebViewFactory {
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun create(context: Context, server: LocalProjectServer): WebView = WebView(context).apply {
        setBackgroundColor(Color.WHITE)
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        // Preview/render tidak interaktif; ini juga mencegah konflik gesture dengan scroll Compose.
        setOnTouchListener { _, _ -> true }

        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                server.loader.shouldInterceptRequest(request.url) ?: blocked(server, request)

            // Semua navigasi dari dalam halaman diblokir; hanya loadUrl() dari aplikasi yang jalan.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String?) {
                server.onPageFinished?.invoke()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                server.log("ERROR ${error.description}: ${request.url}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                server.log("HTTP ${errorResponse.statusCode}: ${request.url}")
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val level = if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) "ERROR" else "LOG"
                server.log("$level ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
    }

    private fun blocked(server: LocalProjectServer, request: WebResourceRequest): WebResourceResponse {
        server.log("HTTP 403 (diblokir, hanya aset lokal): ${request.url}")
        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}

// ---------- Helper JavaScript (panggil dari main thread) ----------

suspend fun WebView.evalJs(script: String): String? = suspendCancellableCoroutine { cont ->
    evaluateJavascript(script) { if (cont.isActive) cont.resume(it) }
}

/** evaluateJavascript mengembalikan JSON; fungsi JS kita mengembalikan JSON.stringify(...), jadi di-unquote dulu. */
fun parseJsonObject(raw: String?): JSONObject? {
    if (raw == null || raw == "null") return null
    return try {
        when (val v = JSONTokener(raw).nextValue()) {
            is String -> JSONObject(v)
            is JSONObject -> v
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

private val visualStateId = AtomicLong(1)

/** Menunggu sampai renderer WebView selesai menerapkan semua perubahan DOM sebelum titik ini. */
suspend fun WebView.awaitVisualState(timeoutMs: Long = 3000) {
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Unit> { cont ->
            postVisualStateCallback(visualStateId.getAndIncrement(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
        }
    }
}

// ---------- Analisis kompatibilitas ----------

data class PageAnalysis(
    val hasRenderFrame: Boolean,
    val cssAnimations: Int,
    val smilCount: Int,
    val rafCalls: Int,
    val timerCalls: Int,
    val durationMs: Long?,
    val errors: List<String>,
) {
    enum class Level { OK, WARN }
    data class Note(val level: Level, val text: String)

    fun notes(): List<Note> {
        val n = mutableListOf<Note>()
        if (hasRenderFrame) {
            n += Note(Level.OK, "window.renderFrame(timeMs) terdeteksi: kontrol timeline akurat.")
        }
        if (cssAnimations > 0 || smilCount > 0) {
            n += Note(Level.OK, "Animasi CSS/SVG terdeteksi ($cssAnimations CSS, $smilCount SMIL): dikendalikan lewat Web Animations / SMIL.")
        }
        if (!hasRenderFrame && (rafCalls > 0 || timerCalls > 0)) {
            n += Note(
                Level.WARN,
                "Animasi JavaScript tanpa renderFrame(timeMs): dijalankan dengan jam virtual " +
                    "(performance.now, requestAnimationFrame, setTimeout). Hasil bisa berbeda bila project membaca waktu dengan cara lain (mis. new Date()) atau memakai WebGL/video."
            )
        }
        if (!hasRenderFrame && cssAnimations == 0 && smilCount == 0 && rafCalls == 0 && timerCalls == 0) {
            n += Note(Level.WARN, "Tidak ada kontrol timeline yang terdeteksi. Video kemungkinan statis.")
        }
        if (errors.isNotEmpty()) {
            n += Note(Level.WARN, "${errors.size} error terdeteksi di halaman, contoh: ${errors.first().take(120)}")
        }
        return n
    }

    companion object {
        fun from(o: JSONObject, extraErrors: List<String> = emptyList()): PageAnalysis {
            val errs = mutableListOf<String>()
            o.optJSONArray("errors")?.let { for (i in 0 until it.length()) errs += it.getString(i) }
            errs += extraErrors
            return PageAnalysis(
                hasRenderFrame = o.optBoolean("hasRenderFrame"),
                cssAnimations = o.optInt("cssAnimations"),
                smilCount = o.optInt("smilCount"),
                rafCalls = o.optInt("rafCalls"),
                timerCalls = o.optInt("timerCalls"),
                durationMs = if (o.isNull("durationMs")) null else o.optLong("durationMs").takeIf { it > 0 },
                errors = errs.distinct(),
            )
        }
    }
}
