package com.nitinshukla.notebook.ui.revisions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nitinshukla.notebook.R
import com.nitinshukla.notebook.core.model.Revision
import com.nitinshukla.notebook.core.repository.NoteRepository
import com.nitinshukla.notebook.core.repository.RevisionRepository
import com.nitinshukla.notebook.core.revision.DiffLine
import com.nitinshukla.notebook.core.revision.DiffOp
import com.nitinshukla.notebook.core.revision.LineDiff
import com.nitinshukla.notebook.ui.common.ConfirmDialog
import com.nitinshukla.notebook.ui.common.EmptyState
import com.nitinshukla.notebook.ui.common.formatBytes
import com.nitinshukla.notebook.ui.common.formatDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RevisionsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val revisions: RevisionRepository,
    private val notes: NoteRepository,
) : ViewModel() {
    val noteId: Long = savedState.get<Long>("noteId") ?: 0L
    val list = revisions.observe(noteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var current by mutableStateOf("")
        private set
    var diff by mutableStateOf<List<DiffLine>?>(null)
        private set
    var comparing by mutableStateOf<Revision?>(null)
        private set

    init {
        viewModelScope.launch { current = notes.get(noteId)?.note?.content.orEmpty() }
    }

    fun compare(r: Revision) {
        comparing = r
        diff = LineDiff.diff(r.content, current)
    }

    fun closeCompare() {
        comparing = null; diff = null
    }

    fun restore(r: Revision) = viewModelScope.launch {
        val details = notes.get(noteId) ?: return@launch
        // The current text becomes a revision so the restore itself can be undone.
        revisions.insert(Revision(noteId = noteId, title = details.note.title, content = details.note.content, createdAt = System.currentTimeMillis()))
        notes.update(details.note.copy(title = r.title, content = r.content))
        current = r.content
        closeCompare()
    }

    fun delete(id: Long) = viewModelScope.launch { revisions.delete(listOf(id)) }
    fun deleteAll() = viewModelScope.launch { revisions.deleteAllFor(noteId) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevisionsScreen(onBack: () -> Unit, viewModel: RevisionsViewModel = hiltViewModel()) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    var confirmRestore by remember { mutableStateOf<Revision?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val diff = viewModel.diff

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.version_history)) },
            navigationIcon = { IconButton(onClick = { if (diff != null) viewModel.closeCompare() else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { if (list.isNotEmpty() && diff == null) IconButton(onClick = { confirmDeleteAll = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete_all_revisions)) } },
        )
    }) { padding ->
        if (diff != null) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(stringResource(R.string.comparing_with, formatDate(viewModel.comparing?.createdAt ?: 0)), modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirmRestore = viewModel.comparing }) { Icon(Icons.Default.Restore, null); Text(stringResource(R.string.restore)) }
                }
                LazyColumn {
                    items(diff) { line ->
                        val (bg, prefix) = when (line.op) {
                            DiffOp.ADDED -> Color(0x3300AA00) to "+ "
                            DiffOp.REMOVED -> Color(0x33DD0000) to "- "
                            DiffOp.EQUAL -> Color.Transparent to "  "
                        }
                        Text(prefix + line.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 2.dp))
                    }
                }
            }
        } else if (list.isEmpty()) {
            EmptyState(Icons.Default.History, stringResource(R.string.no_revisions_title), stringResource(R.string.no_revisions_message), Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.id }) { r ->
                    ListItem(
                        headlineContent = { Text(formatDate(r.createdAt)) },
                        supportingContent = { Text((r.title.ifBlank { r.content }).lineSequence().firstOrNull().orEmpty().take(80) + " · " + formatBytes(r.sizeBytes), maxLines = 1) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { confirmRestore = r }) { Icon(Icons.Default.Restore, stringResource(R.string.restore)) }
                                IconButton(onClick = { viewModel.delete(r.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                            }
                        },
                        modifier = Modifier.clickable { viewModel.compare(r) },
                    )
                }
            }
        }
    }
    confirmRestore?.let { r ->
        ConfirmDialog(stringResource(R.string.restore_revision), stringResource(R.string.restore_revision_confirm, formatDate(r.createdAt)), stringResource(R.string.restore), { viewModel.restore(r) }, { confirmRestore = null })
    }
    if (confirmDeleteAll) {
        ConfirmDialog(stringResource(R.string.delete_all_revisions), stringResource(R.string.delete_all_revisions_confirm), stringResource(R.string.delete), { viewModel.deleteAll() }, { confirmDeleteAll = false }, destructive = true)
    }
}
