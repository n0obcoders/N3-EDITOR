package com.nitinshukla.notebook.core.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** On-disk JSON backup format. Bump [CURRENT_VERSION] and keep older readers when it changes. */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = CURRENT_VERSION,
    val createdAt: Long,
    val appVersion: String = "",
    val notes: List<BackupNote> = emptyList(),
    /** Written by Notebook 1.0 backups. Read for compatibility; label names are folded into note text on restore. */
    val labels: List<BackupLabel> = emptyList(),
    val templates: List<BackupTemplate> = emptyList(),
) {
    companion object {
        const val FORMAT = "notebook-backup"
        /** Format 2 drops labels, reminders, favorites and daily notes. Format 1 files still restore. */
        const val CURRENT_VERSION = 2
        const val JSON_ENTRY = "notebook.json"
        const val ATTACHMENTS_DIR = "attachments/"
    }
}

@Serializable
data class BackupNote(
    val id: Long,
    val title: String = "",
    val content: String = "",
    val type: String = "PLAIN",
    val color: String = "DEFAULT",
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val trashed: Boolean = false,
    val trashedAt: Long? = null,
    /** Format 1 only; ignored on restore. */
    val favorite: Boolean = false,
    val sortOrder: Long = 0,
    val fileName: String? = null,
    /** Format 1 only; a former daily note restores as a normal note. */
    val dailyDate: String? = null,
    /** Format 1 only; resolved against [BackupFile.labels] and appended to the content as tags. */
    val labelIds: List<Long> = emptyList(),
    val checklist: List<BackupChecklistItem> = emptyList(),
    val attachments: List<BackupAttachment> = emptyList(),
    /** Format 1 only; reminders are not restored. */
    val reminder: BackupReminder? = null,
    val revisions: List<BackupRevision> = emptyList(),
)

@Serializable
data class BackupChecklistItem(val text: String, val checked: Boolean = false, val position: Int = 0)

@Serializable
data class BackupLabel(val id: Long, val name: String, val color: String = "DEFAULT")

@Serializable
data class BackupAttachment(
    val type: String,
    /** Entry name inside the ZIP's attachments/ folder. Absent from JSON-only backups. */
    @SerialName("file") val fileName: String,
    val mimeType: String,
    val displayName: String = "",
    val caption: String = "",
    val sizeBytes: Long = 0,
    val durationMs: Long? = null,
    val createdAt: Long = 0,
)

@Serializable
data class BackupReminder(val triggerAt: Long, val repeat: String = "NONE", val completed: Boolean = false)

@Serializable
data class BackupRevision(val title: String, val content: String, val createdAt: Long)

@Serializable
data class BackupTemplate(val name: String, val title: String = "", val content: String = "", val type: String = "PLAIN")

class BackupException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NOT_JSON, WRONG_FORMAT, UNSUPPORTED_VERSION, INVALID_DATA, TOO_LARGE, MISSING_ENTRY }
}

object BackupCodec {
    /** Hard limits that protect against hostile or corrupted files. */
    const val MAX_NOTES = 100_000
    const val MAX_NOTE_CHARS = 5_000_000

    private val validTypes = setOf("PLAIN", "RICH", "CHECKLIST", "IMAGE", "VOICE", "DRAWING", "LINK", "CODE")
    private val validColors = setOf(
        "DEFAULT", "WHITE", "LIGHT_GRAY", "SOFT_YELLOW", "LIGHT_GREEN", "LIGHT_BLUE", "LAVENDER",
        "LIGHT_PINK", "PEACH", "SLATE", "CHARCOAL",
    )
    private val validRepeats = setOf("NONE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")
    private val validAttachmentTypes = setOf("IMAGE", "AUDIO", "DRAWING", "FILE")
    private val safeEntryName = Regex("^[A-Za-z0-9._-]{1,128}$")

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        explicitNulls = false
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** Parses and validates; throws [BackupException] describing the first problem found. */
    fun decode(text: String): BackupFile {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) throw BackupException(BackupException.Reason.NOT_JSON, "This file is not a Notebook backup (not JSON).")
        val backup = try {
            json.decodeFromString(BackupFile.serializer(), trimmed)
        } catch (e: SerializationException) {
            throw BackupException(BackupException.Reason.WRONG_FORMAT, "The backup is damaged or incomplete: ${e.message?.take(200)}")
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupException.Reason.WRONG_FORMAT, "The backup is damaged or incomplete.")
        }
        validate(backup)
        return backup
    }

    fun validate(backup: BackupFile) {
        fun invalid(msg: String): Nothing = throw BackupException(BackupException.Reason.INVALID_DATA, msg)
        if (backup.format != BackupFile.FORMAT) {
            throw BackupException(BackupException.Reason.WRONG_FORMAT, "This file is not a Notebook backup.")
        }
        if (backup.version > BackupFile.CURRENT_VERSION || backup.version < 1) {
            throw BackupException(
                BackupException.Reason.UNSUPPORTED_VERSION,
                "This backup was made by a newer version of Notebook (format ${backup.version}). Update the app and try again.",
            )
        }
        if (backup.notes.size > MAX_NOTES) throw BackupException(BackupException.Reason.TOO_LARGE, "The backup has too many notes.")
        val noteIds = HashSet<Long>()
        val labelIds = HashSet<Long>()
        backup.labels.forEach { label ->
            if (!labelIds.add(label.id)) invalid("Label id ${label.id} appears twice.")
            if (label.name.isBlank()) invalid("A label has no name.")
            if (label.color !in validColors) invalid("Label \"${label.name}\" has an unknown color.")
        }
        backup.notes.forEach { note ->
            if (!noteIds.add(note.id)) invalid("Note id ${note.id} appears twice.")
            if (note.type !in validTypes) invalid("Note ${note.id} has an unknown type \"${note.type}\".")
            if (note.color !in validColors) invalid("Note ${note.id} has an unknown color.")
            if (note.createdAt < 0 || note.updatedAt < 0) invalid("Note ${note.id} has an invalid date.")
            if (note.content.length > MAX_NOTE_CHARS) throw BackupException(BackupException.Reason.TOO_LARGE, "Note ${note.id} is too large.")
            note.reminder?.let { if (it.repeat !in validRepeats) invalid("Note ${note.id} has an unknown reminder repeat.") }
            note.attachments.forEach { a ->
                if (a.type !in validAttachmentTypes) invalid("Note ${note.id} has an unknown attachment type.")
                if (!safeEntryName.matches(a.fileName)) invalid("Note ${note.id} has an unsafe attachment name.")
            }
        }
        backup.templates.forEach { t ->
            if (t.name.isBlank()) invalid("A template has no name.")
            if (t.type !in validTypes) invalid("Template \"${t.name}\" has an unknown type.")
        }
    }

    /** True for names that are safe to resolve inside the attachments directory. */
    fun isSafeEntryName(name: String): Boolean = safeEntryName.matches(name)
}

/** Keeps information from removed features readable instead of dropping it silently. */
object LegacyData {
    /**
     * Appends label names to a note's text as hashtags on their own line, so a note that was
     * labelled "Work" still turns up when searching for "#work". Returns [content] unchanged
     * when there are no names or the tags are already present.
     */
    fun foldLabelsIntoContent(content: String, labelNames: List<String>): String {
        val tags = labelNames.map { it.trim() }.filter { it.isNotEmpty() }.map { "#" + it.replace(Regex("\\s+"), "_") }
        if (tags.isEmpty()) return content
        val line = tags.joinToString(" ")
        if (content.lineSequence().any { it.trim() == line }) return content
        return if (content.isBlank()) line else content.trimEnd() + "\n\n" + line
    }
}
