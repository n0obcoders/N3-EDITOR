package com.nitinshukla.notebook

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nitinshukla.notebook.data.db.Migrations
import com.nitinshukla.notebook.data.db.NotebookDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Runs the 1 → 2 migration against the exported version-1 schema on Robolectric, so a
 * broken migration fails `./gradlew test` rather than a user's first launch after update.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationRobolectricTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NotebookDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test
    fun labelsBecomeTagsLockedNotesKeepCiphertextAndChildrenSurvive() {
        val name = "m12"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO notes (id, title, content, type, color, createdAt, updatedAt, isPinned, isArchived, isTrashed, isFavorite, isLocked, sortOrder, cursorPosition, revisionsEnabled) VALUES (1, 'a', 'hello', 'PLAIN', 'DEFAULT', 1, 1, 0, 0, 0, 1, 0, 3, 0, 1)")
            execSQL("INSERT INTO notes (id, title, content, type, color, createdAt, updatedAt, isPinned, isArchived, isTrashed, isFavorite, isLocked, sortOrder, cursorPosition, revisionsEnabled, dailyDate) VALUES (2, 'locked', 'enc:v1:xyz', 'PLAIN', 'DEFAULT', 1, 1, 0, 0, 0, 0, 1, 4, 0, 1, '2026-01-01')")
            execSQL("INSERT INTO labels (id, name, color) VALUES (5, 'Work', 'DEFAULT'), (6, 'Big idea', 'DEFAULT')")
            execSQL("INSERT INTO note_labels (noteId, labelId) VALUES (1, 5), (1, 6), (2, 5)")
            execSQL("INSERT INTO reminders (noteId, triggerAt, repeat, isCompleted, createdAt) VALUES (1, 9, 'NONE', 0, 1)")
            execSQL("INSERT INTO checklist_items (noteId, text, isChecked, position) VALUES (1, 'item', 0, 0)")
            execSQL("INSERT INTO revisions (noteId, title, content, createdAt, sizeBytes) VALUES (1, 'a', 'old', 1, 6)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true, Migrations.MIGRATION_1_2)
        assertEquals("hello\n\n#Big_idea #Work", db.query("SELECT content FROM notes WHERE id = 1").use { it.moveToFirst(); it.getString(0) })
        assertEquals("enc:v1:xyz", db.query("SELECT content FROM notes WHERE id = 2").use { it.moveToFirst(); it.getString(0) })
        assertEquals(1, db.query("SELECT COUNT(*) FROM checklist_items WHERE noteId = 1").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, db.query("SELECT COUNT(*) FROM revisions WHERE noteId = 1").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(4L, db.query("SELECT sortOrder FROM notes WHERE id = 2").use { it.moveToFirst(); it.getLong(0) })
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }
        assertFalse("labels" in tables)
        assertFalse("note_labels" in tables)
        assertFalse("reminders" in tables)
        db.close()
    }
}
