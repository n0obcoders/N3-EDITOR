package com.nitinshukla.notebook.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nitinshukla.notebook.core.model.NoteColor
import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.repository.SettingsRepository
import com.nitinshukla.notebook.core.search.SortOrder
import com.nitinshukla.notebook.core.settings.AppSettings
import com.nitinshukla.notebook.core.settings.Density
import com.nitinshukla.notebook.core.settings.EditorFont
import com.nitinshukla.notebook.core.settings.NoteLayout
import com.nitinshukla.notebook.core.settings.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(@ApplicationContext context: Context) : SettingsRepository {
    private val store = context.settingsStore

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val defaultColor = stringPreferencesKey("default_color")
        val defaultType = stringPreferencesKey("default_type")
        val sort = stringPreferencesKey("sort")
        val layout = stringPreferencesKey("layout")
        val density = stringPreferencesKey("density")
        val fontSize = intPreferencesKey("font_size")
        val font = stringPreferencesKey("font")
        val wordWrap = booleanPreferencesKey("word_wrap")
        val lineNumbers = booleanPreferencesKey("line_numbers")
        val statusBar = booleanPreferencesKey("status_bar")
        val checkedToBottom = booleanPreferencesKey("checked_to_bottom")
        val autosaveDelay = intPreferencesKey("autosave_delay")
        val codeFontSize = intPreferencesKey("code_font_size")
        val appLock = booleanPreferencesKey("app_lock")
        val autoLock = intPreferencesKey("auto_lock")
        val secureScreen = booleanPreferencesKey("secure_screen")
        val revisions = booleanPreferencesKey("revisions")
        val revisionInterval = intPreferencesKey("revision_interval")
        val revisionsPerNote = intPreferencesKey("revisions_per_note")
        val revisionStorage = intPreferencesKey("revision_storage")
        val trashRetention = intPreferencesKey("trash_retention")
        val linkPreviews = booleanPreferencesKey("link_previews")
        val onboarding = booleanPreferencesKey("onboarding_n3core")
        val recentSearches = stringPreferencesKey("recent_searches")
    }

    override val settings: Flow<AppSettings> = store.data.map { settingsFrom(it) }.distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val current = settingsFrom(p)
            val s = transform(current)
            p[Keys.theme] = s.themeMode.name
            p[Keys.dynamicColor] = s.dynamicColor
            p[Keys.defaultColor] = s.defaultNoteColor.name
            p[Keys.defaultType] = s.defaultNoteType.name
            p[Keys.sort] = s.sortOrder.name
            p[Keys.layout] = s.layout.name
            p[Keys.density] = s.density.name
            p[Keys.fontSize] = s.editorFontSizeSp
            p[Keys.font] = s.editorFont.name
            p[Keys.wordWrap] = s.wordWrap
            p[Keys.lineNumbers] = s.showLineNumbers
            p[Keys.statusBar] = s.showStatusBar
            p[Keys.checkedToBottom] = s.moveCheckedToBottom
            p[Keys.autosaveDelay] = s.autosaveDelayMs
            p[Keys.codeFontSize] = s.codeFontSizeSp
            p[Keys.appLock] = s.appLockEnabled
            p[Keys.autoLock] = s.autoLockMinutes
            p[Keys.secureScreen] = s.secureScreen
            p[Keys.revisions] = s.revisionsEnabled
            p[Keys.revisionInterval] = s.revisionIntervalMinutes
            p[Keys.revisionsPerNote] = s.maxRevisionsPerNote
            p[Keys.revisionStorage] = s.revisionStorageLimitMb
            p[Keys.trashRetention] = s.trashRetentionDays
            p[Keys.linkPreviews] = s.linkPreviewsEnabled
            p[Keys.onboarding] = s.onboardingDone
            p[Keys.recentSearches] = s.recentSearches.joinToString("\u0001")
        }
    }

    private fun settingsFrom(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = enumOr(p[Keys.theme], d.themeMode),
            dynamicColor = p[Keys.dynamicColor] ?: d.dynamicColor,
            defaultNoteColor = NoteColor.fromName(p[Keys.defaultColor]),
            defaultNoteType = enumOr(p[Keys.defaultType], d.defaultNoteType),
            sortOrder = SortOrder.fromName(p[Keys.sort]),
            layout = enumOr(p[Keys.layout], d.layout),
            density = enumOr(p[Keys.density], d.density),
            editorFontSizeSp = p[Keys.fontSize] ?: d.editorFontSizeSp,
            editorFont = enumOr(p[Keys.font], d.editorFont),
            wordWrap = p[Keys.wordWrap] ?: d.wordWrap,
            showLineNumbers = p[Keys.lineNumbers] ?: d.showLineNumbers,
            showStatusBar = p[Keys.statusBar] ?: d.showStatusBar,
            moveCheckedToBottom = p[Keys.checkedToBottom] ?: d.moveCheckedToBottom,
            autosaveDelayMs = p[Keys.autosaveDelay] ?: d.autosaveDelayMs,
            codeFontSizeSp = p[Keys.codeFontSize] ?: d.codeFontSizeSp,
            appLockEnabled = p[Keys.appLock] ?: d.appLockEnabled,
            autoLockMinutes = p[Keys.autoLock] ?: d.autoLockMinutes,
            secureScreen = p[Keys.secureScreen] ?: d.secureScreen,
            revisionsEnabled = p[Keys.revisions] ?: d.revisionsEnabled,
            revisionIntervalMinutes = p[Keys.revisionInterval] ?: d.revisionIntervalMinutes,
            maxRevisionsPerNote = p[Keys.revisionsPerNote] ?: d.maxRevisionsPerNote,
            revisionStorageLimitMb = p[Keys.revisionStorage] ?: d.revisionStorageLimitMb,
            trashRetentionDays = p[Keys.trashRetention] ?: d.trashRetentionDays,
            linkPreviewsEnabled = p[Keys.linkPreviews] ?: d.linkPreviewsEnabled,
            onboardingDone = p[Keys.onboarding] ?: d.onboardingDone,
            recentSearches = p[Keys.recentSearches]?.split('\u0001')?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
