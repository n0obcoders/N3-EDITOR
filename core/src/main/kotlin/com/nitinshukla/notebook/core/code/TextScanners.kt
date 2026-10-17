package com.nitinshukla.notebook.core.code

/** Markdown: headings, fenced and inline code, emphasis, links, bullets and quotes. */
internal class MarkdownScanner(text: CharSequence, limit: Int) : Scanner(text, limit) {
    override fun scan() {
        var ls = 0
        while (ls < limit) {
            val le = lineEnd(ls)
            val first = skipHorizontal(ls)
            if (first < le && (startsWith("```", first) || startsWith("~~~", first))) {
                scanFence(first, le)
                ls = pos + 1
                continue
            }
            scanLine(ls, first, le)
            ls = le + 1
        }
    }

    private fun scanFence(start: Int, firstLineEnd: Int) {
        val fence = slice(start, start + 3)
        var ls = firstLineEnd + 1
        while (ls < limit) {
            val le = lineEnd(ls)
            val f = skipHorizontal(ls)
            if (startsWith(fence, f)) {
                pos = le
                emit(start, le, TokenType.STRING)
                return
            }
            ls = le + 1
        }
        pos = limit
        emit(start, limit, TokenType.STRING)
    }

    private fun scanLine(ls: Int, first: Int, le: Int) {
        pos = first
        if (first >= le) return
        val c = text[first]
        if (c == '#') {
            var i = first
            while (i < le && text[i] == '#' && i - first < 6) i++
            if (i >= le || isSpace(text[i])) { emit(first, le, TokenType.HEADING); pos = le; return }
        }
        if (c == '>') { emit(first, le, TokenType.COMMENT); pos = le; return }
        if ((c == '-' || c == '*' || c == '_') && isRule(first, le)) { emit(first, le, TokenType.PUNCTUATION); pos = le; return }
        if ((c == '-' || c == '*' || c == '+') && first + 1 < le && isSpace(text[first + 1])) {
            emit(first, first + 1, TokenType.PUNCTUATION)
            pos = first + 1
            // Task list checkbox.
            val b = skipHorizontal(pos)
            if (startsWith("[ ]", b) || startsWith("[x]", b) || startsWith("[X]", b)) { emit(b, b + 3, TokenType.PUNCTUATION); pos = b + 3 }
        } else if (isDigit(c)) {
            var i = first
            while (i < le && isDigit(text[i]) && i - first < 9) i++
            if (i < le && (text[i] == '.' || text[i] == ')') && i + 1 < le && isSpace(text[i + 1])) { emit(first, i + 1, TokenType.PUNCTUATION); pos = i + 1 }
        } else if (first - ls >= 4 || (first > ls && text[ls] == '\t')) {
            emit(first, le, TokenType.STRING) // indented code block
            pos = le
            return
        }
        scanInline(le)
    }

    private fun isRule(first: Int, le: Int): Boolean {
        val ch = text[first]
        var count = 0
        for (k in first until le) {
            if (text[k] == ch) count++ else if (!isHorizontalSpace(text[k])) return false
        }
        return count >= 3
    }

    private fun scanInline(le: Int) {
        while (pos < le) {
            val before = pos
            val c = text[pos]
            when {
                c == '\\' -> pos += 2
                c == '`' -> scanInlineCode(le)
                c == '*' || c == '_' -> scanEmphasis(le)
                c == '[' || (c == '!' && peek(1) == '[') -> scanLink(le)
                c == '<' && (startsWith("<http") || isAutoLink(le)) -> {
                    var i = pos
                    while (i < le && text[i] != '>') i++
                    pos = if (i < le) i + 1 else before + 1
                    if (i < le) emit(before, pos, TokenType.LINK)
                }
                c == 'h' && (startsWith("http://") || startsWith("https://")) && !isWordChar(peek(-1)) -> scanUrl(le)
                else -> pos++
            }
            if (pos == before) pos++
        }
    }

    private fun isAutoLink(le: Int): Boolean {
        var i = pos + 1
        var sawAt = false
        while (i < le && i - pos < 100) {
            val ch = text[i]
            if (ch == '>') return sawAt
            if (ch == '@') sawAt = true
            if (isSpace(ch)) return false
            i++
        }
        return false
    }

    private fun scanUrl(le: Int) {
        val start = pos
        var i = pos
        while (i < le && !isSpace(text[i]) && text[i] != '>' && text[i] != ')' && text[i] != ']' && text[i] != '"') i++
        while (i > start && (text[i - 1] == '.' || text[i - 1] == ',' || text[i - 1] == ';' || text[i - 1] == ':' || text[i - 1] == '!' || text[i - 1] == '?')) i--
        pos = i
        emit(start, pos, TokenType.LINK)
    }

    private fun scanInlineCode(le: Int) {
        val start = pos
        var ticks = 0
        while (pos < le && text[pos] == '`') { ticks++; pos++ }
        var i = pos
        while (i < le) {
            if (text[i] == '`') {
                var n = 0
                while (i + n < le && text[i + n] == '`') n++
                if (n == ticks) { pos = i + n; emit(start, pos, TokenType.STRING); return }
                i += n
            } else {
                i++
            }
        }
        pos = start + ticks // no closing run: leave as plain
    }

    private fun scanEmphasis(le: Int) {
        val start = pos
        val ch = text[pos]
        var n = 0
        while (pos < le && text[pos] == ch && n < 3) { n++; pos++ }
        val next = charAt(pos)
        if (pos >= le || isSpace(next)) return // "* " is not emphasis
        if (ch == '_' && isWordChar(charAt(start - 1))) return // snake_case
        val marker = slice(start, pos)
        var i = pos
        while (i < le) {
            if (text[i] == ch && startsWith(marker, i) && !isSpace(text[i - 1])) {
                if (ch == '_' && isWordChar(charAt(i + n))) { i++; continue }
                pos = i + n
                emit(start, pos, TokenType.KEYWORD)
                return
            }
            if (text[i] == '`' || text[i] == '\\') { i += if (text[i] == '\\') 2 else 1; continue }
            i++
        }
        // Not closed on this line: leave plain.
    }

    private fun scanLink(le: Int) {
        val start = pos
        var i = pos + if (text[pos] == '!') 2 else 1
        var depth = 1
        while (i < le && depth > 0) {
            when (text[i]) {
                '[' -> depth++
                ']' -> depth--
                '\\' -> i++
            }
            i++
        }
        if (depth != 0) { pos = start + 1; return }
        // ](url) or ][ref]
        if (i < le && (text[i] == '(' || text[i] == '[')) {
            val close = if (text[i] == '(') ')' else ']'
            var j = i + 1
            var d = 1
            while (j < le && d > 0) {
                val cj = text[j]
                if (cj == text[i]) d++ else if (cj == close) d--
                j++
            }
            if (d == 0) { pos = j; emit(start, pos, TokenType.LINK); return }
        }
        if (start + 1 < i && text[start] == '[' && i - start <= 4 && (slice(start, i) == "[ ]" || slice(start, i).lowercase() == "[x]")) {
            pos = i
            emit(start, pos, TokenType.PUNCTUATION)
            return
        }
        pos = i
        emit(start, pos, TokenType.LINK) // [shortcut reference] or footnote
    }
}

/** Log files: timestamps, levels, exceptions, IPs and URLs. */
internal class LogScanner(text: CharSequence, limit: Int) : Scanner(text, limit) {
    override fun scan() {
        while (pos < limit) {
            val before = pos
            val c = text[pos]
            when {
                isDigit(c) && !isWordChar(peek(-1)) -> scanNumberish()
                isIdentStart(c) && !isWordChar(peek(-1)) -> scanWord()
                else -> pos++
            }
            if (pos == before) pos++
        }
    }

    /** A timestamp (2024-01-02, 12:34:56.789, 1700000000), an IP address, or a plain number. */
    private fun scanNumberish() {
        val start = pos
        var i = pos
        var dots = 0
        var colons = 0
        var dashes = 0
        var letters = 0
        while (i < limit) {
            val ch = text[i]
            when {
                isDigit(ch) -> {}
                ch == '.' && isDigit(charAt(i + 1)) -> dots++
                ch == ':' && isDigit(charAt(i + 1)) -> colons++
                ch == '-' && isDigit(charAt(i + 1)) && dots == 0 -> dashes++
                ch == '/' && isDigit(charAt(i + 1)) && dots == 0 -> dashes++
                ch == 'T' && colons == 0 && dashes == 2 && isDigit(charAt(i + 1)) -> letters++
                ch == ',' && colons == 2 && isDigit(charAt(i + 1)) -> dots++
                ch == 'Z' && colons >= 1 -> { i++; break }
                else -> break
            }
            i++
        }
        pos = i
        if (isIdentPart(charAt(i)) && charAt(i) != 'Z') { // part of an identifier such as 2fa or 0x1F
            while (pos < limit && isIdentPart(text[pos])) pos++
            return
        }
        when {
            dots == 3 && colons == 0 && dashes == 0 -> emit(start, pos, TokenType.LINK) // IPv4
            colons >= 2 || dashes >= 2 || letters > 0 -> emit(start, pos, TokenType.NUMBER) // time or date
            i - start >= 10 && dots == 0 && colons == 0 && dashes == 0 -> emit(start, pos, TokenType.NUMBER) // epoch
            charAt(start - 1) == '[' && charAt(pos) == ']' -> emit(start, pos, TokenType.NUMBER)
            else -> emit(start, pos, TokenType.NUMBER)
        }
    }

    private fun scanWord() {
        val start = pos
        var i = pos
        while (i < limit && isIdentPart(text[i])) i++
        if (i < limit && text[i] == ':' && (startsWith("://", i))) {
            // URL
            var j = i + 3
            while (j < limit && !isSpace(text[j]) && text[j] != '"' && text[j] != '\'' && text[j] != '>' && text[j] != ')' && text[j] != ']') j++
            while (j > i + 3 && (text[j - 1] == '.' || text[j - 1] == ',' || text[j - 1] == ';')) j--
            pos = j
            emit(start, pos, TokenType.LINK)
            return
        }
        pos = i
        val w = slice(start, i)
        val upper = w.uppercase()
        when {
            w == upper && (w == "ERROR" || w == "FATAL" || w == "SEVERE" || w == "CRITICAL" || w == "PANIC" || w == "EXCEPTION" || w == "FAIL" || w == "FAILED" || w == "ERR") -> emit(start, i, TokenType.ERROR)
            w == upper && (w == "WARN" || w == "WARNING") -> emit(start, i, TokenType.KEYWORD)
            w == upper && (w == "INFO" || w == "DEBUG" || w == "TRACE" || w == "VERBOSE" || w == "NOTICE" || w == "FINE") -> emit(start, i, TokenType.COMMENT)
            (w.endsWith("Exception") || w.endsWith("Error")) && w.length > 5 && w[0].isUpperCase() -> emit(start, i, TokenType.ERROR)
            w == "Exception" || w == "Error" || w == "Caused" -> emit(start, i, TokenType.ERROR)
            w == "Warning" -> emit(start, i, TokenType.KEYWORD)
            w == "Info" || w == "Debug" -> emit(start, i, TokenType.COMMENT)
            w == "true" || w == "false" || w == "null" || w == "TRUE" || w == "FALSE" || w == "NULL" -> emit(start, i, TokenType.CONSTANT)
        }
    }
}
