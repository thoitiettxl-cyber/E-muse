package io.github.thoitiet.emuse.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Dark mode flag, provided at the composition root. */
val LocalAppDarkMode = staticCompositionLocalOf { false }

/**
 * E-Muse theme: Miuix (HyperOS style) with system dark mode detection.
 * Simplified from Camera2Magit — no custom accent colors, just system.
 */
@Composable
fun EmuseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalAppDarkMode provides darkTheme,
    ) {
        MiuixTheme(
            // MiuixTheme handles dark/light via its own colorSchemeMode;
            // we use the default which follows system.
            content = content,
        )
    }
}
