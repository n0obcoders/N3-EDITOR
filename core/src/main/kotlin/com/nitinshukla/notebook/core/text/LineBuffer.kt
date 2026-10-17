package com.nitinshukla.notebook.core.text

/** A position inside a [LineBuffer]: zero-based line and column (UTF-16 units). */
data class LinePosition(val line: Int, val column: Int)

/** One reversible change: the [removed] lines at [from] were replaced by [inserted]. */
data class LineChange(val from: Int, val removed: List<String>, val inserted: List<String>) {
    fun inverse() = LineChange(from, inserted, removed)
}

/**
 * Text kept as a list of lines so that editing one line of a 50 000-line file costs that one
 * line, not a copy of the whole text. Every mutation goes through [replaceLines], which
 * records a [LineChange] for undo. The buffer never contains fewer than one line and lines
 * never contain '\n'.
 *
 * [lines] may be any mutable list (the app passes a Compose snapshot list so the UI observes
 * exactly the lines that changed).
 */
class LineBuffer(val lines: MutableList<String>, private val historyLimit: Int = 500) {

    private val undoStack = ArrayDeque<LineChange>()
    private val redoStack = ArrayDeque<LineChange>()

    /** Total UTF-16 length of the text including '\n' separators; kept incrementally. */
    var charCount: Long = 0
        private set

    init {
        if (lines.isEmpty()) lines += ""
        charCount = lines.sumOf { it.length.toLong() } + (lines.size - 1)
    }

    val lineCount: Int get() = lines.size
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun text(): String {
        val sb = StringBuilder(charCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        lines.forEachIndexed { i, l -> if (i > 0) sb.append('\n'); sb.append(l) }
        return sb.toString()
    }

    /** Replaces [count] lines starting at [from] with [newLines]; recorded for undo unless [record] is false. */
    fun replaceLines(from: Int, count: Int, newLines: List<String>, record: Boolean = true): LineChange {
        require(from in 0..lines.size && count >= 0 && from + count <= lines.size) { "bad range $from+$count of ${lines.size}" }
        val removed = ArrayList<String>(count)
        for (i in 0 until count) removed += lines[from + i]
        val change = LineChange(from, removed, newLines.toList())
        apply(change)
        if (record) {
            undoStack.addLast(change)
            if (undoStack.size > historyLimit) undoStack.removeFirst()
            redoStack.clear()
        }
        return change
    }

    private fun apply(change: LineChange) {
        val from = change.from
        val removed = change.removed
        val inserted = change.inserted
        // Overwrite in place where both sides have lines, then add or remove the difference,
        // which keeps snapshot-list notifications to the touched indices.
        val common = minOf(removed.size, inserted.size)
        for (i in 0 until common) if (lines[from + i] != inserted[i]) lines[from + i] = inserted[i]
        if (inserted.size > common) lines.addAll(from + common, inserted.subList(common, inserted.size))
        if (removed.size > common) {
            val sub = lines.subList(from + common, from + removed.size)
            sub.clear()
        }
        if (lines.isEmpty()) lines += ""
        charCount += inserted.sumOf { it.length.toLong() } - removed.sumOf { it.length.toLong() } + (inserted.size - removed.size)
        if (charCount < 0) charCount = 0
    }

    fun setLine(index: Int, text: String): LineChange? {
        if (lines[index] == text) return null
        require('\n' !in text) { "use splitLine for newlines" }
        // Coalesce consecutive edits to the same line into one undo step.
        val last = undoStack.lastOrNull()
        if (coalesce && last != null && last.from == index && last.removed.size == 1 && last.inserted.size == 1 && redoStack.isEmpty()) {
            val merged = LineChange(index, last.removed, listOf(text))
            undoStack.removeLast()
            undoStack.addLast(merged)
            apply(LineChange(index, listOf(lines[index]), listOf(text)))
            return merged
        }
        coalesce = true
        return replaceLines(index, 1, listOf(text))
    }

    private var coalesce = true

    /** Splits line [index] at [column]; the second half becomes line index+1. */
    fun splitLine(index: Int, column: Int, firstHalf: String, secondHalf: String): LineChange =
        replaceLines(index, 1, listOf(firstHalf, secondHalf))

    /** Joins line [index] onto the end of the previous one; returns the join column, or -1 when index is 0. */
    fun joinWithPrevious(index: Int): Int {
        if (index <= 0) return -1
        val prev = lines[index - 1]
        replaceLines(index - 1, 2, listOf(prev + lines[index]))
        return prev.length
    }

    fun insertLines(at: Int, newLines: List<String>) = replaceLines(at, 0, newLines)
    fun removeLine(index: Int) = replaceLines(index, 1, emptyList())

    fun swapLines(a: Int, b: Int): Boolean {
        if (a !in lines.indices || b !in lines.indices || a == b) return false
        val lo = minOf(a, b); val hi = maxOf(a, b)
        replaceLines(lo, hi - lo + 1, lines.subList(lo, hi + 1).toMutableList().also { val t = it[0]; it[0] = it[it.lastIndex]; it[it.lastIndex] = t })
        return true
    }

    /** Marks a boundary so the next [setLine] starts a new undo step. */
    fun breakRun() {
        coalesce = false
    }

    fun undo(): LineChange? {
        val change = undoStack.removeLastOrNull() ?: return null
        apply(change.inverse())
        redoStack.addLast(change)
        return change
    }

    fun redo(): LineChange? {
        val change = redoStack.removeLastOrNull() ?: return null
        apply(change)
        undoStack.addLast(change)
        return change
    }

    /** Position of an absolute character [offset] into [text]. O(lines) but allocation free. */
    fun positionOf(offset: Int): LinePosition {
        var remaining = offset.coerceAtLeast(0)
        for ((i, l) in lines.withIndex()) {
            if (remaining <= l.length) return LinePosition(i, remaining)
            remaining -= l.length + 1
        }
        return LinePosition(lines.lastIndex, lines.last().length)
    }

    fun offsetOf(line: Int, column: Int): Int {
        val l = line.coerceIn(0, lines.lastIndex)
        var offset = 0L
        for (i in 0 until l) offset += lines[i].length + 1
        return (offset + column.coerceIn(0, lines[l].length)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Replaces [query] in every line. Returns the number of replacements. Patterns are matched
     * per line, so a match can never span a line break.
     */
    fun replaceAll(replaceLine: (String) -> Pair<String, Int>): Int {
        var total = 0
        val changed = ArrayList<Pair<Int, String>>()
        lines.forEachIndexed { i, l ->
            val (result, n) = replaceLine(l)
            if (n > 0) { total += n; changed += i to result }
        }
        if (changed.isEmpty()) return 0
        // One undo step for the whole operation: record a change covering the touched span.
        val from = changed.first().first
        val to = changed.last().first
        val removed = lines.subList(from, to + 1).toList()
        val inserted = removed.toMutableList()
        for ((i, r) in changed) inserted[i - from] = r
        replaceLines(from, removed.size, inserted)
        return total
    }

    companion object {
        /** Splits text into lines; '\r\n' and '\r' must already be normalized to '\n'. */
        fun split(text: String): MutableList<String> {
            val out = ArrayList<String>(text.length / 32 + 1)
            var start = 0
            while (true) {
                val nl = text.indexOf('\n', start)
                if (nl < 0) { out += text.substring(start); break }
                out += text.substring(start, nl)
                start = nl + 1
            }
            return out
        }
    }
}
