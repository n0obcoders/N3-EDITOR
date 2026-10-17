package com.nitinshukla.notebook.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.nitinshukla.notebook.MainActivity
import com.nitinshukla.notebook.core.model.NoteSummary
import com.nitinshukla.notebook.core.repository.NoteRepository
import com.nitinshukla.notebook.core.repository.SettingsRepository
import com.nitinshukla.notebook.core.search.NoteSearch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun notes(): NoteRepository
    fun settings(): SettingsRepository
}

private fun entry(context: Context) = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

private fun openNoteIntent(context: Context, id: Long) =
    Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_NOTE).putExtra(MainActivity.EXTRA_NOTE_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun actionIntent(context: Context, action: String) =
    Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Buttons for a new text note, a new checklist and a new code note. */
class QuickNoteWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp)) {
                    Text("N3-Core", style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
                    Spacer(GlanceModifier.height(8.dp))
                    WidgetButton("＋ New note", actionIntent(context, MainActivity.ACTION_NEW_NOTE))
                    Spacer(GlanceModifier.height(6.dp))
                    WidgetButton("☑ New checklist", actionIntent(context, MainActivity.ACTION_NEW_CHECKLIST))
                    Spacer(GlanceModifier.height(6.dp))
                    WidgetButton("</> New code note", actionIntent(context, MainActivity.ACTION_NEW_CODE_NOTE))
                }
            }
        }
    }
}

/** Pinned notes; locked notes show only their title, honoring the privacy setting. */
class PinnedNotesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val e = entry(context)
        val prefs = e.settings().settings.first()
        val all = e.notes().observeSummaries().first()
        val pinned = NoteSearch.sort(all.filter { it.isPinned && !it.isTrashed && !it.isArchived }, prefs.sortOrder).take(6)
        val appLocked = prefs.appLockEnabled
        provideContent {
            GlanceTheme {
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp)) {
                    Text("Pinned", style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
                    if (pinned.isEmpty()) Text("Pin a note to see it here", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
                    pinned.forEach { d -> NoteRow(context, d, hideContent = appLocked || d.isLocked) }
                }
            }
        }
    }
}

/** The most recently edited checklist with its first items. */
class ChecklistWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val e = entry(context)
        val all = e.notes().observeSummaries().first()
        val appLocked = e.settings().settings.first().appLockEnabled
        val list = all.filter { !it.isTrashed && !it.isArchived && it.checklistTotal > 0 && !it.isLocked }.maxByOrNull { it.updatedAt }
        provideContent {
            GlanceTheme {
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp).let { m -> if (list != null) m.clickable(actionStartActivity(openNoteIntent(context, list.id))) else m }) {
                    Text(list?.let { NoteSearch.displayTitle(it).ifBlank { "Checklist" } } ?: "Checklist", style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
                    when {
                        list == null -> Text("No checklists yet", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
                        appLocked -> Text("Unlock Notebook to view", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
                        else -> list.checklistItems.forEach { (text, checked) ->
                            Text((if (checked) "☑ " else "☐ ") + text, style = TextStyle(color = GlanceTheme.colors.onSurface), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetButton(label: String, intent: Intent) {
    Row(GlanceModifier.fillMaxWidth().background(GlanceTheme.colors.primaryContainer).cornerRadius(12.dp).padding(10.dp).clickable(actionStartActivity(intent)), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun NoteRow(context: Context, d: NoteSummary, hideContent: Boolean) {
    Column(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(actionStartActivity(openNoteIntent(context, d.id)))) {
        Text(NoteSearch.displayTitle(d).ifBlank { "Untitled" }, style = TextStyle(fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface), maxLines = 1)
        if (!hideContent && d.preview.isNotBlank()) {
            Text(d.preview.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
    }
}

class QuickNoteWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = QuickNoteWidget() }
class PinnedNotesWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = PinnedNotesWidget() }
class ChecklistWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = ChecklistWidget() }

/** Refreshes data-bearing widgets; called after note changes from the app. */
object WidgetUpdater {
    suspend fun updateAll(context: Context) {
        PinnedNotesWidget().updateAll(context)
        ChecklistWidget().updateAll(context)
    }
}
