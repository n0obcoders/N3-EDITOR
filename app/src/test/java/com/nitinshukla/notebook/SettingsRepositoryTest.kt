package com.nitinshukla.notebook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nitinshukla.notebook.core.settings.ThemeMode
import com.nitinshukla.notebook.data.prefs.DataStoreSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsRepositoryTest {
    @Test
    fun settingsPersistAcrossInstances() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = DataStoreSettingsRepository(context)
        repo.update { it.copy(themeMode = ThemeMode.DARK, editorFontSizeSp = 20, recentSearches = listOf("a", "b")) }
        val loaded = DataStoreSettingsRepository(context).settings.first()
        assertEquals(ThemeMode.DARK, loaded.themeMode)
        assertEquals(20, loaded.editorFontSizeSp)
        assertEquals(listOf("a", "b"), loaded.recentSearches)
    }
}
