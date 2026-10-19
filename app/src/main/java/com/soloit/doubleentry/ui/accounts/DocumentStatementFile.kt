package com.soloit.doubleentry.ui.accounts

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/** A statement document the user picked with the system file picker. */
class DocumentStatementFile(private val resolver: ContentResolver, private val uri: Uri) : StatementFile {
    override val name: String?
        get() = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    override fun open(): InputStream? = resolver.openInputStream(uri)
}
