package com.nitinshukla.notebook.data.files

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the private directory where attachment files live. Names are opaque and never user supplied. */
@Singleton
class AttachmentStore @Inject constructor(@ApplicationContext private val context: Context) {
    val directory: File get() = File(context.filesDir, "attachments").apply { mkdirs() }

    fun newFileName(extension: String): String =
        UUID.randomUUID().toString().replace("-", "") + "." + extension.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "bin" }

    fun file(fileName: String): File = File(directory, fileName)

    /** Content URI for sharing or viewing through other apps. */
    fun shareUri(fileName: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file(fileName))

    suspend fun importFrom(uri: Uri, extension: String, maxBytes: Long = MAX_ATTACHMENT_BYTES): Pair<String, Long>? = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        input.use { copy(it, extension, maxBytes) }
    }

    suspend fun copy(input: InputStream, extension: String, maxBytes: Long = MAX_ATTACHMENT_BYTES): Pair<String, Long>? = withContext(Dispatchers.IO) {
        val name = newFileName(extension)
        val target = file(name)
        var total = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    target.delete()
                    return@withContext null
                }
                out.write(buffer, 0, read)
            }
        }
        name to total
    }

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) { file(fileName).delete() }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) { directory.listFiles()?.sumOf { it.length() } ?: 0L }

    /** Removes files no attachment row refers to any more. */
    suspend fun deleteOrphans(referenced: Set<String>) = withContext(Dispatchers.IO) {
        directory.listFiles()?.filter { it.name !in referenced }?.forEach { it.delete() }
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
    }
}
