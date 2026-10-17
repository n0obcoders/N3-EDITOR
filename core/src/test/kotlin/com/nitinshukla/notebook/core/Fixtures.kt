package com.nitinshukla.notebook.core

import com.nitinshukla.notebook.core.model.Attachment
import com.nitinshukla.notebook.core.model.AttachmentType
import com.nitinshukla.notebook.core.model.ChecklistItem
import com.nitinshukla.notebook.core.model.Note
import com.nitinshukla.notebook.core.model.NoteColor
import com.nitinshukla.notebook.core.model.NoteDetails
import com.nitinshukla.notebook.core.model.NoteSummary
import com.nitinshukla.notebook.core.model.NoteType

fun note(
    id: Long,
    title: String = "",
    content: String = "",
    created: Long = id * 1000,
    updated: Long = id * 1000,
    pinned: Boolean = false,
    archived: Boolean = false,
    trashed: Boolean = false,
    locked: Boolean = false,
    type: NoteType = NoteType.PLAIN,
    sortOrder: Long = 0,
    fileName: String? = null,
    checklist: List<String> = emptyList(),
    attachments: List<Attachment> = emptyList(),
) = NoteDetails(
    note = Note(
        id = id, title = title, content = content, createdAt = created, updatedAt = updated,
        isPinned = pinned, isArchived = archived, isTrashed = trashed,
        isLocked = locked, type = type, sortOrder = sortOrder, fileName = fileName,
    ),
    checklist = checklist.mapIndexed { i, t -> ChecklistItem(id = i + 1L, noteId = id, text = t, position = i) },
    attachments = attachments,
)

fun summary(
    id: Long,
    title: String = "",
    content: String = "",
    created: Long = id * 1000,
    updated: Long = id * 1000,
    pinned: Boolean = false,
    archived: Boolean = false,
    trashed: Boolean = false,
    locked: Boolean = false,
    type: NoteType = NoteType.PLAIN,
    sortOrder: Long = 0,
    fileName: String? = null,
    checklistTotal: Int = 0,
    attachmentCount: Int = 0,
) = NoteSummary(
    id = id, title = title, preview = if (locked) "" else content.take(NoteSummary.PREVIEW_CHARS), type = type,
    color = NoteColor.DEFAULT, createdAt = created, updatedAt = updated, isPinned = pinned, isArchived = archived,
    isTrashed = trashed, isLocked = locked, sortOrder = sortOrder, fileName = fileName,
    checklistTotal = checklistTotal, attachmentCount = attachmentCount,
)

fun attachment(name: String, caption: String = "") =
    Attachment(id = 1, noteId = 1, type = AttachmentType.IMAGE, fileName = "a.jpg", mimeType = "image/jpeg", displayName = name, caption = caption)
