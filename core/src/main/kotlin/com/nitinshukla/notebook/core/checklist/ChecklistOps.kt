package com.nitinshukla.notebook.core.checklist

import com.nitinshukla.notebook.core.model.ChecklistItem

data class ChecklistProgress(val done: Int, val total: Int) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/**
 * Pure checklist operations. Every function returns a new list with positions renumbered
 * 0..n-1 in display order. New items get id 0 until the database assigns one, so callers
 * that need stable keys before saving should use [withTemporaryIds].
 */
object ChecklistOps {

    fun normalize(items: List<ChecklistItem>): List<ChecklistItem> =
        items.mapIndexed { index, item -> if (item.position == index) item else item.copy(position = index) }

    fun add(items: List<ChecklistItem>, text: String, index: Int = items.size, noteId: Long = 0): List<ChecklistItem> {
        val list = items.toMutableList()
        list.add(index.coerceIn(0, list.size), ChecklistItem(noteId = noteId, text = text))
        return normalize(list)
    }

    fun setText(items: List<ChecklistItem>, index: Int, text: String): List<ChecklistItem> =
        items.mapIndexed { i, item -> if (i == index) item.copy(text = text) else item }

    fun toggle(items: List<ChecklistItem>, index: Int, moveCheckedToBottom: Boolean = false): List<ChecklistItem> {
        val updated = items.mapIndexed { i, item -> if (i == index) item.copy(isChecked = !item.isChecked) else item }
        return if (moveCheckedToBottom) checkedToBottom(updated) else normalize(updated)
    }

    fun remove(items: List<ChecklistItem>, index: Int): List<ChecklistItem> =
        normalize(items.filterIndexed { i, _ -> i != index })

    fun move(items: List<ChecklistItem>, from: Int, to: Int): List<ChecklistItem> {
        if (from !in items.indices) return items
        val list = items.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        return normalize(list)
    }

    /** Stable partition: unchecked items keep their order, then checked items. */
    fun checkedToBottom(items: List<ChecklistItem>): List<ChecklistItem> =
        normalize(items.filter { !it.isChecked } + items.filter { it.isChecked })

    fun clearCompleted(items: List<ChecklistItem>): List<ChecklistItem> = normalize(items.filter { !it.isChecked })

    fun uncheckAll(items: List<ChecklistItem>): List<ChecklistItem> = normalize(items.map { it.copy(isChecked = false) })

    fun progress(items: List<ChecklistItem>): ChecklistProgress =
        ChecklistProgress(items.count { it.isChecked }, items.size)

    /** "[x] item" / "[ ] item" lines, the format used when converting and exporting. */
    fun toText(items: List<ChecklistItem>): String =
        items.joinToString("\n") { (if (it.isChecked) "[x] " else "[ ] ") + it.text }

    private val checkboxPrefix = Regex("^\\s*(?:[-*+]\\s+)?\\[([ xX])]\\s?")
    private val bulletPrefix = Regex("^\\s*[-*+•]\\s+")

    /** Each non-blank line becomes an item; "[x]" / "- [ ]" / bullet prefixes are understood. */
    fun fromText(text: String, noteId: Long = 0): List<ChecklistItem> =
        normalize(
            text.lines().filter { it.isNotBlank() }.map { line ->
                val box = checkboxPrefix.find(line)
                if (box != null) {
                    ChecklistItem(noteId = noteId, text = line.substring(box.range.last + 1).trim(), isChecked = box.groupValues[1].isNotBlank())
                } else {
                    ChecklistItem(noteId = noteId, text = line.replace(bulletPrefix, "").trim())
                }
            },
        )

    /** Gives unsaved items unique negative ids so UI lists can key them. */
    fun withTemporaryIds(items: List<ChecklistItem>): List<ChecklistItem> {
        var next = -1L
        val used = items.map { it.id }.toMutableSet()
        return items.map { item ->
            if (item.id != 0L) {
                item
            } else {
                while (next in used) next--
                used += next
                item.copy(id = next--)
            }
        }
    }
}
