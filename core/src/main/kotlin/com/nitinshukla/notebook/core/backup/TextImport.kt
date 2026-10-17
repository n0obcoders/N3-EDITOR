package com.nitinshukla.notebook.core.backup

/** Rules for importing plain-text documents as notes. */
object TextImport {
    const val MAX_TEXT_BYTES = 5L * 1024 * 1024

    private val supportedExtensions = setOf(
        "txt", "text", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "properties",
        "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "html", "htm", "css", "scss", "c", "h", "cpp", "hpp", "cc", "hh",
        "cs", "go", "rs", "rb", "php", "sql", "sh", "bash", "zsh", "bat", "ps1", "gradle", "pcapfilter", "env", "gitignore", "dockerfile", "makefile",
    )

    fun isSupported(fileName: String?, mimeType: String?): Boolean {
        if (mimeType != null && (mimeType.startsWith("text/") || mimeType == "application/json" || mimeType == "application/xml" ||
                mimeType == "application/javascript" || mimeType == "application/x-sh" || mimeType == "application/x-yaml")
        ) return true
        val lower = fileName?.lowercase().orEmpty()
        val ext = lower.substringAfterLast('.', "")
        return ext in supportedExtensions || lower in supportedExtensions
    }

    /** Title from the file name without its extension. */
    fun titleFor(fileName: String?): String =
        fileName?.substringBeforeLast('.')?.trim().orEmpty().ifEmpty { "Imported note" }

    /** Strips a UTF-8 byte-order mark and normalizes Windows/old-Mac line endings. */
    fun normalize(text: String): String = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')

    /** A safe file name for exporting a note: no path separators or reserved characters. */
    fun exportFileName(title: String, extension: String = "txt"): String {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80)
        return (cleaned.ifEmpty { "Untitled note" }) + "." + extension
    }
}
