package com.nitinshukla.notebook.ui.editor

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nitinshukla.notebook.MainActivity
import com.nitinshukla.notebook.core.backup.TextImport
import com.nitinshukla.notebook.core.checklist.ChecklistOps
import com.nitinshukla.notebook.core.model.Attachment
import com.nitinshukla.notebook.core.model.AttachmentType
import com.nitinshukla.notebook.core.model.ChecklistItem
import com.nitinshukla.notebook.core.model.LinkPreview
import com.nitinshukla.notebook.core.model.Note
import com.nitinshukla.notebook.core.model.NoteColor
import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.repository.LinkPreviewRepository
import com.nitinshukla.notebook.core.repository.NoteRepository
import com.nitinshukla.notebook.core.repository.RevisionService
import com.nitinshukla.notebook.core.repository.SettingsRepository
import com.nitinshukla.notebook.core.repository.TemplateRepository
import com.nitinshukla.notebook.core.settings.AppSettings
import com.nitinshukla.notebook.core.template.DefaultTemplates
import com.nitinshukla.notebook.core.text.CodeFileTypes
import com.nitinshukla.notebook.core.text.CursorPosition
import com.nitinshukla.notebook.core.text.EditHistory
import com.nitinshukla.notebook.core.text.FindOptions
import com.nitinshukla.notebook.core.text.LinkDetector
import com.nitinshukla.notebook.core.text.RichText
import com.nitinshukla.notebook.core.text.TextFinder
import com.nitinshukla.notebook.core.text.TextSnapshot
import com.nitinshukla.notebook.core.text.TextStyleKind
import com.nitinshukla.notebook.data.files.AttachmentStore
import com.nitinshukla.notebook.data.files.TextFileException
import com.nitinshukla.notebook.data.files.TextFiles
import com.nitinshukla.notebook.linkpreview.LinkPreviewFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class FindState(
    val visible: Boolean = false,
    val query: String = "",
    val replacement: String = "",
    val options: FindOptions = FindOptions(),
    val matches: List<IntRange> = emptyList(),
    val current: Int = -1,
    val showReplace: Boolean = false,
    /** Set when the last Find next / previous wrapped past the end or start of the note. */
    val wrapped: Boolean = false,
)

sealed interface EditorEvent {
    data class Message(val text: String) : EditorEvent
    data object Closed : EditorEvent
}

/**
 * Save state is tracked with revision counters rather than a boolean: every edit bumps
 * [editRevision], a successful save records which revision it wrote, and the note is dirty
 * exactly when the two differ. An edit that arrives while a save is in flight therefore
 * stays dirty, and a failed save never clears the indicator or moves the timestamp.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val notes: NoteRepository,
    private val revisionService: RevisionService,
    private val settingsRepository: SettingsRepository,
    private val templates: TemplateRepository,
    private val textFiles: TextFiles,
    private val attachmentStore: AttachmentStore,
    private val linkPreviews: LinkPreviewRepository,
    private val linkPreviewFetcher: LinkPreviewFetcher,
) : ViewModel() {
    private val requestedId: Long = savedState.get<Long>("noteId") ?: 0L
    private val requestedType: String = savedState.get<String>("type").orEmpty()
    private val requestedTemplate: Long = savedState.get<Long>("template") ?: 0L

    var note by mutableStateOf(Note())
        private set
    var loaded by mutableStateOf(false)
        private set
    var needsUnlock by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set
    var text by mutableStateOf(TextFieldValue(""))
        private set
    var checklist by mutableStateOf<List<ChecklistItem>>(emptyList())
        private set
    var attachments by mutableStateOf<List<Attachment>>(emptyList())
        private set
    var saving by mutableStateOf(false)
        private set
    /** Timestamp the database holds for the last successful save, or null before the first one in this session. */
    var lastSavedAt by mutableStateOf<Long?>(null)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var find by mutableStateOf(FindState())
        private set
    var monospace by mutableStateOf(false)
        private set
    var plainTextMode by mutableStateOf(false)
        private set
    var fileUri by mutableStateOf<Uri?>(null)
        private set
    var linkPreview by mutableStateOf<LinkPreview?>(null)
        private set

    /** Incremented whenever the editor should scroll the current selection into view. */
    var revealSelection by mutableIntStateOf(0)
        private set

    private var editRevision by mutableIntStateOf(0)
    private var savedRevision by mutableIntStateOf(0)
    private var checklistRevision = 0
    private var checklistSavedRevision = 0

    val dirty: Boolean get() = editRevision != savedRevision

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _events = MutableStateFlow<EditorEvent?>(null)
    val events: StateFlow<EditorEvent?> = _events

    private val history = EditHistory()
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    private var autosaveJob: Job? = null
    private var observeJob: Job? = null
    private val saveLock = Mutex()

    val cursor: CursorPosition get() = CursorPosition.of(text.text, text.selection.start)
    /**
     * Links in the text. Running the URL regex over the whole note on every recomposition made
     * typing in long notes stutter, so it runs off the main thread after typing pauses.
     */
    var links by mutableStateOf<List<com.nitinshukla.notebook.core.text.DetectedLink>>(emptyList())
        private set
    private var linksJob: Job? = null

    private fun refreshLinks() {
        linksJob?.cancel()
        val snapshot = text.text
        linksJob = viewModelScope.launch {
            delay(400)
            links = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { LinkDetector.find(snapshot) }
        }
    }

    /** The name shown in the top bar: the title, else the first line, else nothing. */
    val displayTitle: String
        get() = title.trim().ifEmpty { text.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() }

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (requestedId == 0L) {
            val prefs = settingsRepository.settings.first()
            val type = NoteType.entries.firstOrNull { it.name == requestedType } ?: prefs.defaultNoteType
            var initialTitle = ""
            var initialContent = ""
            var initialType = type
            if (requestedTemplate != 0L) {
                templates.get(requestedTemplate)?.let { t ->
                    initialTitle = DefaultTemplates.expand(t.title)
                    initialContent = DefaultTemplates.expand(t.content)
                    initialType = t.type
                }
            }
            MainActivity.sharedText?.let { shared ->
                initialContent = shared
                initialTitle = MainActivity.sharedTitle.orEmpty()
                MainActivity.sharedText = null
                MainActivity.sharedTitle = null
            }
            val id = notes.create(Note(title = initialTitle, content = if (initialType == NoteType.CHECKLIST) "" else initialContent, type = initialType, color = prefs.defaultNoteColor, revisionsEnabled = prefs.revisionsEnabled))
            if (initialType == NoteType.CHECKLIST && initialContent.isNotBlank()) notes.replaceChecklist(id, ChecklistOps.fromText(initialContent, id))
            MainActivity.openUri?.let { uri -> MainActivity.openUri = null; openFromUri(uri, id) }
            start(id)
        } else {
            start(requestedId)
        }
    }

    private suspend fun start(id: Long) {
        val details = notes.get(id)
        if (details == null) {
            _events.value = EditorEvent.Closed
            return
        }
        note = details.note
        if (details.note.isLocked) {
            needsUnlock = true
            loaded = true
            return
        }
        apply(details.note, details.checklist, details.attachments)
        observeJob = viewModelScope.launch {
            notes.observe(id).collect { d ->
                if (d == null) return@collect
                // Only refresh fields other screens can change; the text being typed is ours.
                attachments = d.attachments
                note = note.copy(isPinned = d.note.isPinned, isArchived = d.note.isArchived, isTrashed = d.note.isTrashed, color = d.note.color)
            }
        }
    }

    private fun apply(n: Note, cl: List<ChecklistItem>, at: List<Attachment>) {
        note = n
        title = n.title
        val cursor = n.cursorPosition.coerceIn(0, n.content.length)
        text = TextFieldValue(n.content, TextRange(cursor))
        checklist = ChecklistOps.withTemporaryIds(cl)
        attachments = at
        monospace = n.type.isMonospaced
        history.clear()
        refreshLinks()
        savedRevision = editRevision
        checklistSavedRevision = checklistRevision
        loaded = true
        needsUnlock = false
    }

    /** Called after biometric success for a locked note. */
    fun unlocked() {
        viewModelScope.launch {
            val details = notes.get(note.id) ?: return@launch
            apply(details.note, details.checklist, details.attachments)
        }
    }

    // ---- Editing -------------------------------------------------------------------------

    fun onTitleChange(value: String) {
        title = value
        markDirty()
    }

    fun onTextChange(value: TextFieldValue) {
        val changed = value.text != text.text
        if (changed) {
            history.record(snapshot(text), snapshot(value), System.currentTimeMillis())
        }
        text = value
        if (changed) {
            markDirty()
            if (find.visible && find.query.isNotEmpty()) refreshMatches()
        }
    }

    private fun snapshot(v: TextFieldValue) = TextSnapshot(v.text, v.selection.start, v.selection.end)
    private fun TextSnapshot.toValue() = TextFieldValue(text, TextRange(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length)))

    fun undo() {
        history.undo(snapshot(text))?.let { text = it.toValue(); markDirty(); revealSelection++ }
    }

    fun redo() {
        history.redo(snapshot(text))?.let { text = it.toValue(); markDirty(); revealSelection++ }
    }

    fun selectAll() {
        text = text.copy(selection = TextRange(0, text.text.length))
    }

    fun insertText(insert: String) {
        val s = text.selection
        val newText = text.text.replaceRange(s.min, s.max, insert)
        onTextChange(TextFieldValue(newText, TextRange(s.min + insert.length)))
    }

    fun selectedText(): String = text.text.substring(text.selection.min, text.selection.max)

    fun deleteSelection() {
        if (text.selection.collapsed) return
        insertText("")
    }

    fun toggleStyle(style: TextStyleKind) {
        val edit = RichText.toggle(text.text, text.selection.start, text.selection.end, style)
        onTextChange(TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd)))
    }

    fun updateMonospace(enabled: Boolean) {
        monospace = enabled
    }

    fun updatePlainTextMode(enabled: Boolean) {
        plainTextMode = enabled
    }

    fun setType(type: NoteType) {
        if (type == note.type) return
        if (type == NoteType.CHECKLIST) {
            checklist = ChecklistOps.withTemporaryIds(ChecklistOps.fromText(text.text, note.id))
            checklistRevision++
            text = TextFieldValue("")
        } else if (note.type == NoteType.CHECKLIST) {
            text = TextFieldValue(ChecklistOps.toText(checklist))
            checklist = emptyList()
            checklistRevision++
        }
        note = note.copy(type = type)
        monospace = type.isMonospaced
        markDirty()
        save()
    }

    // ---- Rename ----------------------------------------------------------------------------

    /**
     * Renames the note from inside the editor. The title is written on its own, immediately,
     * so the list and the top bar agree even while body edits are still pending autosave.
     */
    fun rename(newTitle: String) {
        val trimmed = newTitle.trim()
        title = trimmed
        viewModelScope.launch {
            saveLock.withLock {
                try {
                    val at = notes.rename(note.id, trimmed)
                    note = note.copy(title = trimmed, updatedAt = at)
                    lastSavedAt = at
                    saveError = null
                } catch (e: Exception) {
                    saveError = e.message ?: "Rename failed"
                    _events.value = EditorEvent.Message("Could not rename: ${e.message}")
                }
            }
        }
    }

    // ---- Checklist ------------------------------------------------------------------------

    private fun updateChecklist(items: List<ChecklistItem>) {
        checklist = ChecklistOps.withTemporaryIds(items)
        checklistRevision++
        markDirty()
    }

    fun addChecklistItem(textValue: String = "", index: Int = checklist.size) = updateChecklist(ChecklistOps.add(checklist, textValue, index, note.id))
    fun setChecklistText(index: Int, value: String) = updateChecklist(ChecklistOps.setText(checklist, index, value))
    fun toggleChecklistItem(index: Int) = updateChecklist(ChecklistOps.toggle(checklist, index, settings.value.moveCheckedToBottom))
    fun removeChecklistItem(index: Int) = updateChecklist(ChecklistOps.remove(checklist, index))
    fun moveChecklistItem(from: Int, to: Int) = updateChecklist(ChecklistOps.move(checklist, from, to))
    fun clearCompleted() = updateChecklist(ChecklistOps.clearCompleted(checklist))
    fun uncheckAll() = updateChecklist(ChecklistOps.uncheckAll(checklist))
    fun completedToBottom() = updateChecklist(ChecklistOps.checkedToBottom(checklist))

    // ---- Note properties -------------------------------------------------------------------

    fun setColor(color: NoteColor) {
        note = note.copy(color = color)
        viewModelScope.launch { notes.setColor(listOf(note.id), color) }
    }

    fun togglePinned() = viewModelScope.launch { notes.setPinned(listOf(note.id), !note.isPinned) }
    fun toggleArchived() = viewModelScope.launch { notes.setArchived(listOf(note.id), !note.isArchived) }

    fun setRevisionsEnabled(enabled: Boolean) {
        note = note.copy(revisionsEnabled = enabled)
        markDirty(); save()
    }

    fun moveToTrash() = viewModelScope.launch {
        saveNow(force = false)
        notes.moveToTrash(listOf(note.id))
        _events.value = EditorEvent.Closed
    }

    fun duplicate() = viewModelScope.launch {
        saveNow(force = false)
        notes.duplicate(note.id)
        _events.value = EditorEvent.Message("Duplicated")
    }

    fun setLocked(locked: Boolean) = viewModelScope.launch {
        saveNow(force = false)
        notes.setLocked(note.id, locked)
        note = note.copy(isLocked = locked)
    }

    // ---- Saving ----------------------------------------------------------------------------

    private fun markDirty() {
        refreshLinks()
        editRevision++
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(settings.value.autosaveDelayMs.toLong().coerceIn(100L, 10_000L))
            saveNow(force = false)
        }
    }

    fun save() {
        autosaveJob?.cancel()
        viewModelScope.launch { saveNow(force = true) }
    }

    /**
     * Writes the note. Only after the database confirms the write does the UI learn the new
     * timestamp and drop the unsaved marker, and only for the edits that existed when the
     * save started: anything typed meanwhile keeps the note dirty.
     */
    suspend fun saveNow(force: Boolean): Boolean {
        if (!loaded || needsUnlock) return false
        if (!dirty && !force) return true
        return saveLock.withLock {
            if (!dirty && !force) return@withLock true
            val revisionBeingSaved = editRevision
            val checklistBeingSaved = checklistRevision
            saving = true
            try {
                val updated = note.copy(title = title, content = text.text, cursorPosition = text.selection.start)
                val persistedAt = notes.update(updated)
                if (checklistBeingSaved != checklistSavedRevision || note.type == NoteType.CHECKLIST && force) {
                    notes.replaceChecklist(note.id, checklist)
                    checklistSavedRevision = checklistBeingSaved
                }
                note = updated.copy(updatedAt = persistedAt)
                savedRevision = revisionBeingSaved
                lastSavedAt = persistedAt
                saveError = null
                revisionService.snapshotIfNeeded(updated, settings.value, force = force)
                if (force) history.breakRun()
                true
            } catch (e: Exception) {
                saveError = e.message ?: "Save failed"
                _events.value = EditorEvent.Message("Could not save: ${e.message ?: "unknown error"}")
                false
            } finally {
                saving = false
            }
        }
    }

    fun saveCursor() {
        if (!loaded || needsUnlock) return
        viewModelScope.launch { notes.saveCursor(note.id, text.selection.start) }
    }

    /** Called when the editor is left: flushes pending edits and drops empty new notes. */
    fun onLeave() {
        viewModelScope.launch {
            autosaveJob?.cancel()
            saveNow(force = false)
            if (loaded && !needsUnlock && title.isBlank() && text.text.isBlank() && checklist.all { it.text.isBlank() } && attachments.isEmpty() && note.fileName == null) {
                notes.deletePermanently(listOf(note.id))
            }
        }
    }

    // ---- Find / replace --------------------------------------------------------------------

    fun showFind(replace: Boolean = false) {
        find = find.copy(visible = true, showReplace = replace)
        if (text.selection.min != text.selection.max) setFindQuery(selectedText())
        else if (find.query.isNotEmpty()) refreshMatches(jumpToFirst = true)
    }

    fun hideFind() {
        find = FindState()
    }

    fun setFindQuery(q: String) {
        find = find.copy(query = q)
        refreshMatches(jumpToFirst = true)
    }

    fun setReplacement(r: String) {
        find = find.copy(replacement = r)
    }

    fun setFindOptions(options: FindOptions) {
        find = find.copy(options = options)
        refreshMatches(jumpToFirst = true)
    }

    private fun refreshMatches(jumpToFirst: Boolean = false) {
        val matches = TextFinder.findAll(text.text, find.query, find.options)
        val current = if (jumpToFirst) TextFinder.nextIndex(matches, text.selection.min) else find.current.coerceIn(-1, matches.lastIndex)
        find = find.copy(matches = matches, current = current, wrapped = false)
        if (jumpToFirst && current >= 0) selectMatch(current, wrapped = false)
    }

    /** Selects a match and asks the editor to scroll it into view, as Notepad's Find next does. */
    private fun selectMatch(index: Int, wrapped: Boolean) {
        val m = find.matches.getOrNull(index) ?: return
        text = text.copy(selection = TextRange(m.first, m.last + 1))
        find = find.copy(current = index, wrapped = wrapped)
        revealSelection++
    }

    fun findNext() {
        val matches = find.matches
        if (matches.isEmpty()) return
        val from = text.selection.min + 1
        val i = TextFinder.nextIndex(matches, from)
        selectMatch(i, wrapped = matches[i].first < from)
    }

    fun findPrevious() {
        val matches = find.matches
        if (matches.isEmpty()) return
        val from = text.selection.min
        val i = TextFinder.previousIndex(matches, from)
        selectMatch(i, wrapped = matches[i].first >= from)
    }

    /** Replaces the selected match and moves on to the next one. */
    fun replaceCurrent() {
        val m = find.matches.getOrNull(find.current) ?: return
        val replaced = TextFinder.replaceRange(text.text, m, find.replacement)
        onTextChange(TextFieldValue(replaced, TextRange(m.first + find.replacement.length)))
        refreshMatches(jumpToFirst = true)
    }

    fun replaceAll(): Int {
        val (result, count) = TextFinder.replaceAll(text.text, find.query, find.replacement, find.options)
        if (count > 0) onTextChange(TextFieldValue(result, TextRange(text.selection.start.coerceAtMost(result.length))))
        refreshMatches()
        return count
    }

    // ---- Files -----------------------------------------------------------------------------

    fun openFile(uri: Uri) = viewModelScope.launch { openFromUri(uri, note.id) }

    private suspend fun openFromUri(uri: Uri, noteId: Long) {
        try {
            val opened = textFiles.read(uri)
            textFiles.persist(uri)
            fileUri = uri
            val n = notes.get(noteId)?.note ?: return
            val type = if (CodeFileTypes.languageFor(opened.displayName) != null && CodeFileTypes.extensionOf(opened.displayName) !in setOf("txt", "md", "log")) NoteType.CODE else n.type
            val at = notes.update(n.copy(title = n.title.ifBlank { TextImport.titleFor(opened.displayName) }, content = opened.text, fileName = opened.displayName, type = type))
            if (noteId == note.id && loaded) {
                title = title.ifBlank { TextImport.titleFor(opened.displayName) }
                text = TextFieldValue(opened.text)
                note = note.copy(fileName = opened.displayName, type = type, updatedAt = at)
                monospace = type.isMonospaced
                history.clear()
                savedRevision = editRevision
                lastSavedAt = at
            }
        } catch (e: TextFileException) {
            _events.value = EditorEvent.Message(e.message ?: "Could not open file")
        } catch (e: SecurityException) {
            _events.value = EditorEvent.Message("Permission to read the file was denied")
        }
    }

    /** Writes the note to [uri] (chosen by "Save as") or to the file it was opened from. */
    fun saveToFile(uri: Uri? = fileUri) = viewModelScope.launch {
        val target = uri ?: return@launch
        try {
            textFiles.write(target, exportText())
            textFiles.persist(target)
            fileUri = target
            val name = textFiles.displayName(target)
            note = note.copy(fileName = name)
            if (saveNow(force = true)) _events.value = EditorEvent.Message("Saved to ${name ?: "file"}")
        } catch (e: TextFileException) {
            _events.value = EditorEvent.Message(e.message ?: "Could not save file")
        } catch (e: SecurityException) {
            _events.value = EditorEvent.Message("Permission to write the file was denied")
        }
    }

    /** Default extension for Save as: the opened file's, else by note type. */
    fun defaultExtension(): String {
        val current = CodeFileTypes.extensionOf(note.fileName)
        if (current.isNotEmpty()) return current
        return if (note.type == NoteType.CODE) "py" else "txt"
    }

    fun exportBaseName(): String {
        val fromFile = note.fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        return fromFile ?: TextImport.exportFileName(displayTitle).substringBeforeLast('.')
    }

    fun exportFileName(): String = CodeFileTypes.fileName(exportBaseName(), defaultExtension())

    /** The note as plain text for sharing and export. Code notes export their body only. */
    fun exportText(): String {
        val body = when (note.type) {
            NoteType.CHECKLIST -> ChecklistOps.toText(checklist)
            NoteType.RICH -> if (plainTextMode) RichText.toPlainText(text.text) else text.text
            else -> text.text
        }
        return if (title.isBlank() || note.type == NoteType.CODE || note.fileName != null) body else "$title\n\n$body"
    }

    // ---- Attachments ------------------------------------------------------------------------

    fun attachImage(uri: Uri, mime: String?, displayName: String?) = viewModelScope.launch {
        val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "jpg" }
        val copied = attachmentStore.importFrom(uri, ext)
        if (copied == null) {
            _events.value = EditorEvent.Message("Image is too large or could not be read")
            return@launch
        }
        notes.addAttachment(Attachment(noteId = note.id, type = AttachmentType.IMAGE, fileName = copied.first, mimeType = mime ?: "image/jpeg", displayName = displayName ?: copied.first, sizeBytes = copied.second))
    }

    fun attachAudio(fileName: String, durationMs: Long, sizeBytes: Long) = viewModelScope.launch {
        notes.addAttachment(Attachment(noteId = note.id, type = AttachmentType.AUDIO, fileName = fileName, mimeType = "audio/mp4", displayName = "Recording", sizeBytes = sizeBytes, durationMs = durationMs))
    }

    fun setCaption(id: Long, caption: String) = viewModelScope.launch { notes.updateAttachmentCaption(id, caption) }
    fun removeAttachment(id: Long) = viewModelScope.launch { notes.removeAttachment(id) }
    fun attachmentUri(a: Attachment): Uri = attachmentStore.shareUri(a.fileName)
    fun attachmentFile(a: Attachment) = attachmentStore.file(a.fileName)

    // ---- Links -------------------------------------------------------------------------------

    fun loadPreview(url: String) = viewModelScope.launch {
        if (!settings.value.linkPreviewsEnabled) return@launch
        linkPreview = linkPreviews.observe(url).first() ?: linkPreviewFetcher.fetch(url)
        if (linkPreview == null) _events.value = EditorEvent.Message("No preview available")
    }

    fun clearPreview() {
        linkPreview = null
    }

    fun consumeEvent() {
        _events.value = null
    }

    override fun onCleared() {
        observeJob?.cancel()
    }
}
