package com.htmlrenderstudio.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htmlrenderstudio.data.AspectRatio
import com.htmlrenderstudio.data.RenderSettings
import com.htmlrenderstudio.preview.LocalProjectServer
import com.htmlrenderstudio.preview.PageAnalysis
import com.htmlrenderstudio.preview.ProjectWebViewFactory
import com.htmlrenderstudio.preview.parseJsonObject
import java.io.File
import kotlin.math.ceil

@Stable
class PreviewController {
    var web: WebView? = null
    var paused by mutableStateOf(false)
    var reloadToken by mutableIntStateOf(0)

    fun togglePause() {
        paused = !paused
        web?.evaluateJavascript("window.__rs && window.__rs.setPaused($paused)", null)
    }

    fun restart() {
        paused = false
        reloadToken++
    }
}

@Composable
fun ProjectScreen(vm: AppViewModel, projectId: String) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val project = projects.firstOrNull { it.id == projectId }

    if (project == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MutedText("Project tidak ditemukan.") }
        return
    }

    val controller = remember(project.id) { PreviewController() }
    val baseDir = remember(project.id) { vm.contentDir(project) }
    val (w, h) = settings.dimensions()
    val durationSec: Float? = if (settings.autoDuration) analysis?.durationMs?.let { it / 1000f } else settings.customSeconds
    val frames = durationSec?.let { ceil(it * settings.fps).toInt() }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") }
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    MutedText("HTML utama: ${project.entry}")
                }
            }
        }
        item {
            HCard {
                SectionTitle("Preview")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    HtmlPreview(
                        baseDir = baseDir,
                        entry = project.entry,
                        projectId = project.id,
                        settings = settings,
                        controller = controller,
                        onAnalysis = vm::setAnalysis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = controller::togglePause) {
                        if (!controller.paused) Text("Pause") else {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Play")
                        }
                    }
                    OutlinedButton(onClick = controller::restart) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Ulang")
                    }
                }
                MutedText("Preview berjalan realtime dengan jam virtual yang sama seperti saat render, pada rasio ${settings.ratio.label}.")
            }
        }
        item {
            HCard {
                SectionTitle("Pemeriksaan kompatibilitas")
                val a = analysis
                if (a == null) {
                    MutedText("Menganalisis halaman...")
                } else {
                    a.notes().forEach { note ->
                        Text(
                            (if (note.level == PageAnalysis.Level.OK) "✓ " else "⚠ ") + note.text,
                            color = if (note.level == PageAnalysis.Level.OK) Good else Warn,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    MutedText(
                        a.durationMs?.let { "Durasi terdeteksi: ${"%.1f".format(it / 1000.0)} detik" }
                            ?: "Durasi tidak terdeteksi otomatis (set window.renderDurationMs atau pakai Custom)."
                    )
                }
            }
        }
        item {
            HCard {
                SectionTitle("Render settings")
                RenderSettingsEditor(settings, vm::updateSettings)
            }
        }
        item {
            HCard {
                MutedText(
                    "Output: ${w}×$h · ${settings.fps} FPS · " +
                        (frames?.let { "$it frame" } ?: "jumlah frame menunggu deteksi durasi")
                )
                Button(
                    onClick = vm::beginRender,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Render ke MP4") }
                MutedText("Biarkan aplikasi tetap terbuka dan layar menyala selama render.")
            }
        }
    }
}

@Composable
private fun HtmlPreview(
    baseDir: File,
    entry: String,
    projectId: String,
    settings: RenderSettings,
    controller: PreviewController,
    onAnalysis: (PageAnalysis?) -> Unit,
) {
    val context = LocalContext.current
    val (w, h) = settings.dimensions()
    val frac = when (settings.ratio) {
        AspectRatio.LANDSCAPE -> 1f
        AspectRatio.SQUARE -> 0.8f
        AspectRatio.PORTRAIT -> 0.55f
    }

    key(projectId, w, h, controller.reloadToken) {
        val server = remember { LocalProjectServer(baseDir, w, h, manual = false, shim = LocalProjectServer.loadShim(context)) }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth(frac)
                .aspectRatio(w.toFloat() / h)
                .clip(RoundedCornerShape(12.dp)),
            factory = { ctx ->
                ProjectWebViewFactory.create(ctx, server).also { web ->
                    controller.web = web
                    onAnalysis(null)
                    server.onPageFinished = {
                        web.postDelayed({
                            web.evaluateJavascript("window.__rs ? window.__rs.info() : null") { raw ->
                                onAnalysis(parseJsonObject(raw)?.let { PageAnalysis.from(it, server.errorLines()) })
                            }
                        }, 1200)
                    }
                    web.loadUrl(LocalProjectServer.urlFor(entry))
                }
            },
            onRelease = { web ->
                web.stopLoading()
                web.destroy()
                if (controller.web === web) controller.web = null
            },
        )
    }
}
