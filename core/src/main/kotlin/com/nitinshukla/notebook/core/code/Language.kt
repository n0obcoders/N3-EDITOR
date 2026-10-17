package com.nitinshukla.notebook.core.code

/** A language the code editor can highlight, with the comment and indentation conventions the editor commands use. */
enum class Language(
    val displayName: String,
    val extensions: List<String>,
    val lineComment: String?,
    val blockComment: Pair<String, String>?,
    val indentWidth: Int = 4,
    val indentWithTabs: Boolean = false,
) {
    PYTHON("Python", listOf("py", "pyw", "pyi"), "#", null),
    KOTLIN("Kotlin", listOf("kt", "kts"), "//", "/*" to "*/"),
    JAVA("Java", listOf("java"), "//", "/*" to "*/"),
    C("C", listOf("c", "h"), "//", "/*" to "*/"),
    CPP("C++", listOf("cpp", "cc", "cxx", "hpp", "hh", "hxx"), "//", "/*" to "*/"),
    CSHARP("C#", listOf("cs"), "//", "/*" to "*/"),
    JAVASCRIPT("JavaScript", listOf("js", "mjs", "cjs", "jsx"), "//", "/*" to "*/"),
    TYPESCRIPT("TypeScript", listOf("ts", "tsx"), "//", "/*" to "*/"),
    GO("Go", listOf("go"), "//", "/*" to "*/", indentWithTabs = true),
    RUST("Rust", listOf("rs"), "//", "/*" to "*/"),
    PHP("PHP", listOf("php"), "//", "/*" to "*/"),
    SWIFT("Swift", listOf("swift"), "//", "/*" to "*/"),
    RUBY("Ruby", listOf("rb"), "#", null, indentWidth = 2),
    HTML("HTML", listOf("html", "htm"), null, "<!--" to "-->", indentWidth = 2),
    XML("XML", listOf("xml", "xsd", "svg", "plist"), null, "<!--" to "-->", indentWidth = 2),
    CSS("CSS", listOf("css", "scss", "less"), null, "/*" to "*/", indentWidth = 2),
    JSON("JSON", listOf("json", "jsonc"), null, null, indentWidth = 2),
    YAML("YAML", listOf("yaml", "yml"), "#", null, indentWidth = 2),
    TOML("TOML", listOf("toml"), "#", null),
    SQL("SQL", listOf("sql"), "--", "/*" to "*/"),
    SHELL("Shell", listOf("sh", "bash", "zsh"), "#", null),
    POWERSHELL("PowerShell", listOf("ps1"), "#", "<#" to "#>"),
    BATCH("Batch", listOf("bat", "cmd"), "REM ", null),
    MARKDOWN("Markdown", listOf("md", "markdown"), null, null),
    INI("INI", listOf("ini", "conf", "cfg", "properties", "env"), "#", null),
    DOCKERFILE("Dockerfile", listOf("dockerfile"), "#", null),
    MAKEFILE("Makefile", listOf("mk", "makefile"), "#", null, indentWithTabs = true),
    GRADLE("Gradle", listOf("gradle"), "//", "/*" to "*/"),
    LOG("Log", listOf("log"), null, null),
    PLAIN("Plain text", listOf("txt", "text"), null, null);

    /** The whitespace inserted for one indent level. */
    val indentUnit: String get() = if (indentWithTabs) "\t" else " ".repeat(indentWidth)

    companion object {
        private val byExtension: Map<String, Language> = buildMap {
            for (language in Language.entries) for (ext in language.extensions) putIfAbsent(ext, language)
        }

        /** File names without an extension (or with a leading dot) that identify a language. */
        private val byName: Map<String, Language> = mapOf(
            "dockerfile" to DOCKERFILE,
            "containerfile" to DOCKERFILE,
            "makefile" to MAKEFILE,
            "gnumakefile" to MAKEFILE,
            "jenkinsfile" to GRADLE,
            ".gitignore" to INI,
            ".gitattributes" to INI,
            ".gitmodules" to INI,
            ".dockerignore" to INI,
            ".npmignore" to INI,
            ".editorconfig" to INI,
            ".npmrc" to INI,
            ".env" to INI,
            ".bashrc" to SHELL,
            ".bash_profile" to SHELL,
            ".zshrc" to SHELL,
            ".profile" to SHELL,
        )

        /** By extension or well-known file name (Dockerfile, Makefile, .gitignore -> INI); PLAIN when unknown. Case-insensitive. */
        fun fromFileName(fileName: String?): Language {
            if (fileName.isNullOrBlank()) return PLAIN
            val name = fileName.trim().substringAfterLast('/').substringAfterLast('\\').lowercase()
            byName[name]?.let { return it }
            if (name.startsWith("dockerfile.") || name.endsWith(".dockerfile")) return DOCKERFILE
            if (name.endsWith(".gradle.kts")) return GRADLE
            if (name.startsWith(".env.")) return INI
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.lastIndex) return PLAIN
            return fromExtension(name.substring(dot + 1))
        }

        /** Language for a file extension with or without the leading dot; PLAIN when unknown. Case-insensitive. */
        fun fromExtension(extension: String?): Language {
            if (extension.isNullOrBlank()) return PLAIN
            return byExtension[extension.trim().removePrefix(".").lowercase()] ?: PLAIN
        }
    }
}
