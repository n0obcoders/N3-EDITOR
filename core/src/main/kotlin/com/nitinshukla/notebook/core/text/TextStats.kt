package com.nitinshukla.notebook.core.text

import kotlin.math.ceil

data class TextStats(
    val characters: Int,
    val charactersNoSpaces: Int,
    val words: Int,
    val lines: Int,
    val readingMinutes: Int,
) {
    companion object {
        const val WORDS_PER_MINUTE = 200

        fun of(text: String): TextStats {
            var words = 0
            var inWord = false
            var nonSpace = 0
            var lines = 1
            for (c in text) {
                if (c == '\n') lines++
                if (c.isWhitespace()) {
                    inWord = false
                } else {
                    nonSpace++
                    if (!inWord) {
                        words++
                        inWord = true
                    }
                }
            }
            val minutes = if (words == 0) 0 else ceil(words / WORDS_PER_MINUTE.toDouble()).toInt()
            return TextStats(text.length, nonSpace, words, lines, minutes)
        }
    }
}

/** One-based line and column of a cursor offset, as Notepad shows in its status bar. */
data class CursorPosition(val line: Int, val column: Int) {
    companion object {
        fun of(text: String, offset: Int): CursorPosition {
            val safe = offset.coerceIn(0, text.length)
            var line = 1
            var lineStart = 0
            for (i in 0 until safe) {
                if (text[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
            }
            return CursorPosition(line, safe - lineStart + 1)
        }
    }
}
