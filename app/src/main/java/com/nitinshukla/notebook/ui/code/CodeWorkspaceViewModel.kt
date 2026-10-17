package com.nitinshukla.notebook.ui.code

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nitinshukla.notebook.core.code.CodeChecker
import com.nitinshukla.notebook.core.code.CodeEditing
import com.nitinshukla.notebook.core.code.Language
import com.nitinshukla.notebook.core.code.SyntaxHighlighter
import com.nitinshukla.notebook.core.code.TextEdit
import com.nitinshukla.notebook.core.repository.SettingsRepository
import com.nitinshukla.notebook.core.settings.AppSettings
import com.nitinshukla.notebook.core.text.CodeFileTypes
import com.nitinshukla.notebook.core.text.FindOptions
import com.nitinshukla.notebook.core.text.LineBuffer
import com.nitinshukla.notebook.core.text.LineSeparator
import com.nitinshukla.notebook.core.text.TextEncoding
import com.nitinshukla.notebook.core.text.TextFinder
import com.nitinshukla.notebook.core.text.TextSnapshot
import com.nitinshukla.notebook.data.files.DocumentException
import com.nitinshukla.notebook.data.files.DocumentFiles
import com.nitinshukla.notebook.data.files.SavedSession
import com.nitinshukla.notebook.data.files.SavedTab
import com.nitinshukla.notebook.data.files.TreeEntry
import com.nitinshukla.notebook.data.files.WorkspaceStore
import com.nitinshukla.notebook.ui.editor.FindState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import javax.inject.Inject

/** A node of the explorer tree. Children are loaded on first expansion. */
class TreeNode(val entry: TreeEntry, val depth: Int) {
    var expanded by mutableStateOf(false)
    var children by mutableStateOf<List<TreeNode>?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

sealed interface WorkspaceEvent {
    data class Message(val text: String) : WorkspaceEvent
    /** Ask the user whether to discard, save or keep editing before closing [id]. */
    data class ConfirmClose(val id: String) : WorkspaceEvent
}

/**
 * Shared document management for the Python editor and the code editor: tabs, open/save
 * through the Storage Access Framework, highlighting on a background thread, drafts for
 * recovery, and the folder explorer. One instance per workspace name ("python" or "code"),
 * which also namespaces the persisted session.
 */
@HiltViewModel
class CodeWorkspaceViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val files: DocumentFiles,
    private val store: WorkspaceStore,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val workspace: String = savedState.get<String>("workspace") ?: "code"
    val isPython: Boolean get() = workspace == "python"

    val documents = mutableStateListOf<OpenDocument>()
    var activeId by mutableStateOf<String?>(null)
        private set
    val active: OpenDocument? get() = documents.firstOrNull { it.id == activeId }

    var restored by mutableStateOf(false)
        private set
    var autoSave by mutableStateOf(false)
        private set
    var wordWrap by mutableStateOf(false)
        private set

    // Explorer
    var treeUri by mutableStateOf<Uri?>(null)
        private set
    var root by mutableStateOf<TreeNode?>(null)
        private set
    var treeName by mutableStateOf<String?>(null)
        private set

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _events = MutableStateFlow<WorkspaceEvent?>(null)
    val events: StateFlow<WorkspaceEvent?> = _events

    private val highlightJobs = HashMap<String, Job>()
    private val draftJobs = HashMap<String, Job>()
    private val autoSaveJobs = HashMap<String, Job>()
    private var sessionJob: Job? = null
    private val saveLock = Mutex()

    init {
        viewModelScope.launch {
            restoreSession()
            pendingOpen?.let { pendingOpen = null; open(it) }
        }
    }

    // ---- Session / recovery -------------------------------------------------------------------

    private suspend fun restoreSession() {
        val session = store.load(workspace)
        autoSave = session.autoSave
        wordWrap = session.wordWrap
        session.treeUri?.let { uri -> if (files.hasPersistedPermission(uri)) openTree(uri, persist = false) }
        for (tab in session.tabs) {
            val language = Language.entries.firstOrNull { it.name == tab.language } ?: Language.PLAIN
            val encoding = TextEncoding(runCatching { Charset.forName(tab.encoding) }.getOrDefault(Charsets.UTF_8), tab.bom)
            val separator = LineSeparator.entries.firstOrNull { it.name == tab.separator } ?: LineSeparator.LF
            val draft = if (tab.dirty) store.readDraft(workspace, tab.id) else null
            val doc: OpenDocument? = when {
                draft != null -> OpenDocument(tab.id, tab.uri, tab.name, language, draft, encoding, separator, tab.diskModified, dirtyOnLoad = true)
                tab.uri == null -> OpenDocument(tab.id, null, tab.name, language, "", encoding, separator)
                else -> runCatching { files.read(tab.uri) }.getOrNull()?.let { loaded ->
                    OpenDocument(tab.id, loaded.uri, loaded.displayName, language, loaded.decoded.text, loaded.decoded.encoding, loaded.decoded.lineSeparator, loaded.lastModified)
                }
            }
            if (doc != null) {
                if (doc.large) {
                    val pos = doc.buffer!!.positionOf(tab.cursor)
                    doc.cursorLine = pos.line; doc.cursorColumn = pos.column
                } else {
                    doc.text = TextFieldValue(doc.text.text, TextRange(tab.cursor.coerceIn(0, doc.text.text.length)))
                }
                if (draft != null && tab.uri != null) {
                    val now = files.lastModified(tab.uri)
                    if (now != null && tab.diskModified != 0L && now != tab.diskModified) doc.changedOnDisk = true
                }
                documents += doc
                scheduleHighlight(doc)
            }
        }
        activeId = session.activeId?.takeIf { id -> documents.any { it.id == id } } ?: documents.firstOrNull()?.id
        if (documents.isEmpty() && isPython) newFile(Language.PYTHON)
        store.pruneDrafts(workspace, documents.map { it.id }.toSet())
        restored = true
    }

    private fun scheduleSessionSave() {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            delay(400)
            persistSession()
        }
    }

    private suspend fun persistSession() {
        store.save(
            workspace,
            SavedSession(
                tabs = documents.map { d ->
                    SavedTab(d.id, d.uri, d.name, d.language.name, d.caretOffset(), d.dirty, d.encoding.charset.name(), d.encoding.bom, d.separator.name, d.diskModified)
                },
                activeId = activeId,
                treeUri = treeUri,
                autoSave = autoSave,
                wordWrap = wordWrap,
            ),
        )
    }

    private fun scheduleDraft(doc: OpenDocument) {
        draftJobs[doc.id]?.cancel()
        draftJobs[doc.id] = viewModelScope.launch {
            delay(if (doc.large) DRAFT_DELAY_MS * 3 else DRAFT_DELAY_MS)
            if (doc.dirty) {
                val text = doc.fullText()
                withContext(Dispatchers.IO) { store.writeDraft(workspace, doc.id, text) }
            }
            persistSession()
        }
    }

    /** Called when the screen leaves the foreground: drafts and the tab list must be on disk. */
    fun flush() {
        viewModelScope.launch {
            documents.filter { it.dirty }.forEach { store.writeDraft(workspace, it.id, it.text.text) }
            persistSession()
        }
    }

    // ---- Tabs -----------------------------------------------------------------------------------

    fun activate(id: String) {
        if (documents.any { it.id == id }) {
            activeId = id
            scheduleSessionSave()
        }
    }

    fun newFile(language: Language = if (isPython) Language.PYTHON else Language.PLAIN): OpenDocument {
        val ext = language.extensions.firstOrNull() ?: "txt"
        val n = documents.count { it.isUntitled } + 1
        val doc = OpenDocument(uri = null, name = "untitled-$n.$ext", language = language, initialText = "")
        documents += doc
        activeId = doc.id
        scheduleSessionSave()
        return doc
    }

    fun open(uri: Uri) = viewModelScope.launch {
        documents.firstOrNull { it.uri == uri }?.let { activate(it.id); return@launch }
        try {
            val loaded = files.read(uri)
            files.persist(uri)
            val language = Language.fromFileName(loaded.displayName)
            // Splitting a large file into lines happens off the main thread.
            val doc = withContext(Dispatchers.Default) {
                OpenDocument(uri = loaded.uri, name = loaded.displayName, language = language, initialText = loaded.decoded.text, encoding = loaded.decoded.encoding, separator = loaded.decoded.lineSeparator, diskModified = loaded.lastModified)
            }
            doc.readOnly = false
            // Replace an unused untitled tab rather than piling up empties.
            val placeholder = active?.takeIf { it.isUntitled && it.text.text.isEmpty() && !it.dirty }
            if (placeholder != null) documents.remove(placeholder)
            documents += doc
            activeId = doc.id
            scheduleHighlight(doc)
            if (loaded.decoded.hadDecodingErrors) message("Some bytes in ${loaded.displayName} were not valid ${loaded.decoded.encoding.displayName}; they were replaced.")
            scheduleSessionSave()
        } catch (e: DocumentException) {
            message(e.message ?: "Could not open the file")
        } catch (e: SecurityException) {
            message("Permission to read the file was denied")
        }
    }

    fun open(entry: TreeEntry) {
        if (entry.isDirectory) return
        open(entry.uri)
    }

    /** Closes a tab; asks first when it has unsaved edits unless [force]. */
    fun close(id: String, force: Boolean = false) {
        val doc = documents.firstOrNull { it.id == id } ?: return
        if (doc.dirty && !force) {
            _events.value = WorkspaceEvent.ConfirmClose(id)
            return
        }
        val index = documents.indexOf(doc)
        documents.remove(doc)
        highlightJobs.remove(id)?.cancel()
        draftJobs.remove(id)?.cancel()
        autoSaveJobs.remove(id)?.cancel()
        viewModelScope.launch { store.deleteDraft(workspace, id) }
        if (activeId == id) activeId = documents.getOrNull(index.coerceAtMost(documents.lastIndex))?.id
        scheduleSessionSave()
    }

    fun closeOthers(id: String) = documents.filter { it.id != id && !it.dirty }.forEach { close(it.id) }

    // ---- Editing ----------------------------------------------------------------------------------

    fun onTextChange(doc: OpenDocument, value: TextFieldValue) {
        val old = doc.text
        if (value.text == old.text) {
            doc.text = value
            return
        }
        // Soft keyboards deliver Enter and brackets as plain insertions; turn them into
        // language-aware edits (auto indent, auto close) the same way a hardware key would.
        val adjusted = adjustInsertion(doc, old, value) ?: value
        applyEdit(doc, adjusted, recordHistory = true)
    }

    private fun adjustInsertion(doc: OpenDocument, old: TextFieldValue, new: TextFieldValue): TextFieldValue? {
        if (new.text.length != old.text.length + 1 || !old.selection.collapsed) return null
        val at = old.selection.start
        if (new.selection.start != at + 1 || !new.text.startsWith(old.text.substring(0, at)) || !new.text.endsWith(old.text.substring(at))) return null
        return when (val typed = new.text[at]) {
            '\n' -> CodeEditing.newLine(old.text, at, doc.language).toValue()
            '(', '[', '{', '"', '\'', '`' -> CodeEditing.autoClose(old.text, at, typed, doc.language)?.toValue()
            else -> null
        }
    }

    private fun TextEdit.toValue() = TextFieldValue(text, TextRange(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length)))

    private fun applyEdit(doc: OpenDocument, value: TextFieldValue, recordHistory: Boolean) {
        if (recordHistory) doc.history.record(TextSnapshot(doc.text.text, doc.text.selection.start, doc.text.selection.end), TextSnapshot(value.text, value.selection.start, value.selection.end), System.currentTimeMillis())
        doc.text = value
        scheduleHighlight(doc)
        afterEdit(doc)
    }

    /** Bookkeeping shared by both storage modes once the content changed. */
    private fun afterEdit(doc: OpenDocument) {
        doc.editRevision++
        doc.saveError = null
        scheduleDraft(doc)
        if (doc.find.visible && doc.find.query.isNotEmpty()) refreshMatches(doc)
        if (autoSave && doc.uri != null) {
            autoSaveJobs[doc.id]?.cancel()
            autoSaveJobs[doc.id] = viewModelScope.launch {
                delay(settings.value.autosaveDelayMs.toLong().coerceIn(300L, 10_000L))
                saveNow(doc)
            }
        }
    }

    // ---- Large documents: one line at a time ---------------------------------------------------

    /** Line [index] of a large document changed in its own text field, or the caret moved. */
    fun onLineChange(doc: OpenDocument, index: Int, value: TextFieldValue) {
        val b = doc.buffer ?: return
        val old = doc.lines.getOrNull(index) ?: return
        doc.cursorLine = index
        doc.cursorColumn = value.selection.start.coerceIn(0, value.text.length)
        val new = value.text
        if (new == old) return
        val singleInsert = new.length == old.length + 1 && value.selection.collapsed && value.selection.start >= 1 &&
            new.removeRange(value.selection.start - 1, value.selection.start) == old
        if ('\n' in new) {
            val at = value.selection.start - 1
            val edit = if (singleInsert && new[at] == '\n') CodeEditing.newLine(old, at, doc.language) else TextEdit(new, value.selection.start, value.selection.start)
            replaceLineWith(doc, index, edit)
            return
        }
        if (singleInsert) {
            val typed = new[value.selection.start - 1]
            if (typed in "([{\"'`") {
                CodeEditing.autoClose(old, value.selection.start - 1, typed, doc.language)?.let { closed ->
                    b.breakRun()
                    b.setLine(index, closed.text)
                    largeChanged(doc, index)
                    doc.focusAt(index, closed.selectionStart)
                    return
                }
            }
        }
        b.setLine(index, new)
        largeChanged(doc, index)
    }

    /** Replaces one line with the (possibly multi-line) result of an editing command and places the caret. */
    private fun replaceLineWith(doc: OpenDocument, index: Int, edit: TextEdit) {
        val b = doc.buffer ?: return
        val parts = LineBuffer.split(edit.text)
        b.breakRun()
        b.replaceLines(index, 1, parts)
        largeChanged(doc, index, parts.size)
        var remaining = edit.selectionStart.coerceAtLeast(0)
        var line = 0
        while (line < parts.lastIndex && remaining > parts[line].length) { remaining -= parts[line].length + 1; line++ }
        doc.focusAt(index + line, remaining)
    }

    private fun largeChanged(doc: OpenDocument, from: Int, count: Int = 1) {
        for (i in from until minOf(from + count, doc.lines.size)) if (doc.lines[i].length > doc.longestLine) doc.longestLine = doc.lines[i].length
        afterEdit(doc)
    }

    /** Backspace at the start of a line: merge it into the previous one. */
    fun joinWithPrevious(doc: OpenDocument, index: Int) {
        val b = doc.buffer ?: return
        b.breakRun()
        val col = b.joinWithPrevious(index)
        if (col < 0) return
        largeChanged(doc, index - 1)
        doc.focusAt(index - 1, col)
    }

    /** Delete at the end of a line: pull the next line up. */
    fun joinWithNext(doc: OpenDocument, index: Int) {
        val b = doc.buffer ?: return
        if (index >= doc.lines.lastIndex) return
        val col = doc.lines[index].length
        b.breakRun()
        b.joinWithPrevious(index + 1)
        largeChanged(doc, index)
        doc.focusAt(index, col)
    }

    /** Arrow keys between lines of a large document. */
    fun moveCaret(doc: OpenDocument, deltaLines: Int) {
        val target = (doc.cursorLine + deltaLines).coerceIn(0, doc.lines.lastIndex)
        if (target == doc.cursorLine) return
        doc.focusAt(target, doc.cursorColumn)
    }

    private fun largeEdit(doc: OpenDocument, op: (line: String, column: Int) -> TextEdit?) {
        val line = doc.cursorLine.coerceIn(0, doc.lines.lastIndex)
        val column = doc.cursorColumn.coerceIn(0, doc.lines[line].length)
        val result = op(doc.lines[line], column) ?: return
        replaceLineWith(doc, line, result)
    }

    private fun edit(doc: OpenDocument, result: TextEdit) {
        applyEdit(doc, result.toValue(), recordHistory = true)
        doc.revealSelection++
    }

    fun insert(doc: OpenDocument, snippet: String) {
        if (doc.large) return largeEdit(doc) { l, c -> TextEdit(l.replaceRange(c, c, snippet), c + snippet.length, c + snippet.length) }
        val s = doc.text.selection
        val text = doc.text.text.replaceRange(s.min, s.max, snippet)
        edit(doc, TextEdit(text, s.min + snippet.length, s.min + snippet.length))
    }

    fun tab(doc: OpenDocument) = if (doc.large) largeEdit(doc) { l, c -> CodeEditing.indent(l, c, c, doc.language) } else edit(doc, CodeEditing.indent(doc.text.text, doc.text.selection.min, doc.text.selection.max, doc.language))
    fun outdent(doc: OpenDocument) = if (doc.large) largeEdit(doc) { l, c -> CodeEditing.outdent(l, c, c, doc.language) } else edit(doc, CodeEditing.outdent(doc.text.text, doc.text.selection.min, doc.text.selection.max, doc.language))
    fun toggleComment(doc: OpenDocument) = if (doc.large) largeEdit(doc) { l, c -> CodeEditing.toggleComment(l, c, c, doc.language) } else edit(doc, CodeEditing.toggleComment(doc.text.text, doc.text.selection.min, doc.text.selection.max, doc.language))
    fun duplicateLine(doc: OpenDocument) {
        if (doc.large) {
            val b = doc.buffer ?: return
            val i = doc.cursorLine.coerceIn(0, doc.lines.lastIndex)
            b.breakRun(); b.insertLines(i + 1, listOf(doc.lines[i])); largeChanged(doc, i + 1); doc.focusAt(i + 1, doc.cursorColumn)
        } else edit(doc, CodeEditing.duplicateLine(doc.text.text, doc.text.selection.start))
    }
    fun deleteLine(doc: OpenDocument) {
        if (doc.large) {
            val b = doc.buffer ?: return
            val i = doc.cursorLine.coerceIn(0, doc.lines.lastIndex)
            b.breakRun(); b.removeLine(i); largeChanged(doc, i); doc.focusAt(i.coerceAtMost(doc.lines.lastIndex), 0)
        } else edit(doc, CodeEditing.deleteLine(doc.text.text, doc.text.selection.start))
    }
    fun moveLine(doc: OpenDocument, up: Boolean) {
        if (doc.large) {
            val b = doc.buffer ?: return
            val i = doc.cursorLine
            val j = if (up) i - 1 else i + 1
            b.breakRun()
            if (b.swapLines(i, j)) { largeChanged(doc, minOf(i, j), 2); doc.focusAt(j, doc.cursorColumn) }
        } else edit(doc, CodeEditing.moveLine(doc.text.text, doc.text.selection.start, up))
    }
    fun newLine(doc: OpenDocument) = if (doc.large) largeEdit(doc) { l, c -> CodeEditing.newLine(l, c, doc.language) } else edit(doc, CodeEditing.newLine(doc.text.text, doc.text.selection.start, doc.language))

    fun undo(doc: OpenDocument) {
        if (doc.large) {
            val change = doc.buffer!!.undo() ?: return
            largeChanged(doc, change.from, change.removed.size)
            doc.focusAt(change.from, doc.cursorColumn)
            return
        }
        val s = doc.history.undo(TextSnapshot(doc.text.text, doc.text.selection.start, doc.text.selection.end)) ?: return
        applyEdit(doc, TextFieldValue(s.text, TextRange(s.selectionStart.coerceIn(0, s.text.length), s.selectionEnd.coerceIn(0, s.text.length))), recordHistory = false)
        doc.revealSelection++
    }

    fun redo(doc: OpenDocument) {
        if (doc.large) {
            val change = doc.buffer!!.redo() ?: return
            largeChanged(doc, change.from, change.inserted.size)
            doc.focusAt(change.from, doc.cursorColumn)
            return
        }
        val s = doc.history.redo(TextSnapshot(doc.text.text, doc.text.selection.start, doc.text.selection.end)) ?: return
        applyEdit(doc, TextFieldValue(s.text, TextRange(s.selectionStart.coerceIn(0, s.text.length), s.selectionEnd.coerceIn(0, s.text.length))), recordHistory = false)
        doc.revealSelection++
    }

    fun goToLine(doc: OpenDocument, line: Int) {
        if (doc.large) { doc.focusAt(line - 1, 0); return }
        val offset = CodeEditing.offsetOfLine(doc.text.text, line)
        doc.text = doc.text.copy(selection = TextRange(offset))
        doc.revealSelection++
    }

    fun selectAll(doc: OpenDocument) {
        if (doc.large) { message("Select all is not available for very large files; selection works within a line."); return }
        doc.text = doc.text.copy(selection = TextRange(0, doc.text.text.length))
    }

    fun setLanguage(doc: OpenDocument, language: Language) {
        doc.language = language
        scheduleHighlight(doc)
        scheduleSessionSave()
    }

    fun setSeparator(doc: OpenDocument, separator: LineSeparator) {
        if (doc.separator == separator) return
        doc.separator = separator
        doc.editRevision++
        scheduleSessionSave()
    }

    fun toggleAutoSave(enabled: Boolean) {
        autoSave = enabled
        scheduleSessionSave()
    }

    fun toggleWordWrap(enabled: Boolean) {
        wordWrap = enabled
        scheduleSessionSave()
    }

    private fun scheduleHighlight(doc: OpenDocument) {
        if (doc.large) return // large documents are highlighted per visible line by the editor
        val revision = doc.editRevision
        val text = doc.text.text
        val language = doc.language
        highlightJobs[doc.id]?.cancel()
        highlightJobs[doc.id] = viewModelScope.launch(Dispatchers.Default) {
            delay(HIGHLIGHT_DELAY_MS)
            val tokens = SyntaxHighlighter.tokenize(text, language)
            withContext(Dispatchers.Main.immediate) {
                if (doc.editRevision == revision) {
                    doc.tokens = tokens
                    doc.tokensRevision = revision
                }
            }
        }
    }

    /** Offline syntax check; results feed the problems panel. */
    fun check(doc: OpenDocument) = viewModelScope.launch(Dispatchers.Default) {
        val issues = CodeChecker.check(doc.fullText(), doc.language)
        withContext(Dispatchers.Main.immediate) {
            doc.issues = issues
            if (issues.isEmpty()) message("No problems found")
        }
    }

    fun clearIssues(doc: OpenDocument) {
        doc.issues = emptyList()
    }

    // ---- Find / replace --------------------------------------------------------------------------

    fun showFind(doc: OpenDocument, replace: Boolean = false) {
        doc.find = doc.find.copy(visible = true, showReplace = replace)
        val sel = doc.text.selection
        if (!sel.collapsed && sel.length < 200) setFindQuery(doc, doc.text.text.substring(sel.min, sel.max))
        else if (doc.find.query.isNotEmpty()) refreshMatches(doc, jumpToFirst = true)
    }

    fun hideFind(doc: OpenDocument) {
        doc.find = FindState()
    }

    fun setFindQuery(doc: OpenDocument, q: String) {
        doc.find = doc.find.copy(query = q)
        refreshMatches(doc, jumpToFirst = true)
    }

    fun setReplacement(doc: OpenDocument, r: String) {
        doc.find = doc.find.copy(replacement = r)
    }

    fun setFindOptions(doc: OpenDocument, options: FindOptions) {
        doc.find = doc.find.copy(options = options)
        refreshMatches(doc, jumpToFirst = true)
    }

    private fun refreshMatches(doc: OpenDocument, jumpToFirst: Boolean = false) {
        val matches = TextFinder.findAll(doc.fullText(), doc.find.query, doc.find.options)
        val current = if (jumpToFirst) TextFinder.nextIndex(matches, doc.caretOffset()) else doc.find.current.coerceIn(-1, matches.lastIndex)
        doc.find = doc.find.copy(matches = matches, current = current, wrapped = false)
        if (jumpToFirst && current >= 0) selectMatch(doc, current, wrapped = false)
    }

    private fun selectMatch(doc: OpenDocument, index: Int, wrapped: Boolean) {
        val m = doc.find.matches.getOrNull(index) ?: return
        doc.find = doc.find.copy(current = index, wrapped = wrapped)
        if (doc.large) {
            val b = doc.buffer!!
            val start = b.positionOf(m.first)
            val end = b.positionOf(m.last + 1)
            doc.focusAt(start.line, start.column, if (end.line == start.line) end.column else start.column)
            return
        }
        doc.text = doc.text.copy(selection = TextRange(m.first, m.last + 1))
        doc.revealSelection++
    }

    fun findNext(doc: OpenDocument) {
        val matches = doc.find.matches
        if (matches.isEmpty()) return
        val from = doc.caretOffset() + 1
        val i = TextFinder.nextIndex(matches, from)
        selectMatch(doc, i, wrapped = matches[i].first < from)
    }

    fun findPrevious(doc: OpenDocument) {
        val matches = doc.find.matches
        if (matches.isEmpty()) return
        val from = doc.caretOffset()
        val i = TextFinder.previousIndex(matches, from)
        selectMatch(doc, i, wrapped = matches[i].first >= from)
    }

    fun replaceCurrent(doc: OpenDocument) {
        val m = doc.find.matches.getOrNull(doc.find.current) ?: return
        if (doc.large) {
            val b = doc.buffer!!
            val start = b.positionOf(m.first)
            val end = b.positionOf(m.last + 1)
            if (start.line != end.line) { message("This match spans lines; use Replace all."); return }
            val line = doc.lines[start.line]
            b.breakRun()
            b.setLine(start.line, line.replaceRange(start.column, end.column, doc.find.replacement))
            largeChanged(doc, start.line)
            doc.focusAt(start.line, start.column + doc.find.replacement.length)
            refreshMatches(doc, jumpToFirst = true)
            return
        }
        val replaced = TextFinder.replaceRange(doc.text.text, m, doc.find.replacement)
        applyEdit(doc, TextFieldValue(replaced, TextRange(m.first + doc.find.replacement.length)), recordHistory = true)
        refreshMatches(doc, jumpToFirst = true)
    }

    fun replaceAll(doc: OpenDocument): Int {
        if (doc.large) {
            val b = doc.buffer!!
            val q = doc.find.query; val r = doc.find.replacement; val o = doc.find.options
            b.breakRun()
            val count = b.replaceAll { line -> TextFinder.replaceAll(line, q, r, o) }
            if (count > 0) { doc.longestLine = doc.lines.maxOf { it.length }; afterEdit(doc) }
            refreshMatches(doc)
            return count
        }
        val (result, count) = TextFinder.replaceAll(doc.text.text, doc.find.query, doc.find.replacement, doc.find.options)
        if (count > 0) applyEdit(doc, TextFieldValue(result, TextRange(doc.text.selection.start.coerceAtMost(result.length))), recordHistory = true)
        refreshMatches(doc)
        return count
    }

    // ---- Saving -------------------------------------------------------------------------------------

    /** Saves to the document's own file; returns false for untitled documents (caller shows Save as). */
    fun save(doc: OpenDocument): Boolean {
        if (doc.uri == null) return false
        autoSaveJobs[doc.id]?.cancel()
        viewModelScope.launch { saveNow(doc) }
        return true
    }

    fun saveAs(doc: OpenDocument, uri: Uri) = viewModelScope.launch {
        doc.uri = uri
        files.persist(uri)
        val name = files.displayName(uri)
        if (name != null) {
            doc.name = name
            if (doc.language == Language.PLAIN || Language.fromFileName(name) != Language.PLAIN) doc.language = Language.fromFileName(name)
        }
        doc.changedOnDisk = false
        if (saveNow(doc)) refreshTree()
    }

    /**
     * Writes the text; only after the provider returns does the document record which
     * revision is on disk and when. A failure keeps the dirty marker and shows the reason.
     */
    suspend fun saveNow(doc: OpenDocument): Boolean = saveLock.withLock {
        val uri = doc.uri ?: return@withLock false
        val revision = doc.editRevision
        val text = doc.fullText()
        doc.saving = true
        try {
            files.write(uri, text, doc.encoding, doc.separator)
            doc.savedRevision = revision
            doc.lastSavedAt = System.currentTimeMillis()
            doc.diskModified = files.lastModified(uri) ?: doc.diskModified
            doc.changedOnDisk = false
            doc.saveError = null
            store.deleteDraft(workspace, doc.id)
            persistSession()
            true
        } catch (e: DocumentException) {
            doc.saveError = e.message
            message(e.message ?: "Save failed")
            false
        } catch (e: SecurityException) {
            doc.saveError = "Permission to write was denied"
            message("Permission to write the file was denied. Use Save as to pick a new location.")
            false
        } finally {
            doc.saving = false
        }
    }

    fun saveAll() = documents.filter { it.dirty && it.uri != null }.forEach { save(it) }

    /** Re-reads a document that changed on disk, discarding local edits. */
    fun reloadFromDisk(doc: OpenDocument) = viewModelScope.launch {
        val uri = doc.uri ?: return@launch
        try {
            val loaded = files.read(uri)
            if (doc.large) doc.resetLines(loaded.decoded.text)
            else doc.text = TextFieldValue(loaded.decoded.text, TextRange(doc.text.selection.start.coerceAtMost(loaded.decoded.text.length)))
            doc.encoding = loaded.decoded.encoding
            doc.separator = loaded.decoded.lineSeparator
            doc.diskModified = loaded.lastModified
            doc.editRevision++
            doc.savedRevision = doc.editRevision
            doc.changedOnDisk = false
            doc.history.clear()
            doc.longestLine = doc.lines.maxOfOrNull { it.length } ?: 0
            scheduleHighlight(doc)
            store.deleteDraft(workspace, doc.id)
        } catch (e: DocumentException) {
            message(e.message ?: "Could not reload")
        }
    }

    fun suggestedFileName(doc: OpenDocument): String {
        val ext = CodeFileTypes.extensionOf(doc.name).ifEmpty { doc.language.extensions.firstOrNull() ?: "txt" }
        return CodeFileTypes.fileName(doc.name.substringBeforeLast('.'), ext)
    }

    // ---- Explorer --------------------------------------------------------------------------------

    fun openTree(uri: Uri, persist: Boolean = true) {
        if (persist) files.persist(uri)
        treeUri = uri
        val rootUri = files.rootDocumentUri(uri)
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.ifEmpty { null } ?: "Folder"
        treeName = name
        val node = TreeNode(TreeEntry(rootUri, rootId, name, true, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, 0, 0), 0)
        root = node
        toggle(node)
        scheduleSessionSave()
    }

    fun closeTree() {
        treeUri?.let { files.release(it) }
        treeUri = null
        root = null
        treeName = null
        scheduleSessionSave()
    }

    fun toggle(node: TreeNode) {
        if (!node.entry.isDirectory) return
        node.expanded = !node.expanded
        if (node.expanded && node.children == null) loadChildren(node)
    }

    private fun loadChildren(node: TreeNode) = viewModelScope.launch {
        val tree = treeUri ?: return@launch
        node.loading = true
        node.error = null
        try {
            node.children = files.listChildren(tree, node.entry.documentId).map { TreeNode(it, node.depth + 1) }
        } catch (e: DocumentException) {
            node.error = e.message
            node.children = emptyList()
        } finally {
            node.loading = false
        }
    }

    fun refresh(node: TreeNode) {
        node.children = null
        if (node.expanded) loadChildren(node)
    }

    fun refreshTree() {
        root?.let { refreshRecursively(it) }
    }

    private fun refreshRecursively(node: TreeNode) {
        if (!node.expanded) { node.children = null; return }
        viewModelScope.launch {
            val tree = treeUri ?: return@launch
            val previous = node.children.orEmpty().associateBy { it.entry.documentId }
            try {
                node.children = files.listChildren(tree, node.entry.documentId).map { entry ->
                    previous[entry.documentId]?.also { it.entry.let { _ -> } } ?: TreeNode(entry, node.depth + 1)
                }
                node.children.orEmpty().filter { it.expanded }.forEach { refreshRecursively(it) }
            } catch (e: DocumentException) {
                node.error = e.message
            }
        }
    }

    /** Flattened visible rows for a lazy list, depth-first. */
    fun visibleRows(): List<TreeNode> {
        val out = ArrayList<TreeNode>()
        fun walk(n: TreeNode) {
            out += n
            if (n.expanded) n.children?.forEach(::walk)
        }
        root?.let(::walk)
        return out
    }

    fun createFile(parent: TreeNode, name: String) = viewModelScope.launch {
        try {
            val uri = files.createFile(parent.entry.uri, name, CodeFileTypes.mimeFor(CodeFileTypes.extensionOf(name)))
            parent.expanded = true
            refresh(parent)
            open(uri)
        } catch (e: DocumentException) {
            message(e.message ?: "Could not create the file")
        }
    }

    fun createFolder(parent: TreeNode, name: String) = viewModelScope.launch {
        try {
            files.createDirectory(parent.entry.uri, name)
            parent.expanded = true
            refresh(parent)
        } catch (e: DocumentException) {
            message(e.message ?: "Could not create the folder")
        }
    }

    fun rename(node: TreeNode, parent: TreeNode?, newName: String) = viewModelScope.launch {
        try {
            val newUri = files.rename(node.entry.uri, newName)
            documents.filter { it.uri == node.entry.uri }.forEach { it.uri = newUri; it.name = newName; it.language = Language.fromFileName(newName).takeIf { l -> l != Language.PLAIN } ?: it.language; scheduleHighlight(it) }
            parent?.let { refresh(it) } ?: refreshTree()
        } catch (e: DocumentException) {
            message(e.message ?: "Could not rename")
        }
    }

    fun delete(node: TreeNode, parent: TreeNode?) = viewModelScope.launch {
        if (files.delete(node.entry.uri)) {
            documents.filter { it.uri == node.entry.uri }.forEach { close(it.id, force = true) }
            parent?.let { refresh(it) } ?: refreshTree()
        } else {
            message("Could not delete ${node.entry.name}")
        }
    }

    fun parentOf(node: TreeNode): TreeNode? {
        fun find(n: TreeNode): TreeNode? {
            n.children?.forEach { c -> if (c === node) return n; find(c)?.let { return it } }
            return null
        }
        return root?.let(::find)
    }

    // ---- Misc ----------------------------------------------------------------------------------------

    private fun message(text: String) {
        _events.value = WorkspaceEvent.Message(text)
    }

    fun consumeEvent() {
        _events.value = null
    }

    companion object {
        /** A file picked elsewhere in the app (Notes > Open document) to open once the workspace is up. */
        var pendingOpen: Uri? = null

        private const val HIGHLIGHT_DELAY_MS = 150L
        private const val DRAFT_DELAY_MS = 1_200L
    }
}
