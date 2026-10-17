package com.nitinshukla.notebook.data.db

import com.nitinshukla.notebook.core.model.Attachment
import com.nitinshukla.notebook.core.model.AttachmentType
import com.nitinshukla.notebook.core.model.ChecklistItem
import com.nitinshukla.notebook.core.model.LinkPreview
import com.nitinshukla.notebook.core.model.Note
import com.nitinshukla.notebook.core.model.NoteColor
import com.nitinshukla.notebook.core.model.NoteDetails
import com.nitinshukla.notebook.core.model.NoteSummary
import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.model.Revision
import com.nitinshukla.notebook.core.model.Template

internal inline fun <reified E : Enum<E>> enumOrDefault(name: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

fun NoteEntity.toModel(decryptedContent: String = content) = Note(
    id = id, title = title, content = decryptedContent,
    type = enumOrDefault(type, NoteType.PLAIN), color = NoteColor.fromName(color),
    createdAt = createdAt, updatedAt = updatedAt, isPinned = isPinned, isArchived = isArchived,
    isTrashed = isTrashed, trashedAt = trashedAt, isLocked = isLocked,
    sortOrder = sortOrder, fileName = fileName, cursorPosition = cursorPosition,
    revisionsEnabled = revisionsEnabled,
)

fun Note.toEntity(storedContent: String = content) = NoteEntity(
    id = id, title = title, content = storedContent, type = type.name, color = color.name,
    createdAt = createdAt, updatedAt = updatedAt, isPinned = isPinned, isArchived = isArchived,
    isTrashed = isTrashed, trashedAt = trashedAt, isLocked = isLocked,
    sortOrder = sortOrder, fileName = fileName, cursorPosition = cursorPosition,
    revisionsEnabled = revisionsEnabled,
)

fun NoteSummaryRow.toModel() = NoteSummary(
    id = id, title = title, preview = preview, type = enumOrDefault(type, NoteType.PLAIN),
    color = NoteColor.fromName(color), createdAt = createdAt, updatedAt = updatedAt,
    isPinned = isPinned, isArchived = isArchived, isTrashed = isTrashed, trashedAt = trashedAt,
    isLocked = isLocked, sortOrder = sortOrder, fileName = fileName,
    checklistTotal = checklistTotal, checklistDone = checklistDone,
    checklistPreview = checklistPreview.orEmpty(), attachmentCount = attachmentCount,
)

fun ChecklistItemEntity.toModel() = ChecklistItem(id, noteId, text, isChecked, position)
fun ChecklistItem.toEntity(noteId: Long = this.noteId) =
    ChecklistItemEntity(id = if (id < 0) 0 else id, noteId = noteId, text = text, isChecked = isChecked, position = position)

fun AttachmentEntity.toModel() = Attachment(
    id, noteId, enumOrDefault(type, AttachmentType.FILE), fileName, mimeType, displayName, caption, sizeBytes, durationMs, createdAt,
)

fun Attachment.toEntity() = AttachmentEntity(id, noteId, type.name, fileName, mimeType, displayName, caption, sizeBytes, durationMs, createdAt)

fun RevisionEntity.toModel() = Revision(id, noteId, title, content, createdAt, sizeBytes)
fun RevisionMetaRow.toModel() = Revision(id, noteId, "", "", createdAt, sizeBytes)
fun Revision.toEntity() = RevisionEntity(id, noteId, title, content, createdAt, sizeBytes)

fun TemplateEntity.toModel() = Template(id, name, title, content, enumOrDefault(type, NoteType.PLAIN), isBuiltIn)
fun Template.toEntity() = TemplateEntity(id, name, title, content, type.name, isBuiltIn)

fun LinkPreviewEntity.toModel() = LinkPreview(url, title, description, fetchedAt)
fun LinkPreview.toEntity() = LinkPreviewEntity(url, title, description, fetchedAt)

/** Locked notes come out with empty content unless [decrypt] is provided and succeeds. */
fun NoteWithRelations.toModel(decrypt: ((String) -> String?)? = null): NoteDetails {
    val content = when {
        !note.isLocked -> note.content
        decrypt != null -> decrypt(note.content) ?: ""
        else -> ""
    }
    return NoteDetails(
        note = note.toModel(content),
        checklist = if (note.isLocked && decrypt == null) emptyList() else checklist.sortedBy { it.position }.map { it.toModel() },
        attachments = attachments.map { it.toModel() },
    )
}
