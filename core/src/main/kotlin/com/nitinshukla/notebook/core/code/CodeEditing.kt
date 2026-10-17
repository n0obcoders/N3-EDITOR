package com.nitinshukla.notebook.core.code

/** The result of an editor command: the new text and selection (UTF-16 offsets). */
data class TextEdit(val text: String, val selectionStart: Int, val selectionEnd: Int)

/** Editor helpers; all return the new text and selection. Indices are UTF-16 offsets as in a Kotlin String. */
object CodeEditing {

    /** Index of the first character of the line containing [offset]. */
    fun lineStartOf(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    /** Index of the '\n' ending the line containing [offset], or text.length. */
    fun lineEndOf(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun leadingWhitespace(text: String, lineStart: Int): String {
        var i = lineStart
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.substring(lineStart, i)
    }

    private fun isWord(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * Text to insert for Enter at [cursor]: newline + the current line's leading whitespace, plus
     * one more indent level when the line ends with an opener ('{', '(', '['; ':' for Python).
     * Between a bracket pair the closer is moved to its own line and the cursor placed on the
     * indented middle line.
     */
    fun newLine(text: String, cursor: Int, language: Language): TextEdit {
        val c = cursor.coerceIn(0, text.length)
        val ls = lineStartOf(text, c)
        val indent = leadingWhitespace(text, ls).let { if (ls + it.length > c) it.substring(0, c - ls) else it }
        val unit = language.indentUnit
        var before = c
        while (before > ls && (text[before - 1] == ' ' || text[before - 1] == '\t')) before--
        val last = if (before > ls) text[before - 1] else '\u0000'
        val structural = language != Language.PLAIN && language != Language.MARKDOWN && language != Language.LOG
        val opensBlock = structural && (last == '{' || last == '(' || last == '[' || (last == ':' && language == Language.PYTHON))
        val next = if (c < text.length) text[c] else '\u0000'
        val between = structural && c > 0 && ((text[c - 1] == '{' && next == '}') || (text[c - 1] == '(' && next == ')') || (text[c - 1] == '[' && next == ']'))
        val insert = if (between) "\n$indent$unit\n$indent" else if (opensBlock) "\n$indent$unit" else "\n$indent"
        val newText = text.substring(0, c) + insert + text.substring(c)
        val newCursor = if (between) c + 1 + indent.length + unit.length else c + insert.length
        return TextEdit(newText, newCursor, newCursor)
    }

    /** First and last line start of the lines touched by a selection; a selection ending at a line start excludes that line. */
    private fun lineRange(text: String, selectionStart: Int, selectionEnd: Int): Pair<Int, Int> {
        val s = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val e = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val first = lineStartOf(text, s)
        var last = lineStartOf(text, e)
        if (last > first && last == e) last = lineStartOf(text, e - 1)
        return first to last
    }

    private fun lineStarts(text: String, first: Int, last: Int): List<Int> {
        val starts = ArrayList<Int>()
        var ls = first
        while (true) {
            starts.add(ls)
            if (ls >= last) break
            ls = lineEndOf(text, ls) + 1
            if (ls > text.length) break
        }
        return starts
    }

    /** Inserts an indent at the cursor, or indents every selected line when the selection spans more than one line. */
    fun indent(text: String, selectionStart: Int, selectionEnd: Int, language: Language): TextEdit {
        val s = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val e = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val unit = language.indentUnit
        val (first, last) = lineRange(text, s, e)
        if (first == last) {
            val newText = text.substring(0, s) + unit + text.substring(e)
            return TextEdit(newText, s + unit.length, s + unit.length)
        }
        val sb = StringBuilder(text.length + 64)
        var newStart = s
        var newEnd = e
        var prev = 0
        for (ls in lineStarts(text, first, last)) {
            val le = lineEndOf(text, ls)
            sb.append(text, prev, ls)
            if (le > ls) { // skip blank lines
                sb.append(unit)
                if (ls < s) newStart += unit.length else if (ls == s && s > first) newStart += unit.length
                if (ls < e) newEnd += unit.length
            }
            prev = ls
        }
        sb.append(text, prev, text.length)
        return TextEdit(sb.toString(), newStart, newEnd)
    }

    /** Removes one indent level from the current or selected lines. */
    fun outdent(text: String, selectionStart: Int, selectionEnd: Int, language: Language): TextEdit {
        val s = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val e = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val (first, last) = lineRange(text, s, e)
        val sb = StringBuilder(text.length)
        var newStart = s
        var newEnd = e
        var prev = 0
        for (ls in lineStarts(text, first, last)) {
            var remove = 0
            if (ls < text.length && text[ls] == '\t') remove = 1
            else while (remove < language.indentWidth && ls + remove < text.length && text[ls + remove] == ' ') remove++
            sb.append(text, prev, ls)
            prev = ls + remove
            if (remove > 0) {
                if (s > ls) newStart -= minOf(remove, s - ls)
                if (e > ls) newEnd -= minOf(remove, e - ls)
            }
        }
        sb.append(text, prev, text.length)
        return TextEdit(sb.toString(), newStart.coerceAtLeast(0), maxOf(newEnd, newStart).coerceAtLeast(0))
    }

    /** Toggles the line comment on the current/selected lines (block comment when the language has no line comment; no-op with neither). */
    fun toggleComment(text: String, selectionStart: Int, selectionEnd: Int, language: Language): TextEdit {
        val s = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val e = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val marker = language.lineComment
        if (marker != null) return toggleLineComment(text, s, e, marker)
        val block = language.blockComment ?: return TextEdit(text, s, e)
        return toggleBlockComment(text, s, e, block.first, block.second)
    }

    private fun toggleLineComment(text: String, s: Int, e: Int, marker: String): TextEdit {
        val (first, last) = lineRange(text, s, e)
        val starts = lineStarts(text, first, last)
        val bare = marker.trimEnd()
        var allCommented = true
        var anyContent = false
        var minIndent = Int.MAX_VALUE
        for (ls in starts) {
            val le = lineEndOf(text, ls)
            val ws = leadingWhitespace(text, ls)
            if (ls + ws.length >= le) continue // blank
            anyContent = true
            minIndent = minOf(minIndent, ws.length)
            if (!text.regionMatches(ls + ws.length, bare, 0, bare.length, ignoreCase = true)) allCommented = false
        }
        if (!anyContent) {
            // Comment an empty line anyway.
            val ls = starts[0]
            val ws = leadingWhitespace(text, ls)
            val insert = if (marker.endsWith(" ")) marker else "$marker "
            val at = ls + ws.length
            return TextEdit(text.substring(0, at) + insert + text.substring(at), s + insert.length, e + insert.length)
        }
        val sb = StringBuilder(text.length + 64)
        var newStart = s
        var newEnd = e
        var prev = 0
        for (ls in starts) {
            val le = lineEndOf(text, ls)
            val ws = leadingWhitespace(text, ls)
            if (ls + ws.length >= le) continue
            if (allCommented) {
                val at = ls + ws.length
                var remove = bare.length
                if (at + remove < le && text[at + remove] == ' ') remove++
                sb.append(text, prev, at)
                prev = at + remove
                if (s > at) newStart -= minOf(remove, s - at)
                if (e > at) newEnd -= minOf(remove, e - at)
            } else {
                val at = ls + minOf(minIndent, ws.length)
                val insert = if (marker.endsWith(" ")) marker else "$marker "
                sb.append(text, prev, at).append(insert)
                prev = at
                if (s > at || (s == at && (ls != first || first == last))) newStart += insert.length
                if (e > at) newEnd += insert.length
            }
        }
        sb.append(text, prev, text.length)
        return TextEdit(sb.toString(), newStart.coerceAtLeast(0), maxOf(newEnd, newStart))
    }

    private fun toggleBlockComment(text: String, s: Int, e: Int, open: String, close: String): TextEdit {
        var from = s
        var to = e
        if (from == to) {
            from = lineStartOf(text, s) + leadingWhitespace(text, lineStartOf(text, s)).length
            to = lineEndOf(text, s)
            if (from > to) from = to
        }
        // Selection sits inside the markers: "/* |text| */".
        var o = from
        while (o > 0 && text[o - 1] == ' ') o--
        var cl = to
        while (cl < text.length && text[cl] == ' ') cl++
        if (o >= open.length && text.regionMatches(o - open.length, open, 0, open.length) && text.regionMatches(cl, close, 0, close.length)) {
            val openAt = o - open.length
            val closeEnd = cl + close.length
            val newText = text.substring(0, openAt) + text.substring(from, to) + text.substring(closeEnd)
            return TextEdit(newText, openAt, openAt + (to - from))
        }
        val inner = text.substring(from, to)
        val trimmed = inner.trim()
        if (trimmed.startsWith(open) && trimmed.endsWith(close) && trimmed.length >= open.length + close.length) {
            val openAt = from + inner.indexOf(open)
            var openLen = open.length
            if (openAt + openLen < to && text[openAt + openLen] == ' ') openLen++
            val closeAt = from + inner.lastIndexOf(close)
            var closeFrom = closeAt
            if (closeFrom - 1 > openAt + openLen && text[closeFrom - 1] == ' ') closeFrom--
            val newText = text.substring(0, openAt) + text.substring(openAt + openLen, closeFrom) + text.substring(closeAt + close.length)
            return TextEdit(newText, openAt, closeFrom - openLen)
        }
        val newText = text.substring(0, from) + open + " " + inner + " " + close + text.substring(to)
        return TextEdit(newText, from + open.length + 1, to + open.length + 1)
    }

    private fun closerFor(c: Char): Char? = when (c) {
        '(' -> ')'
        '[' -> ']'
        '{' -> '}'
        '"' -> '"'
        '\'' -> '\''
        '`' -> '`'
        else -> null
    }

    /**
     * For a typed opening bracket or quote: the pair to insert with the cursor between, or null
     * when auto-close should not happen (next char is a word char, quote typed inside a string
     * or after a word character, or the language does not use that quote).
     */
    fun autoClose(text: String, cursor: Int, typed: Char, language: Language): TextEdit? {
        val c = cursor.coerceIn(0, text.length)
        val closer = closerFor(typed) ?: return null
        if (language == Language.PLAIN || language == Language.MARKDOWN || language == Language.LOG) {
            if (typed == '\'' || typed == '`' || typed == '"') return null
        }
        val next = if (c < text.length) text[c] else '\u0000'
        if (isWord(next)) return null
        val isQuote = typed == '"' || typed == '\'' || typed == '`'
        if (isQuote) {
            if (next == typed) return null
            if (typed == '`' && language !in backtickLanguages) return null
            if (typed == '\'' && language == Language.RUST) return null
            val prev = if (c > 0) text[c - 1] else '\u0000'
            if (isWord(prev) && !(language == Language.PYTHON && prev.lowercaseChar() in "rbfu" && (c < 2 || !isWord(text[c - 2])))) return null
            if (prev == typed) return null
            // Inside a string on this line? Count unescaped quotes of this kind before the cursor.
            val ls = lineStartOf(text, c)
            var count = 0
            var i = ls
            while (i < c) {
                val ch = text[i]
                if (ch == '\\') { i += 2; continue }
                if (ch == typed) count++
                i++
            }
            if (count % 2 == 1) return null
        } else if (next != '\u0000' && next != ' ' && next != '\t' && next != '\n' && next != '\r' && next != ')' && next != ']' && next != '}' && next != ',' && next != ';' && next != ':' && next != '.' && next != '>' && next != '<' && next != '"' && next != '\'' && next != '`' && next != '|' && next != '&' && next != '=' && next != '+' && next != '-' && next != '*' && next != '/') {
            return null
        }
        val newText = text.substring(0, c) + typed + closer + text.substring(c)
        return TextEdit(newText, c + 1, c + 1)
    }

    private val backtickLanguages = setOf(Language.JAVASCRIPT, Language.TYPESCRIPT, Language.GO, Language.SHELL, Language.MARKDOWN, Language.SQL, Language.RUBY, Language.MAKEFILE)

    /** Index of the bracket matching the one at or before [cursor], or -1 (plain counting; strings are not skipped). */
    fun matchingBracket(text: String, cursor: Int): Int {
        val c = cursor.coerceIn(0, text.length)
        var at = -1
        if (c < text.length && isBracket(text[c])) at = c
        else if (c > 0 && isBracket(text[c - 1])) at = c - 1
        if (at < 0) return -1
        val ch = text[at]
        val open: Char
        val close: Char
        val forward: Boolean
        when (ch) {
            '(' -> { open = '('; close = ')'; forward = true }
            '[' -> { open = '['; close = ']'; forward = true }
            '{' -> { open = '{'; close = '}'; forward = true }
            ')' -> { open = '('; close = ')'; forward = false }
            ']' -> { open = '['; close = ']'; forward = false }
            else -> { open = '{'; close = '}'; forward = false }
        }
        var depth = 0
        if (forward) {
            var i = at
            while (i < text.length) {
                val x = text[i]
                if (x == open) depth++ else if (x == close) { depth--; if (depth == 0) return i }
                i++
            }
        } else {
            var i = at
            while (i >= 0) {
                val x = text[i]
                if (x == close) depth++ else if (x == open) { depth--; if (depth == 0) return i }
                i--
            }
        }
        return -1
    }

    private fun isBracket(c: Char) = c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}'

    /** Duplicates the line under the cursor below itself; the cursor keeps its column on the copy. */
    fun duplicateLine(text: String, cursor: Int): TextEdit {
        val c = cursor.coerceIn(0, text.length)
        val ls = lineStartOf(text, c)
        val le = lineEndOf(text, c)
        val line = text.substring(ls, le)
        val newText = text.substring(0, le) + "\n" + line + text.substring(le)
        val newCursor = c + 1 + line.length
        return TextEdit(newText, newCursor, newCursor)
    }

    /** Deletes the line under the cursor including its line break; the cursor keeps its column on the line that takes its place. */
    fun deleteLine(text: String, cursor: Int): TextEdit {
        if (text.isEmpty()) return TextEdit(text, 0, 0)
        val c = cursor.coerceIn(0, text.length)
        val ls = lineStartOf(text, c)
        val le = lineEndOf(text, c)
        val column = c - ls
        val newText: String
        val newLineStart: Int
        if (le < text.length) {
            newText = text.substring(0, ls) + text.substring(le + 1)
            newLineStart = ls
        } else if (ls > 0) {
            newText = text.substring(0, ls - 1)
            newLineStart = lineStartOf(newText, newText.length)
        } else {
            return TextEdit("", 0, 0)
        }
        val newCursor = minOf(newLineStart + column, lineEndOf(newText, newLineStart))
        return TextEdit(newText, newCursor, newCursor)
    }

    /** Swaps the line under the cursor with the one above or below; the cursor moves with it. Unchanged at the edges. */
    fun moveLine(text: String, cursor: Int, up: Boolean): TextEdit {
        val c = cursor.coerceIn(0, text.length)
        val ls = lineStartOf(text, c)
        val le = lineEndOf(text, c)
        val column = c - ls
        val line = text.substring(ls, le)
        if (up) {
            if (ls == 0) return TextEdit(text, c, c)
            val pls = lineStartOf(text, ls - 1)
            val prevLine = text.substring(pls, ls - 1)
            val newText = text.substring(0, pls) + line + "\n" + prevLine + text.substring(le)
            val nc = pls + column
            return TextEdit(newText, nc, nc)
        } else {
            if (le >= text.length) return TextEdit(text, c, c)
            val nle = lineEndOf(text, le + 1)
            val nextLine = text.substring(le + 1, nle)
            val newText = text.substring(0, ls) + nextLine + "\n" + line + text.substring(nle)
            val nc = ls + nextLine.length + 1 + column
            return TextEdit(newText, nc, nc)
        }
    }

    /** Line number (1-based) and column (1-based, tabs count as 1) for an offset. */
    fun position(text: String, offset: Int): Pair<Int, Int> {
        val o = offset.coerceIn(0, text.length)
        var line = 1
        var lineStart = 0
        for (i in 0 until o) {
            if (text[i] == '\n') { line++; lineStart = i + 1 }
        }
        return line to (o - lineStart + 1)
    }

    /** Offset of the start of a 1-based line number (clamped to the first and last line). */
    fun offsetOfLine(text: String, line: Int): Int {
        if (line <= 1) return 0
        var current = 1
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                current++
                if (current == line) return i + 1
            }
            i++
        }
        return lineStartOf(text, text.length)
    }
}
