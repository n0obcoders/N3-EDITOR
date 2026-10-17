package com.nitinshukla.notebook.core.text

data class TextSnapshot(val text: String, val selectionStart: Int, val selectionEnd: Int = selectionStart)

/**
 * Bounded undo/redo history. Consecutive small edits within [coalesceWindowMs] are merged
 * into one step so undo works word-by-word rather than keystroke-by-keystroke.
 */
class EditHistory(
    private val maxEntries: Int = 200,
    private val coalesceWindowMs: Long = 1_000,
    /**
     * Snapshots hold the whole text, so a long note could keep tens of megabytes alive and
     * make the garbage collector stall typing. The oldest steps are dropped past this budget.
     */
    private val maxChars: Long = 4_000_000,
) {
    private var storedChars = 0L
    private val undoStack = ArrayDeque<TextSnapshot>()
    private val redoStack = ArrayDeque<TextSnapshot>()
    private var lastPushAt = Long.MIN_VALUE / 2
    private var lastWasWordBoundary = true

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Records [before], the state prior to an edit made at [now]. [after] is used to decide
     * whether the edit continues the current typing run.
     */
    fun record(before: TextSnapshot, after: TextSnapshot, now: Long) {
        if (before.text == after.text) return
        val delta = after.text.length - before.text.length
        val typedChar = if (delta == 1 && after.selectionStart in 1..after.text.length) after.text[after.selectionStart - 1] else null
        val isSmallEdit = delta == 1 || delta == -1
        val continuesRun = isSmallEdit && now - lastPushAt < coalesceWindowMs && !lastWasWordBoundary && undoStack.isNotEmpty()
        if (!continuesRun) {
            undoStack.addLast(before)
            storedChars += before.text.length
            while (undoStack.size > 1 && (undoStack.size > maxEntries || storedChars > maxChars)) {
                storedChars -= undoStack.removeFirst().text.length
            }
        }
        lastWasWordBoundary = typedChar == null || typedChar.isWhitespace() || !isSmallEdit
        lastPushAt = now
        redoStack.forEach { storedChars -= it.text.length }
        redoStack.clear()
    }

    /** Forces the next edit to start a new undo step. */
    fun breakRun() {
        lastWasWordBoundary = true
    }

    fun undo(current: TextSnapshot): TextSnapshot? {
        val previous = undoStack.removeLastOrNull() ?: return null
        storedChars += current.text.length - previous.text.length
        redoStack.addLast(current)
        breakRun()
        return previous
    }

    fun redo(current: TextSnapshot): TextSnapshot? {
        val next = redoStack.removeLastOrNull() ?: return null
        storedChars += current.text.length - next.text.length
        undoStack.addLast(current)
        breakRun()
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        storedChars = 0
        breakRun()
    }
}
