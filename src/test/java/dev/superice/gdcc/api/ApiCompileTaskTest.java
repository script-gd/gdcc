package dev.superice.gdcc.api;

import dev.superice.gdcc.exception.ApiCompileAlreadyRunningException;
import dev.superice.gdcc.exception.ApiCompileTaskNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCompileTaskTest {
    @Test
    void compileRunsOnVirtualThreadAndOnlyAllowsOneActiveTaskPerModule(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Compile Task Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("task-project")));
        api.putFile("demo", "/src/task_demo.gd", """
                class_name CompileTaskDemo
                extends RefCounted
                
                func value() -> int:
                    return 3
                """);

        var taskId = api.compile("demo");

        var queuedTask = api.getCompileTask(taskId);
        assertEquals(CompileTaskSnapshot.State.QUEUED, queuedTask.state());
        assertEquals(CompileTaskSnapshot.Stage.QUEUED, queuedTask.stage());
        assertEquals(1, queuedTask.revision());
        assertNull(queuedTask.result());

        assertTrue(compiler.awaitEntered());
        var runningTask = ApiCompileTestSupport.awaitSnapshot(
                api,
                taskId,
                snapshot -> snapshot.state() == CompileTaskSnapshot.State.RUNNING
                        && snapshot.stage() == CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                "BUILDING_NATIVE"
        );
        assertEquals(taskId, runningTask.taskId());
        assertEquals("demo", runningTask.moduleId());
        assertEquals(CompileTaskSnapshot.State.RUNNING, runningTask.state());
        assertEquals(CompileTaskSnapshot.Stage.BUILDING_NATIVE, runningTask.stage());
        assertTrue(runningTask.revision() > queuedTask.revision());
        assertNull(runningTask.result());

        var duplicateCompileError = assertThrows(ApiCompileAlreadyRunningException.class, () -> api.compile("demo"));
        assertEquals("Module 'demo' already has active compile task " + taskId, duplicateCompileError.getMessage());

        compiler.release();
        var completedTask = ApiCompileTestSupport.awaitTask(api, taskId);

        assertEquals(CompileTaskSnapshot.State.SUCCEEDED, completedTask.state());
        assertEquals(CompileTaskSnapshot.Stage.FINISHED, completedTask.stage());
        assertTrue(completedTask.success());
        assertTrue(compiler.ranOnVirtualThread());
        assertEquals(completedTask.result(), api.getLastCompileResult("demo"));
    }

    @Test
    void compileReturnsQueuedTaskImmediatelyWhileModuleIsBusyWithNormalOperation(@TempDir Path tempDir) throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Queued Compile Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("queued-compile-project")));
        api.putFile("demo", "/src/demo.gd", """
                class_name QueuedCompileDemo
                extends RefCounted
                
                func value() -> int:
                    return 7
                """);

        try (var blocker = ApiCompileTestSupport.blockModuleOperation(api, "demo");
             var executor = Executors.newSingleThreadExecutor()) {
            assertTrue(blocker.awaitEntered());

            var taskId = executor.submit(() -> api.compile("demo")).get(500, TimeUnit.MILLISECONDS);
            var queuedTask = api.getCompileTask(taskId);

            assertEquals(CompileTaskSnapshot.State.QUEUED, queuedTask.state());
            assertEquals(CompileTaskSnapshot.Stage.QUEUED, queuedTask.stage());
            assertEquals(1, queuedTask.revision());
            assertNull(queuedTask.result());

            var duplicateCompileError = assertThrows(ApiCompileAlreadyRunningException.class, () -> api.compile("demo"));
            assertEquals("Module 'demo' already has queued compile task " + taskId, duplicateCompileError.getMessage());

            blocker.release();
            assertTrue(compiler.awaitEntered());
            compiler.release();
            var completedTask = ApiCompileTestSupport.awaitTask(api, taskId);

            assertEquals(CompileTaskSnapshot.State.SUCCEEDED, completedTask.state());
            assertEquals(CompileTaskSnapshot.Stage.FINISHED, completedTask.stage());
            assertTrue(completedTask.success());
            assertTrue(compiler.ranOnVirtualThread());
        } finally {
            compiler.release();
        }
    }

    @Test
    void getCompileTaskRejectsUnknownTaskId() {
        var api = new API(ApiCompileTestSupport.FIXED_CLOCK);

        var missingError = assertThrows(ApiCompileTaskNotFoundException.class, () -> api.getCompileTask(42));
        assertEquals("Compile task '42' does not exist", missingError.getMessage());
    }
}
