package dev.superice.gdcc.backend.c.build;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.gen.CCodegen;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPassManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FrontendLoweringToCProjectBuilderIntegrationTest {
    @Test
    void lowerFrontendModuleBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping frontend-to-native Godot integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_lowering_build_smoke");
        Files.createDirectories(tempDir);

        var source = """
                class_name FrontendBuildSmoke
                extends Node
                
                func ping() -> int:
                    var obj: Object = null;
                    return 1
                """;
        var module = parseModule(
                tempDir.resolve("frontend_build_smoke.gd"),
                source,
                Map.of("FrontendBuildSmoke", "RuntimeFrontendBuildSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered);
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        assertEquals("RuntimeFrontendBuildSmoke", lowered.getClassDefs().getFirst().getName());
        assertEquals(1, lowered.getClassDefs().getFirst().getFunctions().size());
        assertEquals("ping", lowered.getClassDefs().getFirst().getFunctions().getFirst().getName());
        assertFalse(lowered.getClassDefs().getFirst().getFunctions().getFirst().getEntryBlockId().isBlank());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_build_smoke",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        var librarySuffix = projectInfo.getTargetPlatform().sharedLibraryFileName("artifact").replace("artifact", "");

        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        assertTrue(Files.exists(projectDir.resolve("entry.c")));
        assertTrue(Files.exists(projectDir.resolve("entry.h")));
        assertTrue(
                buildResult.artifacts().stream()
                        .anyMatch(artifact -> artifact.getFileName().toString().endsWith(librarySuffix)),
                () -> "Expected a native library artifact with suffix '" + librarySuffix + "', got " + buildResult.artifacts()
        );
        assertTrue(buildResult.artifacts().stream().allMatch(Files::exists));

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "FrontendBuildSmokeNode",
                        lowered.getClassDefs().getFirst().getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(testScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend lowering runtime ping check passed."),
                () -> "Godot output should confirm ping result.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend lowering runtime ping check failed."),
                () -> "Ping check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull Path sourcePath,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var parser = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parser.parseUnit(sourcePath, source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule("frontend_build_smoke_module", java.util.List.of(unit), topLevelCanonicalNameMap);
    }

    private static @NotNull String testScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "FrontendBuildSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var result = int(target.call("ping"))
                    if result == 1:
                        print("frontend lowering runtime ping check passed.")
                    else:
                        push_error("frontend lowering runtime ping check failed.")
                """;
    }
}
