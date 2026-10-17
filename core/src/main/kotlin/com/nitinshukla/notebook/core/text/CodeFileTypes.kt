package com.nitinshukla.notebook.core.text

/** A file extension the code editors can save to, with the MIME type to hand the document picker. */
data class FileType(val extension: String, val mimeType: String, val language: String)

/**
 * File types offered by "Save as" for code notes and the code editors, in the order they are
 * shown. The MIME type matters: ACTION_CREATE_DOCUMENT uses it as a hint and some providers
 * append an extension for well-known types, so text/plain is used where no better type is
 * universally recognised.
 */
object CodeFileTypes {
    val all: List<FileType> = listOf(
        FileType("py", "text/x-python", "Python"),
        FileType("java", "text/x-java-source", "Java"),
        FileType("cpp", "text/x-c++src", "C++"),
        FileType("c", "text/x-csrc", "C"),
        FileType("h", "text/x-chdr", "C header"),
        FileType("hpp", "text/x-c++hdr", "C++ header"),
        FileType("js", "text/javascript", "JavaScript"),
        FileType("ts", "text/plain", "TypeScript"),
        FileType("html", "text/html", "HTML"),
        FileType("css", "text/css", "CSS"),
        FileType("php", "text/plain", "PHP"),
        FileType("sql", "text/plain", "SQL"),
        FileType("json", "application/json", "JSON"),
        FileType("xml", "text/xml", "XML"),
        FileType("yaml", "text/plain", "YAML"),
        FileType("yml", "text/plain", "YAML"),
        FileType("sh", "text/plain", "Shell"),
        FileType("rs", "text/plain", "Rust"),
        FileType("go", "text/plain", "Go"),
        FileType("kt", "text/plain", "Kotlin"),
        FileType("txt", "text/plain", "Plain text"),
        FileType("md", "text/markdown", "Markdown"),
        FileType("log", "text/plain", "Log"),
        FileType("conf", "text/plain", "Config"),
        FileType("ini", "text/plain", "INI"),
    )

    private val byExtension = all.associateBy { it.extension }

    fun forExtension(extension: String): FileType? = byExtension[extension.lowercase().removePrefix(".")]

    /** MIME type for any extension, falling back to text/plain for custom ones. */
    fun mimeFor(extension: String): String = forExtension(extension)?.mimeType ?: "text/plain"

    /** Language name for syntax highlighting, or null when unknown. */
    fun languageFor(fileName: String?): String? = fileName?.substringAfterLast('.', "")?.let { forExtension(it)?.language }

    /** True for an extension made of 1–10 letters or digits. */
    fun isValidCustomExtension(extension: String): Boolean = Regex("^[A-Za-z0-9]{1,10}$").matches(extension)

    /**
     * Joins a base name and an extension into a file name safe for the document picker.
     * A base name that already ends with the extension is not doubled.
     */
    fun fileName(baseName: String, extension: String): String {
        val cleaned = baseName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(100)
        val ext = extension.lowercase().removePrefix(".")
        val base = if (cleaned.lowercase().endsWith(".$ext")) cleaned.dropLast(ext.length + 1) else cleaned
        return (base.ifEmpty { "untitled" }) + "." + ext
    }

    /** Extension of a file name without the dot, lowercase, or empty. */
    fun extensionOf(fileName: String?): String = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
}
