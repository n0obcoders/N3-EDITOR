package com.nitinshukla.notebook.core

import com.nitinshukla.notebook.core.text.LineSeparator
import com.nitinshukla.notebook.core.text.TextCodec
import com.nitinshukla.notebook.core.text.TextEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCodecTest {
    @Test
    fun utf8WithoutBomAndLf() {
        val d = TextCodec.decode("a\nb\n".toByteArray())
        assertEquals("a\nb\n", d.text)
        assertEquals(LineSeparator.LF, d.lineSeparator)
        assertEquals(TextEncoding.UTF8, d.encoding)
        assertFalse(d.hadDecodingErrors)
        assertFalse(d.looksBinary)
    }

    @Test
    fun crlfIsNormalisedAndRestored() {
        val original = "x\r\ny\r\n".toByteArray()
        val d = TextCodec.decode(original)
        assertEquals("x\ny\n", d.text)
        assertEquals(LineSeparator.CRLF, d.lineSeparator)
        assertArrayEquals(original, TextCodec.encode(d.text, d.encoding, d.lineSeparator))
    }

    @Test
    fun utf8BomRoundTrips() {
        val original = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo".toByteArray()
        val d = TextCodec.decode(original)
        assertEquals("héllo", d.text)
        assertTrue(d.encoding.bom)
        assertEquals("UTF-8 BOM", d.encoding.displayName)
        assertArrayEquals(original, TextCodec.encode(d.text, d.encoding, d.lineSeparator))
    }

    @Test
    fun utf16LeWithBomRoundTrips() {
        val original = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi\r\n".toByteArray(Charsets.UTF_16LE)
        val d = TextCodec.decode(original)
        assertEquals("hi\n", d.text)
        assertEquals("UTF-16LE BOM", d.encoding.displayName)
        assertEquals(LineSeparator.CRLF, d.lineSeparator)
        assertArrayEquals(original, TextCodec.encode(d.text, d.encoding, d.lineSeparator))
    }

    @Test
    fun utf16WithoutBomIsGuessedFromNulPattern() {
        val original = "print('x')\n".toByteArray(Charsets.UTF_16LE)
        val d = TextCodec.decode(original)
        assertEquals("print('x')\n", d.text)
        assertFalse(d.encoding.bom)
        assertEquals("UTF-16LE", d.encoding.charset.name())
    }

    @Test
    fun invalidUtf8IsDecodedLenientlyAndFlagged() {
        val d = TextCodec.decode(byteArrayOf('a'.code.toByte(), 0xC3.toByte(), 0x28, 'b'.code.toByte()))
        assertTrue(d.hadDecodingErrors)
        assertTrue(d.text.startsWith("a"))
        assertTrue(d.text.endsWith("b"))
    }

    @Test
    fun binaryDetection() {
        assertTrue(TextCodec.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)).looksBinary)
        assertFalse(TextCodec.decode("plain\ttext\n".toByteArray()).looksBinary)
        assertFalse(TextCodec.decode(ByteArray(0)).looksBinary)
    }

    @Test
    fun lineSeparatorMajorityWins() {
        assertEquals(LineSeparator.CRLF, LineSeparator.detect("a\r\nb\r\nc\n"))
        assertEquals(LineSeparator.LF, LineSeparator.detect("a\nb\nc\r\n"))
        assertEquals(LineSeparator.CR, LineSeparator.detect("a\rb\rc"))
        assertEquals(LineSeparator.LF, LineSeparator.detect("no breaks"))
        assertEquals("a\nb\nc", TextCodec.normalize("a\r\nb\rc"))
    }
}
