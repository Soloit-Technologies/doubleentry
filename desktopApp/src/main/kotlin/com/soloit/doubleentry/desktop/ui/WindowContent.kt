package com.soloit.doubleentry.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.soloit.doubleentry.desktop.platform.WindowChrome
import java.awt.Window

/** The theme and background every window of the app has, with a title bar to match. */
@Composable
fun WindowContent(window: Window, dark: Boolean, content: @Composable () -> Unit) {
    DesktopTheme(dark) {
        val background = AppColors.window
        LaunchedEffect(dark, background) { WindowChrome.apply(window, dark, background.toArgb()) }
        Box(Modifier.fillMaxSize().background(background)) { content() }
    }
}
