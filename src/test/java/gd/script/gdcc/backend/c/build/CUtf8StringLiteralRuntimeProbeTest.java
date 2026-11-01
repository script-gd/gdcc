package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.parser.DomLirParser;
import gd.script.gdcc.lir.parser.DomLirSerializer;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.lir.LirParameterDef;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Runtime proof for the length-aware `literal_string` emission: an `a\0b` payload travels
/// DOM serialize -> DOM parse -> C codegen -> real C compile/run. The generated call must hand
/// the fake engine wrapper exactly three UTF-8 bytes so the tail `b` after the embedded NUL
/// survives — a NUL-terminated `utf8_chars` call could not express that.
class CUtf8StringLiteralRuntimeProbeTest {
    @TempDir
    private Path tempDir;

    @Test
    void literalStringWithEmbeddedNulReachesRuntimeWithFullByteLength() throws Exception {
        var callExpr = emitUtf8CharsAndLenCall("a\0b");
        var probe = """
                #include <string.h>
                #include <stdio.h>
                typedef long long godot_int;
                typedef struct { int tag; } godot_String;
                static unsigned char captured[64];
                static godot_int captured_len = -1;
                static godot_String godot_new_String_with_utf8_chars_and_len(const char *p, godot_int n) {
                    godot_String s = {0};
                    memcpy(captured, p, (size_t)n);
                    captured_len = n;
                    return s;
                }
                int main(void) {
                    godot_String s = %s;
                    (void)s;
                    if (captured_len != 3) { printf("bad len %%lld\\n", captured_len); return 1; }
                    if (captured[0] != 'a' || captured[1] != 0 || captured[2] != 'b') return 2;
                    printf("OK len=3 tail=b\\n");
                    return 0;
                }
                """.formatted(callExpr);
        runOnAvailableToolchains(probe);
    }

    @Test
    void literalStringWithMultibytePayloadPassesUtf8ByteCount() throws Exception {
        var callExpr = emitUtf8CharsAndLenCall("é中");
        var probe = """
                #include <string.h>
                #include <stdio.h>
                typedef long long godot_int;
                typedef struct { int tag; } godot_String;
                static unsigned char captured[64];
                static godot_int captured_len = -1;
                static godot_String godot_new_String_with_utf8_chars_and_len(const char *p, godot_int n) {
                    godot_String s = {0};
                    memcpy(captured, p, (size_t)n);
                    captured_len = n;
                    return s;
                }
                int main(void) {
                    godot_String s = %s;
                    (void)s;
                    /* "é中" is 2 UTF-16 units / 2 code points but 5 UTF-8 bytes. */
                    if (captured_len != 5) { printf("bad len %%lld\\n", captured_len); return 1; }
                    if (captured[0] != 0xC3 || captured[1] != 0xA9
                            || captured[2] != 0xE4 || captured[3] != 0xB8 || captured[4] != 0xAD) return 2;
                    printf("OK utf8 bytes=5\\n");
                    return 0;
                }
                """.formatted(callExpr);
        runOnAvailableToolchains(probe);
    }

    /// Builds a one-instruction module, pushes it through the DOM XML roundtrip, re-parses the
    /// `literal_string`, generates its C call and extracts the `..._and_len(...)` expression
    /// text for embedding into a probe TU.
    private static @org.jetbrains.annotations.NotNull String emitUtf8CharsAndLenCall(String payload) throws Exception {
        var clazz = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("emit", "entry");
        func.setReturnType(GdVoidType.VOID);
        func.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, func));
        func.createAndAddVariable("0", GdStringType.STRING);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LiteralStringInsn("0", payload));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        clazz.addFunction(func);
        var module = new LirModule("probe_module", List.of(clazz));

        var api = ExtensionApiLoader.loadDefault();
        var xml = new DomLirSerializer().serializeToString(module);
        var parsedModule = new DomLirParser(new ClassRegistry(api)).parse(new StringReader(xml));

        var parsedClass = parsedModule.getClassDefs().getFirst();
        var parsedFunc = parsedClass.getFunctions().getFirst();
        var parsedLiteral = assertInstanceOf(LiteralStringInsn.class,
                parsedFunc.getBasicBlock("entry").getNonTerminatorInstructions().getFirst());
        assertEquals(payload, parsedLiteral.value());

        var ctx = new CodegenContext(
                new ProjectInfo("ProbeProject", GodotVersion.V451, Path.of(".")) {},
                new ClassRegistry(api)
        );
        var codegen = new CCodegen();
        codegen.prepare(ctx, parsedModule);
        var body = codegen.generateFuncBody(parsedClass, parsedFunc);

        var marker = "godot_new_String_with_utf8_chars_and_len(";
        var start = body.indexOf(marker);
        assertTrue(start >= 0, () -> "expected length-aware wrapper in generated body:\n" + body);
        var end = body.indexOf(')', start);
        assertTrue(end > start, () -> "malformed generated call:\n" + body);
        return body.substring(start, end + 1);
    }

    private void runOnAvailableToolchains(String probeSource) throws Exception {
        var ran = false;
        // `List.of` rejects null, so the nullable discovery results ride a plain array.
        for (var toolchain : new RuntimeProbeToolchain[]{
                RuntimeProbeToolchain.zig(), RuntimeProbeToolchain.tcc()}) {
            if (toolchain == null) {
                continue;
            }
            ran = true;
            var run = toolchain.compileLinkAndRun(tempDir, "utf8_literal", probeSource, List.of(), List.of());
            assertEquals(0, run.exitCode(), run::diagnostic);
            assertTrue(run.output().contains("OK"), run::diagnostic);
        }
        Assumptions.assumeTrue(ran, "no C toolchain (zig/tcc) available for the runtime probe");
    }
}
