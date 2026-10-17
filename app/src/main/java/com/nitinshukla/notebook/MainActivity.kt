package com.nitinshukla.notebook

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nitinshukla.notebook.core.repository.SettingsRepository
import com.nitinshukla.notebook.core.settings.AppSettings
import com.nitinshukla.notebook.security.AppLockManager
import com.nitinshukla.notebook.ui.NotebookApp
import com.nitinshukla.notebook.ui.theme.NotebookTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** FragmentActivity rather than ComponentActivity because BiometricPrompt requires it. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var appLock: AppLockManager

    private var pendingNoteId by mutableStateOf<Long?>(null)
    private var pendingAction by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val prefs by settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val current = prefs ?: return@setContent
            LaunchedEffect(current.secureScreen) {
                if (current.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            NotebookTheme(themeMode = current.themeMode, dynamicColor = current.dynamicColor) {
                val locked by appLock.locked.collectAsState()
                // Edge-to-edge windows are not resized by adjustResize, so the keyboard used to
                // cover the lower half of every editor. Padding the whole app by the IME inset
                // keeps text fields, find bars and status bars above the keyboard; the inset is
                // consumed here, so Scaffolds do not add the navigation bar padding on top of it.
                Box(Modifier.fillMaxSize().imePadding()) {
                    NotebookApp(
                        settings = current,
                        locked = locked,
                        pendingNoteId = pendingNoteId,
                        pendingAction = pendingAction,
                        onPendingConsumed = { pendingNoteId = null; pendingAction = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ACTION_NEW_NOTE -> pendingAction = ACTION_NEW_NOTE
            ACTION_NEW_CHECKLIST -> pendingAction = ACTION_NEW_CHECKLIST
            ACTION_NEW_CODE_NOTE -> pendingAction = ACTION_NEW_CODE_NOTE
            ACTION_OPEN_NOTE -> pendingNoteId = intent.getLongExtra(EXTRA_NOTE_ID, -1).takeIf { it >= 0 }
            Intent.ACTION_SEND -> if (intent.type?.startsWith("text/") == true) {
                pendingAction = ACTION_NEW_NOTE
                sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                sharedTitle = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            }
            Intent.ACTION_VIEW -> if (intent.data != null) {
                pendingAction = ACTION_NEW_NOTE
                openUri = intent.data
            }
        }
    }

    companion object {
        const val ACTION_NEW_NOTE = "com.nitinshukla.notebook.NEW_NOTE"
        const val ACTION_NEW_CHECKLIST = "com.nitinshukla.notebook.NEW_CHECKLIST"
        const val ACTION_NEW_CODE_NOTE = "com.nitinshukla.notebook.NEW_CODE_NOTE"
        const val ACTION_OPEN_NOTE = "com.nitinshukla.notebook.OPEN_NOTE_WIDGET"
        const val EXTRA_NOTE_ID = "note_id"

        /** Text shared into the app, consumed by the editor once. */
        var sharedText: String? = null
        var sharedTitle: String? = null
        var openUri: android.net.Uri? = null
    }
}
