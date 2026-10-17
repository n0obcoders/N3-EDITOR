package com.nitinshukla.notebook.data.repository

import com.nitinshukla.notebook.core.model.LinkPreview
import com.nitinshukla.notebook.core.model.Revision
import com.nitinshukla.notebook.core.model.Template
import com.nitinshukla.notebook.core.repository.LinkPreviewRepository
import com.nitinshukla.notebook.core.repository.RevisionRepository
import com.nitinshukla.notebook.core.repository.TemplateRepository
import com.nitinshukla.notebook.core.template.DefaultTemplates
import com.nitinshukla.notebook.data.db.NotebookDatabase
import com.nitinshukla.notebook.data.db.toEntity
import com.nitinshukla.notebook.data.db.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomRevisionRepository @Inject constructor(private val db: NotebookDatabase) : RevisionRepository {
    private val dao get() = db.revisionDao()
    override fun observe(noteId: Long): Flow<List<Revision>> = dao.observe(noteId).map { l -> l.map { it.toModel() } }
    override suspend fun latest(noteId: Long): Revision? = dao.latest(noteId)?.toModel()
    override suspend fun get(id: Long): Revision? = dao.get(id)?.toModel()
    override suspend fun insert(revision: Revision): Long = dao.insert(revision.toEntity())
    override suspend fun delete(ids: Collection<Long>) { if (ids.isNotEmpty()) dao.delete(ids) }
    override suspend fun deleteAllFor(noteId: Long) = dao.deleteFor(noteId)
    override suspend fun allForNote(noteId: Long): List<Revision> = dao.forNote(noteId).map { it.toModel() }
    override suspend fun allMetadata(): List<Revision> = dao.allMeta().map { it.toModel() }
    suspend fun totalBytes(): Long = dao.totalBytes()
}

@Singleton
class RoomTemplateRepository @Inject constructor(private val db: NotebookDatabase) : TemplateRepository {
    private val dao get() = db.templateDao()
    override fun observeAll(): Flow<List<Template>> = dao.observeAll().map { l -> l.map { it.toModel() } }
    override suspend fun get(id: Long): Template? = dao.get(id)?.toModel()
    override suspend fun upsert(template: Template): Long {
        val id = dao.upsert(template.toEntity())
        return if (template.id != 0L) template.id else id
    }
    override suspend fun delete(id: Long) = dao.delete(id)
    override suspend fun ensureDefaults() {
        if (dao.count() == 0) dao.insertAll(DefaultTemplates.all.map { it.toEntity() })
    }
}

@Singleton
class RoomLinkPreviewRepository @Inject constructor(private val db: NotebookDatabase) : LinkPreviewRepository {
    override fun observe(url: String): Flow<LinkPreview?> = db.linkPreviewDao().observe(url).map { it?.toModel() }
    override suspend fun save(preview: LinkPreview) = db.linkPreviewDao().upsert(preview.toEntity())
}
