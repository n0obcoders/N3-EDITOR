package com.nitinshukla.notebook.core.util

import com.nitinshukla.notebook.core.model.Note

object TrashRules {
    /** Ids of trashed notes older than [retentionDays]. Zero retention keeps everything. */
    fun expired(notes: List<Note>, retentionDays: Int, now: Long): List<Long> {
        if (retentionDays <= 0) return emptyList()
        val cutoff = now - retentionDays * 86_400_000L
        return notes.filter { it.isTrashed && (it.trashedAt ?: it.updatedAt) < cutoff }.map { it.id }
    }

    fun daysLeft(note: Note, retentionDays: Int, now: Long): Int? {
        if (!note.isTrashed || retentionDays <= 0) return null
        val trashedAt = note.trashedAt ?: note.updatedAt
        val left = retentionDays - ((now - trashedAt) / 86_400_000L).toInt()
        return left.coerceAtLeast(0)
    }
}
