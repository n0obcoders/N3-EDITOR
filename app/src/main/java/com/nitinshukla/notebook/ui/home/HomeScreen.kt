package com.nitinshukla.notebook.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nitinshukla.notebook.R
import com.nitinshukla.notebook.core.model.NoteSummary
import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.search.NoteSection
import com.nitinshukla.notebook.core.search.SortOrder
import com.nitinshukla.notebook.core.settings.AppSettings
import com.nitinshukla.notebook.core.settings.NoteLayout
import com.nitinshukla.notebook.ui.common.ChoiceDialog
import com.nitinshukla.notebook.ui.common.ColorSwatchRow
import com.nitinshukla.notebook.ui.common.ConfirmDialog
import com.nitinshukla.notebook.ui.common.EmptyState
import com.nitinshukla.notebook.ui.common.LoadingState
import com.nitinshukla.notebook.ui.common.SectionHeader
import com.nitinshukla.notebook.ui.common.formatBytes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    settings: AppSettings,
    onOpenDrawer: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onNewNote: (String?) -> Unit,
    onOpenDocument: (android.net.Uri) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pickDocument = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onOpenDocument) }
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.undo)
    val section = filter.section
    val selectionMode = selection.isNotEmpty()

    var showSort by remember { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showNewMenu by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        when (val m = message) {
            is HomeMessage.Undoable -> {
                val result = snackbar.showSnackbar(m.text, actionLabel = undoLabel, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) m.undo()
                viewModel.consumeMessage()
            }
            is HomeMessage.Info -> { snackbar.showSnackbar(m.text); viewModel.consumeMessage() }
            null -> Unit
        }
    }

    val archivedMsg = stringResource(R.string.note_archived)
    val unarchivedMsg = stringResource(R.string.note_unarchived)
    val trashedMsg = stringResource(R.string.note_trashed)
    val title = when (section) {
        NoteSection.NOTES -> stringResource(R.string.nav_notes)
        NoteSection.CODE -> stringResource(R.string.nav_code_notes)
        NoteSection.ARCHIVE -> stringResource(R.string.nav_archive)
        NoteSection.TRASH -> stringResource(R.string.nav_trash)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selection.size)) },
                    navigationIcon = { IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Default.Close, stringResource(R.string.clear_selection)) } },
                    actions = {
                        IconButton(onClick = viewModel::selectAll) { Icon(Icons.Default.SelectAll, stringResource(R.string.select_all)) }
                        if (section == NoteSection.TRASH) {
                            IconButton(onClick = { viewModel.restore() }) { Icon(Icons.Default.Restore, stringResource(R.string.restore)) }
                            IconButton(onClick = { confirmDeleteSelected = true }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.delete_forever)) }
                        } else {
                            val allPinned = viewModel.selectedNotes().all { it.isPinned }
                            IconButton(onClick = { viewModel.pin(pinned = !allPinned) }) { Icon(Icons.Default.PushPin, stringResource(if (allPinned) R.string.unpin else R.string.pin)) }
                            IconButton(onClick = { showColors = true }) { Icon(Icons.Default.Palette, stringResource(R.string.change_color)) }
                            if (section == NoteSection.ARCHIVE) {
                                IconButton(onClick = { viewModel.archive(archived = false, message = unarchivedMsg) }) { Icon(Icons.Default.Unarchive, stringResource(R.string.unarchive)) }
                            } else {
                                IconButton(onClick = { viewModel.archive(archived = true, message = archivedMsg) }) { Icon(Icons.Default.Archive, stringResource(R.string.archive)) }
                            }
                            IconButton(onClick = { viewModel.trash(message = trashedMsg) }) { Icon(Icons.Default.Delete, stringResource(R.string.move_to_trash)) }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            } else {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Default.Menu, stringResource(R.string.open_navigation)) } },
                    actions = {
                        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                        IconButton(onClick = viewModel::toggleLayout) {
                            Icon(if (layout == NoteLayout.GRID) Icons.Default.ViewList else Icons.Default.GridView, stringResource(if (layout == NoteLayout.GRID) R.string.list_view else R.string.grid_view))
                        }
                        IconButton(onClick = { showFilters = !showFilters }) { Icon(Icons.Default.FilterList, stringResource(R.string.filters)) }
                        IconButton(onClick = { showSort = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort)) }
                        if (section == NoteSection.TRASH) {
                            IconButton(onClick = { confirmEmptyTrash = true }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.empty_trash)) }
                        } else {
                            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.nav_settings)) }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (section != NoteSection.TRASH && section != NoteSection.ARCHIVE && !selectionMode) {
                FloatingActionButton(onClick = { if (section == NoteSection.CODE) onNewNote(NoteType.CODE.name) else showNewMenu = true }) {
                    Icon(Icons.Default.Add, stringResource(if (section == NoteSection.CODE) R.string.new_code_note else R.string.new_note))
                    DropdownMenu(expanded = showNewMenu, onDismissRequest = { showNewMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.note_type_text)) }, leadingIcon = { Icon(Icons.Default.Notes, null) }, onClick = { showNewMenu = false; onNewNote(settings.defaultNoteType.name) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.note_type_checklist)) }, leadingIcon = { Icon(Icons.Default.Checklist, null) }, onClick = { showNewMenu = false; onNewNote(NoteType.CHECKLIST.name) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.note_type_image)) }, leadingIcon = { Icon(Icons.Default.Image, null) }, onClick = { showNewMenu = false; onNewNote(NoteType.IMAGE.name) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.note_type_voice)) }, leadingIcon = { Icon(Icons.Default.Mic, null) }, onClick = { showNewMenu = false; onNewNote(NoteType.VOICE.name) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.note_type_drawing)) }, leadingIcon = { Icon(Icons.Default.Brush, null) }, onClick = { showNewMenu = false; onNewNote(NoteType.DRAWING.name) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.note_type_code)) }, leadingIcon = { Icon(Icons.Default.Code, null) }, onClick = { showNewMenu = false; onNewNote(NoteType.CODE.name) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.open_document)) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) }, onClick = { showNewMenu = false; pickDocument.launch(arrayOf("*/*")) })
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showFilters) {
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = filter.pinnedOnly, onClick = { viewModel.setFilter { it.copy(pinnedOnly = !it.pinnedOnly) } }, label = { Text(stringResource(R.string.filter_pinned)) }) }
                    item { FilterChip(selected = filter.checklistOnly, onClick = { viewModel.setFilter { it.copy(checklistOnly = !it.checklistOnly) } }, label = { Text(stringResource(R.string.filter_checklists)) }) }
                    item { FilterChip(selected = filter.withAttachments, onClick = { viewModel.setFilter { it.copy(withAttachments = !it.withAttachments) } }, label = { Text(stringResource(R.string.filter_attachments)) }) }
                }
            }
            when {
                state.loading -> LoadingState()
                state.isEmpty -> EmptyState(
                    icon = when (section) { NoteSection.TRASH -> Icons.Default.Delete; NoteSection.ARCHIVE -> Icons.Default.Archive; NoteSection.CODE -> Icons.Default.Code; else -> Icons.Outlined.Lightbulb },
                    title = stringResource(when (section) { NoteSection.TRASH -> R.string.empty_trash_title; NoteSection.ARCHIVE -> R.string.empty_archive_title; NoteSection.CODE -> R.string.empty_code_title; else -> R.string.empty_notes_title }),
                    message = stringResource(when (section) { NoteSection.TRASH -> R.string.empty_trash_message; NoteSection.ARCHIVE -> R.string.empty_archive_message; NoteSection.CODE -> R.string.empty_code_message; else -> R.string.empty_notes_message }),
                )
                else -> NoteCollection(
                    state = state,
                    layout = layout,
                    settings = settings,
                    selection = selection,
                    section = section,
                    onOpen = { id -> if (selectionMode) viewModel.toggleSelected(id) else onOpenNote(id) },
                    onLongPress = viewModel::toggleSelected,
                    onArchive = { d ->
                        when (section) {
                            NoteSection.TRASH -> viewModel.restore(d.id)
                            NoteSection.ARCHIVE -> viewModel.archive(d.id, false, unarchivedMsg)
                            else -> viewModel.archive(d.id, true, archivedMsg)
                        }
                    },
                    onDelete = { d -> if (section == NoteSection.TRASH) confirmDeleteId = d.id else viewModel.trash(d.id, trashedMsg) },
                )
            }
        }
    }

    if (showSort) {
        ChoiceDialog(stringResource(R.string.sort_by), SortOrder.entries, filter.sort, { it.displayName }, { viewModel.setSort(it) }) { showSort = false }
    }
    if (showColors) {
        ModalBottomSheet(onDismissRequest = { showColors = false }) {
            Text(stringResource(R.string.change_color), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            ColorSwatchRow(selected = settings.defaultNoteColor, onSelect = { viewModel.color(it); showColors = false }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp))
        }
    }
    if (confirmEmptyTrash) {
        ConfirmDialog(stringResource(R.string.empty_trash), stringResource(R.string.empty_trash_confirm), stringResource(R.string.delete_forever), { viewModel.emptyTrash() }, { confirmEmptyTrash = false }, destructive = true)
    }
    confirmDeleteId?.let { id ->
        ConfirmDialog(stringResource(R.string.delete_forever), stringResource(R.string.delete_forever_confirm), stringResource(R.string.delete_forever), { viewModel.deleteForever(id) }, { confirmDeleteId = null }, destructive = true)
    }
    if (confirmDeleteSelected) {
        ConfirmDialog(stringResource(R.string.delete_forever), stringResource(R.string.delete_forever_confirm_many, selection.size), stringResource(R.string.delete_forever), { viewModel.deleteForever() }, { confirmDeleteSelected = false }, destructive = true)
    }
}

@Composable
private fun NoteCollection(
    state: HomeUiState,
    layout: NoteLayout,
    settings: AppSettings,
    selection: Set<Long>,
    section: NoteSection,
    onOpen: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onArchive: (NoteSummary) -> Unit,
    onDelete: (NoteSummary) -> Unit,
) {
    val selectionMode = selection.isNotEmpty()
    val widthDp = LocalConfiguration.current.screenWidthDp
    val columns = when {
        layout == NoteLayout.LIST -> 1
        widthDp >= 900 -> 4
        widthDp >= 600 -> 3
        else -> 2
    }
    val footer = stringResource(R.string.note_count_footer, state.totalCount, formatBytes(state.storageBytes))

    @Composable
    fun card(d: NoteSummary) = NoteCard(
        note = d,
        density = settings.density,
        selected = d.id in selection,
        selectionMode = selectionMode,
        inTrash = section == NoteSection.TRASH,
        onClick = { onOpen(d.id) },
        onLongClick = { onLongPress(d.id) },
        onArchive = { onArchive(d) },
        onDelete = { onDelete(d) },
    )

    if (columns == 1) {
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.pinned.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.section_pinned)) }
                items(state.pinned, key = { "p${it.id}" }) { card(it) }
                item { SectionHeader(stringResource(R.string.section_others)) }
            }
            items(state.others, key = { it.id }) { card(it) }
            item { Text(footer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
        }
    } else {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.pinned.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) { SectionHeader(stringResource(R.string.section_pinned)) }
                items(state.pinned, key = { "p${it.id}" }) { card(it) }
                item(span = StaggeredGridItemSpan.FullLine) { SectionHeader(stringResource(R.string.section_others)) }
            }
            items(state.others, key = { it.id }) { card(it) }
            item(span = StaggeredGridItemSpan.FullLine) { Text(footer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
        }
    }
}
