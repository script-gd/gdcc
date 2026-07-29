package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.ConstructObjectInsn;
import gd.script.gdcc.lir.insn.LoadPropertyInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.StorePropertyInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
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
                List.of(new LirInstruction.VariableOperand("label"))
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
                        new LirInstruction.VariableOperand("head"),
                        new LirInstruction.VariableOperand("tail")
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
        assertEquals(List.of("entry.c", "engine_method_binds.h", "object_fat_ptr_types.h", "entry.h"), files.stream().map(GeneratedFile::filePath).toList());

        var renderedFiles = renderFiles(files);
        var entrySource = renderedFiles.get("entry.c");
        var bindHeader = renderedFiles.get("engine_method_binds.h");
        var entryHeader = renderedFiles.get("entry.h");

        assertContainsAll(entryHeader, "#include \"engine_method_binds.h\"");
        assertContainsAll(
                bindHeader,
                "GDEXTENSION_ENGINE_BIND_HEADER_MODULE_ENGINE_METHOD_BINDS_H",
                "gdcc_engine_method_bind_probe_touch_P_RV(",
                "gdcc_engine_method_bind_static_probe_touch_PT_RV(",
                "gdcc_engine_method_bind_probe_touch_PI_RV_Xv(",
                "gdcc_engine_method_bind_probe_count_P_RI(",
                "gdcc_engine_call_probe_touch_P_RV(",
                "gdcc_engine_call_static_probe_touch_PT_RV(",
                "gdcc_engine_callv_probe_touch_PI_RV_Xv(",
                "gdcc_engine_call_probe_count_P_RI("
        );
        assertContainsAll(
                bindHeader,
                "GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR(",
                "gdcc_engine_method_bind_probe_count_P_RI,",
                "u8\"Probe\"",
                "u8\"count\"",
                "(GDExtensionInt)72LL",
                "(GDExtensionInt)2",
                "(GDExtensionInt)721LL",
                "(GDExtensionInt)722LL"
        );
        assertFalse(bindHeader.contains("gdcc_engine_method_bind_probe_count_P_RI_compatibility_hashes"), bindHeader);
        assertFalse(
                bindHeader.contains("static inline GDExtensionBool gdcc_engine_method_bind_probe_count_P_RI("),
                bindHeader
        );
        assertFalse(bindHeader.contains("gdcc_engine_method_bind_array_size_"), bindHeader);
        assertFalse(bindHeader.contains("gdcc_engine_method_bind_worker_ping_"), bindHeader);
        assertFalse(bindHeader.contains("gdcc_engine_method_bind_variant_callv_"), bindHeader);
        assertFalse(bindHeader.contains("static inline void godot_Probe_touch("), bindHeader);

        assertContainsAll(
                entrySource,
                "gdcc_engine_call_probe_touch_P_RV(",
                "gdcc_engine_call_static_probe_touch_PT_RV(",
                "gdcc_engine_callv_probe_touch_PI_RV_Xv(",
                "gdcc_engine_call_probe_count_P_RI("
        );
        assertFalse(entrySource.contains("gdcc_engine_method_bind_probe_touch_P_RV("), entrySource);
        assertFalse(entrySource.contains("godot_Probe_touch("), entrySource);
    }

    @Test
    @DisplayName("generate should emit bind header for exact engine property accessors")
    void generateShouldEmitBindHeaderForExactEnginePropertyAccessors() {
        var hostClass = newClass("Worker", "RefCounted");

        var accessProperties = newVoidFunction("access_window_properties");
        accessProperties.createAndAddVariable("window", new GdObjectType("Window"));
        accessProperties.createAndAddVariable("title", GdStringType.STRING);
        accessProperties.createAndAddVariable("flag", GdBoolType.BOOL);
        accessProperties.createAndAddVariable("flag_value", GdBoolType.BOOL);
        entry(accessProperties).appendInstruction(new LoadPropertyInsn("title", "window_title", "window"));
        entry(accessProperties).appendInstruction(new StorePropertyInsn("window_title", "window", "title"));
        entry(accessProperties).appendInstruction(new LoadPropertyInsn("flag", "unresizable", "window"));
        entry(accessProperties).appendInstruction(new StorePropertyInsn("unresizable", "window", "flag_value"));
        entry(accessProperties).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(accessProperties);

        var module = new LirModule("engine_property_bind_header_module", List.of(hostClass));
        var codegen = newCodegen(
                module,
                apiWith(List.of(), List.of(windowClassWithPropertyAccessors())),
                List.of(hostClass)
        );
        var renderedFiles = renderFiles(codegen.generate());
        var entrySource = renderedFiles.get("entry.c");
        var bindHeader = renderedFiles.get("engine_method_binds.h");

        assertContainsAll(
                entrySource,
                "gdcc_engine_call_window_get_title_override_P_RT($window)",
                "gdcc_engine_call_window_set_title_override_PT_RV($window, &$title);",
                "gdcc_engine_call_window_get_flag_PI_RZ($window, 0)",
                "gdcc_engine_call_window_set_flag_PIZ_RV($window, 0, $flag_value);"
        );
        assertContainsAll(
                bindHeader,
                "gdcc_engine_method_bind_window_get_title_override_P_RT(",
                "gdcc_engine_method_bind_window_set_title_override_PT_RV(",
                "gdcc_engine_method_bind_window_get_flag_PI_RZ(",
                "gdcc_engine_method_bind_window_set_flag_PIZ_RV(",
                "gdcc_engine_call_window_get_title_override_P_RT(",
                "gdcc_engine_call_window_set_title_override_PT_RV(",
                "gdcc_engine_call_window_get_flag_PI_RZ(",
                "gdcc_engine_call_window_set_flag_PIZ_RV("
        );
        assertFalse(entrySource.contains("window_get_window_title"), entrySource);
        assertFalse(entrySource.contains("window_set_window_title"), entrySource);
        assertFalse(entrySource.contains("godot_Window_get_window_title"), entrySource);
        assertFalse(entrySource.contains("godot_Window_set_window_title"), entrySource);
        assertFalse(bindHeader.contains("window_get_window_title"), bindHeader);
        assertFalse(bindHeader.contains("window_set_window_title"), bindHeader);
    }

    @Test
    @DisplayName("helper and accessor names should stay stable when only bind hashes change")
    void helperAndAccessorNamesShouldStayStableWhenOnlyBindHashesChange() {
        var hostClass = newClass("Worker", "RefCounted");
        var callCount = newVoidFunction("call_count");
        callCount.createAndAddVariable("probe", new GdObjectType("Probe"));
        callCount.createAndAddVariable("count", GdIntType.INT);
        entry(callCount).appendInstruction(new CallMethodInsn("count", "count", "probe", List.of()));
        entry(callCount).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(callCount);

        var module = new LirModule("engine_bind_hash_stability_module", List.of(hostClass));
        var oldFiles = renderFiles(newCodegen(
                module,
                apiWith(List.of(), List.of(probeClassWithCount(72L, List.of(721L, 722L)))),
                List.of(hostClass)
        ).generate());
        var newFiles = renderFiles(newCodegen(
                module,
                apiWith(List.of(), List.of(probeClassWithCount(172L, List.of(1721L)))),
                List.of(hostClass)
        ).generate());

        var oldHeader = oldFiles.get("engine_method_binds.h");
        var newHeader = newFiles.get("engine_method_binds.h");
        var oldEntry = oldFiles.get("entry.c");
        var newEntry = newFiles.get("entry.c");

        assertContainsAll(
                oldHeader,
                "gdcc_engine_method_bind_probe_count_P_RI(",
                "gdcc_engine_call_probe_count_P_RI("
        );
        assertContainsAll(
                newHeader,
                "gdcc_engine_method_bind_probe_count_P_RI(",
                "gdcc_engine_call_probe_count_P_RI("
        );
        assertContainsAll(
                oldHeader,
                "gdcc_engine_method_bind_probe_count_P_RI,",
                "(GDExtensionInt)72LL",
                "(GDExtensionInt)721LL",
                "(GDExtensionInt)722LL"
        );
        assertContainsAll(
                newHeader,
                "gdcc_engine_method_bind_probe_count_P_RI,",
                "(GDExtensionInt)172LL",
                "(GDExtensionInt)1721LL"
        );
        assertFalse(newHeader.contains("(GDExtensionInt)72LL"), newHeader);
        assertEquals(oldEntry, newEntry);
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
                "No engine constructors were collected for this module.",
                "No module-local Godot wrappers were collected for this module.",
                "No exact engine method binds were collected for this module."
        );
        assertFalse(bindHeader.contains("godot_new_Node"), bindHeader);
        assertFalse(bindHeader.contains("gdcc_engine_method_bind_"), bindHeader);
        assertFalse(bindHeader.contains("gdcc_engine_call_"), bindHeader);
    }

    @Test
    @DisplayName("generate should emit constructor wrappers without polluting exact engine method usage")
    void generateShouldEmitConstructorWrappersWithoutPollutingExactEngineMethodUsage() {
        var hostClass = newClass("Worker", "RefCounted");

        var constructNode = newVoidFunction("construct_node");
        constructNode.createAndAddVariable("node", new GdObjectType("Node"));
        entry(constructNode).appendInstruction(new ConstructObjectInsn("node", "Node"));
        entry(constructNode).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(constructNode);

        var module = new LirModule("engine_constructor_bind_header_module", List.of(hostClass));
        var codegen = newCodegen(module, apiWith(List.of(), List.of(nodeClass(), refCountedClass())), List.of(hostClass));
        var renderedFiles = renderFiles(codegen.generate());
        var entrySource = renderedFiles.get("entry.c");
        var bindHeader = renderedFiles.get("engine_method_binds.h");

        // Constructor still returns raw; call site captures into fat storage.
        assertTrue(
                entrySource.contains("gdcc_Node_fat_ptr_from_raw((GDExtensionObjectPtr)(godot_new_Node()))"),
                entrySource
        );
        assertContainsAll(
                bindHeader,
                "static inline godot_Node *godot_new_Node(void)",
                "GDExtensionObjectPtr object = godot_classdb_construct_object(GD_STATIC_SN(u8\"Node\"));",
                "gdcc_binding_lookup_context context = { 0 };",
                "context.kind = \"engine_constructor\";",
                "context.function_name = \"godot_new_Node\";",
                "context.lookup_name = \"Node\";",
                "return (godot_Node *)object;",
                "No exact engine method binds were collected for this module."
        );
        assertFalse(bindHeader.contains("gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){"), bindHeader);
        assertFalse(bindHeader.contains("\n                .kind = \"engine_constructor\""), bindHeader);
        assertFalse(bindHeader.contains("godot_new_RefCounted(void)"), bindHeader);
        assertFalse(bindHeader.contains("godot_classdb_construct_object2"), bindHeader);
        assertFalse(bindHeader.contains("godot_new_StringName_with_latin1_chars"), bindHeader);
        assertFalse(bindHeader.contains("godot_StringName_destroy"), bindHeader);
        assertFalse(bindHeader.contains("GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR("), bindHeader);
        assertFalse(bindHeader.contains("gdcc_engine_method_bind_node_"), bindHeader);
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
                "static inline godot_int gdcc_engine_call_probe_link_PL5Probe_TI_RI"
        );
        // Public surface is fat self/object args; raw slots stay inside the helper.
        assertContainsAll(
                linkSignature,
                "gdcc_Probe_fat_ptr self",
                "gdcc_Probe_fat_ptr arg0",
                "godot_String* arg1",
                "godot_int arg2"
        );
        var linkBody = resolveFunctionBodyByPrefix(bindHeader, "static inline godot_int gdcc_engine_call_probe_link_PL5Probe_TI_RI");
        assertContainsAll(
                linkBody,
                "GDExtensionMethodBindPtr bind = NULL;",
                "if (!gdcc_engine_method_bind_probe_link_PL5Probe_TI_RI(&bind)) {",
                "return 0;",
                "GDExtensionObjectPtr self_raw = gdcc_Probe_fat_ptr_live_object(self);",
                "GDExtensionObjectPtr arg0_raw = gdcc_Probe_fat_ptr_live_object(arg0);",
                "const GDExtensionConstTypePtr args[] = {",
                "&arg0_raw,",
                "arg1,",
                "&arg2",
                "godot_object_method_bind_ptrcall(",
                "self_raw,",
                "&result"
        );
        assertFalse(linkBody.contains("&arg0,"), linkBody);
        assertFalse(linkSignature.contains("GDExtensionObjectPtr self"), linkSignature);
        assertFalse(linkSignature.contains("godot_Probe* arg0"), linkSignature);
        assertFalse(linkBody.contains("bind == NULL"), linkBody);
        assertFalse(linkBody.contains("engine method bind lookup failed: Probe.link"), linkBody);
        assertFalse(linkBody.contains("NULL,\n        args"), linkBody);

        var spawnSignature = resolveFunctionSignatureByPrefix(
                bindHeader,
                "static inline void gdcc_engine_call_static_probe_spawn_PT_RV"
        );
        assertFalse(spawnSignature.contains("self"), spawnSignature);
        assertContainsAll(spawnSignature, "godot_String* arg0");
        var spawnBody = resolveFunctionBodyByPrefix(
                bindHeader,
                "static inline void gdcc_engine_call_static_probe_spawn_PT_RV"
        );
        assertContainsAll(
                spawnBody,
                "GDExtensionMethodBindPtr bind = NULL;",
                "if (!gdcc_engine_method_bind_static_probe_spawn_PT_RV(&bind)) {",
                "godot_object_method_bind_ptrcall(",
                "NULL,",
                "args,",
                "arg0"
        );
        assertFalse(spawnBody.contains("bind == NULL"), spawnBody);
        assertFalse(spawnBody.contains("engine method bind lookup failed: Probe.spawn"), spawnBody);
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
                "static inline void gdcc_engine_call_probe_configure_PIIT_RV"
        );
        assertContainsAll(
                configureSignature,
                "gdcc_Probe_fat_ptr self",
                "godot_int arg0",
                "godot_int arg1",
                "godot_String* arg2"
        );
        assertFalse(configureSignature.contains("godot_Probe_Mode*"), configureSignature);
        assertFalse(configureSignature.contains("godot_Probe_Flags*"), configureSignature);

        var configureBody = resolveFunctionBodyByPrefix(bindHeader, "static inline void gdcc_engine_call_probe_configure_PIIT_RV");
        assertContainsAll(
                configureBody,
                "GDExtensionObjectPtr self_raw = gdcc_Probe_fat_ptr_live_object(self);",
                "const godot_Probe_Mode arg0_slot = (godot_Probe_Mode)arg0;",
                "const godot_Probe_Flags arg1_slot = (godot_Probe_Flags)arg1;",
                "const GDExtensionConstTypePtr args[] = {",
                "&arg0_slot,",
                "&arg1_slot,",
                "arg2",
                "godot_object_method_bind_ptrcall(",
                "self_raw,"
        );
        assertFalse(configureBody.contains("(const godot_Probe_Flags *)&"), configureBody);
        assertFalse(configureBody.contains("(const godot_Probe_Mode *)&"), configureBody);
    }

    @Test
    @DisplayName("generate should zero initialize destroyable ptrcall return carriers")
    void generateShouldZeroInitializeDestroyablePtrcallReturnCarriers() {
        var hostClass = newClass("Worker", "RefCounted");

        var callName = newVoidFunction("call_name");
        callName.createAndAddVariable("probe", new GdObjectType("Probe"));
        callName.createAndAddVariable("name", GdStringType.STRING);
        entry(callName).appendInstruction(new CallMethodInsn("name", "name", "probe", List.of()));
        entry(callName).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(callName);

        var module = new LirModule("engine_bind_destroyable_return_module", List.of(hostClass));
        var codegen = newCodegen(
                module,
                apiWith(List.of(), List.of(probeClassWithDestroyableReturnHelper())),
                List.of(hostClass)
        );
        var bindHeader = renderFiles(codegen.generate()).get("engine_method_binds.h");

        var nameBody = resolveFunctionBodyByPrefix(bindHeader, "static inline godot_String gdcc_engine_call_probe_name_P_RT");
        assertContainsAll(
                nameBody,
                "godot_String result = { 0 };",
                "godot_object_method_bind_ptrcall(",
                "&result",
                "return result;"
        );
        assertFalse(nameBody.contains("godot_String result;\n"), nameBody);
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
                "static inline godot_String gdcc_engine_callv_probe_mix_PRT_RT_Xv"
        );
        assertContainsAll(
                mixSignature,
                "gdcc_Probe_fat_ptr self",
                "godot_Variant* arg0",
                "godot_String* arg1",
                "const godot_Variant **argv",
                "godot_int argc"
        );
        var mixBody = resolveFunctionBodyByPrefix(bindHeader, "static inline godot_String gdcc_engine_callv_probe_mix_PRT_RT_Xv");
        assertContainsAll(
                mixBody,
                "GDExtensionObjectPtr self_raw = gdcc_Probe_fat_ptr_live_object(self);",
                "godot_Variant fixed_arg_0 = godot_new_Variant_with_Variant(arg0);",
                "godot_Variant fixed_arg_1 = godot_new_Variant_with_String(arg1);",
                "const godot_int fixed_argc = (godot_int)2;",
                "GDExtensionConstVariantPtr final_args[2 + argc];",
                "final_args[fixed_argc + i] = argv[i];",
                "// object_method_bind_call constructs into raw Variant storage; error paths must not destroy it.",
                "godot_bool ret_initialized = false;",
                "godot_Variant ret;",
                "godot_object_method_bind_call(",
                "self_raw,",
                "(GDExtensionUninitializedVariantPtr)&ret,",
                "&error",
                "char call_error_desc[512];",
                "gdcc_variant_type_to_utf8(error.expected, expected_type_name, sizeof(expected_type_name));",
                "engine method call failed: Probe.mix: invalid argument #%lld, expected '%s', got '%s'",
                "engine method call failed: Probe.mix: unknown call error %d",
                "GDCC_PRINT_RUNTIME_ERROR(call_error_desc, __func__, __FILE__, __LINE__);",
                "ret_initialized = true;",
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
                "if (ret_initialized) {",
                "godot_Variant_destroy(&ret);",
                "godot_Variant_destroy(&fixed_arg_1);",
                "godot_Variant_destroy(&fixed_arg_0);"
        );
        assertFalse(mixBody.contains("godot_Variant_destroy(argv["), mixBody);
        assertFalse(mixBody.contains("godot_Variant ret = godot_new_Variant_nil();"), mixBody);
        assertFalse(mixBody.contains(", NULL,\n        &error"), mixBody);

        var broadcastSignature = resolveFunctionSignatureByPrefix(
                bindHeader,
                "static inline void gdcc_engine_callv_static_probe_broadcast_PI_RV_Xv"
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
                "static inline void gdcc_engine_callv_static_probe_broadcast_PI_RV_Xv"
        );
        assertContainsAll(
                broadcastBody,
                "godot_Variant fixed_arg_0 = godot_new_Variant_with_int(arg0);",
                "godot_bool ret_initialized = false;",
                "godot_Variant ret;",
                "godot_object_method_bind_call(",
                "NULL,",
                "(GDExtensionUninitializedVariantPtr)&ret,",
                "&error",
                "char call_error_desc[512];",
                "engine method call failed: Probe.broadcast: too many arguments, expected %lld, got %lld",
                "GDCC_PRINT_RUNTIME_ERROR(call_error_desc, __func__, __FILE__, __LINE__);",
                "ret_initialized = true;",
                "if (ret_initialized) {",
                "godot_Variant_destroy(&ret);",
                "godot_Variant_destroy(&fixed_arg_0);"
        );
        assertFalse(broadcastBody.contains("godot_Variant ret = godot_new_Variant_nil();"), broadcastBody);
        assertFalse(broadcastBody.contains(", NULL,\n        &error"), broadcastBody);
    }

    @Test
    @DisplayName("generate should own vararg RefCounted object return before temporary Variant destroy")
    void generateShouldOwnVarargObjectReturnBeforeVariantDestroy() {
        var hostClass = newClass("Worker", "RefCounted");

        var callMake = newVoidFunction("call_make");
        callMake.createAndAddVariable("probe", new GdObjectType("Probe"));
        callMake.createAndAddVariable("label", GdStringType.STRING);
        callMake.createAndAddVariable("extra", GdVariantType.VARIANT);
        callMake.createAndAddVariable("result", new GdObjectType("RefCounted"));
        entry(callMake).appendInstruction(new CallMethodInsn(
                "result",
                "make_ref",
                "probe",
                List.of(varOperand("label"), varOperand("extra"))
        ));
        entry(callMake).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(callMake);

        var module = new LirModule("engine_bind_vararg_object_return_module", List.of(hostClass));
        var codegen = newCodegen(
                module,
                apiWith(List.of(), List.of(probeClassWithVarargObjectReturn(), refCountedClass())),
                List.of(hostClass)
        );
        var bindHeader = renderFiles(codegen.generate()).get("engine_method_binds.h");
        // Object return leaf encodes as L{len}{Name}_ so descriptor ends with trailing '_' before _Xv.
        var makeBody = resolveFunctionBodyByPrefix(
                bindHeader,
                "static inline gdcc_RefCounted_fat_ptr gdcc_engine_callv_probe_make_ref_PT_RL10RefCounted__Xv"
        );
        assertTrue(makeBody.contains("gdcc_RefCounted_fat_ptr_from_variant("), makeBody);
        assertTrue(makeBody.contains("own_object(gdcc_RefCounted_fat_ptr_live_object(result));"), makeBody);
        assertTrue(makeBody.contains("godot_Variant_destroy(&ret);"), makeBody);
        assertOrderedFragments(
                makeBody,
                "gdcc_RefCounted_fat_ptr_from_variant(",
                "own_object(gdcc_RefCounted_fat_ptr_live_object(result));",
                "if (ret_initialized) {",
                "godot_Variant_destroy(&ret);"
        );
        var ownIdx = makeBody.indexOf("own_object(gdcc_RefCounted_fat_ptr_live_object(result));");
        var destroyIdx = makeBody.indexOf("godot_Variant_destroy(&ret);", ownIdx);
        assertTrue(ownIdx >= 0 && destroyIdx > ownIdx, "own must precede Variant destroy.\n" + makeBody);
    }

    @Test
    @DisplayName("generate should normalize mixed vararg fixed prefix surface before helper-owned pack and cleanup")
    void generateShouldNormalizeMixedVarargFixedPrefixSurfaceBeforeHelperOwnedPackAndCleanup() {
        var hostClass = newClass("Worker", "RefCounted");

        var callDispatch = newVoidFunction("call_dispatch");
        callDispatch.createAndAddVariable("probe", new GdObjectType("Probe"));
        callDispatch.createAndAddVariable("peer", new GdObjectType("Probe"));
        callDispatch.createAndAddVariable("mode", GdIntType.INT);
        callDispatch.createAndAddVariable("flags", GdIntType.INT);
        callDispatch.createAndAddVariable("label", GdStringType.STRING);
        callDispatch.createAndAddVariable("tail", GdVariantType.VARIANT);
        callDispatch.createAndAddVariable("result", GdIntType.INT);
        entry(callDispatch).appendInstruction(new CallMethodInsn(
                "result",
                "dispatch",
                "probe",
                List.of(
                        varOperand("peer"),
                        varOperand("mode"),
                        varOperand("flags"),
                        varOperand("label"),
                        varOperand("tail")
                )
        ));
        entry(callDispatch).setTerminator(new ReturnInsn(null));
        hostClass.addFunction(callDispatch);

        var module = new LirModule("engine_bind_vararg_mixed_prefix_module", List.of(hostClass));
        var codegen = newCodegen(
                module,
                apiWith(List.of(), List.of(probeClassWithVarargHelpers())),
                List.of(hostClass)
        );
        var bindHeader = renderFiles(codegen.generate()).get("engine_method_binds.h");

        var dispatchSignature = resolveFunctionSignatureByPrefix(
                bindHeader,
                "static inline godot_int gdcc_engine_callv_probe_dispatch_PL5Probe_IIT_RI_Xv"
        );
        assertContainsAll(
                dispatchSignature,
                "gdcc_Probe_fat_ptr self",
                "gdcc_Probe_fat_ptr arg0",
                "godot_int arg1",
                "godot_int arg2",
                "godot_String* arg3",
                "const godot_Variant **argv",
                "godot_int argc"
        );
        assertFalse(dispatchSignature.contains("godot_Probe_Mode"), dispatchSignature);
        assertFalse(dispatchSignature.contains("godot_Probe_Flags"), dispatchSignature);

        var dispatchBody = resolveFunctionBodyByPrefix(
                bindHeader,
                "static inline godot_int gdcc_engine_callv_probe_dispatch_PL5Probe_IIT_RI_Xv"
        );
        assertContainsAll(
                dispatchBody,
                "GDExtensionObjectPtr self_raw = gdcc_Probe_fat_ptr_live_object(self);",
                "godot_Variant fixed_arg_0 = gdcc_Probe_fat_ptr_to_variant(arg0);",
                "godot_Variant fixed_arg_1 = godot_new_Variant_with_int(arg1);",
                "godot_Variant fixed_arg_2 = godot_new_Variant_with_int(arg2);",
                "godot_Variant fixed_arg_3 = godot_new_Variant_with_String(arg3);",
                "const GDExtensionConstVariantPtr fixed_args[] = {",
                "&fixed_arg_0,",
                "&fixed_arg_1,",
                "&fixed_arg_2,",
                "&fixed_arg_3",
                "const godot_int fixed_argc = (godot_int)4;",
                "GDExtensionConstVariantPtr final_args[4 + argc];",
                "final_args[i] = fixed_args[i];",
                "final_args[fixed_argc + i] = argv[i];",
                "char call_error_desc[512];",
                "engine method call failed: Probe.dispatch: invalid argument #%lld, expected '%s', got '%s'",
                "engine method call failed: Probe.dispatch: too many arguments, expected %lld, got %lld",
                "GDCC_PRINT_RUNTIME_ERROR(call_error_desc, __func__, __FILE__, __LINE__);",
                "godot_Variant_destroy(&fixed_arg_3);",
                "godot_Variant_destroy(&fixed_arg_2);",
                "godot_Variant_destroy(&fixed_arg_1);",
                "godot_Variant_destroy(&fixed_arg_0);"
        );
        assertFalse(dispatchBody.contains("arg1_slot"), dispatchBody);
        assertFalse(dispatchBody.contains("arg2_slot"), dispatchBody);
        assertFalse(dispatchBody.contains("godot_Probe_Mode"), dispatchBody);
        assertFalse(dispatchBody.contains("godot_Probe_Flags"), dispatchBody);
        assertOrderedFragments(
                dispatchBody,
                "if (error.error != GDEXTENSION_CALL_OK)",
                "goto cleanup;",
                "cleanup:",
                "godot_Variant_destroy(&ret);",
                "godot_Variant_destroy(&fixed_arg_3);",
                "godot_Variant_destroy(&fixed_arg_0);"
        );
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

    private static @NotNull LirInstruction.VariableOperand varOperand(@NotNull String name) {
        return new LirInstruction.VariableOperand(name);
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

    private static @NotNull ExtensionGdClass windowClassWithPropertyAccessors() {
        var getTitle = new ExtensionGdClass.ClassMethod(
                "get_title_override",
                false,
                false,
                false,
                false,
                81L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("String"),
                List.of()
        );
        var setTitle = new ExtensionGdClass.ClassMethod(
                "set_title_override",
                false,
                false,
                false,
                false,
                82L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("title", "String", null, null))
        );
        var getFlag = new ExtensionGdClass.ClassMethod(
                "get_flag",
                false,
                false,
                false,
                false,
                83L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("bool"),
                List.of(new ExtensionFunctionArgument("flag", "enum::Window.Flags", null, null))
        );
        var setFlag = new ExtensionGdClass.ClassMethod(
                "set_flag",
                false,
                false,
                false,
                false,
                84L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(
                        new ExtensionFunctionArgument("flag", "enum::Window.Flags", null, null),
                        new ExtensionFunctionArgument("enabled", "bool", null, null)
                )
        );
        return new ExtensionGdClass(
                "Window",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(getTitle, setTitle, getFlag, setFlag),
                List.of(),
                List.of(
                        new ExtensionGdClass.PropertyInfo(
                                "window_title",
                                "String",
                                true,
                                true,
                                "",
                                "get_title_override",
                                "set_title_override",
                                null
                        ),
                        new ExtensionGdClass.PropertyInfo(
                                "unresizable",
                                "bool",
                                true,
                                true,
                                "",
                                "get_flag",
                                "set_flag",
                                0
                        )
                ),
                List.of()
        );
    }

    private static @NotNull ExtensionGdClass nodeClass() {
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static @NotNull ExtensionGdClass refCountedClass() {
        return new ExtensionGdClass(
                "RefCounted",
                true,
                true,
                "Object",
                "core",
                List.of(),
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
        var dispatch = new ExtensionGdClass.ClassMethod(
                "dispatch",
                false,
                true,
                false,
                false,
                96L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("int"),
                List.of(
                        new ExtensionFunctionArgument("peer", "Probe", null, null),
                        new ExtensionFunctionArgument("mode", "enum::Probe.Mode", null, null),
                        new ExtensionFunctionArgument("flags", "bitfield::Probe.Flags", null, null),
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
                List.of(mix, dispatch, broadcast),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static @NotNull ExtensionGdClass probeClassWithVarargObjectReturn() {
        var makeRef = new ExtensionGdClass.ClassMethod(
                "make_ref",
                false,
                true,
                false,
                false,
                98L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("RefCounted"),
                List.of(new ExtensionFunctionArgument("label", "String", null, null))
        );
        return new ExtensionGdClass(
                "Probe",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(makeRef),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static @NotNull ExtensionGdClass probeClassWithDestroyableReturnHelper() {
        var name = new ExtensionGdClass.ClassMethod(
                "name",
                false,
                false,
                false,
                false,
                97L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("String"),
                List.of()
        );
        return new ExtensionGdClass(
                "Probe",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(name),
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

    private static @NotNull ExtensionGdClass probeClassWithCount(long hash, @NotNull List<Long> hashCompatibility) {
        var count = new ExtensionGdClass.ClassMethod(
                "count",
                false,
                false,
                false,
                false,
                hash,
                hashCompatibility,
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
                List.of(count),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
