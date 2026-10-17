package com.nitinshukla.notebook.core.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TokenizerInvariantsTest {
    private val nasty: List<String> by lazy {
        val random = Random(42)
        val bytes = String(CharArray(5000) { random.nextInt(0, 0x3000).toChar() })
        val surrogates = String(CharArray(2000) { if (it % 2 == 0) '\uD83D' else '\uDE00' })
        listOf(
            "",
            "\"",
            "'",
            "`",
            "\"\"\"",
            "'''unterminated\nmore\n",
            "\"'`\"'`\"'`",
            "/*",
            "/* /* */",
            "<!--",
            "<",
            "<a href=\"",
            "\$",
            "\${",
            "@",
            "#",
            "#[",
            "%",
            "%%",
            "!",
            "-",
            "--",
            ".",
            "..",
            "0x",
            "1e",
            "1.",
            ".5",
            "R\"(",
            "r#\"",
            "b'",
            "'\\",
            "\\",
            "\n\n\n",
            "\r\n\r\n",
            "\t\t[\n",
            "a" .repeat(100_000),
            "\"" .repeat(10_001),
            "(" .repeat(3000) + ")".repeat(2999),
            "{\"a\":" .repeat(2000),
            "- " .repeat(5000),
            "# ".repeat(3000),
            "=begin\n",
            "*emphasis _mixed `code",
            "[link](",
            "[x",
            "```\n",
            "key: [{a: b, c",
            "[section",
            "FROM",
            "all:",
            "\$(",
            "\$(\$(\$(",
            ":label",
            "%VAR",
            "!VAR",
            "2024-",
            "12:",
            "::",
            "?>",
            "<?php",
            "@\"",
            "\$\"",
            "L'",
            "u8\"",
            "'a",
            "\u0000\u0000\u0000",
            "日本語 変数 = \"文字列\" # コメント",
            "emoji 😀 \"😀\" '😀' // 😀",
            bytes,
            surrogates,
            "\uD83D", // lone high surrogate
            "\uDE00", // lone low surrogate
        )
    }

    @Test fun `every language survives nasty inputs with valid tokens`() {
        for (language in Language.entries) {
            for (input in nasty) {
                val tokens = try {
                    SyntaxHighlighter.tokenize(input, language)
                } catch (e: Throwable) {
                    throw AssertionError("tokenize threw for $language on <${input.take(40)}>", e)
                }
                try {
                    assertInvariants(input, tokens)
                } catch (e: AssertionError) {
                    throw AssertionError("$language on <${input.take(40)}>: ${e.message}", e)
                }
            }
        }
    }

    @Test fun `every language survives a long single line of mixed code`() {
        val line = buildString { repeat(4000) { append("foo(\"bar\", 'c', 0x1F, \$x, @a, #b, <t a=\"1\">, [1, 2], {k: v}, // not\n".take(if (it % 7 == 0) 60 else 59)) } }
        for (language in Language.entries) {
            val tokens = SyntaxHighlighter.tokenize(line, language)
            assertInvariants(line, tokens)
        }
    }

    @Test fun `checker never throws on nasty inputs`() {
        for (language in Language.entries) {
            for (input in nasty) {
                try {
                    CodeChecker.check(input, language)
                } catch (e: Throwable) {
                    throw AssertionError("check threw for $language on <${input.take(40)}>", e)
                }
            }
        }
    }

    @Test fun `maxChars limits tokenized region`() {
        val text = "val a = 1\n".repeat(100)
        val tokens = SyntaxHighlighter.tokenize(text, Language.KOTLIN, maxChars = 25)
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.all { it.end <= 25 })
        assertEquals(emptyList<Token>(), SyntaxHighlighter.tokenize(text, Language.KOTLIN, maxChars = 0))
        assertEquals(emptyList<Token>(), SyntaxHighlighter.tokenize(text, Language.KOTLIN, maxChars = -5))
    }

    @Test fun `tokens cover plausible fraction of real code`() {
        val python = "def f(x):\n    return x + 1  # comment\n"
        val tokens = SyntaxHighlighter.tokenize(python, Language.PYTHON)
        val covered = tokens.sumOf { it.end - it.start }
        assertTrue(covered > python.length / 2)
    }

    @Test fun `300k chars of python tokenize in under a second`() {
        val unit = "import os\n\n@decorator\nclass Thing(Base):\n    \"\"\"Doc string\n    spanning lines\"\"\"\n    def method(self, a, b=0x1F, *args, **kwargs):\n        s = f\"value {a!r} and {b:>10}\"\n        if a and not b or a is None:\n            return [x ** 2 for x in range(10) if x % 2 == 0]\n        # trailing comment with 'quotes' and \"more\"\n        return {'k': 1_000, \"v\": 3.14e-2j}\n\n"
        val text = buildString { while (length < 300_000) append(unit) }
        SyntaxHighlighter.tokenize(text, Language.PYTHON) // warm-up
        val start = System.nanoTime()
        val tokens = SyntaxHighlighter.tokenize(text, Language.PYTHON)
        val millis = (System.nanoTime() - start) / 1_000_000
        assertInvariants(text, tokens)
        assertTrue("tokenizing took $millis ms", millis < 1000)
        assertTrue(tokens.size > 10_000)
    }

    @Test fun `300k chars of every language tokenize in reasonable time`() {
        val unit = "<div class=\"a\">text &amp; {\"k\": [1, 2]} key: value # c\n- item **b** `c` \$VAR %V% \$(x) SELECT * FROM t WHERE a = 'b' -- c\nfunc main() { x := 1 }\n"
        val text = buildString { while (length < 300_000) append(unit) }
        for (language in Language.entries) {
            val start = System.nanoTime()
            val tokens = SyntaxHighlighter.tokenize(text, language)
            val millis = (System.nanoTime() - start) / 1_000_000
            assertInvariants(text, tokens)
            assertTrue("$language took $millis ms", millis < 2000)
        }
    }
}
