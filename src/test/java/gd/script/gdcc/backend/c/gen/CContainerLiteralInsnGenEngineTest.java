package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.CProjectBuilder;
import gd.script.gdcc.backend.c.build.CProjectInfo;
import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.ZigUtil;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.ConstructContainerLiteralInsn;
import gd.script.gdcc.lir.insn.LiteralFloatInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
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

/// Engine integration for `construct_container_literal` fill paths.
class CContainerLiteralInsnGenEngineTest {

    @Test
    @DisplayName("container literal fill should run in real Godot with typed metadata and value checks")
    void containerLiteralFillShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/container_literal_engine");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "container_literal_engine",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var engineClass = newContainerLiteralEngineClass();
        var module = new LirModule("container_literal_engine_module", List.of(engineClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(entrySource.contains("godot_Array_push_back"), entrySource);
        assertTrue(entrySource.contains("godot_Dictionary_set"), entrySource);
        assertTrue(entrySource.contains("godot_new_Array()"), entrySource);
        assertTrue(entrySource.contains("godot_new_Array_with_Array_int_StringName_Variant"), entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "ContainerLiteralNode",
                        engineClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(testScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("empty array check passed."), combinedOutput);
        assertTrue(combinedOutput.contains("generic array fill check passed."), combinedOutput);
        assertTrue(combinedOutput.contains("typed array int fill check passed."), combinedOutput);
        assertTrue(combinedOutput.contains("typed array float fill check passed."), combinedOutput);
        assertTrue(combinedOutput.contains("generic dictionary fill check passed."), combinedOutput);
        assertTrue(combinedOutput.contains("typed dictionary fill check passed."), combinedOutput);
        assertTrue(combinedOutput.contains("duplicate key overwrite check passed."), combinedOutput);
        assertTrue(combinedOutput.contains("nested array fill check passed."), combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    private static boolean hasZig() {
        return ZigUtil.findZig() != null;
    }

    private static LirClassDef newContainerLiteralEngineClass() {
        var clazz = new LirClassDef("GDContainerLiteralEngineNode", "Node");
        clazz.setSourceFile("container_literal_engine.gd");
        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newEmptyArrayFunction(selfType));
        clazz.addFunction(newGenericArrayFillFunction(selfType));
        clazz.addFunction(newTypedArrayIntFillFunction(selfType));
        clazz.addFunction(newTypedArrayFloatFillFunction(selfType));
        clazz.addFunction(newGenericDictionaryFillFunction(selfType));
        clazz.addFunction(newTypedDictionaryFillFunction(selfType));
        clazz.addFunction(newDuplicateKeyDictionaryFunction(selfType));
        clazz.addFunction(newNestedArrayFillFunction(selfType));
        return clazz;
    }

    private static LirFunctionDef newEmptyArrayFunction(GdObjectType selfType) {
        var arrayType = new GdArrayType(GdVariantType.VARIANT);
        // Shared 0-arg Array binding helper with other generic Array[Variant] methods.
        var func = newMethod("make_empty_array", arrayType, selfType);
        func.createAndAddVariable("arr", arrayType);
        entry(func).appendInstruction(new ConstructContainerLiteralInsn("arr", List.of()));
        entry(func).appendInstruction(new ReturnInsn("arr"));
        return func;
    }

    private static LirFunctionDef newGenericArrayFillFunction(GdObjectType selfType) {
        var arrayType = new GdArrayType(GdVariantType.VARIANT);
        var func = newMethod("make_generic_array_fill", arrayType, selfType);
        func.createAndAddVariable("a", GdIntType.INT);
        func.createAndAddVariable("b", GdStringType.STRING);
        func.createAndAddVariable("arr", arrayType);
        var block = entry(func);
        block.appendInstruction(new LiteralIntInsn("a", 7));
        block.appendInstruction(new LiteralStringInsn("b", "hi"));
        block.appendInstruction(new ConstructContainerLiteralInsn(
                "arr",
                List.of(varRef("a"), varRef("b"))
        ));
        block.appendInstruction(new ReturnInsn("arr"));
        return func;
    }

    private static LirFunctionDef newTypedArrayIntFillFunction(GdObjectType selfType) {
        var arrayType = new GdArrayType(GdIntType.INT);
        // Distinct marker type so bind helper name does not collide with generic Array methods.
        var func = newMethodWithMarker("make_typed_array_int_fill", arrayType, selfType, GdStringType.STRING);
        func.createAndAddVariable("x", GdIntType.INT);
        func.createAndAddVariable("y", GdIntType.INT);
        func.createAndAddVariable("arr", arrayType);
        var block = entry(func);
        block.appendInstruction(new LiteralIntInsn("x", 1));
        block.appendInstruction(new LiteralIntInsn("y", 2));
        block.appendInstruction(new ConstructContainerLiteralInsn(
                "arr",
                List.of(varRef("x"), varRef("y"))
        ));
        block.appendInstruction(new ReturnInsn("arr"));
        return func;
    }

    private static LirFunctionDef newTypedArrayFloatFillFunction(GdObjectType selfType) {
        // Frontend would cast int->float before this insn; backend only packs float carriers.
        var arrayType = new GdArrayType(GdFloatType.FLOAT);
        var func = newMethodWithMarker("make_typed_array_float_fill", arrayType, selfType, GdIntType.INT);
        func.createAndAddVariable("f0", GdFloatType.FLOAT);
        func.createAndAddVariable("f1", GdFloatType.FLOAT);
        func.createAndAddVariable("arr", arrayType);
        var block = entry(func);
        block.appendInstruction(new LiteralFloatInsn("f0", 1.5));
        block.appendInstruction(new LiteralFloatInsn("f1", 2.5));
        block.appendInstruction(new ConstructContainerLiteralInsn(
                "arr",
                List.of(varRef("f0"), varRef("f1"))
        ));
        block.appendInstruction(new ReturnInsn("arr"));
        return func;
    }

    private static LirFunctionDef newGenericDictionaryFillFunction(GdObjectType selfType) {
        var dictionaryType = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
        var func = newMethod("make_generic_dictionary_fill", dictionaryType, selfType);
        func.createAndAddVariable("k", GdStringType.STRING);
        func.createAndAddVariable("v", GdIntType.INT);
        func.createAndAddVariable("dict", dictionaryType);
        var block = entry(func);
        block.appendInstruction(new LiteralStringInsn("k", "score"));
        block.appendInstruction(new LiteralIntInsn("v", 42));
        block.appendInstruction(new ConstructContainerLiteralInsn(
                "dict",
                List.of(varRef("k"), varRef("v"))
        ));
        block.appendInstruction(new ReturnInsn("dict"));
        return func;
    }

    private static LirFunctionDef newTypedDictionaryFillFunction(GdObjectType selfType) {
        var dictionaryType = new GdDictionaryType(GdStringType.STRING, GdIntType.INT);
        var func = newMethodWithMarker("make_typed_dictionary_fill", dictionaryType, selfType, GdBoolType.BOOL);
        func.createAndAddVariable("k", GdStringType.STRING);
        func.createAndAddVariable("v", GdIntType.INT);
        func.createAndAddVariable("dict", dictionaryType);
        var block = entry(func);
        block.appendInstruction(new LiteralStringInsn("k", "n"));
        block.appendInstruction(new LiteralIntInsn("v", 9));
        block.appendInstruction(new ConstructContainerLiteralInsn(
                "dict",
                List.of(varRef("k"), varRef("v"))
        ));
        block.appendInstruction(new ReturnInsn("dict"));
        return func;
    }

    private static LirFunctionDef newDuplicateKeyDictionaryFunction(GdObjectType selfType) {
        var dictionaryType = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
        var func = newMethod("make_duplicate_key_dictionary", dictionaryType, selfType);
        func.createAndAddVariable("k0", GdStringType.STRING);
        func.createAndAddVariable("v0", GdIntType.INT);
        func.createAndAddVariable("k1", GdStringType.STRING);
        func.createAndAddVariable("v1", GdIntType.INT);
        func.createAndAddVariable("dict", dictionaryType);
        var block = entry(func);
        block.appendInstruction(new LiteralStringInsn("k0", "dup"));
        block.appendInstruction(new LiteralIntInsn("v0", 1));
        block.appendInstruction(new LiteralStringInsn("k1", "dup"));
        block.appendInstruction(new LiteralIntInsn("v1", 2));
        block.appendInstruction(new ConstructContainerLiteralInsn(
                "dict",
                List.of(varRef("k0"), varRef("v0"), varRef("k1"), varRef("v1"))
        ));
        block.appendInstruction(new ReturnInsn("dict"));
        return func;
    }

    private static LirFunctionDef newNestedArrayFillFunction(GdObjectType selfType) {
        var outerType = new GdArrayType(GdVariantType.VARIANT);
        var innerType = new GdArrayType(GdVariantType.VARIANT);
        var func = newMethod("make_nested_array_fill", outerType, selfType);
        func.createAndAddVariable("inner_elem", GdIntType.INT);
        func.createAndAddVariable("inner", innerType);
        func.createAndAddVariable("outer", outerType);
        var block = entry(func);
        block.appendInstruction(new LiteralIntInsn("inner_elem", 3));
        block.appendInstruction(new ConstructContainerLiteralInsn(
                "inner",
                List.of(varRef("inner_elem"))
        ));
        block.appendInstruction(new ConstructContainerLiteralInsn(
                "outer",
                List.of(varRef("inner"))
        ));
        block.appendInstruction(new ReturnInsn("outer"));
        return func;
    }

    private static LirFunctionDef newMethod(String name, GdType returnType, GdObjectType selfType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        func.addParameter(new LirParameterDef("self", selfType, null, func));
        var entry = new LirBasicBlock("entry");
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    /// Extra parameter type distinguishes bind helper names when return types render identically
    /// (`Array` / `Dictionary`) but BindingData equality differs (typed vs generic).
    private static LirFunctionDef newMethodWithMarker(
            String name,
            GdType returnType,
            GdObjectType selfType,
            GdType markerType
    ) {
        var func = newMethod(name, returnType, selfType);
        func.addParameter(new LirParameterDef("marker", markerType, null, func));
        return func;
    }

    private static LirBasicBlock entry(LirFunctionDef functionDef) {
        return functionDef.getBasicBlock("entry");
    }

    private static LirInstruction.VariableOperand varRef(String id) {
        return new LirInstruction.VariableOperand(id);
    }

    private static String testScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "ContainerLiteralNode"
                const EPSILON = 0.0001
                
                func _ready() -> void:
                	var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                	if target == null:
                		push_error("Target node missing.")
                		return
                
                	var empty_arr = target.call("make_empty_array")
                	if _check_empty_array(empty_arr):
                		print("empty array check passed.")
                	else:
                		push_error("empty array check failed.")
                
                	var generic_arr = target.call("make_generic_array_fill")
                	if _check_generic_array_fill(generic_arr):
                		print("generic array fill check passed.")
                	else:
                		push_error("generic array fill check failed.")
                
                	# Dummy marker args only disambiguate C bind helper names; ignored by method bodies.
                	var typed_int_arr = target.call("make_typed_array_int_fill", "")
                	if _check_typed_array_int(typed_int_arr):
                		print("typed array int fill check passed.")
                	else:
                		push_error("typed array int fill check failed.")
                
                	var typed_float_arr = target.call("make_typed_array_float_fill", 0)
                	if _check_typed_array_float(typed_float_arr):
                		print("typed array float fill check passed.")
                	else:
                		push_error("typed array float fill check failed.")
                
                	var generic_dict = target.call("make_generic_dictionary_fill")
                	if _check_generic_dictionary_fill(generic_dict):
                		print("generic dictionary fill check passed.")
                	else:
                		push_error("generic dictionary fill check failed.")
                
                	var typed_dict = target.call("make_typed_dictionary_fill", false)
                	if _check_typed_dictionary_fill(typed_dict):
                		print("typed dictionary fill check passed.")
                	else:
                		push_error("typed dictionary fill check failed.")
                
                	var dup_dict = target.call("make_duplicate_key_dictionary")
                	if _check_duplicate_key(dup_dict):
                		print("duplicate key overwrite check passed.")
                	else:
                		push_error("duplicate key overwrite check failed.")
                
                	var nested = target.call("make_nested_array_fill")
                	if _check_nested_array(nested):
                		print("nested array fill check passed.")
                	else:
                		push_error("nested array fill check failed.")
                
                func _check_empty_array(value: Variant) -> bool:
                	if typeof(value) != TYPE_ARRAY:
                		return false
                	var arr: Array = value
                	return not arr.is_typed() and arr.size() == 0
                
                func _check_generic_array_fill(value: Variant) -> bool:
                	if typeof(value) != TYPE_ARRAY:
                		return false
                	var arr: Array = value
                	return not arr.is_typed() and arr.size() == 2 and int(arr[0]) == 7 and String(arr[1]) == "hi"
                
                func _check_typed_array_int(value: Variant) -> bool:
                	if typeof(value) != TYPE_ARRAY:
                		return false
                	var arr: Array = value
                	return arr.is_typed() \\
                			and arr.get_typed_builtin() == TYPE_INT \\
                			and arr.size() == 2 \\
                			and int(arr[0]) == 1 \\
                			and int(arr[1]) == 2
                
                func _check_typed_array_float(value: Variant) -> bool:
                	if typeof(value) != TYPE_ARRAY:
                		return false
                	var arr: Array = value
                	return arr.is_typed() \\
                			and arr.get_typed_builtin() == TYPE_FLOAT \\
                			and arr.size() == 2 \\
                			and absf(float(arr[0]) - 1.5) <= EPSILON \\
                			and absf(float(arr[1]) - 2.5) <= EPSILON
                
                func _check_generic_dictionary_fill(value: Variant) -> bool:
                	if typeof(value) != TYPE_DICTIONARY:
                		return false
                	var dict: Dictionary = value
                	return not dict.is_typed() and dict.size() == 1 and int(dict["score"]) == 42
                
                func _check_typed_dictionary_fill(value: Variant) -> bool:
                	if typeof(value) != TYPE_DICTIONARY:
                		return false
                	var dict: Dictionary = value
                	return dict.is_typed() \\
                			and dict.get_typed_key_builtin() == TYPE_STRING \\
                			and dict.get_typed_value_builtin() == TYPE_INT \\
                			and dict.size() == 1 \\
                			and int(dict["n"]) == 9
                
                func _check_duplicate_key(value: Variant) -> bool:
                	if typeof(value) != TYPE_DICTIONARY:
                		return false
                	var dict: Dictionary = value
                	return dict.size() == 1 and int(dict["dup"]) == 2
                
                func _check_nested_array(value: Variant) -> bool:
                	if typeof(value) != TYPE_ARRAY:
                		return false
                	var outer: Array = value
                	if outer.size() != 1:
                		return false
                	var inner: Array = outer[0]
                	return inner.size() == 1 and int(inner[0]) == 3
                """;
    }
}
