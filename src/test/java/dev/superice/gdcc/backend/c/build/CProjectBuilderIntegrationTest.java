package dev.superice.gdcc.backend.c.build;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.gen.CCodegen;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.*;
import dev.superice.gdcc.lir.insn.CallGlobalInsn;
import dev.superice.gdcc.lir.insn.LiteralStringInsn;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.*;
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

    @Test
    public void compileWithRealZigAndRunInGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }
        var tempDir = Path.of("tmp/test/c_build");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo("intproj", GodotVersion.V451, tempDir, COptimizationLevel.DEBUG, TargetPlatform.WINDOWS_X86_64);
        var builder = new CProjectBuilder();

        builder.initProject(projectInfo);
        assertTrue(Files.exists(tempDir.resolve("include")));

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
            bb1.instructions().add(new LiteralStringInsn(v0.id(), "Camera ready."));
            bb1.instructions().add(new PackVariantInsn(v1.id(), v0.id()));
            bb1.instructions().add(new CallGlobalInsn("print", List.of(new LirInstruction.VariableOperand(v1.id()))));
            readyFunc.addBasicBlock(bb1);
            rotatingCameraClass.addFunction(readyFunc);
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

                    var pitch = float(camera.get("pitch_degree"))
                    print("Pitch degree:", pitch)
                    if absf(pitch - EXPECTED_PITCH_DEGREE) <= 0.001:
                        print("Pitch check passed.")
                    else:
                        push_error("Pitch check failed: expected " + str(EXPECTED_PITCH_DEGREE) + ", got " + str(pitch))
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
        assertFalse(combinedOutput.contains("Pitch check failed"), "Pitch check should not fail.\nOutput:\n" + combinedOutput);
    }
}
