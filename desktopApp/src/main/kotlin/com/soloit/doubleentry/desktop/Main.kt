package com.soloit.doubleentry.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.soloit.doubleentry.data.ThemeMode
import com.soloit.doubleentry.desktop.ui.App
import com.soloit.doubleentry.desktop.ui.AppState
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.Section
import com.soloit.doubleentry.desktop.ui.WindowContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.Image
import java.awt.Dimension

fun main() {
    val container = DesktopContainer(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    container.start()
    val icon = BitmapPainter(Image.makeFromEncoded(DesktopContainer::class.java.getResource("/icon.png")!!.readBytes()).toComposeImageBitmap())

    application {
        val saved = container.window
        val windowState = rememberWindowState(
            placement = if (saved.getBoolean("maximized", false)) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = if (saved.getInt("x", Int.MIN_VALUE) == Int.MIN_VALUE) {
                WindowPosition.PlatformDefault
            } else {
                WindowPosition(saved.getInt("x", 0).dp, saved.getInt("y", 0).dp)
            },
            size = DpSize(saved.getInt("width", 1280).dp, saved.getInt("height", 800).dp),
        )

        fun saveBounds() = saved.edit {
            putBoolean("maximized", windowState.placement == WindowPlacement.Maximized)
            if (windowState.placement == WindowPlacement.Floating) {
                putInt("width", windowState.size.width.value.toInt())
                putInt("height", windowState.size.height.value.toInt())
                val position = windowState.position
                if (position is WindowPosition.Absolute) {
                    putInt("x", position.x.value.toInt())
                    putInt("y", position.y.value.toInt())
                }
            }
        }

        Window(
            onCloseRequest = { saveBounds(); exitApplication() },
            state = windowState,
            title = "DoubleEntry",
            icon = icon,
        ) {
            window.minimumSize = Dimension(960, 600)
            val state = remember { AppState(container, window) }
            val appearance by container.core.preferences.appearance.collectAsState()
            val dark = when (appearance.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // Realtime and periodic sync run while the window is open, not while it's minimized.
            LaunchedEffect(windowState) {
                snapshotFlow { windowState.isMinimized }.collect { minimized ->
                    if (minimized) {
                        container.core.sync.onBackground()
                    } else {
                        container.core.sync.onForeground()
                        container.core.onForeground()
                    }
                }
            }
            LaunchedEffect(Unit) {
                container.openInvoices.collect {
                    state.section = Section.Invoices
                    windowState.isMinimized = false
                    window.toFront()
                }
            }
            CompositionLocalProvider(LocalApp provides state) {
                WindowContent(window, dark) {
                    Box(Modifier.onPreviewKeyEvent(state::onKey)) { App(state) }
                }
            }
        }
    }
}
