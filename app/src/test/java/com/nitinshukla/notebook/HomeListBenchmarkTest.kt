package com.nitinshukla.notebook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nitinshukla.notebook.core.model.ChecklistItem
import com.nitinshukla.notebook.core.model.Note
import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.search.NoteFilter
import com.nitinshukla.notebook.core.search.NoteSearch
import com.nitinshukla.notebook.core.util.Clock
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

/**
 * Timing comparison between the pre-upgrade home list query ([RoomNoteRepository.observeAll],
 * full notes plus every checklist item and attachment) and the summary projection the lists
 * use now. Runs on the JVM SQLite that Robolectric bundles, so absolute numbers are not device
 * numbers; the ratio is what matters. Prints a `BENCH` line per measurement.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HomeListBenchmarkTest {
    private lateinit var db: NotebookDatabase
    private lateinit var notes: RoomNoteRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).allowMainThreadQueries().build()
        notes = RoomNoteRepository(db, NoteCrypto(), AttachmentStore(context), Clock { System.currentTimeMillis() })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun summaryProjectionVersusFullNotes() = runTest {
        val count = 2_000
        val body = buildString { repeat(40) { append("Line $it of a fairly ordinary note body with some words in it.\n") } }
        repeat(count) { i ->
            val checklist = if (i % 4 == 0) (0 until 6).map { ChecklistItem(text = "Item $it", isChecked = it % 2 == 0, position = it) } else emptyList()
            notes.create(Note(title = "Note $i", content = body + " needle$i", type = if (i % 4 == 0) NoteType.CHECKLIST else NoteType.PLAIN), checklist)
        }
        // Warm-up so page cache and Room statement caches are equal for both.
        notes.observeAll().first(); notes.observeSummaries().first()

        val fullNs = median(5) { notes.observeAll().first().size }
        val summaryNs = median(5) { notes.observeSummaries().first().size }
        val summaryBytes = notes.observeSummaries().first().sumOf { it.preview.length + it.title.length } * 2L
        val fullBytes = notes.observeAll().first().sumOf { it.note.content.length + it.note.title.length } * 2L

        println("BENCH home_list notes=$count full_notes_ms=${fullNs / 1e6} summaries_ms=${summaryNs / 1e6} full_text_bytes=$fullBytes summary_text_bytes=$summaryBytes")

        val all = notes.observeSummaries().first()
        assertEquals(count, all.size)
        assertTrue(all.all { it.preview.length <= com.nitinshukla.notebook.core.model.NoteSummary.PREVIEW_CHARS })

        // Search: SQL LIKE over content versus loading everything and filtering in memory.
        val query = "needle1999"
        val sqlNs = median(5) { notes.searchIds(query)!!.size }
        val memoryNs = median(5) {
            val details = notes.observeAll().first()
            details.count { it.note.content.contains(query, ignoreCase = true) }
        }
        val hits = notes.searchIds(query)!!
        val visible = NoteSearch.apply(all, NoteFilter(query = query), hits)
        println("BENCH search query=$query sql_like_ms=${sqlNs / 1e6} load_all_and_filter_ms=${memoryNs / 1e6} hits=${hits.size}")
        assertEquals(1, visible.size)
    }

    private inline fun median(runs: Int, block: () -> Int): Long {
        val times = LongArray(runs) {
            val start = System.nanoTime()
            block()
            System.nanoTime() - start
        }
        times.sort()
        return times[runs / 2]
    }
}
