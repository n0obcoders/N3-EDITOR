package com.nitinshukla.notebook.core

import com.nitinshukla.notebook.core.checklist.ChecklistOps
import com.nitinshukla.notebook.core.model.ChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistOpsTest {
    private val base = ChecklistOps.fromText("A\nB\nC")

    @Test fun `add inserts and renumbers`() {
        val l = ChecklistOps.add(base, "X", 1)
        assertEquals(listOf("A", "X", "B", "C"), l.map { it.text })
        assertEquals(listOf(0, 1, 2, 3), l.map { it.position })
    }

    @Test fun `toggle and move checked to bottom keeps order`() {
        val l = ChecklistOps.toggle(base, 0, moveCheckedToBottom = true)
        assertEquals(listOf("B", "C", "A"), l.map { it.text })
        assertTrue(l.last().isChecked)
        val again = ChecklistOps.toggle(l, 2)
        assertEquals(0, again.count { it.isChecked })
    }

    @Test fun `move remove and clear completed`() {
        assertEquals(listOf("B", "C", "A"), ChecklistOps.move(base, 0, 2).map { it.text })
        assertEquals(listOf("A", "C"), ChecklistOps.remove(base, 1).map { it.text })
        val checked = ChecklistOps.toggle(ChecklistOps.toggle(base, 0), 2)
        assertEquals(listOf("B"), ChecklistOps.clearCompleted(checked).map { it.text })
        assertEquals(0, ChecklistOps.uncheckAll(checked).count { it.isChecked })
        assertEquals(base, ChecklistOps.move(base, 7, 0))
    }

    @Test fun `progress`() {
        val p = ChecklistOps.progress(ChecklistOps.toggle(base, 1))
        assertEquals(1, p.done)
        assertEquals(3, p.total)
        assertEquals(1f / 3, p.fraction, 0.0001f)
        assertEquals(0f, ChecklistOps.progress(emptyList()).fraction)
    }

    @Test fun `text round trip and bullet parsing`() {
        val items = ChecklistOps.fromText("- [x] done\n[ ] todo\n* bullet\n\n  plain  ")
        assertEquals(listOf("done", "todo", "bullet", "plain"), items.map { it.text })
        assertEquals(listOf(true, false, false, false), items.map { it.isChecked })
        assertEquals("[x] done\n[ ] todo\n[ ] bullet\n[ ] plain", ChecklistOps.toText(items))
        assertEquals(items.map { it.text to it.isChecked }, ChecklistOps.fromText(ChecklistOps.toText(items)).map { it.text to it.isChecked })
    }

    @Test fun `temporary ids are unique and negative`() {
        val items = listOf(ChecklistItem(id = 5), ChecklistItem(), ChecklistItem(), ChecklistItem(id = -1))
        val ids = ChecklistOps.withTemporaryIds(items).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(5L, ids[0])
        assertTrue(ids[1] < 0 && ids[2] < 0)
    }
}
