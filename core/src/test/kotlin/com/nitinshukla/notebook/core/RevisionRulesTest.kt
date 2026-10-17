package com.nitinshukla.notebook.core

import com.nitinshukla.notebook.core.model.Revision
import com.nitinshukla.notebook.core.revision.DiffOp
import com.nitinshukla.notebook.core.revision.LineDiff
import com.nitinshukla.notebook.core.revision.RevisionPolicy
import com.nitinshukla.notebook.core.revision.RevisionRules
import com.nitinshukla.notebook.core.template.DefaultTemplates
import com.nitinshukla.notebook.core.util.TrashRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class RevisionRulesTest {
    private val policy = RevisionPolicy(minIntervalMs = 1000, maxPerNote = 2, maxTotalBytes = 100)

    @Test fun `snapshot rules`() {
        val latest = Revision(1, 1, "t", "a", createdAt = 0)
        assertTrue(RevisionRules.shouldSnapshot(null, "t", "a", 0, policy))
        assertFalse(RevisionRules.shouldSnapshot(latest, "t", "a", 5000, policy))
        assertFalse(RevisionRules.shouldSnapshot(latest, "t", "b", 500, policy))
        assertTrue(RevisionRules.shouldSnapshot(latest, "t", "b", 500, policy, force = true))
        assertTrue(RevisionRules.shouldSnapshot(latest, "t", "b", 1000, policy))
        assertFalse(RevisionRules.shouldSnapshot(null, " ", "", 0, policy))
    }

    @Test fun `pruning by count and storage`() {
        val revs = (1..4).map { Revision(it.toLong(), 1, "", "x".repeat(10), createdAt = it * 10L) }
        assertEquals(listOf(2L, 1L), RevisionRules.excessForNote(revs, 2))
        // Each revision is 20 bytes; 100 bytes allowed means no deletion, 50 means delete two oldest.
        assertTrue(RevisionRules.excessForStorage(revs, 100).isEmpty())
        assertEquals(listOf(1L, 2L), RevisionRules.excessForStorage(revs, 50))
    }

    @Test fun `line diff`() {
        val d = LineDiff.diff("a\nb\nc", "a\nB\nc\nd")
        assertEquals(
            listOf(DiffOp.EQUAL, DiffOp.REMOVED, DiffOp.ADDED, DiffOp.EQUAL, DiffOp.ADDED),
            d.map { it.op },
        )
        assertTrue(LineDiff.diff("same", "same").all { it.op == DiffOp.EQUAL })
    }

    @Test fun `trash retention`() {
        val day = 86_400_000L
        val now = 100 * day
        val notes = listOf(
            note(1, trashed = true).note.copy(trashedAt = now - 31 * day),
            note(2, trashed = true).note.copy(trashedAt = now - 5 * day),
            note(3).note,
        )
        assertEquals(listOf(1L), TrashRules.expired(notes, 30, now))
        assertTrue(TrashRules.expired(notes, 0, now).isEmpty())
        assertEquals(25, TrashRules.daysLeft(notes[1], 30, now))
        assertNull(TrashRules.daysLeft(notes[2], 30, now))
    }

    @Test fun `templates`() {
        assertEquals(8, DefaultTemplates.all.size)
        assertTrue(DefaultTemplates.all.all { it.isBuiltIn })
        assertEquals("Meeting: Oct 3, 2026", DefaultTemplates.expand("Meeting: {{date}}", LocalDate.of(2026, 10, 3), Locale.US))
    }
}
