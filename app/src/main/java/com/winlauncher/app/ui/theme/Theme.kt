package com.winlauncher.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AccentTeal = Color(0xFF4FE0C4)
private val BackgroundDark = Color(0xFF121218)
private val SurfaceDark = Color(0xFF1C1C24)

private val LauncherColorScheme = darkColorScheme(
    primary = AccentTeal,
    secondary = AccentTeal,
    background = BackgroundDark,
    surface = SurfaceDark,
)

@Composable
fun WinLauncherTheme(content: @Composable () -> Unit) {
    // Launcher is intentionally always dark-themed, independent of system setting.
    MaterialTheme(
        colorScheme = LauncherColorScheme,
        content = content,
    )
}
