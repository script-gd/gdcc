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
import dev.superice.gdcc.lir.insn.CallGlobalInsn;
import dev.superice.gdcc.lir.insn.LiteralStringInsn;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.lir.insn.UnpackVariantInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdFloatType;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdStringType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
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

class CallGlobalInsnGenEngineTest {

    @Test
    @DisplayName("CALL_GLOBAL should call tan/fposmod/lerp/max/print in real engine")
    void callGlobalUtilitiesShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/call_global_engine");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "call_global_engine",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.WINDOWS_X86_64
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var callGlobalClass = newCallGlobalEngineClass();
        var module = new LirModule("call_global_engine_module", List.of(callGlobalClass));
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
                        "CallGlobalNode",
                        callGlobalClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(testScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("tan check passed."), "tan should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("fposmod check passed."), "fposmod should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("lerp check passed."), "lerp should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("max check passed."), "max should pass.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("[engine] call_print from extension"), "print should be emitted by extension call.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    private static boolean hasZig() {
        return ZigUtil.findZig() != null;
    }

    private static LirClassDef newCallGlobalEngineClass() {
        var clazz = new LirClassDef("GDCallGlobalEngineNode", "Node");
        clazz.setSourceFile("call_global_engine.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newTanFunction(selfType));
        clazz.addFunction(newFposmodFunction(selfType));
        clazz.addFunction(newLerpFunction(selfType));
        clazz.addFunction(newMaxFunction(selfType));
        clazz.addFunction(newPrintFunction(selfType));
        return clazz;
    }

    private static LirFunctionDef newTanFunction(GdObjectType selfType) {
        var func = newMethod("call_tan", GdFloatType.FLOAT, selfType);
        func.addParameter(new LirParameterDef("angleRad", GdFloatType.FLOAT, null, func));
        func.createAndAddVariable("result", GdFloatType.FLOAT);

        entry(func).instructions().add(new CallGlobalInsn(
                "result",
                "tan",
                List.of(varRef("angleRad"))
        ));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newFposmodFunction(GdObjectType selfType) {
        var func = newMethod("call_fposmod", GdFloatType.FLOAT, selfType);
        func.addParameter(new LirParameterDef("x", GdFloatType.FLOAT, null, func));
        func.addParameter(new LirParameterDef("y", GdFloatType.FLOAT, null, func));
        func.createAndAddVariable("result", GdFloatType.FLOAT);

        entry(func).instructions().add(new CallGlobalInsn(
                "result",
                "fposmod",
                List.of(varRef("x"), varRef("y"))
        ));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newLerpFunction(GdObjectType selfType) {
        var func = newMethod("call_lerp", GdFloatType.FLOAT, selfType);
        func.addParameter(new LirParameterDef("from", GdFloatType.FLOAT, null, func));
        func.addParameter(new LirParameterDef("to", GdFloatType.FLOAT, null, func));
        func.addParameter(new LirParameterDef("weight", GdFloatType.FLOAT, null, func));

        func.createAndAddVariable("fromVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("toVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("weightVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("resultVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("result", GdFloatType.FLOAT);

        entry(func).instructions().add(new PackVariantInsn("fromVariant", "from"));
        entry(func).instructions().add(new PackVariantInsn("toVariant", "to"));
        entry(func).instructions().add(new PackVariantInsn("weightVariant", "weight"));
        entry(func).instructions().add(new CallGlobalInsn(
                "resultVariant",
                "lerp",
                List.of(varRef("fromVariant"), varRef("toVariant"), varRef("weightVariant"))
        ));
        entry(func).instructions().add(new UnpackVariantInsn("result", "resultVariant"));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newMaxFunction(GdObjectType selfType) {
        var func = newMethod("call_max", GdFloatType.FLOAT, selfType);
        func.addParameter(new LirParameterDef("a", GdFloatType.FLOAT, null, func));
        func.addParameter(new LirParameterDef("b", GdFloatType.FLOAT, null, func));
        func.addParameter(new LirParameterDef("c", GdFloatType.FLOAT, null, func));

        func.createAndAddVariable("aVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("bVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("cVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("resultVariant", GdVariantType.VARIANT);
        func.createAndAddVariable("result", GdFloatType.FLOAT);

        entry(func).instructions().add(new PackVariantInsn("aVariant", "a"));
        entry(func).instructions().add(new PackVariantInsn("bVariant", "b"));
        entry(func).instructions().add(new PackVariantInsn("cVariant", "c"));
        entry(func).instructions().add(new CallGlobalInsn(
                "resultVariant",
                "max",
                List.of(varRef("aVariant"), varRef("bVariant"), varRef("cVariant"))
        ));
        entry(func).instructions().add(new UnpackVariantInsn("result", "resultVariant"));
        entry(func).instructions().add(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newPrintFunction(GdObjectType selfType) {
        var func = newMethod("call_print", GdVoidType.VOID, selfType);
        func.createAndAddVariable("messageText", GdStringType.STRING);
        func.createAndAddVariable("messageVariant", GdVariantType.VARIANT);

        entry(func).instructions().add(new LiteralStringInsn("messageText", "[engine] call_print from extension"));
        entry(func).instructions().add(new PackVariantInsn("messageVariant", "messageText"));
        entry(func).instructions().add(new CallGlobalInsn(
                null,
                "print",
                List.of(varRef("messageVariant"))
        ));
        entry(func).instructions().add(new ReturnInsn(null));
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

                const TARGET_NODE_NAME = "CallGlobalNode"
                const EPSILON = 0.001

                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return

                    var tan_value = float(target.call("call_tan", 0.5))
                    if absf(tan_value - tan(0.5)) <= EPSILON:
                        print("tan check passed.")
                    else:
                        push_error("tan check failed.")

                    var fposmod_value = float(target.call("call_fposmod", -1.5, 1.0))
                    if absf(fposmod_value - fposmod(-1.5, 1.0)) <= EPSILON:
                        print("fposmod check passed.")
                    else:
                        push_error("fposmod check failed.")

                    var lerp_value = float(target.call("call_lerp", 10.0, 20.0, 0.25))
                    if absf(lerp_value - lerp(10.0, 20.0, 0.25)) <= EPSILON:
                        print("lerp check passed.")
                    else:
                        push_error("lerp check failed.")

                    var max_value = float(target.call("call_max", 1.25, 4.5, 2.75))
                    if absf(max_value - max(1.25, 4.5, 2.75)) <= EPSILON:
                        print("max check passed.")
                    else:
                        push_error("max check failed.")

                    target.call("call_print")
                """;
    }
}
