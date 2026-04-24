package dev.superice.gdcc.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCompileTaskCancellationTest {
    @Test
    void queuedCompileTaskCanBeCanceledBeforeItAcquiresModuleGate(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Queued Cancel Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("queued-cancel-project")));
        api.putFile("demo", "/src/demo.gd", validSource("QueuedCancelDemo"));

        try (var blocker = ApiCompileTestSupport.blockModuleOperation(api, "demo")) {
            assertTrue(blocker.awaitEntered());

            var canceledTaskId = api.compile("demo");
            var queuedTask = api.getCompileTask(canceledTaskId);
            assertEquals(CompileTaskSnapshot.State.QUEUED, queuedTask.state());
            assertEquals(CompileTaskSnapshot.Stage.QUEUED, queuedTask.stage());

            var canceledTask = api.cancelCompileTask(canceledTaskId);
            assertEquals(CompileTaskSnapshot.State.CANCELED, canceledTask.state());
            assertEquals(CompileTaskSnapshot.Stage.QUEUED, canceledTask.stage());
            assertFalse(canceledTask.success());
            assertEquals(CompileResult.Outcome.CANCELED, Objects.requireNonNull(canceledTask.result()).outcome());

            blocker.release();
        }

        try {
            assertEquals(CompileResult.Outcome.CANCELED, Objects.requireNonNull(api.getLastCompileResult("demo")).outcome());
            var nextTaskId = api.compile("demo");
            assertTrue(compiler.awaitEntered());
            compiler.release();

            var completedTask = ApiCompileTestSupport.awaitTask(api, nextTaskId);
            assertEquals(CompileTaskSnapshot.State.SUCCEEDED, completedTask.state());
            assertEquals(CompileResult.Outcome.SUCCESS, Objects.requireNonNull(completedTask.result()).outcome());
            assertEquals(1, compiler.invocationCount());
        } finally {
            compiler.release();
        }
    }

    @Test
    void runningCompileTaskCanBeCanceledAndReleasesModule(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Running Cancel Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("running-cancel-project")));
        api.putFile("demo", "/src/demo.gd", validSource("RunningCancelDemo"));

        try {
            var taskId = api.compile("demo");
            assertTrue(compiler.awaitEntered());
            ApiCompileTestSupport.awaitSnapshot(
                    api,
                    taskId,
                    snapshot -> snapshot.state() == CompileTaskSnapshot.State.RUNNING
                            && snapshot.stage() == CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                    "BUILDING_NATIVE"
            );

            api.cancelCompileTask(taskId);
            var canceledTask = ApiCompileTestSupport.awaitTask(api, taskId);

            assertEquals(CompileTaskSnapshot.State.CANCELED, canceledTask.state());
            assertEquals(CompileTaskSnapshot.Stage.BUILDING_NATIVE, canceledTask.stage());
            assertEquals(CompileResult.Outcome.CANCELED, Objects.requireNonNull(canceledTask.result()).outcome());
            assertEquals(canceledTask.result(), api.getLastCompileResult("demo"));

            var updatedEntry = api.putFile("demo", "/src/demo.gd", validSource("RunningCancelDemoUpdated"));
            assertEquals("/src/demo.gd", updatedEntry.path());
            assertEquals(1, compiler.invocationCount());
        } finally {
            compiler.release();
        }
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
