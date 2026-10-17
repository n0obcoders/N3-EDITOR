package com.nitinshukla.notebook.data.repository

import androidx.room.withTransaction
import com.nitinshukla.notebook.core.model.Attachment
import com.nitinshukla.notebook.core.model.ChecklistItem
import com.nitinshukla.notebook.core.model.Note
import com.nitinshukla.notebook.core.model.NoteColor
import com.nitinshukla.notebook.core.model.NoteDetails
import com.nitinshukla.notebook.core.model.NoteSummary
import com.nitinshukla.notebook.core.repository.NoteRepository
import com.nitinshukla.notebook.core.search.NoteSearch
import com.nitinshukla.notebook.core.util.Clock
import com.nitinshukla.notebook.data.db.NotebookDatabase
import com.nitinshukla.notebook.data.db.toEntity
import com.nitinshukla.notebook.data.db.toModel
import com.nitinshukla.notebook.data.files.AttachmentStore
import com.nitinshukla.notebook.security.NoteCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomNoteRepository @Inject constructor(
    private val db: NotebookDatabase,
    private val crypto: NoteCrypto,
    private val attachments: AttachmentStore,
    private val clock: Clock,
) : NoteRepository {
    private val notes get() = db.noteDao()
    private val checklist get() = db.checklistDao()
    private val attachmentDao get() = db.attachmentDao()

    override fun observeSummaries(): Flow<List<NoteSummary>> =
        notes.observeSummaries().map { rows -> rows.map { it.toModel() } }
            .distinctUntilChanged()
            .flowOn(kotlinx.coroutines.Dispatchers.Default)

    override fun observeAll(): Flow<List<NoteDetails>> =
        notes.observeAll().map { list -> list.map { it.toModel() } }

    override fun observe(id: Long): Flow<NoteDetails?> =
        // Mapping and decrypting ran on the collector's (main) thread after every autosave.
        notes.observe(id).map { it?.toModel(crypto::decrypt) }.flowOn(kotlinx.coroutines.Dispatchers.Default)

    override suspend fun get(id: Long): NoteDetails? = notes.getWithRelations(id)?.let { row ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { row.toModel(crypto::decrypt) }
    }

    override suspend fun create(note: Note, checklist: List<ChecklistItem>): Long = db.withTransaction {
        val now = clock.now()
        val entity = note.copy(
            createdAt = if (note.createdAt == 0L) now else note.createdAt,
            updatedAt = if (note.updatedAt == 0L) now else note.updatedAt,
            sortOrder = if (note.sortOrder == 0L) notes.maxSortOrder() + 1 else note.sortOrder,
        ).toEntity(storedContent = if (note.isLocked) crypto.encrypt(note.content) else note.content)
        val id = notes.insert(entity)
        if (checklist.isNotEmpty()) this.checklist.replace(id, checklist.mapIndexed { i, item -> item.copy(position = i).toEntity(id) })
        id
    }

    override suspend fun update(note: Note, touch: Boolean): Long {
        val stored = if (note.isLocked) crypto.encrypt(note.content) else note.content
        val updatedAt = if (touch) clock.now() else note.updatedAt
        notes.saveContent(
            id = note.id, title = note.title, content = stored, cursor = note.cursorPosition,
            updatedAt = updatedAt, type = note.type.name, revisionsEnabled = note.revisionsEnabled, fileName = note.fileName,
        )
        return updatedAt
    }

    override suspend fun rename(id: Long, title: String): Long {
        val now = clock.now()
        notes.rename(id, title.trim(), now)
        return now
    }

    override suspend fun saveCursor(id: Long, cursor: Int) = notes.updateCursor(id, cursor)
    override suspend fun setPinned(ids: Collection<Long>, pinned: Boolean) = notes.setPinned(ids, pinned)
    override suspend fun setArchived(ids: Collection<Long>, archived: Boolean) = notes.setArchived(ids, archived)
    override suspend fun setColor(ids: Collection<Long>, color: NoteColor) = notes.setColor(ids, color.name)

    override suspend fun setLocked(id: Long, locked: Boolean) = db.withTransaction {
        val entity = notes.get(id) ?: return@withTransaction
        if (entity.isLocked == locked) return@withTransaction
        val plain = if (entity.isLocked) crypto.decrypt(entity.content) ?: "" else entity.content
        notes.update(entity.copy(isLocked = locked, content = if (locked) crypto.encrypt(plain) else plain))
    }

    override suspend fun moveToTrash(ids: Collection<Long>) = notes.moveToTrash(ids, clock.now())
    override suspend fun restoreFromTrash(ids: Collection<Long>) = notes.restore(ids)

    override suspend fun deletePermanently(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val files = attachmentDao.forNotes(ids).map { it.fileName }
        // Child rows cascade through foreign keys; files are cleaned up after the commit.
        notes.delete(ids)
        files.forEach { attachments.delete(it) }
    }

    override suspend fun emptyTrash() = deletePermanently(notes.trashedIds())

    override suspend fun duplicate(id: Long): Long = db.withTransaction {
        val source = notes.getWithRelations(id) ?: return@withTransaction 0L
        val details = source.toModel(crypto::decrypt)
        val now = clock.now()
        val copy = details.note.copy(
            id = 0, title = details.note.title.let { if (it.isBlank()) it else "$it (copy)" },
            createdAt = now, updatedAt = now, isPinned = false, fileName = null,
        )
        val newId = create(copy, details.checklist.map { it.copy(id = 0) })
        details.attachments.forEach { a ->
            val src = attachments.file(a.fileName)
            if (src.exists()) {
                val copied = attachments.copy(src.inputStream(), a.fileName.substringAfterLast('.', "bin"))
                if (copied != null) attachmentDao.insert(a.copy(id = 0, noteId = newId, fileName = copied.first, createdAt = now).toEntity())
            }
        }
        newId
    }

    override suspend fun replaceChecklist(noteId: Long, items: List<ChecklistItem>) =
        checklist.replace(noteId, items.mapIndexed { i, item -> item.copy(position = i).toEntity(noteId) })

    override suspend fun updateManualOrder(idsInOrder: List<Long>) = db.withTransaction {
        idsInOrder.forEachIndexed { index, id -> notes.setSortOrder(id, index.toLong() + 1) }
    }

    override suspend fun addAttachment(attachment: Attachment): Long =
        attachmentDao.insert(attachment.copy(createdAt = if (attachment.createdAt == 0L) clock.now() else attachment.createdAt).toEntity())

    override suspend fun updateAttachmentCaption(attachmentId: Long, caption: String) = attachmentDao.updateCaption(attachmentId, caption)

    override suspend fun removeAttachment(attachmentId: Long) {
        val entity = attachmentDao.get(attachmentId) ?: return
        attachmentDao.delete(attachmentId)
        attachments.delete(entity.fileName)
    }

    override suspend fun allForBackup(): List<NoteDetails> = notes.getAllWithRelations().map { it.toModel(crypto::decrypt) }

    /**
     * Ids of unlocked notes whose full text, checklist or attachments contain every term of
     * [query]. The database does the scanning, so the UI never loads every note's content.
     * Returns null for an empty query (meaning "no full-text restriction").
     */
    suspend fun searchIds(query: String): Set<Long>? {
        val terms = NoteSearch.tokenize(query)
        if (terms.isEmpty()) return null
        var result: Set<Long>? = null
        for (term in terms) {
            val ids = notes.searchIds(NoteSearch.likePattern(term)).toSet()
            result = if (result == null) ids else result intersect ids
            if (result.isEmpty()) break
        }
        return result ?: emptySet()
    }

    /** Full content of unlocked notes, for search snippets. */
    suspend fun contents(ids: Collection<Long>): Map<Long, String> =
        if (ids.isEmpty()) emptyMap() else notes.contents(ids).associate { it.id to it.content }

    suspend fun approximateTextBytes(): Long = notes.approximateTextBytes()
    fun observeCount(): Flow<Int> = notes.observeCount()
    suspend fun fingerprints() = notes.fingerprints()
    suspend fun trashedNotes(): List<Note> = notes.trashed().map { it.toModel() }
}
