package com.nitinshukla.notebook.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nitinshukla.notebook.core.backup.LegacyData

@Database(
    entities = [
        NoteEntity::class,
        ChecklistItemEntity::class,
        AttachmentEntity::class,
        RevisionEntity::class,
        TemplateEntity::class,
        LinkPreviewEntity::class,
    ],
    version = NotebookDatabase.VERSION,
    exportSchema = true,
)
abstract class NotebookDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun checklistDao(): ChecklistDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun revisionDao(): RevisionDao
    abstract fun templateDao(): TemplateDao
    abstract fun linkPreviewDao(): LinkPreviewDao

    companion object {
        const val NAME = "notebook.db"
        const val VERSION = 2
    }
}

/**
 * Hand-written migrations, one per schema bump. Room's exported schemas in app/schemas let
 * MigrationTest verify each step. Never use fallbackToDestructiveMigration: user notes must
 * survive every update.
 */
object Migrations {
    /**
     * 1 → 2: labels, reminders, favourites and daily notes are gone.
     *
     * Nothing a user typed is dropped: before the `labels` tables go, every unlocked note's
     * label names are appended to its text as hashtags (locked notes keep ciphertext, so their
     * labels cannot be folded in and are lost; the app explains this on first launch). Daily
     * notes simply become ordinary notes. Reminders are deleted. The `notes` table is rebuilt
     * without the `isFavorite` / `dailyDate` columns and gains an index on `type` for the
     * Code Notes section. Room runs migrations with foreign keys off, so the rebuild is safe
     * for the child tables that reference `notes`.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            foldLabels(db)
            db.execSQL("DROP TABLE IF EXISTS `note_labels`")
            db.execSQL("DROP TABLE IF EXISTS `labels`")
            db.execSQL("DROP TABLE IF EXISTS `reminders`")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `notes_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `type` TEXT NOT NULL, `color` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, " +
                    "`isArchived` INTEGER NOT NULL, `isTrashed` INTEGER NOT NULL, `trashedAt` INTEGER, " +
                    "`isLocked` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `fileName` TEXT, " +
                    "`cursorPosition` INTEGER NOT NULL, `revisionsEnabled` INTEGER NOT NULL DEFAULT 1)",
            )
            db.execSQL(
                "INSERT INTO `notes_new` (id, title, content, type, color, createdAt, updatedAt, isPinned, isArchived, " +
                    "isTrashed, trashedAt, isLocked, sortOrder, fileName, cursorPosition, revisionsEnabled) " +
                    "SELECT id, title, content, type, color, createdAt, updatedAt, isPinned, isArchived, " +
                    "isTrashed, trashedAt, isLocked, sortOrder, fileName, cursorPosition, revisionsEnabled FROM `notes`",
            )
            db.execSQL("DROP TABLE `notes`")
            db.execSQL("ALTER TABLE `notes_new` RENAME TO `notes`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_isTrashed` ON `notes` (`isTrashed`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_isArchived` ON `notes` (`isArchived`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_updatedAt` ON `notes` (`updatedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_type` ON `notes` (`type`)")
        }

        private fun foldLabels(db: SupportSQLiteDatabase) {
            val byNote = LinkedHashMap<Long, MutableList<String>>()
            db.query(
                "SELECT nl.noteId, l.name FROM note_labels nl JOIN labels l ON l.id = nl.labelId " +
                    "JOIN notes n ON n.id = nl.noteId WHERE n.isLocked = 0 ORDER BY nl.noteId, l.name COLLATE NOCASE",
            ).use { c ->
                while (c.moveToNext()) byNote.getOrPut(c.getLong(0)) { mutableListOf() } += c.getString(1)
            }
            for ((noteId, names) in byNote) {
                val content = db.query("SELECT content FROM notes WHERE id = ?", arrayOf(noteId)).use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: continue
                val folded = LegacyData.foldLabelsIntoContent(content, names)
                if (folded != content) db.execSQL("UPDATE notes SET content = ? WHERE id = ?", arrayOf(folded, noteId))
            }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
