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

    @Test
    void frontendFailureKeepsPreviousMountedOutputsUntilNextSuccessfulPublish(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Frontend Failure Refresh Demo");
        api.putFile("demo", "/src/main.gd", validSource("FrontendFailureRefreshDemo"));
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
        assertEquals(CompileResult.Outcome.SUCCESS, firstResult.outcome());
        assertPublishedLinkExists(api, "/first-build/generated/entry.c");

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
        api.putFile("demo", "/src/main.gd", brokenSource());

        var secondResult = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.FRONTEND_FAILED, secondResult.outcome());
        assertTrue(secondResult.outputLinks().isEmpty());
        assertPublishedLinkExists(api, "/first-build/generated/entry.c");
        assertPathMissing(api, "/second-build/generated/entry.c");

        api.putFile("demo", "/src/main.gd", validSource("FrontendFailureRefreshDemo"));
        var thirdResult = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.SUCCESS, thirdResult.outcome());
        assertPublishedLinkExists(api, "/second-build/generated/entry.c");
        assertPathMissing(api, "/first-build/generated/entry.c");
    }

    @Test
    void sourceCollectionFailureKeepsPreviouslyMountedOutputs(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Source Collection Failure Demo");
        api.putFile("demo", "/src/main.gd", validSource("SourceCollectionFailureDemo"));
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("source-collection-project")));

        var firstResult = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        assertEquals(CompileResult.Outcome.SUCCESS, firstResult.outcome());
        assertPublishedLinkExists(api, "/__build__/generated/entry.c");

        api.createLink("demo", "/broken", VfsEntrySnapshot.LinkKind.VIRTUAL, "/missing");
        var secondResult = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.SOURCE_COLLECTION_FAILED, secondResult.outcome());
        assertTrue(secondResult.outputLinks().isEmpty());
        assertEquals(
                "Virtual link '/broken' in module 'demo' points to missing path '/missing'",
                secondResult.failureMessage()
        );
        assertEquals(1, compiler.invocationCount());
        assertPublishedLinkExists(api, "/__build__/generated/entry.c");
    }

    @Test
    void configurationFailureKeepsPreviouslyMountedOutputs(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Configuration Failure Demo");
        api.putFile("demo", "/src/main.gd", validSource("ConfigurationFailureDemo"));
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("configuration-success-project")));

        var firstResult = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        assertEquals(CompileResult.Outcome.SUCCESS, firstResult.outcome());
        assertPublishedLinkExists(api, "/__build__/generated/entry.c");

        api.setCompileOptions("demo", CompileOptions.defaults());
        var secondResult = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.CONFIGURATION_FAILED, secondResult.outcome());
        assertEquals(
                "Compile options for module 'demo' must set projectPath before compile",
                secondResult.failureMessage()
        );
        assertTrue(secondResult.outputLinks().isEmpty());
        assertEquals(1, compiler.invocationCount());
        assertPublishedLinkExists(api, "/__build__/generated/entry.c");
    }

    private static String validSource(String className) {
        return """
                class_name %s
                extends RefCounted
                
                func value() -> int:
                    return 1
                """.formatted(className);
    }

    private static String brokenSource() {
        return """
                class_name BrokenRefreshDemo
                extends RefCounted
                
                func value(
                    return 1
                """;
    }

    private static void assertPublishedLinkExists(API api, String virtualPath) {
        assertInstanceOf(VfsEntrySnapshot.LinkEntrySnapshot.class, api.readEntry("demo", virtualPath));
    }

    private static void assertPathMissing(API api, String virtualPath) {
        var missingPath = assertThrows(ApiPathNotFoundException.class, () -> api.readEntry("demo", virtualPath));
        assertEquals("Path '" + virtualPath + "' does not exist in module 'demo'", missingPath.getMessage());
    }
}
