package com.nitinshukla.notebook.core.code

/** [CLikeSpec]s for the languages tokenized by [CLikeScanner]. */
internal object LanguageSpecs {
    private fun words(s: String): Set<String> = s.split(' ', '\n').filter { it.isNotEmpty() }.toHashSet()

    private val cTypes = words("void int char short long float double signed unsigned bool size_t ssize_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t uintptr_t intptr_t ptrdiff_t wchar_t FILE auto")

    val python = CLikeSpec(
        keywords = words("and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case"),
        types = words("Exception BaseException ValueError TypeError KeyError IndexError RuntimeError StopIteration AttributeError ImportError OSError IOError NotImplementedError ZeroDivisionError object"),
        builtins = words("print len range str int float list dict set tuple open isinstance issubclass enumerate zip map filter sorted reversed super type input abs all any bin bool bytes bytearray callable chr complex dir divmod eval exec format frozenset getattr setattr hasattr delattr globals locals hash hex id iter next max min oct ord pow repr round slice sum vars __import__ staticmethod classmethod property"),
        constants = words("True False None NotImplemented Ellipsis"),
        variables = words("self cls"),
        functionAfter = words("def"),
        typeAfter = words("class"),
        lineComments = listOf("#"),
        blockComment = null,
        stringQuotes = "\"'",
        charQuote = false,
        tripleQuotes = true,
        stringPrefixes = words("r b f u rb br fr rf"),
    )

    private val kotlinKeywords = words("abstract actual annotation as break by catch class companion const constructor continue crossinline data do dynamic else enum expect external final finally for fun get if import in infix init inline inner interface internal is lateinit noinline object open operator out override package private protected public reified return sealed set super suspend tailrec this throw try typealias typeof val value var vararg when where while field it")
    private val kotlinTypes = words("Int Long Short Byte Float Double Boolean Char String Unit Any Nothing Array List MutableList Map MutableMap Set MutableSet Pair Triple IntArray LongArray ByteArray CharSequence Iterable Sequence Collection Throwable Exception Error Number Comparable Lazy")
    private val kotlinBuiltins = words("println print listOf mutableListOf mapOf mutableMapOf setOf mutableSetOf arrayOf emptyList emptyMap emptySet require check error TODO lazy let also apply run with takeIf takeUnless repeat buildList buildMap buildString sequence flow launch async withContext runBlocking")

    val kotlin = CLikeSpec(
        keywords = kotlinKeywords,
        types = kotlinTypes,
        builtins = kotlinBuiltins,
        constants = words("true false null"),
        functionAfter = words("fun"),
        typeAfter = words("class interface object enum typealias"),
        nestedComments = true,
        tripleQuotes = true,
        tripleEscapes = false,
    )

    val java = CLikeSpec(
        keywords = words("abstract assert break case catch class const continue default do else enum extends final finally for goto if implements import instanceof interface native new package private protected public return static strictfp super switch synchronized this throw throws transient try volatile while var record sealed permits yield non-sealed"),
        types = words("void int long short byte float double boolean char String Object Integer Long Short Byte Float Double Boolean Character List Map Set ArrayList HashMap HashSet Optional Exception RuntimeException Throwable Error Iterable Collection StringBuilder Thread Runnable"),
        builtins = words("System out err println print printf length size"),
        constants = words("true false null"),
        typeAfter = words("class interface enum record extends implements new"),
        tripleQuotes = true,
    )

    val c = CLikeSpec(
        keywords = words("auto break case const continue default do else enum extern for goto if inline register restrict return sizeof static struct switch typedef union volatile while _Bool _Complex _Atomic _Static_assert _Thread_local _Alignas _Alignof _Generic _Noreturn"),
        types = cTypes,
        builtins = words("printf fprintf sprintf snprintf scanf malloc calloc realloc free memcpy memset strlen strcpy strncpy strcmp strcat fopen fclose fread fwrite puts putchar getchar exit abort assert"),
        constants = words("NULL EOF true false stdin stdout stderr"),
        typeAfter = words("struct union enum"),
        preprocessor = true,
        decorators = false,
        stringPrefixes = words("l u u8"),
    )

    val cpp = CLikeSpec(
        keywords = words("alignas alignof and and_eq asm auto bitand bitor break case catch class compl concept const consteval constexpr constinit const_cast continue co_await co_return co_yield decltype default delete do dynamic_cast else enum explicit export extern final for friend goto if import inline module mutable namespace new noexcept not not_eq operator or or_eq override private protected public register reinterpret_cast requires return sizeof static static_assert static_cast struct switch template this thread_local throw try typedef typeid typename union using virtual volatile while xor xor_eq"),
        types = cTypes + words("string wstring vector map set unordered_map unordered_set list deque array pair tuple optional variant shared_ptr unique_ptr weak_ptr string_view span function istream ostream ifstream ofstream stringstream"),
        builtins = words("std cout cin cerr endl printf malloc free memcpy memset strlen sizeof move forward make_shared make_unique make_pair swap begin end size push_back emplace_back"),
        constants = words("NULL nullptr true false EOF"),
        typeAfter = words("struct union enum class typename"),
        preprocessor = true,
        decorators = false,
        stringPrefixes = words("l u u8 r lr ur u8r"),
        cppRawStrings = true,
    )

    val csharp = CLikeSpec(
        keywords = words("abstract as async await base break case catch checked class const continue default delegate do else enum event explicit extern finally fixed for foreach goto if implicit in interface internal is lock namespace new operator out override params private protected public readonly ref return sealed sizeof stackalloc static struct switch this throw try typeof unchecked unsafe using virtual volatile while var dynamic get set value where yield partial record init required when with nameof global add remove select from orderby group into join let on equals by ascending descending"),
        types = words("void int uint long ulong short ushort byte sbyte float double decimal bool char string object String Object Int32 Int64 Boolean Double List Dictionary IEnumerable IList Task Action Func Exception DateTime TimeSpan Guid HashSet Nullable StringBuilder Console"),
        builtins = words("Console WriteLine Write ReadLine ToString Length Count"),
        constants = words("true false null"),
        typeAfter = words("class interface struct enum record namespace new"),
        preprocessor = true,
        decorators = false,
        csharpStrings = true,
    )

    private val jsKeywords = words("async await break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new of return static super switch this throw try typeof var void while with yield get set from as")
    private val jsBuiltins = words("console log warn error document window require module exports Math JSON parseInt parseFloat setTimeout setInterval clearTimeout clearInterval fetch alert isNaN isFinite encodeURIComponent decodeURIComponent process globalThis")
    private val jsTypes = words("Object Array String Number Boolean Symbol Date RegExp Map Set WeakMap WeakSet Promise Error TypeError RangeError Function BigInt Proxy Reflect ArrayBuffer Uint8Array Int32Array Float64Array")

    val javascript = CLikeSpec(
        keywords = jsKeywords,
        types = jsTypes,
        builtins = jsBuiltins,
        constants = words("true false null undefined NaN Infinity"),
        functionAfter = words("function"),
        typeAfter = words("class extends new"),
        stringQuotes = "\"'",
        charQuote = false,
        backtickEscapes = true,
        extraIdentStart = "$",
    )

    val typescript = CLikeSpec(
        keywords = jsKeywords + words("abstract any as asserts declare enum implements interface is keyof module namespace never override private protected public readonly satisfies type unique unknown infer out"),
        types = jsTypes + words("string number boolean any void never unknown object bigint symbol Record Partial Required Readonly Pick Omit Exclude Extract ReturnType Parameters Awaited"),
        builtins = jsBuiltins,
        constants = words("true false null undefined NaN Infinity"),
        functionAfter = words("function"),
        typeAfter = words("class interface type enum extends implements new namespace"),
        stringQuotes = "\"'",
        charQuote = false,
        backtickEscapes = true,
        extraIdentStart = "$",
    )

    val go = CLikeSpec(
        keywords = words("break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var"),
        types = words("bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr any comparable"),
        builtins = words("append cap close complex copy delete imag len make new panic print println real recover fmt Println Printf Sprintf Errorf errors os io log context time strings strconv"),
        constants = words("true false nil iota"),
        functionAfter = words("func"),
        typeAfter = words("type struct interface chan map"),
        backtickEscapes = false,
    )

    val rust = CLikeSpec(
        keywords = words("as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait type unsafe use where while union macro_rules"),
        types = words("i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box Rc Arc RefCell Cell HashMap HashSet BTreeMap BTreeSet Mutex RwLock Some None Ok Err Default Clone Copy Debug Send Sync Sized Iterator IntoIterator Fn FnMut FnOnce"),
        builtins = words("println print eprintln eprint format vec panic assert assert_eq assert_ne todo unimplemented unreachable dbg write writeln include_str env matches drop std core alloc"),
        constants = words("true false"),
        functionAfter = words("fn"),
        typeAfter = words("struct enum trait type impl union mod"),
        nestedComments = true,
        stringPrefixes = words("b r br c cr"),
        rustRawStrings = true,
        rustAttributes = true,
        decorators = false,
        macroBang = true,
        lifetimes = true,
    )

    val php = CLikeSpec(
        keywords = words("abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile enum extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield"),
        types = words("int float string bool array object mixed void null iterable callable self parent static never Exception Throwable Closure Generator ArrayObject DateTime PDO"),
        builtins = words("echo print count strlen str_replace array_map array_filter array_keys array_values in_array implode explode isset unset empty die exit var_dump print_r json_encode json_decode sprintf printf substr strpos trim strtolower strtoupper preg_match preg_replace is_array is_string is_int is_null function_exists class_exists define defined"),
        constants = words("true false null php_eol php_version __class__ __function__ __method__ __file__ __line__ __dir__ __namespace__"),
        functionAfter = words("function fn"),
        typeAfter = words("class interface trait enum extends implements new instanceof"),
        lineComments = listOf("//", "#"),
        stringQuotes = "\"'",
        charQuote = false,
        stringsSpanLines = true,
        variablePrefixes = "$",
        decorators = false,
        caseInsensitive = true,
        phpTags = true,
    )

    val swift = CLikeSpec(
        keywords = words("associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private precedencegroup protocol public rethrows static struct subscript typealias var break case catch continue default defer do else fallthrough for guard if in repeat return throw switch where while as any await is nil super self Self throws try async some actor nonisolated convenience dynamic final indirect lazy mutating nonmutating optional override required unowned weak willSet didSet get set"),
        types = words("Int Int8 Int16 Int32 Int64 UInt UInt8 UInt16 UInt32 UInt64 Float Double Bool String Character Array Dictionary Set Optional Any AnyObject Void Never Error Result Data Date URL Codable Equatable Hashable Comparable Identifiable ObservableObject View"),
        builtins = words("print debugPrint fatalError precondition assert min max abs map filter reduce compactMap flatMap forEach sorted contains append count isEmpty first last"),
        constants = words("true false nil"),
        functionAfter = words("func"),
        typeAfter = words("class struct enum protocol extension typealias actor"),
        nestedComments = true,
        tripleQuotes = true,
        charQuote = false,
        stringQuotes = "\"'",
    )

    val ruby = CLikeSpec(
        keywords = words("alias and begin break case class def defined? do else elsif end ensure for if in module next not or redo rescue retry return self super then undef unless until when while yield require require_relative include extend attr_accessor attr_reader attr_writer private protected public raise lambda proc loop module_function"),
        types = words("Integer Float String Symbol Array Hash Range Proc Object Class Module Kernel Comparable Enumerable NilClass TrueClass FalseClass Exception StandardError ArgumentError RuntimeError IO File Dir Time Struct Set"),
        builtins = words("puts print p pp gets require_relative each map select reject reduce inject times upto downto step new to_s to_i to_a to_h to_sym length size empty? nil? is_a? respond_to? freeze frozen? dup clone send instance_variable_get block_given? format sprintf printf"),
        constants = words("true false nil __FILE__ __LINE__ __method__"),
        variables = words("self"),
        functionAfter = words("def"),
        typeAfter = words("class module"),
        lineComments = listOf("#"),
        blockComment = null,
        stringQuotes = "\"'",
        charQuote = false,
        stringsSpanLines = true,
        decorators = false,
        rubyVariables = true,
        rubySymbols = true,
        rubyMethodSuffix = true,
        rubyBlockComments = true,
        backtickEscapes = true,
    )

    val sql = CLikeSpec(
        keywords = words("select from where insert into values update set delete create table drop alter add column index view trigger procedure function returns return begin end declare as join inner left right full outer cross on and or not null is in exists between like ilike case when then else end if group by order having limit offset union all distinct asc desc primary key foreign references constraint unique check default auto_increment autoincrement cascade truncate grant revoke commit rollback transaction with recursive over partition window rows range preceding following current row fetch first next only top explain analyze vacuum describe show use database schema temporary temp replace exec execute call merge using matched output except intersect natural lateral materialized sequence type cast collate escape"),
        types = words("int integer smallint bigint tinyint decimal numeric float real double precision boolean bool char varchar nvarchar nchar text clob blob binary varbinary date time timestamp timestamptz datetime interval json jsonb uuid serial bigserial money bit xml array enum bytea"),
        builtins = words("count sum avg min max coalesce nullif ifnull isnull length char_length upper lower trim ltrim rtrim substring substr concat replace round floor ceil ceiling abs now current_date current_time current_timestamp date_part extract date_trunc to_char to_date cast convert row_number rank dense_rank lag lead first_value last_value string_agg group_concat array_agg json_agg greatest least random exists any some"),
        constants = words("true false null unknown"),
        typeAfter = words("table view index database schema"),
        lineComments = listOf("--", "#"),
        stringQuotes = "'",
        charQuote = false,
        stringsSpanLines = true,
        sqlStrings = true,
        quotedIdentifiers = "\"`",
        variablePrefixes = "@",
        decorators = false,
        caseInsensitive = true,
        capitalizedIsType = false,
        allCapsIsConstant = false,
    )

    val powershell = CLikeSpec(
        keywords = words("begin break catch class continue data define do dynamicparam else elseif end enum exit filter finally for foreach from function hidden if in inlinescript parallel param process return sequence static switch throw trap try until using var while workflow configuration"),
        types = words("string int long double bool char byte array hashtable datetime switch object void psobject pscustomobject scriptblock regex xml"),
        builtins = words("echo write-host write-output write-error get-childitem get-content set-content get-item set-location get-location foreach-object where-object select-object sort-object measure-object out-null out-file import-module new-object get-process stop-process start-process invoke-expression invoke-webrequest invoke-restmethod test-path join-path split-path get-date read-host"),
        constants = words("true false null"),
        functionAfter = words("function filter workflow"),
        typeAfter = words("class enum"),
        lineComments = listOf("#"),
        blockComment = "<#" to "#>",
        stringQuotes = "\"'",
        charQuote = false,
        stringsSpanLines = true,
        variablePrefixes = "$",
        decorators = false,
        caseInsensitive = true,
        dashedIdentifiers = true,
        options = true,
        operatorWords = words("-eq -ne -gt -ge -lt -le -like -notlike -match -notmatch -contains -notcontains -in -notin -replace -and -or -not -xor -band -bor -bnot -bxor -is -isnot -as -join -split -f -shl -shr -ceq -cne -cgt -clt -ieq -ine"),
        capitalizedIsType = false,
    )

    val gradle = CLikeSpec(
        keywords = kotlinKeywords + words("def assert new instanceof trait extends implements switch case default static synchronized transient volatile throws boolean"),
        types = kotlinTypes,
        builtins = kotlinBuiltins + words("plugins dependencies repositories implementation api testImplementation androidTestImplementation debugImplementation kapt ksp annotationProcessor compileOnly runtimeOnly classpath android kotlin id version apply alias allprojects subprojects task tasks register sourceSets buildscript mavenCentral google maven gradlePluginPortal mavenLocal ext project rootProject defaultConfig buildTypes release debug compileSdk minSdk targetSdk namespace applicationId versionCode versionName proguardFiles getDefaultProguardFile isMinifyEnabled minifyEnabled compileOptions kotlinOptions buildFeatures composeOptions packaging dependencyResolutionManagement pluginManagement include libs platform files fileTree exclude group name url uri jvmTarget sourceCompatibility targetCompatibility testOptions lint signingConfigs"),
        constants = words("true false null"),
        functionAfter = words("fun def"),
        typeAfter = words("class interface object enum"),
        nestedComments = true,
        stringQuotes = "\"'",
        charQuote = false,
        tripleQuotes = true,
    )

    fun forLanguage(language: Language): CLikeSpec? = when (language) {
        Language.PYTHON -> python
        Language.KOTLIN -> kotlin
        Language.JAVA -> java
        Language.C -> c
        Language.CPP -> cpp
        Language.CSHARP -> csharp
        Language.JAVASCRIPT -> javascript
        Language.TYPESCRIPT -> typescript
        Language.GO -> go
        Language.RUST -> rust
        Language.PHP -> php
        Language.SWIFT -> swift
        Language.RUBY -> ruby
        Language.SQL -> sql
        Language.POWERSHELL -> powershell
        Language.GRADLE -> gradle
        else -> null
    }
}
