package com.soloit.doubleentry.desktop.platform

import java.io.File

/**
 * Where the app keeps its data: `%LOCALAPPDATA%\DoubleEntry` on Windows, `~/Library/Application
 * Support/DoubleEntry` on macOS, `$XDG_DATA_HOME/doubleentry` elsewhere. `-Ddoubleentry.dataDir`
 * overrides it (development, tests).
 */
object AppDirectories {
    val data: File by lazy {
        val override = System.getProperty("doubleentry.dataDir")?.takeIf { it.isNotBlank() }
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val dir = when {
            override != null -> File(override)
            os.startsWith("windows") -> File(System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local", "DoubleEntry")
            os.startsWith("mac") -> File(home, "Library/Application Support/DoubleEntry")
            else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "doubleentry")
        }
        dir.apply { mkdirs() }
    }

    val databases: File by lazy { File(data, "databases").apply { mkdirs() } }
    val preferences: File by lazy { File(data, "preferences").apply { mkdirs() } }
}

val isWindows: Boolean = System.getProperty("os.name").lowercase().startsWith("windows")
