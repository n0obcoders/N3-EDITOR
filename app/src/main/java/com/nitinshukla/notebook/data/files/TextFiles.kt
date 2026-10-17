package com.nitinshukla.notebook.data.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nitinshukla.notebook.core.backup.TextImport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class OpenedTextFile(val displayName: String, val text: String)

class TextFileException(message: String) : Exception(message)

/** Reads and writes text documents through URIs the user picked with the Storage Access Framework. */
@Singleton
class TextFiles @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    suspend fun read(uri: Uri): OpenedTextFile = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: uri.lastPathSegment ?: "document.txt"
        val mime = context.contentResolver.getType(uri)
        if (!TextImport.isSupported(name, mime)) throw TextFileException("\"$name\" is not a text file Notebook can open.")
        val input = context.contentResolver.openInputStream(uri) ?: throw TextFileException("Could not open \"$name\".")
        val bytes = input.use { it.readBytes() }
        if (bytes.size > TextImport.MAX_TEXT_BYTES) throw TextFileException("\"$name\" is larger than 5 MB.")
        OpenedTextFile(name, TextImport.normalize(String(bytes, Charsets.UTF_8)))
    }

    suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw TextFileException("Could not write to the chosen location.")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Takes a persistable permission so "Save" can write back later without a new picker. */
    fun persist(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Some providers do not offer persistable permissions; saving will prompt again.
        }
    }
}
