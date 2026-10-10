package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Portability surface gate for the shared C layer: the same sources must be accepted by both
/// zig cc (`-std=c23`) and tinycc (`-std=c11`), so C23-only constructs must never appear as code
/// tokens. Comment text and string-literal content are stripped before scanning, which keeps
/// illustrative comments (e.g. the `GD_STATIC_S` examples) and byte-string payloads (e.g. the
/// minicoro machine-code arrays, whose byte comments read `jmpq`) from counting as violations,
/// while a real `nullptr` or `__int128` in code position fails the build. The `u8"..."` prefix is
/// deliberately NOT checked here: the vendored tinycc patch (`src/main/c/tinycc/tccpp.c`, see
/// GDCC_PIN.md) accepts it as a transparent narrow-string prefix, covered by TinyCcU8StringTest.
/// Inline-assembly strings (minicoro.h) get the inverse treatment: their *content* is scanned for
/// the assembler forms tcc rejects (`jmpq`, `.type name @function` without the comma).
class CPortabilitySurfaceTest {
    private static final Path CODEGEN_ROOT = Path.of("src/main/c/codegen");
    private static final Path INCLUDE_ROOT = CODEGEN_ROOT.resolve("include_451");
    private static final Path TEMPLATE_ROOT = CODEGEN_ROOT.resolve("template_451");

    private static final Pattern NULLPTR_TOKEN = Pattern.compile("\\bnullptr\\b");
    private static final Pattern INT128_TOKEN = Pattern.compile("\\b__int128\\b");
    private static final Pattern ASM_JMPQ = Pattern.compile("\\bjmpq\\b");
    /// The rejected form has whitespace (not a comma) between symbol and @function.
    private static final Pattern ASM_TYPE_WITHOUT_COMMA = Pattern.compile("\\.type\\s+[^\\s,]+\\s+@function");

    @Test
    void includeAndTemplateTreesShouldCarryNoC23OnlyCodeTokens() throws IOException {
        var scannedFiles = 0;
        for (var root : List.of(INCLUDE_ROOT, TEMPLATE_ROOT)) {
            try (var walk = Files.walk(root)) {
                for (var file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    var name = file.getFileName().toString();
                    var isTemplate = name.endsWith(".ftl");
                    if (!isTemplate && !name.endsWith(".c") && !name.endsWith(".h")) {
                        continue;
                    }
                    var source = Files.readString(file, StandardCharsets.UTF_8);
                    var scan = scanC(isTemplate ? blankFreemarkerDirectives(source, file) : source, file);
                    assertCodePortable(scan.codeText(), file);
                    scannedFiles++;
                }
            }
        }
        // Presence anchor: a path mistake walking the trees must fail loudly, not pass vacuously.
        assertTrue(scannedFiles > 20, "expected to scan the real include/template trees, got " + scannedFiles);
    }

    @Test
    void minicoroInlineAssemblyShouldUseOnlyTccAcceptedForms() throws IOException {
        var minicoroHeader = INCLUDE_ROOT.resolve("gdcc/minicoro.h");
        var scan = scanC(Files.readString(minicoroHeader, StandardCharsets.UTF_8), minicoroHeader);
        var asmContent = String.join("\n", scan.stringContents());
        assertFalse(ASM_JMPQ.matcher(asmContent).find(), "jmpq is not accepted by tcc's inline assembler");
        assertFalse(ASM_TYPE_WITHOUT_COMMA.matcher(asmContent).find(),
                ".type needs the comma form `.type name, @function` for tcc");
        // Presence anchors: the patched forms must actually be there (guards against scanning the
        // wrong regions and passing vacuously).
        assertTrue(asmContent.contains(".type _mco_switch, @function"), "patched .type form missing");
        assertTrue(asmContent.contains(".Lmco_switch_resume"), "labeled resume address missing");
    }

    @Test
    void generatedEntryTuShouldCarryNoC23OnlyCodeTokens() throws IOException {
        for (var generated : generateFixtureModuleFiles()) {
            if (!generated.filePath().endsWith(".c") && !generated.filePath().endsWith(".h")) {
                continue;
            }
            var source = new String(generated.contentWriter(), StandardCharsets.UTF_8);
            var scan = scanC(source, Path.of(generated.filePath()));
            assertCodePortable(scan.codeText(), Path.of(generated.filePath()));
        }
    }

    @Test
    void scannerShouldIgnoreCommentsAndStringsButFlagCodeTokens() {
        var scan = scanC("""
                // u8"comment" nullptr __int128
                /* u8"block" nullptr __int128 jmpq */
                const char *s = "u8\\" text with nullptr and __int128";
                const char *cont = "line\\
                continuation stays content";
                const char *decode_u8 = "decode_u8";
                int code_u8_style = 0;
                """, Path.of("synthetic.c"));
        assertCodePortable(scan.codeText(), Path.of("synthetic.c"));

        var violating = scanC("""
                void *p = nullptr;
                __int128 wide = 0;
                __int128/**/glued = 0;
                """, Path.of("synthetic_bad.c"));
        // Two distinct __int128 tokens: a comment must act as a token separator, never glue.
        var int128Matcher = INT128_TOKEN.matcher(violating.codeText());
        assertTrue(int128Matcher.find());
        assertTrue(int128Matcher.find(), "comment-separated `__int128/**/x` must still be detected");
        assertTrue(NULLPTR_TOKEN.matcher(violating.codeText()).find());
    }

    @Test
    void scannerShouldNotBeEvadedByLineContinuationSplicing() {
        // C translation phase 2 deletes backslash-newline before tokens exist, so split spellings
        // of forbidden tokens must still be caught.
        var splicedViolation = scanC("""
                __int\\
                128 split = 0;
                """, Path.of("synthetic_splice.c"));
        assertTrue(INT128_TOKEN.matcher(splicedViolation.codeText()).find());
    }

    @Test
    void scannerShouldSpliceMixedLineEndingsOnlyOnce() {
        // Two backslashes + CRLF + a blank LF line: phase 2 splices only the `\`+CRLF pair; the
        // remaining LF still terminates the line comment, so the token on the last line is code.
        // A two-pass replace would wrongly splice again and swallow the token into the comment.
        var source = "// comment " + "\\\\" + "\r\n\n__int128 x;\n";
        var scan = scanC(source, Path.of("synthetic_mixed_splice.c"));
        assertTrue(INT128_TOKEN.matcher(scan.codeText()).find());
    }

    @Test
    void asmPatternsShouldDistinguishRejectedAndAcceptedForms() {
        assertTrue(ASM_JMPQ.matcher("  jmpq *(%rsi)\n").find());
        assertFalse(ASM_JMPQ.matcher("  jmp *(%rsi)\n").find());
        assertTrue(ASM_TYPE_WITHOUT_COMMA.matcher(".type _mco_switch @function\n").find());
        assertFalse(ASM_TYPE_WITHOUT_COMMA.matcher(".type _mco_switch, @function\n").find());
    }

    // ---------------------------------------------------------------------------

    private static void assertCodePortable(String codeText, Path file) {
        assertFalse(NULLPTR_TOKEN.matcher(codeText).find(),
                () -> file + ": nullptr is C23-only; use NULL");
        assertFalse(INT128_TOKEN.matcher(codeText).find(),
                () -> file + ": __int128 is not supported by tcc");
    }

    /// A module exercising the entry.c literal surface: class/property registration names plus a
    /// non-ASCII string literal (materialized as `u8"..."` with UCN escapes by escapeStringLiteral).
    private static List<GeneratedFile> generateFixtureModuleFiles() throws IOException {
        var projectInfo = new CProjectInfo("surfaceprobe", GodotVersion.V451,
                Path.of("unused"), COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var ctx = new CodegenContext(projectInfo, new ClassRegistry(api));

        var probeClass = new LirClassDef("GDSurfaceProbe", "RefCounted");
        probeClass.setSourceFile("surface_probe.gd");
        probeClass.addProperty(new LirPropertyDef("title", GdStringType.STRING, false, null, null, null, Map.of()));
        var greetFunc = new LirFunctionDef("greet", "entry");
        greetFunc.setReturnType(GdStringType.STRING);
        greetFunc.addParameter(new LirParameterDef("self", new GdObjectType("GDSurfaceProbe"), null, greetFunc));
        greetFunc.createAndAddVariable("0", GdStringType.STRING);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LiteralStringInsn("0", "café 中文"));
        entry.appendInstruction(new ReturnInsn("0"));
        greetFunc.addBasicBlock(entry);
        probeClass.addFunction(greetFunc);

        var codegen = new CCodegen();
        codegen.prepare(ctx, new LirModule("surface_probe_module", List.of(probeClass)));
        return codegen.generate();
    }

    /// FreeMarker directives are not C; their inner text is blanked (keeping delimiters so quote
    /// balance is preserved) before the C tokenizer runs, so only template *output text* is scanned.
    private static String blankFreemarkerDirectives(String source, Path file) {
        var withoutComments = source.replaceAll("(?s)<#--.*?-->", " ");
        var sb = new StringBuilder(withoutComments);
        var i = 0;
        while (i < sb.length()) {
            int open = indexOfDirectiveStart(sb, i);
            if (open < 0) {
                break;
            }
            int close = directiveEnd(sb, open);
            if (close < 0) {
                fail(file + ": unterminated FreeMarker directive at offset " + open);
            }
            for (var j = open + 2; j < close; j++) {
                if (sb.charAt(j) != '\n') {
                    sb.setCharAt(j, ' ');
                }
            }
            i = close;
        }
        return sb.toString();
    }

    private static int indexOfDirectiveStart(CharSequence text, int from) {
        for (var i = from; i + 1 < text.length(); i++) {
            if (text.charAt(i) == '<' && text.charAt(i + 1) == '#') {
                return i;
            }
            if (text.charAt(i) == '$' && text.charAt(i + 1) == '{') {
                return i;
            }
        }
        return -1;
    }

    private static int directiveEnd(CharSequence text, int open) {
        var terminator = text.charAt(open) == '<' ? '>' : '}';
        var i = open + 2;
        while (i < text.length()) {
            var c = text.charAt(i);
            if (c == '"' || c == '\'') {
                // FTL strings may embed the terminator (e.g. "self->"); skip the quoted span.
                var j = i + 1;
                while (j < text.length() && text.charAt(j) != c) {
                    j += text.charAt(j) == '\\' && j + 1 < text.length() ? 2 : 1;
                }
                if (j >= text.length()) {
                    return -1;
                }
                i = j + 1;
            } else if (c == terminator) {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    /// Minimal C source classifier: returns the concatenation of all code regions (comments,
    /// string-literal bodies and char-literal bodies removed) plus every string-literal body.
    /// Escapes are honored so a `\"` never leaves string state early. Fails loudly on unterminated
    /// constructs instead of silently scanning a misparsed remainder.
    private static ScanResult scanC(String source, Path file) {
        // C translation phase 2 deletes backslash-newline before tokenization, in a single pass;
        // splice the same way (one regex over the original input, replacements never rescanned)
        // so neither a split token nor a mixed CRLF/LF sequence can evade the surface checks.
        var spliced = source.replaceAll("\\\\(?:\\r\\n|\\n)", "");
        var code = new StringBuilder(spliced.length());
        var strings = new ArrayList<String>();
        var i = 0;
        var n = spliced.length();
        while (i < n) {
            var c = spliced.charAt(i);
            if (c == '/' && i + 1 < n && spliced.charAt(i + 1) == '/') {
                var end = spliced.indexOf('\n', i);
                // Comments act as token separators in C; keep the newline so `a//x\nb` never glues.
                code.append('\n');
                i = end < 0 ? n : end + 1;
            } else if (c == '/' && i + 1 < n && spliced.charAt(i + 1) == '*') {
                var end = spliced.indexOf("*/", i + 2);
                if (end < 0) {
                    fail(file + ": unterminated block comment at offset " + i);
                }
                code.append(' ');
                i = end + 2;
            } else if (c == '"' || c == '\'') {
                var literal = new StringBuilder();
                var j = i + 1;
                while (j < n && spliced.charAt(j) != c) {
                    if (spliced.charAt(j) == '\\' && j + 1 < n) {
                        literal.append(spliced.charAt(j)).append(spliced.charAt(j + 1));
                        j += 2;
                    } else {
                        literal.append(spliced.charAt(j));
                        j++;
                    }
                }
                if (j >= n) {
                    fail(file + ": unterminated literal at offset " + i);
                }
                if (c == '"') {
                    strings.add(literal.toString());
                }
                // Keep a visible separator so adjacent tokens across the literal never glue.
                code.append(' ');
                i = j + 1;
            } else {
                code.append(c);
                i++;
            }
        }
        return new ScanResult(code.toString(), strings);
    }

    private record ScanResult(String codeText, List<String> stringContents) {
    }
}
