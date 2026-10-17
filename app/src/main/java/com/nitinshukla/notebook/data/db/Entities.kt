package com.nitinshukla.notebook.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "notes",
    indices = [Index("isTrashed"), Index("isArchived"), Index("updatedAt"), Index("type")],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Plain content, or "enc:v1:<base64>" ciphertext when [isLocked]. */
    val content: String,
    val type: String,
    val color: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val trashedAt: Long? = null,
    val isLocked: Boolean = false,
    val sortOrder: Long = 0,
    val fileName: String? = null,
    val cursorPosition: Int = 0,
    @ColumnInfo(defaultValue = "1") val revisionsEnabled: Boolean = true,
)

@Entity(
    tableName = "checklist_items",
    foreignKeys = [ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId")],
)
data class ChecklistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val text: String,
    val isChecked: Boolean,
    val position: Int,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId")],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val type: String,
    val fileName: String,
    val mimeType: String,
    val displayName: String,
    val caption: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val createdAt: Long,
)

@Entity(
    tableName = "revisions",
    foreignKeys = [ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId"), Index("createdAt")],
)
data class RevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val title: String,
    val content: String,
    val createdAt: Long,
    val sizeBytes: Long,
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val title: String,
    val content: String,
    val type: String,
    val isBuiltIn: Boolean,
)

@Entity(tableName = "link_previews")
data class LinkPreviewEntity(
    @PrimaryKey val url: String,
    val title: String?,
    val description: String?,
    val fetchedAt: Long,
)

data class NoteWithRelations(
    @Embedded val note: NoteEntity,
    @Relation(parentColumn = "id", entityColumn = "noteId")
    val checklist: List<ChecklistItemEntity>,
    @Relation(parentColumn = "id", entityColumn = "noteId")
    val attachments: List<AttachmentEntity>,
)

/**
 * List projection: a content preview instead of the full text, and counts instead of child
 * rows. Keeps the home list query cheap however large the notes are.
 */
data class NoteSummaryRow(
    val id: Long,
    val title: String,
    val preview: String,
    val type: String,
    val color: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isTrashed: Boolean,
    val trashedAt: Long?,
    val isLocked: Boolean,
    val sortOrder: Long,
    val fileName: String?,
    val checklistTotal: Int,
    val checklistDone: Int,
    val checklistPreview: String?,
    val attachmentCount: Int,
)

/** Content of an unlocked note, for search snippets. */
data class NoteContentRow(val id: Long, val content: String)

/** Projection used for duplicate detection during import. */
data class NoteFingerprintRow(val id: Long, val title: String, val content: String, val createdAt: Long)

/** Revision without its body, for storage accounting. */
data class RevisionMetaRow(val id: Long, val noteId: Long, val createdAt: Long, val sizeBytes: Long)
