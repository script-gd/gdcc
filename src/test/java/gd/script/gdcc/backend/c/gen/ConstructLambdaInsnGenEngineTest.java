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
import gd.script.gdcc.lir.LirCaptureDef;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.insn.AwaitInsn;
import gd.script.gdcc.lir.insn.ConstructLambdaInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.StorePropertyInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
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

/// Generated lambda Callables must call, copy captures, and bind `object_id`.
final class ConstructLambdaInsnGenEngineTest {
    @Test
    @DisplayName("construct_lambda should compile and run captureless / capture / self-object_id cases")
    void constructLambdaShouldRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/construct_lambda_engine");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "construct_lambda_engine",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var clazz = newLambdaEngineClass();
        var module = new LirModule("construct_lambda_engine_module", List.of(clazz));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");
        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        var headerSource = Files.readString(tempDir.resolve("entry.h"));
        assertTrue(entrySource.contains("gdcc_new_lambda_callable("), entrySource);
        assertTrue(headerSource.contains("GDLambdaEngineNode_Capture__lambda_1"), headerSource);
        assertTrue(headerSource.contains("godot_object_get_instance_from_id(captures->self.instance_id)"), headerSource);

        if (GodotGdextensionTestRunner.findGodotBinaryFromEnv() == null) {
            Assumptions.abort("GODOT_BIN not found; skipping runtime integration test");
            return;
        }

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "LambdaNode",
                        clazz.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(testScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();
        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("captureless lambda check passed."), combinedOutput);
        assertTrue(combinedOutput.contains("captured string check passed."), combinedOutput);
        assertTrue(combinedOutput.contains("self object_id check passed."), combinedOutput);
        assertTrue(combinedOutput.contains("freed self-lambda stays invalid check passed."), combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), combinedOutput);
    }

    @Test
    @DisplayName("suspended coroutine lambda resumes from its capture frame after the Callable is released")
    void constructCoroutineLambdaContinuesAfterCallableRelease() throws IOException, InterruptedException {
        // Core acceptance on a real engine: a coroutine lambda is invoked through
        // the Callable ABI, suspends on a signal, and then both the Callable (capture block) and
        // the returned state handle are released. The suspended coroutine must stay alive purely
        // on its signal-wait edge (the one-shot connection's custom Callable holds the self state
        // reference, spec §3.6), resume when the signal fires, and read its typed capture frame
        // fields — proving the per-call frame copy outlives the capture block.
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/construct_coroutine_lambda_engine");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "construct_coroutine_lambda_engine",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var clazz = newCoroLambdaEngineClass();
        var module = new LirModule("construct_coroutine_lambda_engine_module", List.of(clazz));
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");
        var headerSource = Files.readString(tempDir.resolve("entry.h"));
        assertTrue(headerSource.contains("GDCoroLambdaEngineNode_Capture__lambda_0"), headerSource);
        assertTrue(headerSource.contains("_coro_capture_seed"), headerSource);

        if (GodotGdextensionTestRunner.findGodotBinaryFromEnv() == null) {
            Assumptions.abort("GODOT_BIN not found; skipping runtime integration test");
            return;
        }

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "CoroLambdaNode",
                        clazz.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(coroutineTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();
        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("coroutine lambda survives Callable release check passed."), combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), combinedOutput);
    }

    private static LirClassDef newCoroLambdaEngineClass() {
        var clazz = new LirClassDef("GDCoroLambdaEngineNode", "Node");
        clazz.setSourceFile("construct_coroutine_lambda_engine.gd");
        clazz.addProperty(new LirPropertyDef("result", GdIntType.INT));
        var selfType = new GdObjectType(clazz.getName());

        // Coroutine lambda: captures [self, sig, seed]; awaits the captured signal, then writes
        // the captured `seed` into the `result` property through the captured `self` — both reads
        // hit the per-call frame fields, never the (already freed) capture block.
        var coroLambda = newLambda("_lambda_0", GdIntType.INT);
        coroLambda.setCoroutine(true);
        coroLambda.addCapture(new LirCaptureDef("self", selfType, coroLambda));
        coroLambda.addCapture(new LirCaptureDef("sig", new GdSignalType(), coroLambda));
        coroLambda.addCapture(new LirCaptureDef("seed", GdIntType.INT, coroLambda));
        coroLambda.createAndAddVariable("resumed", GdVariantType.VARIANT);
        lambdaEntry(coroLambda).appendInstruction(new AwaitInsn("resumed", "sig"));
        lambdaEntry(coroLambda).appendInstruction(new StorePropertyInsn("result", "self", "seed"));
        lambdaEntry(coroLambda).appendInstruction(new ReturnInsn("seed"));
        clazz.addFunction(coroLambda);

        var maker = newMethod("make_coro_cb", new GdCallableType(), selfType);
        maker.addParameter(new LirParameterDef("sig", new GdSignalType(), null, maker));
        maker.createAndAddVariable("seed", GdIntType.INT);
        maker.createAndAddVariable("cb", new GdCallableType());
        entry(maker).appendInstruction(new LiteralIntInsn("seed", 41));
        entry(maker).appendInstruction(new ConstructLambdaInsn(
                "cb",
                "_lambda_0",
                List.of(
                        new LirInstruction.VariableOperand("self"),
                        new LirInstruction.VariableOperand("sig"),
                        new LirInstruction.VariableOperand("seed")
                )
        ));
        entry(maker).appendInstruction(new ReturnInsn("cb"));
        clazz.addFunction(maker);
        return clazz;
    }

    private static String coroutineTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "CoroLambdaNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var sig_holder := Node.new()
                    sig_holder.add_user_signal("release")
                    var cb: Callable = target.call("make_coro_cb", Signal(sig_holder, "release"))
                    var state: Variant = cb.call()
                    if state == null:
                        push_error("coroutine lambda suspend check failed: call returned nil.")
                        return
                    # Drop the Callable (freeing the capture block) and the returned state handle:
                    # the suspended coroutine must stay alive on its signal-wait edge alone.
                    cb = Callable()
                    state = null
                    sig_holder.emit_signal("release")
                    if int(target.result) == 41:
                        print("coroutine lambda survives Callable release check passed.")
                    else:
                        push_error("coroutine lambda survives Callable release check failed: result=%s" % [target.result])
                    sig_holder.free()
                """;
    }

    private static boolean hasZig() {
        return ZigUtil.findZig() != null;
    }

    private static LirClassDef newLambdaEngineClass() {
        var clazz = new LirClassDef("GDLambdaEngineNode", "Node");
        clazz.setSourceFile("construct_lambda_engine.gd");
        clazz.addProperty(new LirPropertyDef("flag", GdIntType.INT));
        var selfType = new GdObjectType(clazz.getName());

        var constLambda = newLambda("_lambda_0", GdIntType.INT);
        constLambda.createAndAddVariable("result", GdIntType.INT);
        lambdaEntry(constLambda).appendInstruction(new LiteralIntInsn("result", 7));
        lambdaEntry(constLambda).appendInstruction(new ReturnInsn("result"));
        clazz.addFunction(constLambda);

        var stringLambda = newLambda("_lambda_1", GdStringType.STRING);
        stringLambda.addCapture(new LirCaptureDef("label", GdStringType.STRING, stringLambda));
        lambdaEntry(stringLambda).appendInstruction(new ReturnInsn("label"));
        clazz.addFunction(stringLambda);

        var selfLambda = newLambda("_lambda_2", GdVoidType.VOID);
        selfLambda.addCapture(new LirCaptureDef("self", selfType, selfLambda));
        selfLambda.createAndAddVariable("one", GdIntType.INT);
        lambdaEntry(selfLambda).appendInstruction(new LiteralIntInsn("one", 1));
        lambdaEntry(selfLambda).appendInstruction(new StorePropertyInsn("flag", "self", "one"));
        lambdaEntry(selfLambda).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(selfLambda);

        clazz.addFunction(newConstMaker(selfType));
        clazz.addFunction(newStringMaker(selfType));
        clazz.addFunction(newSelfMaker(selfType));
        return clazz;
    }

    private static LirFunctionDef newLambda(String name, gd.script.gdcc.type.GdType returnType) {
        var lambda = new LirFunctionDef(name, "entry");
        lambda.setLambda(true);
        lambda.setHidden(true);
        lambda.setStatic(true);
        lambda.setReturnType(returnType);
        lambda.addBasicBlock(new LirBasicBlock("entry"));
        return lambda;
    }

    private static LirBasicBlock lambdaEntry(LirFunctionDef lambda) {
        return lambda.getBasicBlock("entry");
    }

    private static LirFunctionDef newConstMaker(GdObjectType selfType) {
        var func = newMethod("make_const_cb", new GdCallableType(), selfType);
        func.createAndAddVariable("cb", new GdCallableType());
        entry(func).appendInstruction(new ConstructLambdaInsn("cb", "_lambda_0", List.of()));
        entry(func).appendInstruction(new ReturnInsn("cb"));
        return func;
    }

    private static LirFunctionDef newStringMaker(GdObjectType selfType) {
        var func = newMethod("make_label_cb", new GdCallableType(), selfType);
        func.createAndAddVariable("label", GdStringType.STRING);
        func.createAndAddVariable("cb", new GdCallableType());
        entry(func).appendInstruction(new LiteralStringInsn("label", "hello"));
        entry(func).appendInstruction(new ConstructLambdaInsn(
                "cb",
                "_lambda_1",
                List.of(new LirInstruction.VariableOperand("label"))
        ));
        entry(func).appendInstruction(new ReturnInsn("cb"));
        return func;
    }

    private static LirFunctionDef newSelfMaker(GdObjectType selfType) {
        var func = newMethod("make_self_cb", new GdCallableType(), selfType);
        func.createAndAddVariable("cb", new GdCallableType());
        entry(func).appendInstruction(new ConstructLambdaInsn(
                "cb",
                "_lambda_2",
                List.of(new LirInstruction.VariableOperand("self"))
        ));
        entry(func).appendInstruction(new ReturnInsn("cb"));
        return func;
    }

    private static LirFunctionDef newMethod(String name, gd.script.gdcc.type.GdType returnType, GdObjectType selfType) {
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

    private static String testScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "LambdaNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var const_cb: Callable = target.call("make_const_cb")
                    if const_cb.get_argument_count() == 0 and const_cb.call() == 7:
                        print("captureless lambda check passed.")
                    else:
                        push_error("captureless lambda check failed.")
                
                    var label_cb: Callable = target.call("make_label_cb")
                    if label_cb.call() == "hello":
                        print("captured string check passed.")
                    else:
                        push_error("captured string check failed.")
                
                    var self_cb: Callable = target.call("make_self_cb")
                    self_cb.call()
                    if self_cb.get_object() == target and self_cb.is_valid() and target.flag == 1:
                        print("self object_id check passed.")
                    else:
                        push_error("self object_id check failed.")
                
                    var helper := Node.new()
                    helper.add_user_signal("fired")
                    helper.connect("fired", self_cb)
                    target.free()
                    helper.emit_signal("fired")
                    if not self_cb.is_valid():
                        print("freed self-lambda stays invalid check passed.")
                    else:
                        push_error("freed self-lambda stays invalid check failed.")
                    helper.free()
                """;
    }
}
