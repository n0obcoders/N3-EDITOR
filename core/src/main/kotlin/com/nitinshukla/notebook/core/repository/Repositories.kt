package com.nitinshukla.notebook.core.repository

import com.nitinshukla.notebook.core.model.Attachment
import com.nitinshukla.notebook.core.model.ChecklistItem
import com.nitinshukla.notebook.core.model.LinkPreview
import com.nitinshukla.notebook.core.model.Note
import com.nitinshukla.notebook.core.model.NoteColor
import com.nitinshukla.notebook.core.model.NoteDetails
import com.nitinshukla.notebook.core.model.NoteSummary
import com.nitinshukla.notebook.core.model.Revision
import com.nitinshukla.notebook.core.model.Template
import com.nitinshukla.notebook.core.revision.RevisionRules
import com.nitinshukla.notebook.core.settings.AppSettings
import kotlinx.coroutines.flow.Flow

interface NoteRepository {
    /**
     * Lightweight rows for lists: no full content, no relations. This is what the home
     * screen, search and widgets observe, so a save in the editor only re-runs a cheap query.
     */
    fun observeSummaries(): Flow<List<NoteSummary>>

    /** Every note with content and relations. Used by backup and tests only. */
    fun observeAll(): Flow<List<NoteDetails>>

    /** One note with its content decrypted. Callers must have passed the lock check. */
    fun observe(id: Long): Flow<NoteDetails?>

    suspend fun get(id: Long): NoteDetails?

    suspend fun create(note: Note, checklist: List<ChecklistItem> = emptyList()): Long

    /**
     * Saves title, content and other fields. Bumps updatedAt unless [touch] is false.
     * Returns the updatedAt value that was persisted, so callers can show the real save time.
     */
    suspend fun update(note: Note, touch: Boolean = true): Long

    /** Changes only the title. Returns the persisted updatedAt. */
    suspend fun rename(id: Long, title: String): Long

    suspend fun saveCursor(id: Long, cursor: Int)

    suspend fun setPinned(ids: Collection<Long>, pinned: Boolean)
    suspend fun setArchived(ids: Collection<Long>, archived: Boolean)
    suspend fun setColor(ids: Collection<Long>, color: NoteColor)
    suspend fun setLocked(id: Long, locked: Boolean)
    suspend fun moveToTrash(ids: Collection<Long>)
    suspend fun restoreFromTrash(ids: Collection<Long>)

    /** Deletes notes with their checklist items, attachments and revisions. */
    suspend fun deletePermanently(ids: Collection<Long>)
    suspend fun emptyTrash()

    suspend fun duplicate(id: Long): Long
    suspend fun replaceChecklist(noteId: Long, items: List<ChecklistItem>)
    suspend fun updateManualOrder(idsInOrder: List<Long>)

    suspend fun addAttachment(attachment: Attachment): Long
    suspend fun updateAttachmentCaption(attachmentId: Long, caption: String)
    suspend fun removeAttachment(attachmentId: Long)

    suspend fun allForBackup(): List<NoteDetails>
}

interface RevisionRepository {
    fun observe(noteId: Long): Flow<List<Revision>>
    suspend fun latest(noteId: Long): Revision?
    suspend fun get(id: Long): Revision?
    suspend fun insert(revision: Revision): Long
    suspend fun delete(ids: Collection<Long>)
    suspend fun deleteAllFor(noteId: Long)
    suspend fun allForNote(noteId: Long): List<Revision>
    suspend fun allMetadata(): List<Revision>
}

interface TemplateRepository {
    fun observeAll(): Flow<List<Template>>
    suspend fun get(id: Long): Template?
    suspend fun upsert(template: Template): Long
    suspend fun delete(id: Long)
    suspend fun ensureDefaults()
}

interface LinkPreviewRepository {
    fun observe(url: String): Flow<LinkPreview?>
    suspend fun save(preview: LinkPreview)
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/** Takes revision snapshots according to the user's settings. */
class RevisionService(
    private val revisions: RevisionRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun snapshotIfNeeded(note: Note, settings: AppSettings, force: Boolean = false): Boolean {
        if (!settings.revisionsEnabled || !note.revisionsEnabled || note.id == 0L || note.isLocked) return false
        val policy = settings.revisionPolicy
        val latest = revisions.latest(note.id)
        val time = now()
        if (!RevisionRules.shouldSnapshot(latest, note.title, note.content, time, policy, force)) return false
        revisions.insert(Revision(noteId = note.id, title = note.title, content = note.content, createdAt = time))
        prune(note.id, settings)
        return true
    }

    suspend fun prune(noteId: Long, settings: AppSettings) {
        val policy = settings.revisionPolicy
        val excess = RevisionRules.excessForNote(revisions.allForNote(noteId), policy.maxPerNote)
        if (excess.isNotEmpty()) revisions.delete(excess)
        val overStorage = RevisionRules.excessForStorage(revisions.allMetadata(), policy.maxTotalBytes)
        if (overStorage.isNotEmpty()) revisions.delete(overStorage)
    }
}
