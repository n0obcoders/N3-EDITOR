package com.nitinshukla.notebook.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nitinshukla.notebook.R
import com.nitinshukla.notebook.core.model.NoteSummary
import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.search.NoteSearch
import com.nitinshukla.notebook.core.settings.Density
import com.nitinshukla.notebook.core.text.RichText
import com.nitinshukla.notebook.ui.common.formatDateOnly
import com.nitinshukla.notebook.ui.theme.contentColor
import com.nitinshukla.notebook.ui.theme.toComposeColor

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: NoteSummary,
    density: Density,
    selected: Boolean,
    selectionMode: Boolean,
    inTrash: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
) {
    val bg = note.color.toComposeColor()
    val fg = note.color.contentColor()
    val compact = density == Density.COMPACT
    val title = NoteSearch.displayTitle(note)
    val titleIsFromContent = note.title.isBlank()
    val lockedPreview = stringResource(R.string.locked_note_preview)
    // Derived once per summary instance; the summary is a data class so recomposition with
    // the same row skips this.
    val preview = remember(note, lockedPreview) {
        when {
            note.isLocked -> lockedPreview
            note.type == NoteType.CHECKLIST -> ""
            note.type == NoteType.RICH -> RichText.toPlainText(note.preview)
            titleIsFromContent -> note.preview.lineSequence().dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()
            else -> note.preview
        }
    }
    val checklist = remember(note) { if (note.isLocked) emptyList() else note.checklistItems }
    val description = stringResource(R.string.note_card_description, title.ifBlank { stringResource(R.string.untitled_note) })

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.selected = selected; contentDescription = description }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(if (compact) 10.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (title.isNotBlank()) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (note.isLocked) Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.locked), modifier = Modifier.size(18.dp))
                if (note.isPinned) Icon(Icons.Default.PushPin, contentDescription = stringResource(R.string.pinned), modifier = Modifier.size(18.dp))
            }
            if (preview.isNotBlank()) {
                if (title.isNotBlank()) Spacer(Modifier.height(4.dp))
                Text(
                    preview,
                    style = if (note.type.isMonospaced) MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
                    maxLines = if (compact) 3 else 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (checklist.isNotEmpty() && note.checklistTotal > 0) {
                Spacer(Modifier.height(6.dp))
                checklist.take(if (compact) 3 else 5).forEach { (text, checked) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (note.checklistTotal > 5) Text(stringResource(R.string.more_items, note.checklistTotal - 5), style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(progress = { note.checklistDone.toFloat() / note.checklistTotal }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            if (note.hasAttachments || note.fileName != null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (note.hasAttachments) {
                        AssistChip(onClick = onClick, label = { Text(note.attachmentCount.toString(), style = MaterialTheme.typography.labelSmall) }, leadingIcon = { Icon(Icons.Outlined.AttachFile, stringResource(R.string.attachments), Modifier.size(14.dp)) })
                    }
                    note.fileName?.let { name ->
                        AssistChip(onClick = onClick, label = { Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDateOnly(note.updatedAt), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                if (!selectionMode) {
                    IconButton(onClick = onArchive, modifier = Modifier.size(36.dp)) {
                        Icon(if (inTrash) Icons.Default.Delete else Icons.Default.Archive, contentDescription = stringResource(if (inTrash) R.string.restore else if (note.isArchived) R.string.unarchive else R.string.archive), Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(if (inTrash) R.string.delete_forever else R.string.move_to_trash), Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
