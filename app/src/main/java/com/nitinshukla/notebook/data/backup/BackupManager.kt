package com.nitinshukla.notebook.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.nitinshukla.notebook.BuildConfig
import com.nitinshukla.notebook.core.backup.BackupAttachment
import com.nitinshukla.notebook.core.backup.BackupChecklistItem
import com.nitinshukla.notebook.core.backup.BackupCodec
import com.nitinshukla.notebook.core.backup.BackupException
import com.nitinshukla.notebook.core.backup.BackupFile
import com.nitinshukla.notebook.core.backup.BackupNote
import com.nitinshukla.notebook.core.backup.BackupRevision
import com.nitinshukla.notebook.core.backup.BackupTemplate
import com.nitinshukla.notebook.core.backup.DuplicateStrategy
import com.nitinshukla.notebook.core.backup.ImportPlanner
import com.nitinshukla.notebook.core.backup.LegacyData
import com.nitinshukla.notebook.core.backup.NoteFingerprint
import com.nitinshukla.notebook.core.model.NoteDetails
import com.nitinshukla.notebook.core.util.Clock
import com.nitinshukla.notebook.data.db.AttachmentEntity
import com.nitinshukla.notebook.data.db.ChecklistItemEntity
import com.nitinshukla.notebook.data.db.NoteEntity
import com.nitinshukla.notebook.data.db.NotebookDatabase
import com.nitinshukla.notebook.data.db.RevisionEntity
import com.nitinshukla.notebook.data.db.TemplateEntity
import com.nitinshukla.notebook.data.files.AttachmentStore
import com.nitinshukla.notebook.data.repository.RoomNoteRepository
import com.nitinshukla.notebook.security.NoteCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class RestoreResult(val inserted: Int, val replaced: Int, val skipped: Int, val attachmentsMissing: Int)

data class BackupInfo(val createdAt: Long, val noteCount: Int, val hasAttachments: Boolean, val appVersion: String, val formatVersion: Int)

/**
 * Creates and restores backups. JSON backups hold note data only; ZIP backups add the
 * attachment files. Both are written to a user-chosen SAF destination and never anywhere
 * else. Locked notes are exported decrypted, so backups should be stored somewhere safe.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: NotebookDatabase,
    private val notes: RoomNoteRepository,
    private val crypto: NoteCrypto,
    private val attachments: AttachmentStore,
    private val clock: Clock,
) {
    suspend fun buildBackup(noteIds: Collection<Long>? = null): BackupFile = withContext(Dispatchers.IO) {
        val all = notes.allForBackup().filter { noteIds == null || it.id in noteIds }
        BackupFile(
            createdAt = clock.now(),
            appVersion = BuildConfig.VERSION_NAME,
            notes = all.map { toBackupNote(it) },
            templates = if (noteIds == null) db.templateDao().getAll().filter { !it.isBuiltIn }.map { BackupTemplate(it.name, it.title, it.content, it.type) } else emptyList(),
        )
    }

    private suspend fun toBackupNote(d: NoteDetails): BackupNote {
        val n = d.note
        return BackupNote(
            id = n.id, title = n.title, content = n.content, type = n.type.name, color = n.color.name,
            createdAt = n.createdAt, updatedAt = n.updatedAt, pinned = n.isPinned, archived = n.isArchived,
            trashed = n.isTrashed, trashedAt = n.trashedAt, sortOrder = n.sortOrder,
            fileName = n.fileName,
            checklist = d.checklist.map { BackupChecklistItem(it.text, it.isChecked, it.position) },
            attachments = d.attachments.map { BackupAttachment(it.type.name, it.fileName, it.mimeType, it.displayName, it.caption, it.sizeBytes, it.durationMs, it.createdAt) },
            revisions = db.revisionDao().forNote(n.id).map { BackupRevision(it.title, it.content, it.createdAt) },
        )
    }

    suspend fun writeJson(target: Uri, noteIds: Collection<Long>? = null) = withContext(Dispatchers.IO) {
        val backup = buildBackup(noteIds)
        openOutput(target).use { it.write(BackupCodec.encode(backup).toByteArray(Charsets.UTF_8)) }
    }

    suspend fun writeZip(target: Uri, noteIds: Collection<Long>? = null) = withContext(Dispatchers.IO) {
        val backup = buildBackup(noteIds)
        ZipOutputStream(openOutput(target).buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFile.JSON_ENTRY))
            zip.write(BackupCodec.encode(backup).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            backup.notes.flatMap { it.attachments }.distinctBy { it.fileName }.forEach { a ->
                val file = attachments.file(a.fileName)
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry(BackupFile.ATTACHMENTS_DIR + a.fileName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Reads and validates a backup without changing anything, so the UI can confirm first. */
    suspend fun inspect(source: Uri): BackupInfo = withContext(Dispatchers.IO) {
        val backup = readBackup(source).first
        BackupInfo(backup.createdAt, backup.notes.size, backup.notes.any { it.attachments.isNotEmpty() }, backup.appVersion, backup.version)
    }

    suspend fun restore(source: Uri, strategy: DuplicateStrategy): RestoreResult = withContext(Dispatchers.IO) {
        val (backup, isZip) = readBackup(source)
        val fingerprints = notes.fingerprints().map { NoteFingerprint(it.id, it.title, it.content, it.createdAt) }
        val plan = ImportPlanner.plan(fingerprints, backup.notes, strategy)
        var missingAttachments = 0
        // Format-1 backups carried labels; they come back as hashtags in the note text.
        val labelNames = backup.labels.associate { it.id to it.name }
        val restoredNoteIds = mutableListOf<Long>()
        val pendingFiles = HashMap<String, String>() // backup name -> local name

        db.withTransaction {
            plan.toReplace.forEach { (_, localId) -> db.noteDao().delete(listOf(localId)) }
            (plan.toInsert + plan.toReplace.map { it.first }).forEach { bn ->
                val id = db.noteDao().insert(
                    NoteEntity(
                        title = bn.title, content = LegacyData.foldLabelsIntoContent(bn.content, bn.labelIds.mapNotNull { labelNames[it] }),
                        type = bn.type, color = bn.color,
                        createdAt = bn.createdAt, updatedAt = bn.updatedAt, isPinned = bn.pinned, isArchived = bn.archived,
                        isTrashed = bn.trashed, trashedAt = bn.trashedAt, sortOrder = bn.sortOrder,
                        fileName = bn.fileName,
                    ),
                )
                restoredNoteIds += id
                if (bn.checklist.isNotEmpty()) {
                    db.checklistDao().insertAll(bn.checklist.sortedBy { it.position }.mapIndexed { i, c -> ChecklistItemEntity(noteId = id, text = c.text, isChecked = c.checked, position = i) })
                }
                bn.revisions.forEach { r ->
                    db.revisionDao().insert(RevisionEntity(noteId = id, title = r.title, content = r.content, createdAt = r.createdAt, sizeBytes = (r.title.length + r.content.length) * 2L))
                }
                bn.attachments.forEach { a ->
                    if (!isZip) { missingAttachments++; return@forEach }
                    val localName = pendingFiles.getOrPut(a.fileName) { attachments.newFileName(a.fileName.substringAfterLast('.', "bin")) }
                    db.attachmentDao().insert(AttachmentEntity(noteId = id, type = a.type, fileName = localName, mimeType = a.mimeType, displayName = a.displayName, caption = a.caption, sizeBytes = a.sizeBytes, durationMs = a.durationMs, createdAt = a.createdAt))
                }
            }
            backup.templates.forEach { t -> db.templateDao().upsert(TemplateEntity(name = t.name, title = t.title, content = t.content, type = t.type, isBuiltIn = false)) }
        }
        if (isZip && pendingFiles.isNotEmpty()) missingAttachments += extractAttachments(source, pendingFiles)
        RestoreResult(plan.toInsert.size, plan.toReplace.size, plan.skipped, missingAttachments)
    }

    private fun readBackup(source: Uri): Pair<BackupFile, Boolean> {
        val stream = context.contentResolver.openInputStream(source) ?: throw BackupException(BackupException.Reason.NOT_JSON, "Could not open the file.")
        BufferedInputStream(stream, 8192).use { input ->
            input.mark(4)
            val head = ByteArray(4)
            val read = input.read(head)
            input.reset()
            val isZip = read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            if (!isZip) return BackupCodec.decode(readLimited(input)) to false
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == BackupFile.JSON_ENTRY) return BackupCodec.decode(readLimited(zip)) to true
                    entry = zip.nextEntry
                }
            }
            throw BackupException(BackupException.Reason.MISSING_ENTRY, "The ZIP file does not contain a Notebook backup.")
        }
    }

    private fun readLimited(input: InputStream): String {
        val bytes = input.readBytes()
        if (bytes.size > MAX_JSON_BYTES) throw BackupException(BackupException.Reason.TOO_LARGE, "The backup file is too large.")
        return String(bytes, Charsets.UTF_8)
    }

    /** Copies attachment entries into private storage. Returns the count of entries that were missing. */
    private fun extractAttachments(source: Uri, wanted: Map<String, String>): Int {
        val remaining = wanted.toMutableMap()
        context.contentResolver.openInputStream(source)?.let { stream ->
            ZipInputStream(BufferedInputStream(stream)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && remaining.isNotEmpty()) {
                    val name = entry.name.removePrefix(BackupFile.ATTACHMENTS_DIR)
                    if (entry.name.startsWith(BackupFile.ATTACHMENTS_DIR) && BackupCodec.isSafeEntryName(name)) {
                        remaining.remove(name)?.let { local ->
                            val target: File = attachments.file(local)
                            target.outputStream().use { out -> copyLimited(zip, out, AttachmentStore.MAX_ATTACHMENT_BYTES) }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return remaining.size
    }

    private fun copyLimited(input: InputStream, output: OutputStream, max: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw BackupException(BackupException.Reason.TOO_LARGE, "An attachment in the backup is too large.")
            output.write(buffer, 0, n)
        }
    }

    private fun openOutput(target: Uri): OutputStream =
        context.contentResolver.openOutputStream(target, "wt") ?: throw BackupException(BackupException.Reason.NOT_JSON, "Could not write to the chosen location.")

    companion object {
        const val MAX_JSON_BYTES = 200 * 1024 * 1024
        fun suggestedName(zip: Boolean, now: Long): String {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(now))
            return "notebook-backup-$stamp." + if (zip) "zip" else "json"
        }
    }
}
