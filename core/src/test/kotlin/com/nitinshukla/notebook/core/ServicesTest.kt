package com.nitinshukla.notebook.core

import com.nitinshukla.notebook.core.model.Note
import com.nitinshukla.notebook.core.model.Revision
import com.nitinshukla.notebook.core.repository.RevisionRepository
import com.nitinshukla.notebook.core.repository.RevisionService
import com.nitinshukla.notebook.core.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRevisions : RevisionRepository {
    val items = mutableListOf<Revision>()
    private var nextId = 1L
    override fun observe(noteId: Long) = flowOf(items.filter { it.noteId == noteId })
    override suspend fun latest(noteId: Long) = items.filter { it.noteId == noteId }.maxByOrNull { it.createdAt }
    override suspend fun get(id: Long) = items.firstOrNull { it.id == id }
    override suspend fun insert(revision: Revision): Long { val id = nextId++; items += revision.copy(id = id); return id }
    override suspend fun delete(ids: Collection<Long>) { items.removeAll { it.id in ids } }
    override suspend fun deleteAllFor(noteId: Long) { items.removeAll { it.noteId == noteId } }
    override suspend fun allForNote(noteId: Long) = items.filter { it.noteId == noteId }
    override suspend fun allMetadata() = items.toList()
}

class ServicesTest {
    @Test fun `revision service snapshots and prunes`() = runTest {
        val repo = FakeRevisions()
        var time = 0L
        val service = RevisionService(repo) { time }
        val settings = AppSettings(revisionIntervalMinutes = 1, maxRevisionsPerNote = 2)
        val note = Note(id = 1, title = "t", content = "v1")
        assertTrue(service.snapshotIfNeeded(note, settings))
        time = 10_000
        assertFalse(service.snapshotIfNeeded(note.copy(content = "v2"), settings))
        assertTrue(service.snapshotIfNeeded(note.copy(content = "v2"), settings, force = true))
        time = 200_000
        assertTrue(service.snapshotIfNeeded(note.copy(content = "v3"), settings))
        assertEquals(listOf("v2", "v3"), repo.items.map { it.content })
        assertFalse(service.snapshotIfNeeded(note.copy(content = "v4", isLocked = true), settings, force = true))
        assertFalse(service.snapshotIfNeeded(note.copy(content = "v4"), settings.copy(revisionsEnabled = false), force = true))
    }
}
