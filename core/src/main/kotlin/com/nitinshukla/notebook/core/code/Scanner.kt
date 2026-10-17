package com.nitinshukla.notebook.core.code

/**
 * Base for the single-pass tokenizers. Subclasses advance [pos] through [text] up to [limit]
 * and call [emit]; [emit] drops empty, PLAIN, out-of-order and out-of-bounds tokens so the
 * output always satisfies the ordering and bounds invariants of [SyntaxHighlighter.tokenize].
 */
internal abstract class Scanner(protected val text: CharSequence, protected val limit: Int) {
    val tokens = ArrayList<Token>(limit / 6 + 16)

    /** Start offsets of strings and block comments that were not closed before the end of the text. */
    val unterminatedStrings = ArrayList<Int>()
    val unterminatedComments = ArrayList<Int>()

    protected var pos = 0
    private var lastEnd = 0

    abstract fun scan()

    protected fun emit(start: Int, end: Int, type: TokenType) {
        val e = minOf(end, limit)
        if (type == TokenType.PLAIN || start < lastEnd || e <= start || start < 0) return
        tokens.add(Token(start, e, type))
        lastEnd = e
    }

    protected fun peek(offset: Int = 0): Char {
        val i = pos + offset
        return if (i in 0 until limit) text[i] else NONE
    }

    protected fun charAt(i: Int): Char = if (i in 0 until limit) text[i] else NONE

    protected fun startsWith(s: String, at: Int = pos): Boolean {
        if (s.isEmpty() || at < 0 || at + s.length > limit) return false
        for (k in s.indices) if (text[at + k] != s[k]) return false
        return true
    }

    protected fun startsWithIgnoreCase(s: String, at: Int = pos): Boolean {
        if (s.isEmpty() || at < 0 || at + s.length > limit) return false
        for (k in s.indices) if (text[at + k].lowercaseChar() != s[k].lowercaseChar()) return false
        return true
    }

    /** Index of the next '\n' at or after [from], or [limit]. */
    protected fun lineEnd(from: Int = pos): Int {
        var i = from
        while (i < limit && text[i] != '\n') i++
        return i
    }

    /** Index of the first character of the line containing [i]. */
    protected fun lineStart(i: Int): Int {
        var k = minOf(i, limit)
        while (k > 0 && text[k - 1] != '\n') k--
        return k
    }

    /** Index of [s] at or after [from], or -1 when it does not occur before [limit]. */
    protected fun indexOf(s: String, from: Int): Int {
        val first = s[0]
        var i = maxOf(from, 0)
        val last = limit - s.length
        while (i <= last) {
            if (text[i] == first && startsWith(s, i)) return i
            i++
        }
        return -1
    }

    protected fun slice(start: Int, end: Int): String = text.subSequence(start, minOf(end, limit)).toString()

    protected fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u000C'

    protected fun isHorizontalSpace(c: Char): Boolean = c == ' ' || c == '\t'

    protected fun isDigit(c: Char): Boolean = c in '0'..'9'

    protected fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    protected fun isIdentStart(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c == '_' || (c.code > 127 && c.isLetter())

    protected fun isIdentPart(c: Char): Boolean = isIdentStart(c) || isDigit(c)

    protected fun isWordChar(c: Char): Boolean = isIdentPart(c)

    /** Skips the horizontal and vertical whitespace at [pos]. */
    protected fun skipSpaces() {
        while (pos < limit && isSpace(text[pos])) pos++
    }

    /** First non-horizontal-space index at or after [from]. */
    protected fun skipHorizontal(from: Int): Int {
        var i = from
        while (i < limit && isHorizontalSpace(text[i])) i++
        return i
    }

    /** Scans a single-line comment starting at [pos] to the end of the line. */
    protected fun scanLineComment() {
        val start = pos
        pos = lineEnd(pos)
        emit(start, pos, TokenType.COMMENT)
    }

    /** Scans a block comment whose opener is at [pos]; unterminated comments extend to the end. */
    protected fun scanBlockComment(open: String, close: String, nested: Boolean = false) {
        val start = pos
        pos += open.length
        var depth = 1
        while (pos < limit) {
            if (startsWith(close)) {
                pos += close.length
                depth--
                if (depth == 0) {
                    emit(start, pos, TokenType.COMMENT)
                    return
                }
            } else if (nested && startsWith(open)) {
                pos += open.length
                depth++
            } else {
                pos++
            }
        }
        unterminatedComments.add(start)
        emit(start, limit, TokenType.COMMENT)
    }

    /**
     * Scans a string delimited by [quote] starting at [pos] (which may point at a prefix before
     * the quote, in which case [quoteAt] is the index of the opening quote). Backslash escapes
     * are honoured unless [escapes] is false; [doubled] treats two consecutive quotes as an
     * escaped quote (SQL). When [spanLines] is false an unterminated string ends at the line end.
     */
    protected fun scanQuoted(
        quote: Char,
        type: TokenType = TokenType.STRING,
        spanLines: Boolean = false,
        escapes: Boolean = true,
        doubled: Boolean = false,
        quoteAt: Int = pos,
    ) {
        val start = pos
        var i = quoteAt + 1
        while (i < limit) {
            val c = text[i]
            if (escapes && c == '\\') {
                i += 2
                continue
            }
            if (c == quote) {
                if (doubled && i + 1 < limit && text[i + 1] == quote) {
                    i += 2
                    continue
                }
                pos = i + 1
                emit(start, pos, type)
                return
            }
            if (c == '\n' && !spanLines) break
            i++
        }
        unterminatedStrings.add(start)
        pos = if (spanLines) limit else minOf(i, limit)
        emit(start, pos, type)
    }

    /** Scans a string delimited by the multi-character [delimiter] (e.g. triple quotes) starting at [pos]. */
    protected fun scanDelimited(open: String, close: String, type: TokenType = TokenType.STRING, escapes: Boolean = false) {
        val start = pos
        var i = pos + open.length
        while (i < limit) {
            val c = text[i]
            if (escapes && c == '\\') {
                i += 2
                continue
            }
            if (c == close[0] && startsWith(close, i)) {
                pos = i + close.length
                emit(start, pos, type)
                return
            }
            i++
        }
        unterminatedStrings.add(start)
        pos = limit
        emit(start, pos, type)
    }

    /**
     * Scans a number literal starting at [pos] (a digit, or '.' followed by a digit): decimal with
     * underscores, fraction, exponent, 0x/0b/0o prefixes, and any trailing suffix letters.
     */
    protected fun scanNumber(): Boolean {
        val start = pos
        var i = pos
        val c0 = text[i]
        if (c0 == '0' && i + 1 < limit && (text[i + 1] == 'x' || text[i + 1] == 'X' || text[i + 1] == 'b' || text[i + 1] == 'B' || text[i + 1] == 'o' || text[i + 1] == 'O')) {
            i += 2
            while (i < limit && (isHexDigit(text[i]) || text[i] == '_')) i++
        } else {
            while (i < limit && (isDigit(text[i]) || text[i] == '_')) i++
            if (i < limit && text[i] == '.' && i + 1 < limit && isDigit(text[i + 1])) {
                i++
                while (i < limit && (isDigit(text[i]) || text[i] == '_')) i++
            } else if (i < limit && text[i] == '.' && i > start && !(i + 1 < limit && (text[i + 1] == '.' || isIdentStart(text[i + 1])))) {
                i++ // "1." style literal
            }
            if (i < limit && (text[i] == 'e' || text[i] == 'E')) {
                var j = i + 1
                if (j < limit && (text[j] == '+' || text[j] == '-')) j++
                if (j < limit && isDigit(text[j])) {
                    i = j
                    while (i < limit && isDigit(text[i])) i++
                }
            }
        }
        while (i < limit && isIdentPart(text[i])) i++ // suffixes: L, f, u32, j, n ...
        if (i == start) return false
        pos = i
        emit(start, pos, TokenType.NUMBER)
        return true
    }

    /** Scans an identifier at [pos] and returns its end (pos is not moved). */
    protected fun identifierEnd(from: Int = pos, extra: (Char) -> Boolean = { false }): Int {
        var i = from
        while (i < limit && (isIdentPart(text[i]) || extra(text[i]))) i++
        return i
    }

    /** Index of the next character that is not a space or tab at or after [from] (may be [limit]). */
    protected fun nextNonSpace(from: Int): Int {
        var i = from
        while (i < limit && isSpace(text[i])) i++
        return i
    }

    protected fun isAllCaps(start: Int, end: Int): Boolean {
        if (end - start < 2) return false
        var letters = 0
        for (k in start until end) {
            val c = text[k]
            if (c in 'A'..'Z') letters++ else if (c != '_' && !isDigit(c)) return false
        }
        return letters > 0
    }

    companion object {
        const val NONE = '\u0000'
    }
}
