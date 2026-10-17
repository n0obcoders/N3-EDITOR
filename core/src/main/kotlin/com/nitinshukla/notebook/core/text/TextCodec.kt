package com.nitinshukla.notebook.core.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** How a text file was encoded on disk, so it can be written back the same way. */
data class TextEncoding(val charset: Charset, val bom: Boolean) {
    val displayName: String get() = charset.name().let { if (bom) "$it BOM" else it }

    companion object {
        val UTF8 = TextEncoding(Charsets.UTF_8, bom = false)
    }
}

/** Line separator style of a file. Text is kept with '\n' in memory and converted on write. */
enum class LineSeparator(val chars: String, val displayName: String) {
    LF("\n", "LF"), CRLF("\r\n", "CRLF"), CR("\r", "CR");

    companion object {
        /** The majority separator in [text]; LF when there are none. */
        fun detect(text: CharSequence): LineSeparator {
            var crlf = 0
            var lf = 0
            var cr = 0
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                if (c == '\r') {
                    if (i + 1 < n && text[i + 1] == '\n') { crlf++; i++ } else cr++
                } else if (c == '\n') {
                    lf++
                }
                i++
            }
            return when {
                crlf >= lf && crlf >= cr && crlf > 0 -> CRLF
                cr > lf && cr > 0 -> CR
                else -> LF
            }
        }
    }
}

data class DecodedText(
    val text: String,
    val encoding: TextEncoding,
    val lineSeparator: LineSeparator,
    /** True when the bytes were not valid in the detected charset and were decoded leniently. */
    val hadDecodingErrors: Boolean,
    /** Likely a binary file: NUL bytes or a high share of control characters. */
    val looksBinary: Boolean,
)

/**
 * Decodes files for the code editors without losing what the file looked like: byte-order
 * marks, UTF-16, and CRLF/CR line endings are detected, normalised to '\n' for editing, and
 * restored by [encode]. Pure JVM so it is unit-testable.
 */
object TextCodec {
    private val UTF16LE: Charset = Charset.forName("UTF-16LE")
    private val UTF16BE: Charset = Charset.forName("UTF-16BE")

    fun decode(bytes: ByteArray): DecodedText {
        val (encoding, offset) = detectEncoding(bytes)
        val body = if (offset == 0) bytes else bytes.copyOfRange(offset, bytes.size)
        var errors = false
        val raw = try {
            encoding.charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (e: CharacterCodingException) {
            errors = true
            String(body, encoding.charset)
        }
        val separator = LineSeparator.detect(raw)
        val text = if (separator == LineSeparator.LF && raw.indexOf('\r') < 0) raw else normalize(raw)
        return DecodedText(text, encoding, separator, errors, looksBinary(body, raw))
    }

    fun encode(text: String, encoding: TextEncoding, separator: LineSeparator): ByteArray {
        val withSeparators = if (separator == LineSeparator.LF) text else text.replace("\n", separator.chars)
        val body = withSeparators.toByteArray(encoding.charset)
        if (!encoding.bom) return body
        val bom = when (encoding.charset) {
            Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            UTF16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            UTF16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> ByteArray(0)
        }
        return bom + body
    }

    /** Converts CRLF and lone CR to LF. */
    fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    /** Returns the encoding and the number of BOM bytes to skip. */
    fun detectEncoding(bytes: ByteArray): Pair<TextEncoding, Int> {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return TextEncoding(Charsets.UTF_8, true) to 3
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return TextEncoding(UTF16LE, true) to 2
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return TextEncoding(UTF16BE, true) to 2
        // UTF-16 without a BOM shows up as NULs in every other byte of ASCII text.
        if (bytes.size >= 4 && bytes.size % 2 == 0) {
            val sample = minOf(bytes.size, 512)
            var evenNul = 0
            var oddNul = 0
            for (i in 0 until sample) if (bytes[i] == 0.toByte()) { if (i % 2 == 0) evenNul++ else oddNul++ }
            if (oddNul > sample / 4 && evenNul == 0) return TextEncoding(UTF16LE, false) to 0
            if (evenNul > sample / 4 && oddNul == 0) return TextEncoding(UTF16BE, false) to 0
        }
        return TextEncoding.UTF8 to 0
    }

    private fun looksBinary(bytes: ByteArray, decoded: String): Boolean {
        if (bytes.isEmpty()) return false
        val sample = minOf(bytes.size, 8_000)
        var control = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C && b != 0x1B) control++
        }
        if (control * 10 > sample) return true
        return decoded.isEmpty() && bytes.isNotEmpty()
    }
}
