package com.nitinshukla.notebook.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nitinshukla.notebook.core.model.NoteColor
import com.nitinshukla.notebook.core.model.NoteSummary
import com.nitinshukla.notebook.core.repository.NoteRepository
import com.nitinshukla.notebook.core.repository.SettingsRepository
import com.nitinshukla.notebook.core.search.NoteFilter
import com.nitinshukla.notebook.core.search.NoteSearch
import com.nitinshukla.notebook.core.search.NoteSection
import com.nitinshukla.notebook.core.search.SortOrder
import com.nitinshukla.notebook.core.settings.NoteLayout
import com.nitinshukla.notebook.data.files.AttachmentStore
import com.nitinshukla.notebook.data.repository.RoomNoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val pinned: List<NoteSummary> = emptyList(),
    val others: List<NoteSummary> = emptyList(),
    val totalCount: Int = 0,
    val storageBytes: Long = 0,
) {
    val isEmpty: Boolean get() = !loading && pinned.isEmpty() && others.isEmpty()
}

sealed interface HomeMessage {
    data class Undoable(val text: String, val undo: suspend () -> Unit) : HomeMessage
    data class Info(val text: String) : HomeMessage
}

/**
 * Drives the home list. It observes note *summaries*, so a keystroke saved in the editor
 * re-runs one cheap projection query rather than reloading every note with its relations,
 * and filtering/sorting happens off the main thread.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val notes: NoteRepository,
    private val roomNotes: RoomNoteRepository,
    private val settingsRepository: SettingsRepository,
    private val attachmentStore: AttachmentStore,
) : ViewModel() {
    private val _filter = MutableStateFlow(NoteFilter())
    val filter: StateFlow<NoteFilter> = _filter
    val section: StateFlow<NoteSection> = _filter.map { it.section }.stateIn(viewModelScope, SharingStarted.Eagerly, NoteSection.NOTES)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection

    private val _messages = MutableStateFlow<HomeMessage?>(null)
    val messages: StateFlow<HomeMessage?> = _messages

    private val _storage = MutableStateFlow(0L)

    val layout: StateFlow<NoteLayout> = settingsRepository.settings.map { it.layout }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteLayout.GRID)

    private val sortSetting = settingsRepository.settings.map { it.sortOrder }.distinctUntilChanged()

    val state: StateFlow<HomeUiState> = combine(notes.observeSummaries(), _filter, sortSetting, _storage) { all, filter, savedSort, storage ->
        val effective = if (filter.sort == SortOrder.MODIFIED_DESC && savedSort != SortOrder.MODIFIED_DESC && !sortOverridden) filter.copy(sort = savedSort) else filter
        val results = NoteSearch.apply(all, effective)
        val showPinnedSection = effective.section == NoteSection.NOTES || effective.section == NoteSection.CODE
        HomeUiState(
            loading = false,
            pinned = if (showPinnedSection) results.filter { it.isPinned } else emptyList(),
            others = if (showPinnedSection) results.filter { !it.isPinned } else results,
            totalCount = all.count { !it.isTrashed },
            storageBytes = storage,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private var sortOverridden = false

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            _storage.value = roomNotes.approximateTextBytes() + attachmentStore.totalBytes()
        }
    }

    fun setSection(section: NoteSection) {
        if (_filter.value.section != section) _filter.value = _filter.value.copy(section = section)
        clearSelection()
    }

    fun setFilter(transform: (NoteFilter) -> NoteFilter) {
        _filter.value = transform(_filter.value)
    }

    fun setSort(sort: SortOrder) {
        sortOverridden = true
        _filter.value = _filter.value.copy(sort = sort)
        viewModelScope.launch { settingsRepository.update { it.copy(sortOrder = sort) } }
    }

    fun toggleLayout() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(layout = if (it.layout == NoteLayout.GRID) NoteLayout.LIST else NoteLayout.GRID) }
        }
    }

    fun toggleSelected(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        if (_selection.value.isNotEmpty()) _selection.value = emptySet()
    }

    fun selectAll() {
        _selection.value = (state.value.pinned + state.value.others).map { it.id }.toSet()
    }

    private fun targets(id: Long?): Set<Long> = id?.let { setOf(it) } ?: _selection.value

    fun pin(id: Long? = null, pinned: Boolean) = viewModelScope.launch { notes.setPinned(targets(id), pinned); clearSelection() }
    fun color(color: NoteColor) = viewModelScope.launch { notes.setColor(targets(null), color); clearSelection() }

    fun archive(id: Long? = null, archived: Boolean, message: String) = viewModelScope.launch {
        val ids = targets(id)
        notes.setArchived(ids, archived)
        clearSelection()
        _messages.value = HomeMessage.Undoable(message) { notes.setArchived(ids, !archived) }
    }

    fun trash(id: Long? = null, message: String) = viewModelScope.launch {
        val ids = targets(id)
        notes.moveToTrash(ids)
        clearSelection()
        _messages.value = HomeMessage.Undoable(message) { notes.restoreFromTrash(ids) }
    }

    fun restore(id: Long? = null) = viewModelScope.launch { notes.restoreFromTrash(targets(id)); clearSelection() }
    fun deleteForever(id: Long? = null) = viewModelScope.launch { notes.deletePermanently(targets(id)); clearSelection(); refreshStorage() }
    fun emptyTrash() = viewModelScope.launch { notes.emptyTrash(); refreshStorage() }
    fun duplicate(id: Long) = viewModelScope.launch { notes.duplicate(id) }

    fun reorder(idsInOrder: List<Long>) = viewModelScope.launch { notes.updateManualOrder(idsInOrder) }

    fun consumeMessage() {
        _messages.value = null
    }

    fun selectedNotes(): List<NoteSummary> = (state.value.pinned + state.value.others).filter { it.id in _selection.value }
}
