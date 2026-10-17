package com.nitinshukla.notebook.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nitinshukla.notebook.R
import com.nitinshukla.notebook.core.model.ChecklistItem
import com.nitinshukla.notebook.core.text.CodeFileTypes
import com.nitinshukla.notebook.core.text.CursorPosition
import com.nitinshukla.notebook.core.text.FindOptions
import com.nitinshukla.notebook.core.text.TextStats
import com.nitinshukla.notebook.ui.common.formatDate
import com.nitinshukla.notebook.ui.common.formatTime

/** The Notepad-style text area: optional line numbers, optional word wrap, configurable font. */
@Composable
fun TextEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    fontSizeSp: Int,
    fontFamily: FontFamily?,
    wordWrap: Boolean,
    showLineNumbers: Boolean,
    textColor: Color,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    /** Bump this to scroll the current selection (a find match, an undo) into view. */
    revealSelection: Int = 0,
    /** Find matches (end inclusive) to highlight; the field keeps no focus while finding. */
    findMatches: List<IntRange> = emptyList(),
    currentMatch: Int = -1,
    /** False while the find bar is open, so revealing a match does not take its focus. */
    focusOnReveal: Boolean = true,
) {
    val style = TextStyle(fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.5).sp, fontFamily = fontFamily, color = textColor)
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val placeholder = stringResource(R.string.editor_placeholder)
    val description = stringResource(R.string.editor_body)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Notepad's Find next jumps the view to the match; BasicTextField only keeps the caret
    // visible while typing, so selection changes made by the view model scroll explicitly.
    LaunchedEffect(revealSelection) {
        if (revealSelection == 0) return@LaunchedEffect
        val l = layout ?: return@LaunchedEffect
        val offset = value.selection.start.coerceIn(0, l.layoutInput.text.length)
        val line = l.getLineForOffset(offset)
        val top = l.getLineTop(line).toInt()
        val bottom = l.getLineBottom(line).toInt()
        val viewport = vertical.viewportSize
        if (viewport > 0 && (top < vertical.value || bottom > vertical.value + viewport)) {
            vertical.animateScrollTo((top - viewport / 3).coerceIn(0, vertical.maxValue))
        }
        if (!wordWrap) {
            val left = l.getHorizontalPosition(offset, usePrimaryDirection = true).toInt()
            val hViewport = horizontal.viewportSize
            if (hViewport > 0 && (left < horizontal.value || left > horizontal.value + hViewport - 48)) {
                horizontal.animateScrollTo((left - hViewport / 3).coerceIn(0, horizontal.maxValue))
            }
        }
        if (focusOnReveal) focusRequester.requestFocus()
    }

    // When the keyboard opens the viewport shrinks under the caret; scroll it back into view.
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { vertical.viewportSize }.collect { viewport ->
            val l = layout ?: return@collect
            if (viewport <= 0) return@collect
            val line = l.getLineForOffset(value.selection.end.coerceIn(0, l.layoutInput.text.length))
            val top = l.getLineTop(line).toInt()
            val bottom = l.getLineBottom(line).toInt()
            if (bottom > vertical.value + viewport) vertical.scrollTo((bottom - viewport + 48).coerceIn(0, vertical.maxValue))
            else if (top < vertical.value) vertical.scrollTo(top.coerceIn(0, vertical.maxValue))
        }
    }

    val matchColor = MaterialTheme.colorScheme.tertiaryContainer
    val currentMatchColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
    val transformation = remember(findMatches, currentMatch, matchColor) {
        if (findMatches.isEmpty()) androidx.compose.ui.text.input.VisualTransformation.None
        else com.nitinshukla.notebook.ui.code.HighlightTransformation(emptyList(), null, findMatches, currentMatch, matchColor, currentMatchColor)
    }

    Row(modifier.verticalScroll(vertical)) {
        if (showLineNumbers) {
            // Line numbers only line up exactly when wrapping is off; with wrapping they
            // still mark logical lines, which is what Notepad does with its status bar.
            // One Text for all numbers: a composable per line cost thousands of nodes in long notes.
            val numbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
            Text(
                numbers,
                style = style.copy(color = MaterialTheme.colorScheme.outline, fontFamily = FontFamily.Monospace, fontSize = (fontSizeSp - 2).sp, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                modifier = Modifier.padding(start = 8.dp, end = 4.dp).widthIn(min = 28.dp),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .then(if (wordWrap) Modifier else Modifier.horizontalScroll(horizontal)),
        ) {
            if (value.text.isEmpty()) {
                Text(placeholder, style = style.copy(color = MaterialTheme.colorScheme.outline), modifier = Modifier.padding(horizontal = 12.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = style,
                readOnly = readOnly,
                visualTransformation = transformation,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default, autoCorrectEnabled = true),
                onTextLayout = { layout = it },
                modifier = Modifier
                    .then(if (wordWrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = 2000.dp))
                    .padding(horizontal = 12.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = description },
            )
        }
    }
}

@Composable
fun EditorStatusBar(
    text: String,
    cursor: CursorPosition,
    saving: Boolean,
    dirty: Boolean,
    lastSavedAt: Long?,
    saveError: String?,
    modifier: Modifier = Modifier,
) {
    // Word and line counts are O(n). Counting on the main thread on every keystroke made long
    // notes lag, so they are recounted off the main thread once typing pauses.
    val stats by androidx.compose.runtime.produceState(initialValue = remember { TextStats.of(text) }, text) {
        kotlinx.coroutines.delay(300)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { TextStats.of(text) }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val small = MaterialTheme.typography.labelSmall
            Text(stringResource(R.string.status_ln_col, cursor.line, cursor.column), style = small)
            Text(stringResource(R.string.status_words, stats.words), style = small)
            Text(stringResource(R.string.status_chars, stats.characters), style = small)
            Text(stringResource(R.string.status_lines, stats.lines), style = small)
            Text(stringResource(R.string.status_reading, stats.readingMinutes), style = small)
            Text(
                when {
                    saveError != null -> stringResource(R.string.status_save_failed)
                    saving -> stringResource(R.string.status_saving)
                    dirty -> stringResource(R.string.status_unsaved)
                    lastSavedAt != null -> stringResource(R.string.status_saved_at, formatTime(lastSavedAt))
                    else -> stringResource(R.string.status_saved)
                },
                style = small,
                color = if (saveError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** "Created · Last saved" line under the title, from the timestamps the database holds. */
@Composable
fun SaveTimestamps(createdAt: Long, updatedAt: Long, dirty: Boolean, modifier: Modifier = Modifier) {
    val created = remember(createdAt) { formatDate(createdAt) }
    val saved = remember(updatedAt) { formatDate(updatedAt) }
    Text(
        text = stringResource(R.string.created_last_saved, created, saved) + if (dirty) "  •  " + stringResource(R.string.status_unsaved) else "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

/** Rename dialog: changes only the title, immediately, without touching the body. */
@Composable
fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_note)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.title_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(name.text); onDismiss() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(name.text); onDismiss() }) { Text(stringResource(R.string.rename)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/**
 * Save as: a file name, then an extension (one of the common code types or a custom one).
 * The caller hands the result to ACTION_CREATE_DOCUMENT with the matching MIME type.
 */
@Composable
fun SaveAsDialog(
    initialBaseName: String,
    initialExtension: String,
    onConfirm: (fileName: String, mimeType: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var baseName by remember { mutableStateOf(initialBaseName) }
    val known = CodeFileTypes.forExtension(initialExtension) != null
    var extension by remember { mutableStateOf(if (known) initialExtension else "") }
    var custom by remember { mutableStateOf(if (known) "" else initialExtension) }
    val effective = if (extension.isNotEmpty()) extension else custom.trim().removePrefix(".").lowercase()
    val valid = baseName.isNotBlank() && CodeFileTypes.isValidCustomExtension(effective)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_as_file)) },
        text = {
            Column {
                OutlinedTextField(value = baseName, onValueChange = { baseName = it }, singleLine = true, label = { Text(stringResource(R.string.file_name)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.file_type), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CodeFileTypes.all.forEach { t ->
                        FilterChip(selected = extension == t.extension, onClick = { extension = t.extension; custom = "" }, label = { Text("." + t.extension) })
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it; if (it.isNotBlank()) extension = "" },
                    singleLine = true,
                    label = { Text(stringResource(R.string.custom_extension)) },
                    prefix = { Text(".") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (valid) Text(CodeFileTypes.fileName(baseName, effective), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(CodeFileTypes.fileName(baseName, effective), CodeFileTypes.mimeFor(effective)) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
fun FindBar(
    state: FindState,
    onQuery: (String) -> Unit,
    onReplacement: (String) -> Unit,
    onOptions: (FindOptions) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    label = { Text(stringResource(R.string.find)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                Text(
                    when {
                        state.query.isEmpty() -> ""
                        state.matches.isEmpty() -> stringResource(R.string.find_no_matches)
                        else -> "${state.current + 1}/${state.matches.size}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.matches.isEmpty() && state.query.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(onClick = onPrevious, enabled = state.matches.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.find_previous)) }
                IconButton(onClick = onNext, enabled = state.matches.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.find_next)) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            if (state.showReplace) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = state.replacement, onValueChange = onReplacement, singleLine = true, label = { Text(stringResource(R.string.replace_with)) }, modifier = Modifier.weight(1f))
                    TextButton(onClick = onReplace, enabled = state.current >= 0) { Text(stringResource(R.string.replace)) }
                    TextButton(onClick = onReplaceAll, enabled = state.matches.isNotEmpty()) { Text(stringResource(R.string.replace_all)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.wrapped) Text(stringResource(R.string.find_wrapped), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                FilterChip(selected = state.options.matchCase, onClick = { onOptions(state.options.copy(matchCase = !state.options.matchCase)) }, label = { Text(stringResource(R.string.match_case)) })
                FilterChip(selected = state.options.wholeWord, onClick = { onOptions(state.options.copy(wholeWord = !state.options.wholeWord)) }, label = { Text(stringResource(R.string.whole_word)) })
                FilterChip(selected = state.options.useRegex, onClick = { onOptions(state.options.copy(useRegex = !state.options.useRegex)) }, label = { Text(stringResource(R.string.regex)) })
            }
        }
    }
}

@Composable
fun ChecklistEditor(
    items: List<ChecklistItem>,
    textColor: Color,
    fontSizeSp: Int,
    onToggle: (Int) -> Unit,
    onTextChange: (Int, String) -> Unit,
    onAdd: (String, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = items.count { it.isChecked }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        if (items.isNotEmpty()) {
            Text(stringResource(R.string.checklist_progress, done, items.size), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
            LinearProgressIndicator(progress = { done.toFloat() / items.size }, modifier = Modifier.fillMaxWidth().padding(8.dp))
        }
        items.forEachIndexed { index, item ->
            androidx.compose.runtime.key(item.id) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = item.isChecked, onCheckedChange = { onToggle(index) })
                    BasicTextField(
                        value = item.text,
                        onValueChange = { onTextChange(index, it) },
                        textStyle = TextStyle(fontSize = fontSizeSp.sp, color = textColor, textDecoration = if (item.isChecked) TextDecoration.LineThrough else null),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { onAdd("", index + 1) }),
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp).semantics { contentDescription = "Checklist item ${index + 1}" },
                    )
                    IconButton(onClick = { if (index > 0) onMove(index, index - 1) }, enabled = index > 0, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up)) }
                    IconButton(onClick = { if (index < items.lastIndex) onMove(index, index + 1) }, enabled = index < items.lastIndex, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.move_down)) }
                    IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, stringResource(R.string.remove_item)) }
                }
            }
        }
        TextButton(onClick = { onAdd("", items.size) }, modifier = Modifier.padding(start = 4.dp)) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_item))
        }
        Spacer(Modifier.height(80.dp))
    }
}
