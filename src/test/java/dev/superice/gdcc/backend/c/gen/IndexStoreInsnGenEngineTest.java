package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.CProjectBuilder;
import dev.superice.gdcc.backend.c.build.CProjectInfo;
import dev.superice.gdcc.backend.c.build.GodotGdextensionTestRunner;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.backend.c.build.ZigUtil;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.insn.LiteralIntInsn;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.lir.insn.UnpackVariantInsn;
import dev.superice.gdcc.lir.insn.VariantGetInsn;
import dev.superice.gdcc.lir.insn.VariantGetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantGetKeyedInsn;
import dev.superice.gdcc.lir.insn.VariantGetNamedInsn;
import dev.superice.gdcc.lir.insn.VariantSetInsn;
import dev.superice.gdcc.lir.insn.VariantSetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantSetKeyedInsn;
import dev.superice.gdcc.lir.insn.VariantSetNamedInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPackedNumericArrayType;
import dev.superice.gdcc.type.GdStringNameType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexStoreInsnGenEngineTest {

    @Test
    @DisplayName("index store ref semantics should read back correctly in real engine")
    void variantSetRefContainersShouldReadBackWithoutWritebackInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/index_store_engine_ref_container");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "index_store_engine_ref_container",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var testClass = newIndexStoreEngineClass();
        var module = new LirModule("index_store_engine_ref_container_module", List.of(testClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(entrySource.contains("godot_new_Variant_with_Array($arr)"), entrySource);
        assertTrue(entrySource.contains("godot_new_Variant_with_Dictionary($dict)"), entrySource);
        assertTrue(entrySource.contains("godot_new_Variant_with_PackedInt32Array($packed)"), entrySource);
        assertTrue(entrySource.contains("(GDExtensionInt)$idx"), entrySource);
        assertTrue(entrySource.contains("godot_new_Variant_with_int($value)"), entrySource);
        assertTrue(entrySource.contains("godot_new_Variant_with_String($key)"), entrySource);
        assertTrue(entrySource.contains("godot_new_Variant_with_String($value)"), entrySource);
        assertTrue(entrySource.contains("godot_variant_set_named("), entrySource);
        assertTrue(entrySource.contains("godot_variant_set_indexed("), entrySource);
        assertTrue(entrySource.contains("godot_variant_set("), entrySource);
        assertFalse(entrySource.contains("$arr = godot_new_Array_with_Variant("), entrySource);
        assertFalse(entrySource.contains("$dict = godot_new_Dictionary_with_Variant("), entrySource);
        assertTrue(entrySource.contains("$packed_local = godot_new_PackedInt32Array_with_Variant(&__gdcc_tmp_idx_self_variant_"), entrySource);
        assertFalse(entrySource.contains("*$packed ="), entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "IndexStoreNode",
                        testClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(testScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("index store array ref check passed."), "Array check should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("index store dictionary ref check passed."), "Dictionary check should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("index store array ref index/value check passed."), "Array ref index/value check should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("index store dictionary ref key/value check passed."), "Dictionary ref key/value check should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("index store dictionary named ref check passed."), "Dictionary named ref check should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("index store dictionary string ref check passed."), "Dictionary string ref check should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("index store packed ref check passed."), "Packed array check should pass.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    private static boolean hasZig() {
        return ZigUtil.findZig() != null;
    }

    private static @NotNull LirClassDef newIndexStoreEngineClass() {
        var clazz = new LirClassDef("GDIndexStoreEngineNode", "Node");
        clazz.setSourceFile("index_store_engine.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newArrayRefSetGetFunction(selfType));
        clazz.addFunction(newDictionaryRefSetGetFunction(selfType));
        clazz.addFunction(newArrayRefSetGetWithRefIndexValueFunction(selfType));
        clazz.addFunction(newDictionaryRefSetGetWithRefKeyValueFunction(selfType));
        clazz.addFunction(newDictionaryRefSetNamedGetWithRefNameValueFunction(selfType));
        clazz.addFunction(newDictionaryRefSetKeyedWithRefStringKeyValueFunction(selfType));
        clazz.addFunction(newPackedInt32RefSetGetFunction(selfType));
        return clazz;
    }

    private static @NotNull LirFunctionDef newArrayRefSetGetFunction(@NotNull GdObjectType selfType) {
        var func = newMethod("array_ref_set_get", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("arr", new GdArrayType(GdVariantType.VARIANT), null, func));
        func.createAndAddVariable("idx", GdIntType.INT);
        func.createAndAddVariable("value", GdIntType.INT);
        func.createAndAddVariable("result", GdIntType.INT);

        var entry = entry(func);
        entry.instructions().add(new LiteralIntInsn("idx", 1));
        entry.instructions().add(new LiteralIntInsn("value", 77));
        entry.instructions().add(new VariantSetIndexedInsn("arr", "idx", "value"));
        entry.instructions().add(new VariantGetIndexedInsn("result", "arr", "idx"));
        entry.instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newDictionaryRefSetGetFunction(@NotNull GdObjectType selfType) {
        var func = newMethod("dictionary_ref_set_get", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("dict", new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT), null, func));
        func.createAndAddVariable("key", GdIntType.INT);
        func.createAndAddVariable("value", GdIntType.INT);
        func.createAndAddVariable("result", GdIntType.INT);

        var entry = entry(func);
        entry.instructions().add(new LiteralIntInsn("key", 123));
        entry.instructions().add(new LiteralIntInsn("value", 456));
        entry.instructions().add(new VariantSetKeyedInsn("dict", "key", "value"));
        entry.instructions().add(new VariantGetKeyedInsn("result", "dict", "key"));
        entry.instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newArrayRefSetGetWithRefIndexValueFunction(@NotNull GdObjectType selfType) {
        var func = newMethod("array_ref_set_get_with_ref_index_value", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("arr", new GdArrayType(GdVariantType.VARIANT), null, func));
        func.addParameter(new LirParameterDef("idx", GdIntType.INT, null, func));
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        var entry = entry(func);
        entry.instructions().add(new VariantSetIndexedInsn("arr", "idx", "value"));
        entry.instructions().add(new VariantGetIndexedInsn("result", "arr", "idx"));
        entry.instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newDictionaryRefSetGetWithRefKeyValueFunction(@NotNull GdObjectType selfType) {
        var func = newMethod("dictionary_ref_set_get_with_ref_key_value", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("dict", new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT), null, func));
        func.addParameter(new LirParameterDef("key", GdIntType.INT, null, func));
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        var entry = entry(func);
        entry.instructions().add(new VariantSetInsn("dict", "key", "value"));
        entry.instructions().add(new VariantGetInsn("result", "dict", "key"));
        entry.instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newDictionaryRefSetNamedGetWithRefNameValueFunction(@NotNull GdObjectType selfType) {
        var func = newMethod("dictionary_ref_set_named_get_with_ref_name_value", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("dict", new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT), null, func));
        func.addParameter(new LirParameterDef("name", GdStringNameType.STRING_NAME, null, func));
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        var entry = entry(func);
        entry.instructions().add(new VariantSetNamedInsn("dict", "name", "value"));
        entry.instructions().add(new VariantGetNamedInsn("result", "dict", "name"));
        entry.instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newDictionaryRefSetKeyedWithRefStringKeyValueFunction(@NotNull GdObjectType selfType) {
        var func = newMethod("dictionary_ref_set_keyed_with_ref_string_key_value", GdStringType.STRING, selfType);
        func.addParameter(new LirParameterDef("dict", new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT), null, func));
        func.addParameter(new LirParameterDef("key", GdStringType.STRING, null, func));
        func.addParameter(new LirParameterDef("value", GdStringType.STRING, null, func));
        func.createAndAddVariable("result", GdStringType.STRING);

        var entry = entry(func);
        entry.instructions().add(new VariantSetKeyedInsn("dict", "key", "value"));
        entry.instructions().add(new VariantGetKeyedInsn("result", "dict", "key"));
        entry.instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newPackedInt32RefSetGetFunction(@NotNull GdObjectType selfType) {
        var func = newMethod("packed_int32_ref_set_get", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY, null, func));
        func.createAndAddVariable("packed_variant", GdVariantType.VARIANT);
        func.createAndAddVariable("packed_local", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        func.createAndAddVariable("idx", GdIntType.INT);
        func.createAndAddVariable("value", GdIntType.INT);
        func.createAndAddVariable("result", GdIntType.INT);

        var entry = entry(func);
        entry.instructions().add(new LiteralIntInsn("idx", 0));
        entry.instructions().add(new LiteralIntInsn("value", 66));
        entry.instructions().add(new PackVariantInsn("packed_variant", "packed"));
        entry.instructions().add(new UnpackVariantInsn("packed_local", "packed_variant"));
        entry.instructions().add(new VariantSetIndexedInsn("packed_local", "idx", "value"));
        entry.instructions().add(new VariantGetIndexedInsn("result", "packed_local", "idx"));
        entry.instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static @NotNull LirFunctionDef newMethod(@NotNull String name,
                                                     @NotNull GdType returnType,
                                                     @NotNull GdObjectType selfType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        func.addParameter(new LirParameterDef("self", selfType, null, func));
        func.addBasicBlock(new LirBasicBlock("entry"));
        func.setEntryBlockId("entry");
        return func;
    }

    private static @NotNull LirBasicBlock entry(@NotNull LirFunctionDef functionDef) {
        return functionDef.getBasicBlock("entry");
    }

    private static @NotNull String testScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "IndexStoreNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var arr = [10, 20, 30]
                    var arr_result = int(target.call("array_ref_set_get", arr))
                    if arr_result == 77 and int(arr[1]) == 77:
                        print("index store array ref check passed.")
                    else:
                        push_error("index store array ref check failed.")
                
                    var dict = {1: 5}
                    var dict_result = int(target.call("dictionary_ref_set_get", dict))
                    if dict_result == 456 and int(dict[123]) == 456:
                        print("index store dictionary ref check passed.")
                    else:
                        push_error("index store dictionary ref check failed.")
                
                    var arr_ref_args = [1, 2, 3]
                    var arr_ref_idx = 2
                    var arr_ref_value = 88
                    var arr_ref_args_result = int(target.call("array_ref_set_get_with_ref_index_value", arr_ref_args, arr_ref_idx, arr_ref_value))
                    if arr_ref_args_result == 88 and int(arr_ref_args[2]) == 88:
                        print("index store array ref index/value check passed.")
                    else:
                        push_error("index store array ref index/value check failed.")
                
                    var dict_ref_args = {4: 40}
                    var dict_ref_key = 9
                    var dict_ref_value = 333
                    var dict_ref_args_result = int(target.call("dictionary_ref_set_get_with_ref_key_value", dict_ref_args, dict_ref_key, dict_ref_value))
                    if dict_ref_args_result == 333 and dict_ref_args.has(9) and int(dict_ref_args[9]) == 333:
                        print("index store dictionary ref key/value check passed.")
                    else:
                        push_error("index store dictionary ref key/value check failed.")
                
                    var dict_named = {}
                    var prop_name = &"hp"
                    var named_value = 999
                    var dict_named_result = int(target.call("dictionary_ref_set_named_get_with_ref_name_value", dict_named, prop_name, named_value))
                    if dict_named_result == 999 and dict_named.has(&"hp") and int(dict_named[&"hp"]) == 999:
                        print("index store dictionary named ref check passed.")
                    else:
                        push_error("index store dictionary named ref check failed.")
                
                    var dict_str = {}
                    var str_key = "title"
                    var str_value = "gdcc"
                    var dict_str_result = str(target.call("dictionary_ref_set_keyed_with_ref_string_key_value", dict_str, str_key, str_value))
                    if dict_str_result == "gdcc" and dict_str.has("title") and str(dict_str["title"]) == "gdcc":
                        print("index store dictionary string ref check passed.")
                    else:
                        push_error("index store dictionary string ref check failed.")
                
                    var packed = PackedInt32Array([9, 8, 7])
                    var packed_result = int(target.call("packed_int32_ref_set_get", packed))
                    if packed_result == 66:
                        print("index store packed ref check passed.")
                    else:
                        push_error("index store packed ref check failed.")
                """;
    }
}
