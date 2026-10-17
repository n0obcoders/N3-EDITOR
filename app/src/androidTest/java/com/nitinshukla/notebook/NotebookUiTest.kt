package com.nitinshukla.notebook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.nitinshukla.notebook.core.repository.SettingsRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * End-to-end Compose tests through the real activity, database and DataStore. They run on
 * an emulator or device with `./gradlew connectedDebugAndroidTest`.
 */
@HiltAndroidTest
class NotebookUiTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking { settings.update { it.copy(onboardingDone = true) } }
    }

    private fun createNote(title: String, body: String) {
        compose.onNodeWithContentDescription("New note").performClick()
        compose.onNodeWithText("Text note").performClick()
        compose.onNodeWithContentDescription("Note title").performTextInput(title)
        compose.onNodeWithContentDescription("Note text").performTextInput(body)
        compose.onNodeWithContentDescription("Back").performClick()
    }

    @Test
    fun createAndEditNote() {
        createNote("Shopping", "milk and eggs")
        compose.onNodeWithText("Shopping").assertIsDisplayed()
        compose.onNodeWithText("Shopping").performClick()
        compose.onNodeWithContentDescription("Note text").performTextReplacement("milk, eggs and bread")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("milk, eggs and bread").assertIsDisplayed()
    }

    @Test
    fun searchFindsNote() {
        createNote("Meeting", "discuss roadmap")
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasText("Search notes, code, files…")).performTextInput("roadmap")
        compose.onNodeWithText("Meeting").assertIsDisplayed()
    }

    @Test
    fun pinAndArchive() {
        createNote("Pin me", "x")
        compose.onNodeWithText("Pin me").performClick()
        compose.onNodeWithContentDescription("Pin").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Pinned").assertIsDisplayed()
        compose.onNode(hasContentDescription("Archive")).performClick()
        compose.onNodeWithText("Note archived").assertIsDisplayed()
    }

    @Test
    fun addChecklistItems() {
        compose.onNodeWithContentDescription("New note").performClick()
        compose.onNodeWithText("Checklist").performClick()
        compose.onNodeWithText("Add item").performClick()
        compose.onNodeWithContentDescription("Checklist item 1").performTextInput("Buy milk")
        compose.onNodeWithText("0 of 1 done").assertIsDisplayed()
    }

    @Test
    fun renameFromEditor() {
        createNote("Call", "mom")
        compose.onNodeWithText("Call").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Title").performTextClearance()
        compose.onNodeWithText("Title").performTextInput("Phone")
        compose.onAllNodesWithText("Rename")[1].performClick()
        compose.onNodeWithText("Phone").assertIsDisplayed()
    }

    @Test
    fun changeTheme() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Theme").performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Dark").assertIsDisplayed()
    }
}
