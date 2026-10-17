package com.nitinshukla.notebook.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    /**
     * The list projection. `substr` keeps the preview bounded, locked notes expose no text,
     * and the correlated counts use the noteId indexes. Observed tables: notes,
     * checklist_items, attachments.
     */
    @Query(
        """
        SELECT n.id, n.title,
               CASE WHEN n.isLocked THEN '' ELSE substr(n.content, 1, 400) END AS preview,
               n.type, n.color, n.createdAt, n.updatedAt, n.isPinned, n.isArchived, n.isTrashed, n.trashedAt,
               n.isLocked, n.sortOrder, n.fileName,
               (SELECT COUNT(*) FROM checklist_items c WHERE c.noteId = n.id) AS checklistTotal,
               (SELECT COUNT(*) FROM checklist_items c WHERE c.noteId = n.id AND c.isChecked = 1) AS checklistDone,
               CASE WHEN n.isLocked THEN '' ELSE
                 (SELECT group_concat(line, char(10)) FROM
                   (SELECT (CASE WHEN c.isChecked THEN '1' ELSE '0' END) || c.text AS line
                    FROM checklist_items c WHERE c.noteId = n.id ORDER BY c.position LIMIT 5))
               END AS checklistPreview,
               (SELECT COUNT(*) FROM attachments a WHERE a.noteId = n.id) AS attachmentCount
        FROM notes n
        """,
    )
    fun observeSummaries(): Flow<List<NoteSummaryRow>>

    @Transaction
    @Query("SELECT * FROM notes")
    fun observeAll(): Flow<List<NoteWithRelations>>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: Long): Flow<NoteWithRelations?>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getWithRelations(id: Long): NoteWithRelations?

    @Transaction
    @Query("SELECT * FROM notes")
    suspend fun getAllWithRelations(): List<NoteWithRelations>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE id IN (:ids)")
    suspend fun getMany(ids: Collection<Long>): List<NoteEntity>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    /** The editor's save path: only the columns typing can change, in one statement. */
    @Query("UPDATE notes SET title = :title, content = :content, cursorPosition = :cursor, updatedAt = :updatedAt, type = :type, revisionsEnabled = :revisionsEnabled, fileName = :fileName WHERE id = :id")
    suspend fun saveContent(id: Long, title: String, content: String, cursor: Int, updatedAt: Long, type: String, revisionsEnabled: Boolean, fileName: String?)

    @Query("UPDATE notes SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE notes SET cursorPosition = :cursor WHERE id = :id")
    suspend fun updateCursor(id: Long, cursor: Int)

    @Query("UPDATE notes SET isPinned = :pinned WHERE id IN (:ids)")
    suspend fun setPinned(ids: Collection<Long>, pinned: Boolean)

    /** Archiving unpins, as in Keep. */
    @Query("UPDATE notes SET isArchived = :archived, isPinned = CASE WHEN :archived THEN 0 ELSE isPinned END WHERE id IN (:ids)")
    suspend fun setArchived(ids: Collection<Long>, archived: Boolean)

    @Query("UPDATE notes SET color = :color WHERE id IN (:ids)")
    suspend fun setColor(ids: Collection<Long>, color: String)

    @Query("UPDATE notes SET isTrashed = 1, trashedAt = :now, isPinned = 0 WHERE id IN (:ids)")
    suspend fun moveToTrash(ids: Collection<Long>, now: Long)

    @Query("UPDATE notes SET isTrashed = 0, trashedAt = NULL WHERE id IN (:ids)")
    suspend fun restore(ids: Collection<Long>)

    @Query("DELETE FROM notes WHERE id IN (:ids)")
    suspend fun delete(ids: Collection<Long>)

    @Query("SELECT id FROM notes WHERE isTrashed = 1")
    suspend fun trashedIds(): List<Long>

    @Query("SELECT * FROM notes WHERE isTrashed = 1")
    suspend fun trashed(): List<NoteEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM notes")
    suspend fun maxSortOrder(): Long

    @Query("UPDATE notes SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Long)

    @Query("SELECT id, title, content, createdAt FROM notes")
    suspend fun fingerprints(): List<NoteFingerprintRow>

    @Query("SELECT COUNT(*) FROM notes WHERE isTrashed = 0")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(LENGTH(title) + LENGTH(content)), 0) * 2 FROM notes")
    suspend fun approximateTextBytes(): Long

    /**
     * Ids of unlocked notes that contain [pattern] (a LIKE pattern from NoteSearch.likePattern)
     * in their title, text, file name, checklist items or attachment captions. Called once per
     * search term; the caller intersects the results.
     */
    @Query(
        """
        SELECT id FROM notes WHERE isLocked = 0 AND (
          title LIKE :pattern ESCAPE '\' OR content LIKE :pattern ESCAPE '\' OR fileName LIKE :pattern ESCAPE '\'
          OR id IN (SELECT noteId FROM checklist_items WHERE text LIKE :pattern ESCAPE '\')
          OR id IN (SELECT noteId FROM attachments WHERE displayName LIKE :pattern ESCAPE '\' OR caption LIKE :pattern ESCAPE '\'))
        """,
    )
    suspend fun searchIds(pattern: String): List<Long>

    @Query("SELECT id, content FROM notes WHERE isLocked = 0 AND id IN (:ids)")
    suspend fun contents(ids: Collection<Long>): List<NoteContentRow>
}

@Dao
interface ChecklistDao {
    @Query("SELECT * FROM checklist_items WHERE noteId = :noteId ORDER BY position")
    suspend fun forNote(noteId: Long): List<ChecklistItemEntity>

    @Query("DELETE FROM checklist_items WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: Long)

    @Insert
    suspend fun insertAll(items: List<ChecklistItemEntity>)

    @Transaction
    suspend fun replace(noteId: Long, items: List<ChecklistItemEntity>) {
        deleteForNote(noteId)
        if (items.isNotEmpty()) insertAll(items)
    }
}

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachment: AttachmentEntity): Long

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: Long): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE noteId IN (:noteIds)")
    suspend fun forNotes(noteIds: Collection<Long>): List<AttachmentEntity>

    @Query("SELECT fileName FROM attachments")
    suspend fun allFileNames(): List<String>

    @Query("UPDATE attachments SET caption = :caption WHERE id = :id")
    suspend fun updateCaption(id: Long, caption: String)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RevisionDao {
    @Query("SELECT * FROM revisions WHERE noteId = :noteId ORDER BY createdAt DESC")
    fun observe(noteId: Long): Flow<List<RevisionEntity>>

    @Query("SELECT * FROM revisions WHERE noteId = :noteId ORDER BY createdAt DESC")
    suspend fun forNote(noteId: Long): List<RevisionEntity>

    @Query("SELECT * FROM revisions WHERE noteId = :noteId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latest(noteId: Long): RevisionEntity?

    @Query("SELECT * FROM revisions WHERE id = :id")
    suspend fun get(id: Long): RevisionEntity?

    @Query("SELECT id, noteId, createdAt, sizeBytes FROM revisions")
    suspend fun allMeta(): List<RevisionMetaRow>

    @Insert
    suspend fun insert(revision: RevisionEntity): Long

    @Query("DELETE FROM revisions WHERE id IN (:ids)")
    suspend fun delete(ids: Collection<Long>)

    @Query("DELETE FROM revisions WHERE noteId = :noteId")
    suspend fun deleteFor(noteId: Long)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM revisions")
    suspend fun totalBytes(): Long
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY isBuiltIn DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates")
    suspend fun getAll(): List<TemplateEntity>

    @Query("SELECT * FROM templates WHERE id = :id")
    suspend fun get(id: Long): TemplateEntity?

    @Query("SELECT COUNT(*) FROM templates")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(template: TemplateEntity): Long

    @Insert
    suspend fun insertAll(templates: List<TemplateEntity>)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LinkPreviewDao {
    @Query("SELECT * FROM link_previews WHERE url = :url")
    fun observe(url: String): Flow<LinkPreviewEntity?>

    @Upsert
    suspend fun upsert(preview: LinkPreviewEntity)
}
