package com.nitinshukla.notebook.ui.code

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.nitinshukla.notebook.core.code.Issue
import com.nitinshukla.notebook.core.code.Language
import com.nitinshukla.notebook.core.code.Token
import com.nitinshukla.notebook.core.text.EditHistory
import com.nitinshukla.notebook.core.text.LineBuffer
import com.nitinshukla.notebook.core.text.LinePosition
import com.nitinshukla.notebook.core.text.LineSeparator
import com.nitinshukla.notebook.core.text.TextEncoding
import com.nitinshukla.notebook.ui.editor.FindState
import java.util.UUID

/**
 * One editor tab. Compose state lives per document so typing in one tab recomposes only
 * that tab. Dirtiness is revision based: [editRevision] advances on every edit and
 * [savedRevision] records which revision reached disk, so a save that completes while the
 * user keeps typing never clears the marker for edits it did not include.
 *
 * Two storage modes share this class. Small documents live in one [text] field that a single
 * text field edits. Documents over [LARGE_LINES] lines or [LARGE_CHARS] characters are
 * [large]: their text lives in [lines] (a [LineBuffer] over a snapshot list) and the editor
 * shows one small text field per visible line, so a 50 000-line file costs only the lines
 * on screen. In large mode [text] is unused; call [fullText] for the whole content.
 */
class OpenDocument(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    uri: Uri?,
    name: String,
    language: Language,
    initialText: String,
    encoding: TextEncoding = TextEncoding.UTF8,
    separator: LineSeparator = LineSeparator.LF,
    /** Last-modified time of the file when it was read, to notice outside changes. */
    var diskModified: Long = 0L,
    dirtyOnLoad: Boolean = false,
    forceLarge: Boolean = false,
) {
    var uri by mutableStateOf(uri)
    var name by mutableStateOf(name)
    var language by mutableStateOf(language)
    var encoding by mutableStateOf(encoding)
    var separator by mutableStateOf(separator)

    /** True when the document is edited line by line. Fixed for the document's lifetime. */
    val large: Boolean = forceLarge || isLargeText(initialText)

    var text by mutableStateOf(TextFieldValue(if (large) "" else initialText))

    /** Line storage for large documents; empty list otherwise. */
    val lines = mutableStateListOf<String>()
    val buffer: LineBuffer? = if (large) LineBuffer(lines.also { it.addAll(LineBuffer.split(initialText)) }) else null

    /** Large mode: zero-based caret line and column, as the status bar shows them. */
    var cursorLine by mutableIntStateOf(0)
    var cursorColumn by mutableIntStateOf(0)
    /** Large mode: the line that should take focus, with its selection, when [focusRequest] changes. */
    var focusLine by mutableIntStateOf(-1)
    var focusSelection by mutableStateOf(TextRange.Zero)
    var focusRequest by mutableIntStateOf(0)
    /** The [focusRequest] the editor already acted on, so a row scrolled back into view does not steal focus. */
    var focusHandled: Int = 0
    /** Large mode: longest line length seen, used to size the horizontal scroll area. */
    var longestLine by mutableIntStateOf(if (large) lines.maxOfOrNull { it.length } ?: 0 else 0)

    var tokens by mutableStateOf<List<Token>>(emptyList())
    /** Revision the current [tokens] were computed for; stale tokens are not drawn. */
    var tokensRevision by mutableIntStateOf(-1)
    var issues by mutableStateOf<List<Issue>>(emptyList())
    var editRevision by mutableIntStateOf(if (dirtyOnLoad) 1 else 0)
    var savedRevision by mutableIntStateOf(0)
    var lastSavedAt by mutableStateOf<Long?>(null)
    var saveError by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
    var find by mutableStateOf(FindState())
    var revealSelection by mutableIntStateOf(0)
    var readOnly by mutableStateOf(false)
    /** Set when the file changed on disk after it was loaded, until the user reloads or saves. */
    var changedOnDisk by mutableStateOf(false)

    val history = EditHistory()
    val dirty: Boolean get() = editRevision != savedRevision
    val isUntitled: Boolean get() = uri == null
    /** Line start offsets of [text], recomputed once per text change and shared by the editor and status bar. */
    val lineStarts: IntArray by androidx.compose.runtime.derivedStateOf { lineStartOffsets(text.text) }
    val lineCount: Int get() = if (large) lines.size else lineStarts.size
    val charCount: Long get() = if (large) buffer!!.charCount else text.text.length.toLong()

    /** The whole content. In large mode this joins every line, so call it only to save, check or search. */
    fun fullText(): String = if (large) buffer!!.text() else text.text

    /** Caret position as zero-based line and column in either mode. */
    fun caret(): LinePosition = if (large) LinePosition(cursorLine, cursorColumn) else {
        // Binary search over the cached line starts instead of a scan from the top per keystroke.
        val starts = lineStarts
        val at = text.selection.start.coerceIn(0, text.text.length)
        val line = lineNumberAt(starts, at) - 1
        LinePosition(line, at - starts[line])
    }

    /** Absolute caret offset in either mode. */
    fun caretOffset(): Int = if (large) buffer!!.offsetOf(cursorLine, cursorColumn) else text.selection.start

    /** Large mode: moves the caret and asks the editor to focus that line. */
    fun focusAt(line: Int, column: Int, selectionEnd: Int = column) {
        val l = line.coerceIn(0, lines.lastIndex)
        val len = lines[l].length
        cursorLine = l
        cursorColumn = column.coerceIn(0, len)
        focusLine = l
        focusSelection = TextRange(cursorColumn, selectionEnd.coerceIn(0, len))
        focusRequest++
    }

    /** Large mode: replaces the whole content (reload, replace all). */
    fun resetLines(newText: String) {
        val b = buffer ?: return
        b.replaceLines(0, lines.size, LineBuffer.split(newText), record = false)
        longestLine = lines.maxOfOrNull { it.length } ?: 0
    }

    companion object {
        // One BasicTextField re-lays out the whole file on every keystroke; past these sizes the
        // line-by-line editor is much smoother on phones.
        const val LARGE_LINES = 1_500
        const val LARGE_CHARS = 80_000

        fun isLargeText(text: String): Boolean {
            if (text.length > LARGE_CHARS) return true
            var n = 1
            for (c in text) if (c == '\n' && ++n > LARGE_LINES) return true
            return false
        }
    }
}
