package com.nitinshukla.notebook.core

import com.nitinshukla.notebook.core.text.CursorPosition
import com.nitinshukla.notebook.core.text.EditHistory
import com.nitinshukla.notebook.core.text.FindOptions
import com.nitinshukla.notebook.core.text.LinkDetector
import com.nitinshukla.notebook.core.text.RichText
import com.nitinshukla.notebook.core.text.TextFinder
import com.nitinshukla.notebook.core.text.TextSnapshot
import com.nitinshukla.notebook.core.text.TextStats
import com.nitinshukla.notebook.core.text.TextStyleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolsTest {
    @Test fun `text stats count words lines and reading time`() {
        val s = TextStats.of("Hello world\n\nthird  line ")
        assertEquals(4, s.words)
        assertEquals(3, s.lines)
        assertEquals(25, s.characters)
        assertEquals(19, s.charactersNoSpaces)
        assertEquals(1, s.readingMinutes)
        assertEquals(0, TextStats.of("").words)
        assertEquals(2, TextStats.of(List(201) { "w" }.joinToString(" ")).readingMinutes)
    }

    @Test fun `cursor position is one based`() {
        assertEquals(CursorPosition(1, 1), CursorPosition.of("abc", 0))
        assertEquals(CursorPosition(2, 2), CursorPosition.of("ab\ncd", 4))
        assertEquals(CursorPosition(2, 3), CursorPosition.of("ab\ncd", 99))
    }

    @Test fun `find all respects case and whole word`() {
        val text = "Cat cat concatenate CAT"
        assertEquals(3, TextFinder.findAll(text, "cat", FindOptions(wholeWord = true)).size)
        assertEquals(4, TextFinder.findAll(text, "cat").size)
        assertEquals(listOf(4..6), TextFinder.findAll(text, "cat", FindOptions(matchCase = true, wholeWord = true)))
    }

    @Test fun `regex find and invalid regex`() {
        assertEquals(2, TextFinder.findAll("a1 b22", "\\d+", FindOptions(useRegex = true)).size)
        assertTrue(TextFinder.findAll("abc", "(", FindOptions(useRegex = true)).isEmpty())
        // Without regex mode special characters are literal.
        assertEquals(1, TextFinder.findAll("a(b", "(").size)
    }

    @Test fun `next and previous wrap around`() {
        val m = listOf(0..1, 5..6, 10..11)
        assertEquals(1, TextFinder.nextIndex(m, 3))
        assertEquals(0, TextFinder.nextIndex(m, 11))
        assertEquals(0, TextFinder.previousIndex(m, 3))
        assertEquals(2, TextFinder.previousIndex(m, 0))
        assertEquals(-1, TextFinder.nextIndex(emptyList(), 0))
    }

    @Test fun `replace one and replace all`() {
        assertEquals("a X c", TextFinder.replaceRange("a b c", 2..2, "X"))
        assertEquals("dog dog" to 2, TextFinder.replaceAll("cat Cat", "cat", "dog"))
        assertEquals("2024-01" to 1, TextFinder.replaceAll("01/2024", "(\\d+)/(\\d+)", "$2-$1", FindOptions(useRegex = true)))
    }

    @Test fun `edit history coalesces typing and supports redo`() {
        val h = EditHistory(coalesceWindowMs = 1000)
        h.record(TextSnapshot("", 0), TextSnapshot("h", 1), 0)
        h.record(TextSnapshot("h", 1), TextSnapshot("hi", 2), 100)
        h.record(TextSnapshot("hi", 2), TextSnapshot("hi ", 3), 200)
        h.record(TextSnapshot("hi ", 3), TextSnapshot("hi y", 4), 300)
        // "hi" was one run; the space ended it; "y" starts a new step.
        val u1 = h.undo(TextSnapshot("hi y", 4))
        assertEquals("hi ", u1?.text)
        val u2 = h.undo(u1!!)
        assertEquals("", u2?.text)
        assertFalse(h.canUndo)
        val r1 = h.redo(u2!!)
        assertEquals("hi ", r1?.text)
        h.record(r1!!, TextSnapshot("hi!", 3), 5000)
        assertFalse(h.canRedo)
        assertNull(EditHistory().undo(TextSnapshot("", 0)))
    }

    @Test fun `edit history respects max size`() {
        val h = EditHistory(maxEntries = 3, coalesceWindowMs = 0)
        var text = ""
        repeat(10) { i ->
            val next = "$text$i "
            h.record(TextSnapshot(text, text.length), TextSnapshot(next, next.length), i * 10_000L)
            text = next
        }
        var cur = TextSnapshot(text, text.length)
        var count = 0
        while (true) { cur = h.undo(cur) ?: break; count++ }
        assertEquals(3, count)
    }

    @Test fun `link detection`() {
        val links = LinkDetector.find("See https://example.com/a?b=1, and www.test.org.")
        assertEquals(listOf("https://example.com/a?b=1", "https://www.test.org"), links.map { it.url })
        assertEquals("https://example.com/a?b=1", "See https://example.com/a?b=1, and".substring(links[0].range))
        assertTrue(LinkDetector.isSafeWebUrl("https://a.com"))
        assertFalse(LinkDetector.isSafeWebUrl("javascript:alert(1)"))
        assertFalse(LinkDetector.isSafeWebUrl("file:///etc/passwd"))
    }

    @Test fun `rich text parse strips markers into spans`() {
        val p = RichText.parse("a **bold** _it_ ++u++ snake_case_word")
        assertEquals("a bold it u snake_case_word", p.plain)
        assertEquals(3, p.spans.size)
        val bold = p.spans.first { it.style == TextStyleKind.BOLD }
        assertEquals("bold", p.plain.substring(bold.start, bold.end))
        val under = p.spans.first { it.style == TextStyleKind.UNDERLINE }
        assertEquals("u", p.plain.substring(under.start, under.end))
    }

    @Test fun `rich text nested styles`() {
        val p = RichText.parse("**_both_**")
        assertEquals("both", p.plain)
        assertEquals(setOf(TextStyleKind.BOLD, TextStyleKind.ITALIC), p.spans.map { it.style }.toSet())
    }

    @Test fun `rich text toggle wraps and unwraps`() {
        val wrapped = RichText.toggle("hello world", 0, 5, TextStyleKind.BOLD)
        assertEquals("**hello** world", wrapped.text)
        assertEquals(2 to 7, wrapped.selectionStart to wrapped.selectionEnd)
        val unwrapped = RichText.toggle(wrapped.text, wrapped.selectionStart, wrapped.selectionEnd, TextStyleKind.BOLD)
        assertEquals("hello world", unwrapped.text)
        assertEquals(0 to 5, unwrapped.selectionStart to unwrapped.selectionEnd)
        val empty = RichText.toggle("ab", 1, 1, TextStyleKind.UNDERLINE)
        assertEquals("a++++b", empty.text)
        assertEquals(3, empty.selectionStart)
    }
}
