package com.nitinshukla.notebook.core.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTest {
    @Test fun `fromFileName by extension is case-insensitive`() {
        assertEquals(Language.JSON, Language.fromFileName("ARCHIVE.JSON"))
        assertEquals(Language.PYTHON, Language.fromFileName("/tmp/dir/script.py"))
        assertEquals(Language.KOTLIN, Language.fromFileName("C:\\src\\Main.KT"))
        assertEquals(Language.TYPESCRIPT, Language.fromFileName("app.tsx"))
        assertEquals(Language.CPP, Language.fromFileName("a.hpp"))
        assertEquals(Language.YAML, Language.fromFileName("ci.yml"))
        assertEquals(Language.INI, Language.fromFileName("app.properties"))
        assertEquals(Language.MARKDOWN, Language.fromFileName("README.markdown"))
    }

    @Test fun `fromFileName knows well-known names`() {
        assertEquals(Language.DOCKERFILE, Language.fromFileName("Dockerfile"))
        assertEquals(Language.DOCKERFILE, Language.fromFileName("dockerfile"))
        assertEquals(Language.DOCKERFILE, Language.fromFileName("Dockerfile.dev"))
        assertEquals(Language.MAKEFILE, Language.fromFileName("Makefile"))
        assertEquals(Language.MAKEFILE, Language.fromFileName("GNUmakefile"))
        assertEquals(Language.MAKEFILE, Language.fromFileName("rules.mk"))
        assertEquals(Language.INI, Language.fromFileName(".gitignore"))
        assertEquals(Language.INI, Language.fromFileName(".env"))
        assertEquals(Language.INI, Language.fromFileName(".env.local"))
        assertEquals(Language.SHELL, Language.fromFileName(".bashrc"))
        assertEquals(Language.GRADLE, Language.fromFileName("build.gradle.kts"))
        assertEquals(Language.GRADLE, Language.fromFileName("build.gradle"))
    }

    @Test fun `fromFileName falls back to plain`() {
        assertEquals(Language.PLAIN, Language.fromFileName("x.tar.gz"))
        assertEquals(Language.PLAIN, Language.fromFileName("notes"))
        assertEquals(Language.PLAIN, Language.fromFileName("notes."))
        assertEquals(Language.PLAIN, Language.fromFileName(".hidden"))
        assertEquals(Language.PLAIN, Language.fromFileName(""))
        assertEquals(Language.PLAIN, Language.fromFileName(null))
        assertEquals(Language.PLAIN, Language.fromFileName("a.txt"))
    }

    @Test fun `fromExtension with or without dot`() {
        assertEquals(Language.RUST, Language.fromExtension("rs"))
        assertEquals(Language.RUST, Language.fromExtension(".RS"))
        assertEquals(Language.PLAIN, Language.fromExtension("zzz"))
        assertEquals(Language.PLAIN, Language.fromExtension(null))
        assertEquals(Language.PLAIN, Language.fromExtension(" "))
    }

    @Test fun `every extension maps back to its language`() {
        for (language in Language.entries) {
            for (ext in language.extensions) {
                assertEquals(ext, language, Language.fromExtension(ext))
            }
        }
        assertTrue(Language.GO.indentWithTabs)
        assertEquals("\t", Language.MAKEFILE.indentUnit)
        assertEquals("  ", Language.YAML.indentUnit)
        assertEquals("    ", Language.PYTHON.indentUnit)
    }

    @Test fun `line ending detection picks the majority and defaults to LF`() {
        assertEquals(LineEnding.LF, LineEnding.detect(""))
        assertEquals(LineEnding.LF, LineEnding.detect("no newline"))
        assertEquals(LineEnding.LF, LineEnding.detect("a\nb\nc"))
        assertEquals(LineEnding.CRLF, LineEnding.detect("a\r\nb\r\nc\n"))
        assertEquals(LineEnding.CR, LineEnding.detect("a\rb\rc\n"))
        assertEquals(LineEnding.LF, LineEnding.detect("a\r\nb\n")) // tie goes to LF
        assertEquals(LineEnding.CRLF, LineEnding.detect("a\r\nb\r\nc\rd\re\n"))
    }

    @Test fun `line endings normalize and apply`() {
        assertEquals("a\nb\nc\n", LineEndings.normalizeToLf("a\r\nb\rc\n"))
        assertEquals("a\r\nb\r\n", LineEndings.apply("a\nb\n", LineEnding.CRLF))
        assertEquals("a\rb", LineEndings.apply("a\nb", LineEnding.CR))
        assertEquals("a\nb", LineEndings.apply("a\r\nb", LineEnding.LF))
        assertEquals("a\r\nb", LineEndings.apply("a\rb", LineEnding.CRLF))
        val same = "plain"
        assertEquals(same, LineEndings.normalizeToLf(same))
        assertEquals("LF", LineEnding.LF.displayName)
        assertEquals("\r\n", LineEnding.CRLF.chars)
    }
}
