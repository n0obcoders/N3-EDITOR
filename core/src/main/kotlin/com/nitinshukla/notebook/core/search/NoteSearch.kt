package com.nitinshukla.notebook.core.search

import com.nitinshukla.notebook.core.model.NoteSummary
import com.nitinshukla.notebook.core.model.NoteType

/** Which collection of notes a screen is showing. */
enum class NoteSection { NOTES, CODE, ARCHIVE, TRASH }

enum class SortOrder(val displayName: String) {
    MODIFIED_DESC("Last modified"),
    MODIFIED_ASC("Oldest modified"),
    CREATED_DESC("Newest created"),
    CREATED_ASC("Oldest created"),
    TITLE_ASC("Title A–Z"),
    TITLE_DESC("Title Z–A"),
    MANUAL("Manual");

    companion object {
        fun fromName(name: String?): SortOrder = entries.firstOrNull { it.name == name } ?: MODIFIED_DESC
    }
}

data class NoteFilter(
    val section: NoteSection = NoteSection.NOTES,
    val query: String = "",
    val type: NoteType? = null,
    val pinnedOnly: Boolean = false,
    val checklistOnly: Boolean = false,
    val withAttachments: Boolean = false,
    /** Inclusive lower bound on the modified timestamp. */
    val modifiedFrom: Long? = null,
    /** Inclusive upper bound on the modified timestamp. */
    val modifiedTo: Long? = null,
    val sort: SortOrder = SortOrder.MODIFIED_DESC,
) {
    val hasRefinements: Boolean
        get() = type != null || pinnedOnly || checklistOnly || withAttachments || modifiedFrom != null || modifiedTo != null
}

/** Where a search term was found, for display. */
enum class MatchField { TITLE, CONTENT, FILE_NAME }

/**
 * Pure, in-memory filtering and sorting over note summaries. Summaries carry only a content
 * preview, so callers that need full-text matching pass the ids the database found for the
 * query in [fullTextIds]; a note matches when its summary matches every term or its id is in
 * that set. Locked notes only match on their title, so protected content is never surfaced.
 */
object NoteSearch {

    fun apply(items: List<NoteSummary>, filter: NoteFilter, fullTextIds: Set<Long>? = null): List<NoteSummary> {
        val terms = tokenize(filter.query)
        val filtered = items.filter { note ->
            inSection(note, filter.section) &&
                passesRefinements(note, filter) &&
                (terms.isEmpty() || (fullTextIds != null && note.id in fullTextIds) || matchesAll(note, terms))
        }
        return sort(filtered, filter.sort)
    }

    fun inSection(note: NoteSummary, section: NoteSection): Boolean = when (section) {
        NoteSection.NOTES -> !note.isTrashed && !note.isArchived
        NoteSection.CODE -> !note.isTrashed && !note.isArchived && note.type == NoteType.CODE
        NoteSection.ARCHIVE -> !note.isTrashed && note.isArchived
        NoteSection.TRASH -> note.isTrashed
    }

    private fun passesRefinements(note: NoteSummary, filter: NoteFilter): Boolean {
        if (filter.type != null && note.type != filter.type) return false
        if (filter.pinnedOnly && !note.isPinned) return false
        if (filter.checklistOnly && note.type != NoteType.CHECKLIST && note.checklistTotal == 0) return false
        if (filter.withAttachments && !note.hasAttachments) return false
        if (filter.modifiedFrom != null && note.updatedAt < filter.modifiedFrom) return false
        if (filter.modifiedTo != null && note.updatedAt > filter.modifiedTo) return false
        return true
    }

    /** Splits a query into lowercase terms; quoted phrases stay together. */
    fun tokenize(query: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("\"([^\"]+)\"|(\\S+)")
        regex.findAll(query.trim()).forEach { m ->
            val term = (m.groups[1]?.value ?: m.groups[2]?.value).orEmpty().trim().lowercase()
            if (term.isNotEmpty()) result += term
        }
        return result
    }

    fun matches(note: NoteSummary, query: String): Boolean {
        val terms = tokenize(query)
        return terms.isEmpty() || matchesAll(note, terms)
    }

    private fun matchesAll(note: NoteSummary, terms: List<String>): Boolean =
        terms.all { term -> matchField(note, term) != null }

    /** The first field containing [term], or null. [term] must already be lowercase. */
    fun matchField(note: NoteSummary, term: String): MatchField? = when {
        note.title.contains(term, ignoreCase = true) -> MatchField.TITLE
        !note.isLocked && note.preview.contains(term, ignoreCase = true) -> MatchField.CONTENT
        note.fileName?.contains(term, ignoreCase = true) == true -> MatchField.FILE_NAME
        else -> null
    }

    /**
     * Pinned notes come first (except in trash), then the chosen order. Ties fall back to
     * id so the result is deterministic.
     */
    fun sort(items: List<NoteSummary>, order: SortOrder): List<NoteSummary> {
        val byOrder: Comparator<NoteSummary> = when (order) {
            SortOrder.MODIFIED_DESC -> compareByDescending { it.updatedAt }
            SortOrder.MODIFIED_ASC -> compareBy { it.updatedAt }
            SortOrder.CREATED_DESC -> compareByDescending { it.createdAt }
            SortOrder.CREATED_ASC -> compareBy { it.createdAt }
            SortOrder.TITLE_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { displayTitle(it) }
            SortOrder.TITLE_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { displayTitle(it) }
            SortOrder.MANUAL -> compareBy { it.sortOrder }
        }
        return items.sortedWith(byOrder.thenBy { it.id })
    }

    /** The title, or the first line of content when the title is empty. */
    fun displayTitle(note: NoteSummary): String = displayTitle(note.title, if (note.isLocked) "" else note.preview)

    fun displayTitle(title: String, content: String): String {
        if (title.isNotBlank()) return title.trim()
        return content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    /** Ranges (end exclusive) of every case-insensitive occurrence of each term in [text]. */
    fun highlightRanges(text: String, query: String): List<IntRange> {
        val terms = tokenize(query)
        if (terms.isEmpty() || text.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        for (term in terms) {
            var index = text.indexOf(term, ignoreCase = true)
            while (index >= 0) {
                ranges += index until index + term.length
                index = text.indexOf(term, index + term.length, ignoreCase = true)
            }
        }
        return mergeRanges(ranges)
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return ranges
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf(sorted.first())
        for (r in sorted.drop(1)) {
            val last = merged.last()
            if (r.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
            } else {
                merged += r
            }
        }
        return merged
    }

    /**
     * A short excerpt of [text] around the first match of [query], with ellipses where it
     * was cut. Returns the start of the text when nothing matches.
     */
    fun snippet(text: String, query: String, maxLength: Int = 160): String {
        val flat = text.replace('\n', ' ')
        val first = highlightRanges(flat, query).firstOrNull()
        if (first == null || flat.length <= maxLength) {
            return if (flat.length <= maxLength) flat else flat.take(maxLength).trimEnd() + "…"
        }
        val start = (first.first - maxLength / 3).coerceAtLeast(0)
        val end = (start + maxLength).coerceAtMost(flat.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        return prefix + flat.substring(start, end).trim() + suffix
    }

    /**
     * Suggestions for the search box: recent searches that start with the prefix, then
     * note titles that contain it.
     */
    fun suggestions(
        items: List<NoteSummary>,
        recentSearches: List<String>,
        prefix: String,
        limit: Int = 8,
    ): List<String> {
        val p = prefix.trim()
        if (p.isEmpty()) return recentSearches.take(limit)
        val out = LinkedHashSet<String>()
        recentSearches.filter { it.startsWith(p, ignoreCase = true) && !it.equals(p, ignoreCase = true) }
            .forEach { out += it }
        items.asSequence()
            .filter { !it.isTrashed }
            .map { it.title.trim() }
            .filter { it.isNotEmpty() && it.contains(p, ignoreCase = true) && !it.equals(p, ignoreCase = true) }
            .distinct()
            .forEach { out += it }
        return out.take(limit)
    }

    /** Adds [query] to the front of the recent-search list, de-duplicated and capped. */
    fun rememberSearch(recent: List<String>, query: String, max: Int = 10): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return recent
        return (listOf(q) + recent.filterNot { it.equals(q, ignoreCase = true) }).take(max)
    }

    /** SQL LIKE pattern for a term, with the wildcard characters escaped (escape char '\'). */
    fun likePattern(term: String): String =
        "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
