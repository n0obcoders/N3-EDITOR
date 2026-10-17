package com.nitinshukla.notebook.ui.code

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nitinshukla.notebook.R
import com.nitinshukla.notebook.core.code.CodeEditing
import com.nitinshukla.notebook.core.code.Issue
import com.nitinshukla.notebook.core.code.Language
import com.nitinshukla.notebook.core.text.CodeFileTypes
import com.nitinshukla.notebook.core.text.LineSeparator
import com.nitinshukla.notebook.ui.common.ConfirmDialog
import com.nitinshukla.notebook.ui.common.EmptyState
import com.nitinshukla.notebook.ui.common.formatBytes
import com.nitinshukla.notebook.ui.common.formatTime
import com.nitinshukla.notebook.ui.editor.FindBar
import com.nitinshukla.notebook.ui.editor.SaveAsDialog
import kotlinx.coroutines.launch

/** ACTION_CREATE_DOCUMENT with a per-call MIME type. */
class CreateCodeDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }
}

enum class WorkspaceMode { PYTHON, CODE }

/**
 * The Python editor and the VS Code-style code editor are one screen in two modes. Both
 * share tabs, the editor surface, find/replace, the key bar and the status bar; CODE adds the
 * folder explorer (a side panel on wide screens, a drawer on phones) and PYTHON adds the
 * snippet menu, the offline syntax check and a clearly labelled Run action.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CodeWorkspaceScreen(mode: WorkspaceMode, onBack: () -> Unit, viewModel: CodeWorkspaceViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val event by viewModel.events.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val doc = viewModel.active
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val focus = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var showMenu by remember { mutableStateOf(false) }
    var showSaveAs by remember { mutableStateOf<OpenDocument?>(null) }
    var confirmClose by remember { mutableStateOf<String?>(null) }
    var showGoTo by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showSeparator by remember { mutableStateOf(false) }
    var showRun by remember { mutableStateOf(false) }
    var showSnippets by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }
    var showProblems by remember { mutableStateOf(false) }
    var fontSize by remember(settings.codeFontSizeSp) { mutableStateOf(settings.codeFontSizeSp) }
    var treeAction by remember { mutableStateOf<Pair<TreeNode, String>?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) viewModel.flush() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); viewModel.flush() }
    }

    LaunchedEffect(event) {
        when (val e = event) {
            is WorkspaceEvent.Message -> { snackbar.showSnackbar(e.text); viewModel.consumeEvent() }
            is WorkspaceEvent.ConfirmClose -> { confirmClose = e.id; viewModel.consumeEvent() }
            null -> Unit
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::open) }
    val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { viewModel.openTree(it) } }
    val createDocument = rememberLauncherForActivityResult(CreateCodeDocument()) { uri -> val d = showSaveAs; showSaveAs = null; if (uri != null && d != null) viewModel.saveAs(d, uri) }
    val replacedTemplate = stringResource(R.string.replaced_count)

    fun saveOrSaveAs(d: OpenDocument) { if (!viewModel.save(d)) showSaveAs = d }

    BackHandler(enabled = doc?.find?.visible == true || drawerState.isOpen) {
        if (drawerState.isOpen) scope.launch { drawerState.close() } else doc?.let(viewModel::hideFind)
    }

    val explorer: @Composable () -> Unit = {
        ExplorerPanel(
            viewModel = viewModel,
            onPickFolder = { openTree.launch(null) },
            onOpenFile = { entry -> viewModel.open(entry); scope.launch { drawerState.close() } },
            onAction = { node, action -> treeAction = node to action },
        )
    }

    val content: @Composable () -> Unit = {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (mode == WorkspaceMode.PYTHON) stringResource(R.string.nav_python_editor) else viewModel.treeName ?: stringResource(R.string.nav_code_editor),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (mode == WorkspaceMode.CODE && !wide) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, stringResource(R.string.explorer)) }
                        } else {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                        }
                    },
                    actions = {
                        IconButton(onClick = { openDocument.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileOpen, stringResource(R.string.open_file)) }
                        if (doc != null) {
                            IconButton(onClick = { saveOrSaveAs(doc) }, enabled = doc.dirty || doc.isUntitled) { Icon(Icons.Default.Save, stringResource(R.string.save)) }
                            IconButton(onClick = { viewModel.showFind(doc) }) { Icon(Icons.Default.Search, stringResource(R.string.find)) }
                            if (mode == WorkspaceMode.PYTHON) {
                                IconButton(onClick = { showRun = true }) { Icon(Icons.Default.PlayArrow, stringResource(R.string.run)) }
                            }
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                @Composable fun item(label: String, action: () -> Unit) = DropdownMenuItem(text = { Text(label) }, onClick = { showMenu = false; action() })
                                item(stringResource(R.string.new_file)) { viewModel.newFile() }
                                if (mode == WorkspaceMode.CODE) item(stringResource(R.string.open_folder)) { openTree.launch(null) }
                                if (doc != null) {
                                    item(stringResource(R.string.save_as_file)) { showSaveAs = doc }
                                    item(stringResource(R.string.save_all)) { viewModel.saveAll() }
                                    item(stringResource(R.string.replace)) { viewModel.showFind(doc, replace = true) }
                                    item(stringResource(R.string.go_to_line)) { showGoTo = true }
                                    item(stringResource(R.string.check_syntax)) { viewModel.check(doc); showProblems = true }
                                    if (mode == WorkspaceMode.PYTHON) item(stringResource(R.string.snippets)) { showSnippets = true }
                                    item(stringResource(R.string.command_palette)) { showPalette = true }
                                    HorizontalDivider()
                                    item(stringResource(R.string.select_all)) { viewModel.selectAll(doc) }
                                    item(stringResource(R.string.toggle_comment)) { viewModel.toggleComment(doc) }
                                    item(stringResource(R.string.duplicate_line)) { viewModel.duplicateLine(doc) }
                                    item(stringResource(R.string.delete_line)) { viewModel.deleteLine(doc) }
                                    HorizontalDivider()
                                    item(stringResource(R.string.language_colon, doc.language.displayName)) { showLanguage = true }
                                    item(stringResource(R.string.line_ending_colon, doc.separator.displayName)) { showSeparator = true }
                                    if (doc.changedOnDisk) item(stringResource(R.string.reload_from_disk)) { viewModel.reloadFromDisk(doc) }
                                }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(stringResource(R.string.word_wrap)) }, trailingIcon = { Checkbox(viewModel.wordWrap, null) }, onClick = { viewModel.toggleWordWrap(!viewModel.wordWrap) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.auto_save_files)) }, trailingIcon = { Checkbox(viewModel.autoSave, null) }, onClick = { viewModel.toggleAutoSave(!viewModel.autoSave) })
                                item(stringResource(R.string.font_larger)) { fontSize = (fontSize + 1).coerceAtMost(32) }
                                item(stringResource(R.string.font_smaller)) { fontSize = (fontSize - 1).coerceAtLeast(10) }
                                if (doc != null) {
                                    HorizontalDivider()
                                    item(stringResource(R.string.close_tab)) { viewModel.close(doc.id) }
                                    item(stringResource(R.string.close_others)) { viewModel.closeOthers(doc.id) }
                                }
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Column {
                    if (doc != null && !doc.readOnly) {
                        CodeKeyBar(
                            onInsert = { viewModel.insert(doc, it) },
                            onTab = { viewModel.tab(doc) },
                            onOutdent = { viewModel.outdent(doc) },
                            onUndo = { viewModel.undo(doc) },
                            onRedo = { viewModel.redo(doc) },
                            onComment = { viewModel.toggleComment(doc) },
                            canUndo = doc.history.canUndo,
                            canRedo = doc.history.canRedo,
                        )
                    }
                    if (doc != null) {
                        CodeStatusBar(
                            doc = doc,
                            onLanguage = { showLanguage = true },
                            onSeparator = { showSeparator = true },
                            onProblems = { showProblems = !showProblems },
                            onPosition = { showGoTo = true },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (viewModel.documents.isNotEmpty()) {
                    TabStrip(viewModel.documents, viewModel.activeId, onSelect = viewModel::activate, onClose = { viewModel.close(it) }, onNew = { viewModel.newFile() })
                }
                if (doc == null) {
                    if (!viewModel.restored) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        EmptyWorkspace(mode, folderName = viewModel.treeName, onNew = { viewModel.newFile() }, onOpen = { openDocument.launch(arrayOf("*/*")) }, onOpenFolder = { openTree.launch(null) })
                    }
                } else {
                    if (doc.find.visible) {
                        FindBar(
                            state = doc.find,
                            onQuery = { viewModel.setFindQuery(doc, it) },
                            onReplacement = { viewModel.setReplacement(doc, it) },
                            onOptions = { viewModel.setFindOptions(doc, it) },
                            onNext = { viewModel.findNext(doc) },
                            onPrevious = { viewModel.findPrevious(doc) },
                            onReplace = { viewModel.replaceCurrent(doc) },
                            onReplaceAll = { val n = viewModel.replaceAll(doc); scope.launch { snackbar.showSnackbar(replacedTemplate.format(n)) } },
                            onClose = { viewModel.hideFind(doc) },
                        )
                    }
                    if (doc.changedOnDisk) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null, Modifier.size(18.dp))
                                Text(stringResource(R.string.changed_on_disk), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { viewModel.reloadFromDisk(doc) }) { Text(stringResource(R.string.reload)) }
                            }
                        }
                    }
                    if (doc.large) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                            Text(stringResource(R.string.large_mode_banner, doc.lineCount), style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp))
                        }
                        LargeCodeEditor(
                            doc = doc,
                            fontSizeSp = fontSize,
                            wordWrap = viewModel.wordWrap,
                            onLineChange = { index, value -> viewModel.onLineChange(doc, index, value) },
                            onJoinPrevious = { viewModel.joinWithPrevious(doc, it) },
                            onJoinNext = { viewModel.joinWithNext(doc, it) },
                            onMoveCaret = { viewModel.moveCaret(doc, it) },
                            onTab = { viewModel.tab(doc) },
                            onShiftTab = { viewModel.outdent(doc) },
                            onToggleComment = { viewModel.toggleComment(doc) },
                            onDuplicateLine = { viewModel.duplicateLine(doc) },
                            onMoveLine = { up -> viewModel.moveLine(doc, up) },
                            onUndo = { viewModel.undo(doc) },
                            onRedo = { viewModel.redo(doc) },
                            onFind = { replace -> viewModel.showFind(doc, replace) },
                            onSave = { saveOrSaveAs(doc) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else CodeEditor(
                        doc = doc,
                        fontSizeSp = fontSize,
                        wordWrap = viewModel.wordWrap,
                        onChange = { viewModel.onTextChange(doc, it) },
                        onTab = { viewModel.tab(doc) },
                        onShiftTab = { viewModel.outdent(doc) },
                        onToggleComment = { viewModel.toggleComment(doc) },
                        onDuplicateLine = { viewModel.duplicateLine(doc) },
                        onMoveLine = { up -> viewModel.moveLine(doc, up) },
                        onUndo = { viewModel.undo(doc) },
                        onRedo = { viewModel.redo(doc) },
                        onFind = { replace -> viewModel.showFind(doc, replace) },
                        onSave = { saveOrSaveAs(doc) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        focusRequester = focus,
                    )
                    if (showProblems) {
                        ProblemsPanel(doc.issues, onClose = { showProblems = false; viewModel.clearIssues(doc) }, onJump = { viewModel.goToLine(doc, it.line) })
                    }
                }
            }
        }
    }

    when {
        mode == WorkspaceMode.CODE && wide -> Row(Modifier.fillMaxSize()) {
            Surface(Modifier.width(300.dp).fillMaxHeight(), tonalElevation = 1.dp) { explorer() }
            VerticalDivider()
            Box(Modifier.weight(1f)) { content() }
        }
        mode == WorkspaceMode.CODE -> ModalNavigationDrawer(drawerState = drawerState, drawerContent = { ModalDrawerSheet { explorer() } }) { content() }
        else -> content()
    }

    // ---- Dialogs ----
    showSaveAs?.let { d ->
        SaveAsDialog(
            initialBaseName = d.name.substringBeforeLast('.'),
            initialExtension = CodeFileTypes.extensionOf(d.name).ifEmpty { d.language.extensions.firstOrNull() ?: "txt" },
            onConfirm = { name, mime -> createDocument.launch(name to mime) },
            onDismiss = { showSaveAs = null },
        )
    }
    confirmClose?.let { id ->
        val d = viewModel.documents.firstOrNull { it.id == id }
        if (d == null) confirmClose = null else AlertDialog(
            onDismissRequest = { confirmClose = null },
            title = { Text(stringResource(R.string.unsaved_changes)) },
            text = { Text(stringResource(R.string.unsaved_changes_message, d.name)) },
            confirmButton = { TextButton(onClick = { confirmClose = null; if (d.uri != null) { scope.launch { if (viewModel.saveNow(d)) viewModel.close(d.id, force = true) } } else showSaveAs = d }) { Text(stringResource(R.string.save)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmClose = null; viewModel.close(id, force = true) }) { Text(stringResource(R.string.discard)) }
                    TextButton(onClick = { confirmClose = null }) { Text(stringResource(android.R.string.cancel)) }
                }
            },
        )
    }
    if (showGoTo && doc != null) {
        var line by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGoTo = false },
            title = { Text(stringResource(R.string.go_to_line)) },
            text = { OutlinedTextField(value = line, onValueChange = { line = it.filter(Char::isDigit) }, singleLine = true, label = { Text("1 – ${doc.lineCount}") }) },
            confirmButton = { TextButton(onClick = { line.toIntOrNull()?.let { viewModel.goToLine(doc, it) }; showGoTo = false }) { Text(stringResource(R.string.go)) } },
            dismissButton = { TextButton(onClick = { showGoTo = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (showLanguage && doc != null) {
        AlertDialog(
            onDismissRequest = { showLanguage = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(Language.entries) { l ->
                        ListItem(headlineContent = { Text(l.displayName, fontWeight = if (l == doc.language) FontWeight.Bold else FontWeight.Normal) }, modifier = Modifier.clickable { viewModel.setLanguage(doc, l); showLanguage = false })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLanguage = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (showSeparator && doc != null) {
        AlertDialog(
            onDismissRequest = { showSeparator = false },
            title = { Text(stringResource(R.string.line_ending)) },
            text = { Column { LineSeparator.entries.forEach { s -> ListItem(headlineContent = { Text(s.displayName, fontWeight = if (s == doc.separator) FontWeight.Bold else FontWeight.Normal) }, modifier = Modifier.clickable { viewModel.setSeparator(doc, s); showSeparator = false }) } } },
            confirmButton = { TextButton(onClick = { showSeparator = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (showRun && doc != null) {
        AlertDialog(
            onDismissRequest = { showRun = false },
            icon = { Icon(Icons.Default.PlayArrow, null) },
            title = { Text(stringResource(R.string.run_unavailable_title)) },
            text = { Text(stringResource(R.string.run_unavailable_message)) },
            confirmButton = { TextButton(onClick = { showRun = false; viewModel.check(doc); showProblems = true }) { Text(stringResource(R.string.check_syntax)) } },
            dismissButton = { TextButton(onClick = { showRun = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (showSnippets && doc != null) {
        AlertDialog(
            onDismissRequest = { showSnippets = false },
            title = { Text(stringResource(R.string.snippets)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(PythonSnippets.all) { s ->
                        ListItem(headlineContent = { Text(s.first) }, supportingContent = { Text(s.second.lineSequence().first(), fontFamily = FontFamily.Monospace, maxLines = 1) }, modifier = Modifier.clickable { viewModel.insert(doc, s.second); showSnippets = false })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSnippets = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (showPalette) {
        CommandPalette(
            commands = buildList {
                add(stringResource(R.string.new_file) to { viewModel.newFile(); Unit })
                add(stringResource(R.string.open_file) to { openDocument.launch(arrayOf("*/*")) })
                if (mode == WorkspaceMode.CODE) add(stringResource(R.string.open_folder) to { openTree.launch(null) })
                if (doc != null) {
                    add(stringResource(R.string.save) to { saveOrSaveAs(doc) })
                    add(stringResource(R.string.save_as_file) to { showSaveAs = doc })
                    add(stringResource(R.string.find) to { viewModel.showFind(doc) })
                    add(stringResource(R.string.replace) to { viewModel.showFind(doc, true) })
                    add(stringResource(R.string.go_to_line) to { showGoTo = true })
                    add(stringResource(R.string.toggle_comment) to { viewModel.toggleComment(doc) })
                    add(stringResource(R.string.duplicate_line) to { viewModel.duplicateLine(doc) })
                    add(stringResource(R.string.delete_line) to { viewModel.deleteLine(doc) })
                    add(stringResource(R.string.check_syntax) to { viewModel.check(doc); showProblems = true })
                    add(stringResource(R.string.language) to { showLanguage = true })
                    add(stringResource(R.string.line_ending) to { showSeparator = true })
                    add(stringResource(R.string.close_tab) to { viewModel.close(doc.id) })
                }
                add(stringResource(R.string.word_wrap) to { viewModel.toggleWordWrap(!viewModel.wordWrap) })
            },
            onDismiss = { showPalette = false },
        )
    }
    treeAction?.let { (node, action) ->
        val parent = viewModel.parentOf(node)
        when (action) {
            "newFile", "newFolder", "rename" -> {
                var name by remember(node, action) { mutableStateOf(if (action == "rename") node.entry.name else "") }
                AlertDialog(
                    onDismissRequest = { treeAction = null },
                    title = { Text(stringResource(when (action) { "newFile" -> R.string.new_file; "newFolder" -> R.string.new_folder; else -> R.string.rename })) },
                    text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.file_name)) }) },
                    confirmButton = {
                        TextButton(enabled = name.isNotBlank() && !name.contains('/'), onClick = {
                            when (action) {
                                "newFile" -> viewModel.createFile(node, name.trim())
                                "newFolder" -> viewModel.createFolder(node, name.trim())
                                else -> viewModel.rename(node, parent, name.trim())
                            }
                            treeAction = null
                        }) { Text(stringResource(android.R.string.ok)) }
                    },
                    dismissButton = { TextButton(onClick = { treeAction = null }) { Text(stringResource(android.R.string.cancel)) } },
                )
            }
            "delete" -> ConfirmDialog(stringResource(R.string.delete), stringResource(R.string.delete_entry_confirm, node.entry.name), stringResource(R.string.delete), { viewModel.delete(node, parent) }, { treeAction = null }, destructive = true)
        }
    }
}

@Composable
private fun EmptyWorkspace(mode: WorkspaceMode, folderName: String?, onNew: () -> Unit, onOpen: () -> Unit, onOpenFolder: () -> Unit) {
    val python = mode == WorkspaceMode.PYTHON
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Icon(if (python) Icons.Outlined.Terminal else Icons.Outlined.DataObject, null, Modifier.padding(12.dp).size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column {
                    Text(stringResource(if (python) R.string.nav_python_editor else R.string.nav_code_editor), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(if (python) R.string.welcome_python_subtitle else R.string.welcome_code_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            WelcomeSection(stringResource(R.string.welcome_start)) {
                WelcomeAction(Icons.Default.NoteAdd, stringResource(R.string.new_file), stringResource(R.string.welcome_new_file_hint), "Ctrl+N", onNew)
                HorizontalDivider()
                WelcomeAction(Icons.Default.FileOpen, stringResource(R.string.open_file), stringResource(R.string.welcome_open_file_hint), "Ctrl+O", onOpen)
                if (!python) {
                    HorizontalDivider()
                    WelcomeAction(Icons.Default.FolderOpen, stringResource(R.string.open_folder), stringResource(R.string.welcome_open_folder_hint), null, onOpenFolder)
                }
            }

            WelcomeSection(stringResource(R.string.welcome_workspace)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (folderName != null) stringResource(R.string.welcome_folder_open, folderName) else stringResource(R.string.no_folder_open), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.welcome_large_files), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (python) Text(stringResource(R.string.run_unavailable_message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            WelcomeSection(stringResource(R.string.welcome_shortcuts)) {
                Text(stringResource(R.string.welcome_shortcut_rows), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun WelcomeSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

@Composable
private fun WelcomeAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String, shortcut: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shortcut != null) Text(shortcut, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
}

@Composable
private fun TabStrip(docs: List<OpenDocument>, activeId: String?, onSelect: (String) -> Unit, onClose: (String) -> Unit, onNew: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            docs.forEach { d ->
                val selected = d.id == activeId
                Row(
                    Modifier
                        .background(if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onSelect(d.id) }
                        .padding(start = 12.dp, end = 2.dp)
                        .height(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text((if (d.dirty) "● " else "") + d.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    IconButton(onClick = { onClose(d.id) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, stringResource(R.string.close_tab), Modifier.size(14.dp)) }
                }
            }
            IconButton(onClick = onNew, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Add, stringResource(R.string.new_file), Modifier.size(18.dp)) }
        }
    }
}

/** Symbols that are slow to reach on phone keyboards, plus indent/undo/comment actions. */
@Composable
private fun CodeKeyBar(onInsert: (String) -> Unit, onTab: () -> Unit, onOutdent: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit, onComment: () -> Unit, canUndo: Boolean, canRedo: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            KeyButton("⇥", stringResource(R.string.indent), onTab)
            KeyButton("⇤", stringResource(R.string.outdent), onOutdent)
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo), Modifier.size(18.dp)) }
            IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo), Modifier.size(18.dp)) }
            KeyButton("//", stringResource(R.string.toggle_comment), onComment)
            listOf("(", ")", "[", "]", "{", "}", "<", ">", ";", ":", "'", "\"", "=", "+", "-", "*", "/", "\\", "|", "&", "!", "?", "_", "#", "@", "$", "%", "^", "~", "`").forEach { k ->
                KeyButton(k, k) { onInsert(k) }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(vertical = 4.dp).size(width = 36.dp, height = 34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun CodeStatusBar(doc: OpenDocument, onLanguage: () -> Unit, onSeparator: () -> Unit, onProblems: () -> Unit, onPosition: () -> Unit) {
    val caret = doc.caret()
    val line = caret.line + 1
    val col = caret.column + 1
    val sel = if (doc.large) 0 else doc.text.selection.length
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val small = MaterialTheme.typography.labelSmall
            Text(
                when {
                    doc.saveError != null -> stringResource(R.string.status_save_failed)
                    doc.saving -> stringResource(R.string.status_saving)
                    doc.dirty -> stringResource(R.string.status_unsaved)
                    doc.lastSavedAt != null -> stringResource(R.string.status_saved_at, formatTime(doc.lastSavedAt!!))
                    doc.isUntitled -> stringResource(R.string.untitled)
                    else -> stringResource(R.string.status_saved)
                },
                style = small,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.status_ln_col, line, col) + if (sel > 0) " (" + stringResource(R.string.selected_chars, sel) + ")" else "", style = small, modifier = Modifier.clickable(onClick = onPosition))
            Text(if (doc.language.indentWithTabs) stringResource(R.string.indent_tabs) else stringResource(R.string.indent_spaces, doc.language.indentWidth), style = small)
            Text(doc.encoding.displayName, style = small)
            Text(doc.separator.displayName, style = small, modifier = Modifier.clickable(onClick = onSeparator))
            Text(doc.language.displayName, style = small, modifier = Modifier.clickable(onClick = onLanguage))
            if (doc.issues.isNotEmpty()) {
                Row(Modifier.clickable(onClick = onProblems), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BugReport, null, Modifier.size(14.dp))
                    Text(" ${doc.issues.size}", style = small)
                }
            }
            Text(stringResource(R.string.lines_count, doc.lineCount) + " · " + formatBytes(doc.charCount), style = small)
        }
    }
}

@Composable
private fun ProblemsPanel(issues: List<Issue>, onClose: () -> Unit, onJump: (Issue) -> Unit) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.problems_count, issues.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            if (issues.isEmpty()) {
                Text(stringResource(R.string.no_problems), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn {
                    items(issues) { issue ->
                        ListItem(
                            headlineContent = { Text(issue.message, style = MaterialTheme.typography.bodySmall) },
                            supportingContent = { Text("Ln ${issue.line}, Col ${issue.column}", style = MaterialTheme.typography.labelSmall) },
                            leadingContent = { Icon(if (issue.severity == Issue.Severity.ERROR) Icons.Default.Circle else Icons.Default.Warning, null, Modifier.size(14.dp), tint = if (issue.severity == Issue.Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary) },
                            modifier = Modifier.clickable { onJump(issue) }.height(52.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandPalette(commands: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val shown = commands.filter { it.first.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.command_palette_hint)) }, modifier = Modifier.fillMaxWidth().focusRequester(focus)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(shown) { (label, action) -> ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable { onDismiss(); action() }) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerPanel(viewModel: CodeWorkspaceViewModel, onPickFolder: () -> Unit, onOpenFile: (com.nitinshukla.notebook.data.files.TreeEntry) -> Unit, onAction: (TreeNode, String) -> Unit) {
    val root = viewModel.root
    var menuFor by remember { mutableStateOf<TreeNode?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.explorer).uppercase(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (root != null) {
                IconButton(onClick = { onAction(root, "newFile") }) { Icon(Icons.Default.NoteAdd, stringResource(R.string.new_file), Modifier.size(18.dp)) }
                IconButton(onClick = { onAction(root, "newFolder") }) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.new_folder), Modifier.size(18.dp)) }
                IconButton(onClick = viewModel::refreshTree) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh), Modifier.size(18.dp)) }
            }
            IconButton(onClick = onPickFolder) { Icon(Icons.Default.FolderOpen, stringResource(R.string.open_folder), Modifier.size(18.dp)) }
        }
        if (root == null) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.no_folder_open), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onPickFolder) { Text(stringResource(R.string.open_folder)) }
            }
            if (viewModel.documents.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.open_editors).uppercase(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                viewModel.documents.forEach { d ->
                    ListItem(headlineContent = { Text((if (d.dirty) "● " else "") + d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingContent = { Icon(Icons.Default.Description, null, Modifier.size(18.dp)) }, modifier = Modifier.clickable { viewModel.activate(d.id) }.height(40.dp))
                }
            }
            return
        }
        val rows = viewModel.visibleRows()
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.entry.documentId }) { node ->
                val isOpen = viewModel.documents.any { it.uri == node.entry.uri }
                val isActive = viewModel.active?.uri == node.entry.uri
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .combinedClickable(onClick = { if (node.entry.isDirectory) viewModel.toggle(node) else onOpenFile(node.entry) }, onLongClick = { menuFor = node })
                            .padding(start = (8 + node.depth * 14).dp, end = 8.dp)
                            .height(34.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (node.entry.isDirectory) {
                            Icon(if (node.expanded) Icons.Default.ExpandMore else Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp))
                            Icon(if (node.expanded) Icons.Default.FolderOpen else Icons.Default.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                        } else {
                            Spacer(Modifier.width(18.dp))
                            Icon(Icons.Default.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(node.entry.name, Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (isOpen) FontWeight.SemiBold else FontWeight.Normal)
                        if (node.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        else if (!node.entry.isDirectory && node.entry.sizeBytes > 0) Text(formatBytes(node.entry.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    DropdownMenu(expanded = menuFor === node, onDismissRequest = { menuFor = null }) {
                        if (node.entry.isDirectory) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.new_file)) }, onClick = { menuFor = null; onAction(node, "newFile") })
                            DropdownMenuItem(text = { Text(stringResource(R.string.new_folder)) }, onClick = { menuFor = null; onAction(node, "newFolder") })
                            DropdownMenuItem(text = { Text(stringResource(R.string.refresh)) }, onClick = { menuFor = null; viewModel.refresh(node) })
                        }
                        if (node.depth > 0) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menuFor = null; onAction(node, "rename") })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menuFor = null; onAction(node, "delete") })
                        } else {
                            DropdownMenuItem(text = { Text(stringResource(R.string.close_folder)) }, onClick = { menuFor = null; viewModel.closeTree() })
                        }
                    }
                }
                node.error?.let { Text(it, Modifier.padding(start = (30 + node.depth * 14).dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Small Python templates inserted at the cursor. */
object PythonSnippets {
    val all: List<Pair<String, String>> = listOf(
        "Main guard" to "if __name__ == \"__main__\":\n    main()\n",
        "Function" to "def function_name(arg):\n    \"\"\"Docstring.\"\"\"\n    return arg\n",
        "Class" to "class ClassName:\n    def __init__(self):\n        pass\n",
        "For loop" to "for item in items:\n    pass\n",
        "While loop" to "while condition:\n    pass\n",
        "If / elif / else" to "if condition:\n    pass\nelif other:\n    pass\nelse:\n    pass\n",
        "Try / except" to "try:\n    pass\nexcept Exception as e:\n    print(e)\n",
        "With open" to "with open(\"file.txt\", encoding=\"utf-8\") as f:\n    data = f.read()\n",
        "List comprehension" to "[x for x in items if x]",
        "Dataclass" to "from dataclasses import dataclass\n\n\n@dataclass\nclass Item:\n    name: str\n    value: int = 0\n",
        "argparse" to "import argparse\n\nparser = argparse.ArgumentParser()\nparser.add_argument(\"path\")\nargs = parser.parse_args()\n",
        "Socket client" to "import socket\n\nwith socket.create_connection((\"example.com\", 80), timeout=5) as s:\n    s.sendall(b\"GET / HTTP/1.0\\r\\nHost: example.com\\r\\n\\r\\n\")\n    print(s.recv(4096))\n",
    )
}
