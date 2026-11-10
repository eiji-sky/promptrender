package com.htmlrenderstudio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF101114)
val SurfaceColor = Color(0xFF1B1D22)
val SurfaceHigh = Color(0xFF24272E)
val Accent = Color(0xFF7957FF)
val Muted = Color(0xFFA0A4AE)
val Warn = Color(0xFFFFB84D)
val Good = Color(0xFF58D68D)
val ErrorRed = Color(0xFFFF6B6B)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Bg,
    onBackground = Color.White,
    surface = SurfaceColor,
    onSurface = Color.White,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Muted,
    secondaryContainer = Color(0xFF2B2650),
    onSecondaryContainer = Color.White,
    error = ErrorRed,
    outline = Color(0xFF3A3D45),
)

@Composable
fun HrsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
