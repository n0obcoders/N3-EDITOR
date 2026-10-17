package com.nitinshukla.notebook.di

import android.content.Context
import androidx.room.Room
import com.nitinshukla.notebook.core.repository.LinkPreviewRepository
import com.nitinshukla.notebook.core.repository.NoteRepository
import com.nitinshukla.notebook.core.repository.RevisionRepository
import com.nitinshukla.notebook.core.repository.RevisionService
import com.nitinshukla.notebook.core.repository.SettingsRepository
import com.nitinshukla.notebook.core.repository.TemplateRepository
import com.nitinshukla.notebook.core.util.Clock
import com.nitinshukla.notebook.data.db.Migrations
import com.nitinshukla.notebook.data.db.NotebookDatabase
import com.nitinshukla.notebook.data.prefs.DataStoreSettingsRepository
import com.nitinshukla.notebook.data.repository.RoomLinkPreviewRepository
import com.nitinshukla.notebook.data.repository.RoomNoteRepository
import com.nitinshukla.notebook.data.repository.RoomRevisionRepository
import com.nitinshukla.notebook.data.repository.RoomTemplateRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): NotebookDatabase =
        Room.databaseBuilder(context, NotebookDatabase::class.java, NotebookDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            .openHelperFactory(com.nitinshukla.notebook.data.db.LargeRowOpenHelperFactory())
            .build()

    @Provides
    @Singleton
    fun clock(): Clock = Clock.System

    @Provides
    @Singleton
    fun revisionService(revisions: RevisionRepository, clock: Clock): RevisionService =
        RevisionService(revisions) { clock.now() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun notes(impl: RoomNoteRepository): NoteRepository
    @Binds abstract fun revisions(impl: RoomRevisionRepository): RevisionRepository
    @Binds abstract fun templates(impl: RoomTemplateRepository): TemplateRepository
    @Binds abstract fun linkPreviews(impl: RoomLinkPreviewRepository): LinkPreviewRepository
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
}
