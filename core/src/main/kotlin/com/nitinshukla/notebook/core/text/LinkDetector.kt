package com.nitinshukla.notebook.core.text

data class DetectedLink(val url: String, val range: IntRange)

object LinkDetector {
    private val urlRegex = Regex(
        "(?i)\\b((?:https?://|www\\.)[\\w-]+(?:\\.[\\w-]+)+(?::\\d+)?(?:[/?#][^\\s<>\"']*)?)",
    )
    private val trailingPunctuation = ".,;:!?)]}'\""

    fun find(text: String): List<DetectedLink> = urlRegex.findAll(text).map { m ->
        var value = m.value
        while (value.isNotEmpty() && value.last() in trailingPunctuation) value = value.dropLast(1)
        DetectedLink(normalize(value), m.range.first until m.range.first + value.length)
    }.toList()

    /** Adds https:// to bare www. links. Only http and https are ever produced. */
    fun normalize(url: String): String =
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) url else "https://$url"

    fun isSafeWebUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.startsWith("https://") || lower.startsWith("http://")) && urlRegex.matches(url)
    }
}
