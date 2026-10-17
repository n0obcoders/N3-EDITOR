package com.nitinshukla.notebook

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nitinshukla.notebook.core.backup.DuplicateStrategy
import com.nitinshukla.notebook.core.model.Note
import com.nitinshukla.notebook.core.util.Clock
import com.nitinshukla.notebook.data.backup.BackupManager
import com.nitinshukla.notebook.data.db.LargeRowOpenHelperFactory
import com.nitinshukla.notebook.data.db.NotebookDatabase
import com.nitinshukla.notebook.data.files.AttachmentStore
import com.nitinshukla.notebook.data.repository.RoomNoteRepository
import com.nitinshukla.notebook.security.NoteCrypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Restoring a backup that holds a note bigger than Android's 2 MB cursor window used to fail with
 * "Row too big to fit into CursorWindow". The database now reads such rows in a larger window.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LargeNoteBackupTest {
    private lateinit var context: Context
    private lateinit var db: NotebookDatabase
    private lateinit var notes: RoomNoteRepository
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java)
            .openHelperFactory(LargeRowOpenHelperFactory())
            .allowMainThreadQueries()
            .build()
        val store = AttachmentStore(context)
        notes = RoomNoteRepository(db, NoteCrypto(), store, Clock { 1_000 })
        manager = BackupManager(context, db, notes, NoteCrypto(), store, Clock { 42 })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun backupAndRestoreWithNoteLargerThanCursorWindow() = runTest {
        val big = buildString { repeat(120_000) { append("line $it of a very long pasted log\n") } } // ~4 MB
        notes.create(Note(title = "small 1", content = "a"))
        notes.create(Note(title = "small 2", content = "b"))
        notes.create(Note(title = "small 3", content = "c"))
        notes.create(Note(title = "small 4", content = "d"))
        notes.create(Note(title = "huge", content = big))

        // Room's queries go through the large-window helper (on a device that is what lets the
        // 4 MB row be read; Robolectric's SQLite does not enforce the 2 MB limit itself).
        val before = LargeRowOpenHelperFactory.cursorsWidened
        notes.observeAll().first()
        assertTrue(LargeRowOpenHelperFactory.cursorsWidened > before)

        val uri = Uri.fromFile(File(context.cacheDir, "large.zip"))
        manager.writeZip(uri)
        val skip = manager.restore(uri, DuplicateStrategy.SKIP)
        assertEquals(5, skip.skipped)
        val both = manager.restore(uri, DuplicateStrategy.KEEP_BOTH)
        assertEquals(5, both.inserted)

        val restored = notes.observeAll().first().filter { it.note.title == "huge" }
        assertEquals(2, restored.size)
        restored.forEach { assertEquals(big.length, it.note.content.length) }
    }
}
