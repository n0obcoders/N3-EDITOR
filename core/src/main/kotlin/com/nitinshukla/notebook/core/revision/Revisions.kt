package com.nitinshukla.notebook.core.revision

import com.nitinshukla.notebook.core.model.Revision

data class RevisionPolicy(
    /** Minimum time between automatic snapshots of the same note. */
    val minIntervalMs: Long = 10 * 60 * 1000L,
    val maxPerNote: Int = 20,
    /** Upper bound on the estimated size of all revisions together. */
    val maxTotalBytes: Long = 20L * 1024 * 1024,
)

object RevisionRules {

    /**
     * Whether to snapshot [title]/[content] now. A snapshot is taken when the text differs
     * from the newest revision and enough time has passed, or [force] is set (manual save).
     */
    fun shouldSnapshot(
        latest: Revision?,
        title: String,
        content: String,
        now: Long,
        policy: RevisionPolicy,
        force: Boolean = false,
    ): Boolean {
        if (title.isBlank() && content.isBlank()) return false
        if (latest == null) return true
        if (latest.title == title && latest.content == content) return false
        return force || now - latest.createdAt >= policy.minIntervalMs
    }

    /** Ids to delete so that at most [maxPerNote] newest revisions remain for one note. */
    fun excessForNote(revisions: List<Revision>, maxPerNote: Int): List<Long> =
        revisions.sortedByDescending { it.createdAt }.drop(maxPerNote.coerceAtLeast(0)).map { it.id }

    /** Ids to delete, oldest first across all notes, until the total fits in [maxTotalBytes]. */
    fun excessForStorage(all: List<Revision>, maxTotalBytes: Long): List<Long> {
        var total = all.sumOf { it.sizeBytes }
        if (total <= maxTotalBytes) return emptyList()
        val out = mutableListOf<Long>()
        for (r in all.sortedBy { it.createdAt }) {
            if (total <= maxTotalBytes) break
            out += r.id
            total -= r.sizeBytes
        }
        return out
    }
}

enum class DiffOp { EQUAL, ADDED, REMOVED }

data class DiffLine(val op: DiffOp, val text: String)

/** Line-based diff for comparing a revision with the current text. */
object LineDiff {
    /** Above this many line pairs the LCS table is skipped and a coarse diff is returned. */
    private const val MAX_CELLS = 4_000_000L

    fun diff(old: String, new: String): List<DiffLine> {
        val a = old.split('\n')
        val b = new.split('\n')
        // Trim common prefix and suffix to keep the table small.
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val aMid = a.subList(prefix, a.size - suffix)
        val bMid = b.subList(prefix, b.size - suffix)
        val result = mutableListOf<DiffLine>()
        a.subList(0, prefix).forEach { result += DiffLine(DiffOp.EQUAL, it) }
        if (aMid.size.toLong() * bMid.size > MAX_CELLS) {
            aMid.forEach { result += DiffLine(DiffOp.REMOVED, it) }
            bMid.forEach { result += DiffLine(DiffOp.ADDED, it) }
        } else {
            result += lcsDiff(aMid, bMid)
        }
        a.subList(a.size - suffix, a.size).forEach { result += DiffLine(DiffOp.EQUAL, it) }
        return result
    }

    private fun lcsDiff(a: List<String>, b: List<String>): List<DiffLine> {
        val n = a.size
        val m = b.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        val out = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += DiffLine(DiffOp.EQUAL, a[i]); i++; j++ }
                table[i + 1][j] >= table[i][j + 1] -> { out += DiffLine(DiffOp.REMOVED, a[i]); i++ }
                else -> { out += DiffLine(DiffOp.ADDED, b[j]); j++ }
            }
        }
        while (i < n) out += DiffLine(DiffOp.REMOVED, a[i++])
        while (j < m) out += DiffLine(DiffOp.ADDED, b[j++])
        return out
    }
}
