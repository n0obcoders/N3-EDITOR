package com.nitinshukla.notebook.ui.drawing

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nitinshukla.notebook.R
import com.nitinshukla.notebook.core.model.Attachment
import com.nitinshukla.notebook.core.model.AttachmentType
import com.nitinshukla.notebook.core.repository.NoteRepository
import com.nitinshukla.notebook.data.files.AttachmentStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class StrokeData(val points: List<Offset>, val color: Color, val width: Float, val eraser: Boolean)

@HiltViewModel
class DrawingViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val notes: NoteRepository,
    private val store: AttachmentStore,
) : ViewModel() {
    val noteId: Long = savedState.get<Long>("noteId") ?: 0L
    val strokes = mutableStateListOf<StrokeData>()
    private val redoStack = ArrayDeque<StrokeData>()
    var canRedo by mutableStateOf(false)
        private set

    fun add(stroke: StrokeData) {
        strokes += stroke
        redoStack.clear()
        canRedo = false
    }

    fun undo() {
        strokes.removeLastOrNull()?.let { redoStack.addLast(it); canRedo = true }
    }

    fun redo() {
        redoStack.removeLastOrNull()?.let { strokes += it }
        canRedo = redoStack.isNotEmpty()
    }

    fun clear() {
        strokes.clear(); redoStack.clear(); canRedo = false
    }

    /** Rasterizes the strokes to a PNG in private storage and attaches it to the note. */
    fun save(size: IntSize, background: Color, onDone: () -> Unit) = viewModelScope.launch {
        if (strokes.isEmpty() || size.width == 0 || size.height == 0) { onDone(); return@launch }
        val name = store.newFileName("png")
        val file = store.file(name)
        withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(background.toArgb())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            strokes.forEach { s ->
                paint.color = if (s.eraser) background.toArgb() else s.color.toArgb()
                paint.strokeWidth = s.width
                val path = android.graphics.Path()
                s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                if (s.points.size == 1) canvas.drawPoint(s.points[0].x, s.points[0].y, paint) else canvas.drawPath(path, paint)
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        notes.addAttachment(Attachment(noteId = noteId, type = AttachmentType.DRAWING, fileName = name, mimeType = "image/png", displayName = "Drawing", sizeBytes = file.length()))
        onDone()
    }
}

private val palette = listOf(Color.Black, Color(0xFFD32F2F), Color(0xFF1976D2), Color(0xFF388E3C), Color(0xFFFBC02D), Color(0xFF7B1FA2), Color(0xFFF57C00), Color.White)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawingScreen(onBack: () -> Unit, viewModel: DrawingViewModel = hiltViewModel()) {
    var color by remember { mutableStateOf(Color.Black) }
    var width by remember { mutableFloatStateOf(8f) }
    var eraser by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val background = Color.White

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.draw)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(onClick = viewModel::undo, enabled = viewModel.strokes.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                IconButton(onClick = viewModel::redo, enabled = viewModel.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                IconButton(onClick = viewModel::clear) { Icon(Icons.Default.Delete, stringResource(R.string.clear_canvas)) }
                IconButton(onClick = { viewModel.save(canvasSize, background, onBack) }) { Icon(Icons.Default.Save, stringResource(R.string.save_drawing)) }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(background)
                    .onSizeChanged { canvasSize = it }
                    .semantics { contentDescription = "Drawing canvas" }
                    .pointerInput(color, width, eraser) {
                        detectDragGestures(
                            onDragStart = { current = listOf(it) },
                            onDrag = { change, _ -> current = current + change.position },
                            onDragEnd = { viewModel.add(StrokeData(current, color, width, eraser)); current = emptyList() },
                            onDragCancel = { current = emptyList() },
                        )
                    },
            ) {
                fun draw(s: StrokeData) {
                    val path = Path()
                    s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                    drawPath(path, if (s.eraser) background else s.color, style = Stroke(width = s.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                viewModel.strokes.forEach { draw(it) }
                if (current.isNotEmpty()) draw(StrokeData(current, color, width, eraser))
            }
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { eraser = !eraser }) { Icon(if (eraser) Icons.Default.Check else Icons.Default.Brush, stringResource(if (eraser) R.string.eraser else R.string.pen)) }
                        Text(stringResource(if (eraser) R.string.eraser else R.string.pen), style = MaterialTheme.typography.labelSmall)
                        palette.forEach { c ->
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(c)
                                    .then(if (c == color && !eraser) Modifier.padding(0.dp) else Modifier)
                                    .semantics { contentDescription = "Color" }
                                    .pointerInput(c) { detectTapGesturesCompat { color = c; eraser = false } },
                            ) {
                                if (c == color && !eraser) Icon(Icons.Default.Check, null, tint = if (c == Color.White || c == Color.Yellow) Color.Black else Color.White, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.stroke_size), style = MaterialTheme.typography.labelSmall)
                        Slider(value = width, onValueChange = { width = it }, valueRange = 2f..48f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    }
                }
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesCompat(onTap: () -> Unit) =
    detectTapGestures(onTap = { onTap() })
