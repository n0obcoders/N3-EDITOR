package com.nitinshukla.notebook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nitinshukla.notebook.data.db.AttachmentEntity
import com.nitinshukla.notebook.data.db.ChecklistItemEntity
import com.nitinshukla.notebook.data.db.NoteEntity
import com.nitinshukla.notebook.data.db.NotebookDatabase
import com.nitinshukla.notebook.data.db.RevisionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Room CRUD, relations and cascade behavior, run on Robolectric's SQLite. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NoteDatabaseTest {
    private lateinit var db: NotebookDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun note(title: String = "t", content: String = "c") =
        NoteEntity(title = title, content = content, type = "PLAIN", color = "DEFAULT", createdAt = 1, updatedAt = 1)

    @Test
    fun insertReadUpdateDelete() = runTest {
        val id = db.noteDao().insert(note("Hello"))
        assertEquals("Hello", db.noteDao().get(id)!!.title)
        db.noteDao().update(db.noteDao().get(id)!!.copy(title = "Changed"))
        assertEquals("Changed", db.noteDao().get(id)!!.title)
        db.noteDao().delete(listOf(id))
        assertNull(db.noteDao().get(id))
    }

    @Test
    fun relationsLoadTogether() = runTest {
        val id = db.noteDao().insert(note())
        db.checklistDao().insertAll(listOf(ChecklistItemEntity(noteId = id, text = "a", isChecked = false, position = 1), ChecklistItemEntity(noteId = id, text = "b", isChecked = true, position = 0)))
        db.attachmentDao().insert(AttachmentEntity(noteId = id, type = "IMAGE", fileName = "x.jpg", mimeType = "image/jpeg", displayName = "x", caption = "", sizeBytes = 1, durationMs = null, createdAt = 1))
        val loaded = db.noteDao().getWithRelations(id)!!
        assertEquals(2, loaded.checklist.size)
        assertEquals(1, loaded.attachments.size)
        assertEquals(listOf("b", "a"), db.checklistDao().forNote(id).map { it.text })
        val summary = db.noteDao().observeSummaries().first().single()
        assertEquals(2, summary.checklistTotal)
        assertEquals(1, summary.checklistDone)
        assertEquals("1b\n0a", summary.checklistPreview)
        assertEquals(1, summary.attachmentCount)
    }

    @Test
    fun deletingNoteCascadesToChildren() = runTest {
        val id = db.noteDao().insert(note())
        db.checklistDao().insertAll(listOf(ChecklistItemEntity(noteId = id, text = "a", isChecked = false, position = 0)))
        db.revisionDao().insert(RevisionEntity(noteId = id, title = "", content = "old", createdAt = 1, sizeBytes = 6))
        db.noteDao().delete(listOf(id))
        assertTrue(db.checklistDao().forNote(id).isEmpty())
        assertTrue(db.revisionDao().forNote(id).isEmpty())
    }

    @Test
    fun trashArchivePinFlags() = runTest {
        val id = db.noteDao().insert(note())
        db.noteDao().setPinned(listOf(id), true)
        db.noteDao().setArchived(listOf(id), true)
        assertTrue(db.noteDao().get(id)!!.isArchived)
        assertTrue(!db.noteDao().get(id)!!.isPinned)
        db.noteDao().moveToTrash(listOf(id), 99)
        assertEquals(99L, db.noteDao().get(id)!!.trashedAt)
        assertEquals(listOf(id), db.noteDao().trashedIds())
        db.noteDao().restore(listOf(id))
        assertNull(db.noteDao().get(id)!!.trashedAt)
        assertEquals(1, db.noteDao().observeCount().first())
    }

    @Test
    fun lockedNotesExposeNoPreviewOrSearchHits() = runTest {
        val id = db.noteDao().insert(note("Secret", "enc:v1:abc").copy(isLocked = true))
        val summary = db.noteDao().observeSummaries().first().single { it.id == id }
        assertEquals("", summary.preview)
        assertTrue(db.noteDao().searchIds("%enc%").isEmpty())
        assertTrue(db.noteDao().contents(listOf(id)).isEmpty())
    }

    @Test
    fun saveContentUpdatesOnlyEditableColumns() = runTest {
        val id = db.noteDao().insert(note())
        db.noteDao().setPinned(listOf(id), true)
        db.noteDao().saveContent(id, "T", "body", 2, 55, "CODE", false, "a.py")
        val n = db.noteDao().get(id)!!
        assertEquals("T", n.title)
        assertEquals("body", n.content)
        assertEquals(55L, n.updatedAt)
        assertEquals("CODE", n.type)
        assertEquals("a.py", n.fileName)
        assertTrue(n.isPinned)
        assertEquals(1L, n.createdAt)
    }

    @Test
    fun checklistReplaceKeepsOrder() = runTest {
        val id = db.noteDao().insert(note())
        db.checklistDao().replace(id, listOf(ChecklistItemEntity(noteId = id, text = "x", isChecked = false, position = 0)))
        db.checklistDao().replace(id, listOf(ChecklistItemEntity(noteId = id, text = "y", isChecked = false, position = 0), ChecklistItemEntity(noteId = id, text = "z", isChecked = false, position = 1)))
        assertEquals(listOf("y", "z"), db.checklistDao().forNote(id).map { it.text })
    }
}
