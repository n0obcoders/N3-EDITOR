package com.nitinshukla.notebook.core.code

/** The newline convention of a document. */
enum class LineEnding(val chars: String, val displayName: String) {
    LF("\n", "LF"),
    CRLF("\r\n", "CRLF"),
    CR("\r", "CR");

    companion object {
        /** The most frequent line ending in [text]; LF when there is none or on a tie with LF. */
        fun detect(text: String): LineEnding {
            var lf = 0
            var crlf = 0
            var cr = 0
            var i = 0
            val n = text.length
            while (i < n) {
                when (text[i]) {
                    '\n' -> lf++
                    '\r' -> if (i + 1 < n && text[i + 1] == '\n') { crlf++; i++ } else cr++
                }
                i++
            }
            return when {
                crlf > lf && crlf >= cr -> CRLF
                cr > lf && cr > crlf -> CR
                else -> LF
            }
        }
    }
}

/** Conversions between line ending conventions. */
object LineEndings {
    /** Replaces CRLF and lone CR with LF. */
    fun normalizeToLf(text: String): String {
        if (text.indexOf('\r') < 0) return text
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\r') {
                sb.append('\n')
                if (i + 1 < n && text[i + 1] == '\n') i++
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Converts LF-normalized text to [ending]. Text that still contains CR is normalized first. */
    fun apply(textWithLf: String, ending: LineEnding): String {
        val lf = normalizeToLf(textWithLf)
        return if (ending == LineEnding.LF) lf else lf.replace("\n", ending.chars)
    }
}
