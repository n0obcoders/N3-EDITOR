package com.nitinshukla.notebook.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nitinshukla.notebook.core.code.Token
import com.nitinshukla.notebook.core.code.TokenType

/** Colours for syntax tokens, VS Code Light+ / Dark+ inspired, chosen for 4.5:1 contrast. */
class HighlightColors(private val dark: Boolean) {
    fun styleFor(type: TokenType): SpanStyle? = when (type) {
        TokenType.KEYWORD -> SpanStyle(color = if (dark) Color(0xFF569CD6) else Color(0xFF0000FF))
        TokenType.TYPE -> SpanStyle(color = if (dark) Color(0xFF4EC9B0) else Color(0xFF267F99))
        TokenType.BUILTIN -> SpanStyle(color = if (dark) Color(0xFFDCDCAA) else Color(0xFF795E26))
        TokenType.STRING, TokenType.CHAR -> SpanStyle(color = if (dark) Color(0xFFCE9178) else Color(0xFFA31515))
        TokenType.COMMENT -> SpanStyle(color = if (dark) Color(0xFF6A9955) else Color(0xFF008000), fontStyle = FontStyle.Italic)
        TokenType.NUMBER, TokenType.CONSTANT -> SpanStyle(color = if (dark) Color(0xFFB5CEA8) else Color(0xFF098658))
        TokenType.FUNCTION -> SpanStyle(color = if (dark) Color(0xFFDCDCAA) else Color(0xFF795E26))
        TokenType.OPERATOR -> SpanStyle(color = if (dark) Color(0xFFD4D4D4) else Color(0xFF000000))
        TokenType.PUNCTUATION -> null
        TokenType.TAG -> SpanStyle(color = if (dark) Color(0xFF569CD6) else Color(0xFF800000))
        TokenType.ATTRIBUTE -> SpanStyle(color = if (dark) Color(0xFF9CDCFE) else Color(0xFFE50000))
        TokenType.DECORATOR -> SpanStyle(color = if (dark) Color(0xFFC586C0) else Color(0xFFAF00DB))
        TokenType.VARIABLE -> SpanStyle(color = if (dark) Color(0xFF9CDCFE) else Color(0xFF001080))
        TokenType.HEADING -> SpanStyle(color = if (dark) Color(0xFF569CD6) else Color(0xFF0000FF), fontWeight = FontWeight.Bold)
        TokenType.LINK -> SpanStyle(color = if (dark) Color(0xFF4FC1FF) else Color(0xFF0066CC))
        TokenType.ERROR -> SpanStyle(color = if (dark) Color(0xFFF44747) else Color(0xFFCD3131), fontWeight = FontWeight.Bold)
        TokenType.PLAIN -> null
    }
}

/**
 * Applies token spans to the text the field draws. Tokens are computed off the main thread
 * and can lag the text by a keystroke; out-of-range spans are clamped, and the caller only
 * passes tokens that belong to the current revision.
 */
internal class HighlightTransformation(
    private val tokens: List<Token>,
    private val colors: HighlightColors?,
    /** Find matches (end inclusive) drawn with a background so they show without focus. */
    private val matches: List<IntRange> = emptyList(),
    private val currentMatch: Int = -1,
    private val matchColor: Color = Color.Unspecified,
    private val currentMatchColor: Color = Color.Unspecified,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (tokens.isEmpty() && matches.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val length = text.length
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>(tokens.size + minOf(matches.size, MAX_MATCH_SPANS))
        if (colors != null) for (t in tokens) {
            val style = colors.styleFor(t.type) ?: continue
            val start = t.start.coerceIn(0, length)
            val end = t.end.coerceIn(start, length)
            if (end > start) spans += AnnotatedString.Range(style, start, end)
        }
        // A very common term can match tens of thousands of times; span the ones around the
        // current match so drawing stays cheap.
        val from = if (matches.size <= MAX_MATCH_SPANS) 0 else (currentMatch - MAX_MATCH_SPANS / 2).coerceIn(0, matches.size - MAX_MATCH_SPANS)
        val to = minOf(matches.size, from + MAX_MATCH_SPANS)
        for (i in from until to) {
            val m = matches[i]
            val start = m.first.coerceIn(0, length)
            val end = (m.last + 1).coerceIn(start, length)
            if (end > start) spans += AnnotatedString.Range(SpanStyle(background = if (i == currentMatch) currentMatchColor else matchColor), start, end)
        }
        return TransformedText(AnnotatedString(text.text, spans), OffsetMapping.Identity)
    }

    private companion object {
        const val MAX_MATCH_SPANS = 2_000
    }
}

/**
 * The shared editor surface: monospace text with syntax colours, a gutter with line numbers
 * drawn only for the lines on screen, the current line tinted, optional wrapping, and
 * hardware shortcuts (Tab / Shift+Tab, Ctrl+/ comment, Ctrl+D duplicate, Ctrl+Z/Y, Ctrl+F/H,
 * Ctrl+S, Alt+Up/Down move line). Soft-keyboard Enter and brackets are handled by the view
 * model, which rewrites the inserted character into an indented or auto-closed edit.
 */
@Composable
fun CodeEditor(
    doc: OpenDocument,
    fontSizeSp: Int,
    wordWrap: Boolean,
    onChange: (TextFieldValue) -> Unit,
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
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val colors = remember(dark) { HighlightColors(dark) }
    val textColor = MaterialTheme.colorScheme.onSurface
    val style = TextStyle(fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.45).sp, fontFamily = FontFamily.Monospace, color = textColor)
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Every visible line number is measured per frame; the default 8-entry cache thrashed.
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    val gutterColor = MaterialTheme.colorScheme.outline
    val currentLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.07f else 0.05f)
    val activeNumberColor = MaterialTheme.colorScheme.onSurface
    val value = doc.text
    // Stale tokens are drawn until fresh ones arrive (spans are clamped). Dropping them on every
    // edit made each keystroke lay the whole file out twice and flicker the colours.
    val tokens = doc.tokens
    val find = doc.find
    val matches = if (find.visible && find.query.isNotEmpty()) find.matches else emptyList()
    val matchColor = MaterialTheme.colorScheme.tertiaryContainer
    val currentMatchColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
    val transformation = remember(tokens, colors, matches, find.current, matchColor) { HighlightTransformation(tokens, colors, matches, find.current, matchColor, currentMatchColor) }
    val lineStarts = doc.lineStarts
    val digits = maxOf(2, lineStarts.size.toString().length)
    val gutterWidth = with(density) { (fontSizeSp * 0.62f * digits).sp.toDp() } + 16.dp
    val numberStyle = style.copy(fontSize = (fontSizeSp - 2).sp, color = gutterColor, textAlign = TextAlign.End)

    LaunchedEffect(doc.revealSelection, doc.id) {
        if (doc.revealSelection == 0) return@LaunchedEffect
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
        // While the find bar is open it keeps the keyboard focus, so typing goes on refining
        // the search; matches are drawn by the transformation instead of the selection.
        if (!doc.find.visible) focusRequester.requestFocus()
    }

    // When the keyboard opens the viewport shrinks under the caret; scroll it back into view.
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { vertical.viewportSize }.collect { viewport ->
            val l = layout ?: return@collect
            if (viewport <= 0) return@collect
            val line = l.getLineForOffset(doc.text.selection.end.coerceIn(0, l.layoutInput.text.length))
            val top = l.getLineTop(line).toInt()
            val bottom = l.getLineBottom(line).toInt()
            if (bottom > vertical.value + viewport) vertical.scrollTo((bottom - viewport + 48).coerceIn(0, vertical.maxValue))
            else if (top < vertical.value) vertical.scrollTo(top.coerceIn(0, vertical.maxValue))
        }
    }

    val keyHandler = Modifier.onPreviewKeyEvent { e ->
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
            e.key == Key.DirectionUp && e.isAltPressedCompat() -> { onMoveLine(true); true }
            e.key == Key.DirectionDown && e.isAltPressedCompat() -> { onMoveLine(false); true }
            else -> false
        }
    }

    Row(modifier.verticalScroll(vertical)) {
        // Gutter: numbers are drawn, not composed, so a 10 000-line file costs nothing extra.
        Box(
            Modifier
                .width(gutterWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .drawBehind {
                    val l = layout ?: return@drawBehind
                    drawGutter(l, lineStarts, vertical.value, vertical.viewportSize, value.selection.start, measurer, numberStyle, activeNumberColor, currentLineColor, size.width)
                },
        )
        Box(
            Modifier
                .weight(1f)
                .then(if (wordWrap) Modifier else Modifier.horizontalScroll(horizontal))
                .drawBehind {
                    val l = layout ?: return@drawBehind
                    val line = l.getLineForOffset(value.selection.start.coerceIn(0, l.layoutInput.text.length))
                    val pad = 4.dp.toPx()
                    val top = l.getLineTop(line) + pad
                    drawRect(currentLineColor, Offset(0f, top), Size(maxOf(size.width, l.size.width.toFloat()), l.getLineBottom(line) + pad - top))
                },
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = style,
                readOnly = doc.readOnly,
                visualTransformation = transformation,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default,
                ),
                onTextLayout = { layout = it },
                modifier = Modifier
                    .then(if (wordWrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = 4000.dp))
                    .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 200.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .then(keyHandler)
                    .semantics { contentDescription = "Code editor, ${doc.name}" },
            )
        }
    }
}

private fun androidx.compose.ui.input.key.KeyEvent.isAltPressedCompat(): Boolean = nativeKeyEvent.isAltPressed

/** Offsets at which each logical line starts (index 0 is always 0). */
fun lineStartOffsets(text: String): IntArray {
    var count = 1
    for (c in text) if (c == '\n') count++
    val out = IntArray(count)
    var i = 1
    text.forEachIndexed { index, c -> if (c == '\n') out[i++] = index + 1 }
    return out
}

/** 1-based logical line number containing [offset]. */
fun lineNumberAt(lineStarts: IntArray, offset: Int): Int {
    var lo = 0
    var hi = lineStarts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
    }
    return lo + 1
}

private fun DrawScope.drawGutter(
    layout: TextLayoutResult,
    lineStarts: IntArray,
    scroll: Int,
    viewport: Int,
    cursor: Int,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    activeColor: Color,
    currentLineColor: Color,
    width: Float,
) {
    val textLength = layout.layoutInput.text.length
    val firstVisual = layout.getLineForVerticalPosition(scroll.toFloat()).coerceAtLeast(0)
    val lastVisual = if (viewport > 0) layout.getLineForVerticalPosition((scroll + viewport).toFloat()) else layout.lineCount - 1
    val cursorLine = lineNumberAt(lineStarts, cursor.coerceIn(0, textLength))
    val topPadding = 4.dp.toPx()
    for (visual in firstVisual..lastVisual.coerceAtMost(layout.lineCount - 1)) {
        val start = layout.getLineStart(visual)
        // Only the first visual line of a wrapped logical line carries a number.
        if (start != 0 && layout.layoutInput.text[start - 1] != '\n') continue
        val number = lineNumberAt(lineStarts, start)
        val top = layout.getLineTop(visual) + topPadding
        val bottom = layout.getLineBottom(visual) + topPadding
        if (number == cursorLine) drawRect(currentLineColor, Offset(0f, top), Size(width, bottom - top))
        val measured = measurer.measure(number.toString(), if (number == cursorLine) style.copy(color = activeColor) else style)
        drawText(measured, topLeft = Offset(width - measured.size.width - 8.dp.toPx(), top + (bottom - top - measured.size.height) / 2))
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
