package com.nitinshukla.notebook.ui.editor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nitinshukla.notebook.R
import com.nitinshukla.notebook.core.model.Attachment
import com.nitinshukla.notebook.core.model.AttachmentType
import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.settings.AppSettings
import com.nitinshukla.notebook.core.text.TextStyleKind
import com.nitinshukla.notebook.security.AppLockManager
import com.nitinshukla.notebook.ui.common.ColorSwatchRow
import com.nitinshukla.notebook.ui.common.ConfirmDialog
import com.nitinshukla.notebook.ui.common.LoadingState
import com.nitinshukla.notebook.ui.common.formatDuration
import com.nitinshukla.notebook.ui.theme.contentColor
import com.nitinshukla.notebook.ui.theme.toComposeColor
import com.nitinshukla.notebook.ui.theme.toFontFamily
import com.nitinshukla.notebook.ui.voice.AudioPlayer
import com.nitinshukla.notebook.ui.voice.VoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** ACTION_CREATE_DOCUMENT with a MIME type chosen per call, so Save as can offer any extension. */
class CreateTypedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenRevisions: (Long) -> Unit,
    onOpenDrawing: (Long) -> Unit,
    onOpenNote: (Long) -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val event by viewModel.events.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val note = viewModel.note

    var showMenu by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showSaveAs by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var showRecorder by remember { mutableStateOf(false) }
    var captionFor by remember { mutableStateOf<Attachment?>(null) }
    var wordWrap by remember(settings.wordWrap) { mutableStateOf(settings.wordWrap) }
    var lineNumbers by remember(settings.showLineNumbers) { mutableStateOf(settings.showLineNumbers) }
    var fontSize by remember(settings.editorFontSizeSp) { mutableStateOf(settings.editorFontSizeSp) }

    fun leave() {
        viewModel.onLeave()
        onBack()
    }

    BackHandler { if (viewModel.find.visible) viewModel.hideFind() else leave() }

    LaunchedEffect(event) {
        when (val e = event) {
            is EditorEvent.Message -> { snackbar.showSnackbar(e.text); viewModel.consumeEvent() }
            EditorEvent.Closed -> { viewModel.consumeEvent(); onBack() }
            null -> Unit
        }
    }
    DisposableEffect(Unit) { onDispose { viewModel.saveCursor() } }

    // ---- Launchers ----
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::openFile) }
    val createDocument = rememberLauncherForActivityResult(CreateTypedDocument()) { uri -> uri?.let { viewModel.saveToFile(it) } }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.attachImage(it, context.contentResolver.getType(it), null) }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showRecorder = true else Toast.makeText(context, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
    }
    val replacedTemplate = stringResource(R.string.replaced_count)

    fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, viewModel.title)
            putExtra(Intent.EXTRA_TEXT, viewModel.exportText())
            val image = viewModel.attachments.firstOrNull { it.type == AttachmentType.IMAGE || it.type == AttachmentType.DRAWING }
            if (image != null) {
                putExtra(Intent.EXTRA_STREAM, viewModel.attachmentUri(image))
                type = image.mimeType
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_note)))
    }

    fun shareAsFile() {
        scope.launch {
            val file = java.io.File(context.cacheDir, "share").apply { mkdirs() }.resolve(viewModel.exportFileName())
            file.writeText(viewModel.exportText())
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_note)))
        }
    }

    if (!viewModel.loaded) { LoadingState(); return }

    if (viewModel.needsUnlock) {
        LockedNoteGate(onBack = onBack) { viewModel.unlocked() }
        return
    }

    val bg = note.color.toComposeColor()
    val fg = note.color.contentColor()
    val fontFamily = if (viewModel.monospace) FontFamily.Monospace else settings.editorFont.toFontFamily()
    val isCode = note.type == NoteType.CODE
    val dirty = viewModel.dirty

    val keyHandler = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
        when (e.key) {
            Key.S -> { viewModel.save(); true }
            Key.Z -> { viewModel.undo(); true }
            Key.Y -> { viewModel.redo(); true }
            Key.F -> { viewModel.showFind(); true }
            Key.H -> { viewModel.showFind(replace = true); true }
            Key.R -> { showRename = true; true }
            Key.B -> { if (note.type == NoteType.RICH) viewModel.toggleStyle(TextStyleKind.BOLD); true }
            Key.I -> { if (note.type == NoteType.RICH) viewModel.toggleStyle(TextStyleKind.ITALIC); true }
            Key.U -> { if (note.type == NoteType.RICH) viewModel.toggleStyle(TextStyleKind.UNDERLINE); true }
            else -> false
        }
    }

    Scaffold(
        modifier = keyHandler,
        containerColor = bg,
        contentColor = fg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, titleContentColor = fg, navigationIconContentColor = fg, actionIconContentColor = fg),
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    // Notepad convention: an asterisk before the name while there are unsaved edits.
                    val name = viewModel.displayTitle.ifBlank { stringResource(R.string.untitled_note) }
                    Text(
                        if (dirty) "*$name" else name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { showRename = true }.semantics { contentDescription = if (dirty) "$name, unsaved changes" else name },
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = viewModel.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                    IconButton(onClick = viewModel::redo, enabled = viewModel.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                    if (note.type != NoteType.CHECKLIST) IconButton(onClick = { viewModel.showFind() }) { Icon(Icons.Default.Search, stringResource(R.string.find)) }
                    IconButton(onClick = viewModel::togglePinned) { Icon(if (note.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin, stringResource(if (note.isPinned) R.string.unpin else R.string.pin)) }
                    IconButton(onClick = viewModel::save) { Icon(Icons.Default.Save, stringResource(R.string.save)) }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            @Composable fun item(label: Int, action: () -> Unit) = DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { showMenu = false; action() })
                            item(R.string.rename_note) { showRename = true }
                            if (note.type != NoteType.CHECKLIST) {
                                item(R.string.find) { viewModel.showFind() }
                                item(R.string.replace) { viewModel.showFind(replace = true) }
                                item(R.string.select_all) { viewModel.selectAll() }
                                item(R.string.cut) { clipboard.setText(AnnotatedString(viewModel.selectedText())); viewModel.deleteSelection() }
                                item(R.string.copy) { clipboard.setText(AnnotatedString(viewModel.selectedText().ifEmpty { viewModel.text.text })) }
                                item(R.string.paste) { clipboard.getText()?.text?.let(viewModel::insertText) }
                                HorizontalDivider()
                            }
                            item(R.string.open_file) { openDocument.launch(arrayOf("text/*", "application/json", "application/xml", "application/javascript", "application/x-sh", "application/octet-stream")) }
                            if (viewModel.fileUri != null) item(R.string.save_to_file) { viewModel.saveToFile() }
                            item(R.string.save_as_file) { showSaveAs = true }
                            item(R.string.share) { share() }
                            item(R.string.share_as_file) { shareAsFile() }
                            item(R.string.copy_note) { clipboard.setText(AnnotatedString(viewModel.exportText())) }
                            HorizontalDivider()
                            item(R.string.change_color) { showColors = true }
                            item(R.string.note_type) { showTypeMenu = true }
                            item(R.string.version_history) { viewModel.save(); onOpenRevisions(note.id) }
                            item(if (note.isLocked) R.string.unlock_note else R.string.lock_note) {
                                val activity = context as? FragmentActivity ?: return@item
                                if (!AppLockManager.canAuthenticate(activity)) {
                                    Toast.makeText(context, R.string.no_device_lock, Toast.LENGTH_LONG).show()
                                } else {
                                    scope.launch {
                                        if (AppLockManager.authenticate(activity, context.getString(R.string.confirm_identity))) viewModel.setLocked(!note.isLocked)
                                    }
                                }
                            }
                            HorizontalDivider()
                            if (note.type != NoteType.CHECKLIST) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.word_wrap)) }, trailingIcon = { Checkbox(wordWrap, null) }, onClick = { wordWrap = !wordWrap })
                                DropdownMenuItem(text = { Text(stringResource(R.string.line_numbers)) }, trailingIcon = { Checkbox(lineNumbers, null) }, onClick = { lineNumbers = !lineNumbers })
                                DropdownMenuItem(text = { Text(stringResource(R.string.monospace)) }, trailingIcon = { Checkbox(viewModel.monospace, null) }, onClick = { viewModel.updateMonospace(!viewModel.monospace) })
                                if (note.type == NoteType.RICH) DropdownMenuItem(text = { Text(stringResource(R.string.plain_text_mode)) }, trailingIcon = { Checkbox(viewModel.plainTextMode, null) }, onClick = { viewModel.updatePlainTextMode(!viewModel.plainTextMode) })
                                item(R.string.font_larger) { fontSize = (fontSize + 2).coerceAtMost(AppSettings.FONT_SIZES.last) }
                                item(R.string.font_smaller) { fontSize = (fontSize - 2).coerceAtLeast(AppSettings.FONT_SIZES.first) }
                            } else {
                                item(R.string.clear_completed) { viewModel.clearCompleted() }
                                item(R.string.uncheck_all) { viewModel.uncheckAll() }
                                item(R.string.completed_to_bottom) { viewModel.completedToBottom() }
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.keep_revisions)) }, trailingIcon = { Checkbox(note.revisionsEnabled, null) }, onClick = { viewModel.setRevisionsEnabled(!note.revisionsEnabled) })
                            HorizontalDivider()
                            item(R.string.duplicate) { viewModel.duplicate() }
                            item(if (note.isArchived) R.string.unarchive else R.string.archive) { viewModel.toggleArchived() }
                            item(R.string.move_to_trash) { confirmTrash = true }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (!isCode) {
                    Surface(color = bg, contentColor = fg) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (note.type == NoteType.RICH) {
                                IconButton(onClick = { viewModel.toggleStyle(TextStyleKind.BOLD) }) { Icon(Icons.Default.FormatBold, stringResource(R.string.bold)) }
                                IconButton(onClick = { viewModel.toggleStyle(TextStyleKind.ITALIC) }) { Icon(Icons.Default.FormatItalic, stringResource(R.string.italic)) }
                                IconButton(onClick = { viewModel.toggleStyle(TextStyleKind.UNDERLINE) }) { Icon(Icons.Default.FormatUnderlined, stringResource(R.string.underline)) }
                            }
                            IconButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.Image, stringResource(R.string.attach_image)) }
                            IconButton(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Icon(Icons.Default.Mic, stringResource(R.string.record_audio)) }
                            IconButton(onClick = { viewModel.save(); onOpenDrawing(note.id) }) { Icon(Icons.Default.Brush, stringResource(R.string.draw)) }
                            IconButton(onClick = { showColors = true }) { Icon(Icons.Default.Palette, stringResource(R.string.change_color)) }
                            Spacer(Modifier.weight(1f))
                            if (note.isLocked) Icon(Icons.Default.Lock, stringResource(R.string.locked), Modifier.padding(end = 12.dp).size(18.dp))
                        }
                    }
                }
                if (settings.showStatusBar && note.type != NoteType.CHECKLIST) {
                    val cursor = remember(viewModel.text.text, viewModel.text.selection.start) { viewModel.cursor }
                    EditorStatusBar(viewModel.text.text, cursor, viewModel.saving, dirty, viewModel.lastSavedAt, viewModel.saveError)
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (viewModel.find.visible) {
                FindBar(
                    state = viewModel.find,
                    onQuery = viewModel::setFindQuery,
                    onReplacement = viewModel::setReplacement,
                    onOptions = viewModel::setFindOptions,
                    onNext = viewModel::findNext,
                    onPrevious = viewModel::findPrevious,
                    onReplace = viewModel::replaceCurrent,
                    onReplaceAll = {
                        val n = viewModel.replaceAll()
                        scope.launch { snackbar.showSnackbar(replacedTemplate.format(n)) }
                    },
                    onClose = viewModel::hideFind,
                )
            }
            BasicTextField(
                value = viewModel.title,
                onValueChange = viewModel::onTitleChange,
                textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = fg),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                singleLine = true,
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (viewModel.title.isEmpty()) Text(stringResource(R.string.title_hint), fontSize = 22.sp, color = MaterialTheme.colorScheme.outline)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Note title" },
            )
            SaveTimestamps(createdAt = note.createdAt, updatedAt = note.updatedAt, dirty = dirty)
            note.fileName?.let { name ->
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { AssistChip(onClick = { showSaveAs = true }, label = { Text(name) }) }
                }
            }
            if (viewModel.attachments.isNotEmpty()) {
                AttachmentsRow(viewModel, onCaption = { captionFor = it })
            }
            Box(Modifier.weight(1f)) {
                if (note.type == NoteType.CHECKLIST) {
                    ChecklistEditor(viewModel.checklist, fg, fontSize, viewModel::toggleChecklistItem, viewModel::setChecklistText, viewModel::addChecklistItem, viewModel::removeChecklistItem, viewModel::moveChecklistItem)
                } else {
                    TextEditor(
                        value = viewModel.text,
                        onValueChange = viewModel::onTextChange,
                        fontSizeSp = if (isCode) settings.codeFontSizeSp.coerceAtLeast(fontSize - 4) else fontSize,
                        fontFamily = fontFamily,
                        wordWrap = if (isCode) false else wordWrap,
                        showLineNumbers = if (isCode) true else lineNumbers,
                        textColor = fg,
                        readOnly = false,
                        modifier = Modifier.fillMaxSize(),
                        revealSelection = viewModel.revealSelection,
                        findMatches = if (viewModel.find.visible && viewModel.find.query.isNotEmpty()) viewModel.find.matches else emptyList(),
                        currentMatch = viewModel.find.current,
                        focusOnReveal = !viewModel.find.visible,
                    )
                }
            }
            val links = viewModel.links
            if (links.isNotEmpty() && note.type != NoteType.CHECKLIST && !isCode) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(links.distinctBy { it.url }.take(10)) { link ->
                        AssistChip(
                            onClick = { runCatching { uriHandler.openUri(link.url) } },
                            label = { Text(link.url.removePrefix("https://").removePrefix("http://").take(40)) },
                            trailingIcon = if (settings.linkPreviewsEnabled) ({ TextButton(onClick = { viewModel.loadPreview(link.url) }) { Text(stringResource(R.string.preview)) } }) else null,
                        )
                    }
                }
            }
            viewModel.linkPreview?.let { p ->
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title ?: p.url, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            p.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3) }
                            Text(p.url, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        IconButton(onClick = viewModel::clearPreview) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    }
                }
            }
        }
    }

    // ---- Dialogs ----
    if (showRename) {
        RenameDialog(current = viewModel.title, onRename = viewModel::rename, onDismiss = { showRename = false })
    }
    if (showSaveAs) {
        SaveAsDialog(
            initialBaseName = viewModel.exportBaseName(),
            initialExtension = viewModel.defaultExtension(),
            onConfirm = { name, mime -> showSaveAs = false; createDocument.launch(name to mime) },
            onDismiss = { showSaveAs = false },
        )
    }
    if (showColors) {
        ModalBottomSheet(onDismissRequest = { showColors = false }) {
            Text(stringResource(R.string.change_color), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            ColorSwatchRow(selected = note.color, onSelect = { viewModel.setColor(it) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp))
        }
    }
    if (showTypeMenu) {
        AlertDialog(
            onDismissRequest = { showTypeMenu = false },
            title = { Text(stringResource(R.string.note_type)) },
            text = {
                Column {
                    NoteType.entries.forEach { t ->
                        TextButton(onClick = { viewModel.setType(t); showTypeMenu = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(noteTypeLabel(t)), fontWeight = if (t == note.type) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTypeMenu = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (confirmTrash) {
        ConfirmDialog(stringResource(R.string.move_to_trash), stringResource(R.string.move_to_trash_confirm), stringResource(R.string.move_to_trash), { viewModel.moveToTrash() }, { confirmTrash = false })
    }
    if (showRecorder) {
        RecorderDialog(onDismiss = { showRecorder = false }, onSaved = { name, duration, size -> viewModel.attachAudio(name, duration, size) }, newFile = { viewModel.attachmentFile(Attachment(type = AttachmentType.AUDIO, fileName = java.util.UUID.randomUUID().toString().replace("-", "") + ".m4a", mimeType = "audio/mp4")) })
    }
    captionFor?.let { a ->
        var caption by remember { mutableStateOf(a.caption) }
        AlertDialog(
            onDismissRequest = { captionFor = null },
            title = { Text(stringResource(R.string.caption)) },
            text = { OutlinedTextField(value = caption, onValueChange = { caption = it }) },
            confirmButton = { TextButton(onClick = { viewModel.setCaption(a.id, caption); captionFor = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { captionFor = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

fun noteTypeLabel(t: NoteType): Int = when (t) {
    NoteType.PLAIN -> R.string.note_type_text
    NoteType.RICH -> R.string.note_type_rich
    NoteType.CHECKLIST -> R.string.note_type_checklist
    NoteType.IMAGE -> R.string.note_type_image
    NoteType.VOICE -> R.string.note_type_voice
    NoteType.DRAWING -> R.string.note_type_drawing
    NoteType.LINK -> R.string.note_type_link
    NoteType.CODE -> R.string.note_type_code
}
@Composable
private fun AttachmentsRow(viewModel: EditorViewModel, onCaption: (Attachment) -> Unit) {
    val context = LocalContext.current
    val player = remember { AudioPlayer() }
    var playing by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<Attachment?>(null) }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(viewModel.attachments, key = { it.id }) { a ->
            Card(Modifier.width(160.dp)) {
                Column {
                    when (a.type) {
                        AttachmentType.IMAGE, AttachmentType.DRAWING -> AsyncImage(
                            model = viewModel.attachmentFile(a),
                            contentDescription = a.caption.ifBlank { a.displayName },
                            modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                .clickable {
                                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(viewModel.attachmentUri(a), a.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    runCatching { context.startActivity(intent) }
                                },
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                        AttachmentType.AUDIO -> Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                player.toggle(viewModel.attachmentFile(a)) { playing = null }
                                playing = player.playingFile
                            }) { Icon(if (playing == a.fileName) Icons.Default.Stop else Icons.Default.PlayArrow, stringResource(if (playing == a.fileName) R.string.stop else R.string.play)) }
                            Text(formatDuration(a.durationMs ?: 0), style = MaterialTheme.typography.bodyMedium)
                        }
                        AttachmentType.FILE -> Text(a.displayName, Modifier.padding(8.dp), maxLines = 2)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onCaption(a) }, modifier = Modifier.weight(1f)) { Text(a.caption.ifBlank { stringResource(R.string.add_caption) }, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                        IconButton(onClick = { confirmRemove = a }) { Icon(Icons.Default.Close, stringResource(R.string.remove_attachment)) }
                    }
                }
            }
        }
    }
    confirmRemove?.let { a ->
        ConfirmDialog(stringResource(R.string.remove_attachment), stringResource(R.string.remove_attachment_confirm), stringResource(R.string.remove), { viewModel.removeAttachment(a.id) }, { confirmRemove = null }, destructive = true)
    }
}

@Composable
private fun RecorderDialog(onDismiss: () -> Unit, onSaved: (String, Long, Long) -> Unit, newFile: () -> java.io.File) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { recorder.start(newFile()) } catch (e: Exception) { error = e.message ?: "Could not start recording" }
        while (true) { elapsed = recorder.elapsedMs(); delay(250) }
    }
    DisposableEffect(Unit) { onDispose { if (recorder.isRecording) recorder.cancel() } }
    AlertDialog(
        onDismissRequest = { recorder.cancel(); onDismiss() },
        title = { Text(stringResource(R.string.recording)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(formatDuration(elapsed), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.mic_rationale), style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Row {
                IconButton(onClick = { if (paused) recorder.resume() else recorder.pause(); paused = !paused }) { Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, stringResource(if (paused) R.string.resume else R.string.pause)) }
                Button(onClick = {
                    val file = recorder.file
                    val duration = recorder.stop()
                    if (file != null && duration != null && duration > 300) onSaved(file.name, duration, file.length()) else file?.delete()
                    onDismiss()
                }) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.stop_and_save)) }
            }
        },
        dismissButton = { TextButton(onClick = { recorder.cancel(); onDismiss() }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun LockedNoteGate(onBack: () -> Unit, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.unlock_note)
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.locked_note_message), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            val activity = context as? FragmentActivity ?: return@Button
            scope.launch { if (AppLockManager.authenticate(activity, title)) onUnlocked() }
        }) { Icon(Icons.Default.LockOpen, null); Spacer(Modifier.width(8.dp)); Text(title) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
}
