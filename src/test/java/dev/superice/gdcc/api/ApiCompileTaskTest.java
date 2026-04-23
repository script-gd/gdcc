package dev.superice.gdcc.api;

import dev.superice.gdcc.exception.ApiCompileAlreadyRunningException;
import dev.superice.gdcc.exception.ApiCompileTaskNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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

        assertTrue(compiler.awaitEntered());
        var runningTask = api.getCompileTask(taskId);
        assertEquals(taskId, runningTask.taskId());
        assertEquals("demo", runningTask.moduleId());
        assertEquals(CompileTaskSnapshot.State.RUNNING, runningTask.state());
        assertNull(runningTask.result());

        var duplicateCompileError = assertThrows(ApiCompileAlreadyRunningException.class, () -> api.compile("demo"));
        assertEquals("Module 'demo' already has active compile task " + taskId, duplicateCompileError.getMessage());

        compiler.release();
        var completedTask = ApiCompileTestSupport.awaitTask(api, taskId);

        assertEquals(CompileTaskSnapshot.State.SUCCEEDED, completedTask.state());
        assertTrue(completedTask.success());
        assertTrue(compiler.ranOnVirtualThread());
        assertEquals(completedTask.result(), api.getLastCompileResult("demo"));
    }

    @Test
    void getCompileTaskRejectsUnknownTaskId() {
        var api = new API(ApiCompileTestSupport.FIXED_CLOCK);

        var missingError = assertThrows(ApiCompileTaskNotFoundException.class, () -> api.getCompileTask(42));
        assertEquals("Compile task '42' does not exist", missingError.getMessage());
    }
}
