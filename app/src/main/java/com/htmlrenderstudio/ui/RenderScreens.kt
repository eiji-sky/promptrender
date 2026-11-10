package com.htmlrenderstudio.ui

import android.content.Intent
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htmlrenderstudio.data.AspectRatio
import com.htmlrenderstudio.render.Phase

@Composable
fun RenderingScreen(vm: AppViewModel) {
    val state by vm.render.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val (w, h) = settings.dimensions()
    var host by remember { mutableStateOf<FrameLayout?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }

    BackHandler { if (state.error != null) vm.leaveRenderError() else confirmCancel = true }

    LaunchedEffect(host) {
        host?.let { vm.startRender(it) }
    }

    val frac = when (settings.ratio) {
        AspectRatio.LANDSCAPE -> 1f
        AspectRatio.SQUARE -> 0.8f
        AspectRatio.PORTRAIT -> 0.55f
    }
    val percent = if (state.total > 0) (state.frame * 100 / state.total) else 0

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Rendering Video", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(frac).aspectRatio(w.toFloat() / h).clip(RoundedCornerShape(12.dp)),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        setBackgroundColor(androidx.compose.ui.graphics.Color.Black.toArgb())
                        keepScreenOn = true
                        host = this
                    }
                },
            )
        }

        HCard {
            val err = state.error
            if (err != null) {
                Text("Render gagal", color = ErrorRed, fontWeight = FontWeight.SemiBold)
                Text(err, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = vm::leaveRenderError, modifier = Modifier.fillMaxWidth()) { Text("Kembali") }
            } else {
                Text(
                    when (state.phase) {
                        Phase.LOADING -> "Memuat project..."
                        Phase.RENDERING -> "Frame ${state.frame} / ${state.total}"
                        Phase.FINALIZING -> "Menyelesaikan file MP4..."
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MutedText("Progress $percent%")
                    MutedText("Waktu render ${formatClock(state.elapsedMs)}")
                }
                MutedText("Status: ${state.message} · ${w}×$h · ${settings.fps} FPS")
                OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Batalkan render?") },
            text = { Text("Progress akan hilang dan file sementara dibersihkan.") },
            confirmButton = { TextButton(onClick = { confirmCancel = false; vm.cancelRender() }) { Text("Batalkan") } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Lanjutkan") } },
            containerColor = SurfaceColor,
        )
    }
}

@Composable
fun ResultScreen(vm: AppViewModel, historyId: String) {
    val history by vm.history.collectAsStateWithLifecycle()
    val entry = history.firstOrNull { it.id == historyId }
    val context = LocalContext.current

    if (entry == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MutedText("Video tidak ditemukan.") }
        return
    }

    val file = remember(entry.id) { vm.renderFile(entry) }
    val holder = remember(entry.id) { arrayOfNulls<VideoView>(1) }
    var playing by remember(entry.id) { mutableStateOf(false) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) vm.copyToUri(entry, uri)
    }
    val frac = when {
        entry.width > entry.height -> 1f
        entry.width == entry.height -> 0.8f
        else -> 0.55f
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Video selesai", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth(frac)
                    .aspectRatio(entry.width.toFloat() / entry.height)
                    .clip(RoundedCornerShape(12.dp)),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(file.absolutePath)
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            seekTo(1)
                        }
                        holder[0] = this
                    }
                },
                onRelease = { it.stopPlayback(); holder[0] = null },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val v = holder[0] ?: return@Button
                    if (v.isPlaying) { v.pause(); playing = false } else { v.start(); playing = true }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text(if (playing) "Pause" else "Play") }
        }

        HCard {
            Text(entry.projectName, fontWeight = FontWeight.SemiBold)
            MutedText("${entry.width}×${entry.height} · ${entry.fps} FPS · ${entry.frames} frame · ${formatClock(entry.durationMs)}")
            MutedText("${formatBytes(entry.sizeBytes)} · waktu render ${formatClock(entry.renderMs)} · MP4 H.264 tanpa audio")
        }

        Button(
            onClick = { vm.saveToGallery(entry) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save to Gallery") }
        OutlinedButton(onClick = { saveAs.launch(vm.suggestedName(entry)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Simpan ke folder...")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { context.startActivity(Intent.createChooser(vm.shareIntent(entry), "Bagikan video")) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Share, null)
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
            OutlinedButton(onClick = { vm.renderAgain(entry) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text("Render Again")
            }
        }
    }
}
