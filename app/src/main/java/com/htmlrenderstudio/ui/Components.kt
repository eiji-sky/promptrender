package com.htmlrenderstudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.htmlrenderstudio.data.AspectRatio
import com.htmlrenderstudio.data.RenderSettings
import com.htmlrenderstudio.data.Resolution
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .background(SurfaceColor)
    Column(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = modifier)
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

/** Editor pengaturan render: resolusi, FPS, rasio, durasi. Dipakai di halaman project dan Settings. */
@Composable
fun RenderSettingsEditor(settings: RenderSettings, onChange: (RenderSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MutedText("Resolusi")
        ChipRow(Resolution.entries, settings.resolution, { it.label }) { onChange(settings.copy(resolution = it)) }
        MutedText("Frame rate")
        ChipRow(listOf(24, 30, 60), settings.fps, { "$it FPS" }) { onChange(settings.copy(fps = it)) }
        MutedText("Rasio")
        ChipRow(AspectRatio.entries, settings.ratio, { it.label }) { onChange(settings.copy(ratio = it)) }
        MutedText("Durasi")
        ChipRow(listOf(true, false), settings.autoDuration, { if (it) "Auto detect" else "Custom" }) {
            onChange(settings.copy(autoDuration = it))
        }
        if (!settings.autoDuration) {
            var text by remember(settings.customSeconds) {
                mutableStateOf(
                    if (settings.customSeconds % 1f == 0f) settings.customSeconds.toInt().toString()
                    else settings.customSeconds.toString()
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v
                    v.replace(',', '.').toFloatOrNull()?.let { onChange(settings.copy(customSeconds = it)) }
                },
                label = { Text("Durasi (detik)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        MutedText("Output: MP4 · H.264 · tanpa audio (audio belum didukung)")
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(Locale.US, b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(Locale.US, b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(Locale.US, b / 1024.0)
    else -> "$b B"
}

fun formatClock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatDate(ms: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id", "ID")).format(Date(ms))
