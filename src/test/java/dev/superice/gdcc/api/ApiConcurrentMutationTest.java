package dev.superice.gdcc.api;

import dev.superice.gdcc.exception.ApiModuleBusyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiConcurrentMutationTest {
    @Test
    void sameModuleWriteWaitsForActiveCompileAndOnlyAffectsTheNextCompile(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Concurrent Mutation Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("concurrent-mutation-project")));
        api.putFile("demo", "/src/demo.gd", validSource("ConcurrentMutationDemo"));

        var firstTaskId = api.compile("demo");
        awaitNativeBuild(api, compiler, firstTaskId);

        try {
            try (var executor = Executors.newSingleThreadExecutor()) {
                var writeFuture = executor.submit(() -> api.putFile("demo", "/src/demo.gd", brokenSource()));

                assertThrows(TimeoutException.class, () -> writeFuture.get(200, TimeUnit.MILLISECONDS));

                compiler.release();
                var firstResult = ApiCompileTestSupport.awaitResult(api, firstTaskId);
                var updatedEntry = writeFuture.get(5, TimeUnit.SECONDS);

                assertEquals(CompileResult.Outcome.SUCCESS, firstResult.outcome());
                assertEquals("/src/demo.gd", updatedEntry.path());
                assertEquals(brokenSource(), api.readFile("demo", "/src/demo.gd"));

                var secondResult = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

                assertEquals(CompileResult.Outcome.FRONTEND_FAILED, secondResult.outcome());
                assertEquals("Frontend diagnostics blocked compilation", secondResult.failureMessage());
                assertEquals(1, compiler.invocationCount());
            }
        } finally {
            compiler.release();
        }
    }

    @Test
    void deleteModuleFailsClearlyWhileCompileTaskIsQueuedOrActive(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Concurrent Delete Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("concurrent-delete-project")));
        api.putFile("demo", "/src/demo.gd", validSource("ConcurrentDeleteDemo"));

        try {
            var taskId = api.compile("demo");
            awaitNativeBuild(api, compiler, taskId);

            var error = assertThrows(ApiModuleBusyException.class, () -> api.deleteModule("demo"));
            assertEquals(
                    "Module 'demo' cannot be deleted while compile task " + taskId + " is queued or active",
                    error.getMessage()
            );

            compiler.release();
            var result = ApiCompileTestSupport.awaitResult(api, taskId);

            assertEquals(CompileResult.Outcome.SUCCESS, result.outcome());
            assertEquals("demo", api.getModule("demo").moduleId());
        } finally {
            compiler.release();
        }
    }

    private static void awaitNativeBuild(API api, ApiCompileTestSupport.RecordingCompiler compiler, long taskId) {
        assertTrue(compiler.awaitEntered());
        ApiCompileTestSupport.awaitSnapshot(
                api,
                taskId,
                snapshot -> snapshot.state() == CompileTaskSnapshot.State.RUNNING
                        && snapshot.stage() == CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                "BUILDING_NATIVE"
        );
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
                class_name BrokenConcurrentMutation
                extends RefCounted
                
                func value(
                    pass
                """;
    }
}
