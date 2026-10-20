package com.soloit.doubleentry.desktop.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The operating system's own open and save dialogs. */
object FileDialogs {
    fun open(parent: Frame?, title: String, extensions: List<String>): File? {
        val dialog = FileDialog(parent, title, FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
            // Windows ignores the filter above; a wildcard pattern works there.
            if (isWindows) file = extensions.joinToString(";") { "*.$it" }
            isVisible = true
        }
        return dialog.file?.let { File(dialog.directory, it) }
    }

    fun save(parent: Frame?, title: String, suggestedName: String): File? {
        val dialog = FileDialog(parent, title, FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val name = dialog.file ?: return null
        val extension = suggestedName.substringAfterLast('.', "")
        val withExtension = if (extension.isNotEmpty() && !name.contains('.')) "$name.$extension" else name
        return File(dialog.directory, withExtension)
    }
}
