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
        assertEquals(
                List.of(
                        new CompileTaskEvent.Indexed(0, emittedEvents.getFirst()),
                        new CompileTaskEvent.Indexed(1, emittedEvents.getLast())
                ),
                api.listCompileTaskEvents(taskId, 0, 10)
        );
        assertEquals(
                List.of(new CompileTaskEvent.Indexed(1, emittedEvents.getLast())),
                api.listCompileTaskEvents(taskId, 1, 1)
        );
        assertEquals(List.of(), api.listCompileTaskEvents(taskId, 2, 10));
        assertEquals(
                List.of(new CompileTaskEvent.Indexed(1, emittedEvents.getLast())),
                api.listCompileTaskEvents(taskId, " backend.build ", 0, 10)
        );
        assertEquals(List.of(), api.listCompileTaskEvents(taskId, "backend.codegen", 1, 10));

        api.clearCompileTaskEvents(taskId);
        assertEquals(List.of(), api.listCompileTaskEvents(taskId));
        assertEquals(List.of(), api.listCompileTaskEvents(taskId, 0, 10));
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

        var indexedListError = assertThrows(ApiCompileTaskNotFoundException.class, () ->
                api.listCompileTaskEvents(42, "backend.build", 0, 10)
        );
        assertEquals("Compile task '42' does not exist", indexedListError.getMessage());

        var clearError = assertThrows(ApiCompileTaskNotFoundException.class, () -> api.clearCompileTaskEvents(42));
        assertEquals("Compile task '42' does not exist", clearError.getMessage());
    }

    @Test
    void indexedCompileTaskEventAccessorsRejectInvalidPageArguments() {
        var api = new API(ApiCompileTestSupport.FIXED_CLOCK);

        var negativeStartIndex = assertThrows(IllegalArgumentException.class, () ->
                api.listCompileTaskEvents(42, -1, 10)
        );
        assertEquals("startIndex must not be negative", negativeStartIndex.getMessage());

        var zeroMaxCount = assertThrows(IllegalArgumentException.class, () ->
                api.listCompileTaskEvents(42, 0, 0)
        );
        assertEquals("maxCount must be between 1 and " + API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE,
                zeroMaxCount.getMessage());

        var tooLargeMaxCount = assertThrows(IllegalArgumentException.class, () ->
                api.listCompileTaskEvents(42, 0, API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE + 1)
        );
        assertEquals("maxCount must be between 1 and " + API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE,
                tooLargeMaxCount.getMessage());

        var blankCategory = assertThrows(IllegalArgumentException.class, () ->
                api.listCompileTaskEvents(42, " ", 0, 10)
        );
        assertEquals("category must not be blank", blankCategory.getMessage());
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
