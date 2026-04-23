package dev.superice.gdcc.api;

import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.exception.ApiPathNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRecompileArtifactRefreshTest {
    @Test
    void recompileRefreshesMountedOutputsWhenMountRootChanges(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Recompile Refresh Demo");
        api.putFile("demo", "/src/main.gd", validSource("RecompileRefreshDemo"));
        api.setCompileOptions(
                "demo",
                ApiCompileTestSupport.compileOptions(
                        tempDir.resolve("first-project"),
                        dev.superice.gdcc.backend.c.build.COptimizationLevel.DEBUG,
                        TargetPlatform.WEB_WASM32,
                        false,
                        "/first-build"
                )
        );

        var firstResult = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        var firstArtifactLinkPath = firstResult.outputLinks().getLast().virtualPath();

        api.setCompileOptions(
                "demo",
                ApiCompileTestSupport.compileOptions(
                        tempDir.resolve("second-project"),
                        dev.superice.gdcc.backend.c.build.COptimizationLevel.RELEASE,
                        TargetPlatform.getNativePlatform(),
                        false,
                        "/second-build"
                )
        );
        var secondResult = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.SUCCESS, secondResult.outcome());
        assertEquals(
                List.of("artifacts", "generated"),
                api.listDirectory("demo", "/second-build").stream().map(VfsEntrySnapshot::name).toList()
        );
        assertTrue(secondResult.outputLinks().stream().allMatch(link ->
                link.target().startsWith(tempDir.resolve("second-project").toString())
        ));

        var staleGeneratedError = assertThrows(
                ApiPathNotFoundException.class,
                () -> api.readEntry("demo", "/first-build/generated/entry.c")
        );
        assertEquals("Path '/first-build/generated/entry.c' does not exist in module 'demo'", staleGeneratedError.getMessage());

        var staleArtifactError = assertThrows(ApiPathNotFoundException.class, () -> api.readEntry("demo", firstArtifactLinkPath));
        assertEquals("Path '" + firstArtifactLinkPath + "' does not exist in module 'demo'", staleArtifactError.getMessage());
    }

    @Test
    void failedRecompileClearsPreviousMountedOutputsWithoutPublishingNewLinks(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeedingThenFailing("native build failed");
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Failed Recompile Demo");
        api.putFile("demo", "/src/main.gd", validSource("FailedRecompileDemo"));
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("first-project")));

        var firstResult = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        assertEquals(CompileResult.Outcome.SUCCESS, firstResult.outcome());
        assertInstanceOf(VfsEntrySnapshot.LinkEntrySnapshot.class, api.readEntry("demo", "/__build__/generated/entry.c"));

        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("second-project")));
        var secondResult = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.BUILD_FAILED, secondResult.outcome());
        assertEquals("Native build reported failure; see buildLog for details", secondResult.failureMessage());
        assertTrue(secondResult.outputLinks().isEmpty());
        assertEquals(
                List.of(
                        tempDir.resolve("second-project").resolve("entry.c"),
                        tempDir.resolve("second-project").resolve("engine_method_binds.h"),
                        tempDir.resolve("second-project").resolve("entry.h")
                ),
                secondResult.generatedFiles()
        );

        var staleGeneratedError = assertThrows(
                ApiPathNotFoundException.class,
                () -> api.readEntry("demo", "/__build__/generated/entry.c")
        );
        assertEquals("Path '/__build__/generated/entry.c' does not exist in module 'demo'", staleGeneratedError.getMessage());

        var staleArtifactError = assertThrows(
                ApiPathNotFoundException.class,
                () -> api.listDirectory("demo", "/__build__/artifacts")
        );
        assertEquals("Path '/__build__/artifacts' does not exist in module 'demo'", staleArtifactError.getMessage());
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
