package com.nitinshukla.notebook.core.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Renders tokens as "TYPE:text TYPE:text" so expectations stay readable. */
internal fun render(text: String, language: Language): String =
    SyntaxHighlighter.tokenize(text, language).joinToString(" ") { "${it.type}:${text.substring(it.start, it.end)}" }

/** Asserts that the tokens of [text] render exactly as [expected]. */
internal fun assertTokens(expected: String, text: String, language: Language) {
    assertEquals("tokens of <$text> in $language", expected, render(text, language))
}

/** Asserts the tokenizer invariants: ascending, non-overlapping, in bounds, non-empty, no PLAIN. */
internal fun assertInvariants(text: CharSequence, tokens: List<Token>) {
    var last = 0
    for (t in tokens) {
        assertTrue("token $t starts before previous end $last", t.start >= last)
        assertTrue("token $t is empty or reversed", t.end > t.start)
        assertTrue("token $t exceeds length ${text.length}", t.end <= text.length)
        assertTrue("PLAIN token emitted: $t", t.type != TokenType.PLAIN)
        last = t.end
    }
}
