package io.github.thoitiet.emuse.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Status semantic colors, following Camera2Magit's StatusColors.
 * Fixed palette — does not drift with wallpaper.
 */
object StatusColors {

    val healthy: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalAppDarkMode.current) GreenDark else GreenLight

    val danger: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalAppDarkMode.current) RedDark else RedLight

    val warning: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalAppDarkMode.current) OrangeDark else OrangeLight

    private val GreenLight = Color(0xFF4CAF50)
    private val GreenDark = Color(0xFF81C784)
    private val RedLight = Color(0xFFE53935)
    private val RedDark = Color(0xFFEF5350)
    private val OrangeLight = Color(0xFFFF9800)
    private val OrangeDark = Color(0xFFFFB74D)
}
