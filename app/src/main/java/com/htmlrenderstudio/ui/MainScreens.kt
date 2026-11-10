package com.htmlrenderstudio.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htmlrenderstudio.BuildConfig
import com.htmlrenderstudio.data.HistoryEntry
import com.htmlrenderstudio.data.Project
import com.htmlrenderstudio.importer.ZipLimits

private val ZIP_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

@Composable
fun HomeScreen(vm: AppViewModel) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val imp by vm.importState.collectAsStateWithLifecycle()
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importZip(uri)
    }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.PlayArrow, null, tint = Color.White) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("HTML Render Studio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    MutedText("HTML · CSS · JS → MP4, langsung di HP")
                }
            }
        }
        item {
            HCard {
                SectionTitle("Import project")
                MutedText("Pilih file ZIP berisi project HTML (maks ${ZipLimits.MAX_ZIP_BYTES / (1024 * 1024)} MB).")
                Button(
                    onClick = { picker.launch(ZIP_MIME_TYPES) },
                    enabled = !imp.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import Project")
                }
                OutlinedButton(onClick = { vm.importSample() }, enabled = !imp.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Coba project contoh (10 detik)")
                }
                if (imp.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        MutedText(imp.message)
                    }
                }
                imp.error?.let {
                    Text(it, color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::dismissImportError) { Text("Tutup") }
                }
            }
        }
        item { SectionTitle("Project terakhir") }
        if (projects.isEmpty()) {
            item { MutedText("Belum ada project. Import ZIP atau coba project contoh.") }
        } else {
            items(projects.take(3), key = { "p" + it.id }) { p -> ProjectRow(p, onOpen = { vm.openProject(p.id) }, onDelete = null) }
        }
        item { SectionTitle("Riwayat render") }
        if (history.isEmpty()) {
            item { MutedText("Belum ada video yang dirender.") }
        } else {
            items(history.take(3), key = { "h" + it.id }) { e -> HistoryRow(e, onOpen = { vm.openResult(e.id) }, onDelete = null) }
        }
        item {
            MutedText("Versi ${BuildConfig.VERSION_NAME}")
        }
    }

    pending?.let { draft ->
        var selected by remember(draft.project.id) { mutableStateOf(draft.project.entry) }
        AlertDialog(
            onDismissRequest = vm::cancelPending,
            title = { Text("Pilih file HTML utama") },
            text = {
                LazyColumn {
                    items(draft.htmlFiles, key = { it }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = f },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == f, onClick = { selected = f })
                            Text(f, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmPending(selected) }) { Text("Pakai") } },
            dismissButton = { TextButton(onClick = vm::cancelPending) { Text("Batal") } },
            containerColor = SurfaceColor,
        )
    }
}

@Composable
private fun ProjectRow(p: Project, onOpen: () -> Unit, onDelete: (() -> Unit)?) {
    HCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold)
                MutedText("${p.entry} · ${p.fileCount} file · ${formatBytes(p.extractedBytes)}")
                MutedText("Diimpor ${formatDate(p.importedAt)}")
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Hapus", tint = Muted) }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, onOpen: () -> Unit, onDelete: (() -> Unit)?) {
    HCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.projectName, fontWeight = FontWeight.SemiBold)
                MutedText("${e.width}×${e.height} · ${e.fps} FPS · ${formatClock(e.durationMs)} · ${formatBytes(e.sizeBytes)}")
                MutedText("${formatDate(e.createdAt)} · render ${formatClock(e.renderMs)}")
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Hapus", tint = Muted) }
            }
        }
    }
}

@Composable
fun ProjectsScreen(vm: AppViewModel) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Project?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { Text("Projects", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (projects.isEmpty()) item { MutedText("Belum ada project. Import dari tab Home.") }
        items(projects, key = { it.id }) { p ->
            ProjectRow(p, onOpen = { vm.openProject(p.id) }, onDelete = { toDelete = p })
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Hapus project?") },
            text = { Text("\"${p.name}\" dan semua filenya akan dihapus dari perangkat.") },
            confirmButton = { TextButton(onClick = { vm.deleteProject(p.id); toDelete = null }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Batal") } },
            containerColor = SurfaceColor,
        )
    }
}

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<HistoryEntry?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (history.isEmpty()) item { MutedText("Belum ada video yang dirender.") }
        items(history, key = { it.id }) { e ->
            HistoryRow(e, onOpen = { vm.openResult(e.id) }, onDelete = { toDelete = e })
        }
    }

    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Hapus video?") },
            text = { Text("File MP4 ini akan dihapus dari penyimpanan aplikasi (salinan di galeri tidak ikut terhapus).") },
            confirmButton = { TextButton(onClick = { vm.deleteHistory(e.id); toDelete = null }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Batal") } },
            containerColor = SurfaceColor,
        )
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tempSize by remember { mutableStateOf(vm.tempBytes()) }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            HCard {
                SectionTitle("Pengaturan render bawaan")
                RenderSettingsEditor(settings, vm::updateSettings)
            }
        }
        item {
            HCard {
                SectionTitle("Penyimpanan")
                MutedText("File sementara render: ${formatBytes(tempSize)} (dibersihkan otomatis saat aplikasi dibuka dan saat render dibatalkan).")
                OutlinedButton(onClick = { vm.clearTemp(); tempSize = 0 }) { Text("Hapus file sementara") }
            }
        }
        item {
            HCard {
                SectionTitle("Tentang")
                MutedText("HTML Render Studio ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                MutedText("Rendering 100% lokal: WebView + MediaCodec (H.264) + MediaMuxer (MP4). Aplikasi tidak punya izin internet; project HTML tidak bisa mengakses jaringan.")
                MutedText("Batasan: audio belum didukung. Render hanya berjalan saat aplikasi terbuka di layar. Resolusi/FPS tinggi belum tentu didukung semua perangkat. Animasi JS paling akurat bila project menyediakan window.renderFrame(timeMs).")
            }
        }
    }
}
