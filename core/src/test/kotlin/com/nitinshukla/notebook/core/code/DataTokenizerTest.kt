package com.nitinshukla.notebook.core.code

import org.junit.Test

class DataTokenizerTest {
    @Test fun `html tags attributes entities comments and script left plain`() {
        assertTokens(
            "DECORATOR:<!DOCTYPE html> PUNCTUATION:< TAG:div ATTRIBUTE:class OPERATOR:= STRING:\"a\" ATTRIBUTE:id OPERATOR:= STRING:'b' ATTRIBUTE:data-x PUNCTUATION:> CONSTANT:&amp; PUNCTUATION:</ TAG:div PUNCTUATION:> " +
                "COMMENT:<!-- c --> PUNCTUATION:< TAG:script PUNCTUATION:> PUNCTUATION:</ TAG:script PUNCTUATION:> PUNCTUATION:< TAG:br PUNCTUATION:/>",
            "<!DOCTYPE html>\n<div class=\"a\" id='b' data-x>&amp; text</div><!-- c --><script>var x = 1 < 2;</script><br/>",
            Language.HTML,
        )
        assertTokens("PUNCTUATION:< TAG:a ATTRIBUTE:href OPERATOR:= STRING:\"open", "<a href=\"open", Language.HTML)
    }

    @Test fun `xml declaration namespaces and cdata`() {
        assertTokens(
            "DECORATOR:<?xml version=\"1.0\"?> PUNCTUATION:< TAG:a:root ATTRIBUTE:xmlns:a OPERATOR:= STRING:\"u\" PUNCTUATION:> STRING:<![CDATA[x < y]]> PUNCTUATION:</ TAG:a:root PUNCTUATION:>",
            "<?xml version=\"1.0\"?>\n<a:root xmlns:a=\"u\"><![CDATA[x < y]]></a:root>",
            Language.XML,
        )
    }

    @Test fun `css selectors properties values and at rules`() {
        assertTokens(
            "KEYWORD:@media TAG:screen PUNCTUATION:{ TAG:a OPERATOR:: TAG:hover PUNCTUATION:, TAG:.cls OPERATOR:> TAG:#id PUNCTUATION:{ " +
                "ATTRIBUTE:color OPERATOR:: CONSTANT:#fff KEYWORD:!important PUNCTUATION:; ATTRIBUTE:margin OPERATOR:: NUMBER:1.5em NUMBER:0 PUNCTUATION:; " +
                "ATTRIBUTE:background OPERATOR:: FUNCTION:url PUNCTUATION:( PUNCTUATION:) PUNCTUATION:; VARIABLE:--v OPERATOR:: NUMBER:1 PUNCTUATION:} PUNCTUATION:} COMMENT:/* c */",
            "@media screen {\n  a:hover, .cls > #id { color: #fff !important; margin: 1.5em 0; background: url(x.png); --v: 1 }\n}\n/* c */",
            Language.CSS,
        )
        assertTokens("TAG:.a PUNCTUATION:{ ATTRIBUTE:width OPERATOR:: NUMBER:50% PUNCTUATION:; ATTRIBUTE:font OPERATOR:: STRING:\"Fira\" PUNCTUATION:}", ".a { width: 50%; font: \"Fira\" }", Language.CSS)
    }

    @Test fun `json keys values and constants`() {
        assertTokens(
            "PUNCTUATION:{ ATTRIBUTE:\"a\" PUNCTUATION:: PUNCTUATION:[ NUMBER:1 PUNCTUATION:, NUMBER:-2.5e3 PUNCTUATION:, CONSTANT:true PUNCTUATION:, CONSTANT:null PUNCTUATION:] PUNCTUATION:, " +
                "ATTRIBUTE:\"b\" PUNCTUATION:: PUNCTUATION:{ ATTRIBUTE:\"c\" PUNCTUATION:: STRING:\"d\" PUNCTUATION:} PUNCTUATION:} COMMENT:// c",
            "{\"a\": [1, -2.5e3, true, null], \"b\": {\"c\": \"d\"}} // c",
            Language.JSON,
        )
        assertTokens("PUNCTUATION:{ ATTRIBUTE:\"k\" PUNCTUATION:: STRING:\"unterminated PUNCTUATION:}", "{\"k\": \"unterminated\n}", Language.JSON)
    }

    @Test fun `yaml keys anchors constants and document markers`() {
        assertTokens(
            "PUNCTUATION:--- ATTRIBUTE:key PUNCTUATION:: COMMENT:# c PUNCTUATION:- ATTRIBUTE:item PUNCTUATION:: VARIABLE:&anc NUMBER:1 PUNCTUATION:- VARIABLE:*anc " +
                "ATTRIBUTE:list PUNCTUATION:: PUNCTUATION:[ PUNCTUATION:, ATTRIBUTE:b PUNCTUATION:: NUMBER:2 PUNCTUATION:] ATTRIBUTE:\"q\" PUNCTUATION:: STRING:'x' ATTRIBUTE:bool PUNCTUATION:: CONSTANT:yes " +
                "ATTRIBUTE:block PUNCTUATION:: OPERATOR:| PUNCTUATION:...",
            "---\nkey: value # c\n- item: &anc 1\n- *anc\nlist: [a, b: 2]\n\"q\": 'x'\nbool: yes\nblock: |\n  text\n...",
            Language.YAML,
        )
        assertTokens("ATTRIBUTE:url PUNCTUATION:: LINK:http://x.y/z ATTRIBUTE:time PUNCTUATION:: NUMBER:12:30", "url: http://x.y/z\ntime: 12:30", Language.YAML)
    }

    @Test fun `toml sections keys and typed values`() {
        assertTokens(
            "HEADING:[section.sub] ATTRIBUTE:key OPERATOR:= STRING:\"value\" COMMENT:# c ATTRIBUTE:n OPERATOR:= NUMBER:1_000 ATTRIBUTE:d OPERATOR:= NUMBER:1979-05-27T07:32:00Z " +
                "ATTRIBUTE:arr OPERATOR:= PUNCTUATION:[ NUMBER:1 PUNCTUATION:, CONSTANT:true PUNCTUATION:] ATTRIBUTE:inline OPERATOR:= PUNCTUATION:{ ATTRIBUTE:a OPERATOR:= NUMBER:1 PUNCTUATION:}",
            "[section.sub]\nkey = \"value\" # c\nn = 1_000\nd = 1979-05-27T07:32:00Z\narr = [1, true]\ninline = { a = 1 }",
            Language.TOML,
        )
        assertTokens("HEADING:[[arr]] ATTRIBUTE:s OPERATOR:= STRING:'''multi\nline'''", "[[arr]]\ns = '''multi\nline'''", Language.TOML)
    }

    @Test fun `ini sections keys comments and values`() {
        assertTokens(
            "HEADING:[core] COMMENT:; c ATTRIBUTE:name OPERATOR:= ATTRIBUTE:flag OPERATOR:= CONSTANT:true COMMENT:# c2 ATTRIBUTE:url OPERATOR:= LINK:http://x.y ATTRIBUTE:port OPERATOR:: NUMBER:80",
            "[core]\n; c\nname = value\nflag=true\n# c2\nurl = http://x.y\nport: 80",
            Language.INI,
        )
        assertTokens("ATTRIBUTE:SECRET OPERATOR:= STRING:\"a b\" COMMENT:# c ATTRIBUTE:*.log", "SECRET=\"a b\" # c\n*.log", Language.INI)
    }

    @Test fun `markdown headings emphasis code links lists quotes and fences`() {
        assertTokens(
            "HEADING:# Title KEYWORD:**bold** KEYWORD:_em_ STRING:`code` LINK:[link](http://x) LINK:https://y.z PUNCTUATION:- PUNCTUATION:1. COMMENT:> quote STRING:```kt\nval x = 1\n``` PUNCTUATION:---",
            "# Title\n\nSome **bold** and _em_ and `code` and [link](http://x) https://y.z.\n- item\n1. one\n> quote\n```kt\nval x = 1\n```\n---\nsnake_case_x",
            Language.MARKDOWN,
        )
        assertTokens("STRING:```\nnever closed\n", "```\nnever closed\n", Language.MARKDOWN)
        assertTokens("PUNCTUATION:- PUNCTUATION:[x] KEYWORD:*a* LINK:![img](a.png)", "- [x] *a* ![img](a.png) 2 * 3 * 4", Language.MARKDOWN)
        assertTokens("", "a * b unclosed_underscore", Language.MARKDOWN)
    }
}
