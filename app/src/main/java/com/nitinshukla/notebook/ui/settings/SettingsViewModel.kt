package com.nitinshukla.notebook.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nitinshukla.notebook.core.backup.BackupException
import com.nitinshukla.notebook.core.backup.DuplicateStrategy
import com.nitinshukla.notebook.core.backup.TextImport
import com.nitinshukla.notebook.core.model.Note
import com.nitinshukla.notebook.core.repository.NoteRepository
import com.nitinshukla.notebook.core.repository.SettingsRepository
import com.nitinshukla.notebook.core.settings.AppSettings
import com.nitinshukla.notebook.data.backup.BackupInfo
import com.nitinshukla.notebook.data.backup.BackupManager
import com.nitinshukla.notebook.data.backup.RestoreResult
import com.nitinshukla.notebook.data.files.AttachmentStore
import com.nitinshukla.notebook.data.files.TextFileException
import com.nitinshukla.notebook.data.files.TextFiles
import com.nitinshukla.notebook.data.repository.RoomNoteRepository
import com.nitinshukla.notebook.data.repository.RoomRevisionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageInfo(val textBytes: Long = 0, val attachmentBytes: Long = 0, val revisionBytes: Long = 0) {
    val total: Long get() = textBytes + attachmentBytes + revisionBytes
}

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Working : BackupUiState
    data class Message(val text: String, val isError: Boolean = false) : BackupUiState
    data class ConfirmRestore(val uri: Uri, val info: BackupInfo) : BackupUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager,
    private val notes: NoteRepository,
    private val roomNotes: RoomNoteRepository,
    private val revisions: RoomRevisionRepository,
    private val attachmentStore: AttachmentStore,
    private val textFiles: TextFiles,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val _storage = MutableStateFlow(StorageInfo())
    val storage: StateFlow<StorageInfo> = _storage
    private val _backup = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backup: StateFlow<BackupUiState> = _backup

    init {
        refreshStorage()
    }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepository.update(transform) }

    fun refreshStorage() = viewModelScope.launch {
        _storage.value = StorageInfo(roomNotes.approximateTextBytes(), attachmentStore.totalBytes(), revisions.totalBytes())
    }

    fun suggestedBackupName(zip: Boolean) = BackupManager.suggestedName(zip, System.currentTimeMillis())

    fun createBackup(uri: Uri, zip: Boolean) = io {
        _backup.value = BackupUiState.Working
        try {
            if (zip) backupManager.writeZip(uri) else backupManager.writeJson(uri)
            _backup.value = BackupUiState.Message("Backup saved")
        } catch (e: Exception) {
            _backup.value = BackupUiState.Message(e.message ?: "Backup failed", isError = true)
        }
    }

    fun exportAllNotes(uri: Uri) = createBackup(uri, zip = false)

    fun inspectBackup(uri: Uri) = io {
        _backup.value = BackupUiState.Working
        try {
            _backup.value = BackupUiState.ConfirmRestore(uri, backupManager.inspect(uri))
        } catch (e: BackupException) {
            _backup.value = BackupUiState.Message(e.message ?: "Invalid backup", isError = true)
        } catch (e: Exception) {
            _backup.value = BackupUiState.Message("Could not read the backup: ${e.message}", isError = true)
        }
    }

    fun restore(uri: Uri, strategy: DuplicateStrategy) = io {
        _backup.value = BackupUiState.Working
        try {
            val r: RestoreResult = backupManager.restore(uri, strategy)
            val msg = buildString {
                append("Restored ${r.inserted} notes")
                if (r.replaced > 0) append(", replaced ${r.replaced}")
                if (r.skipped > 0) append(", skipped ${r.skipped} duplicates")
                if (r.attachmentsMissing > 0) append(". ${r.attachmentsMissing} attachments were not in the backup")
            }
            _backup.value = BackupUiState.Message(msg)
            refreshStorage()
        } catch (e: BackupException) {
            _backup.value = BackupUiState.Message(e.message ?: "Restore failed", isError = true)
        } catch (e: Exception) {
            _backup.value = BackupUiState.Message("Restore failed: ${e.message}", isError = true)
        }
    }

    fun importTextFiles(uris: List<Uri>) = io {
        _backup.value = BackupUiState.Working
        var ok = 0
        var failed = 0
        for (uri in uris) {
            try {
                val f = textFiles.read(uri)
                notes.create(Note(title = TextImport.titleFor(f.displayName), content = f.text, fileName = f.displayName))
                ok++
            } catch (e: TextFileException) {
                failed++
            } catch (e: SecurityException) {
                failed++
            }
        }
        _backup.value = BackupUiState.Message("Imported $ok files" + if (failed > 0) ", $failed could not be read" else "", isError = ok == 0 && failed > 0)
    }

    fun dismissBackupMessage() {
        _backup.value = BackupUiState.Idle
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
