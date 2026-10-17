package com.nitinshukla.notebook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nitinshukla.notebook.core.model.ChecklistItem
import com.nitinshukla.notebook.core.model.Note
import com.nitinshukla.notebook.core.model.NoteColor
import com.nitinshukla.notebook.core.util.Clock
import com.nitinshukla.notebook.data.db.NotebookDatabase
import com.nitinshukla.notebook.data.files.AttachmentStore
import com.nitinshukla.notebook.data.repository.RoomNoteRepository
import com.nitinshukla.notebook.data.repository.RoomTemplateRepository
import com.nitinshukla.notebook.security.NoteCrypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Repository behavior on a real Room instance. Robolectric has no Android Keystore, so
 * locked-note encryption is covered by the instrumented tests instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoryTest {
    private lateinit var db: NotebookDatabase
    private lateinit var notes: RoomNoteRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).allowMainThreadQueries().build()
        notes = RoomNoteRepository(db, NoteCrypto(), AttachmentStore(context), Clock { now })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createSetsTimestampsAndSortOrder() = runTest {
        val a = notes.create(Note(title = "A"))
        now = 2_000
        val b = notes.create(Note(title = "B"))
        val all = notes.observeAll().first().associateBy { it.id }
        assertEquals(1_000L, all[a]!!.note.createdAt)
        assertEquals(2_000L, all[b]!!.note.updatedAt)
        assertTrue(all[b]!!.note.sortOrder > all[a]!!.note.sortOrder)
    }

    @Test
    fun updateTouchesModifiedTime() = runTest {
        val id = notes.create(Note(title = "A"))
        now = 5_000
        notes.update(notes.get(id)!!.note.copy(content = "x"))
        assertEquals(5_000L, notes.get(id)!!.note.updatedAt)
        now = 6_000
        notes.update(notes.get(id)!!.note.copy(content = "y"), touch = false)
        assertEquals(5_000L, notes.get(id)!!.note.updatedAt)
    }

    @Test
    fun checklistRoundTrip() = runTest {
        val id = notes.create(Note(title = "A"), checklist = listOf(ChecklistItem(text = "one"), ChecklistItem(text = "two")))
        val d = notes.get(id)!!
        assertEquals(listOf("one", "two"), d.checklist.map { it.text })
        notes.replaceChecklist(id, listOf(ChecklistItem(id = -3, text = "only")))
        assertEquals(listOf("only"), notes.get(id)!!.checklist.map { it.text })
    }

    @Test
    fun updateReturnsThePersistedTimestamp() = runTest {
        val id = notes.create(Note(title = "A"))
        now = 7_000
        val returned = notes.update(notes.get(id)!!.note.copy(content = "typed"))
        assertEquals(7_000L, returned)
        assertEquals(7_000L, notes.get(id)!!.note.updatedAt)
        now = 8_000
        assertEquals(8_000L, notes.rename(id, "  Renamed "))
        assertEquals("Renamed", notes.get(id)!!.note.title)
        assertEquals("typed", notes.get(id)!!.note.content)
    }

    @Test
    fun summariesCarryPreviewAndCounts() = runTest {
        val long = "x".repeat(2_000)
        val id = notes.create(Note(title = "Big", content = long), checklist = listOf(ChecklistItem(text = "a", isChecked = true), ChecklistItem(text = "b")))
        val s = notes.observeSummaries().first().single { it.id == id }
        assertEquals(400, s.preview.length)
        assertEquals(2, s.checklistTotal)
        assertEquals(1, s.checklistDone)
        assertEquals(listOf("a" to true, "b" to false), s.checklistItems)
        assertEquals(0, s.attachmentCount)
    }

    @Test
    fun fullTextSearchFindsEveryTermAndEscapesWildcards() = runTest {
        val a = notes.create(Note(title = "Shopping", content = "milk and 100% bread"))
        notes.create(Note(title = "Other", content = "milk only"))
        val c = notes.create(Note(title = "List"), checklist = listOf(ChecklistItem(text = "bread and milk")))
        assertEquals(setOf(a, c), notes.searchIds("milk bread"))
        assertEquals(setOf(a), notes.searchIds("100%"))
        assertEquals(emptySet<Long>(), notes.searchIds("nothing-here"))
        assertNull(notes.searchIds("  "))
        assertEquals(mapOf(a to "milk and 100% bread"), notes.contents(listOf(a)))
    }

    @Test
    fun trashRestoreAndPermanentDelete() = runTest {
        val id = notes.create(Note(title = "A"))
        notes.moveToTrash(listOf(id))
        assertTrue(notes.get(id)!!.note.isTrashed)
        notes.restoreFromTrash(listOf(id))
        assertTrue(!notes.get(id)!!.note.isTrashed)
        notes.moveToTrash(listOf(id))
        notes.emptyTrash()
        assertNull(notes.get(id))
    }

    @Test
    fun duplicateCopiesContentAndChecklist() = runTest {
        val id = notes.create(Note(title = "A", content = "body", color = NoteColor.LAVENDER, isPinned = true), checklist = listOf(ChecklistItem(text = "x")))
        val copy = notes.duplicate(id)
        assertNotEquals(id, copy)
        val d = notes.get(copy)!!
        assertEquals("A (copy)", d.note.title)
        assertEquals("body", d.note.content)
        assertEquals(NoteColor.LAVENDER, d.note.color)
        assertTrue(!d.note.isPinned)
        assertEquals(listOf("x"), d.checklist.map { it.text })
    }

    @Test
    fun manualOrderAndColor() = runTest {
        val a = notes.create(Note(title = "A"))
        val b = notes.create(Note(title = "B"))
        notes.updateManualOrder(listOf(b, a))
        val all = notes.observeAll().first().associateBy { it.id }
        assertTrue(all[b]!!.note.sortOrder < all[a]!!.note.sortOrder)
        notes.setColor(listOf(a, b), NoteColor.SOFT_YELLOW)
        assertEquals(NoteColor.SOFT_YELLOW, notes.get(a)!!.note.color)
    }

    @Test
    fun templatesSeedOnce() = runTest {
        val templates = RoomTemplateRepository(db)
        templates.ensureDefaults()
        templates.ensureDefaults()
        assertEquals(8, templates.observeAll().first().size)
    }
}
