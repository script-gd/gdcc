package dev.superice.gdcc.api;

import dev.superice.gdcc.exception.ApiCompileTaskNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCompileTaskEventTest {
    @Test
    void compileTaskEventsCanBeRecordedListedReadBackAndCleared(@TempDir Path tempDir) {
        var emittedEvents = List.of(
                new CompileTaskEvent("backend.codegen", "Prepared generated C sources"),
                new CompileTaskEvent("backend.build", "Invoking native compiler")
        );
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccessWithEvents(
                emittedEvents.getFirst(),
                emittedEvents.getLast()
        );
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Compile Task Event Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("task-event-project")));
        api.putFile("demo", "/src/task_event_demo.gd", validSource("CompileTaskEventDemo"));

        var taskId = api.compile("demo");

        assertTrue(compiler.awaitEntered());
        var runningTask = ApiCompileTestSupport.awaitSnapshot(
                api,
                taskId,
                snapshot -> snapshot.state() == CompileTaskSnapshot.State.RUNNING
                        && snapshot.stage() == CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                "BUILDING_NATIVE"
        );
        assertEquals(taskId, runningTask.taskId());

        assertEquals(emittedEvents, api.listCompileTaskEvents(taskId));
        assertEquals(emittedEvents.getLast(), api.getLatestCompileTaskEvent(taskId));

        api.clearCompileTaskEvents(taskId);
        assertEquals(List.of(), api.listCompileTaskEvents(taskId));
        assertNull(api.getLatestCompileTaskEvent(taskId));

        compiler.release();
        var completedTask = ApiCompileTestSupport.awaitTask(api, taskId);

        assertEquals(CompileTaskSnapshot.State.SUCCEEDED, completedTask.state());
        assertEquals(List.of(), api.listCompileTaskEvents(taskId));
        assertNull(api.getLatestCompileTaskEvent(taskId));
    }

    @Test
    void recordCurrentCompileTaskEventReturnsFalseOutsideCompileTaskThread() {
        assertFalse(API.recordCurrentCompileTaskEvent("frontend.note", "outside api compile task"));
    }

    @Test
    void recordCurrentCompileTaskEventRejectsBlankFields() {
        var blankCategory = assertThrows(IllegalArgumentException.class, () ->
                API.recordCurrentCompileTaskEvent(" ", "valid detail")
        );
        assertEquals("category must not be blank", blankCategory.getMessage());

        var blankDetail = assertThrows(IllegalArgumentException.class, () ->
                API.recordCurrentCompileTaskEvent("frontend.note", " ")
        );
        assertEquals("detail must not be blank", blankDetail.getMessage());
    }

    @Test
    void compileTaskEventAccessorsRejectUnknownTaskId() {
        var api = new API(ApiCompileTestSupport.FIXED_CLOCK);

        var latestError = assertThrows(ApiCompileTaskNotFoundException.class, () -> api.getLatestCompileTaskEvent(42));
        assertEquals("Compile task '42' does not exist", latestError.getMessage());

        var listError = assertThrows(ApiCompileTaskNotFoundException.class, () -> api.listCompileTaskEvents(42));
        assertEquals("Compile task '42' does not exist", listError.getMessage());

        var clearError = assertThrows(ApiCompileTaskNotFoundException.class, () -> api.clearCompileTaskEvents(42));
        assertEquals("Compile task '42' does not exist", clearError.getMessage());
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
