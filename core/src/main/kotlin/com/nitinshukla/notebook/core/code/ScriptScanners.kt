package com.nitinshukla.notebook.core.code

/** POSIX shell / bash, and Dockerfiles (whose instruction words are keywords and whose arguments are shell-like). */
internal class ShellScanner(text: CharSequence, limit: Int, private val dockerfile: Boolean) : Scanner(text, limit) {
    /** True at the start of a command (line start or after ; | & && || ( ` or a keyword such as then/do). */
    private var commandStart = true
    private var dockerInstruction = false

    override fun scan() {
        while (pos < limit) {
            val before = pos
            val c = text[pos]
            when {
                c == '\n' -> { pos++; commandStart = true; dockerInstruction = false }
                isSpace(c) -> pos++
                c == '#' && (pos == 0 || isSpace(text[pos - 1]) || text[pos - 1] == ';' || text[pos - 1] == '(') -> scanLineComment()
                c == '"' -> scanDoubleQuoted()
                c == '\'' -> scanQuoted('\'', spanLines = true, escapes = false)
                c == '`' -> { pos++; emit(before, pos, TokenType.PUNCTUATION); commandStart = true }
                c == '\\' -> pos += 2
                c == '$' -> scanDollar()
                c == '-' && (peek(1).isLetterOrDigit() || peek(1) == '-') && !commandStart && (pos == 0 || isSpace(text[pos - 1]) || text[pos - 1] == '=') -> scanOption()
                isIdentStart(c) -> scanWord()
                isDigit(c) && !isWordChar(peek(-1)) -> {
                    var i = pos
                    while (i < limit && isDigit(text[i])) i++
                    if (!isIdentPart(charAt(i)) && charAt(i) != '.') { pos = i; emit(before, pos, TokenType.NUMBER) } else { pos = i; while (pos < limit && (isIdentPart(text[pos]) || text[pos] == '.')) pos++ }
                }
                c == ';' || c == '|' || c == '&' -> {
                    pos++
                    while (pos < limit && (text[pos] == ';' || text[pos] == '|' || text[pos] == '&')) pos++
                    emit(before, pos, TokenType.PUNCTUATION)
                    commandStart = true
                }
                c == '(' || c == ')' || c == '{' || c == '}' || c == '[' || c == ']' -> { pos++; emit(before, pos, TokenType.PUNCTUATION); if (c == '(' || c == '{') commandStart = true }
                c == '<' || c == '>' || c == '=' || c == '!' -> {
                    pos++
                    while (pos < limit && (text[pos] == '<' || text[pos] == '>' || text[pos] == '&' || text[pos] == '=')) pos++
                    emit(before, pos, TokenType.OPERATOR)
                }
                else -> pos++
            }
            if (pos == before) pos++
        }
    }

    private fun scanDoubleQuoted() {
        val start = pos
        var i = pos + 1
        while (i < limit) {
            val ch = text[i]
            if (ch == '\\') { i += 2; continue }
            if (ch == '"') { pos = i + 1; emit(start, pos, TokenType.STRING); return }
            i++
        }
        unterminatedStrings.add(start)
        pos = limit
        emit(start, pos, TokenType.STRING)
    }

    private fun scanDollar() {
        val start = pos
        val c1 = peek(1)
        when {
            c1 == '{' -> {
                var i = pos + 2
                var depth = 1
                while (i < limit && depth > 0 && text[i] != '\n') { if (text[i] == '{') depth++ else if (text[i] == '}') depth--; i++ }
                pos = i
            }
            c1 == '(' -> { pos += 2; emit(start, pos, TokenType.VARIABLE); commandStart = true; return }
            isIdentStart(c1) -> pos = identifierEnd(pos + 1)
            isDigit(c1) || c1 == '?' || c1 == '@' || c1 == '*' || c1 == '#' || c1 == '!' || c1 == '$' || c1 == '-' || c1 == '_' -> pos += 2
            c1 == '\'' || c1 == '"' -> { pos++; return }
            else -> { pos++; return }
        }
        emit(start, pos, TokenType.VARIABLE)
        commandStart = false
    }

    private fun scanOption() {
        val start = pos
        var i = pos + 1
        while (i < limit && (isIdentPart(text[i]) || text[i] == '-')) i++
        pos = i
        emit(start, pos, TokenType.ATTRIBUTE)
    }

    private fun scanWord() {
        val start = pos
        var i = pos
        while (i < limit && (isIdentPart(text[i]) || text[i] == '-' || text[i] == '.' || text[i] == '/' || text[i] == '+')) i++
        val word = slice(start, i)
        pos = i
        if (dockerfile && commandStart && atLineStart(start)) {
            if (word.uppercase() in dockerInstructions) {
                emit(start, i, TokenType.KEYWORD)
                dockerInstruction = word.uppercase() != "RUN"
                commandStart = word.uppercase() == "RUN"
                return
            }
        }
        if (dockerInstruction) {
            if (charAt(i) == '=' && word.all { isIdentPart(it) }) emit(start, i, TokenType.VARIABLE)
            return
        }
        // Assignment: NAME=value at command position.
        if (commandStart && charAt(i) == '=' && word.all { isIdentPart(it) } && charAt(i + 1) != '=') { emit(start, i, TokenType.VARIABLE); return }
        if (charAt(i) == '+' && charAt(i + 1) == '=' && word.all { isIdentPart(it) }) { emit(start, i, TokenType.VARIABLE); return }
        // Function definition: name() or name ().
        if (charAt(skipHorizontal(i)) == '(' && charAt(skipHorizontal(i) + 1) == ')' && word.all { isIdentPart(it) }) { emit(start, i, TokenType.FUNCTION); return }
        if (word in keywords) {
            emit(start, i, TokenType.KEYWORD)
            commandStart = word in commandStartAfter
            return
        }
        if (commandStart && word in builtins) { emit(start, i, TokenType.BUILTIN); commandStart = false; return }
        if (word == "function" || word == "select" || word == "time") { emit(start, i, TokenType.KEYWORD); return }
        commandStart = false
    }

    private fun atLineStart(i: Int): Boolean {
        var k = i - 1
        while (k >= 0 && isHorizontalSpace(text[k])) k--
        return k < 0 || text[k] == '\n'
    }

    private companion object {
        val keywords = setOf("if", "then", "fi", "for", "do", "done", "case", "esac", "function", "while", "until", "elif", "else", "select", "in", "time", "coproc")
        val commandStartAfter = setOf("if", "then", "do", "elif", "else", "while", "until", "time", "coproc", "function")
        val builtins = setOf("echo", "cd", "export", "source", "exit", "read", "local", "return", "set", "unset", "printf", "test", "alias", "break", "continue", "declare", "eval", "exec", "shift", "trap", "type", "ulimit", "umask", "wait", "pwd", "pushd", "popd", "let", "readonly", "typeset", "sudo", "true", "false", "command", "builtin")
        val dockerInstructions = setOf("FROM", "RUN", "CMD", "LABEL", "EXPOSE", "ENV", "ADD", "COPY", "ENTRYPOINT", "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK", "SHELL", "MAINTAINER", "AS")
    }
}

/** Makefiles: targets, variables, directives; recipe lines are tokenized shell-style for strings and variables. */
internal class MakefileScanner(text: CharSequence, limit: Int) : Scanner(text, limit) {
    override fun scan() {
        var ls = 0
        while (ls < limit) {
            val le = lineEnd(ls)
            scanLine(ls, le)
            ls = le + 1
        }
    }

    private fun scanLine(ls: Int, le: Int) {
        pos = ls
        if (pos >= le) return
        val recipe = text[ls] == '\t'
        if (!recipe) {
            val first = skipHorizontal(ls)
            if (first < le && text[first] == '#') { emit(first, le, TokenType.COMMENT); pos = le; return }
            // Directive, variable assignment, or target.
            var i = first
            while (i < le && (isIdentPart(text[i]) || text[i] == '.' || text[i] == '-' || text[i] == '/' || text[i] == '%' || text[i] == '$' || text[i] == '(' || text[i] == ')')) i++
            val word = slice(first, i)
            if (word.isNotEmpty()) {
                val afterWord = skipHorizontal(i)
                val a = charAt(afterWord)
                val b = charAt(afterWord + 1)
                when {
                    word in directives -> { emit(first, i, TokenType.KEYWORD); pos = i }
                    a == '=' || ((a == ':' || a == '?' || a == '+' || a == '!') && b == '=') -> {
                        emit(first, i, TokenType.VARIABLE)
                        pos = afterWord + (if (a == '=') 1 else 2)
                        emit(afterWord, pos, TokenType.OPERATOR)
                    }
                    word.startsWith("$") -> pos = first
                    else -> {
                        // Target list up to ':'.
                        var j = first
                        while (j < le && text[j] != ':' && text[j] != '#' && text[j] != '=') j++
                        if (j < le && text[j] == ':' && charAt(j + 1) != '=') {
                            var e = j
                            while (e > first && isHorizontalSpace(text[e - 1])) e--
                            emitTargets(first, e)
                            emit(j, j + 1, TokenType.PUNCTUATION)
                            pos = j + 1
                        } else {
                            pos = first
                        }
                    }
                }
            }
        }
        scanRest(le, recipe)
    }

    private fun emitTargets(start: Int, end: Int) {
        var s = start
        while (s < end) {
            while (s < end && isHorizontalSpace(text[s])) s++
            var e = s
            while (e < end && !isHorizontalSpace(text[e])) e++
            if (e > s) emit(s, e, if (text[s] == '.' && slice(s, e) in specialTargets) TokenType.KEYWORD else TokenType.FUNCTION)
            s = e
        }
    }

    private fun scanRest(le: Int, recipe: Boolean) {
        var first = true
        while (pos < le) {
            val before = pos
            val c = text[pos]
            when {
                isHorizontalSpace(c) -> pos++
                c == '\\' -> pos += 2
                c == '#' -> { emit(pos, le, TokenType.COMMENT); pos = le }
                c == '$' && (peek(1) == '(' || peek(1) == '{') -> {
                    val close = if (peek(1) == '(') ')' else '}'
                    var i = pos + 2
                    var depth = 1
                    while (i < le && depth > 0) { if (text[i] == text[pos + 1]) depth++ else if (text[i] == close) depth--; i++ }
                    pos = i
                    emit(before, pos, TokenType.VARIABLE)
                }
                c == '$' && pos + 1 < le -> { pos += 2; emit(before, pos, TokenType.VARIABLE) }
                c == '"' -> scanQuoted('"')
                c == '\'' -> scanQuoted('\'', escapes = false)
                recipe && first && (c == '@' || c == '-' || c == '+') -> { pos++; emit(before, pos, TokenType.OPERATOR) }
                c == '-' && recipe && (pos == 0 || isSpace(text[pos - 1])) && (peek(1).isLetterOrDigit() || peek(1) == '-') -> {
                    var i = pos + 1
                    while (i < le && (isIdentPart(text[i]) || text[i] == '-')) i++
                    pos = i
                    emit(before, pos, TokenType.ATTRIBUTE)
                }
                isIdentStart(c) -> {
                    pos = identifierEnd(pos) { it == '-' || it == '.' || it == '/' }
                    val w = slice(before, pos)
                    if (!recipe && w in directives) emit(before, pos, TokenType.KEYWORD)
                    else if (recipe && first && w in shellBuiltins) emit(before, pos, TokenType.BUILTIN)
                }
                c == '|' || c == ';' || c == '&' || c == '(' || c == ')' || c == ',' -> { pos++; emit(before, pos, TokenType.PUNCTUATION) }
                c == '>' || c == '<' || c == '=' -> { pos++; emit(before, pos, TokenType.OPERATOR) }
                else -> pos++
            }
            if (!isHorizontalSpace(c)) first = first && (c == '@' || c == '-' || c == '+') && recipe
            if (pos == before) pos++
        }
    }

    private companion object {
        val directives = setOf("ifeq", "ifneq", "ifdef", "ifndef", "else", "endif", "include", "-include", "sinclude", "define", "endef", "export", "unexport", "override", "vpath", "undefine", "private")
        val specialTargets = setOf(".PHONY", ".SUFFIXES", ".DEFAULT", ".PRECIOUS", ".INTERMEDIATE", ".SECONDARY", ".DELETE_ON_ERROR", ".IGNORE", ".SILENT", ".EXPORT_ALL_VARIABLES", ".NOTPARALLEL", ".ONESHELL", ".POSIX", ".DEFAULT_GOAL")
        val shellBuiltins = setOf("echo", "cd", "rm", "mkdir", "cp", "mv", "test", "exit", "printf", "touch", "cat", "install")
    }
}

/** Windows batch files: REM/:: comments, %VAR% and !VAR!, :labels, commands as keywords, /options. */
internal class BatchScanner(text: CharSequence, limit: Int) : Scanner(text, limit) {
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
        if (text[pos] == '@') { emit(pos, pos + 1, TokenType.OPERATOR); pos++ }
        if (startsWith("::") || (startsWithIgnoreCase("rem") && (pos + 3 >= le || isSpace(text[pos + 3])))) { emit(pos, le, TokenType.COMMENT); pos = le; return }
        if (text[pos] == ':') {
            var i = pos + 1
            while (i < le && !isSpace(text[i])) i++
            emit(pos, i, TokenType.FUNCTION)
            pos = le
            return
        }
        var commandPos = true
        while (pos < le) {
            val before = pos
            val c = text[pos]
            when {
                isHorizontalSpace(c) -> pos++
                c == '%' -> scanPercent(le)
                c == ':' && isIdentStart(peek(1)) && isSpace(text[pos - 1]) -> {
                    var i = pos + 1
                    while (i < le && !isSpace(text[i])) i++
                    pos = i
                    emit(before, pos, TokenType.FUNCTION)
                }
                c == '!' && isIdentStart(peek(1)) -> {
                    var i = pos + 1
                    while (i < le && (isIdentPart(text[i]) || text[i] == ':' || text[i] == '~' || text[i] == ',' || text[i] == '-')) i++
                    if (charAt(i) == '!') { pos = i + 1; emit(before, pos, TokenType.VARIABLE) } else pos++
                }
                c == '"' -> {
                    var i = pos + 1
                    while (i < le && text[i] != '"') i++
                    pos = if (i < le) i + 1 else le
                    if (i >= le) unterminatedStrings.add(before)
                    emit(before, pos, TokenType.STRING)
                }
                c == '/' && (peek(1).isLetterOrDigit() || peek(1) == '?') -> {
                    var i = pos + 1
                    while (i < le && isIdentPart(text[i])) i++
                    pos = i
                    emit(before, pos, TokenType.ATTRIBUTE)
                }
                c == '&' || c == '|' -> {
                    pos++
                    while (pos < le && (text[pos] == '&' || text[pos] == '|')) pos++
                    emit(before, pos, TokenType.PUNCTUATION)
                    commandPos = true
                }
                c == '(' || c == ')' -> { pos++; emit(before, pos, TokenType.PUNCTUATION); commandPos = c == '(' }
                c == '>' || c == '<' || c == '=' -> { pos++; emit(before, pos, TokenType.OPERATOR) }
                c == '@' -> { pos++; emit(before, pos, TokenType.OPERATOR) }
                isDigit(c) && !isWordChar(peek(-1)) -> {
                    var i = pos
                    while (i < le && isDigit(text[i])) i++
                    if (!isIdentPart(charAt(i))) { pos = i; emit(before, pos, TokenType.NUMBER) } else pos = identifierEnd(i)
                }
                isIdentStart(c) -> {
                    pos = identifierEnd(pos) { it == '-' || it == '.' }
                    val w = slice(before, pos).lowercase()
                    when {
                        w == "rem" && commandPos -> { emit(before, le, TokenType.COMMENT); pos = le }
                        w in keywords -> { emit(before, pos, TokenType.KEYWORD); commandPos = w in commandAfter }
                        commandPos && w in commands -> { emit(before, pos, TokenType.BUILTIN); commandPos = false }
                        w in conditions -> emit(before, pos, TokenType.KEYWORD)
                        else -> commandPos = false
                    }
                }
                else -> pos++
            }
            if (pos == before) pos++
        }
    }

    private fun scanPercent(le: Int) {
        val start = pos
        val c1 = peek(1)
        when {
            c1 == '%' && pos + 2 < le && (isIdentPart(text[pos + 2]) || text[pos + 2] == '~') -> { // %%i in a for loop
                var i = pos + 2
                while (i < le && (text[i] == '~' || isIdentPart(text[i]))) i++
                pos = i
            }
            c1 == '~' || isDigit(c1) || c1 == '*' -> {
                var i = pos + 1
                while (i < le && (text[i] == '~' || isIdentPart(text[i]) || text[i] == '$' || text[i] == ':')) i++
                pos = i
            }
            isIdentStart(c1) -> {
                var i = pos + 1
                while (i < le && (isIdentPart(text[i]) || text[i] == ':' || text[i] == '~' || text[i] == ',' || text[i] == '-' || text[i] == '=' || text[i] == '*' || text[i] == '(' || text[i] == ')')) i++
                if (charAt(i) == '%') { pos = i + 1 } else { pos++; return }
            }
            else -> { pos++; return }
        }
        emit(start, pos, TokenType.VARIABLE)
    }

    private companion object {
        val keywords = setOf("if", "else", "for", "in", "do", "goto", "call", "exit", "not", "exist", "defined", "errorlevel", "equ", "neq", "lss", "leq", "gtr", "geq", "setlocal", "endlocal", "enabledelayedexpansion", "disabledelayedexpansion", "enableextensions", "shift", "pause", "echo", "set")
        val commandAfter = setOf("if", "else", "do", "not", "in")
        val conditions = setOf("exist", "defined", "errorlevel", "equ", "neq", "lss", "leq", "gtr", "geq", "nul", "on", "off")
        val commands = setOf("cd", "chdir", "dir", "copy", "xcopy", "robocopy", "del", "erase", "md", "mkdir", "rd", "rmdir", "ren", "rename", "move", "type", "start", "pushd", "popd", "cls", "title", "choice", "findstr", "find", "timeout", "taskkill", "tasklist", "net", "ping", "ipconfig", "attrib", "assoc", "color", "date", "time", "ver", "vol", "path", "prompt", "more", "sort", "tree", "where", "whoami", "reg", "sc", "wmic", "powershell", "cmd", "python", "java", "node", "npm", "git", "mode", "chcp", "mklink", "fc", "comp", "format", "label", "subst", "schtasks", "msg", "runas", "cacls", "icacls", "takeown", "certutil", "curl", "bitsadmin", "ftp", "telnet", "nslookup", "tracert", "netstat", "route", "arp", "systeminfo", "shutdown", "logoff", "verify", "break", "rem")
    }
}
