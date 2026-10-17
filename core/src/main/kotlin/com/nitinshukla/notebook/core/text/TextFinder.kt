package com.nitinshukla.notebook.core.text

data class FindOptions(
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val useRegex: Boolean = false,
)

/** Find and replace, matching Notepad's Find / Find next / Replace / Replace all. */
object TextFinder {

    /** Builds the pattern for [query], or null when it is empty or an invalid regex. */
    fun pattern(query: String, options: FindOptions): Regex? {
        if (query.isEmpty()) return null
        val body = if (options.useRegex) query else Regex.escape(query)
        val bounded = if (options.wholeWord) "(?<![\\p{L}\\p{N}_])(?:$body)(?![\\p{L}\\p{N}_])" else body
        val opts = buildSet {
            if (!options.matchCase) add(RegexOption.IGNORE_CASE)
            if (options.useRegex) add(RegexOption.MULTILINE)
        }
        return try {
            Regex(bounded, opts)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** All non-empty matches as ranges with exclusive end. */
    fun findAll(text: String, query: String, options: FindOptions = FindOptions()): List<IntRange> {
        val regex = pattern(query, options) ?: return emptyList()
        return regex.findAll(text).filter { it.value.isNotEmpty() }.map { it.range.first until it.range.last + 1 }.toList()
    }

    /** Index of the first match starting at or after [cursor], wrapping to the start. */
    fun nextIndex(matches: List<IntRange>, cursor: Int): Int {
        if (matches.isEmpty()) return -1
        val i = matches.indexOfFirst { it.first >= cursor }
        return if (i >= 0) i else 0
    }

    /** Index of the last match starting before [cursor], wrapping to the end. */
    fun previousIndex(matches: List<IntRange>, cursor: Int): Int {
        if (matches.isEmpty()) return -1
        val i = matches.indexOfLast { it.first < cursor }
        return if (i >= 0) i else matches.lastIndex
    }

    /** Replaces the text in [range] (exclusive end). */
    fun replaceRange(text: String, range: IntRange, replacement: String): String =
        text.substring(0, range.first) + replacement + text.substring(range.last + 1)

    /** Returns the new text and how many replacements were made. */
    fun replaceAll(text: String, query: String, replacement: String, options: FindOptions = FindOptions()): Pair<String, Int> {
        val regex = pattern(query, options) ?: return text to 0
        var count = 0
        val result = regex.replace(text) { m ->
            if (m.value.isEmpty()) {
                m.value
            } else {
                count++
                if (options.useRegex) m.expandReplacement(replacement) else replacement
            }
        }
        return result to count
    }

    private fun MatchResult.expandReplacement(replacement: String): String =
        Regex("\\$(\\d)").replace(replacement) { g ->
            groupValues.getOrNull(g.groupValues[1].toInt()).orEmpty()
        }
}
