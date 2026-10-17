package com.nitinshukla.notebook

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nitinshukla.notebook.core.util.Clock
import com.nitinshukla.notebook.data.backup.BackupManager
import com.nitinshukla.notebook.data.db.NotebookDatabase
import com.nitinshukla.notebook.data.files.AttachmentStore
import com.nitinshukla.notebook.data.files.TextFiles
import com.nitinshukla.notebook.data.prefs.DataStoreSettingsRepository
import com.nitinshukla.notebook.data.repository.RoomNoteRepository
import com.nitinshukla.notebook.data.repository.RoomRevisionRepository
import com.nitinshukla.notebook.security.NoteCrypto
import com.nitinshukla.notebook.ui.settings.SettingsScreen
import com.nitinshukla.notebook.ui.settings.SettingsViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Composes the Settings screen with real repositories so a crash there fails a JVM test. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsScreenComposesAndOpensDialogs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java).allowMainThreadQueries().build()
        val store = AttachmentStore(context)
        val notes = RoomNoteRepository(db, NoteCrypto(), store, Clock { 1L })
        val vm = SettingsViewModel(
            DataStoreSettingsRepository(context),
            BackupManager(context, db, notes, NoteCrypto(), store, Clock { 1L }),
            notes,
            notes,
            RoomRevisionRepository(db),
            store,
            TextFiles(context),
        )
        compose.setContent { SettingsScreen(onBack = {}, viewModel = vm) }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.settings_appearance)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.autosave_delay)).performClick()
        compose.waitForIdle()
        db.close()
    }
}
