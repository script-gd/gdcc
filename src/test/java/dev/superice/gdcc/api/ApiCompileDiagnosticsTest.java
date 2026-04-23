package dev.superice.gdcc.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCompileDiagnosticsTest {
    @Test
    void compileReportsParseDiagnosticsAndSkipsBuild(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Broken Parse Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("parse-project")));
        api.putFile("demo", "/src/broken.gd", """
                class_name Broken
                extends Node
                
                func _ready(
                    pass
                """, "shown broken.gd");

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        var parseDiagnostic = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("parse.lowering"))
                .findFirst()
                .orElseThrow();

        assertEquals(CompileResult.Outcome.FRONTEND_FAILED, result.outcome());
        assertFalse(result.success());
        assertEquals("Frontend diagnostics blocked compilation", result.failureMessage());
        assertTrue(result.diagnostics().hasErrors());
        assertEquals(Path.of("shown broken.gd"), parseDiagnostic.sourcePath());
        assertTrue(parseDiagnostic.message().contains("CST structural issue"));
        assertEquals(List.of("shown broken.gd"), result.sourcePaths());
        assertTrue(result.generatedFiles().isEmpty());
        assertTrue(result.artifacts().isEmpty());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileReportsFrontendCompileBlockDiagnosticsAndSkipsBuild(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Compile Block Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("compile-check-project")));
        api.putFile("demo", "/src/blocked.gd", """
                class_name LoweringBlockedStaticProperty
                extends RefCounted
                
                static var shared := 1
                """);

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        var compileDiagnostic = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .findFirst()
                .orElseThrow();

        assertEquals(CompileResult.Outcome.FRONTEND_FAILED, result.outcome());
        assertEquals("Frontend diagnostics blocked compilation", result.failureMessage());
        assertTrue(result.diagnostics().hasErrors());
        assertTrue(compileDiagnostic.message().contains("Static property 'shared'"));
        assertEquals(List.of("/src/blocked.gd"), result.sourcePaths());
        assertTrue(result.generatedFiles().isEmpty());
        assertTrue(result.artifacts().isEmpty());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileReportsBrokenVirtualLinksDuringSourceCollection(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Broken Link Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("broken-link-project")));
        api.createLink("demo", "/src", VfsEntrySnapshot.LinkKind.VIRTUAL, "/missing");

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.SOURCE_COLLECTION_FAILED, result.outcome());
        assertEquals(
                "Virtual link '/src' in module 'demo' points to missing path '/missing'",
                result.failureMessage()
        );
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileRejectsModulesWithoutAnySourceFiles(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "No Source Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("no-source-project")));
        api.putFile("demo", "/notes/readme.txt", "hello");

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.SOURCE_COLLECTION_FAILED, result.outcome());
        assertEquals("Module 'demo' has no .gd source files to compile", result.failureMessage());
        assertTrue(result.sourcePaths().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileRejectsProjectPathsThatCannotActAsBuildDirectories(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        var occupiedPath = tempDir.resolve("occupied-project-path");
        Files.writeString(occupiedPath, "occupied");

        api.createModule("demo", "Occupied Project Path Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(occupiedPath));
        api.putFile("demo", "/src/valid.gd", validSource("OccupiedProjectPathSmoke"));

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        var failureMessage = Objects.requireNonNull(result.failureMessage());

        assertEquals(CompileResult.Outcome.CONFIGURATION_FAILED, result.outcome());
        assertTrue(failureMessage.contains(occupiedPath.toString()));
        assertTrue(failureMessage.contains("cannot be used as a build directory"));
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(0, compiler.invocationCount());
    }

    @Test
    void compileReportsNativeBuildFailureAfterWritingGeneratedFiles(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.failing("native build failed");
        var api = ApiCompileTestSupport.newApi(compiler);
        var projectPath = tempDir.resolve("build-failure-project");

        api.createModule("demo", "Build Failure Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(projectPath));
        api.putFile("demo", "/src/valid.gd", validSource("BuildFailureSmoke"));

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.BUILD_FAILED, result.outcome());
        assertEquals("Native build reported failure; see buildLog for details", result.failureMessage());
        assertEquals("native build failed", result.buildLog());
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(
                List.of(
                        projectPath.resolve("entry.c"),
                        projectPath.resolve("engine_method_binds.h"),
                        projectPath.resolve("entry.h")
                ),
                result.generatedFiles()
        );
        assertTrue(result.artifacts().isEmpty());
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
