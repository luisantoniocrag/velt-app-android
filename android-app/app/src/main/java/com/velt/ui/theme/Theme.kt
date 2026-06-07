package com.velt.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val VeltColorScheme = darkColorScheme(
    primary = Velt.Cyan,
    onPrimary = Velt.OnCyan,
    secondary = Velt.CyanLight,
    background = Velt.Bg,
    onBackground = Velt.T1,
    surface = Velt.Surf,
    onSurface = Velt.T1,
    surfaceVariant = Velt.Card,
    onSurfaceVariant = Velt.T2,
    outline = Velt.Border,
    error = Velt.Red
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Velt.Bg.toArgb()
            window.navigationBarColor = Velt.Bg.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = VeltColorScheme,
        typography = Typography,
        content = content
    )
}
