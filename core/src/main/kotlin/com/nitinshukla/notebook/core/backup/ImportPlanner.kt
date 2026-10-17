package com.nitinshukla.notebook.core.backup

/** What to do with an imported note that matches one already on the device. */
enum class DuplicateStrategy { SKIP, KEEP_BOTH, REPLACE }

/** Identifies a note for duplicate detection without comparing ids, which differ between devices. */
data class NoteFingerprint(val existingId: Long, val title: String, val content: String, val createdAt: Long) {
    val key: String get() = key(title, content, createdAt)

    companion object {
        fun key(title: String, content: String, createdAt: Long): String =
            "${title.trim()}\u0000${content.trim()}\u0000$createdAt"
    }
}

data class ImportPlan(
    val toInsert: List<BackupNote>,
    /** Backup note paired with the id of the local note it replaces. */
    val toReplace: List<Pair<BackupNote, Long>>,
    val skipped: Int,
)

object ImportPlanner {
    fun plan(existing: List<NoteFingerprint>, incoming: List<BackupNote>, strategy: DuplicateStrategy): ImportPlan {
        val byKey = existing.associateBy { it.key }
        val insert = mutableListOf<BackupNote>()
        val replace = mutableListOf<Pair<BackupNote, Long>>()
        var skipped = 0
        val seenIncoming = HashSet<String>()
        for (note in incoming) {
            val key = NoteFingerprint.key(note.title, note.content, note.createdAt)
            if (!seenIncoming.add(key) && strategy != DuplicateStrategy.KEEP_BOTH) {
                skipped++
                continue
            }
            val match = byKey[key]
            when {
                match == null -> insert += note
                strategy == DuplicateStrategy.SKIP -> skipped++
                strategy == DuplicateStrategy.KEEP_BOTH -> insert += note
                strategy == DuplicateStrategy.REPLACE -> replace += note to match.existingId
            }
        }
        return ImportPlan(insert, replace, skipped)
    }
}
