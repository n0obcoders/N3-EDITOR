package com.nitinshukla.notebook

import com.nitinshukla.notebook.core.code.Language
import com.nitinshukla.notebook.core.text.LinePosition
import com.nitinshukla.notebook.ui.code.OpenDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Storage-mode selection and the line-mode helpers of an editor tab (plain JVM, no Android). */
class OpenDocumentTest {
    private fun doc(text: String) = OpenDocument(uri = null, name = "a.py", language = Language.PYTHON, initialText = text)

    @Test
    fun smallDocumentsUseOneField() {
        val d = doc("print(1)\nprint(2)")
        assertFalse(d.large)
        assertEquals("print(1)\nprint(2)", d.fullText())
        assertEquals(2, d.lineCount)
        assertEquals(LinePosition(0, 0), d.caret())
    }

    @Test
    fun fiftyThousandLinesBecomeALargeDocument() {
        val text = (1..50_000).joinToString("\n") { "x = $it" }
        val d = doc(text)
        assertTrue(d.large)
        assertEquals(50_000, d.lineCount)
        assertEquals(50_000, d.lines.size)
        assertEquals(text.length.toLong(), d.charCount)
        assertEquals(text, d.fullText())
        assertEquals("x = 50000", d.lines.last())
        assertEquals(9, d.longestLine)
        d.focusAt(49_999, 3)
        assertEquals(LinePosition(49_999, 3), d.caret())
        assertEquals(text.length - 6, d.caretOffset())
        d.resetLines("a\nb")
        assertEquals(listOf("a", "b"), d.lines.toList())
        assertEquals(1, d.longestLine)
    }

    @Test
    fun longSingleLineTextIsLargeToo() {
        assertTrue(OpenDocument.isLargeText("x".repeat(OpenDocument.LARGE_CHARS + 1)))
        assertFalse(OpenDocument.isLargeText("x".repeat(1000)))
    }
}
