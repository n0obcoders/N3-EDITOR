package com.nitinshukla.notebook.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nitinshukla.notebook.core.repository.SettingsRepository
import com.nitinshukla.notebook.core.revision.RevisionRules
import com.nitinshukla.notebook.core.util.Clock
import com.nitinshukla.notebook.core.util.TrashRules
import com.nitinshukla.notebook.data.db.NotebookDatabase
import com.nitinshukla.notebook.data.files.AttachmentStore
import com.nitinshukla.notebook.data.repository.RoomNoteRepository
import com.nitinshukla.notebook.data.repository.RoomRevisionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Daily housekeeping: empties old trash, trims revision storage, removes orphaned files. */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val notes: RoomNoteRepository,
    private val revisions: RoomRevisionRepository,
    private val settings: SettingsRepository,
    private val db: NotebookDatabase,
    private val attachments: AttachmentStore,
    private val clock: Clock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = settings.settings.first()
        val expired = TrashRules.expired(notes.trashedNotes(), prefs.trashRetentionDays, clock.now())
        if (expired.isNotEmpty()) notes.deletePermanently(expired)
        val over = RevisionRules.excessForStorage(revisions.allMetadata(), prefs.revisionPolicy.maxTotalBytes)
        if (over.isNotEmpty()) revisions.delete(over)
        attachments.deleteOrphans(db.attachmentDao().allFileNames().toSet())
        return Result.success()
    }

    companion object {
        private const val NAME = "maintenance"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
