package com.nitinshukla.notebook

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nitinshukla.notebook.core.backup.BackupCodec
import com.nitinshukla.notebook.core.backup.BackupException
import com.nitinshukla.notebook.core.backup.DuplicateStrategy
import com.nitinshukla.notebook.core.model.Attachment
import com.nitinshukla.notebook.core.model.AttachmentType
import com.nitinshukla.notebook.core.model.ChecklistItem
import com.nitinshukla.notebook.core.model.Note
import com.nitinshukla.notebook.core.util.Clock
import com.nitinshukla.notebook.data.backup.BackupManager
import com.nitinshukla.notebook.data.db.NotebookDatabase
import com.nitinshukla.notebook.data.files.AttachmentStore
import com.nitinshukla.notebook.data.repository.RoomNoteRepository
import com.nitinshukla.notebook.security.NoteCrypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupManagerTest {
    private lateinit var context: Context
    private lateinit var db: NotebookDatabase
    private lateinit var notes: RoomNoteRepository
    private lateinit var manager: BackupManager
    private lateinit var store: AttachmentStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).allowMainThreadQueries().build()
        store = AttachmentStore(context)
        notes = RoomNoteRepository(db, NoteCrypto(), store, Clock { 1_000 })
        manager = BackupManager(context, db, notes, NoteCrypto(), store, Clock { 42 })
    }

    @After
    fun tearDown() = db.close()

    private fun fileUri(name: String): Uri = Uri.fromFile(File(context.cacheDir, name))

    @Test
    fun jsonRoundTrip() = runTest {
        notes.create(Note(title = "Hello", content = "World", isPinned = true), checklist = listOf(ChecklistItem(text = "item")))
        val uri = fileUri("b.json")
        manager.writeJson(uri)

        val text = File(uri.path!!).readText()
        val decoded = BackupCodec.decode(text)
        assertEquals(42L, decoded.createdAt)
        assertEquals(1, decoded.notes.size)

        val info = manager.inspect(uri)
        assertEquals(1, info.noteCount)

        // Restoring onto the same data skips the duplicate, then KEEP_BOTH adds a copy.
        val skip = manager.restore(uri, DuplicateStrategy.SKIP)
        assertEquals(0, skip.inserted)
        assertEquals(1, skip.skipped)
        val both = manager.restore(uri, DuplicateStrategy.KEEP_BOTH)
        assertEquals(1, both.inserted)
        val all = notes.observeAll().first()
        assertEquals(2, all.size)
        assertTrue(all.all { it.checklist.single().text == "item" && it.note.content == "World" })
    }

    @Test
    fun formatOneBackupFoldsLabelsIntoContent() = runTest {
        val uri = fileUri("v1.json")
        File(uri.path!!).writeText(
            """{"format":"notebook-backup","version":1,"createdAt":1,"labels":[{"id":7,"name":"Work stuff"}],""" +
                """"notes":[{"id":1,"title":"Old","content":"body","createdAt":1,"updatedAt":1,"labelIds":[7],"favorite":true,"dailyDate":"2026-01-01",""" +
                """"reminder":{"triggerAt":5}}]}""",
        )
        val info = manager.inspect(uri)
        assertEquals(1, info.formatVersion)
        val result = manager.restore(uri, DuplicateStrategy.SKIP)
        assertEquals(1, result.inserted)
        val restored = notes.observeAll().first().single()
        assertEquals("body\n\n#Work_stuff", restored.note.content)
        assertEquals("Old", restored.note.title)
    }

    @Test
    fun replaceStrategyReplacesLocalNote() = runTest {
        val id = notes.create(Note(title = "A", content = "v1"))
        val uri = fileUri("r.json")
        manager.writeJson(uri)
        notes.setPinned(listOf(id), true)
        val result = manager.restore(uri, DuplicateStrategy.REPLACE)
        assertEquals(1, result.replaced)
        assertEquals(0, result.inserted)
        val all = notes.observeAll().first()
        assertEquals(1, all.size)
        // The local pin made after the backup is gone because the note was replaced.
        assertTrue(!all.single().note.isPinned)
    }

    @Test
    fun zipRoundTripIncludesAttachments() = runTest {
        val id = notes.create(Note(title = "Pic"))
        val (name, size) = store.copy("hello".byteInputStream(), "txt")!!
        notes.addAttachment(Attachment(noteId = id, type = AttachmentType.FILE, fileName = name, mimeType = "text/plain", displayName = "hello.txt", sizeBytes = size))
        val uri = fileUri("b.zip")
        manager.writeZip(uri)
        assertTrue(manager.inspect(uri).hasAttachments)

        // Wipe and restore.
        notes.deletePermanently(listOf(id))
        assertTrue(store.directory.listFiles().isNullOrEmpty())
        val result = manager.restore(uri, DuplicateStrategy.SKIP)
        assertEquals(1, result.inserted)
        assertEquals(0, result.attachmentsMissing)
        val restored = notes.observeAll().first().single()
        assertEquals("hello", store.file(restored.attachments.single().fileName).readText())
    }

    @Test
    fun corruptedBackupIsRejectedBeforeAnyChange() = runTest {
        notes.create(Note(title = "Keep me"))
        val uri = fileUri("bad.json")
        File(uri.path!!).writeText("{\"format\":\"notebook-backup\",\"version\":1,\"createdAt\":1,\"notes\":[{\"id\":1,\"type\":\"NOPE\",\"createdAt\":1,\"updatedAt\":1}]}")
        try {
            manager.restore(uri, DuplicateStrategy.SKIP)
            fail()
        } catch (e: BackupException) {
            assertEquals(BackupException.Reason.INVALID_DATA, e.reason)
        }
        assertEquals(1, notes.observeAll().first().size)
    }
}
