package com.nitinshukla.notebook.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nitinshukla.notebook.R
import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.search.NoteSearch
import com.nitinshukla.notebook.ui.common.EmptyState
import com.nitinshukla.notebook.ui.common.formatDateOnly
import com.nitinshukla.notebook.ui.editor.noteTypeLabel
import com.nitinshukla.notebook.ui.home.NoteCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenNote: (Long) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    var showDates by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    TextField(
                        value = viewModel.input,
                        onValueChange = viewModel::setQuery,
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.commitSearch() }),
                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedIndicatorColor = MaterialTheme.colorScheme.surface, unfocusedIndicatorColor = MaterialTheme.colorScheme.surface),
                        trailingIcon = { if (viewModel.input.isNotEmpty()) IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Default.Close, stringResource(R.string.clear)) } },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = state.filter.pinnedOnly, onClick = { viewModel.update { it.copy(pinnedOnly = !it.pinnedOnly) } }, label = { Text(stringResource(R.string.filter_pinned)) }) }
                item { FilterChip(selected = state.filter.checklistOnly, onClick = { viewModel.update { it.copy(checklistOnly = !it.checklistOnly) } }, label = { Text(stringResource(R.string.filter_checklists)) }) }
                item { FilterChip(selected = state.filter.withAttachments, onClick = { viewModel.update { it.copy(withAttachments = !it.withAttachments) } }, label = { Text(stringResource(R.string.filter_attachments)) }) }
                item { FilterChip(selected = state.filter.modifiedFrom != null, onClick = { if (state.filter.modifiedFrom != null) viewModel.update { it.copy(modifiedFrom = null, modifiedTo = null) } else showDates = true }, label = { Text(state.filter.modifiedFrom?.let { formatDateOnly(it) + " – " + formatDateOnly(state.filter.modifiedTo ?: it) } ?: stringResource(R.string.filter_dates)) }) }
                item { FilterChip(selected = state.includeArchived, onClick = viewModel::toggleArchived, label = { Text(stringResource(R.string.filter_include_archived)) }) }
                items(NoteType.entries) { t -> FilterChip(selected = state.filter.type == t, onClick = { viewModel.update { it.copy(type = if (it.type == t) null else t) } }, label = { Text(stringResource(noteTypeLabel(t))) }) }
            }
            when {
                viewModel.input.isBlank() && !state.filter.hasRefinements -> LazyColumn {
                    if (state.suggestions.isNotEmpty()) item { Text(stringResource(R.string.recent_searches), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp)) }
                    items(state.suggestions) { s ->
                        ListItem(headlineContent = { Text(s) }, leadingContent = { Icon(Icons.Default.History, null) }, trailingContent = { IconButton(onClick = { viewModel.removeRecent(s) }) { Icon(Icons.Default.Close, stringResource(R.string.remove)) } }, modifier = Modifier.clickable { viewModel.setQuery(s); viewModel.commitSearch() })
                    }
                    if (state.suggestions.isEmpty()) item { EmptyState(Icons.Default.Search, stringResource(R.string.search_title), stringResource(R.string.search_message)) }
                }
                state.results.isEmpty() -> EmptyState(Icons.Default.Search, stringResource(R.string.no_results_title), stringResource(R.string.no_results_message))
                else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.suggestions.isNotEmpty() && state.query.isNotBlank()) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(state.suggestions) { s -> FilterChip(selected = false, onClick = { viewModel.setQuery(s.removePrefix("#")); viewModel.commitSearch() }, label = { Text(s) }) }
                            }
                        }
                    }
                    items(state.results, key = { it.id }) { d ->
                        Column {
                            NoteCard(note = d, density = state.density, selected = false, selectionMode = true, inTrash = false, onClick = { viewModel.commitSearch(); onOpenNote(d.id) }, onLongClick = {}, onArchive = {}, onDelete = {}, query = state.query)
                            val snippet = state.snippets[d.id]
                            if (snippet != null && state.query.isNotBlank()) {
                                val ranges = NoteSearch.highlightRanges(snippet, state.query)
                                if (ranges.isNotEmpty()) {
                                    Text(
                                        buildAnnotatedString {
                                            var last = 0
                                            ranges.forEach { r ->
                                                append(snippet.substring(last, r.first))
                                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.tertiaryContainer)) { append(snippet.substring(r.first, r.last + 1)) }
                                                last = r.last + 1
                                            }
                                            append(snippet.substring(last))
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showDates) {
        val range = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showDates = false },
            confirmButton = {
                TextButton(onClick = {
                    val from = range.selectedStartDateMillis
                    val to = range.selectedEndDateMillis?.plus(86_399_999) ?: from?.plus(86_399_999)
                    viewModel.update { it.copy(modifiedFrom = from, modifiedTo = to) }
                    showDates = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
        ) { DateRangePicker(state = range, modifier = Modifier.padding(16.dp)) }
    }
}
