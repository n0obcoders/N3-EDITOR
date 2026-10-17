package com.nitinshukla.notebook.linkpreview

import com.nitinshukla.notebook.core.model.LinkPreview
import com.nitinshukla.notebook.core.repository.LinkPreviewRepository
import com.nitinshukla.notebook.core.text.LinkDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a page title and description for a link. Only runs when the user taps
 * "Preview" on a link and has enabled previews in settings; nothing is fetched
 * automatically. Only the first 256 KB of HTML is read and no cookies are sent.
 */
@Singleton
class LinkPreviewFetcher @Inject constructor(private val repository: LinkPreviewRepository) {
    suspend fun fetch(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        if (!LinkDetector.isSafeWebUrl(url)) return@withContext null
        val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return@withContext null
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Notebook/1.0 (Android; link preview)")
            connection.setRequestProperty("Accept", "text/html")
            if (connection.responseCode !in 200..299) return@withContext null
            val type = connection.contentType.orEmpty()
            if (!type.startsWith("text/html")) return@withContext null
            val html = connection.inputStream.bufferedReader().use { r ->
                val buf = CharArray(MAX_CHARS)
                var total = 0
                while (total < MAX_CHARS) {
                    val n = r.read(buf, total, MAX_CHARS - total)
                    if (n < 0) break
                    total += n
                }
                String(buf, 0, total)
            }
            val preview = LinkPreview(
                url = url,
                title = meta(html, "og:title") ?: TITLE.find(html)?.groupValues?.get(1)?.let(::clean),
                description = meta(html, "og:description") ?: meta(html, "description"),
                fetchedAt = System.currentTimeMillis(),
            )
            repository.save(preview)
            preview
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun meta(html: String, name: String): String? {
        val regex = Regex("<meta[^>]+(?:property|name)=[\"']${Regex.escape(name)}[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        val alt = Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']${Regex.escape(name)}[\"']", RegexOption.IGNORE_CASE)
        return (regex.find(html) ?: alt.find(html))?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotBlank() }
    }

    private fun clean(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim().take(300)

    companion object {
        private const val MAX_CHARS = 256 * 1024
        private val TITLE = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
    }
}
