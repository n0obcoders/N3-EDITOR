package com.nitinshukla.notebook.data.files

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A tab remembered between launches. [draftFile] holds unsaved text when [dirty]. */
data class SavedTab(
    val id: String,
    val uri: Uri?,
    val name: String,
    val language: String,
    val cursor: Int,
    val dirty: Boolean,
    val encoding: String,
    val bom: Boolean,
    val separator: String,
    val diskModified: Long,
)

data class SavedSession(
    val tabs: List<SavedTab> = emptyList(),
    val activeId: String? = null,
    val treeUri: Uri? = null,
    val autoSave: Boolean = false,
    val wordWrap: Boolean = false,
)

/**
 * Crash and process-death recovery for the code editors. Each workspace ("python", "code")
 * keeps a small session file listing its tabs and, for every tab with unsaved edits, a draft
 * of the text in app-private storage. Nothing here touches the user's files; drafts are
 * removed as soon as the document is saved or closed.
 */
@Singleton
class WorkspaceStore @Inject constructor(@ApplicationContext private val context: Context) {
    private fun dir(workspace: String): File = File(context.filesDir, "workspaces/$workspace").apply { mkdirs() }
    private fun sessionFile(workspace: String) = File(dir(workspace), "session.json")
    fun draftFile(workspace: String, id: String): File = File(dir(workspace), "$id.draft")

    suspend fun load(workspace: String): SavedSession = withContext(Dispatchers.IO) {
        val file = sessionFile(workspace)
        if (!file.exists()) return@withContext SavedSession()
        try {
            val json = JSONObject(file.readText())
            val tabs = json.optJSONArray("tabs") ?: JSONArray()
            SavedSession(
                tabs = (0 until tabs.length()).map { i ->
                    val t = tabs.getJSONObject(i)
                    SavedTab(
                        id = t.getString("id"),
                        uri = t.optString("uri", "").takeIf { it.isNotEmpty() }?.let(Uri::parse),
                        name = t.optString("name", "untitled"),
                        language = t.optString("language", "PLAIN"),
                        cursor = t.optInt("cursor", 0),
                        dirty = t.optBoolean("dirty", false),
                        encoding = t.optString("encoding", "UTF-8"),
                        bom = t.optBoolean("bom", false),
                        separator = t.optString("separator", "LF"),
                        diskModified = t.optLong("diskModified", 0),
                    )
                },
                activeId = json.optString("active", "").takeIf { it.isNotEmpty() },
                treeUri = json.optString("tree", "").takeIf { it.isNotEmpty() }?.let(Uri::parse),
                autoSave = json.optBoolean("autoSave", false),
                wordWrap = json.optBoolean("wordWrap", false),
            )
        } catch (e: Exception) {
            SavedSession()
        }
    }

    suspend fun save(workspace: String, session: SavedSession) = withContext(Dispatchers.IO) {
        val json = JSONObject()
        json.put("active", session.activeId ?: "")
        json.put("tree", session.treeUri?.toString() ?: "")
        json.put("autoSave", session.autoSave)
        json.put("wordWrap", session.wordWrap)
        json.put(
            "tabs",
            JSONArray().apply {
                session.tabs.forEach { t ->
                    put(
                        JSONObject()
                            .put("id", t.id).put("uri", t.uri?.toString() ?: "").put("name", t.name).put("language", t.language)
                            .put("cursor", t.cursor).put("dirty", t.dirty).put("encoding", t.encoding).put("bom", t.bom)
                            .put("separator", t.separator).put("diskModified", t.diskModified),
                    )
                }
            },
        )
        val file = sessionFile(workspace)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    suspend fun writeDraft(workspace: String, id: String, text: String) = withContext(Dispatchers.IO) {
        val file = draftFile(workspace, id)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    suspend fun readDraft(workspace: String, id: String): String? = withContext(Dispatchers.IO) {
        draftFile(workspace, id).takeIf { it.exists() }?.readText()
    }

    suspend fun deleteDraft(workspace: String, id: String) = withContext(Dispatchers.IO) {
        draftFile(workspace, id).delete()
        Unit
    }

    /** Removes drafts that no saved tab refers to any more. */
    suspend fun pruneDrafts(workspace: String, liveIds: Set<String>) = withContext(Dispatchers.IO) {
        dir(workspace).listFiles()?.filter { it.name.endsWith(".draft") && it.name.removeSuffix(".draft") !in liveIds }?.forEach { it.delete() }
        Unit
    }
}
