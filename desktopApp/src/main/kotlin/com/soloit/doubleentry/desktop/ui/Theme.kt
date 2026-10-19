package com.soloit.doubleentry.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.soloit.doubleentry.desktop.platform.SystemAccent
import io.github.composefluent.Colors
import io.github.composefluent.FluentTheme

/** Fluent in light or dark, with the system's accent color (DoubleEntry green where there is none). */
@Composable
fun DesktopTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = remember(dark) { Colors(SystemAccent.shades, dark) }
    FluentTheme(colors = colors, content = content)
}

/** Colors for money: in and out, and the app's window background. */
object AppColors {
    val positive: Color
        @Composable @ReadOnlyComposable
        get() = if (FluentTheme.colors.darkMode) Color(0xFF6CCB5F) else Color(0xFF0F7B0F)

    val negative: Color
        @Composable @ReadOnlyComposable
        get() = if (FluentTheme.colors.darkMode) Color(0xFFFF99A4) else Color(0xFFC42B1C)

    /** Behind the navigation and the panes; the Windows title bar takes the same color. */
    val window: Color
        @Composable @ReadOnlyComposable
        get() = FluentTheme.colors.background.mica.base
}
