package dev.superice.gdcc.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCompileTaskProgressTest {
    @Test
    void compileTaskReportsParsingProgressAndKeepsRevisionStableWithoutFurtherUpdates(@TempDir Path tempDir) {
        var blocker = new ApiCompileTestSupport.SnapshotBlocker(snapshot ->
                snapshot.stage() == CompileTaskSnapshot.Stage.PARSING
                        && snapshot.completedUnits() == 1
                        && snapshot.totalUnits() == 2
                        && "shown second.gd".equals(snapshot.currentSourcePath())
        );
        var api = ApiCompileTestSupport.newApi(
                ApiCompileTestSupport.RecordingCompiler.succeeding(),
                blocker.hooks()
        );

        api.createModule("demo", "Compile Progress Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("progress-project")));
        api.putFile("demo", "/src/first.gd", validSource("FirstProgressFile"));
        api.putFile("demo", "/src/second.gd", validSource("SecondProgressFile"), "shown second.gd");

        var taskId = api.compile("demo");

        assertTrue(blocker.awaitEntered());
        var parsingTask = api.getCompileTask(taskId);

        assertEquals(CompileTaskSnapshot.State.RUNNING, parsingTask.state());
        assertEquals(CompileTaskSnapshot.Stage.PARSING, parsingTask.stage());
        assertEquals("Parsing shown second.gd", parsingTask.stageMessage());
        assertEquals(1, parsingTask.completedUnits());
        assertEquals(2, parsingTask.totalUnits());
        assertEquals("shown second.gd", parsingTask.currentSourcePath());
        assertEquals(blocker.matchedSnapshot(), parsingTask);

        ApiCompileTestSupport.sleepForProgressPolling();
        var unchangedTask = api.getCompileTask(taskId);

        assertEquals(parsingTask, unchangedTask);
        assertEquals(parsingTask.revision(), unchangedTask.revision());

        blocker.release();
        var completedTask = ApiCompileTestSupport.awaitTask(api, taskId);

        assertEquals(CompileTaskSnapshot.State.SUCCEEDED, completedTask.state());
        assertEquals(CompileTaskSnapshot.Stage.FINISHED, completedTask.stage());
        assertEquals(2, completedTask.completedUnits());
        assertEquals(2, completedTask.totalUnits());
        assertNull(completedTask.currentSourcePath());
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
