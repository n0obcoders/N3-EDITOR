package com.nitinshukla.notebook.data.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.nitinshukla.notebook.core.text.DecodedText
import com.nitinshukla.notebook.core.text.LineSeparator
import com.nitinshukla.notebook.core.text.TextCodec
import com.nitinshukla.notebook.core.text.TextEncoding
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** A text document read through the Storage Access Framework. */
data class LoadedDocument(
    val uri: Uri,
    val displayName: String,
    val decoded: DecodedText,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** One row of a folder listing from a document tree. */
data class TreeEntry(
    val uri: Uri,
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

class DocumentException(message: String) : Exception(message)

/**
 * All file access for the code editors and the PCAP reader goes through user-picked URIs:
 * single documents from ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT and folders from
 * ACTION_OPEN_DOCUMENT_TREE. No broad storage permission is ever requested, which is what
 * Android 13–16 expect and what keeps the app out of the "all files" review bucket.
 */
@Singleton
class DocumentFiles @Inject constructor(@ApplicationContext private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) { queryName(uri) }

    private fun queryName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')

    suspend fun read(uri: Uri, maxBytes: Long = MAX_DOCUMENT_BYTES): LoadedDocument = withContext(Dispatchers.IO) {
        val name = queryName(uri) ?: "document.txt"
        var size = -1L
        var modified = 0L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) size = c.getLong(0)
                    if (c.columnCount > 1 && !c.isNull(1)) modified = c.getLong(1)
                }
            }
        } catch (e: Exception) {
            // Not every provider answers metadata queries; the stream is what matters.
        }
        if (size > maxBytes) throw DocumentException("\"$name\" is larger than ${maxBytes / (1024 * 1024)} MB.")
        val stream = try {
            resolver.openInputStream(uri)
        } catch (e: FileNotFoundException) {
            null
        } ?: throw DocumentException("Could not open \"$name\". It may have been moved or deleted.")
        val bytes = stream.use { input -> readLimited(input, maxBytes, name) }
        val decoded = TextCodec.decode(bytes)
        if (decoded.looksBinary) throw DocumentException("\"$name\" looks like a binary file, not text.")
        LoadedDocument(uri, name, decoded, bytes.size.toLong(), modified)
    }

    private fun readLimited(input: java.io.InputStream, max: Long, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw DocumentException("\"$name\" is larger than ${max / (1024 * 1024)} MB.")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Writes [text] back in the file's original encoding and line separator. Truncates first. */
    suspend fun write(uri: Uri, text: String, encoding: TextEncoding = TextEncoding.UTF8, separator: LineSeparator = LineSeparator.LF) = withContext(Dispatchers.IO) {
        val bytes = TextCodec.encode(text, encoding, separator)
        val out = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            null
        } ?: throw DocumentException("Could not write to the chosen location.")
        out.use { it.write(bytes); it.flush() }
    }

    /** Takes a persistable permission so the document can be reopened after a restart. */
    fun persist(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Provider without persistable grants; the picker will be shown again next time.
        }
    }

    fun release(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Already released.
        }
    }

    fun hasPersistedPermission(uri: Uri): Boolean = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /** Last-modified time as the provider reports it, or null. */
    suspend fun lastModified(uri: Uri): Long? = withContext(Dispatchers.IO) {
        try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    // ---- Folders (ACTION_OPEN_DOCUMENT_TREE) --------------------------------------------------

    fun rootDocumentUri(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    /** Children of a directory, folders first, then files, both case-insensitively by name. */
    suspend fun listChildren(treeUri: Uri, parentDocumentId: String): List<TreeEntry> = withContext(Dispatchers.IO) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val out = ArrayList<TreeEntry>()
        try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val mime = c.getString(2) ?: ""
                    out += TreeEntry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        documentId = id,
                        name = c.getString(1) ?: id,
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        mimeType = mime,
                        sizeBytes = if (c.isNull(3)) 0 else c.getLong(3),
                        lastModified = if (c.isNull(4)) 0 else c.getLong(4),
                    )
                }
            }
        } catch (e: Exception) {
            throw DocumentException("Could not read the folder: ${e.message}")
        }
        out.sortedWith(compareBy<TreeEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    suspend fun createFile(parentDocumentUri: Uri, name: String, mimeType: String = "text/plain"): Uri = withContext(Dispatchers.IO) {
        DocumentsContract.createDocument(resolver, parentDocumentUri, mimeType, name) ?: throw DocumentException("Could not create \"$name\".")
    }

    suspend fun createDirectory(parentDocumentUri: Uri, name: String): Uri = withContext(Dispatchers.IO) {
        DocumentsContract.createDocument(resolver, parentDocumentUri, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: throw DocumentException("Could not create folder \"$name\".")
    }

    suspend fun rename(documentUri: Uri, newName: String): Uri = withContext(Dispatchers.IO) {
        try {
            DocumentsContract.renameDocument(resolver, documentUri, newName) ?: throw DocumentException("Could not rename to \"$newName\".")
        } catch (e: FileNotFoundException) {
            throw DocumentException("The file no longer exists.")
        }
    }

    suspend fun delete(documentUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            DocumentsContract.deleteDocument(resolver, documentUri)
        } catch (e: FileNotFoundException) {
            false
        }
    }

    companion object {
        /** Code editors load whole files; 32 MB of text is already several hundred MB of RAM once split into lines. */
        const val MAX_DOCUMENT_BYTES = 32L * 1024 * 1024
    }
}
