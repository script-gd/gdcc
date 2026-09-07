package gd.script.gdcc.api;

import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.ModuleLocalGodotBindingFixtureProjectBuilder;
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
    private static final Path STALE_VENDOR_RUNTIME_SOURCE = Path.of("gdextension-lite", "gdextension-lite-one.c");

    @Test
    void compileBuildsMultiFileModuleAndStoresLastCompileResult(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        var projectPath = tempDir.resolve("pipeline-project");
        var staleVendorFile = projectPath.resolve("include").resolve(STALE_VENDOR_RUNTIME_SOURCE);
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
                        projectPath.resolve("object_fat_ptr_types.h"),
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
                        "/__build__/generated/object_fat_ptr_types.h",
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
        assertFalse(compiler.lastCFiles().contains(staleVendorFile));
        assertTrue(compiler.lastIncludeDirs().stream().anyMatch(path -> path.endsWith("include/gdcc")));
        assertTrue(compiler.lastIncludeDirs().stream().anyMatch(path -> path.endsWith("include/godot")));

        var entrySource = Files.readString(projectPath.resolve("entry.c"));
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"PipelineSmoke\")"), entrySource);
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"Helper\")"), entrySource);
    }

    @Test
    void compileWithModuleLocalBindingKeepsGeneratedLinksAndNativeInputsStable(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(new ModuleLocalGodotBindingFixtureProjectBuilder(compiler));
        var projectPath = tempDir.resolve("module-local-project");

        api.createModule("demo", "Module Local Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(projectPath));
        api.putFile("demo", "/src/module_local_smoke.gd", """
                class_name ModuleLocalSmoke
                extends RefCounted

                func run() -> int:
                    return 13
                """);

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        var artifact = result.artifacts().getFirst();
        var normalizedProjectPath = projectPath.toAbsolutePath().normalize();

        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome());
        assertEquals(
                List.of(
                        projectPath.resolve("entry.c"),
                        projectPath.resolve("engine_method_binds.h"),
                        projectPath.resolve("object_fat_ptr_types.h"),
                        projectPath.resolve("entry.h")
                ),
                result.generatedFiles()
        );
        assertEquals(
                List.of(
                        "/__build__/generated/entry.c",
                        "/__build__/generated/engine_method_binds.h",
                        "/__build__/generated/object_fat_ptr_types.h",
                        "/__build__/generated/entry.h",
                        "/__build__/artifacts/" + artifact.getFileName()
                ),
                result.outputLinks().stream().map(VfsEntrySnapshot.LinkEntrySnapshot::virtualPath).toList()
        );
        assertEquals(
                List.of("engine_method_binds.h", "entry.c", "entry.h", "object_fat_ptr_types.h"),
                api.listDirectory("demo", "/__build__/generated").stream().map(VfsEntrySnapshot::name).toList()
        );

        assertEquals(
                List.of(
                        normalizedProjectPath.resolve("entry.c"),
                        normalizedProjectPath.resolve("include/godot/godot_binding.c"),
                        normalizedProjectPath.resolve("include/gdcc/minicoro.c"),
                        normalizedProjectPath.resolve("include/gdcc/gdcc_coroutine.c")
                ),
                compiler.lastCFiles().stream().map(path -> path.toAbsolutePath().normalize()).toList()
        );
        assertEquals(
                List.of(
                        normalizedProjectPath.resolve("include/gdcc"),
                        normalizedProjectPath.resolve("include/godot")
                ),
                compiler.lastIncludeDirs().stream().map(path -> path.toAbsolutePath().normalize()).toList()
        );

        var bindHeader = Files.readString(projectPath.resolve("engine_method_binds.h"));
        assertTrue(bindHeader.contains("static inline godot_int godot_Probe_READY(void)"), bindHeader);
    }

    @Test
    void sequentialCompilesReuseApiAcrossLambdaModules(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        // Both modules deliberately reuse the same class name so a stale per-run resolver would
        // collide on the lambda name counter as well as the scope reverse index.
        api.createModule("first", "First Lambda Compile Demo");
        api.setCompileOptions("first", ApiCompileTestSupport.compileOptions(tempDir.resolve("first-lambda-project")));
        api.putFile("first", "/src/first.gd", lambdaSource("CompileLambdaShared"));
        api.createModule("second", "Second Lambda Compile Demo");
        api.setCompileOptions("second", ApiCompileTestSupport.compileOptions(tempDir.resolve("second-lambda-project")));
        api.putFile("second", "/src/second.gd", lambdaSource("CompileLambdaShared"));

        // FrontendSuiteResolver keeps per-run lambda state, so the second compile on the same API
        // instance must observe a fresh lowering pipeline instead of stale scope/counter state
        // left behind by the first compile.
        var firstResult = ApiCompileTestSupport.awaitResult(api, api.compile("first"));
        var secondResult = ApiCompileTestSupport.awaitResult(api, api.compile("second"));

        assertEquals(CompileResult.Outcome.SUCCESS, firstResult.outcome());
        assertFalse(firstResult.diagnostics().hasErrors());
        assertEquals(List.of("/src/first.gd"), firstResult.sourcePaths());
        assertEquals(CompileResult.Outcome.SUCCESS, secondResult.outcome());
        assertFalse(secondResult.diagnostics().hasErrors());
        assertEquals(List.of("/src/second.gd"), secondResult.sourcePaths());
        assertEquals(2, compiler.invocationCount());
    }

    private static String lambdaSource(String className) {
        return """
                class_name %s
                extends RefCounted
                
                func make() -> Callable:
                    var add := func(a: int, b: int) -> int:
                        return a + b
                    return add
                """.formatted(className);
    }
}
