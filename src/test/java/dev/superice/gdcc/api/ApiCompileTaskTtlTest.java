package dev.superice.gdcc.api;

import dev.superice.gdcc.api.task.CompileTaskHooks;
import dev.superice.gdcc.exception.ApiCompileTaskNotFoundException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCompileTaskTtlTest {
    @Test
    void completedCompileTaskAndEventsExpireAfterTtl(@TempDir Path tempDir) {
        var clock = new ApiCompileTestSupport.MutableClock(ApiCompileTestSupport.FIXED_TIME);
        var emittedEvent = new CompileTaskEvent("backend.build", "Invoking native compiler");
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccessWithEvents(emittedEvent);
        var api = ApiCompileTestSupport.newApi(
                compiler,
                CompileTaskHooks.none(),
                clock,
                Duration.ofSeconds(2),
                Duration.ofMillis(10)
        );

        api.createModule("demo", "Compile Task TTL Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("task-ttl-project")));
        api.putFile("demo", "/src/task_ttl_demo.gd", validSource("CompileTaskTtlDemo"));

        var taskId = api.compile("demo");

        assertTrue(compiler.awaitEntered());
        ApiCompileTestSupport.awaitSnapshot(
                api,
                taskId,
                snapshot -> snapshot.state() == CompileTaskSnapshot.State.RUNNING
                        && snapshot.stage() == CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                "BUILDING_NATIVE"
        );
        assertEquals(List.of(emittedEvent), api.listCompileTaskEvents(taskId));

        compiler.release();
        var completedTask = ApiCompileTestSupport.awaitTask(api, taskId);
        assertEquals(CompileTaskSnapshot.State.SUCCEEDED, completedTask.state());
        assertEquals(List.of(emittedEvent), api.listCompileTaskEvents(taskId));

        clock.advance(Duration.ofSeconds(1));
        ApiCompileTestSupport.sleepForProgressPolling();
        assertEquals(CompileTaskSnapshot.State.SUCCEEDED, api.getCompileTask(taskId).state());

        clock.advance(Duration.ofSeconds(2));
        awaitTaskExpiration(api, taskId);

        assertTaskNotFound(() -> api.getCompileTask(taskId), taskId);
        assertTaskNotFound(() -> api.getLatestCompileTaskEvent(taskId), taskId);
        assertTaskNotFound(() -> api.listCompileTaskEvents(taskId), taskId);
        assertTaskNotFound(() -> api.clearCompileTaskEvents(taskId), taskId);
    }

    @Test
    void runningCompileTaskIsNotReclaimedBeforeCompletionEvenIfClockMovesPastTtl(@TempDir Path tempDir) {
        var clock = new ApiCompileTestSupport.MutableClock(ApiCompileTestSupport.FIXED_TIME);
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(
                compiler,
                CompileTaskHooks.none(),
                clock,
                Duration.ofSeconds(1),
                Duration.ofMillis(10)
        );

        api.createModule("demo", "Running Compile Task TTL Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("running-task-ttl-project")));
        api.putFile("demo", "/src/running_task_ttl_demo.gd", validSource("RunningCompileTaskTtlDemo"));

        var taskId = api.compile("demo");

        assertTrue(compiler.awaitEntered());
        ApiCompileTestSupport.awaitSnapshot(
                api,
                taskId,
                snapshot -> snapshot.state() == CompileTaskSnapshot.State.RUNNING
                        && snapshot.stage() == CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                "BUILDING_NATIVE"
        );

        clock.advance(Duration.ofMinutes(5));
        ApiCompileTestSupport.sleepForProgressPolling();
        var runningTask = api.getCompileTask(taskId);
        assertEquals(CompileTaskSnapshot.State.RUNNING, runningTask.state());
        assertEquals(CompileTaskSnapshot.Stage.BUILDING_NATIVE, runningTask.stage());

        compiler.release();
        var completedTask = ApiCompileTestSupport.awaitTask(api, taskId);
        assertEquals(CompileTaskSnapshot.State.SUCCEEDED, completedTask.state());

        clock.advance(Duration.ofSeconds(2));
        awaitTaskExpiration(api, taskId);
        assertTaskNotFound(() -> api.getCompileTask(taskId), taskId);
    }

    private static void awaitTaskExpiration(@NotNull API api, long taskId) {
        var deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                api.getCompileTask(taskId);
                ApiCompileTestSupport.sleepForProgressPolling();
            } catch (ApiCompileTaskNotFoundException _) {
                return;
            }
        }
        throw new AssertionError("Timed out waiting for compile task " + taskId + " to expire");
    }

    private static void assertTaskNotFound(@NotNull Executable executable, long taskId) {
        var missingError = assertThrows(ApiCompileTaskNotFoundException.class, executable);
        assertEquals("Compile task '" + taskId + "' does not exist", missingError.getMessage());
    }

    private static String validSource(String className) {
        return """
                class_name %s
                extends RefCounted
                
                func value() -> int:
                    return 5
                """.formatted(className);
    }
}
