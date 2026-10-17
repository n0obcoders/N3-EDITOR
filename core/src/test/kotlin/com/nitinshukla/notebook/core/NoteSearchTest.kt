package com.nitinshukla.notebook.core

import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.search.MatchField
import com.nitinshukla.notebook.core.search.NoteFilter
import com.nitinshukla.notebook.core.search.NoteSearch
import com.nitinshukla.notebook.core.search.NoteSection
import com.nitinshukla.notebook.core.search.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSearchTest {
    private val notes = listOf(
        summary(1, title = "Groceries", content = "Buy apples", type = NoteType.CHECKLIST, checklistTotal = 2),
        summary(2, title = "Standup", content = "Discuss release plan", pinned = true),
        summary(3, title = "Old idea", content = "archived thing", archived = true),
        summary(4, title = "Deleted", content = "trash me", trashed = true),
        summary(5, title = "Secret", content = "bank pin 1234", locked = true),
        summary(6, title = "", content = "First line here\nsecond line", fileName = "notes.txt", attachmentCount = 1),
        summary(7, title = "scanner.py", content = "import socket", type = NoteType.CODE),
    )

    private fun ids(filter: NoteFilter, fullText: Set<Long>? = null) = NoteSearch.apply(notes, filter, fullText).map { it.id }

    @Test fun `notes section excludes archived and trashed and puts pinned first`() {
        assertEquals(listOf(7L, 6L, 5L, 2L, 1L), ids(NoteFilter()))
    }

    @Test fun `archive trash and code sections`() {
        assertEquals(listOf(3L), ids(NoteFilter(section = NoteSection.ARCHIVE)))
        assertEquals(listOf(4L), ids(NoteFilter(section = NoteSection.TRASH)))
        assertEquals(listOf(7L), ids(NoteFilter(section = NoteSection.CODE)))
    }

    @Test fun `search matches titles preview and file names`() {
        assertEquals(listOf(1L), ids(NoteFilter(query = "grocer")))
        assertEquals(listOf(2L), ids(NoteFilter(query = "RELEASE")))
        assertEquals(listOf(6L), ids(NoteFilter(query = "notes.txt")))
    }

    @Test fun `full text ids from the database widen the match`() {
        assertTrue(ids(NoteFilter(query = "bread")).isEmpty())
        assertEquals(listOf(1L), ids(NoteFilter(query = "bread"), fullText = setOf(1L)))
        // Section and refinements still apply to database matches.
        assertTrue(ids(NoteFilter(query = "trash"), fullText = setOf(4L)).isEmpty())
    }

    @Test fun `all terms must match and quoted phrases stay together`() {
        assertEquals(listOf(2L), ids(NoteFilter(query = "discuss plan")))
        assertTrue(ids(NoteFilter(query = "\"plan release\"")).isEmpty())
        assertEquals(listOf("release plan", "x"), NoteSearch.tokenize("\"Release Plan\" x"))
    }

    @Test fun `locked notes match only on title`() {
        assertTrue(ids(NoteFilter(query = "1234")).isEmpty())
        assertEquals(listOf(5L), ids(NoteFilter(query = "secret")))
        assertEquals(MatchField.TITLE, NoteSearch.matchField(notes[4], "secret"))
        assertNull(NoteSearch.matchField(notes[4], "bank"))
    }

    @Test fun `refinement filters`() {
        assertEquals(listOf(2L), ids(NoteFilter(pinnedOnly = true)))
        assertEquals(listOf(1L), ids(NoteFilter(checklistOnly = true)))
        assertEquals(listOf(6L), ids(NoteFilter(withAttachments = true)))
        assertEquals(listOf(1L), ids(NoteFilter(type = NoteType.CHECKLIST)))
        assertEquals(listOf(2L, 1L), ids(NoteFilter(modifiedTo = 2000)))
        assertEquals(listOf(7L, 6L), ids(NoteFilter(modifiedFrom = 6000)))
    }

    @Test fun `sorting options`() {
        val plain = notes.map { it.copy(isPinned = false) }.filter { !it.isTrashed && !it.isArchived }
        assertEquals(listOf(1L, 2L, 5L, 6L, 7L), NoteSearch.sort(plain, SortOrder.MODIFIED_ASC).map { it.id })
        assertEquals(listOf(7L, 6L, 5L, 2L, 1L), NoteSearch.sort(plain, SortOrder.CREATED_DESC).map { it.id })
        // Note 6 has no title so it sorts by its first line, "First line here".
        assertEquals(listOf(6L, 1L, 7L, 5L, 2L), NoteSearch.sort(plain, SortOrder.TITLE_ASC).map { it.id })
        assertEquals(listOf(2L, 5L, 7L, 1L, 6L), NoteSearch.sort(plain, SortOrder.TITLE_DESC).map { it.id })
    }

    @Test fun `manual sort uses sort order`() {
        val list = listOf(summary(1, sortOrder = 3), summary(2, sortOrder = 1), summary(3, sortOrder = 2))
        assertEquals(listOf(2L, 3L, 1L), NoteSearch.sort(list, SortOrder.MANUAL).map { it.id })
    }

    @Test fun `highlight ranges are merged and case insensitive`() {
        assertEquals(listOf(0..2, 8..10), NoteSearch.highlightRanges("Foo bar foo", "foo"))
        assertEquals(listOf(0..5), NoteSearch.highlightRanges("foobar!", "foo bar"))
        assertTrue(NoteSearch.highlightRanges("abc", "").isEmpty())
    }

    @Test fun `snippet centers on match`() {
        val text = "x".repeat(300) + " needle " + "y".repeat(300)
        val s = NoteSearch.snippet(text, "needle", 60)
        assertTrue(s.contains("needle"))
        assertTrue(s.startsWith("…") && s.endsWith("…"))
        assertEquals("short", NoteSearch.snippet("short", "zzz"))
    }

    @Test fun `suggestions include recent searches and titles`() {
        val s = NoteSearch.suggestions(notes, listOf("standup notes", "other"), "st")
        assertEquals("standup notes", s.first())
        assertTrue(s.contains("Standup"))
        assertFalse(s.contains("Deleted"))
    }

    @Test fun `remember search dedupes and caps`() {
        val r = NoteSearch.rememberSearch(listOf("a", "B", "c"), "b", max = 3)
        assertEquals(listOf("b", "a", "c"), r)
        assertEquals(listOf("a"), NoteSearch.rememberSearch(listOf("a"), "  "))
    }

    @Test fun `display title falls back to first line but not for locked notes`() {
        assertEquals("First line here", NoteSearch.displayTitle(notes[5]))
        assertEquals("", NoteSearch.displayTitle(summary(9, content = "hidden", locked = true)))
    }

    @Test fun `like pattern escapes wildcards`() {
        assertEquals("%100\\%\\_done%", NoteSearch.likePattern("100%_done"))
    }
}
