package com.nitinshukla.notebook.core.code

/** Highlight category of a token; the UI maps each to a colour. */
enum class TokenType {
    KEYWORD, TYPE, BUILTIN, STRING, CHAR, COMMENT, NUMBER, FUNCTION, OPERATOR, PUNCTUATION,
    TAG, ATTRIBUTE, DECORATOR, VARIABLE, CONSTANT, HEADING, LINK, ERROR, PLAIN,
}

/** Half-open range [start, end) into the text. Tokens are non-overlapping and sorted; untokenized gaps are PLAIN and need not be emitted. */
data class Token(val start: Int, val end: Int, val type: TokenType)
