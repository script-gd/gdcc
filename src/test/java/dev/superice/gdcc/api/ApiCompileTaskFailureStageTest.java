package dev.superice.gdcc.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCompileTaskFailureStageTest {
    @Test
    void compileTaskPreservesCollectingSourcesStageForBrokenVirtualLinks(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Broken Link Stage Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("broken-link-stage-project")));
        api.createLink("demo", "/src", VfsEntrySnapshot.LinkKind.VIRTUAL, "/missing");

        var failedTask = ApiCompileTestSupport.awaitTask(api, api.compile("demo"));
        var result = Objects.requireNonNull(failedTask.result());

        assertEquals(CompileTaskSnapshot.State.FAILED, failedTask.state());
        assertEquals(CompileTaskSnapshot.Stage.COLLECTING_SOURCES, failedTask.stage());
        assertEquals(CompileResult.Outcome.SOURCE_COLLECTION_FAILED, result.outcome());
        assertEquals(
                "Virtual link '/src' in module 'demo' points to missing path '/missing'",
                result.failureMessage()
        );
        assertEquals(0, failedTask.totalUnits());
        assertNull(failedTask.currentSourcePath());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileTaskPreservesParsingStageForParseDiagnostics(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Parse Stage Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("parse-stage-project")));
        api.putFile("demo", "/src/broken.gd", """
                class_name BrokenTaskStage
                extends Node
                
                func _ready(
                    pass
                """, "shown broken.gd");

        var failedTask = ApiCompileTestSupport.awaitTask(api, api.compile("demo"));
        var result = Objects.requireNonNull(failedTask.result());

        assertEquals(CompileTaskSnapshot.State.FAILED, failedTask.state());
        assertEquals(CompileTaskSnapshot.Stage.PARSING, failedTask.stage());
        assertEquals("shown broken.gd", failedTask.currentSourcePath());
        assertEquals(1, failedTask.completedUnits());
        assertEquals(1, failedTask.totalUnits());
        assertEquals(CompileResult.Outcome.FRONTEND_FAILED, result.outcome());
        assertTrue(result.diagnostics().hasErrors());
        assertEquals("Frontend diagnostics blocked compilation", result.failureMessage());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileTaskPreservesLoweringStageForFrontendCompileBlockers(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Lowering Stage Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("lowering-stage-project")));
        api.putFile("demo", "/src/blocked.gd", """
                class_name LoweringStageBlocked
                extends RefCounted
                
                static var shared := 1
                """);

        var failedTask = ApiCompileTestSupport.awaitTask(api, api.compile("demo"));
        var result = Objects.requireNonNull(failedTask.result());

        assertEquals(CompileTaskSnapshot.State.FAILED, failedTask.state());
        assertEquals(CompileTaskSnapshot.Stage.LOWERING, failedTask.stage());
        assertNull(failedTask.currentSourcePath());
        assertEquals(1, failedTask.completedUnits());
        assertEquals(1, failedTask.totalUnits());
        assertEquals(CompileResult.Outcome.FRONTEND_FAILED, result.outcome());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.compile_check")
        ));
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileTaskPreservesBuildingNativeStageForNativeBuildFailures(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.failing("native build failed");
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Build Stage Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("build-stage-project")));
        api.putFile("demo", "/src/valid.gd", validSource("BuildStageDemo"));

        var failedTask = ApiCompileTestSupport.awaitTask(api, api.compile("demo"));
        var result = Objects.requireNonNull(failedTask.result());

        assertEquals(CompileTaskSnapshot.State.FAILED, failedTask.state());
        assertEquals(CompileTaskSnapshot.Stage.BUILDING_NATIVE, failedTask.stage());
        assertNull(failedTask.currentSourcePath());
        assertEquals(1, failedTask.completedUnits());
        assertEquals(1, failedTask.totalUnits());
        assertEquals(CompileResult.Outcome.BUILD_FAILED, result.outcome());
        assertEquals("native build failed", result.buildLog());
        assertEquals(1, compiler.invocationCount());
    }

    private static String validSource(String className) {
        return """
                class_name %s
                extends RefCounted
                
                func value() -> int:
                    return 1
                """.formatted(className);
    }
}
