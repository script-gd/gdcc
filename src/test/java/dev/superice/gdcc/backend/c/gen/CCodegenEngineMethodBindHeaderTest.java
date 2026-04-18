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
    @DisplayName("generate should emit bind header for exact engine methods only and switch non-vararg entry calls to helpers")
    void generateShouldEmitBindHeaderForExactEngineMethodsOnlyAndSwitchNonVarargEntryCallsToHelpers() {
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
                "gdcc_engine_method_bind_probe_count_72(",
                "gdcc_engine_call_probe_touch_55(",
                "gdcc_engine_call_static_probe_touch_55(",
                "gdcc_engine_callv_probe_touch_55(",
                "gdcc_engine_call_probe_count_72("
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
        assertFalse(bindHeader.contains("static inline void godot_Probe_touch("), bindHeader);

        assertContainsAll(
                entrySource,
                "gdcc_engine_call_probe_touch_55(",
                "gdcc_engine_call_static_probe_touch_55(",
                "gdcc_engine_callv_probe_touch_55(",
                "gdcc_engine_call_probe_count_72("
        );
        assertFalse(entrySource.contains("gdcc_engine_method_bind_probe_touch_55("), entrySource);
        assertFalse(entrySource.contains("godot_Probe_touch("), entrySource);
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
        assertFalse(bindHeader.contains("gdcc_engine_call_"), bindHeader);
    }

    @Test
    @DisplayName("generate should emit non-vararg helpers with ptrcall slot contract and static helper without receiver")
    void generateShouldEmitNonVarargHelpersWithPtrcallSlotContractAndStaticHelperWithoutReceiver() {
        var hostClass = newClass("Worker", "RefCounted");

        var callLink = newVoidFunction("call_link");
        callLink.createAndAddVariable("probe", new GdObjectType("Probe"));
        callLink.createAndAddVariable("peer", new GdObjectType("Probe"));
        callLink.createAndAddVariable("label", GdStringType.STRING);
        callLink.createAndAddVariable("count", GdIntType.INT);
        callLink.createAndAddVariable("result", GdIntType.INT);
        entry(callLink).appendInstruction(new CallMethodInsn(
                "result",
                "link",
                "probe",
                List.of(varOperand("peer"), varOperand("label"), varOperand("count"))
        ));
        entry(callLink).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(callLink);

        var callSpawn = newVoidFunction("call_spawn");
        callSpawn.createAndAddVariable("probe", new GdObjectType("Probe"));
        callSpawn.createAndAddVariable("label", GdStringType.STRING);
        entry(callSpawn).appendInstruction(new CallMethodInsn(
                null,
                "spawn",
                "probe",
                List.of(varOperand("label"))
        ));
        entry(callSpawn).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(callSpawn);

        var module = new LirModule("engine_bind_ptrcall_helper_module", List.of(hostClass));
        var codegen = newCodegen(
                module,
                apiWith(List.of(), List.of(probeClassWithPtrcallHelpers())),
                List.of(hostClass)
        );
        var bindHeader = renderFiles(codegen.generate()).get("engine_method_binds.h");

        var linkSignature = resolveFunctionSignatureByPrefix(
                bindHeader,
                "static inline godot_int gdcc_engine_call_probe_link_91"
        );
        assertContainsAll(
                linkSignature,
                "GDExtensionObjectPtr self",
                "godot_Probe* arg0",
                "godot_String* arg1",
                "godot_int arg2"
        );
        var linkBody = resolveFunctionBodyByPrefix(bindHeader, "static inline godot_int gdcc_engine_call_probe_link_91");
        assertContainsAll(
                linkBody,
                "GDExtensionMethodBindPtr bind = gdcc_engine_method_bind_probe_link_91();",
                "GDCC_PRINT_RUNTIME_ERROR(\"engine method bind lookup failed: Probe.link\"",
                "return 0;",
                "const GDExtensionConstTypePtr args[] = {",
                "&arg0,",
                "arg1,",
                "&arg2",
                "godot_object_method_bind_ptrcall(",
                "self,",
                "&result"
        );
        assertFalse(linkBody.contains("NULL,\n        args"), linkBody);

        var spawnSignature = resolveFunctionSignatureByPrefix(
                bindHeader,
                "static inline void gdcc_engine_call_static_probe_spawn_92"
        );
        assertFalse(spawnSignature.contains("self"), spawnSignature);
        assertContainsAll(spawnSignature, "godot_String* arg0");
        var spawnBody = resolveFunctionBodyByPrefix(
                bindHeader,
                "static inline void gdcc_engine_call_static_probe_spawn_92"
        );
        assertContainsAll(
                spawnBody,
                "GDExtensionMethodBindPtr bind = gdcc_engine_method_bind_static_probe_spawn_92();",
                "GDCC_PRINT_RUNTIME_ERROR(\"engine method bind lookup failed: Probe.spawn\"",
                "godot_object_method_bind_ptrcall(",
                "NULL,",
                "args,",
                "arg0"
        );
    }

    @Test
    @DisplayName("generate should materialize enum and bitfield ptrcall slots inside helper bodies")
    void generateShouldMaterializeEnumAndBitfieldPtrcallSlotsInsideHelperBodies() {
        var hostClass = newClass("Worker", "RefCounted");

        var callConfigure = newVoidFunction("call_configure");
        callConfigure.createAndAddVariable("probe", new GdObjectType("Probe"));
        callConfigure.createAndAddVariable("mode", GdIntType.INT);
        callConfigure.createAndAddVariable("flags", GdIntType.INT);
        callConfigure.createAndAddVariable("label", GdStringType.STRING);
        entry(callConfigure).appendInstruction(new CallMethodInsn(
                null,
                "configure",
                "probe",
                List.of(varOperand("mode"), varOperand("flags"), varOperand("label"))
        ));
        entry(callConfigure).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(callConfigure);

        var module = new LirModule("engine_bind_slot_helper_module", List.of(hostClass));
        var codegen = newCodegen(
                module,
                apiWith(List.of(), List.of(probeClassWithSlotNormalizedHelpers())),
                List.of(hostClass)
        );
        var bindHeader = renderFiles(codegen.generate()).get("engine_method_binds.h");

        var configureSignature = resolveFunctionSignatureByPrefix(
                bindHeader,
                "static inline void gdcc_engine_call_probe_configure_95"
        );
        assertContainsAll(
                configureSignature,
                "GDExtensionObjectPtr self",
                "godot_int arg0",
                "godot_int arg1",
                "godot_String* arg2"
        );
        assertFalse(configureSignature.contains("godot_Probe_Mode*"), configureSignature);
        assertFalse(configureSignature.contains("godot_Probe_Flags*"), configureSignature);

        var configureBody = resolveFunctionBodyByPrefix(bindHeader, "static inline void gdcc_engine_call_probe_configure_95");
        assertContainsAll(
                configureBody,
                "const godot_Probe_Mode arg0_slot = (godot_Probe_Mode)arg0;",
                "const godot_Probe_Flags arg1_slot = (godot_Probe_Flags)arg1;",
                "const GDExtensionConstTypePtr args[] = {",
                "&arg0_slot,",
                "&arg1_slot,",
                "arg2",
                "godot_object_method_bind_ptrcall(",
                "self,"
        );
        assertFalse(configureBody.contains("(const godot_Probe_Flags *)&"), configureBody);
        assertFalse(configureBody.contains("(const godot_Probe_Mode *)&"), configureBody);
    }

    @Test
    @DisplayName("generate should emit vararg helpers with guarded unpack and helper-owned cleanup only")
    void generateShouldEmitVarargHelpersWithGuardedUnpackAndHelperOwnedCleanupOnly() {
        var hostClass = newClass("Worker", "RefCounted");

        var callMix = newVoidFunction("call_mix");
        callMix.createAndAddVariable("probe", new GdObjectType("Probe"));
        callMix.createAndAddVariable("head", GdVariantType.VARIANT);
        callMix.createAndAddVariable("label", GdStringType.STRING);
        callMix.createAndAddVariable("tail", GdVariantType.VARIANT);
        callMix.createAndAddVariable("result", GdStringType.STRING);
        entry(callMix).appendInstruction(new CallMethodInsn(
                "result",
                "mix",
                "probe",
                List.of(varOperand("head"), varOperand("label"), varOperand("tail"))
        ));
        entry(callMix).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(callMix);

        var callBroadcast = newVoidFunction("call_broadcast");
        callBroadcast.createAndAddVariable("probe", new GdObjectType("Probe"));
        callBroadcast.createAndAddVariable("prefix", GdIntType.INT);
        callBroadcast.createAndAddVariable("tail", GdVariantType.VARIANT);
        entry(callBroadcast).appendInstruction(new CallMethodInsn(
                null,
                "broadcast",
                "probe",
                List.of(varOperand("prefix"), varOperand("tail"))
        ));
        entry(callBroadcast).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(callBroadcast);

        var module = new LirModule("engine_bind_vararg_helper_module", List.of(hostClass));
        var codegen = newCodegen(
                module,
                apiWith(List.of(), List.of(probeClassWithVarargHelpers())),
                List.of(hostClass)
        );
        var bindHeader = renderFiles(codegen.generate()).get("engine_method_binds.h");

        var mixSignature = resolveFunctionSignatureByPrefix(
                bindHeader,
                "static inline godot_String gdcc_engine_callv_probe_mix_93"
        );
        assertContainsAll(
                mixSignature,
                "GDExtensionObjectPtr self",
                "godot_Variant* arg0",
                "godot_String* arg1",
                "const godot_Variant **argv",
                "godot_int argc"
        );
        var mixBody = resolveFunctionBodyByPrefix(bindHeader, "static inline godot_String gdcc_engine_callv_probe_mix_93");
        assertContainsAll(
                mixBody,
                "godot_Variant fixed_arg_0 = godot_new_Variant_with_Variant(arg0);",
                "godot_Variant fixed_arg_1 = godot_new_Variant_with_String(arg1);",
                "const godot_int fixed_argc = (godot_int)2;",
                "GDExtensionConstVariantPtr final_args[2 + argc];",
                "final_args[fixed_argc + i] = argv[i];",
                "godot_Variant ret = godot_new_Variant_nil();",
                "godot_object_method_bind_call(",
                "self,",
                "&ret,",
                "&error",
                "result = godot_new_String_with_Variant(",
                "if (!call_ok) {",
                "return godot_new_String();"
        );
        assertOrderedFragments(
                mixBody,
                "if (error.error != GDEXTENSION_CALL_OK)",
                "result = godot_new_String_with_Variant("
        );
        assertOrderedFragments(
                mixBody,
                "cleanup:",
                "godot_Variant_destroy(&ret);",
                "godot_Variant_destroy(&fixed_arg_1);",
                "godot_Variant_destroy(&fixed_arg_0);"
        );
        assertFalse(mixBody.contains("godot_Variant_destroy(argv["), mixBody);
        assertFalse(mixBody.contains("godot_Variant ret;"), mixBody);
        assertFalse(mixBody.contains(", NULL,\n        &error"), mixBody);

        var broadcastSignature = resolveFunctionSignatureByPrefix(
                bindHeader,
                "static inline void gdcc_engine_callv_static_probe_broadcast_94"
        );
        assertFalse(broadcastSignature.contains("self"), broadcastSignature);
        assertContainsAll(
                broadcastSignature,
                "godot_int arg0",
                "const godot_Variant **argv",
                "godot_int argc"
        );
        var broadcastBody = resolveFunctionBodyByPrefix(
                bindHeader,
                "static inline void gdcc_engine_callv_static_probe_broadcast_94"
        );
        assertContainsAll(
                broadcastBody,
                "godot_Variant fixed_arg_0 = godot_new_Variant_with_int(arg0);",
                "godot_Variant ret = godot_new_Variant_nil();",
                "godot_object_method_bind_call(",
                "NULL,",
                "&ret,",
                "&error",
                "godot_Variant_destroy(&ret);",
                "godot_Variant_destroy(&fixed_arg_0);"
        );
        assertFalse(broadcastBody.contains(", NULL,\n        &error"), broadcastBody);
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

    private static @NotNull String resolveFunctionSignatureByPrefix(@NotNull String code, @NotNull String signaturePrefix) {
        var signatureIndex = code.indexOf(signaturePrefix);
        assertTrue(signatureIndex >= 0, "Missing function prefix: " + signaturePrefix);
        var openBraceIndex = code.indexOf('{', signatureIndex);
        assertTrue(openBraceIndex >= 0, "Missing opening brace for " + signaturePrefix);
        return code.substring(signatureIndex, openBraceIndex);
    }

    private static void assertOrderedFragments(@NotNull String text, @NotNull String... fragments) {
        var cursor = -1;
        for (var fragment : fragments) {
            var next = text.indexOf(fragment, cursor + 1);
            assertTrue(next >= 0, () -> "Missing ordered fragment `" + fragment + "` in:\n" + text);
            cursor = next;
        }
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

    private static @NotNull dev.superice.gdcc.lir.LirInstruction.VariableOperand varOperand(@NotNull String name) {
        return new dev.superice.gdcc.lir.LirInstruction.VariableOperand(name);
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

    private static @NotNull ExtensionGdClass probeClassWithPtrcallHelpers() {
        var link = new ExtensionGdClass.ClassMethod(
                "link",
                false,
                false,
                false,
                false,
                91L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("int"),
                List.of(
                        new ExtensionFunctionArgument("peer", "Probe", null, null),
                        new ExtensionFunctionArgument("label", "String", null, null),
                        new ExtensionFunctionArgument("count", "int", null, null)
                )
        );
        var spawn = new ExtensionGdClass.ClassMethod(
                "spawn",
                false,
                false,
                true,
                false,
                92L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("label", "String", null, null))
        );
        return new ExtensionGdClass(
                "Probe",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(link, spawn),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static @NotNull ExtensionGdClass probeClassWithVarargHelpers() {
        var mix = new ExtensionGdClass.ClassMethod(
                "mix",
                false,
                true,
                false,
                false,
                93L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("String"),
                List.of(
                        new ExtensionFunctionArgument("head", "Variant", null, null),
                        new ExtensionFunctionArgument("label", "String", null, null)
                )
        );
        var broadcast = new ExtensionGdClass.ClassMethod(
                "broadcast",
                false,
                true,
                true,
                false,
                94L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("prefix", "int", null, null))
        );
        return new ExtensionGdClass(
                "Probe",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(mix, broadcast),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static @NotNull ExtensionGdClass probeClassWithSlotNormalizedHelpers() {
        var configure = new ExtensionGdClass.ClassMethod(
                "configure",
                false,
                false,
                false,
                false,
                95L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(
                        new ExtensionFunctionArgument("mode", "enum::Probe.Mode", null, null),
                        new ExtensionFunctionArgument("flags", "bitfield::Probe.Flags", null, null),
                        new ExtensionFunctionArgument("label", "String", null, null)
                )
        );
        return new ExtensionGdClass(
                "Probe",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(configure),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
