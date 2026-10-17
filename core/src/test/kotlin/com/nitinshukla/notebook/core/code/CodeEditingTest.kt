package com.nitinshukla.notebook.core.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeEditingTest {
    private fun edit(text: String, start: Int, end: Int = start) = TextEdit(text, start, end)

    @Test fun `newLine copies indentation`() {
        val text = "    x = 1"
        assertEquals(edit("    x = 1\n    ", 14), CodeEditing.newLine(text, 9, Language.PYTHON))
        // Cursor inside the leading whitespace copies only what is before it.
        assertEquals(edit("  \n    x = 1", 5), CodeEditing.newLine(text, 2, Language.PYTHON))
        assertEquals(edit("\n", 1), CodeEditing.newLine("", 0, Language.PLAIN))
    }

    @Test fun `newLine indents after colon in python but not in kotlin`() {
        assertEquals(edit("def f():\n    ", 13), CodeEditing.newLine("def f():", 8, Language.PYTHON))
        // Text after the cursor (here a comment) moves to the new line; whitespace before the cursor is ignored.
        assertEquals(edit("  if x:\n        # c", 14), CodeEditing.newLine("  if x:  # c", 7, Language.PYTHON))
        assertEquals(edit("  if x: \n      ", 15), CodeEditing.newLine("  if x: ", 8, Language.PYTHON))
        assertEquals(edit("val x:\n", 7), CodeEditing.newLine("val x:", 6, Language.KOTLIN))
    }

    @Test fun `newLine after opening brace adds one level and between braces splits`() {
        assertEquals(edit("fun f() {\n    ", 14), CodeEditing.newLine("fun f() {", 9, Language.KOTLIN))
        assertEquals(edit("fun f() {\n    \n}", 14), CodeEditing.newLine("fun f() {}", 9, Language.KOTLIN))
        assertEquals(edit("  a = [\n      \n  ]", 14), CodeEditing.newLine("  a = []", 7, Language.JAVASCRIPT))
        assertEquals(edit("f(\n\t\n)", 4), CodeEditing.newLine("f()", 2, Language.GO))
        // Text after the cursor moves to the new indented line.
        assertEquals(edit("if (x) {\n    y", 13), CodeEditing.newLine("if (x) {y", 8, Language.JAVA))
    }

    @Test fun `indent inserts at cursor for a single line`() {
        assertEquals(edit("ab    cd", 6), CodeEditing.indent("abcd", 2, 2, Language.PYTHON))
        assertEquals(edit("ab\tcd", 3), CodeEditing.indent("abcd", 2, 2, Language.GO))
        assertEquals(edit("a  d", 3), CodeEditing.indent("abcd", 1, 3, Language.YAML))
    }

    @Test fun `indent and outdent multiple lines including tabs`() {
        val text = "a\nb\n\nc"
        assertEquals(edit("    a\n    b\n\n    c", 0, 18), CodeEditing.indent(text, 0, 6, Language.PYTHON))
        assertEquals(edit("\ta\n\tb\n\nc", 0, 6), CodeEditing.indent(text, 0, 4, Language.GO))
        // Selection ending at a line start does not indent that line.
        assertEquals(edit("    a\n    b\n\nc", 0, 13), CodeEditing.indent(text, 0, 5, Language.PYTHON))
        // Selection start inside a line moves with the inserted indent.
        assertEquals(edit("    a\n    b\n\nc", 5, 11), CodeEditing.indent(text, 1, 3, Language.PYTHON))

        assertEquals(edit("a\nb\nc", 0, 5), CodeEditing.outdent("    a\n\tb\n  c", 0, 12, Language.PYTHON))
        assertEquals(edit("a\n  b", 0, 5), CodeEditing.outdent("  a\n    b", 0, 9, Language.RUBY))
        assertEquals(edit("x\n", 0, 2), CodeEditing.outdent("\tx\n", 0, 3, Language.GO))
        // Cursor inside the removed indent clamps to the line start.
        assertEquals(edit("a", 0), CodeEditing.outdent("    a", 2, 2, Language.PYTHON))
        assertEquals(edit("a", 0), CodeEditing.outdent("a", 0, 0, Language.PYTHON))
    }

    @Test fun `toggleComment adds at the common indentation and removes again`() {
        val text = "    a\n        b\n\n    c"
        val commented = CodeEditing.toggleComment(text, 0, text.length, Language.PYTHON)
        assertEquals("    # a\n    #     b\n\n    # c", commented.text)
        assertEquals(0, commented.selectionStart)
        assertEquals(commented.text.length, commented.selectionEnd)
        val back = CodeEditing.toggleComment(commented.text, commented.selectionStart, commented.selectionEnd, Language.PYTHON)
        assertEquals(text, back.text)
        assertEquals(0 to text.length, back.selectionStart to back.selectionEnd)
    }

    @Test fun `toggleComment on a single line keeps the cursor on its text`() {
        assertEquals(edit("// val x = 1", 7), CodeEditing.toggleComment("val x = 1", 4, 4, Language.KOTLIN))
        assertEquals(edit("val x = 1", 4), CodeEditing.toggleComment("// val x = 1", 7, 7, Language.KOTLIN))
        assertEquals(edit("val x = 1", 0), CodeEditing.toggleComment("//val x = 1", 1, 1, Language.KOTLIN))
        assertEquals(edit("-- select", 3), CodeEditing.toggleComment("select", 0, 0, Language.SQL))
        assertEquals(edit("REM echo", 4), CodeEditing.toggleComment("echo", 0, 0, Language.BATCH))
        // Mixed: if any line is uncommented all lines get commented.
        assertEquals("# # a\n# b", CodeEditing.toggleComment("# a\nb", 0, 5, Language.SHELL).text)
    }

    @Test fun `toggleComment uses block comments for css and html and is a no-op for plain`() {
        assertEquals(edit("/* color: red; */", 3, 14), CodeEditing.toggleComment("color: red;", 0, 11, Language.CSS))
        assertEquals(edit("color: red;", 0, 11), CodeEditing.toggleComment("/* color: red; */", 3, 14, Language.CSS))
        assertEquals(edit("  <!-- <b>x</b> -->", 7, 15), CodeEditing.toggleComment("  <b>x</b>", 5, 5, Language.HTML))
        assertEquals(edit("hello", 1, 3), CodeEditing.toggleComment("hello", 1, 3, Language.PLAIN))
    }

    @Test fun `autoClose brackets and quotes`() {
        assertEquals(edit("()", 1), CodeEditing.autoClose("", 0, '(', Language.KOTLIN))
        assertEquals(edit("f({})", 3), CodeEditing.autoClose("f()", 2, '{', Language.KOTLIN))
        assertEquals(edit("x = []", 5), CodeEditing.autoClose("x = ", 4, '[', Language.PYTHON))
        assertEquals(edit("\"\"", 1), CodeEditing.autoClose("", 0, '"', Language.JAVA))
        assertEquals(edit("print('')", 7), CodeEditing.autoClose("print()", 6, '\'', Language.PYTHON))
        assertEquals(edit("``", 1), CodeEditing.autoClose("", 0, '`', Language.JAVASCRIPT))
        assertEquals(edit("x = r\"\"", 6), CodeEditing.autoClose("x = r", 5, '"', Language.PYTHON))
    }

    @Test fun `autoClose declines when it would get in the way`() {
        assertNull(CodeEditing.autoClose("word", 0, '(', Language.KOTLIN)) // before a word char
        assertNull(CodeEditing.autoClose("don", 3, '\'', Language.PYTHON)) // after a word char
        assertNull(CodeEditing.autoClose("\"abc", 4, '"', Language.JAVA)) // inside a string
        assertNull(CodeEditing.autoClose("\"\"", 1, '"', Language.JAVA)) // next char is the same quote
        assertNull(CodeEditing.autoClose("", 0, 'a', Language.JAVA)) // not an opener
        assertNull(CodeEditing.autoClose("", 0, '\'', Language.RUST)) // lifetimes
        assertNull(CodeEditing.autoClose("", 0, '"', Language.PLAIN)) // prose
        assertNull(CodeEditing.autoClose("", 0, '`', Language.KOTLIN)) // no backtick strings
        assertEquals(edit("()", 1), CodeEditing.autoClose("", 0, '(', Language.PLAIN)) // brackets still pair in prose
    }

    @Test fun `matchingBracket in both directions`() {
        val text = "f(a[1], {b: (2)})"
        assertEquals(16, CodeEditing.matchingBracket(text, 1))
        assertEquals(1, CodeEditing.matchingBracket(text, 17)) // cursor after the closer
        assertEquals(1, CodeEditing.matchingBracket(text, 16))
        assertEquals(5, CodeEditing.matchingBracket(text, 3))
        assertEquals(14, CodeEditing.matchingBracket(text, 12))
        assertEquals(15, CodeEditing.matchingBracket(text, 8))
        assertEquals(-1, CodeEditing.matchingBracket(text, 10)) // not on a bracket
        assertEquals(-1, CodeEditing.matchingBracket("((", 0))
        assertEquals(-1, CodeEditing.matchingBracket("", 0))
        assertEquals(-1, CodeEditing.matchingBracket("x", 5))
    }

    @Test fun `duplicateLine including first and last lines`() {
        assertEquals(edit("a\na\nb", 3), CodeEditing.duplicateLine("a\nb", 1))
        assertEquals(edit("a\nb\nb", 4), CodeEditing.duplicateLine("a\nb", 2))
        assertEquals(edit("ab\nab", 4), CodeEditing.duplicateLine("ab", 1))
        assertEquals(edit("\n", 1), CodeEditing.duplicateLine("", 0))
        assertEquals(edit("a\n\n\nb", 3), CodeEditing.duplicateLine("a\n\nb", 2))
    }

    @Test fun `deleteLine including first last and only lines`() {
        assertEquals(edit("b\nc", 0), CodeEditing.deleteLine("a\nb\nc", 0))
        assertEquals(edit("a\nc", 3), CodeEditing.deleteLine("a\nb\nc", 3))
        assertEquals(edit("a\nb", 2), CodeEditing.deleteLine("a\nb\nc", 4)) // last line: cursor lands on the new last line
        assertEquals(edit("", 0), CodeEditing.deleteLine("only", 2))
        assertEquals(edit("", 0), CodeEditing.deleteLine("", 0))
        assertEquals(edit("a\n", 2), CodeEditing.deleteLine("a\n\n", 3)) // trailing empty line
        assertEquals(edit("abc\nx", 5), CodeEditing.deleteLine("abc\nlonger\nx", 9)) // column clamps to the shorter line
    }

    @Test fun `moveLine up and down with edge cases`() {
        assertEquals(edit("b\na\nc", 1), CodeEditing.moveLine("a\nb\nc", 3, up = true))
        assertEquals(edit("b\na\nc", 3), CodeEditing.moveLine("a\nb\nc", 1, up = false))
        assertEquals(edit("a\nb\nc", 0), CodeEditing.moveLine("a\nb\nc", 0, up = true)) // first line up
        assertEquals(edit("a\nb\nc", 5), CodeEditing.moveLine("a\nb\nc", 5, up = false)) // last line down
        assertEquals(edit("a\nc\nb", 4), CodeEditing.moveLine("a\nb\nc", 2, up = false))
        assertEquals(edit("a\nc\nb", 3), CodeEditing.moveLine("a\nb\nc", 5, up = true))
        assertEquals(edit("\na", 1), CodeEditing.moveLine("a\n", 0, up = false)) // move onto an empty last line
        assertEquals(edit("xx\n\nyy", 3), CodeEditing.moveLine("\nxx\nyy", 0, up = false))
    }

    @Test fun `position and offsetOfLine`() {
        val text = "ab\n\tcd\nef"
        assertEquals(1 to 1, CodeEditing.position(text, 0))
        assertEquals(1 to 3, CodeEditing.position(text, 2))
        assertEquals(2 to 1, CodeEditing.position(text, 3))
        assertEquals(2 to 3, CodeEditing.position(text, 5)) // tab counts as one column
        assertEquals(3 to 3, CodeEditing.position(text, 99)) // clamped
        assertEquals(1 to 1, CodeEditing.position(text, -4))
        assertEquals(0, CodeEditing.offsetOfLine(text, 1))
        assertEquals(3, CodeEditing.offsetOfLine(text, 2))
        assertEquals(7, CodeEditing.offsetOfLine(text, 3))
        assertEquals(7, CodeEditing.offsetOfLine(text, 50))
        assertEquals(0, CodeEditing.offsetOfLine(text, 0))
        assertEquals(0, CodeEditing.offsetOfLine("", 3))
    }
}
