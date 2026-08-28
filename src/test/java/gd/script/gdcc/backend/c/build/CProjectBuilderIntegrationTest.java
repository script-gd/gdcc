package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.*;
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.BinaryOpInsn;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.lir.insn.CallGlobalInsn;
import gd.script.gdcc.lir.insn.GoIfInsn;
import gd.script.gdcc.lir.insn.GotoInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.LoadPropertyInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.StoreStaticInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CProjectBuilderIntegrationTest {

    private static boolean hasZig() {
        return ZigUtil.findZig() != null;
    }

    /// Static var C backend smoke: a module with static properties (plain, initialized, and
    /// typed-container) plus load/store_static accessors must produce C that a real C compiler
    /// accepts. Godot runtime validation of the two-phase init semantics lives in the
    /// `member/static_var_*` test-suite cases.
    @Test
    public void compileStaticVarModuleWithRealZig() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }
        var tempDir = Path.of("tmp/test/c_build_static_var");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo("staticvarproj", GodotVersion.V451, tempDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var codegen = new CCodegen();
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var ctx = new CodegenContext(projectInfo, new ClassRegistry(api));

        var workerClass = new LirClassDef("GDStaticWorker", "RefCounted");
        workerClass.setSourceFile("static_worker.gd");
        // Static with a source initializer (hidden static zero-param helper).
        workerClass.addProperty(new LirPropertyDef("count", GdIntType.INT, true, "_field_init_count", null, null, Map.of()));
        // Static without initializer (defaults-section only) and a typed-container static.
        workerClass.addProperty(new LirPropertyDef("title", GdStringType.STRING, true, null, null, null, Map.of()));
        workerClass.addProperty(new LirPropertyDef("items", new GdArrayType(GdIntType.INT), true, null, null, null, Map.of()));
        {
            var initFunc = new LirFunctionDef("_field_init_count", "entry");
            initFunc.setHidden(true);
            initFunc.setStatic(true);
            initFunc.setReturnType(GdIntType.INT);
            var tmpVar = initFunc.createAndAddTmpVariable(GdIntType.INT);
            var entry = new LirBasicBlock("entry");
            entry.appendInstruction(new LiteralIntInsn(tmpVar.id(), 41));
            entry.appendInstruction(new ReturnInsn(tmpVar.id()));
            initFunc.addBasicBlock(entry);
            workerClass.addFunction(initFunc);
        }
        {
            // Reads the static through load_static and bumps it through store_static.
            var bumpFunc = new LirFunctionDef("bump_count", "entry");
            bumpFunc.setStatic(true);
            bumpFunc.setReturnType(GdIntType.INT);
            var v0 = bumpFunc.createAndAddVariable("0", GdIntType.INT);
            var v1 = bumpFunc.createAndAddVariable("1", GdIntType.INT);
            Objects.requireNonNull(v0);
            Objects.requireNonNull(v1);
            var entry = new LirBasicBlock("entry");
            entry.appendInstruction(new LoadStaticInsn(v0.id(), "GDStaticWorker", "count"));
            entry.appendInstruction(new LiteralIntInsn(v1.id(), 1));
            var v2 = bumpFunc.createAndAddVariable("2", GdIntType.INT);
            Objects.requireNonNull(v2);
            entry.appendInstruction(new BinaryOpInsn(v2.id(), GodotOperator.ADD, v0.id(), v1.id()));
            entry.appendInstruction(new StoreStaticInsn("GDStaticWorker", "count", v2.id()));
            entry.appendInstruction(new ReturnInsn(v2.id()));
            bumpFunc.addBasicBlock(entry);
            workerClass.addFunction(bumpFunc);
        }

        var module = new LirModule("static_var_module", List.of(workerClass));
        codegen.prepare(ctx, module);
        var result = builder.buildProject(projectInfo, codegen);
        IO.println(result.buildLog());

        assertTrue(result.success(), "Static var module should compile with zig. Build log:\n" + result.buildLog());
        assertFalse(result.artifacts().isEmpty());
    }

    @Test
    public void compileWithRealZigAndRunInGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }
        var tempDir = Path.of("tmp/test/c_build");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo("intproj", GodotVersion.V451, tempDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        var builder = new CProjectBuilder();

        builder.initProject(projectInfo);
        var projectIncludeDir = tempDir.resolve("include");
        var projectParent = tempDir.toAbsolutePath().normalize().getParent();
        var sharedIncludeDir = projectParent == null ? tempDir.resolveSibling("shared-include") : projectParent.resolve("shared-include");
        assertTrue(Files.exists(projectIncludeDir) || Files.exists(sharedIncludeDir));

        var codegen = new CCodegen();
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var ctx = new CodegenContext(projectInfo, new ClassRegistry(api));
        var rotatingCameraClass = new LirClassDef("GDRotatingCamera3D", "Camera3D");
        var selfType = new GdObjectType("GDRotatingCamera3D");
        rotatingCameraClass.setSourceFile("rotating_camera.gd");
        rotatingCameraClass.addProperty(new LirPropertyDef("pitch_degree",
                GdFloatType.FLOAT,
                false,
                null,
                null,
                null,
                Map.of())
        );
        {
            var readyFunc = new LirFunctionDef("_ready", "bb1");
            readyFunc.setReturnType(GdVoidType.VOID);
            readyFunc.addParameter(new LirParameterDef("self", selfType, null, readyFunc));
            var v0 = readyFunc.createAndAddVariable("0", GdStringType.STRING);
            var v1 = readyFunc.createAndAddVariable("1", GdVariantType.VARIANT);
            Objects.requireNonNull(v0);
            Objects.requireNonNull(v1);
            var bb1 = new LirBasicBlock("bb1");
            bb1.appendInstruction(new LiteralStringInsn(v0.id(), "Camera ready."));
            bb1.appendInstruction(new PackVariantInsn(v1.id(), v0.id()));
            bb1.appendInstruction(new CallGlobalInsn("print", List.of(new LirInstruction.VariableOperand(v1.id()))));
            readyFunc.addBasicBlock(bb1);
            rotatingCameraClass.addFunction(readyFunc);
        }
        {
            var getPitchFunc = new LirFunctionDef("get_pitch", "bb1");
            getPitchFunc.setReturnType(GdFloatType.FLOAT);
            getPitchFunc.addParameter(new LirParameterDef("self", selfType, null, getPitchFunc));
            getPitchFunc.addParameter(new LirParameterDef("to_radians", GdBoolType.BOOL, null, getPitchFunc));
            var v0 = getPitchFunc.createAndAddVariable("0", GdFloatType.FLOAT);
            var v1 = getPitchFunc.createAndAddVariable("1", GdFloatType.FLOAT);
            Objects.requireNonNull(v0);
            Objects.requireNonNull(v1);

            var bb1 = new LirBasicBlock("bb1");
            bb1.appendInstruction(new GoIfInsn("to_radians", "bb2", "bb3"));
            getPitchFunc.addBasicBlock(bb1);

            var bb2 = new LirBasicBlock("bb2");
            bb2.appendInstruction(new LoadPropertyInsn(v0.id(), "pitch_degree", "self"));
            bb2.appendInstruction(new CallGlobalInsn(v1.id(), "deg_to_rad", List.of(new LirInstruction.VariableOperand(v0.id()))));
            bb2.appendInstruction(new ReturnInsn(v1.id()));
            getPitchFunc.addBasicBlock(bb2);

            var bb3 = new LirBasicBlock("bb3");
            bb3.appendInstruction(new LoadPropertyInsn(v0.id(), "pitch_degree", "self"));
            bb3.appendInstruction(new ReturnInsn(v0.id()));
            getPitchFunc.addBasicBlock(bb3);

            rotatingCameraClass.addFunction(getPitchFunc);
        }
        var module = new LirModule("my_module", List.of(rotatingCameraClass));
        codegen.prepare(ctx, module);
        var result = builder.buildProject(projectInfo, codegen);
        IO.println(result.buildLog());

        assertTrue(result.success(), "Compilation should succeed when zig is available. Build log:\n" + result.buildLog());
        assertFalse(result.artifacts().isEmpty());
        for (var p : result.artifacts()) {
            assertTrue(Files.exists(p));
        }

        var testScriptContent = """
                extends Node
                
                const EXPECTED_PITCH_DEGREE = 45.0
                const TARGET_NODE_NAME = "RotatingCameraNode"
                
                func _ready() -> void:
                    var camera = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if camera == null:
                        push_error("Camera node missing.")
                        return
                
                    var pitch = float(camera.call("get_pitch", false))
                    var pitch_radians = float(camera.call("get_pitch", true))
                    print("Pitch degree:", pitch)
                    print("Pitch radians:", pitch_radians)
                    if absf(pitch - EXPECTED_PITCH_DEGREE) <= 0.001:
                        print("Pitch check passed.")
                    else:
                        push_error("Pitch check failed: expected " + str(EXPECTED_PITCH_DEGREE) + ", got " + str(pitch))
                
                    var expected_radians = deg_to_rad(EXPECTED_PITCH_DEGREE)
                    if absf(pitch_radians - expected_radians) <= 0.001:
                        print("Pitch radians check passed.")
                    else:
                        push_error("Pitch radians check failed: expected " + str(expected_radians) + ", got " + str(pitch_radians))
                """;

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                result.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "RotatingCameraNode",
                        rotatingCameraClass.getName(),
                        ".",
                        Map.of("pitch_degree", "45.0")
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(testScriptContent)
        ));
        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        System.out.println(runResult.stdout());
        if (!runResult.stderr().isBlank()) {
            System.err.println(runResult.stderr());
        }

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("Camera ready."), "Godot output should include camera ready log.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("Pitch check passed."), "Godot output should confirm pitch check.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("Pitch radians check passed."), "Godot output should confirm radian pitch check.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("Pitch check failed"), "Pitch check should not fail.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("Pitch radians check failed"), "Radian pitch check should not fail.\nOutput:\n" + combinedOutput);
    }

    @Test
    public void rangeIteratorZeroStepShouldTerminateInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }
        var tempDir = Path.of("tmp/test/for_range_iter_runtime");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo("for_range_iter_runtime", GodotVersion.V451, tempDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var context = new CodegenContext(projectInfo, new ClassRegistry(api));
        var probeClass = new LirClassDef("GDForRangeIterProbe", "Node");
        probeClass.setSourceFile("for_range_iter_probe.gd");
        var probeType = new GdObjectType(probeClass.getName());
        var checkFunction = new LirFunctionDef("should_continue", "entry");
        checkFunction.setReturnType(GdBoolType.BOOL);
        checkFunction.addParameter(new LirParameterDef("self", probeType, null, checkFunction));
        checkFunction.addParameter(new LirParameterDef("start", GdIntType.INT, null, checkFunction));
        checkFunction.addParameter(new LirParameterDef("end", GdIntType.INT, null, checkFunction));
        checkFunction.addParameter(new LirParameterDef("step", GdIntType.INT, null, checkFunction));
        checkFunction.createAndAddVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER);
        checkFunction.createAndAddVariable("result", GdBoolType.BOOL);
        var entryBlock = new LirBasicBlock("entry");
        entryBlock.appendInstruction(new CallIntrinsicInsn(
                "iter",
                "gdcc.for_range_iter.init",
                List.of(
                        new LirInstruction.VariableOperand("start"),
                        new LirInstruction.VariableOperand("end"),
                        new LirInstruction.VariableOperand("step")
                )
        ));
        entryBlock.appendInstruction(new CallIntrinsicInsn(
                "result",
                "gdcc.for_range_iter.should_continue",
                List.of(new LirInstruction.VariableOperand("iter"))
        ));
        entryBlock.appendInstruction(new ReturnInsn("result"));
        checkFunction.addBasicBlock(entryBlock);
        probeClass.addFunction(checkFunction);

        var codegen = new CCodegen();
        codegen.prepare(context, new LirModule("for_range_iter_module", List.of(probeClass)));
        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "ForRangeIterProbe",
                        probeClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec("""
                        extends Node

                        func _ready() -> void:
                            var probe = get_parent().get_node_or_null("ForRangeIterProbe")
                            if probe == null:
                                push_error("For-range iterator probe missing.")
                                return

                            var forward = bool(probe.call("should_continue", 0, 5, 0))
                            var backward = bool(probe.call("should_continue", 5, 0, 0))
                            if not forward and not backward:
                                print("Zero-step range iterator check passed.")
                            else:
                                push_error("Zero-step range iterator check failed.")
                        """)
        ));
        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("Zero-step range iterator check passed."), "Missing zero-step success marker.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("Zero-step range iterator check failed."), "Zero-step check should not fail.\nOutput:\n" + combinedOutput);
    }

    @Test
    public void rangeIteratorBoundaryDirectionShouldProduceZeroIterationsInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }
        var tempDir = Path.of("tmp/test/for_range_iter_boundary");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo("for_range_iter_boundary", GodotVersion.V451, tempDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var context = new CodegenContext(projectInfo, new ClassRegistry(api));
        var probeClass = new LirClassDef("GDForRangeBoundaryProbe", "Node");
        probeClass.setSourceFile("for_range_boundary_probe.gd");
        var probeType = new GdObjectType(probeClass.getName());
        var checkFunction = new LirFunctionDef("should_continue", "entry");
        checkFunction.setReturnType(GdBoolType.BOOL);
        checkFunction.addParameter(new LirParameterDef("self", probeType, null, checkFunction));
        checkFunction.addParameter(new LirParameterDef("start", GdIntType.INT, null, checkFunction));
        checkFunction.addParameter(new LirParameterDef("end", GdIntType.INT, null, checkFunction));
        checkFunction.addParameter(new LirParameterDef("step", GdIntType.INT, null, checkFunction));
        checkFunction.createAndAddVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER);
        checkFunction.createAndAddVariable("result", GdBoolType.BOOL);
        var entryBlock = new LirBasicBlock("entry");
        entryBlock.appendInstruction(new CallIntrinsicInsn(
                "iter",
                "gdcc.for_range_iter.init",
                List.of(
                        new LirInstruction.VariableOperand("start"),
                        new LirInstruction.VariableOperand("end"),
                        new LirInstruction.VariableOperand("step")
                )
        ));
        entryBlock.appendInstruction(new CallIntrinsicInsn(
                "result",
                "gdcc.for_range_iter.should_continue",
                List.of(new LirInstruction.VariableOperand("iter"))
        ));
        entryBlock.appendInstruction(new ReturnInsn("result"));
        checkFunction.addBasicBlock(entryBlock);
        probeClass.addFunction(checkFunction);

        var codegen = new CCodegen();
        codegen.prepare(context, new LirModule("for_range_boundary_module", List.of(probeClass)));
        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "ForRangeBoundaryProbe",
                        probeClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec("""
                        extends Node

                        func _ready() -> void:
                            var probe = get_parent().get_node_or_null("ForRangeBoundaryProbe")
                            if probe == null:
                                push_error("For-range boundary probe missing.")
                                return

                            var positive_reverse = bool(probe.call("should_continue", 5, 0, 1))
                            var negative_forward = bool(probe.call("should_continue", 0, 5, -1))
                            var positive_forward = bool(probe.call("should_continue", 0, 5, 1))
                            var negative_reverse = bool(probe.call("should_continue", 5, 0, -1))
                            var equal_bounds_pos = bool(probe.call("should_continue", 3, 3, 1))
                            var equal_bounds_neg = bool(probe.call("should_continue", 3, 3, -1))

                            if not positive_reverse and not negative_forward \
                                    and positive_forward and negative_reverse \
                                    and not equal_bounds_pos and not equal_bounds_neg:
                                print("Boundary direction check passed.")
                            else:
                                push_error("Boundary direction check failed: +%d -%d +%s -%s =%s =%s" % [
                                    positive_reverse, negative_forward,
                                    positive_forward, negative_reverse,
                                    equal_bounds_pos, equal_bounds_neg])
                        """)
        ));
        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("Boundary direction check passed."), "Missing boundary direction success marker.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("Boundary direction check failed"), "Boundary direction check should not fail.\nOutput:\n" + combinedOutput);
    }

    @Test
    public void rangeIteratorFullLoopShouldAccumulatePerIterationValuesInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }
        var tempDir = Path.of("tmp/test/for_range_iter_full_loop");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo("for_range_iter_full_loop", GodotVersion.V451, tempDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var context = new CodegenContext(projectInfo, new ClassRegistry(api));
        var probeClass = new LirClassDef("GDForRangeFullLoopProbe", "Node");
        probeClass.setSourceFile("for_range_full_loop_probe.gd");
        var probeType = new GdObjectType(probeClass.getName());

        var sumFunction = new LirFunctionDef("sum_range", "entry");
        sumFunction.setReturnType(GdIntType.INT);
        sumFunction.addParameter(new LirParameterDef("self", probeType, null, sumFunction));
        sumFunction.addParameter(new LirParameterDef("start", GdIntType.INT, null, sumFunction));
        sumFunction.addParameter(new LirParameterDef("end", GdIntType.INT, null, sumFunction));
        sumFunction.addParameter(new LirParameterDef("step", GdIntType.INT, null, sumFunction));
        sumFunction.createAndAddVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER);
        sumFunction.createAndAddVariable("next_iter", GdccForRangeIterType.FOR_RANGE_ITER);
        sumFunction.createAndAddVariable("acc", GdIntType.INT);
        sumFunction.createAndAddVariable("cond", GdBoolType.BOOL);
        sumFunction.createAndAddVariable("value", GdIntType.INT);

        var entryBlock = new LirBasicBlock("entry");
        entryBlock.appendInstruction(new CallIntrinsicInsn(
                "iter",
                "gdcc.for_range_iter.init",
                List.of(
                        new LirInstruction.VariableOperand("start"),
                        new LirInstruction.VariableOperand("end"),
                        new LirInstruction.VariableOperand("step")
                )
        ));
        entryBlock.appendInstruction(new LiteralIntInsn("acc", 0));
        entryBlock.appendInstruction(new GotoInsn("loop_cond"));

        var condBlock = new LirBasicBlock("loop_cond");
        condBlock.appendInstruction(new CallIntrinsicInsn(
                "cond",
                "gdcc.for_range_iter.should_continue",
                List.of(new LirInstruction.VariableOperand("iter"))
        ));
        condBlock.appendInstruction(new GoIfInsn("cond", "loop_body", "loop_exit"));

        var bodyBlock = new LirBasicBlock("loop_body");
        bodyBlock.appendInstruction(new CallIntrinsicInsn(
                "value",
                "gdcc.for_range_iter.get",
                List.of(new LirInstruction.VariableOperand("iter"))
        ));
        bodyBlock.appendInstruction(new BinaryOpInsn("acc", GodotOperator.ADD, "acc", "value"));
        bodyBlock.appendInstruction(new CallIntrinsicInsn(
                "next_iter",
                "gdcc.for_range_iter.next",
                List.of(new LirInstruction.VariableOperand("iter"))
        ));
        bodyBlock.appendInstruction(new AssignInsn("iter", "next_iter"));
        bodyBlock.appendInstruction(new GotoInsn("loop_cond"));

        var exitBlock = new LirBasicBlock("loop_exit");
        exitBlock.appendInstruction(new ReturnInsn("acc"));

        sumFunction.addBasicBlock(entryBlock);
        sumFunction.addBasicBlock(condBlock);
        sumFunction.addBasicBlock(bodyBlock);
        sumFunction.addBasicBlock(exitBlock);
        sumFunction.setEntryBlockId("entry");
        probeClass.addFunction(sumFunction);

        var codegen = new CCodegen();
        codegen.prepare(context, new LirModule("for_range_full_loop_module", List.of(probeClass)));
        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "ForRangeFullLoopProbe",
                        probeClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec("""
                        extends Node

                        func _ready() -> void:
                            var probe = get_parent().get_node_or_null("ForRangeFullLoopProbe")
                            if probe == null:
                                push_error("For-range full loop probe missing.")
                                return

                            var forward_sum = int(probe.call("sum_range", 0, 5, 1))
                            var backward_sum = int(probe.call("sum_range", 5, 0, -1))
                            var step2_sum = int(probe.call("sum_range", 0, 10, 2))
                            var empty_pos = int(probe.call("sum_range", 5, 0, 1))
                            var empty_neg = int(probe.call("sum_range", 0, 5, -1))
                            var empty_zero_step = int(probe.call("sum_range", 0, 5, 0))

                            var ok = forward_sum == 10 and backward_sum == 15 \
                                    and step2_sum == 20 and empty_pos == 0 \
                                    and empty_neg == 0 and empty_zero_step == 0
                            if ok:
                                print("Full loop accumulation check passed.")
                            else:
                                push_error("Full loop check failed: fwd=%d bwd=%d s2=%d ep=%d en=%d ez=%d" % [
                                    forward_sum, backward_sum, step2_sum, empty_pos, empty_neg, empty_zero_step])
                        """)
        ));
        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("Full loop accumulation check passed."), "Missing full loop success marker.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("Full loop check failed"), "Full loop check should not fail.\nOutput:\n" + combinedOutput);
    }
}
