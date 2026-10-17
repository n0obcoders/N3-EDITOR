package com.nitinshukla.notebook.core.code

/** HTML and XML: tags, attributes, attribute strings, comments, entities; script/style bodies are left plain in HTML. */
internal class MarkupScanner(text: CharSequence, limit: Int, private val html: Boolean) : Scanner(text, limit) {
    override fun scan() {
        while (pos < limit) {
            val before = pos
            val c = text[pos]
            if (c == '<') {
                when {
                    startsWith("<!--") -> scanBlockComment("<!--", "-->")
                    startsWith("<![CDATA[") -> scanDelimited("<![CDATA[", "]]>")
                    peek(1) == '!' || peek(1) == '?' -> scanDeclaration()
                    isIdentStart(peek(1)) || (peek(1) == '/' && isIdentStart(peek(2))) -> scanTag()
                    else -> pos++
                }
            } else if (c == '&') {
                scanEntity()
            } else {
                pos++
            }
            if (pos == before) pos++
        }
    }

    private fun scanDeclaration() {
        val start = pos
        var i = pos + 2
        while (i < limit && text[i] != '>') i++
        pos = minOf(i + 1, limit)
        emit(start, pos, TokenType.DECORATOR)
    }

    private fun scanEntity() {
        val start = pos
        var i = pos + 1
        if (charAt(i) == '#') i++
        val nameStart = i
        while (i < limit && isIdentPart(text[i]) && i - nameStart < 12) i++
        if (i > nameStart && charAt(i) == ';') {
            pos = i + 1
            emit(start, pos, TokenType.CONSTANT)
        } else {
            pos++
        }
    }

    private fun scanTag() {
        val tagStart = pos
        pos++
        val closing = peek() == '/'
        if (closing) pos++
        emit(tagStart, pos, TokenType.PUNCTUATION)
        val nameStart = pos
        pos = identifierEnd(pos) { it == '-' || it == ':' || it == '.' }
        val name = slice(nameStart, pos)
        emit(nameStart, pos, TokenType.TAG)
        // Attributes until '>' or '/>'.
        while (pos < limit) {
            val c = text[pos]
            when {
                isSpace(c) -> pos++
                c == '>' -> { pos++; emit(pos - 1, pos, TokenType.PUNCTUATION); break }
                c == '/' && peek(1) == '>' -> { pos += 2; emit(pos - 2, pos, TokenType.PUNCTUATION); break }
                c == '<' -> break // malformed: a new tag starts
                c == '=' -> { pos++; emit(pos - 1, pos, TokenType.OPERATOR) }
                c == '"' || c == '\'' -> scanQuoted(c, spanLines = true, escapes = false)
                isIdentStart(c) || c == '@' || c == ':' || c == '*' || c == '[' || c == '(' || c == '#' -> {
                    val s = pos
                    pos++
                    while (pos < limit && (isIdentPart(text[pos]) || text[pos] == '-' || text[pos] == ':' || text[pos] == '.' || text[pos] == '@' || text[pos] == ']' || text[pos] == ')')) pos++
                    emit(s, pos, TokenType.ATTRIBUTE)
                }
                else -> pos++
            }
        }
        if (html && !closing && (name.equals("script", true) || name.equals("style", true))) {
            val close = "</$name"
            var i = pos
            while (i < limit) {
                if (text[i] == '<' && startsWithIgnoreCase(close, i)) break
                i++
            }
            pos = i
        }
    }
}

/** CSS/SCSS/LESS: selectors as TAG, properties as ATTRIBUTE, numbers with units, hex colours, !important, at-rules. */
internal class CssScanner(text: CharSequence, limit: Int) : Scanner(text, limit) {
    private var depth = 0

    override fun scan() {
        while (pos < limit) {
            val before = pos
            val c = text[pos]
            when {
                isSpace(c) -> pos++
                c == '/' && peek(1) == '*' -> scanBlockComment("/*", "*/")
                c == '/' && peek(1) == '/' -> scanLineComment()
                c == '"' || c == '\'' -> scanQuoted(c)
                c == '{' -> { depth++; pos++; emit(before, pos, TokenType.PUNCTUATION) }
                c == '}' -> { if (depth > 0) depth--; pos++; emit(before, pos, TokenType.PUNCTUATION) }
                c == ';' || c == ',' || c == '(' || c == ')' || c == '[' || c == ']' -> { pos++; emit(before, pos, TokenType.PUNCTUATION) }
                c == '@' && isIdentStart(peek(1)) -> { pos = identifierEnd(pos + 1) { it == '-' }; emit(before, pos, TokenType.KEYWORD) }
                c == '!' && startsWithIgnoreCase("!important") -> { pos += 10; emit(before, pos, TokenType.KEYWORD) }
                c == '#' && isHexDigit(peek(1)) && depth > 0 -> {
                    var i = pos + 1
                    while (i < limit && isHexDigit(text[i])) i++
                    if (!isIdentPart(charAt(i))) { pos = i; emit(before, pos, TokenType.CONSTANT) } else scanSelectorToken()
                }
                isDigit(c) || ((c == '.' || c == '-' || c == '+') && isDigit(peek(1))) -> scanCssNumber()
                c == '$' && isIdentStart(peek(1)) -> { pos = identifierEnd(pos + 1) { it == '-' }; emit(before, pos, TokenType.VARIABLE) }
                c == '-' && peek(1) == '-' && isIdentStart(peek(2)) -> { pos = identifierEnd(pos + 2) { it == '-' }; emit(before, pos, TokenType.VARIABLE) }
                depth == 0 && (c == '.' || c == '#' || c == '*' || c == '&' || isIdentStart(c) || c == ':' || c == '[' || c == '%') -> scanSelectorToken()
                depth > 0 && (isIdentStart(c) || c == '-') -> scanDeclarationWord()
                depth > 0 && (c == '.' || c == '#' || c == '&' || c == '*') && !isIdentPart(peek(-1)) && peek(-1) != ')' -> scanSelectorToken()
                c == ':' || c == '>' || c == '+' || c == '~' || c == '=' || c == '*' || c == '/' -> { pos++; emit(before, pos, TokenType.OPERATOR) }
                else -> pos++
            }
            if (pos == before) pos++
        }
    }

    private fun scanCssNumber() {
        val start = pos
        var i = pos
        if (text[i] == '-' || text[i] == '+') i++
        while (i < limit && isDigit(text[i])) i++
        if (i < limit && text[i] == '.') { i++; while (i < limit && isDigit(text[i])) i++ }
        if (i < limit && (text[i] == 'e' || text[i] == 'E') && (isDigit(charAt(i + 1)) || ((charAt(i + 1) == '-' || charAt(i + 1) == '+') && isDigit(charAt(i + 2))))) {
            i += 2
            while (i < limit && isDigit(text[i])) i++
        }
        while (i < limit && (text[i].isLetter() || text[i] == '%')) i++ // unit
        pos = i
        emit(start, pos, TokenType.NUMBER)
    }

    /** A selector piece: tag, .class, #id, :pseudo, [attr], & etc. */
    private fun scanSelectorToken() {
        val start = pos
        if (text[pos] == '[') {
            var i = pos + 1
            while (i < limit && text[i] != ']' && text[i] != '\n' && text[i] != '{') i++
            pos = if (charAt(i) == ']') i + 1 else i
            emit(start, pos, TokenType.TAG)
            return
        }
        if (text[pos] == ':') {
            var i = pos + 1
            if (charAt(i) == ':') i++
            i = identifierEnd(i) { it == '-' }
            pos = i
            emit(start, pos, TokenType.TAG)
            return
        }
        pos++
        while (pos < limit && (isIdentPart(text[pos]) || text[pos] == '-' || text[pos] == '%')) pos++
        emit(start, pos, TokenType.TAG)
    }

    /** Inside a block: a property name before ':' (ATTRIBUTE), a nested selector (TAG), or a plain value word. */
    private fun scanDeclarationWord() {
        val start = pos
        var i = pos
        while (i < limit && (isIdentPart(text[i]) || text[i] == '-')) i++
        if (i == pos) { pos++; return }
        val end = i
        // Look ahead (bounded) for the first structural character to decide property vs selector.
        var k = end
        var sawColon = false
        var kind = 'v' // value word
        while (k < limit && k - end < 512) {
            val ch = text[k]
            if (ch == ':' && !sawColon && k == skipHorizontal(end)) sawColon = true
            if (ch == ';' || ch == '}') { kind = if (sawColon) 'p' else 'v'; break }
            if (ch == '{') { kind = 's'; break }
            if (ch == '\n' && !sawColon) { kind = 'v'; break }
            k++
        }
        if (kind == 'v' && sawColon) kind = 'p'
        pos = end
        when (kind) {
            'p' -> emit(start, end, TokenType.ATTRIBUTE)
            's' -> emit(start, end, TokenType.TAG)
            else -> if (charAt(end) == '(') emit(start, end, TokenType.FUNCTION) else if (slice(start, end) in cssKeywords) emit(start, end, TokenType.CONSTANT)
        }
    }

    private companion object {
        val cssKeywords = setOf("inherit", "initial", "unset", "none", "auto", "important", "transparent", "currentColor", "solid", "block", "inline", "flex", "grid", "absolute", "relative", "fixed", "sticky", "bold", "normal", "italic", "center", "left", "right", "hidden", "visible", "pointer", "ease", "linear", "infinite", "cover", "contain", "nowrap", "wrap", "column", "row", "border-box", "content-box")
    }
}

/** JSON and JSONC: keys as ATTRIBUTE, strings, numbers, true/false/null as CONSTANT, comments. */
internal class JsonScanner(text: CharSequence, limit: Int) : Scanner(text, limit) {
    override fun scan() {
        while (pos < limit) {
            val before = pos
            val c = text[pos]
            when {
                isSpace(c) -> pos++
                c == '"' -> {
                    scanQuoted('"')
                    val t = tokens.lastOrNull()
                    if (t != null && t.start == before && charAt(nextNonSpace(pos)) == ':') tokens[tokens.lastIndex] = t.copy(type = TokenType.ATTRIBUTE)
                }
                c == '\'' -> scanQuoted('\'')
                c == '/' && peek(1) == '/' -> scanLineComment()
                c == '/' && peek(1) == '*' -> scanBlockComment("/*", "*/")
                isDigit(c) || (c == '-' && (isDigit(peek(1)) || peek(1) == '.')) || (c == '.' && isDigit(peek(1))) -> {
                    if (c == '-') pos++
                    if (!scanNumber()) pos = before + 1
                    tokens.lastOrNull()?.let { if (it.start == before + 1 && c == '-') tokens[tokens.lastIndex] = it.copy(start = before) }
                }
                c == '{' || c == '}' || c == '[' || c == ']' || c == ',' || c == ':' -> { pos++; emit(before, pos, TokenType.PUNCTUATION) }
                isIdentStart(c) -> {
                    pos = identifierEnd(pos)
                    val w = slice(before, pos)
                    if (w == "true" || w == "false" || w == "null") emit(before, pos, TokenType.CONSTANT)
                    else if (w == "NaN" || w == "Infinity") emit(before, pos, TokenType.NUMBER)
                    else if (charAt(nextNonSpace(pos)) == ':') emit(before, pos, TokenType.ATTRIBUTE)
                    else emit(before, pos, TokenType.ERROR)
                }
                else -> pos++
            }
            if (pos == before) pos++
        }
    }
}

/** YAML: keys, comments, quoted strings, anchors/aliases/tags, booleans and null, document markers. */
internal class YamlScanner(text: CharSequence, limit: Int) : Scanner(text, limit) {
    override fun scan() {
        var ls = 0
        while (ls < limit) {
            val le = lineEnd(ls)
            scanLine(ls, le)
            ls = le + 1
        }
    }

    private fun scanLine(ls: Int, le: Int) {
        pos = skipHorizontal(ls)
        if (pos >= le) return
        if (pos == ls && (startsWith("---") || startsWith("...")) && (charAt(pos + 3) == NONE || isSpace(charAt(pos + 3)))) {
            pos += 3
            emit(ls, pos, TokenType.PUNCTUATION)
        }
        // Block sequence bullets, possibly several ("- - a").
        while (pos < le && text[pos] == '-' && (pos + 1 >= le || isSpace(text[pos + 1]))) {
            emit(pos, pos + 1, TokenType.PUNCTUATION)
            pos = skipHorizontal(pos + 1)
        }
        var inFlow = 0
        var expectKey = true
        while (pos < le) {
            val before = pos
            val c = text[pos]
            when {
                isHorizontalSpace(c) -> pos++
                c == '#' && (pos == ls || isSpace(text[pos - 1]) || inFlow > 0 && isSpace(text[pos - 1])) -> { emit(pos, le, TokenType.COMMENT); pos = le }
                c == '"' -> { scanQuoted('"', spanLines = true); fixKey(before) ; expectKey = false }
                c == '\'' -> { scanQuoted('\'', spanLines = true, escapes = false, doubled = true); fixKey(before); expectKey = false }
                c == '&' || c == '*' -> { pos = identifierEnd(pos + 1) { it == '-' || it == '.' }; emit(before, pos, TokenType.VARIABLE); expectKey = false }
                c == '!' -> { pos = identifierEnd(pos + 1) { it == '!' || it == '-' || it == '/' || it == ':' }; emit(before, pos, TokenType.DECORATOR) }
                c == '{' || c == '[' -> { inFlow++; pos++; emit(before, pos, TokenType.PUNCTUATION); expectKey = true }
                c == '}' || c == ']' -> { if (inFlow > 0) inFlow--; pos++; emit(before, pos, TokenType.PUNCTUATION); expectKey = false }
                c == ',' && inFlow > 0 -> { pos++; emit(before, pos, TokenType.PUNCTUATION); expectKey = true }
                c == ':' && (pos + 1 >= le || isSpace(text[pos + 1]) || inFlow > 0) -> { pos++; emit(before, pos, TokenType.PUNCTUATION); expectKey = false }
                (c == '|' || c == '>') && (pos + 1 >= le || isSpace(text[pos + 1]) || text[pos + 1] == '-' || text[pos + 1] == '+' || isDigit(text[pos + 1])) -> {
                    pos++
                    while (pos < le && !isSpace(text[pos])) pos++
                    emit(before, pos, TokenType.OPERATOR)
                    expectKey = false
                }
                else -> scanScalar(le, inFlow, expectKey).also { if (it) expectKey = false }
            }
            if (pos == before) pos++
        }
    }

    /** A quoted scalar directly followed by ':' is a key. */
    private fun fixKey(start: Int) {
        val t = tokens.lastOrNull() ?: return
        if (t.start == start && charAt(pos) == ':' && (isSpace(charAt(pos + 1)) || charAt(pos + 1) == NONE)) tokens[tokens.lastIndex] = t.copy(type = TokenType.ATTRIBUTE)
    }

    /** Scans an unquoted scalar; returns true when it was a key (the caller then expects a value). */
    private fun scanScalar(le: Int, inFlow: Int, expectKey: Boolean): Boolean {
        val start = pos
        var i = pos
        var colon = -1
        while (i < le) {
            val ch = text[i]
            if (ch == ':' && (i + 1 >= le || isSpace(text[i + 1]) || (inFlow > 0 && (text[i + 1] == ',' || text[i + 1] == '}')))) { colon = i; break }
            if (ch == '#' && i > 0 && isSpace(text[i - 1])) break
            if (inFlow > 0 && (ch == ',' || ch == '}' || ch == ']')) break
            i++
        }
        if (colon >= 0 && expectKey) {
            var e = colon
            while (e > start && isHorizontalSpace(text[e - 1])) e--
            pos = colon
            emit(start, e, TokenType.ATTRIBUTE)
            return true
        }
        var e = i
        while (e > start && isHorizontalSpace(text[e - 1])) e--
        pos = maxOf(e, start + 1)
        val word = slice(start, e)
        when {
            word in yamlConstants -> emit(start, e, TokenType.CONSTANT)
            looksNumeric(word) -> emit(start, e, TokenType.NUMBER)
            word.startsWith("http://") || word.startsWith("https://") -> emit(start, e, TokenType.LINK)
        }
        return false
    }

    private fun looksNumeric(w: String): Boolean {
        if (w.isEmpty() || w.length > 40) return false
        var i = 0
        if (w[0] == '-' || w[0] == '+') i = 1
        if (i >= w.length) return false
        var digits = 0
        while (i < w.length) {
            val ch = w[i]
            if (isDigit(ch)) digits++
            else if (ch != '.' && ch != '_' && ch != 'e' && ch != 'E' && ch != '-' && ch != ':' && ch != 'x' && ch != 'T' && ch != 'Z' && ch != '+') return false
            i++
        }
        return digits > 0 && isDigit(w[if (w[0] == '-' || w[0] == '+') 1 else 0])
    }

    private companion object {
        val yamlConstants = setOf("true", "false", "null", "yes", "no", "on", "off", "True", "False", "Null", "Yes", "No", "On", "Off", "TRUE", "FALSE", "NULL", "YES", "NO", "ON", "OFF", "~")
    }
}

/** INI, .properties, .env and TOML: sections as HEADING, keys as ATTRIBUTE, values (TOML values are tokenized). */
internal class IniScanner(text: CharSequence, limit: Int, private val toml: Boolean) : Scanner(text, limit) {
    override fun scan() {
        var ls = 0
        while (ls < limit) {
            val le = lineEnd(ls)
            scanLine(ls, le)
            if (pos > le) { ls = lineEnd(pos) + 1 } else ls = le + 1 // multi-line TOML strings may pass the line end
        }
    }

    private fun scanLine(ls: Int, le: Int) {
        pos = skipHorizontal(ls)
        if (pos >= le) return
        val c = text[pos]
        if (c == '#' || (c == ';' && !toml)) { emit(pos, le, TokenType.COMMENT); pos = le; return }
        if (c == '[') {
            var i = pos
            while (i < le && text[i] != ']') i++
            while (i < le && text[i] == ']') i++
            emit(pos, i, TokenType.HEADING)
            pos = i
            scanTrailingComment(le)
            return
        }
        if (c == '"' || c == '\'') {
            // Quoted key (TOML) or a quoted value line.
            scanQuoted(c, escapes = c == '"')
        } else {
            var i = pos
            while (i < le && text[i] != '=' && !(text[i] == ':' && !toml) && text[i] != '#') i++
            var e = i
            while (e > pos && isHorizontalSpace(text[e - 1])) e--
            if (i < le && (text[i] == '=' || text[i] == ':')) {
                emit(pos, e, TokenType.ATTRIBUTE)
            } else if (!toml && e > pos) {
                emit(pos, e, TokenType.ATTRIBUTE) // bare key such as a .gitignore pattern or "export FOO"
            }
            pos = i
        }
        pos = skipHorizontal(pos)
        if (pos < le && (text[pos] == '=' || text[pos] == ':')) { emit(pos, pos + 1, TokenType.OPERATOR); pos++ }
        pos = skipHorizontal(pos)
        if (toml) scanTomlValue(le) else scanIniValue(le)
    }

    private fun scanTrailingComment(le: Int) {
        val i = skipHorizontal(pos)
        if (i < le && (text[i] == '#' || (text[i] == ';' && !toml))) { emit(i, le, TokenType.COMMENT); pos = le }
    }

    private fun scanIniValue(le: Int) {
        if (pos >= le) return
        val c = text[pos]
        when {
            c == '"' || c == '\'' -> scanQuoted(c)
            else -> {
                var e = le
                var i = pos
                while (i < le) { if ((text[i] == '#' || text[i] == ';') && i > 0 && isSpace(text[i - 1])) { e = i; break }; i++ }
                var ve = e
                while (ve > pos && isHorizontalSpace(text[ve - 1])) ve--
                val w = slice(pos, ve)
                if (w.lowercase() in iniConstants) emit(pos, ve, TokenType.CONSTANT)
                else if (w.isNotEmpty() && w.all { isDigit(it) || it == '.' || it == '-' } && isDigit(w.last())) emit(pos, ve, TokenType.NUMBER)
                else if (w.startsWith("http://") || w.startsWith("https://")) emit(pos, ve, TokenType.LINK)
                pos = e
            }
        }
        pos = skipHorizontal(pos)
        if (pos < le && (text[pos] == '#' || text[pos] == ';')) { emit(pos, le, TokenType.COMMENT); pos = le }
    }

    private fun scanTomlValue(le: Int) {
        while (pos < le) {
            val before = pos
            val c = text[pos]
            when {
                isHorizontalSpace(c) -> pos++
                c == '#' -> { emit(pos, le, TokenType.COMMENT); pos = le }
                startsWith("\"\"\"") -> scanDelimited("\"\"\"", "\"\"\"", escapes = true)
                startsWith("'''") -> scanDelimited("'''", "'''")
                c == '"' -> scanQuoted('"')
                c == '\'' -> scanQuoted('\'', escapes = false)
                c == '[' || c == ']' || c == '{' || c == '}' || c == ',' -> { pos++; emit(before, pos, TokenType.PUNCTUATION) }
                c == '=' -> { pos++; emit(before, pos, TokenType.OPERATOR) }
                isDigit(c) || ((c == '-' || c == '+') && (isDigit(peek(1)) || peek(1) == 'i' || peek(1) == 'n')) -> {
                    var i = pos + 1
                    while (i < le && (isIdentPart(text[i]) || text[i] == '.' || text[i] == '-' || text[i] == ':' || text[i] == '+')) i++
                    while (i > pos + 1 && (text[i - 1] == '.' || text[i - 1] == '-' || text[i - 1] == ':')) i--
                    pos = i
                    emit(before, pos, TokenType.NUMBER)
                }
                isIdentStart(c) -> {
                    pos = identifierEnd(pos) { it == '-' || it == '.' }
                    val w = slice(before, pos)
                    if (w == "true" || w == "false" || w == "inf" || w == "nan") emit(before, pos, TokenType.CONSTANT)
                    else if (charAt(skipHorizontal(pos)) == '=') emit(before, pos, TokenType.ATTRIBUTE) // inline table key
                }
                else -> pos++
            }
            if (pos == before) pos++
        }
    }

    private companion object {
        val iniConstants = setOf("true", "false", "yes", "no", "on", "off", "null", "none")
    }
}
