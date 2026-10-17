package com.nitinshukla.notebook.core.code

/** A problem found by [CodeChecker]; [line] and [column] are 1-based. */
data class Issue(val line: Int, val column: Int, val message: String, val severity: Severity) {
    enum class Severity { ERROR, WARNING }
}

/** Cheap, offline checks that do not need a runtime. */
object CodeChecker {
    private const val MAX_ISSUES = 200

    private val noBracketCheck = setOf(
        Language.MARKDOWN, Language.LOG, Language.PLAIN, Language.HTML, Language.XML, Language.YAML,
        Language.INI, Language.DOCKERFILE, Language.MAKEFILE, Language.BATCH,
    )
    private val noStringCheck = setOf(Language.MARKDOWN, Language.LOG, Language.PLAIN, Language.YAML, Language.INI, Language.DOCKERFILE, Language.MAKEFILE, Language.BATCH)

    /**
     * Unbalanced brackets and unterminated strings/comments for any language (comment and string
     * aware); for PYTHON also mixed tabs/spaces, unexpected indents, dedents that match no outer
     * level, a line ending with ':' without an indented block, and a trailing backslash at EOF.
     * For JSON a real validity check with line/column. Never throws.
     */
    fun check(text: String, language: Language): List<Issue> = try {
        val issues = ArrayList<Issue>()
        if (language == Language.JSON) {
            JsonValidator(text).validate()?.let { issues.add(it) }
        } else {
            val scanner = SyntaxHighlighter.scan(text, language, Int.MAX_VALUE)
            val tokens = scanner?.tokens ?: emptyList()
            if (scanner != null && language !in noStringCheck) {
                for (s in scanner.unterminatedStrings) issues.add(issueAt(text, s, "Unterminated string", Issue.Severity.ERROR))
                for (s in scanner.unterminatedComments) issues.add(issueAt(text, s, "Unterminated block comment", Issue.Severity.WARNING))
            }
            if (language !in noBracketCheck) checkBrackets(text, tokens, language, issues)
            if (language == Language.PYTHON) checkPython(text, tokens, issues)
        }
        issues.sortWith(compareBy({ it.line }, { it.column }))
        if (issues.size > MAX_ISSUES) issues.subList(0, MAX_ISSUES).toList() else issues
    } catch (e: Exception) {
        emptyList()
    }

    private fun issueAt(text: String, offset: Int, message: String, severity: Issue.Severity): Issue {
        val (line, col) = CodeEditing.position(text, offset)
        return Issue(line, col, message, severity)
    }

    /** Marks every offset covered by a string, char or comment token. */
    private fun skipMask(text: String, tokens: List<Token>): BooleanArray {
        val mask = BooleanArray(text.length)
        for (t in tokens) {
            if (t.type == TokenType.STRING || t.type == TokenType.CHAR || t.type == TokenType.COMMENT) {
                for (i in t.start until minOf(t.end, text.length)) mask[i] = true
            }
        }
        return mask
    }

    private fun checkBrackets(text: String, tokens: List<Token>, language: Language, issues: MutableList<Issue>) {
        val skip = skipMask(text, tokens)
        val skipParens = language == Language.SHELL
        val openers = ArrayList<Int>()
        val chars = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            if (!skip[i]) {
                val c = text[i]
                when (c) {
                    '(', '[', '{' -> if (!(skipParens && c == '(')) { openers.add(i); chars.append(c) }
                    ')', ']', '}' -> if (!(skipParens && c == ')')) {
                        // Find the nearest opener this closer matches; openers above it are unclosed.
                        var k = chars.length - 1
                        while (k >= 0 && closerOf(chars[k]) != c) k--
                        if (k < 0) {
                            val open = if (chars.isEmpty()) null else chars[chars.length - 1]
                            val message = if (open == null) "Unmatched '$c'" else {
                                val (ol, oc) = CodeEditing.position(text, openers[openers.lastIndex])
                                "Expected '${closerOf(open)}' to close '$open' from line $ol, column $oc, but found '$c'"
                            }
                            issues.add(issueAt(text, i, message, Issue.Severity.ERROR))
                        } else {
                            for (j in chars.length - 1 downTo k + 1) {
                                issues.add(issueAt(text, openers[j], "Unclosed '${chars[j]}'", Issue.Severity.ERROR))
                            }
                            while (chars.length > k) { openers.removeAt(openers.lastIndex); chars.setLength(chars.length - 1) }
                        }
                    }
                }
                if (issues.size >= MAX_ISSUES) return
            }
            i++
        }
        for (k in openers.indices) {
            issues.add(issueAt(text, openers[k], "Unclosed '${chars[k]}'", Issue.Severity.ERROR))
            if (issues.size >= MAX_ISSUES) return
        }
    }

    private fun closerOf(open: Char): Char = when (open) {
        '(' -> ')'
        '[' -> ']'
        else -> '}'
    }

    private fun checkPython(text: String, tokens: List<Token>, issues: MutableList<Issue>) {
        val n = text.length
        val skip = skipMask(text, tokens)
        // Offsets inside multi-line strings (so a line starting inside one is not a logical line).
        val inString = BooleanArray(n)
        for (t in tokens) if (t.type == TokenType.STRING) for (k in t.start until minOf(t.end, n)) inString[k] = true

        val stack = ArrayList<Int>()
        stack.add(0)
        var expectIndentFrom = -1 // offset of the ':' that requires an indented block, or -1
        var depth = 0
        var indentChar = ' '
        var indentCharKnown = false
        var inconsistentReported = false
        var continued = false // previous line ended with a backslash

        var ls = 0
        var lineNo = 1
        while (ls <= n) {
            var le = ls
            while (le < n && text[le] != '\n') le++
            val startDepth = depth
            val startInString = ls < n && inString[ls] && (ls == 0 || inString[ls - 1])
            // Scan the line: content, brackets and trailing backslash.
            var firstContent = -1
            var lastContent = -1
            var endsWithBackslash = false
            var i = ls
            while (i < le) {
                val c = text[i]
                if (!skip[i]) {
                    when (c) {
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> if (depth > 0) depth--
                    }
                }
                if (c != ' ' && c != '\t' && c != '\r' && c != '\u000C') {
                    if (firstContent < 0) firstContent = i
                    if (!skip[i] || inString[i]) lastContent = i
                }
                i++
            }
            if (lastContent >= 0 && text[lastContent] == '\\' && !skip[lastContent]) endsWithBackslash = true
            val isLogical = firstContent >= 0 && !startInString && startDepth == 0 && !continued && !(skip[firstContent] && !inString[firstContent])
            if (isLogical) {
                // Indentation
                var tabs = false
                var spaces = false
                var width = 0
                for (k in ls until firstContent) {
                    when (text[k]) {
                        '\t' -> { tabs = true; width = (width / 8 + 1) * 8 }
                        ' ' -> { spaces = true; width++ }
                    }
                }
                val col = firstContent - ls + 1
                if (tabs && spaces) issues.add(Issue(lineNo, 1, "Mixed tabs and spaces in indentation", Issue.Severity.WARNING))
                else if (tabs || spaces) {
                    val ch = if (tabs) '\t' else ' '
                    if (!indentCharKnown) { indentChar = ch; indentCharKnown = true }
                    else if (ch != indentChar && !inconsistentReported) {
                        inconsistentReported = true
                        issues.add(Issue(lineNo, 1, "Inconsistent use of tabs and spaces in indentation", Issue.Severity.WARNING))
                    }
                }
                val top = stack[stack.lastIndex]
                var handled = false
                if (expectIndentFrom >= 0) {
                    if (width > top) { stack.add(width); handled = true }
                    else issues.add(issueAt(text, expectIndentFrom, "Expected an indented block after this line", Issue.Severity.ERROR))
                    expectIndentFrom = -1
                }
                if (!handled) {
                    if (width > top) {
                        issues.add(Issue(lineNo, col, "Unexpected indent", Issue.Severity.ERROR))
                        stack.add(width)
                    } else if (width < top) {
                        while (stack.size > 1 && stack[stack.lastIndex] > width) stack.removeAt(stack.lastIndex)
                        if (stack[stack.lastIndex] != width) {
                            issues.add(Issue(lineNo, col, "Unindent does not match any outer indentation level", Issue.Severity.ERROR))
                            stack.add(width)
                        }
                    }
                }
            }
            // Does this (possibly multi-line) statement end with ':'?
            if (firstContent >= 0 && lastContent >= 0 && depth == 0 && !endsWithBackslash && text[lastContent] == ':' && !skip[lastContent]) {
                expectIndentFrom = lastContent
            }
            continued = endsWithBackslash
            if (issues.size >= MAX_ISSUES) return
            ls = le + 1
            lineNo++
        }
        if (expectIndentFrom >= 0) issues.add(issueAt(text, expectIndentFrom, "Expected an indented block after this line", Issue.Severity.ERROR))
        // Trailing backslash at EOF.
        var last = n - 1
        while (last >= 0 && (text[last] == '\n' || text[last] == '\r' || text[last] == ' ' || text[last] == '\t')) last--
        if (last >= 0 && text[last] == '\\' && !skip[last]) issues.add(issueAt(text, last, "Line continuation at end of file", Issue.Severity.ERROR))
    }

    /** Strict JSON validator (comments tolerated, for .jsonc) that reports the first problem with its position. */
    private class JsonValidator(private val s: String) {
        private var i = 0
        private val n = s.length
        private var error: Issue? = null

        fun validate(): Issue? {
            if (n > 0 && s[0] == '﻿') i = 1
            skipWs()
            if (i >= n) return null // an empty document is not an error in the editor
            value(0)
            if (error != null) return error
            skipWs()
            if (i < n) fail(i, "Unexpected trailing content")
            return error
        }

        private fun fail(at: Int, message: String) {
            if (error == null) {
                val (line, col) = CodeEditing.position(s, minOf(at, n))
                error = Issue(line, col, message, Issue.Severity.ERROR)
            }
        }

        private fun skipWs() {
            while (i < n) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++
                else if (c == '/' && i + 1 < n && s[i + 1] == '/') { while (i < n && s[i] != '\n') i++ }
                else if (c == '/' && i + 1 < n && s[i + 1] == '*') {
                    val end = s.indexOf("*/", i + 2)
                    if (end < 0) { fail(i, "Unterminated comment"); i = n } else i = end + 2
                } else break
            }
        }

        private fun value(depth: Int) {
            if (error != null) return
            if (depth > 512) { fail(i, "Nesting too deep"); return }
            skipWs()
            if (i >= n) { fail(i, "Unexpected end of input"); return }
            when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> if (c == '-' || c in '0'..'9') number() else fail(i, "Unexpected character '${printable(c)}'")
            }
        }

        private fun printable(c: Char): String = if (c < ' ') "\\u%04x".format(c.code) else c.toString()

        private fun obj(depth: Int) {
            val open = i
            i++
            skipWs()
            if (i < n && s[i] == '}') { i++; return }
            while (error == null) {
                skipWs()
                if (i >= n) { fail(i, "Unclosed '{' from ${pos(open)}"); return }
                if (s[i] != '"') { fail(i, if (s[i] == '}') "Trailing comma before '}'" else "Expected a string key"); return }
                string()
                if (error != null) return
                skipWs()
                if (i >= n || s[i] != ':') { fail(i, "Expected ':' after key"); return }
                i++
                value(depth + 1)
                if (error != null) return
                skipWs()
                if (i >= n) { fail(i, "Unclosed '{' from ${pos(open)}"); return }
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return }
                    else -> { fail(i, "Expected ',' or '}'"); return }
                }
            }
        }

        private fun arr(depth: Int) {
            val open = i
            i++
            skipWs()
            if (i < n && s[i] == ']') { i++; return }
            while (error == null) {
                skipWs()
                if (i < n && s[i] == ']') { fail(i, "Trailing comma before ']'"); return }
                value(depth + 1)
                if (error != null) return
                skipWs()
                if (i >= n) { fail(i, "Unclosed '[' from ${pos(open)}"); return }
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return }
                    else -> { fail(i, "Expected ',' or ']'"); return }
                }
            }
        }

        private fun pos(at: Int): String {
            val (line, col) = CodeEditing.position(s, at)
            return "line $line, column $col"
        }

        private fun string() {
            val start = i
            i++
            while (i < n) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return }
                    c == '\\' -> {
                        if (i + 1 >= n) { fail(i, "Unterminated string"); return }
                        val e = s[i + 1]
                        if (e == 'u') {
                            for (k in 2..5) if (i + k >= n || !isHex(s[i + k])) { fail(i, "Invalid unicode escape"); return }
                            i += 6
                        } else if (e in "\"\\/bfnrt") i += 2
                        else { fail(i, "Invalid escape '\\$e'"); return }
                    }
                    c == '\n' -> { fail(start, "Unterminated string"); return }
                    c < ' ' -> { fail(i, "Control character in string"); return }
                    else -> i++
                }
            }
            fail(start, "Unterminated string")
        }

        private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        private fun literal(word: String) {
            if (s.startsWith(word, i) && (i + word.length >= n || !s[i + word.length].isLetterOrDigit())) i += word.length
            else fail(i, "Unexpected character '${s[i]}'")
        }

        private fun number() {
            val start = i
            if (s[i] == '-') i++
            if (i >= n || s[i] !in '0'..'9') { fail(start, "Invalid number"); return }
            if (s[i] == '0') i++ else while (i < n && s[i] in '0'..'9') i++
            if (i < n && s[i] == '.') {
                i++
                if (i >= n || s[i] !in '0'..'9') { fail(start, "Invalid number"); return }
                while (i < n && s[i] in '0'..'9') i++
            }
            if (i < n && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < n && (s[i] == '+' || s[i] == '-')) i++
                if (i >= n || s[i] !in '0'..'9') { fail(start, "Invalid number"); return }
                while (i < n && s[i] in '0'..'9') i++
            }
            if (i < n && (s[i].isLetterOrDigit() || s[i] == '.')) fail(start, "Invalid number")
        }
    }
}
