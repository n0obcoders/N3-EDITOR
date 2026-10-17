package com.nitinshukla.notebook.core

import com.nitinshukla.notebook.core.text.LineBuffer
import com.nitinshukla.notebook.core.text.LinePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineBufferTest {
    private fun buf(text: String) = LineBuffer(LineBuffer.split(text))

    @Test
    fun splitKeepsEmptyTrailingLine() {
        assertEquals(listOf("a", "b", ""), LineBuffer.split("a\nb\n"))
        assertEquals(listOf(""), LineBuffer.split(""))
    }

    @Test
    fun textRoundTripsAndCountsChars() {
        val b = buf("one\ntwo\n\nfour")
        assertEquals("one\ntwo\n\nfour", b.text())
        assertEquals(13L, b.charCount)
        b.setLine(1, "twenty")
        assertEquals(16L, b.charCount)
        assertEquals("one\ntwenty\n\nfour", b.text())
    }

    @Test
    fun splitJoinAndUndo() {
        val b = buf("hello world")
        b.splitLine(0, 5, "hello", " world")
        assertEquals(listOf("hello", " world"), b.lines)
        assertEquals(5, b.joinWithPrevious(1))
        assertEquals(listOf("hello world"), b.lines)
        assertTrue(b.canUndo)
        b.undo()
        assertEquals(listOf("hello", " world"), b.lines)
        b.undo()
        assertEquals(listOf("hello world"), b.lines)
        assertFalse(b.canUndo)
        b.redo()
        assertEquals(listOf("hello", " world"), b.lines)
    }

    @Test
    fun consecutiveEditsToOneLineAreOneUndoStep() {
        val b = buf("a\nb")
        b.setLine(1, "bc"); b.setLine(1, "bcd"); b.setLine(1, "bcde")
        assertNull(b.setLine(1, "bcde"))
        b.undo()
        assertEquals("b", b.lines[1])
        assertFalse(b.canUndo)
        b.breakRun()
        b.setLine(0, "x"); b.breakRun(); b.setLine(0, "xy")
        b.undo()
        assertEquals("x", b.lines[0])
    }

    @Test
    fun positionsAndOffsets() {
        val b = buf("ab\ncd\nef")
        assertEquals(LinePosition(0, 2), b.positionOf(2))
        assertEquals(LinePosition(1, 0), b.positionOf(3))
        assertEquals(LinePosition(2, 2), b.positionOf(99))
        assertEquals(3, b.offsetOf(1, 0))
        assertEquals(7, b.offsetOf(2, 1))
    }

    @Test
    fun swapAndRemove() {
        val b = buf("1\n2\n3")
        assertTrue(b.swapLines(0, 1))
        assertEquals(listOf("2", "1", "3"), b.lines)
        b.removeLine(2)
        assertEquals(listOf("2", "1"), b.lines)
        b.removeLine(0); b.removeLine(0)
        assertEquals(listOf(""), b.lines)
        assertEquals(0L, b.charCount)
    }

    @Test
    fun replaceAllIsOneUndoStep() {
        val b = buf("foo\nbar\nfoo foo\nbaz")
        val n = b.replaceAll { l -> val r = l.replace("foo", "x"); r to (l.split("foo").size - 1) }
        assertEquals(3, n)
        assertEquals(listOf("x", "bar", "x x", "baz"), b.lines)
        b.undo()
        assertEquals(listOf("foo", "bar", "foo foo", "baz"), b.lines)
    }

    @Test
    fun fiftyThousandLinesEditQuickly() {
        val b = LineBuffer(MutableList(50_000) { "line $it = value * 2  # comment" })
        val start = System.nanoTime()
        repeat(1_000) { b.setLine(25_000, "changed $it") }
        repeat(100) { b.splitLine(10, 2, "li", "ne 10"); b.joinWithPrevious(11) }
        val ms = (System.nanoTime() - start) / 1e6
        println("BENCH line_buffer lines=50000 edits=1200 ms=$ms")
        assertTrue("edits took $ms ms", ms < 2_000)
        assertEquals(50_000, b.lineCount)
    }
}
