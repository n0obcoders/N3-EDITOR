package com.nitinshukla.notebook.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nitinshukla.notebook.core.code.Language
import com.nitinshukla.notebook.core.code.SyntaxHighlighter

/**
 * Editor for [OpenDocument.large] documents. Every line is its own small text field inside a
 * lazy list, so only the lines on screen are laid out, highlighted or recomposed, whatever
 * the file size. Enter splits a line, Backspace at column 0 joins it with the previous one,
 * Delete at the end pulls the next line up, and Up/Down move between lines. A selection
 * cannot span lines in this mode; find, replace, go to line and the line commands all work.
 */
@Composable
fun LargeCodeEditor(
    doc: OpenDocument,
    fontSizeSp: Int,
    wordWrap: Boolean,
    onLineChange: (Int, TextFieldValue) -> Unit,
    onJoinPrevious: (Int) -> Unit,
    onJoinNext: (Int) -> Unit,
    onMoveCaret: (Int) -> Unit,
    onTab: () -> Unit,
    onShiftTab: () -> Unit,
    onToggleComment: () -> Unit,
    onDuplicateLine: () -> Unit,
    onMoveLine: (up: Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: (replace: Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val colors = remember(dark) { HighlightColors(dark) }
    val textColor = MaterialTheme.colorScheme.onSurface
    val style = TextStyle(fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.45).sp, fontFamily = FontFamily.Monospace, color = textColor)
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    val lines = doc.lines
    val digits = maxOf(2, lines.size.toString().length)
    val charWidth: Dp = with(density) { (fontSizeSp * 0.62f).sp.toDp() }
    val gutterWidth = charWidth * digits + 16.dp
    val contentWidth = if (wordWrap) null else (charWidth * (doc.longestLine + 6) + 24.dp).coerceAtLeast(320.dp)
    val gutterColor = MaterialTheme.colorScheme.outline
    val activeColor = MaterialTheme.colorScheme.onSurface
    val currentLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.07f else 0.05f)
    val gutterBackground = MaterialTheme.colorScheme.surfaceContainerLow
    val cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
    val numberStyle = style.copy(fontSize = (fontSizeSp - 2).sp, color = gutterColor, textAlign = TextAlign.End)

    // Bring the line that asked for focus on screen; the row itself takes focus once composed.
    LaunchedEffect(doc.focusRequest, doc.id) {
        val target = doc.focusLine
        if (doc.focusRequest == 0 || target < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        val fullyVisible = visible.any { it.index == target && it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
        if (!fullyVisible) listState.scrollToItem((target - 2).coerceAtLeast(0))
    }

    // Keep the line being edited above the keyboard when it opens and the list shrinks.
    LaunchedEffect(doc.id) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.viewportEndOffset }.collect { end ->
            val target = doc.cursorLine
            if (target < 0 || end <= 0) return@collect
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (item != null && item.offset + item.size > end) listState.scrollBy((item.offset + item.size - end + 48).toFloat())
        }
    }

    val commandKeys = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            e.key == Key.Tab && !e.isCtrlPressed -> { if (e.isShiftPressed) onShiftTab() else onTab(); true }
            e.isCtrlPressed && e.key == Key.Slash -> { onToggleComment(); true }
            e.isCtrlPressed && e.key == Key.D -> { onDuplicateLine(); true }
            e.isCtrlPressed && e.key == Key.Z -> { if (e.isShiftPressed) onRedo() else onUndo(); true }
            e.isCtrlPressed && e.key == Key.Y -> { onRedo(); true }
            e.isCtrlPressed && e.key == Key.F -> { onFind(false); true }
            e.isCtrlPressed && e.key == Key.H -> { onFind(true); true }
            e.isCtrlPressed && e.key == Key.S -> { onSave(); true }
            e.key == Key.DirectionUp && e.nativeKeyEvent.isAltPressed -> { onMoveLine(true); true }
            e.key == Key.DirectionDown && e.nativeKeyEvent.isAltPressed -> { onMoveLine(false); true }
            e.key == Key.DirectionUp -> { onMoveCaret(-1); true }
            e.key == Key.DirectionDown -> { onMoveCaret(1); true }
            e.key == Key.PageUp -> { onMoveCaret(-30); true }
            e.key == Key.PageDown -> { onMoveCaret(30); true }
            else -> false
        }
    }

    val list: @Composable (Modifier) -> Unit = { m ->
        LazyColumn(state = listState, modifier = m.then(commandKeys)) {
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                LineRow(
                    doc = doc,
                    index = index,
                    line = line,
                    language = doc.language,
                    style = style,
                    numberStyle = numberStyle,
                    activeColor = activeColor,
                    gutterWidth = gutterWidth,
                    gutterBackground = gutterBackground,
                    currentLineColor = currentLineColor,
                    cursorBrush = cursorBrush,
                    colors = colors,
                    wordWrap = wordWrap,
                    onLineChange = onLineChange,
                    onJoinPrevious = onJoinPrevious,
                    onJoinNext = onJoinNext,
                )
            }
            item("tail") { Spacer(Modifier.height(240.dp)) }
        }
    }

    if (contentWidth == null) {
        list(modifier.fillMaxSize())
    } else {
        Box(modifier.fillMaxSize().horizontalScroll(horizontal)) {
            list(Modifier.width(contentWidth).fillMaxHeight())
        }
    }
}

@Composable
private fun LineRow(
    doc: OpenDocument,
    index: Int,
    line: String,
    language: Language,
    style: TextStyle,
    numberStyle: TextStyle,
    activeColor: Color,
    gutterWidth: Dp,
    gutterBackground: Color,
    currentLineColor: Color,
    cursorBrush: SolidColor,
    colors: HighlightColors,
    wordWrap: Boolean,
    onLineChange: (Int, TextFieldValue) -> Unit,
    onJoinPrevious: (Int) -> Unit,
    onJoinNext: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(line)) }
    val focusRequester = remember { FocusRequester() }
    val current = doc.cursorLine == index

    // External changes (undo, replace all, a split that rewrote this line) flow in here.
    if (value.text != line) {
        value = value.copy(text = line, selection = TextRange(value.selection.start.coerceAtMost(line.length), value.selection.end.coerceAtMost(line.length)))
    }

    LaunchedEffect(doc.focusRequest) {
        if (doc.focusLine == index && doc.focusRequest != doc.focusHandled) {
            doc.focusHandled = doc.focusRequest
            val len = value.text.length
            value = value.copy(selection = TextRange(doc.focusSelection.start.coerceIn(0, len), doc.focusSelection.end.coerceIn(0, len)))
            // The find bar keeps focus while it is open; the match is highlighted below instead.
            if (!doc.find.visible) runCatching { focusRequester.requestFocus() }
        }
    }

    val tokens = remember(line, language) { if (line.length > 4_000) emptyList() else SyntaxHighlighter.tokenize(line, language) }
    val findMatch = if (doc.find.visible && doc.find.query.isNotEmpty() && doc.focusLine == index && !doc.focusSelection.collapsed) {
        listOf(doc.focusSelection.min until doc.focusSelection.max)
    } else emptyList()
    val currentMatchColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
    val transformation = remember(tokens, colors, findMatch) {
        if (tokens.isEmpty() && findMatch.isEmpty()) VisualTransformation.None else HighlightTransformation(tokens, colors, findMatch, 0, currentMatchColor, currentMatchColor)
    }

    Row(Modifier.fillMaxWidth().background(if (current) currentLineColor else Color.Transparent), verticalAlignment = Alignment.Top) {
        Text(
            (index + 1).toString(),
            style = if (current) numberStyle.copy(color = activeColor) else numberStyle,
            modifier = Modifier.width(gutterWidth).background(gutterBackground).padding(end = 8.dp, top = 2.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = { new ->
                value = new
                onLineChange(index, new)
            },
            textStyle = style,
            readOnly = doc.readOnly,
            visualTransformation = transformation,
            cursorBrush = cursorBrush,
            singleLine = false,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.None,
            ),
            modifier = Modifier
                .then(if (wordWrap) Modifier.weight(1f) else Modifier.weight(1f))
                .padding(start = 8.dp, end = 16.dp, top = 2.dp, bottom = 2.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused && doc.cursorLine != index) onLineChange(index, value) }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.Backspace && value.selection.collapsed && value.selection.start == 0 && index > 0 -> { onJoinPrevious(index); true }
                        e.key == Key.Delete && value.selection.collapsed && value.selection.start == value.text.length -> { onJoinNext(index); true }
                        else -> false
                    }
                }
                .semantics { contentDescription = "Line ${index + 1}" },
        )
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
