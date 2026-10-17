package com.nitinshukla.notebook.core.code

import org.junit.Test

class CLikeTokenizerTest {
    @Test fun `python keywords builtins decorators and functions`() {
        assertTokens(
            "DECORATOR:@dataclass KEYWORD:class TYPE:Foo PUNCTUATION:( TYPE:Base PUNCTUATION:) OPERATOR:: " +
                "KEYWORD:def FUNCTION:main PUNCTUATION:( VARIABLE:self PUNCTUATION:, OPERATOR:: BUILTIN:int OPERATOR:= NUMBER:0x1F PUNCTUATION:) OPERATOR:-> CONSTANT:None OPERATOR:: " +
                "BUILTIN:print PUNCTUATION:( STRING:f\"hi {x}\" PUNCTUATION:, NUMBER:1_000j PUNCTUATION:) COMMENT:# c KEYWORD:return CONSTANT:True",
            "@dataclass\nclass Foo(Base):\n    def main(self, x: int = 0x1F) -> None:\n        print(f\"hi {x}\", 1_000j)  # c\n        return True",
            Language.PYTHON,
        )
    }

    @Test fun `python triple quoted and prefixed strings`() {
        assertTokens(
            "OPERATOR:= STRING:r'''multi\nline''' OPERATOR:+ STRING:b\"x\" OPERATOR:+ STRING:'unterminated BUILTIN:next OPERATOR:= NUMBER:1.5e-3",
            "s = r'''multi\nline''' + b\"x\" + 'unterminated\nnext = 1.5e-3",
            Language.PYTHON,
        )
        assertTokens("OPERATOR:= STRING:\"\"\"never closed\nx = 1", "s = \"\"\"never closed\nx = 1", Language.PYTHON)
        assertTokens("STRING:'a' STRING:\"it's\" STRING:'say \\'hi\\''", "'a' \"it's\" 'say \\'hi\\''", Language.PYTHON)
    }

    @Test fun `python numbers`() {
        assertTokens("NUMBER:0b1010 NUMBER:0o17 NUMBER:1_000_000 NUMBER:3.14 NUMBER:.5 NUMBER:2j NUMBER:1e10", "0b1010 0o17 1_000_000 3.14 .5 2j 1e10", Language.PYTHON)
    }

    @Test fun `kotlin annotations types raw strings chars and number suffixes`() {
        assertTokens(
            "DECORATOR:@Composable KEYWORD:fun FUNCTION:greet PUNCTUATION:( OPERATOR:: TYPE:String PUNCTUATION:) OPERATOR:: TYPE:Int PUNCTUATION:{ " +
                "KEYWORD:val CONSTANT:MAX OPERATOR:= NUMBER:10 COMMENT:// hi KEYWORD:val OPERATOR:= CHAR:'x' KEYWORD:val OPERATOR:= STRING:\"\"\"a\n\"b\"\"\" " +
                "KEYWORD:return BUILTIN:listOf PUNCTUATION:( NUMBER:1L PUNCTUATION:, NUMBER:2.0f PUNCTUATION:, NUMBER:0xFF PUNCTUATION:) OPERATOR:. PUNCTUATION:}",
            "@Composable\nfun greet(name: String): Int {\n    val MAX = 10 // hi\n    val c = 'x'\n    val raw = \"\"\"a\n\"b\"\"\"\n    return listOf(1L, 2.0f, 0xFF).size\n}",
            Language.KOTLIN,
        )
    }

    @Test fun `kotlin nested block comments and templates stay one token`() {
        assertTokens("COMMENT:/* a /* b */ c */ KEYWORD:val OPERATOR:= STRING:\"\$x and \${y.z}\"", "/* a /* b */ c */ val s = \"\$x and \${y.z}\"", Language.KOTLIN)
        assertTokens("KEYWORD:val OPERATOR:= STRING:\"open KEYWORD:val", "val s = \"open\nval", Language.KOTLIN)
    }

    @Test fun `java snippet`() {
        assertTokens(
            "KEYWORD:public KEYWORD:class TYPE:Main PUNCTUATION:{ KEYWORD:public KEYWORD:static TYPE:void FUNCTION:main PUNCTUATION:( TYPE:String PUNCTUATION:[ PUNCTUATION:] PUNCTUATION:) PUNCTUATION:{ " +
                "BUILTIN:System OPERATOR:. BUILTIN:out OPERATOR:. BUILTIN:println PUNCTUATION:( STRING:\"x\" PUNCTUATION:) PUNCTUATION:; TYPE:char OPERATOR:= CHAR:'\\n' PUNCTUATION:; PUNCTUATION:} PUNCTUATION:}",
            "public class Main {\n  public static void main(String[] args) { System.out.println(\"x\"); char c = '\\n'; }\n}",
            Language.JAVA,
        )
    }

    @Test fun `c preprocessor and comments`() {
        assertTokens(
            "DECORATOR:#include <stdio.h> DECORATOR:#define MAX 10 TYPE:int FUNCTION:main PUNCTUATION:( TYPE:void PUNCTUATION:) PUNCTUATION:{ " +
                "BUILTIN:printf PUNCTUATION:( STRING:\"%d\\n\" PUNCTUATION:, CONSTANT:MAX PUNCTUATION:) PUNCTUATION:; KEYWORD:return NUMBER:0 PUNCTUATION:; COMMENT:/* done */ PUNCTUATION:}",
            "#include <stdio.h>\n#define MAX 10\nint main(void) { printf(\"%d\\n\", MAX); return 0; /* done */ }",
            Language.C,
        )
        assertTokens("DECORATOR:#define A \\\n  1 TYPE:int", "#define A \\\n  1\nint", Language.C)
    }

    @Test fun `cpp raw strings prefixes and scope`() {
        assertTokens(
            "KEYWORD:auto OPERATOR:= STRING:R\"x(raw \" str)x\" PUNCTUATION:; BUILTIN:std OPERATOR::: TYPE:vector OPERATOR:< TYPE:int OPERATOR:> PUNCTUATION:; STRING:L\"wide\" PUNCTUATION:; STRING:u8\"a\" PUNCTUATION:;",
            "auto s = R\"x(raw \" str)x\"; std::vector<int> v; L\"wide\"; u8\"a\";",
            Language.CPP,
        )
    }

    @Test fun `csharp verbatim and interpolated strings and regions`() {
        assertTokens(
            "KEYWORD:var OPERATOR:= STRING:@\"c:\\path\" OPERATOR:+ STRING:\$\"{x}\" PUNCTUATION:; DECORATOR:#region A TYPE:Console OPERATOR:. BUILTIN:WriteLine PUNCTUATION:( PUNCTUATION:) PUNCTUATION:;",
            "var s = @\"c:\\path\" + \$\"{x}\";\n#region A\nConsole.WriteLine(s);",
            Language.CSHARP,
        )
        assertTokens("STRING:@\"multi\nline \"\"q\"\"\" PUNCTUATION:;", "@\"multi\nline \"\"q\"\"\";", Language.CSHARP)
    }

    @Test fun `javascript template literals and dollar identifiers`() {
        assertTokens(
            "KEYWORD:const OPERATOR:= KEYWORD:async PUNCTUATION:( PUNCTUATION:) OPERATOR:=> STRING:`hi \${x}` PUNCTUATION:; KEYWORD:let OPERATOR:= BUILTIN:document OPERATOR:. FUNCTION:getElementById PUNCTUATION:( STRING:'a' PUNCTUATION:) PUNCTUATION:; COMMENT:// c",
            "const f = async (x) => `hi \${x}`; let \$el = document.getElementById('a'); // c",
            Language.JAVASCRIPT,
        )
        assertTokens("STRING:`a\nb` CONSTANT:undefined", "`a\nb` undefined", Language.JAVASCRIPT)
        assertTokens("STRING:'no KEYWORD:let", "'no\nlet", Language.JAVASCRIPT)
    }

    @Test fun `typescript types and generics`() {
        assertTokens(
            "KEYWORD:interface TYPE:Foo PUNCTUATION:{ OPERATOR:: TYPE:number PUNCTUATION:} KEYWORD:function FUNCTION:bar OPERATOR:< TYPE:T OPERATOR:> PUNCTUATION:( OPERATOR:: TYPE:T PUNCTUATION:) OPERATOR:: TYPE:string PUNCTUATION:{ KEYWORD:return STRING:`\${x}` PUNCTUATION:; PUNCTUATION:}",
            "interface Foo { a: number }\nfunction bar<T>(x: T): string { return `\${x}`; }",
            Language.TYPESCRIPT,
        )
    }

    @Test fun `go raw strings and runes`() {
        assertTokens(
            "KEYWORD:func FUNCTION:main PUNCTUATION:( PUNCTUATION:) PUNCTUATION:{ BUILTIN:fmt OPERATOR:. BUILTIN:Println PUNCTUATION:( STRING:`raw\nstr` PUNCTUATION:, CONSTANT:nil PUNCTUATION:, CHAR:'x' PUNCTUATION:) PUNCTUATION:}",
            "func main() {\n\tfmt.Println(`raw\nstr`, nil, 'x')\n}",
            Language.GO,
        )
        assertTokens("KEYWORD:type TYPE:Server KEYWORD:struct PUNCTUATION:{ TYPE:string PUNCTUATION:}", "type Server struct { name string }", Language.GO)
    }

    @Test fun `rust attributes lifetimes macros and raw strings`() {
        assertTokens(
            "DECORATOR:#[derive(Debug)] KEYWORD:fn FUNCTION:main OPERATOR:< TYPE:'a OPERATOR:> PUNCTUATION:( OPERATOR:: OPERATOR:& TYPE:'a TYPE:str PUNCTUATION:) PUNCTUATION:{ " +
                "FUNCTION:println! PUNCTUATION:( STRING:\"{}\" PUNCTUATION:, PUNCTUATION:) PUNCTUATION:; KEYWORD:let OPERATOR:= CHAR:'c' PUNCTUATION:; KEYWORD:let OPERATOR:= STRING:r#\"raw\"# PUNCTUATION:; KEYWORD:let OPERATOR:= CHAR:b'a' PUNCTUATION:; PUNCTUATION:}",
            "#[derive(Debug)]\nfn main<'a>(x: &'a str) { println!(\"{}\", x); let c = 'c'; let r = r#\"raw\"#; let b = b'a'; }",
            Language.RUST,
        )
    }

    @Test fun `php tags variables and hash comments`() {
        assertTokens(
            "TAG:<?php VARIABLE:\$x OPERATOR:= KEYWORD:array PUNCTUATION:( NUMBER:1 PUNCTUATION:, NUMBER:2 PUNCTUATION:) PUNCTUATION:; COMMENT:# c KEYWORD:function FUNCTION:foo PUNCTUATION:( VARIABLE:\$a PUNCTUATION:) PUNCTUATION:{ KEYWORD:echo STRING:\"hi \$a\" PUNCTUATION:; PUNCTUATION:} TAG:?>",
            "<?php\n\$x = array(1, 2); # c\nfunction foo(\$a) { echo \"hi \$a\"; }\n?>",
            Language.PHP,
        )
    }

    @Test fun `swift multiline strings and attributes`() {
        assertTokens(
            "KEYWORD:func FUNCTION:greet PUNCTUATION:( OPERATOR:: TYPE:String PUNCTUATION:) OPERATOR:-> TYPE:String PUNCTUATION:{ KEYWORD:let OPERATOR:= STRING:\"\"\"\nhi\n\"\"\" PUNCTUATION:; KEYWORD:return PUNCTUATION:} DECORATOR:@main KEYWORD:struct TYPE:App PUNCTUATION:{ PUNCTUATION:}",
            "func greet(_ name: String) -> String { let s = \"\"\"\nhi\n\"\"\"; return s }\n@main struct App {}",
            Language.SWIFT,
        )
    }

    @Test fun `ruby symbols instance variables interpolation and block comments`() {
        assertTokens(
            "KEYWORD:class TYPE:Foo OPERATOR:< TYPE:Bar KEYWORD:def FUNCTION:hello? PUNCTUATION:( PUNCTUATION:) VARIABLE:@ivar OPERATOR:= CONSTANT::sym BUILTIN:puts STRING:\"hi #{x}\" COMMENT:# c KEYWORD:end KEYWORD:end COMMENT:=begin\nblock\n=end",
            "class Foo < Bar\n  def hello?(x)\n    @ivar = :sym\n    puts \"hi #{x}\" # c\n  end\nend\n=begin\nblock\n=end",
            Language.RUBY,
        )
    }

    @Test fun `sql case insensitive keywords doubled quotes and comments`() {
        assertTokens(
            "KEYWORD:SELECT BUILTIN:COUNT PUNCTUATION:( OPERATOR:* PUNCTUATION:) PUNCTUATION:, VARIABLE:\"Name\" KEYWORD:FROM KEYWORD:WHERE OPERATOR:= NUMBER:5 KEYWORD:AND OPERATOR:= STRING:'O''Brien' COMMENT:-- c COMMENT:/* b */ VARIABLE:@v",
            "SELECT COUNT(*), \"Name\" FROM users WHERE id = 5 AND name = 'O''Brien' -- c\n/* b */ @v",
            Language.SQL,
        )
        assertTokens("KEYWORD:select KEYWORD:from TYPE:varchar PUNCTUATION:( NUMBER:10 PUNCTUATION:)", "select x from t varchar(10)", Language.SQL)
    }

    @Test fun `powershell cmdlets parameters variables and comparison operators`() {
        assertTokens(
            "KEYWORD:function FUNCTION:Get-Thing PUNCTUATION:{ KEYWORD:param PUNCTUATION:( VARIABLE:\$Name PUNCTUATION:) FUNCTION:Write-Host ATTRIBUTE:-ForegroundColor STRING:\"hi \$Name\" PUNCTUATION:; " +
                "KEYWORD:if PUNCTUATION:( VARIABLE:\$a OPERATOR:-eq NUMBER:1 PUNCTUATION:) PUNCTUATION:{ PUNCTUATION:} PUNCTUATION:} COMMENT:<# b #> COMMENT:# c VARIABLE:\$env:PATH",
            "function Get-Thing { param(\$Name) Write-Host -ForegroundColor Red \"hi \$Name\" ; if (\$a -eq 1) { } } <# b #>\n# c\n\$env:PATH",
            Language.POWERSHELL,
        )
    }

    @Test fun `gradle groovy and kotlin dsl`() {
        assertTokens(
            "BUILTIN:plugins PUNCTUATION:{ BUILTIN:id STRING:'com.android.application' PUNCTUATION:} BUILTIN:dependencies PUNCTUATION:{ BUILTIN:implementation PUNCTUATION:( BUILTIN:libs OPERATOR:. PUNCTUATION:) PUNCTUATION:}",
            "plugins { id 'com.android.application' }\ndependencies { implementation(libs.foo) }",
            Language.GRADLE,
        )
    }

    @Test fun `unterminated block comment extends to end of text`() {
        assertTokens("KEYWORD:val COMMENT:/* never\nclosed", "val /* never\nclosed", Language.KOTLIN)
    }

    @Test fun `operators and punctuation`() {
        assertTokens("OPERATOR:== OPERATOR:&& OPERATOR:!= OPERATOR:+= OPERATOR:?: PUNCTUATION:; PUNCTUATION:,", "a == b && c != d += e ?: f; ,", Language.KOTLIN)
    }
}
