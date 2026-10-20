package com.soloit.doubleentry.desktop.platform

import androidx.compose.ui.graphics.Color
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import io.github.composefluent.Shades

/** The user's accent color as Windows shades it, so the app's highlights match the rest of the system. */
object SystemAccent {
    /** DoubleEntry green, where the system has no accent to follow. */
    val brand = Shades(
        base = Color(0xFF0E8A71),
        light1 = Color(0xFF19A88A),
        light2 = Color(0xFF5ECFB3),
        light3 = Color(0xFF9AE8D4),
        dark1 = Color(0xFF0B6F5B),
        dark2 = Color(0xFF075247),
        dark3 = Color(0xFF043A31),
    )

    val shades: Shades by lazy { windowsPalette() ?: brand }

    /**
     * `AccentPalette` holds eight RGBA colors, lightest first: light 3, 2, 1, the accent, dark 1,
     * 2, 3 and one unused.
     */
    private fun windowsPalette(): Shades? {
        if (!isWindows) return null
        return runCatching {
            val bytes = Advapi32Util.registryGetBinaryValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Accent", "AccentPalette")
            fun color(i: Int) = Color(bytes[i * 4].toInt() and 0xFF, bytes[i * 4 + 1].toInt() and 0xFF, bytes[i * 4 + 2].toInt() and 0xFF)
            Shades(base = color(3), light1 = color(2), light2 = color(1), light3 = color(0), dark1 = color(4), dark2 = color(5), dark3 = color(6))
        }.getOrNull()
    }
}
