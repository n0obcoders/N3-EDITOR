package com.nitinshukla.notebook.core.code

/**
 * Fast single-pass tokenizer for the code editor. Call [tokenize] on a background thread after
 * each edit and map the token types to colours. Results are sorted, non-overlapping, within
 * bounds, and never include PLAIN tokens (gaps are plain).
 */
object SyntaxHighlighter {

    /** Tokenizes at most [maxChars] of [text] (the rest is left plain). Never throws. */
    fun tokenize(text: CharSequence, language: Language, maxChars: Int = 400_000): List<Token> =
        scan(text, language, maxChars)?.tokens ?: emptyList()

    /** Runs the scanner for [language] and returns it (with tokens and unterminated-literal info), or null for PLAIN. */
    internal fun scan(text: CharSequence, language: Language, maxChars: Int = 400_000): Scanner? {
        if (language == Language.PLAIN) return null
        val limit = minOf(text.length, maxChars.coerceAtLeast(0))
        val scanner = newScanner(text, limit, language) ?: return null
        try {
            scanner.scan()
        } catch (e: Exception) {
            // A tokenizer bug must never take the editor down; keep whatever was produced so far.
        } catch (e: StackOverflowError) {
            // Same for unexpectedly deep input.
        }
        return scanner
    }

    private fun newScanner(text: CharSequence, limit: Int, language: Language): Scanner? = when (language) {
        Language.HTML -> MarkupScanner(text, limit, html = true)
        Language.XML -> MarkupScanner(text, limit, html = false)
        Language.CSS -> CssScanner(text, limit)
        Language.JSON -> JsonScanner(text, limit)
        Language.YAML -> YamlScanner(text, limit)
        Language.TOML -> IniScanner(text, limit, toml = true)
        Language.INI -> IniScanner(text, limit, toml = false)
        Language.MARKDOWN -> MarkdownScanner(text, limit)
        Language.SHELL -> ShellScanner(text, limit, dockerfile = false)
        Language.DOCKERFILE -> ShellScanner(text, limit, dockerfile = true)
        Language.MAKEFILE -> MakefileScanner(text, limit)
        Language.BATCH -> BatchScanner(text, limit)
        Language.LOG -> LogScanner(text, limit)
        Language.PLAIN -> null
        else -> LanguageSpecs.forLanguage(language)?.let { CLikeScanner(text, limit, it) }
    }
}
