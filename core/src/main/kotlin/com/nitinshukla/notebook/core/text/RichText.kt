package com.nitinshukla.notebook.core.text

enum class TextStyleKind(val marker: String) {
    BOLD("**"),
    ITALIC("_"),
    UNDERLINE("++"),
}

data class StyledRange(val start: Int, val end: Int, val style: TextStyleKind)

data class ParsedRichText(val plain: String, val spans: List<StyledRange>)

/**
 * Lightweight inline markup for rich-text notes: **bold**, _italic_ and ++underline++.
 * Notes stay readable as plain text and survive export to .txt.
 */
object RichText {
    private val patterns = mapOf(
        TextStyleKind.BOLD to Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*"),
        TextStyleKind.ITALIC to Regex("(?<![\\p{L}\\p{N}_])_(?=\\S)(.+?)(?<=\\S)_(?![\\p{L}\\p{N}_])"),
        TextStyleKind.UNDERLINE to Regex("\\+\\+(?=\\S)(.+?)(?<=\\S)\\+\\+"),
    )

    fun parse(source: String): ParsedRichText {
        val removed = BooleanArray(source.length)
        val rawSpans = mutableListOf<Triple<Int, Int, TextStyleKind>>()
        for ((style, regex) in patterns) {
            for (m in regex.findAll(source)) {
                val markerLen = style.marker.length
                val openStart = m.range.first
                val closeStart = m.range.last + 1 - markerLen
                // Skip matches whose markers were already consumed by another style.
                if ((openStart until openStart + markerLen).any { removed[it] }) continue
                if ((closeStart until closeStart + markerLen).any { removed[it] }) continue
                for (i in openStart until openStart + markerLen) removed[i] = true
                for (i in closeStart until closeStart + markerLen) removed[i] = true
                rawSpans += Triple(openStart + markerLen, closeStart, style)
            }
        }
        val map = IntArray(source.length + 1)
        val plain = StringBuilder(source.length)
        for (i in source.indices) {
            map[i] = plain.length
            if (!removed[i]) plain.append(source[i])
        }
        map[source.length] = plain.length
        val spans = rawSpans.map { (s, e, style) -> StyledRange(map[s], map[e], style) }
            .filter { it.end > it.start }
            .sortedBy { it.start }
        return ParsedRichText(plain.toString(), spans)
    }

    fun toPlainText(source: String): String = parse(source).plain

    data class Edit(val text: String, val selectionStart: Int, val selectionEnd: Int)

    /**
     * Wraps the selection in [style]'s markers, or removes them when the selection is
     * already wrapped. With an empty selection, inserts an empty pair with the cursor inside.
     */
    fun toggle(text: String, selectionStart: Int, selectionEnd: Int, style: TextStyleKind): Edit {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val m = style.marker
        val wrappedOutside = start >= m.length && end + m.length <= text.length &&
            text.substring(start - m.length, start) == m && text.substring(end, end + m.length) == m
        if (wrappedOutside) {
            val newText = text.substring(0, start - m.length) + text.substring(start, end) + text.substring(end + m.length)
            return Edit(newText, start - m.length, end - m.length)
        }
        val selected = text.substring(start, end)
        if (selected.length >= 2 * m.length && selected.startsWith(m) && selected.endsWith(m)) {
            val inner = selected.substring(m.length, selected.length - m.length)
            val newText = text.substring(0, start) + inner + text.substring(end)
            return Edit(newText, start, start + inner.length)
        }
        val newText = text.substring(0, start) + m + selected + m + text.substring(end)
        return Edit(newText, start + m.length, end + m.length)
    }
}
