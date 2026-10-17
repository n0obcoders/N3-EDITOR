package com.nitinshukla.notebook.core.code

/** Lexical rules for one language handled by [CLikeScanner]. Word sets are lowercase when [caseInsensitive]. */
internal class CLikeSpec(
    val keywords: Set<String>,
    val types: Set<String> = emptySet(),
    val builtins: Set<String> = emptySet(),
    val constants: Set<String> = emptySet(),
    val variables: Set<String> = emptySet(),
    /** Keywords after which the next identifier is a function name (def, fun, func, fn). */
    val functionAfter: Set<String> = emptySet(),
    /** Keywords after which the next identifier is a type name (class, struct, interface, enum). */
    val typeAfter: Set<String> = emptySet(),
    val lineComments: List<String> = listOf("//"),
    val blockComment: Pair<String, String>? = "/*" to "*/",
    val nestedComments: Boolean = false,
    /** Quote characters that delimit ordinary strings. */
    val stringQuotes: String = "\"",
    /** Whether a single quote delimits a char literal (otherwise it must be in [stringQuotes] to mean anything). */
    val charQuote: Boolean = true,
    val stringsSpanLines: Boolean = false,
    /** Whether a tripled string quote starts a multi-line string. */
    val tripleQuotes: Boolean = false,
    val tripleEscapes: Boolean = true,
    /** `...` strings: null for none, true for template strings with escapes, false for raw strings. */
    val backtickEscapes: Boolean? = null,
    /** Lowercase identifier prefixes allowed directly before a string quote (r, b, f, u, L, u8, R ...). */
    val stringPrefixes: Set<String> = emptySet(),
    val cppRawStrings: Boolean = false,
    val rustRawStrings: Boolean = false,
    val csharpStrings: Boolean = false,
    /** `@name` is a decorator or annotation. */
    val decorators: Boolean = true,
    /** `#` as the first non-blank character of a line starts a preprocessor directive. */
    val preprocessor: Boolean = false,
    val rustAttributes: Boolean = false,
    /** Characters that introduce a variable name ($ in PHP and PowerShell, @ in SQL). */
    val variablePrefixes: String = "",
    val rubyVariables: Boolean = false,
    val rubySymbols: Boolean = false,
    val rubyMethodSuffix: Boolean = false,
    val rubyBlockComments: Boolean = false,
    val caseInsensitive: Boolean = false,
    /** Identifiers may contain '-' between letters (PowerShell cmdlets), and such names are functions. */
    val dashedIdentifiers: Boolean = false,
    /** `-Name` after whitespace is an option (PowerShell); names in [operatorWords] are operators instead. */
    val options: Boolean = false,
    val operatorWords: Set<String> = emptySet(),
    val macroBang: Boolean = false,
    val sqlStrings: Boolean = false,
    /** Characters delimiting quoted identifiers (SQL `"` and backtick), highlighted as VARIABLE. */
    val quotedIdentifiers: String = "",
    val lifetimes: Boolean = false,
    val phpTags: Boolean = false,
    val capitalizedIsType: Boolean = true,
    val allCapsIsConstant: Boolean = true,
    val extraIdentStart: String = "",
)

/** Single-pass tokenizer for the C-like family (and Python, Ruby, SQL, PowerShell, which share most rules). */
internal class CLikeScanner(text: CharSequence, limit: Int, private val spec: CLikeSpec) : Scanner(text, limit) {
    private var lastWord: String? = null

    private fun isStart(c: Char): Boolean = isIdentStart(c) || (spec.extraIdentStart.isNotEmpty() && c in spec.extraIdentStart)
    private fun isPart(c: Char): Boolean = isIdentPart(c) || (spec.extraIdentStart.isNotEmpty() && c in spec.extraIdentStart)

    override fun scan() {
        while (pos < limit) {
            val before = pos
            val c = text[pos]
            when {
                isSpace(c) -> pos++
                spec.blockComment != null && c == spec.blockComment.first[0] && startsWith(spec.blockComment.first) -> {
                    scanBlockComment(spec.blockComment.first, spec.blockComment.second, spec.nestedComments)
                    lastWord = null
                }
                isLineComment(c) -> { scanLineComment(); lastWord = null }
                spec.rubyBlockComments && c == '=' && startsWith("=begin") && atLineStart(pos) -> scanRubyBlockComment()
                spec.rustAttributes && c == '#' && (peek(1) == '[' || (peek(1) == '!' && peek(2) == '[')) -> scanRustAttribute()
                spec.preprocessor && c == '#' && atLineStart(pos) -> scanPreprocessor()
                spec.phpTags && ((c == '<' && (startsWith("<?php") || startsWith("<?="))) || (c == '?' && peek(1) == '>')) -> scanPhpTag(c)
                c == '"' || c == '\'' || c == '`' -> { scanQuoteChar(c); lastWord = null }
                isDigit(c) || (c == '.' && isDigit(peek(1)) && !isPart(peek(-1)) && peek(-1) != '.') -> { scanNumber(); lastWord = null }
                isStart(c) -> scanIdentifier()
                spec.csharpStrings && (c == '@' || c == '$') && isCsharpStringStart() -> { scanCsharpString(); lastWord = null }
                spec.rubyVariables && (c == '@' || c == '$') -> { scanRubyVariable(); lastWord = null }
                spec.variablePrefixes.isNotEmpty() && c in spec.variablePrefixes -> { scanVariable(); lastWord = null }
                spec.decorators && c == '@' && isIdentStart(peek(1)) -> { scanDecorator(); lastWord = null }
                spec.rubySymbols && c == ':' && isIdentStart(peek(1)) && peek(-1) != ':' && !isPart(peek(-1)) -> { scanRubySymbol(); lastWord = null }
                spec.options && c == '-' && (peek(1).isLetter() || (peek(1) == '-' && peek(2).isLetter())) && !isPart(peek(-1)) && peek(-1) != ')' && peek(-1) != ']' -> { scanOption(); lastWord = null }
                c == '(' || c == ')' || c == '{' || c == '}' || c == '[' || c == ']' || c == ',' || c == ';' -> { pos++; emit(before, pos, TokenType.PUNCTUATION); lastWord = null }
                isOperatorChar(c) -> { scanOperator(); lastWord = null }
                else -> { pos++; lastWord = null }
            }
            if (pos == before) pos++
        }
    }

    private fun isOperatorChar(c: Char): Boolean = when (c) {
        '+', '-', '*', '/', '%', '=', '<', '>', '!', '&', '|', '^', '~', '?', ':', '.', '\\' -> true
        else -> false
    }

    private fun scanOperator() {
        val start = pos
        while (pos < limit && isOperatorChar(text[pos])) {
            if (pos > start && (isLineComment(text[pos]) || (spec.blockComment != null && startsWith(spec.blockComment.first)))) break
            pos++
            if (text[pos - 1] == '.' && (isDigit(peek()) || isStart(peek()))) break
        }
        emit(start, pos, TokenType.OPERATOR)
    }

    private fun isLineComment(c: Char): Boolean {
        for (lc in spec.lineComments) if (c == lc[0] && startsWith(lc)) return true
        return false
    }

    private fun atLineStart(i: Int): Boolean {
        var k = i - 1
        while (k >= 0 && isHorizontalSpace(text[k])) k--
        return k < 0 || text[k] == '\n'
    }

    private fun scanRubyBlockComment() {
        val start = pos
        var i = pos
        while (true) {
            val le = lineEnd(i)
            if (i != start && startsWith("=end", i)) { pos = le; break }
            if (le >= limit) { pos = limit; break }
            i = le + 1
        }
        emit(start, pos, TokenType.COMMENT)
        lastWord = null
    }

    private fun scanRustAttribute() {
        val start = pos
        var i = pos
        var depth = 0
        while (i < limit) {
            val ch = text[i]
            if (ch == '[') depth++
            else if (ch == ']') { depth--; if (depth == 0) { i++; break } }
            else if (ch == '\n' && depth == 0) break
            i++
        }
        pos = i
        emit(start, pos, TokenType.DECORATOR)
        lastWord = null
    }

    private fun scanPreprocessor() {
        val start = pos
        var le = lineEnd(pos)
        while (le < limit && le > 0 && text[le - 1] == '\\') le = lineEnd(le + 1)
        pos = le
        emit(start, pos, TokenType.DECORATOR)
        lastWord = null
    }

    private fun scanPhpTag(c: Char) {
        val start = pos
        pos += if (c == '?') 2 else if (startsWith("<?php")) 5 else 3
        emit(start, pos, TokenType.TAG)
        lastWord = null
    }

    private fun scanQuoteChar(c: Char) {
        when (c) {
            '`' -> when {
                spec.backtickEscapes != null -> scanQuoted('`', spanLines = true, escapes = spec.backtickEscapes)
                '`' in spec.quotedIdentifiers -> scanQuoted('`', TokenType.VARIABLE, escapes = false)
                else -> pos++
            }
            '\'' -> when {
                spec.charQuote -> scanCharLiteral()
                '\'' in spec.stringQuotes -> scanStringAt('\'', pos)
                '\'' in spec.quotedIdentifiers -> scanQuoted('\'', TokenType.VARIABLE, escapes = false)
                else -> pos++
            }
            else -> when {
                '"' in spec.stringQuotes -> scanStringAt('"', pos)
                '"' in spec.quotedIdentifiers -> scanQuoted('"', TokenType.VARIABLE, escapes = false)
                else -> pos++
            }
        }
    }

    /** Scans a string whose opening quote [q] is at [quoteAt]; [pos] may point at a prefix. */
    private fun scanStringAt(q: Char, quoteAt: Int, raw: Boolean = false) {
        if (spec.tripleQuotes && charAt(quoteAt + 1) == q && charAt(quoteAt + 2) == q) {
            val triple = "$q$q$q"
            val start = pos
            pos = quoteAt
            scanTripleFrom(start, triple, escapes = spec.tripleEscapes && !raw)
            return
        }
        scanQuoted(q, spanLines = spec.stringsSpanLines, escapes = !(raw && spec.rustRawStrings), doubled = spec.sqlStrings, quoteAt = quoteAt)
    }

    private fun scanTripleFrom(start: Int, triple: String, escapes: Boolean) {
        var i = pos + 3
        while (i < limit) {
            val ch = text[i]
            if (escapes && ch == '\\') { i += 2; continue }
            if (ch == triple[0] && startsWith(triple, i)) {
                // Kotlin/Python allow extra quotes before the closing triple: take the last three.
                var j = i + 3
                while (j < limit && text[j] == triple[0]) j++
                pos = j
                emit(start, pos, TokenType.STRING)
                return
            }
            i++
        }
        unterminatedStrings.add(start)
        pos = limit
        emit(start, pos, TokenType.STRING)
    }

    private fun scanCharLiteral() {
        val start = pos
        var i = pos + 1
        if (i < limit && text[i] == '\\') {
            i++
            if (i < limit && text[i] == 'u' && i + 1 < limit && text[i + 1] == '{') {
                while (i < limit && text[i] != '}' && text[i] != '\n' && i - start < 14) i++
            }
            if (i < limit && text[i] != '\n') i++
            while (i < limit && text[i] != '\'' && text[i] != '\n' && i - start < 14) i++
            if (i < limit && text[i] == '\'') { pos = i + 1; emit(start, pos, TokenType.CHAR); return }
        } else if (i < limit && text[i] != '\n' && text[i] != '\'') {
            var j = i + 1
            if (Character.isHighSurrogate(text[i]) && j < limit) j++
            if (j < limit && text[j] == '\'') { pos = j + 1; emit(start, pos, TokenType.CHAR); return }
        } else if (i < limit && text[i] == '\'') {
            pos = i + 1
            emit(start, pos, TokenType.CHAR)
            return
        }
        if (spec.lifetimes) {
            val e = identifierEnd(pos + 1)
            pos = if (e > pos + 1) e else pos + 1
            emit(start, pos, TokenType.TYPE)
            return
        }
        // Unterminated: extend to the end of the line.
        unterminatedStrings.add(start)
        pos = lineEnd(pos)
        emit(start, pos, TokenType.CHAR)
    }

    private fun isCsharpStringStart(): Boolean {
        val c1 = peek(1)
        if (c1 == '"') return true
        val c0 = peek()
        return (c1 == '@' || c1 == '$') && c1 != c0 && peek(2) == '"'
    }

    private fun scanCsharpString() {
        val start = pos
        var i = pos
        var verbatim = false
        while (i < limit && (text[i] == '@' || text[i] == '$')) { if (text[i] == '@') verbatim = true; i++ }
        if (verbatim) scanQuoted('"', spanLines = true, escapes = false, doubled = true, quoteAt = i)
        else scanQuoted('"', quoteAt = i)
    }

    private fun scanRubyVariable() {
        val start = pos
        var i = pos + 1
        if (i < limit && text[i] == '@') i++
        if (text[pos] == '$' && i < limit && !isIdentStart(text[i])) { pos = i + 1; emit(start, pos, TokenType.VARIABLE); return }
        val e = identifierEnd(i)
        if (e == i) { pos = i; return }
        pos = e
        emit(start, pos, TokenType.VARIABLE)
    }

    private fun scanVariable() {
        val start = pos
        val c1 = peek(1)
        when {
            c1 == '{' -> {
                var i = pos + 2
                while (i < limit && text[i] != '}' && text[i] != '\n') i++
                pos = if (i < limit && text[i] == '}') i + 1 else i
            }
            isIdentStart(c1) -> {
                var e = identifierEnd(pos + 1)
                if (spec.options && charAt(e) == ':' && isIdentStart(charAt(e + 1))) e = identifierEnd(e + 1) // PowerShell $env:NAME
                pos = e
            }
            text[pos] == '$' && (c1 == '_' || c1 == '$' || c1 == '?' || c1 == '^' || isDigit(c1)) -> pos += 2
            else -> { pos++; return }
        }
        emit(start, pos, TokenType.VARIABLE)
    }

    private fun scanDecorator() {
        val start = pos
        var e = identifierEnd(pos + 1)
        while (charAt(e) == '.' && isIdentStart(charAt(e + 1))) e = identifierEnd(e + 1)
        pos = e
        emit(start, pos, TokenType.DECORATOR)
    }

    private fun scanRubySymbol() {
        val start = pos
        var e = identifierEnd(pos + 1)
        if ((charAt(e) == '?' || charAt(e) == '!') && charAt(e + 1) != '=') e++
        pos = e
        emit(start, pos, TokenType.CONSTANT)
    }

    private fun scanOption() {
        val start = pos
        var e = pos + 1
        if (charAt(e) == '-') e++
        while (e < limit && (isIdentPart(text[e]) || text[e] == '-')) e++
        pos = e
        val word = slice(start, e).lowercase()
        emit(start, pos, if (word in spec.operatorWords) TokenType.OPERATOR else TokenType.ATTRIBUTE)
    }

    private fun scanIdentifier() {
        val start = pos
        var end = pos + 1
        while (end < limit && isPart(text[end])) end++
        if (spec.dashedIdentifiers) {
            var dashed = false
            while (charAt(end) == '-' && charAt(end + 1).isLetter()) {
                dashed = true
                end++
                while (end < limit && isPart(text[end])) end++
            }
            if (dashed) { pos = end; emit(start, end, TokenType.FUNCTION); lastWord = null; return }
        }
        if (spec.rubyMethodSuffix && (charAt(end) == '?' || charAt(end) == '!') && charAt(end + 1) != '=') end++

        // String prefixes (r"", b'', f"""""", L"", u8"", R"(...)", r#""#, @"" ...).
        val q = charAt(end)
        if (spec.stringPrefixes.isNotEmpty() && end - start <= 3) {
            val prefix = slice(start, end)
            val lower = prefix.lowercase()
            if (q == '"' || q == '\'' || (q == '#' && spec.rustRawStrings)) {
                if (spec.rustRawStrings && (lower == "r" || lower == "br" || lower == "cr") && q == '#') {
                    if (scanRustRawString(start, end)) return
                } else if (lower in spec.stringPrefixes && (q == '"' || (q == '\'' && ('\'' in spec.stringQuotes || spec.rustRawStrings)))) {
                    if (spec.cppRawStrings && prefix.endsWith("R") && q == '"') { scanCppRawString(start, end); return }
                    if (q == '\'' && spec.charQuote) {
                        pos = end
                        scanCharLiteral()
                        tokens.lastOrNull()?.let { if (it.start == end) { tokens[tokens.lastIndex] = it.copy(start = start) } }
                        lastWord = null
                        return
                    }
                    scanStringAt(q, end, raw = prefix.contains('R') || lower.startsWith("r") || lower.startsWith("br"))
                    lastWord = null
                    return
                }
            }
        }

        pos = end
        val word = slice(start, end)
        val key = if (spec.caseInsensitive) word.lowercase() else word
        val prev = lastWord
        lastWord = key
        val type = classify(key, word, start, end, prev)
        if (type != null) emit(start, pos, type)
    }

    private fun classify(key: String, word: String, start: Int, end: Int, prev: String?): TokenType? {
        if (prev != null) {
            if (prev in spec.functionAfter && key !in spec.keywords && charAt(end) != '.') return TokenType.FUNCTION
            if (prev in spec.typeAfter && key !in spec.keywords) return TokenType.TYPE
        }
        if (spec.macroBang && charAt(end) == '!' && key !in spec.keywords) {
            val after = charAt(end + 1)
            if (after == '(' || after == '[' || after == '{') { pos = end + 1; return TokenType.FUNCTION }
        }
        if (key in spec.keywords) return TokenType.KEYWORD
        if (key in spec.types) return TokenType.TYPE
        if (key in spec.builtins) return TokenType.BUILTIN
        if (key in spec.constants) return TokenType.CONSTANT
        if (key in spec.variables) return TokenType.VARIABLE
        var next = end
        while (next < limit && isHorizontalSpace(text[next])) next++
        if (charAt(next) == '(') return TokenType.FUNCTION
        if (spec.allCapsIsConstant && isAllCaps(start, end)) return TokenType.CONSTANT
        if (spec.capitalizedIsType && word[0] in 'A'..'Z') return TokenType.TYPE
        return null
    }

    private fun scanRustRawString(start: Int, hashAt: Int): Boolean {
        var i = hashAt
        var hashes = 0
        while (i < limit && text[i] == '#') { hashes++; i++ }
        if (charAt(i) != '"') return false
        scanDelimited("\"", "\"" + "#".repeat(hashes))
        tokens.lastOrNull()?.let { tokens[tokens.lastIndex] = it.copy(start = start) }
        return true
    }

    private fun scanCppRawString(start: Int, quoteAt: Int) {
        var i = quoteAt + 1
        val dStart = i
        while (i < limit && text[i] != '(' && text[i] != '\n' && i - dStart < 16) i++
        if (charAt(i) != '(') {
            pos = quoteAt
            scanQuoted('"', quoteAt = quoteAt)
        } else {
            val close = ")" + slice(dStart, i) + "\""
            pos = i
            scanDelimited("(", close)
        }
        tokens.lastOrNull()?.let { if (it.start >= quoteAt) tokens[tokens.lastIndex] = it.copy(start = start) }
        lastWord = null
    }
}
