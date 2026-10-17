package com.nitinshukla.notebook.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nitinshukla.notebook.BuildConfig
import com.nitinshukla.notebook.R
import com.nitinshukla.notebook.core.backup.DuplicateStrategy
import com.nitinshukla.notebook.core.model.NoteColor
import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.search.SortOrder
import com.nitinshukla.notebook.core.settings.AppSettings
import com.nitinshukla.notebook.core.settings.Density
import com.nitinshukla.notebook.core.settings.EditorFont
import com.nitinshukla.notebook.core.settings.NoteLayout
import com.nitinshukla.notebook.core.settings.ThemeMode
import com.nitinshukla.notebook.security.AppLockManager
import com.nitinshukla.notebook.ui.common.ChoiceDialog
import com.nitinshukla.notebook.ui.common.ColorSwatchRow
import com.nitinshukla.notebook.ui.common.SectionHeader
import com.nitinshukla.notebook.ui.common.formatBytes
import com.nitinshukla.notebook.ui.common.formatDate
import com.nitinshukla.notebook.ui.editor.noteTypeLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit = {}, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var dialog by remember { mutableStateOf<String?>(null) }

    val createJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { u -> viewModel.createBackup(u, zip = false) } }
    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let { u -> viewModel.createBackup(u, zip = true) } }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::inspectBackup) }
    val importText = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) viewModel.importTextFiles(it) }

    @Composable
    fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = subtitle?.let { { Text(it) } },
            trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
            modifier = Modifier.clickable { onChange(!checked) },
        )
    }

    @Composable
    fun ActionRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
        ListItem(headlineContent = { Text(title) }, supportingContent = subtitle?.let { { Text(it) } }, modifier = Modifier.clickable(onClick = onClick))
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.settings_appearance))
            ActionRow(stringResource(R.string.theme), themeLabel(context, s.themeMode)) { dialog = "theme" }
            ToggleRow(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_hint), s.dynamicColor) { v -> viewModel.update { it.copy(dynamicColor = v) } }
            ActionRow(stringResource(R.string.layout), if (s.layout == NoteLayout.GRID) stringResource(R.string.grid_view) else stringResource(R.string.list_view)) { viewModel.update { it.copy(layout = if (it.layout == NoteLayout.GRID) NoteLayout.LIST else NoteLayout.GRID) } }
            ActionRow(stringResource(R.string.density), if (s.density == Density.COMPACT) stringResource(R.string.compact) else stringResource(R.string.comfortable)) { viewModel.update { it.copy(density = if (it.density == Density.COMPACT) Density.COMFORTABLE else Density.COMPACT) } }
            ActionRow(stringResource(R.string.default_sort), s.sortOrder.displayName) { dialog = "sort" }
            ActionRow(stringResource(R.string.default_note_type), stringResource(noteTypeLabel(s.defaultNoteType))) { dialog = "type" }
            // Not a ListItem: ListItem measures its slots' intrinsic height, which a lazy row cannot answer (crash).
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.default_note_color), style = MaterialTheme.typography.bodyLarge)
                ColorSwatchRow(selected = s.defaultNoteColor, onSelect = { c -> viewModel.update { it.copy(defaultNoteColor = c) } }, modifier = Modifier.padding(top = 8.dp))
            }
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_editor))
            ListItem(headlineContent = { Text(stringResource(R.string.font_size_value, s.editorFontSizeSp)) }, supportingContent = {
                Slider(value = s.editorFontSizeSp.toFloat(), onValueChange = { v -> viewModel.update { it.copy(editorFontSizeSp = v.toInt()) } }, valueRange = AppSettings.FONT_SIZES.first.toFloat()..AppSettings.FONT_SIZES.last.toFloat(), steps = (AppSettings.FONT_SIZES.last - AppSettings.FONT_SIZES.first) / 2 - 1)
            })
            ActionRow(stringResource(R.string.font_family), s.editorFont.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')) { dialog = "font" }
            ToggleRow(stringResource(R.string.word_wrap), null, s.wordWrap) { v -> viewModel.update { it.copy(wordWrap = v) } }
            ToggleRow(stringResource(R.string.line_numbers), null, s.showLineNumbers) { v -> viewModel.update { it.copy(showLineNumbers = v) } }
            ToggleRow(stringResource(R.string.status_bar), stringResource(R.string.status_bar_hint), s.showStatusBar) { v -> viewModel.update { it.copy(showStatusBar = v) } }
            ToggleRow(stringResource(R.string.completed_to_bottom), stringResource(R.string.completed_to_bottom_hint), s.moveCheckedToBottom) { v -> viewModel.update { it.copy(moveCheckedToBottom = v) } }
            ToggleRow(stringResource(R.string.link_previews), stringResource(R.string.link_previews_hint), s.linkPreviewsEnabled) { v -> viewModel.update { it.copy(linkPreviewsEnabled = v) } }
            ActionRow(stringResource(R.string.autosave_delay), stringResource(R.string.autosave_delay_value, s.autosaveDelayMs)) { dialog = "autosave" }
            ListItem(headlineContent = { Text(stringResource(R.string.code_font_size_value, s.codeFontSizeSp)) }, supportingContent = {
                Slider(value = s.codeFontSizeSp.toFloat(), onValueChange = { v -> viewModel.update { it.copy(codeFontSizeSp = v.toInt()) } }, valueRange = AppSettings.FONT_SIZES.first.toFloat()..AppSettings.FONT_SIZES.last.toFloat(), steps = (AppSettings.FONT_SIZES.last - AppSettings.FONT_SIZES.first) / 2 - 1)
            })
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_privacy))
            ToggleRow(stringResource(R.string.app_lock), stringResource(R.string.app_lock_hint), s.appLockEnabled) { v ->
                val activity = context as? FragmentActivity
                if (v && (activity == null || !AppLockManager.canAuthenticate(activity))) {
                    Toast.makeText(context, R.string.no_device_lock, Toast.LENGTH_LONG).show()
                } else if (activity != null) {
                    scope.launch { if (AppLockManager.authenticate(activity, context.getString(R.string.confirm_identity))) viewModel.update { it.copy(appLockEnabled = v) } }
                }
            }
            ActionRow(stringResource(R.string.auto_lock), if (s.autoLockMinutes == 0) stringResource(R.string.immediately) else stringResource(R.string.minutes_value, s.autoLockMinutes)) { dialog = "autolock" }
            ToggleRow(stringResource(R.string.secure_screen), stringResource(R.string.secure_screen_hint), s.secureScreen) { v -> viewModel.update { it.copy(secureScreen = v) } }
            ListItem(headlineContent = { Text(stringResource(R.string.lock_vs_encryption_title)) }, supportingContent = { Text(stringResource(R.string.lock_vs_encryption)) })
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_history))
            ToggleRow(stringResource(R.string.keep_revisions), stringResource(R.string.keep_revisions_hint), s.revisionsEnabled) { v -> viewModel.update { it.copy(revisionsEnabled = v) } }
            ActionRow(stringResource(R.string.revision_interval), stringResource(R.string.minutes_value, s.revisionIntervalMinutes)) { dialog = "revinterval" }
            ActionRow(stringResource(R.string.revisions_per_note), s.maxRevisionsPerNote.toString()) { dialog = "revcount" }
            ActionRow(stringResource(R.string.revision_storage), "${s.revisionStorageLimitMb} MB") { dialog = "revstorage" }
            ActionRow(stringResource(R.string.trash_retention), if (s.trashRetentionDays == 0) stringResource(R.string.never) else stringResource(R.string.days_value, s.trashRetentionDays)) { dialog = "trash" }
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_backup))
            ActionRow(stringResource(R.string.backup_json), stringResource(R.string.backup_json_hint)) { createJson.launch(viewModel.suggestedBackupName(false)) }
            ActionRow(stringResource(R.string.backup_zip), stringResource(R.string.backup_zip_hint)) { createZip.launch(viewModel.suggestedBackupName(true)) }
            ActionRow(stringResource(R.string.restore_backup), stringResource(R.string.restore_backup_hint)) { openBackup.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "*/*")) }
            ActionRow(stringResource(R.string.import_text_files), stringResource(R.string.import_text_files_hint)) { importText.launch(arrayOf("text/*", "application/json")) }
            ListItem(headlineContent = { Text(stringResource(R.string.backup_warning_title)) }, supportingContent = { Text(stringResource(R.string.backup_warning)) })
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_storage))
            ListItem(headlineContent = { Text(stringResource(R.string.storage_usage)) }, supportingContent = {
                Text(stringResource(R.string.storage_breakdown, formatBytes(storage.total), formatBytes(storage.textBytes), formatBytes(storage.attachmentBytes), formatBytes(storage.revisionBytes)))
            }, modifier = Modifier.clickable { viewModel.refreshStorage() })
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_about))
            ListItem(headlineContent = { Text(stringResource(R.string.app_version)) }, supportingContent = { Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") })
            ActionRow(stringResource(R.string.about_title), stringResource(R.string.about_name)) { onAbout() }
            ActionRow(stringResource(R.string.github_repo), stringResource(R.string.github_repo_hint)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.repo_url)))) }
            ActionRow(stringResource(R.string.send_feedback), null) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.repo_url) + "/issues"))) }
            ActionRow(stringResource(R.string.open_source_licenses), null) { dialog = "licenses" }
            ActionRow(stringResource(R.string.privacy_policy), null) { dialog = "privacy" }
        }
    }

    when (dialog) {
        "theme" -> ChoiceDialog(stringResource(R.string.theme), ThemeMode.entries, s.themeMode, { themeLabel(context, it) }, { v -> viewModel.update { it.copy(themeMode = v) } }) { dialog = null }
        "sort" -> ChoiceDialog(stringResource(R.string.default_sort), SortOrder.entries, s.sortOrder, { it.displayName }, { v -> viewModel.update { it.copy(sortOrder = v) } }) { dialog = null }
        "type" -> ChoiceDialog(stringResource(R.string.default_note_type), listOf(NoteType.PLAIN, NoteType.RICH, NoteType.CHECKLIST, NoteType.CODE), s.defaultNoteType, { context.getString(noteTypeLabel(it)) }, { v -> viewModel.update { it.copy(defaultNoteType = v) } }) { dialog = null }
        "font" -> ChoiceDialog(stringResource(R.string.font_family), EditorFont.entries, s.editorFont, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() }.replace('_', ' ') }, { v -> viewModel.update { it.copy(editorFont = v) } }) { dialog = null }
        "autosave" -> ChoiceDialog(stringResource(R.string.autosave_delay), AppSettings.AUTOSAVE_DELAY_CHOICES, s.autosaveDelayMs, { context.getString(R.string.autosave_delay_value, it) }, { v -> viewModel.update { it.copy(autosaveDelayMs = v) } }) { dialog = null }
        "autolock" -> ChoiceDialog(stringResource(R.string.auto_lock), AppSettings.AUTO_LOCK_CHOICES, s.autoLockMinutes, { if (it == 0) context.getString(R.string.immediately) else context.getString(R.string.minutes_value, it) }, { v -> viewModel.update { it.copy(autoLockMinutes = v) } }) { dialog = null }
        "revinterval" -> ChoiceDialog(stringResource(R.string.revision_interval), listOf(1, 5, 10, 30, 60), s.revisionIntervalMinutes, { context.getString(R.string.minutes_value, it) }, { v -> viewModel.update { it.copy(revisionIntervalMinutes = v) } }) { dialog = null }
        "revcount" -> ChoiceDialog(stringResource(R.string.revisions_per_note), listOf(5, 10, 20, 50, 100), s.maxRevisionsPerNote, { it.toString() }, { v -> viewModel.update { it.copy(maxRevisionsPerNote = v) } }) { dialog = null }
        "revstorage" -> ChoiceDialog(stringResource(R.string.revision_storage), listOf(5, 20, 50, 100), s.revisionStorageLimitMb, { "$it MB" }, { v -> viewModel.update { it.copy(revisionStorageLimitMb = v) } }) { dialog = null }
        "trash" -> ChoiceDialog(stringResource(R.string.trash_retention), AppSettings.TRASH_RETENTION_CHOICES, s.trashRetentionDays, { if (it == 0) context.getString(R.string.never) else context.getString(R.string.days_value, it) }, { v -> viewModel.update { it.copy(trashRetentionDays = v) } }) { dialog = null }
        "licenses" -> TextDialog(stringResource(R.string.open_source_licenses), stringResource(R.string.licenses_text)) { dialog = null }
        "privacy" -> TextDialog(stringResource(R.string.privacy_policy), stringResource(R.string.privacy_text)) { dialog = null }
    }

    when (val b = backup) {
        BackupUiState.Idle -> Unit
        BackupUiState.Working -> AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { CircularProgressIndicator() })
        is BackupUiState.Message -> AlertDialog(onDismissRequest = viewModel::dismissBackupMessage, title = { Text(stringResource(if (b.isError) R.string.error else R.string.done)) }, text = { Text(b.text) }, confirmButton = { TextButton(onClick = viewModel::dismissBackupMessage) { Text(stringResource(android.R.string.ok)) } })
        is BackupUiState.ConfirmRestore -> {
            var strategy by remember { mutableStateOf(DuplicateStrategy.SKIP) }
            AlertDialog(
                onDismissRequest = viewModel::dismissBackupMessage,
                title = { Text(stringResource(R.string.restore_backup)) },
                text = {
                    Column {
                        Text(stringResource(R.string.restore_summary, b.info.noteCount, formatDate(b.info.createdAt), b.info.appVersion.ifBlank { "?" }))
                        if (b.info.formatVersion < 2) Text(stringResource(R.string.restore_legacy_labels), style = MaterialTheme.typography.labelSmall)
                        if (!b.info.hasAttachments) Text(stringResource(R.string.restore_no_attachments), style = MaterialTheme.typography.labelSmall)
                        Text(stringResource(R.string.duplicates), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
                        DuplicateStrategy.entries.forEach { st ->
                            ListItem(
                                headlineContent = { Text(stringResource(when (st) { DuplicateStrategy.SKIP -> R.string.dup_skip; DuplicateStrategy.KEEP_BOTH -> R.string.dup_keep_both; DuplicateStrategy.REPLACE -> R.string.dup_replace })) },
                                leadingContent = { androidx.compose.material3.RadioButton(selected = strategy == st, onClick = { strategy = st }) },
                                modifier = Modifier.clickable { strategy = st },
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { viewModel.restore(b.uri, strategy) }) { Text(stringResource(R.string.restore)) } },
                dismissButton = { TextButton(onClick = viewModel::dismissBackupMessage) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun TextDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body, modifier = Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } })
}

private fun themeLabel(context: android.content.Context, mode: ThemeMode): String = context.getString(when (mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })
