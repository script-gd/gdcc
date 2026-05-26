package gd.script.gdcc.api;

import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCompilePipelineTest {
    @Test
    void compileBuildsMultiFileModuleAndStoresLastCompileResult(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        var projectPath = tempDir.resolve("pipeline-project");
        var staleVendorFile = projectPath.resolve("include/gdextension-lite/gdextension-lite-one.c");
        Files.createDirectories(staleVendorFile.getParent());
        Files.writeString(staleVendorFile, "stale vendor runtime");

        api.createModule("demo", "Pipeline Demo");
        api.setCompileOptions(
                "demo",
                ApiCompileTestSupport.compileOptions(projectPath, COptimizationLevel.RELEASE, TargetPlatform.WEB_WASM32, true)
        );
        api.putFile("demo", "/src/helper.gd", """
                class_name Helper
                extends RefCounted
                
                func value() -> int:
                    return 7
                """, "/display/helper.gd");
        api.putFile("demo", "/src/pipeline_smoke.gd", """
                class_name PipelineSmoke
                extends RefCounted
                
                func run() -> int:
                    var helper: Helper = Helper.new()
                    return helper.value()
                """);

        assertNull(api.getLastCompileResult("demo"));

        var taskId = api.compile("demo");
        var runningTask = api.getCompileTask(taskId);
        var result = ApiCompileTestSupport.awaitResult(api, taskId);

        assertEquals(taskId, runningTask.taskId());
        assertEquals("demo", runningTask.moduleId());
        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome());
        assertTrue(result.success());
        assertTrue(result.diagnostics().isEmpty());
        assertNull(result.failureMessage());
        assertEquals("ok", result.buildLog());
        assertEquals(List.of("/display/helper.gd", "/src/pipeline_smoke.gd"), result.sourcePaths());
        assertEquals(result, api.getLastCompileResult("demo"));
        assertTrue(api.getModule("demo").hasLastCompileResult());

        assertEquals(
                List.of(
                        projectPath.resolve("entry.c"),
                        projectPath.resolve("engine_method_binds.h"),
                        projectPath.resolve("entry.h")
                ),
                result.generatedFiles()
        );
        assertEquals(1, result.artifacts().size());
        assertTrue(Files.exists(result.artifacts().getFirst()));
        assertTrue(result.artifacts().getFirst().getFileName().toString().endsWith(".wasm"));
        assertEquals(
                List.of(
                        "/__build__/generated/entry.c",
                        "/__build__/generated/engine_method_binds.h",
                        "/__build__/generated/entry.h",
                        "/__build__/artifacts/" + result.artifacts().getFirst().getFileName()
                ),
                result.outputLinks().stream().map(VfsEntrySnapshot.LinkEntrySnapshot::virtualPath).toList()
        );

        var completedTask = api.getCompileTask(taskId);
        assertTrue(completedTask.completed());
        assertTrue(completedTask.success());
        assertEquals(result, completedTask.result());
        assertEquals(1, compiler.invocationCount());
        assertTrue(compiler.ranOnVirtualThread());
        assertEquals("demo_release_wasm32", compiler.lastOutputBaseName());
        assertEquals(COptimizationLevel.RELEASE, compiler.lastOptimizationLevel());
        assertEquals(TargetPlatform.WEB_WASM32, compiler.lastTargetPlatform());
        assertTrue(compiler.lastCFiles().stream().anyMatch(path -> path.getFileName().toString().equals("entry.c")));
        assertTrue(compiler.lastCFiles().stream().anyMatch(path -> path.endsWith("godot/godot_binding.c")));
        assertFalse(compiler.lastCFiles().stream().anyMatch(path -> path.endsWith("gdextension-lite/gdextension-lite-one.c")));
        assertFalse(compiler.lastCFiles().contains(staleVendorFile));
        assertTrue(compiler.lastIncludeDirs().stream().anyMatch(path -> path.endsWith("include/gdcc")));
        assertTrue(compiler.lastIncludeDirs().stream().anyMatch(path -> path.endsWith("include/godot")));
        assertFalse(compiler.lastIncludeDirs().stream().anyMatch(path -> path.endsWith("include/gdextension-lite")));

        var entrySource = Files.readString(projectPath.resolve("entry.c"));
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"PipelineSmoke\")"), entrySource);
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"Helper\")"), entrySource);
    }
}
