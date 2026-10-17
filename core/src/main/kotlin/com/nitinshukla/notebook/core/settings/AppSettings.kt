package com.nitinshukla.notebook.core.settings

import com.nitinshukla.notebook.core.model.NoteColor
import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.revision.RevisionPolicy
import com.nitinshukla.notebook.core.search.SortOrder

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class NoteLayout { GRID, LIST }
enum class Density { COMFORTABLE, COMPACT }
enum class EditorFont { DEFAULT, SANS_SERIF, SERIF, MONOSPACE, CURSIVE }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultNoteColor: NoteColor = NoteColor.DEFAULT,
    val defaultNoteType: NoteType = NoteType.PLAIN,
    val sortOrder: SortOrder = SortOrder.MODIFIED_DESC,
    val layout: NoteLayout = NoteLayout.GRID,
    val density: Density = Density.COMFORTABLE,
    val editorFontSizeSp: Int = 16,
    val editorFont: EditorFont = EditorFont.DEFAULT,
    val wordWrap: Boolean = true,
    val showLineNumbers: Boolean = false,
    val showStatusBar: Boolean = true,
    val moveCheckedToBottom: Boolean = true,
    /** Milliseconds of typing silence before an automatic save. */
    val autosaveDelayMs: Int = 800,
    /** Editor font size for the Python and code editors, separate from notes. */
    val codeFontSizeSp: Int = 14,
    val appLockEnabled: Boolean = false,
    val autoLockMinutes: Int = 1,
    /** Blocks screenshots and hides content in the recent-apps switcher. */
    val secureScreen: Boolean = false,
    val revisionsEnabled: Boolean = true,
    val revisionIntervalMinutes: Int = 10,
    val maxRevisionsPerNote: Int = 20,
    val revisionStorageLimitMb: Int = 20,
    /** 0 means never empty the trash automatically. */
    val trashRetentionDays: Int = 30,
    val linkPreviewsEnabled: Boolean = false,
    val onboardingDone: Boolean = false,
    val recentSearches: List<String> = emptyList(),
) {
    val revisionPolicy: RevisionPolicy
        get() = RevisionPolicy(
            minIntervalMs = revisionIntervalMinutes.coerceAtLeast(1) * 60_000L,
            maxPerNote = maxRevisionsPerNote,
            maxTotalBytes = revisionStorageLimitMb.coerceAtLeast(1) * 1024L * 1024L,
        )

    companion object {
        val FONT_SIZES = 12..32
        val AUTOSAVE_DELAY_CHOICES = listOf(300, 800, 1500, 3000)
        val TRASH_RETENTION_CHOICES = listOf(0, 7, 30, 90)
        val AUTO_LOCK_CHOICES = listOf(0, 1, 5, 15, 60)
    }
}
