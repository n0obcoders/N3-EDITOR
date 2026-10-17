package com.nitinshukla.notebook

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nitinshukla.notebook.core.model.Note
import com.nitinshukla.notebook.core.util.Clock
import com.nitinshukla.notebook.data.db.NotebookDatabase
import com.nitinshukla.notebook.data.files.AttachmentStore
import com.nitinshukla.notebook.data.repository.RoomNoteRepository
import com.nitinshukla.notebook.security.NoteCrypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockedNoteRepositoryTest {
    @Test
    fun lockedNotesAreEncryptedAtRestAndHiddenInLists() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).build()
        val crypto = NoteCrypto()
        val repo = RoomNoteRepository(db, crypto, AttachmentStore(context), Clock.System)
        val id = repo.create(Note(title = "Bank", content = "pin 1234"))
        repo.setLocked(id, true)
        val raw = db.noteDao().get(id)!!
        assertTrue(crypto.isEncrypted(raw.content))
        assertEquals("", repo.observeAll().first().single().note.content)
        assertEquals("pin 1234", repo.get(id)!!.note.content)
        repo.setLocked(id, false)
        assertEquals("pin 1234", db.noteDao().get(id)!!.content)
        db.close()
    }
}
