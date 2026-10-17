package com.nitinshukla.notebook

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nitinshukla.notebook.data.db.Migrations
import com.nitinshukla.notebook.data.db.NotebookDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the exported schema in app/schemas can be opened and migrated through every
 * migration in [Migrations.ALL]. When the schema version is bumped, add a step here that
 * creates version N-1, inserts rows and runs validateMigration to N.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NotebookDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test
    fun currentSchemaOpensAndValidates() {
        helper.createDatabase(dbName, NotebookDatabase.VERSION).apply {
            execSQL("INSERT INTO notes (title, content, type, color, createdAt, updatedAt, isPinned, isArchived, isTrashed, isLocked, sortOrder, cursorPosition, revisionsEnabled) VALUES ('t', 'c', 'PLAIN', 'DEFAULT', 1, 1, 0, 0, 0, 0, 0, 0, 1)")
            close()
        }
        val db = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, NotebookDatabase::class.java, dbName)
            .addMigrations(*Migrations.ALL)
            .build()
        val count = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notes").use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, count)
        db.close()
    }

    @Test
    fun migrate1To2FoldsLabelsAndDropsOldTables() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO notes (id, title, content, type, color, createdAt, updatedAt, isPinned, isArchived, isTrashed, isFavorite, isLocked, sortOrder, cursorPosition, revisionsEnabled) VALUES (1, 'a', 'hello', 'PLAIN', 'DEFAULT', 1, 1, 0, 0, 0, 1, 0, 0, 0, 1)")
            execSQL("INSERT INTO labels (id, name, color) VALUES (5, 'Work', 'DEFAULT')")
            execSQL("INSERT INTO note_labels (noteId, labelId) VALUES (1, 5)")
            execSQL("INSERT INTO reminders (noteId, triggerAt, repeat, isCompleted, createdAt) VALUES (1, 9, 'NONE', 0, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 2, true, Migrations.MIGRATION_1_2)
        val content = db.query("SELECT content FROM notes WHERE id = 1").use { c -> c.moveToFirst(); c.getString(0) }
        assertEquals("hello\n\n#Work", content)
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }
        assertEquals(false, "labels" in tables || "note_labels" in tables || "reminders" in tables)
        db.close()
    }
}
