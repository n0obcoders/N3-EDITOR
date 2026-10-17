package com.nitinshukla.notebook

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nitinshukla.notebook.core.repository.TemplateRepository
import com.nitinshukla.notebook.work.MaintenanceWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NotebookApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var templates: TemplateRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /**
     * Nothing heavy runs on the main thread here: the first frame should not wait for
     * WorkManager initialisation or a database write. Both happen on IO shortly after launch.
     */
    override fun onCreate() {
        super.onCreate()
        com.nitinshukla.notebook.data.db.LargeRowOpenHelperFactory.raiseDefaultWindowSize()
        appScope.launch {
            runCatching { templates.ensureDefaults() }
            delay(STARTUP_DEFER_MS)
            // WorkManager is unavailable in some test harnesses; housekeeping is best-effort.
            runCatching { MaintenanceWorker.schedule(this@NotebookApplication) }
        }
    }

    private companion object {
        const val STARTUP_DEFER_MS = 3_000L
    }
}
