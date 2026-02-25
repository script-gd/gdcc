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
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.insn.ConstructArrayInsn;
import dev.superice.gdcc.lir.insn.ConstructBuiltinInsn;
import dev.superice.gdcc.lir.insn.ConstructDictionaryInsn;
import dev.superice.gdcc.lir.insn.LiteralFloatInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdTransform2DType;
import dev.superice.gdcc.type.GdVariantType;
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

class CConstructInsnGenEngineTest {
    @Test
    @DisplayName("construct insns should generate runnable typed container and builtin constructors in real engine")
    void constructInsnsShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/construct_engine");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "construct_engine",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.WINDOWS_X86_64
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var constructClass = newConstructEngineClass();
        var module = new LirModule("construct_engine_module", List.of(constructClass));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "ConstructNode",
                        constructClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(testScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("explicit transform check passed."), "explicit transform should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("prepare transform check passed."), "prepare transform should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("explicit generic array check passed."), "explicit generic array should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("prepare generic array check passed."), "prepare generic array should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("explicit generic dictionary check passed."), "explicit generic dictionary should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("prepare generic dictionary check passed."), "prepare generic dictionary should pass.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    private static boolean hasZig() {
        return ZigUtil.findZig() != null;
    }

    private static LirClassDef newConstructEngineClass() {
        var clazz = new LirClassDef("GDConstructEngineNode", "Node");
        clazz.setSourceFile("construct_engine.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newExplicitTransformFunction(selfType));
        clazz.addFunction(newPrepareTransformFunction(selfType));
        clazz.addFunction(newExplicitGenericArrayFunction(selfType));
        clazz.addFunction(newPrepareGenericArrayFunction(selfType));
        clazz.addFunction(newExplicitGenericDictionaryFunction(selfType));
        clazz.addFunction(newPrepareGenericDictionaryFunction(selfType));
        return clazz;
    }

    private static LirFunctionDef newExplicitTransformFunction(GdObjectType selfType) {
        var func = newMethod("make_transform2d_explicit", GdTransform2DType.TRANSFORM2D, selfType);
        func.createAndAddVariable("xx", GdFloatType.FLOAT);
        func.createAndAddVariable("xy", GdFloatType.FLOAT);
        func.createAndAddVariable("yx", GdFloatType.FLOAT);
        func.createAndAddVariable("yy", GdFloatType.FLOAT);
        func.createAndAddVariable("tx", GdFloatType.FLOAT);
        func.createAndAddVariable("ty", GdFloatType.FLOAT);
        func.createAndAddVariable("t", GdTransform2DType.TRANSFORM2D);

        var block = entry(func);
        block.instructions().add(new LiteralFloatInsn("xx", 1.0));
        block.instructions().add(new LiteralFloatInsn("xy", 0.0));
        block.instructions().add(new LiteralFloatInsn("yx", 0.0));
        block.instructions().add(new LiteralFloatInsn("yy", 1.0));
        block.instructions().add(new LiteralFloatInsn("tx", 2.0));
        block.instructions().add(new LiteralFloatInsn("ty", 3.0));
        block.instructions().add(new ConstructBuiltinInsn(
                "t",
                List.of(
                        varRef("xx"),
                        varRef("xy"),
                        varRef("yx"),
                        varRef("yy"),
                        varRef("tx"),
                        varRef("ty")
                )
        ));
        block.instructions().add(new ReturnInsn("t"));
        return func;
    }

    private static LirFunctionDef newPrepareTransformFunction(GdObjectType selfType) {
        var func = newMethod("make_transform2d_prepare", GdTransform2DType.TRANSFORM2D, selfType);
        func.createAndAddVariable("t", GdTransform2DType.TRANSFORM2D);
        entry(func).instructions().add(new ReturnInsn("t"));
        return func;
    }

    private static LirFunctionDef newExplicitGenericArrayFunction(GdObjectType selfType) {
        var arrayType = new GdArrayType(GdVariantType.VARIANT);
        var func = newMethod("make_generic_array_explicit", arrayType, selfType);
        func.createAndAddVariable("arr", arrayType);
        entry(func).instructions().add(new ConstructArrayInsn("arr", null));
        entry(func).instructions().add(new ReturnInsn("arr"));
        return func;
    }

    private static LirFunctionDef newPrepareGenericArrayFunction(GdObjectType selfType) {
        var arrayType = new GdArrayType(GdVariantType.VARIANT);
        var func = newMethod("make_generic_array_prepare", arrayType, selfType);
        func.createAndAddVariable("arr", arrayType);
        entry(func).instructions().add(new ReturnInsn("arr"));
        return func;
    }

    private static LirFunctionDef newExplicitGenericDictionaryFunction(GdObjectType selfType) {
        var dictionaryType = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
        var func = newMethod("make_generic_dictionary_explicit", dictionaryType, selfType);
        func.createAndAddVariable("dict", dictionaryType);
        entry(func).instructions().add(new ConstructDictionaryInsn("dict", null, null));
        entry(func).instructions().add(new ReturnInsn("dict"));
        return func;
    }

    private static LirFunctionDef newPrepareGenericDictionaryFunction(GdObjectType selfType) {
        var dictionaryType = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
        var func = newMethod("make_generic_dictionary_prepare", dictionaryType, selfType);
        func.createAndAddVariable("dict", dictionaryType);
        entry(func).instructions().add(new ReturnInsn("dict"));
        return func;
    }

    private static LirFunctionDef newMethod(String name, dev.superice.gdcc.type.GdType returnType, GdObjectType selfType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        func.addParameter(new LirParameterDef("self", selfType, null, func));
        func.addBasicBlock(new LirBasicBlock("entry"));
        func.setEntryBlockId("entry");
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

                const TARGET_NODE_NAME = "ConstructNode"
                const EPSILON = 0.001

                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return

                    var transform_explicit = target.call("make_transform2d_explicit")
                    if _check_transform2d(transform_explicit, 2.0, 3.0):
                        print("explicit transform check passed.")
                    else:
                        push_error("explicit transform check failed.")

                    var transform_prepare = target.call("make_transform2d_prepare")
                    if _check_transform2d(transform_prepare, 0.0, 0.0):
                        print("prepare transform check passed.")
                    else:
                        push_error("prepare transform check failed.")

                    var arr_explicit = target.call("make_generic_array_explicit")
                    if _check_generic_array(arr_explicit):
                        print("explicit generic array check passed.")
                    else:
                        push_error("explicit generic array check failed.")

                    var arr_prepare = target.call("make_generic_array_prepare")
                    if _check_generic_array(arr_prepare):
                        print("prepare generic array check passed.")
                    else:
                        push_error("prepare generic array check failed.")

                    var dict_explicit = target.call("make_generic_dictionary_explicit")
                    if _check_generic_dictionary(dict_explicit):
                        print("explicit generic dictionary check passed.")
                    else:
                        push_error("explicit generic dictionary check failed.")

                    var dict_prepare = target.call("make_generic_dictionary_prepare")
                    if _check_generic_dictionary(dict_prepare):
                        print("prepare generic dictionary check passed.")
                    else:
                        push_error("prepare generic dictionary check failed.")

                func _check_transform2d(value: Variant, tx: float, ty: float) -> bool:
                    if typeof(value) != TYPE_TRANSFORM2D:
                        return false
                    var t: Transform2D = value
                    return absf(t.x.x - 1.0) <= EPSILON \
                            and absf(t.x.y - 0.0) <= EPSILON \
                            and absf(t.y.x - 0.0) <= EPSILON \
                            and absf(t.y.y - 1.0) <= EPSILON \
                            and absf(t.origin.x - tx) <= EPSILON \
                            and absf(t.origin.y - ty) <= EPSILON

                func _check_generic_array(value: Variant) -> bool:
                    if typeof(value) != TYPE_ARRAY:
                        return false
                    var arr: Array = value
                    return not arr.is_typed() and arr.size() == 0

                func _check_generic_dictionary(value: Variant) -> bool:
                    if typeof(value) != TYPE_DICTIONARY:
                        return false
                    var dict: Dictionary = value
                    return not dict.is_typed() and dict.is_empty()
                """;
    }
}
