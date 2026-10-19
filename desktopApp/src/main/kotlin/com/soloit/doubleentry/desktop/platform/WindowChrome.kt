package com.soloit.doubleentry.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.Window

/**
 * Windows 11 title bar: dark or light with the app, in the app's background color, so the
 * native title bar (with its snap layouts and system buttons) reads as part of the window.
 * Does nothing on other systems or older Windows versions.
 */
object WindowChrome {
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35

    @Suppress("FunctionName")
    private interface Dwmapi : Library {
        fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: Pointer, size: Int): Int
    }

    private val dwm: Dwmapi? by lazy { if (isWindows) runCatching { Native.load("dwmapi", Dwmapi::class.java) }.getOrNull() else null }

    /** [argb] is the window background, as Compose's Color.toArgb(). */
    fun apply(window: Window, dark: Boolean, argb: Int) {
        val dwm = dwm ?: return
        runCatching {
            val hwnd = HWND(Native.getComponentPointer(window))
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, int(if (dark) 1 else 0), 4)
            // COLORREF is 0x00BBGGRR.
            val r = argb shr 16 and 0xFF
            val g = argb shr 8 and 0xFF
            val b = argb and 0xFF
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, int((b shl 16) or (g shl 8) or r), 4)
        }
    }

    private fun int(value: Int) = Memory(4).apply { setInt(0, value) }
}
