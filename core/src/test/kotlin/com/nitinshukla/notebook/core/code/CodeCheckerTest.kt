package com.nitinshukla.notebook.core.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeCheckerTest {
    private fun errors(text: String, language: Language) = CodeChecker.check(text, language).filter { it.severity == Issue.Severity.ERROR }
    private fun warnings(text: String, language: Language) = CodeChecker.check(text, language).filter { it.severity == Issue.Severity.WARNING }

    @Test fun `balanced code has no issues`() {
        assertEquals(emptyList<Issue>(), CodeChecker.check("fun f(a: Int) = listOf(a)[0] // ) unbalanced in comment\nval s = \"}\"", Language.KOTLIN))
        assertEquals(emptyList<Issue>(), CodeChecker.check("def f(a):\n    return [a, (1, 2), {'k': ')'}]\n", Language.PYTHON))
        assertEquals(emptyList<Issue>(), CodeChecker.check("", Language.JAVA))
    }

    @Test fun `unclosed bracket reports its position`() {
        val issues = errors("fun f() {\n    val x = (1 + 2\n}\n", Language.KOTLIN)
        assertEquals(1, issues.size)
        assertEquals(2, issues[0].line)
        assertEquals(13, issues[0].column)
        assertEquals("Unclosed '('", issues[0].message)
    }

    @Test fun `unmatched closer and mismatched pair are reported`() {
        val unmatched = errors("a = 1)\n", Language.PYTHON)
        assertEquals(1, unmatched.size)
        assertEquals(1 to 6, unmatched[0].line to unmatched[0].column)
        assertEquals("Unmatched ')'", unmatched[0].message)

        val mismatched = errors("x = [1, 2)\n", Language.JAVASCRIPT)
        assertEquals(2, mismatched.size)
        assertEquals(5, mismatched[0].column)
        assertEquals("Unclosed '['", mismatched[0].message)
        assertEquals(10, mismatched[1].column)
        assertTrue(mismatched[1].message.startsWith("Expected ']'"))

        // A closer that matches an outer opener closes it; the inner opener is reported unclosed.
        val recovered = errors("f(a[1)\n", Language.JAVASCRIPT)
        assertEquals(1, recovered.size)
        assertEquals("Unclosed '['", recovered[0].message)
        assertEquals(4, recovered[0].column)
    }

    @Test fun `brackets inside strings and comments are ignored`() {
        assertEquals(emptyList<Issue>(), CodeChecker.check("x = \"(((\"  # )))\n", Language.PYTHON))
        assertEquals(emptyList<Issue>(), CodeChecker.check("/* { */ int a[] = {1};", Language.C))
        assertEquals(emptyList<Issue>(), CodeChecker.check("case \$x in\n  a) echo 1;;\nesac\n", Language.SHELL))
    }

    @Test fun `unterminated string is an error`() {
        val issues = errors("val s = \"abc\n", Language.KOTLIN)
        assertEquals(1, issues.size)
        assertEquals("Unterminated string", issues[0].message)
        assertEquals(1 to 9, issues[0].line to issues[0].column)
        assertTrue(warnings("/* open", Language.JAVA).any { it.message == "Unterminated block comment" })
    }

    @Test fun `python mixed tabs and spaces`() {
        val issues = warnings("if x:\n \tpass\n", Language.PYTHON)
        assertEquals(1, issues.size)
        assertEquals("Mixed tabs and spaces in indentation", issues[0].message)
        assertEquals(2, issues[0].line)
        val inconsistent = warnings("if a:\n    b()\nif c:\n\td()\n", Language.PYTHON)
        assertEquals(1, inconsistent.size)
        assertEquals(4, inconsistent[0].line)
    }

    @Test fun `python unexpected indent`() {
        val issues = errors("x = 1\n    y = 2\n", Language.PYTHON)
        assertEquals(1, issues.size)
        assertEquals("Unexpected indent", issues[0].message)
        assertEquals(2 to 5, issues[0].line to issues[0].column)
    }

    @Test fun `python dedent matching no outer level`() {
        val issues = errors("if a:\n        b()\n    c()\n", Language.PYTHON)
        assertEquals(1, issues.size)
        assertEquals("Unindent does not match any outer indentation level", issues[0].message)
        assertEquals(3, issues[0].line)
    }

    @Test fun `python missing block after colon`() {
        val issues = errors("def f():\nreturn 1\n", Language.PYTHON)
        assertEquals(1, issues.size)
        assertEquals(1 to 8, issues[0].line to issues[0].column)
        assertTrue(issues[0].message.startsWith("Expected an indented block"))
        val atEof = errors("for x in y:\n", Language.PYTHON)
        assertEquals(1, atEof.size)
        assertEquals(1, atEof[0].line)
    }

    @Test fun `python continuation lines and multi-line strings are not indentation errors`() {
        val ok = "d = {\n        'a': 1,\n    'b': 2,\n}\ns = \"\"\"\n  indented\ntext\"\"\"\nx = 1 + \\\n        2\nif x:  # comment: still a block\n    pass\n"
        assertEquals(emptyList<Issue>(), CodeChecker.check(ok, Language.PYTHON))
    }

    @Test fun `python trailing backslash at end of file`() {
        val issues = errors("x = 1 + \\\n", Language.PYTHON)
        assertEquals(1, issues.size)
        assertEquals("Line continuation at end of file", issues[0].message)
        assertEquals(1 to 9, issues[0].line to issues[0].column)
    }

    @Test fun `json validity errors carry line and column`() {
        assertEquals(emptyList<Issue>(), CodeChecker.check("{\"a\": [1, 2.5e3, true, null, \"s\\n\"], \"b\": {}}", Language.JSON))
        assertEquals(emptyList<Issue>(), CodeChecker.check("", Language.JSON))
        val missingComma = CodeChecker.check("{\n  \"a\": 1\n  \"b\": 2\n}", Language.JSON)
        assertEquals(1, missingComma.size)
        assertEquals(3 to 3, missingComma[0].line to missingComma[0].column)
        assertEquals("Expected ',' or '}'", missingComma[0].message)
        val trailing = CodeChecker.check("[1, 2,]", Language.JSON)
        assertEquals("Trailing comma before ']'", trailing[0].message)
        assertEquals(1 to 7, trailing[0].line to trailing[0].column)
        val unclosed = CodeChecker.check("{\"a\": [1, 2", Language.JSON)
        assertEquals(1, unclosed.size)
        assertTrue(unclosed[0].message.startsWith("Unclosed '['"))
        val badNumber = CodeChecker.check("[01]", Language.JSON)
        assertEquals(1, badNumber.size)
        val garbage = CodeChecker.check("{\"a\": 1} x", Language.JSON)
        assertEquals("Unexpected trailing content", garbage[0].message)
        assertEquals(1 to 10, garbage[0].line to garbage[0].column)
        assertEquals("Expected a string key", CodeChecker.check("{'a': 1}", Language.JSON)[0].message)
        assertEquals("Unexpected character '''", CodeChecker.check("['a']", Language.JSON)[0].message)
    }

    @Test fun `issues are sorted by position`() {
        val issues = CodeChecker.check("x = (\ny = [\nz = \"s\n", Language.PYTHON)
        assertEquals(issues.sortedWith(compareBy({ it.line }, { it.column })), issues)
        assertTrue(issues.size >= 3)
    }

    @Test fun `prose languages are not bracket checked`() {
        assertEquals(emptyList<Issue>(), CodeChecker.check("A note (unfinished\n# heading [", Language.MARKDOWN))
        assertEquals(emptyList<Issue>(), CodeChecker.check("12:00 ERROR failed (code", Language.LOG))
        assertEquals(emptyList<Issue>(), CodeChecker.check("(((", Language.PLAIN))
    }
}
