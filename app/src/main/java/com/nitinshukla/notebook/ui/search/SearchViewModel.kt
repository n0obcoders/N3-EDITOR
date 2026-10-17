package com.nitinshukla.notebook.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nitinshukla.notebook.core.model.NoteSummary
import com.nitinshukla.notebook.core.repository.NoteRepository
import com.nitinshukla.notebook.core.repository.SettingsRepository
import com.nitinshukla.notebook.core.search.NoteFilter
import com.nitinshukla.notebook.core.search.NoteSearch
import com.nitinshukla.notebook.core.search.NoteSection
import com.nitinshukla.notebook.core.settings.Density
import com.nitinshukla.notebook.data.repository.RoomNoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val filter: NoteFilter = NoteFilter(),
    val includeArchived: Boolean = false,
    val results: List<NoteSummary> = emptyList(),
    /** Excerpt around the first match for each result that has one, keyed by note id. */
    val snippets: Map<Long, String> = emptyMap(),
    val suggestions: List<String> = emptyList(),
    val density: Density = Density.COMFORTABLE,
)

/**
 * Search runs in two steps: SQLite finds the ids whose full text contains every term, then
 * the in-memory summary list is filtered and sorted. Only the matching notes' content is
 * loaded, for snippets, so a large library never comes into memory at once.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    notes: NoteRepository,
    private val roomNotes: RoomNoteRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    /**
     * What the search box shows. It is updated synchronously on every keystroke; [state] lags
     * behind by the debounce, so binding the text field to `state.query` dropped characters.
     */
    var input by androidx.compose.runtime.mutableStateOf("")
        private set
    private val filter = MutableStateFlow(NoteFilter())
    private val includeArchived = MutableStateFlow(false)

    val state: StateFlow<SearchUiState> = combine(notes.observeSummaries(), query.debounce(150), filter, includeArchived, settings.settings) { all, q, f, archived, prefs ->
        val effective = f.copy(query = q, section = NoteSection.NOTES)
        var snippets: Map<Long, String> = emptyMap()
        val results = if (q.isBlank() && !f.hasRefinements) {
            emptyList()
        } else {
            val fullText = roomNotes.searchIds(q)
            val main = NoteSearch.apply(all, effective, fullText)
            val list = if (archived) NoteSearch.sort(main + NoteSearch.apply(all, effective.copy(section = NoteSection.ARCHIVE), fullText), f.sort) else main
            if (q.isNotBlank()) {
                val contents = roomNotes.contents(list.asSequence().filter { !it.isLocked }.map { it.id }.take(SNIPPET_LIMIT).toList())
                snippets = contents.mapValues { (_, text) -> NoteSearch.snippet(text, q) }
            }
            list
        }
        SearchUiState(
            query = q,
            filter = f,
            includeArchived = archived,
            results = results,
            snippets = snippets,
            suggestions = NoteSearch.suggestions(all, prefs.recentSearches, q),
            density = prefs.density,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(q: String) {
        input = q
        query.value = q
    }

    fun update(transform: (NoteFilter) -> NoteFilter) {
        filter.value = transform(filter.value)
    }

    fun toggleArchived() {
        includeArchived.value = !includeArchived.value
    }

    fun commitSearch() {
        val q = input.trim()
        if (q.isEmpty()) return
        query.value = input
        viewModelScope.launch { settings.update { it.copy(recentSearches = NoteSearch.rememberSearch(it.recentSearches, q)) } }
    }

    fun removeRecent(q: String) {
        viewModelScope.launch { settings.update { it.copy(recentSearches = it.recentSearches - q) } }
    }

    private companion object {
        /** Snippets are only built for the first screenfuls of results. */
        const val SNIPPET_LIMIT = 60
    }
}
