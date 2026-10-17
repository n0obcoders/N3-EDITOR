package com.nitinshukla.notebook.core.model

/** The kinds of note Notebook can hold. Stored by [name], so never rename entries. */
enum class NoteType {
    PLAIN,
    RICH,
    CHECKLIST,
    IMAGE,
    VOICE,
    DRAWING,
    LINK,
    CODE;

    val isMonospaced: Boolean get() = this == CODE
}

/**
 * Note background colors. Each has a light-theme and dark-theme ARGB value chosen so that
 * the default on-surface text color keeps at least 4.5:1 contrast. [DEFAULT] means
 * "use the theme surface color". Stored by [name].
 */
enum class NoteColor(val lightArgb: Long, val darkArgb: Long, val displayName: String) {
    DEFAULT(0x00000000, 0x00000000, "Default"),
    WHITE(0xFFFFFFFF, 0xFF2D2E30, "White"),
    LIGHT_GRAY(0xFFE8EAED, 0xFF3C3F43, "Light gray"),
    SOFT_YELLOW(0xFFFFF4C2, 0xFF5B4B12, "Soft yellow"),
    LIGHT_GREEN(0xFFD9F2D0, 0xFF264D2A, "Light green"),
    LIGHT_BLUE(0xFFD3E8FD, 0xFF1F3F5C, "Light blue"),
    LAVENDER(0xFFE6DDF8, 0xFF3E3260, "Lavender"),
    LIGHT_PINK(0xFFFADDE6, 0xFF5C2B3B, "Light pink"),
    PEACH(0xFFFDE2CC, 0xFF5E3A1E, "Peach"),
    SLATE(0xFF4A5059, 0xFF2A2F36, "Slate"),
    CHARCOAL(0xFF2F3134, 0xFF1C1D1F, "Charcoal");

    /** True when this color is dark in the light theme, so text must be light. */
    val isDarkInLightTheme: Boolean get() = this == SLATE || this == CHARCOAL

    companion object {
        fun fromName(name: String?): NoteColor = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

data class Note(
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val type: NoteType = NoteType.PLAIN,
    val color: NoteColor = NoteColor.DEFAULT,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val trashedAt: Long? = null,
    val isLocked: Boolean = false,
    val sortOrder: Long = 0,
    /** Name of the file the note was opened from or last saved to, if any. */
    val fileName: String? = null,
    val cursorPosition: Int = 0,
    val revisionsEnabled: Boolean = true,
) {
    val isBlank: Boolean get() = title.isBlank() && content.isBlank()
}

data class ChecklistItem(
    val id: Long = 0,
    val noteId: Long = 0,
    val text: String = "",
    val isChecked: Boolean = false,
    val position: Int = 0,
)

enum class AttachmentType { IMAGE, AUDIO, DRAWING, FILE }

data class Attachment(
    val id: Long = 0,
    val noteId: Long = 0,
    val type: AttachmentType,
    /** File name inside the app's private attachments directory. */
    val fileName: String,
    val mimeType: String,
    val displayName: String = "",
    val caption: String = "",
    val sizeBytes: Long = 0,
    val durationMs: Long? = null,
    val createdAt: Long = 0,
)

data class Revision(
    val id: Long = 0,
    val noteId: Long,
    val title: String,
    val content: String,
    val createdAt: Long,
    /** Approximate storage used, in bytes (UTF-16). */
    val sizeBytes: Long = (title.length + content.length).toLong() * 2,
)

data class Template(
    val id: Long = 0,
    val name: String,
    val title: String = "",
    val content: String = "",
    val type: NoteType = NoteType.PLAIN,
    val isBuiltIn: Boolean = false,
)

/** A note with everything attached to it. */
data class NoteDetails(
    val note: Note,
    val checklist: List<ChecklistItem> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
) {
    val id: Long get() = note.id
    val hasAttachments: Boolean get() = attachments.isNotEmpty()
}

/** Cached, user-requested preview of a web link. */
data class LinkPreview(
    val url: String,
    val title: String?,
    val description: String?,
    val fetchedAt: Long,
)

/**
 * What a list needs to draw a note card: no full content, no relations. [preview] is the
 * first few hundred characters of the content (empty for locked notes), and
 * [checklistTotal] / [checklistDone] replace loading every checklist item.
 */
data class NoteSummary(
    val id: Long,
    val title: String,
    val preview: String,
    val type: NoteType,
    val color: NoteColor,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isTrashed: Boolean,
    val trashedAt: Long? = null,
    val isLocked: Boolean,
    val sortOrder: Long,
    val fileName: String? = null,
    val checklistTotal: Int = 0,
    val checklistDone: Int = 0,
    /** Up to [CHECKLIST_PREVIEW_ITEMS] items, one per line, each prefixed with '1' (checked) or '0'. */
    val checklistPreview: String = "",
    val attachmentCount: Int = 0,
) {
    val hasAttachments: Boolean get() = attachmentCount > 0

    /** Parsed [checklistPreview]: text to checked. */
    val checklistItems: List<Pair<String, Boolean>>
        get() = if (checklistPreview.isEmpty()) emptyList() else checklistPreview.split('\n').map { it.drop(1) to it.startsWith("1") }

    companion object {
        /** How much content a summary carries. Enough for an eight-line card preview. */
        const val PREVIEW_CHARS = 400
        const val CHECKLIST_PREVIEW_ITEMS = 5
    }
}
