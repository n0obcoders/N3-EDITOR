package com.nitinshukla.notebook.core.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTokenizerTest {
    @Test fun `shell keywords builtins variables strings and options`() {
        assertTokens(
            "COMMENT:#!/bin/bash KEYWORD:if PUNCTUATION:[ STRING:\"\$HOME/x\" PUNCTUATION:] PUNCTUATION:; KEYWORD:then BUILTIN:echo STRING:\"hi \${USER}\" OPERATOR:> COMMENT:# c KEYWORD:fi " +
                "VARIABLE:FOO OPERATOR:= FUNCTION:my_func PUNCTUATION:( PUNCTUATION:) PUNCTUATION:{ BUILTIN:local OPERATOR:= VARIABLE:\$( ATTRIBUTE:-la PUNCTUATION:) PUNCTUATION:; PUNCTUATION:} " +
                "KEYWORD:for KEYWORD:in NUMBER:1 NUMBER:2 PUNCTUATION:; KEYWORD:do BUILTIN:echo VARIABLE:\$i PUNCTUATION:; KEYWORD:done",
            "#!/bin/bash\nif [ -f \"\$HOME/x\" ]; then\n  echo \"hi \${USER}\" > out.txt # c\nfi\nFOO=bar\nmy_func() { local x=\$(ls -la); }\nfor i in 1 2; do echo \$i; done",
            Language.SHELL,
        )
        assertTokens("STRING:'multi\nline' VARIABLE:\$? VARIABLE:\$1 VARIABLE:\$@", "'multi\nline' \$? \$1 \$@", Language.SHELL)
        assertTokens("BUILTIN:echo", "echo a#b", Language.SHELL)
    }

    @Test fun `dockerfile instructions at line start`() {
        assertTokens(
            "KEYWORD:FROM KEYWORD:RUN ATTRIBUTE:-y PUNCTUATION:&& BUILTIN:echo STRING:\"x\" KEYWORD:ENV VARIABLE:A OPERATOR:= NUMBER:1 VARIABLE:B OPERATOR:= NUMBER:2 KEYWORD:COPY COMMENT:# c KEYWORD:CMD PUNCTUATION:[ STRING:\"a\" STRING:\"b\" PUNCTUATION:]",
            "FROM ubuntu:22.04 AS base\nRUN apt-get install -y curl && echo \"x\"\nENV A=1 B=2\nCOPY . /app\n# c\nCMD [\"a\", \"b\"]",
            Language.DOCKERFILE,
        )
    }

    @Test fun `makefile targets variables and directives`() {
        assertTokens(
            "VARIABLE:CC OPERATOR::= KEYWORD:.PHONY PUNCTUATION:: FUNCTION:all PUNCTUATION:: COMMENT:# c OPERATOR:@ VARIABLE:\$(CC) ATTRIBUTE:-o VARIABLE:\$@ VARIABLE:\$(OBJS) STRING:\"x\" " +
                "KEYWORD:ifeq PUNCTUATION:( VARIABLE:\$(OS) PUNCTUATION:, PUNCTUATION:) KEYWORD:endif",
            "CC := gcc\n.PHONY: all\nall: main.o # c\n\t@\$(CC) -o \$@ \$(OBJS) \"x\"\nifeq (\$(OS),Windows_NT)\nendif",
            Language.MAKEFILE,
        )
        assertTokens("FUNCTION:build FUNCTION:test PUNCTUATION:: BUILTIN:echo VARIABLE:\${FOO}", "build test: deps\n\techo \${FOO}", Language.MAKEFILE)
    }

    @Test fun `batch comments variables labels and commands`() {
        assertTokens(
            "OPERATOR:@ KEYWORD:echo KEYWORD:off COMMENT:REM comment COMMENT::: also KEYWORD:set OPERATOR:= NUMBER:1 KEYWORD:if KEYWORD:exist STRING:\"%VAR%\" KEYWORD:goto FUNCTION::end FUNCTION::end " +
                "KEYWORD:echo VARIABLE:%PATH% VARIABLE:!X! ATTRIBUTE:/y KEYWORD:for VARIABLE:%%i KEYWORD:in PUNCTUATION:( PUNCTUATION:) KEYWORD:do KEYWORD:echo VARIABLE:%%i",
            "@echo off\nREM comment\n:: also\nset VAR=1\nif exist \"%VAR%\" goto :end\n:end\necho %PATH% !X! /y\nfor %%i in (a) do echo %%i",
            Language.BATCH,
        )
        assertTokens("BUILTIN:xcopy ATTRIBUTE:/s ATTRIBUTE:/e PUNCTUATION:&& BUILTIN:dir COMMENT:rem x", "xcopy a b /s /e && dir\n  rem x", Language.BATCH)
    }

    @Test fun `log timestamps levels exceptions ips and urls`() {
        assertTokens(
            "NUMBER:2024-01-02 NUMBER:12:34:56.789 ERROR:ERROR ERROR:NullPointerException LINK:192.168.0.1 LINK:http://x.y/z NUMBER:2024-01-02T12:34:56Z KEYWORD:WARN COMMENT:INFO NUMBER:1700000000",
            "2024-01-02 12:34:56.789 [main] ERROR com.foo.Bar - failed: java.lang.NullPointerException at 192.168.0.1 http://x.y/z\n2024-01-02T12:34:56Z WARN x\nINFO ok 1700000000",
            Language.LOG,
        )
        assertTokens("COMMENT:DEBUG ERROR:FATAL ERROR:Exception", "DEBUG x FATAL y Exception in thread", Language.LOG)
    }

    @Test fun `plain text has no tokens`() {
        assertTrue(SyntaxHighlighter.tokenize("def x(): { \"a\" } // b", Language.PLAIN).isEmpty())
        assertEquals(emptyList<Token>(), SyntaxHighlighter.tokenize("", Language.PLAIN))
    }
}
