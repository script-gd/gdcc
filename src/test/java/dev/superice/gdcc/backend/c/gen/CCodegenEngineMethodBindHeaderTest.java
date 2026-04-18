package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.GeneratedFile;
import dev.superice.gdcc.backend.ProjectInfo;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionAPI;
import dev.superice.gdcc.gdextension.ExtensionBuiltinClass;
import dev.superice.gdcc.gdextension.ExtensionFunctionArgument;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CCodegenEngineMethodBindHeaderTest {
    @Test
    @DisplayName("generate should emit bind header for exact engine methods only and keep entry call path unchanged")
    void generateShouldEmitBindHeaderForExactEngineMethodsOnlyAndKeepEntryCallPathUnchanged() {
        var hostClass = newClass("Worker", "RefCounted");

        var gdccPing = newVoidFunction("ping");
        gdccPing.createAndAddVariable("self", new GdObjectType("Worker"));
        entry(gdccPing).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(gdccPing);

        var instanceCall = newVoidFunction("call_instance");
        instanceCall.createAndAddVariable("probe", new GdObjectType("Probe"));
        entry(instanceCall).appendInstruction(new CallMethodInsn(null, "touch", "probe", List.of()));
        entry(instanceCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(instanceCall);

        var staticCall = newVoidFunction("call_static");
        staticCall.createAndAddVariable("probe", new GdObjectType("Probe"));
        staticCall.createAndAddVariable("label", GdStringType.STRING);
        entry(staticCall).appendInstruction(new CallMethodInsn(
                null,
                "touch",
                "probe",
                List.of(new dev.superice.gdcc.lir.LirInstruction.VariableOperand("label"))
        ));
        entry(staticCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(staticCall);

        var varargCall = newVoidFunction("call_vararg");
        varargCall.createAndAddVariable("probe", new GdObjectType("Probe"));
        varargCall.createAndAddVariable("head", GdIntType.INT);
        varargCall.createAndAddVariable("tail", GdVariantType.VARIANT);
        entry(varargCall).appendInstruction(new CallMethodInsn(
                null,
                "touch",
                "probe",
                List.of(
                        new dev.superice.gdcc.lir.LirInstruction.VariableOperand("head"),
                        new dev.superice.gdcc.lir.LirInstruction.VariableOperand("tail")
                )
        ));
        entry(varargCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(varargCall);

        var compatCall = newVoidFunction("call_count");
        compatCall.createAndAddVariable("probe", new GdObjectType("Probe"));
        compatCall.createAndAddVariable("count", GdIntType.INT);
        entry(compatCall).appendInstruction(new CallMethodInsn("count", "count", "probe", List.of()));
        entry(compatCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(compatCall);

        var builtinCall = newVoidFunction("call_builtin");
        builtinCall.createAndAddVariable("array", new GdArrayType(GdVariantType.VARIANT));
        entry(builtinCall).appendInstruction(new CallMethodInsn(null, "size", "array", List.of()));
        entry(builtinCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(builtinCall);

        var dynamicCall = newVoidFunction("call_dynamic");
        dynamicCall.createAndAddVariable("value", GdVariantType.VARIANT);
        entry(dynamicCall).appendInstruction(new CallMethodInsn(null, "callv", "value", List.of()));
        entry(dynamicCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(dynamicCall);

        var gdccCall = newVoidFunction("call_gdcc");
        gdccCall.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(gdccCall).appendInstruction(new CallMethodInsn(null, "ping", "worker", List.of()));
        entry(gdccCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(gdccCall);

        var module = new LirModule("engine_bind_header_module", List.of(hostClass));
        var codegen = newCodegen(
                module,
                apiWith(List.of(arrayBuiltinWithSize()), List.of(probeClassWithBindFallbacks())),
                List.of(hostClass)
        );

        var files = codegen.generate();
        assertEquals(List.of("entry.c", "engine_method_binds.h", "entry.h"), files.stream().map(GeneratedFile::filePath).toList());

        var renderedFiles = renderFiles(files);
        var entrySource = renderedFiles.get("entry.c");
        var bindHeader = renderedFiles.get("engine_method_binds.h");
        var entryHeader = renderedFiles.get("entry.h");

        assertContainsAll(entryHeader, "#include \"engine_method_binds.h\"");
        assertContainsAll(
                bindHeader,
                "GDEXTENSION_ENGINE_BIND_HEADER_MODULE_ENGINE_METHOD_BINDS_H",
                "gdcc_engine_method_bind_probe_touch_55(",
                "gdcc_engine_method_bind_static_probe_touch_55(",
                "gdcc_engine_method_bind_vararg_probe_touch_55(",
                "gdcc_engine_method_bind_probe_count_72("
        );
        assertContainsAll(
                resolveFunctionBodyByPrefix(bindHeader, "static inline GDExtensionMethodBindPtr gdcc_engine_method_bind_probe_count_72"),
                "(GDExtensionInt)72LL",
                "(GDExtensionInt)721LL",
                "(GDExtensionInt)722LL"
        );
        assertFalse(bindHeader.contains("gdcc_engine_method_bind_array_size_"), bindHeader);
        assertFalse(bindHeader.contains("gdcc_engine_method_bind_worker_ping_"), bindHeader);
        assertFalse(bindHeader.contains("gdcc_engine_method_bind_variant_callv_"), bindHeader);

        assertContainsAll(entrySource, "godot_Probe_touch(");
        assertFalse(entrySource.contains("gdcc_engine_method_bind_probe_touch_55("), entrySource);
    }

    @Test
    @DisplayName("generate should emit empty bind header when no exact engine method is used")
    void generateShouldEmitEmptyBindHeaderWhenNoExactEngineMethodIsUsed() {
        var hostClass = newClass("Worker", "RefCounted");

        var gdccPing = newVoidFunction("ping");
        gdccPing.createAndAddVariable("self", new GdObjectType("Worker"));
        entry(gdccPing).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(gdccPing);

        var builtinCall = newVoidFunction("call_builtin");
        builtinCall.createAndAddVariable("array", new GdArrayType(GdVariantType.VARIANT));
        entry(builtinCall).appendInstruction(new CallMethodInsn(null, "size", "array", List.of()));
        entry(builtinCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(builtinCall);

        var dynamicCall = newVoidFunction("call_dynamic");
        dynamicCall.createAndAddVariable("value", GdVariantType.VARIANT);
        entry(dynamicCall).appendInstruction(new CallMethodInsn(null, "callv", "value", List.of()));
        entry(dynamicCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(dynamicCall);

        var gdccCall = newVoidFunction("call_gdcc");
        gdccCall.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(gdccCall).appendInstruction(new CallMethodInsn(null, "ping", "worker", List.of()));
        entry(gdccCall).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(gdccCall);

        var module = new LirModule("empty_engine_bind_header_module", List.of(hostClass));
        var codegen = newCodegen(module, apiWith(List.of(arrayBuiltinWithSize()), List.of()), List.of(hostClass));
        var renderedFiles = renderFiles(codegen.generate());
        var bindHeader = renderedFiles.get("engine_method_binds.h");
        var entryHeader = renderedFiles.get("entry.h");

        assertContainsAll(entryHeader, "#include \"engine_method_binds.h\"");
        assertContainsAll(
                bindHeader,
                "GDEXTENSION_EMPTY_ENGINE_BIND_HEADER_MODULE_ENGINE_METHOD_BINDS_H",
                "No exact engine method binds were collected for this module."
        );
        assertFalse(bindHeader.contains("gdcc_engine_method_bind_"), bindHeader);
    }

    private static @NotNull Map<String, String> renderFiles(@NotNull List<GeneratedFile> files) {
        var rendered = new LinkedHashMap<String, String>();
        for (var file : files) {
            rendered.put(file.filePath(), new String(file.contentWriter()));
        }
        return rendered;
    }

    private static void assertContainsAll(@NotNull String text, @NotNull String... needles) {
        for (var needle : needles) {
            assertTrue(text.contains(needle), () -> "Missing fragment `" + needle + "` in:\n" + text);
        }
    }

    private static @NotNull String resolveFunctionBodyByPrefix(@NotNull String code, @NotNull String signaturePrefix) {
        var signatureIndex = code.indexOf(signaturePrefix);
        assertTrue(signatureIndex >= 0, "Missing function prefix: " + signaturePrefix);
        var openBraceIndex = code.indexOf('{', signatureIndex);
        assertTrue(openBraceIndex >= 0, "Missing opening brace for " + signaturePrefix);
        var closeBraceIndex = findMatchingBrace(code, openBraceIndex);
        return code.substring(openBraceIndex + 1, closeBraceIndex);
    }

    private static int findMatchingBrace(@NotNull String text, int openBraceIndex) {
        var depth = 0;
        for (var i = openBraceIndex; i < text.length(); i++) {
            var ch = text.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new AssertionError("Missing closing brace for function body");
    }

    private static @NotNull CCodegen newCodegen(
            @NotNull LirModule module,
            @NotNull ExtensionAPI api,
            @NotNull List<LirClassDef> gdccClasses
    ) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        return codegen;
    }

    private static @NotNull ExtensionAPI apiWith(
            @NotNull List<ExtensionBuiltinClass> builtinClasses,
            @NotNull List<ExtensionGdClass> gdClasses
    ) {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                builtinClasses,
                gdClasses,
                List.of(),
                List.of()
        );
    }

    private static @NotNull LirClassDef newClass(@NotNull String name, @NotNull String superName) {
        return new LirClassDef(name, superName, false, false, Map.of(), List.of(), List.of(), List.of());
    }

    private static @NotNull LirFunctionDef newVoidFunction(@NotNull String name) {
        var func = new LirFunctionDef(name);
        func.setReturnType(GdVoidType.VOID);
        var entry = new LirBasicBlock("entry");
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    private static @NotNull LirBasicBlock entry(@NotNull LirFunctionDef functionDef) {
        return Objects.requireNonNull(functionDef.getBasicBlock("entry"));
    }

    private static @NotNull ExtensionBuiltinClass arrayBuiltinWithSize() {
        var size = new ExtensionBuiltinClass.ClassMethod(
                "size",
                "int",
                false,
                true,
                false,
                false,
                0L,
                List.of(),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("int")
        );
        return new ExtensionBuiltinClass(
                "Array",
                false,
                List.of(),
                List.of(size),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static @NotNull ExtensionGdClass probeClassWithBindFallbacks() {
        var instanceTouch = new ExtensionGdClass.ClassMethod(
                "touch",
                false,
                false,
                false,
                false,
                55L,
                List.of(551L),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of()
        );
        var staticTouch = new ExtensionGdClass.ClassMethod(
                "touch",
                false,
                false,
                true,
                false,
                55L,
                List.of(552L),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("label", "String", null, null))
        );
        var varargTouch = new ExtensionGdClass.ClassMethod(
                "touch",
                false,
                true,
                false,
                false,
                55L,
                List.of(553L),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("head", "int", null, null))
        );
        var count = new ExtensionGdClass.ClassMethod(
                "count",
                false,
                false,
                false,
                false,
                72L,
                List.of(721L, 722L),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("int"),
                List.of()
        );
        return new ExtensionGdClass(
                "Probe",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(instanceTouch, staticTouch, varargTouch, count),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
