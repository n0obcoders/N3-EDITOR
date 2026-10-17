package com.nitinshukla.notebook.ui.templates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nitinshukla.notebook.R
import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.model.Template
import com.nitinshukla.notebook.core.repository.TemplateRepository
import com.nitinshukla.notebook.ui.common.ConfirmDialog
import com.nitinshukla.notebook.ui.editor.noteTypeLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TemplatesViewModel @Inject constructor(private val repo: TemplateRepository) : ViewModel() {
    val templates = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun save(t: Template) = viewModelScope.launch { repo.upsert(t) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(onBack: () -> Unit, onUseTemplate: (Long) -> Unit, viewModel: TemplatesViewModel = hiltViewModel()) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Template?>(null) }
    var deleting by remember { mutableStateOf<Template?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_templates)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) },
        floatingActionButton = { FloatingActionButton(onClick = { editing = Template(name = "") }) { Icon(Icons.Default.Add, stringResource(R.string.new_template)) } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { Text(stringResource(R.string.templates_hint), modifier = Modifier.padding(16.dp)) }
            items(templates, key = { it.id }) { t ->
                ListItem(
                    headlineContent = { Text(t.name) },
                    supportingContent = { Text(t.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), maxLines = 1) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = t }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
                            IconButton(onClick = { deleting = t }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                        }
                    },
                    modifier = Modifier.clickable { onUseTemplate(t.id) },
                )
            }
        }
    }

    editing?.let { t ->
        var name by remember { mutableStateOf(t.name) }
        var title by remember { mutableStateOf(t.title) }
        var content by remember { mutableStateOf(t.content) }
        var type by remember { mutableStateOf(t.type) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (t.id == 0L) R.string.new_template else R.string.edit_template)) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.template_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.template_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text(stringResource(R.string.template_content)) }, modifier = Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp))
                    Row(Modifier.padding(top = 8.dp)) {
                        listOf(NoteType.PLAIN, NoteType.CHECKLIST, NoteType.RICH).forEach { nt ->
                            FilterChip(selected = type == nt, onClick = { type = nt }, label = { Text(stringResource(noteTypeLabel(nt))) }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                    Text(stringResource(R.string.template_date_hint), style = androidx.compose.material3.MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { viewModel.save(t.copy(name = name, title = title, content = content, type = type, isBuiltIn = false)); editing = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    deleting?.let { t ->
        ConfirmDialog(stringResource(R.string.delete_template), stringResource(R.string.delete_template_confirm, t.name), stringResource(R.string.delete), { viewModel.delete(t.id) }, { deleting = null }, destructive = true)
    }
}
