package gd.script.gdcc.api;

import gd.script.gdcc.backend.c.build.CProjectBuilder;
import gd.script.gdcc.backend.c.build.FakeProcess;
import gd.script.gdcc.backend.c.build.FakeProcessLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// API-level wiring gate for the multi-process cancellation protocol, driving the REAL
/// pipeline — API task runner → real `CProjectBuilder` (codegen included) → real
/// `ZigCcCompiler` — with only the OS process layer faked out ([FakeProcessLauncher], all
/// processes block until destroyed). The existing `RecordingCompiler` cancellation tests
/// replace the whole `CCompiler` and therefore cannot observe this layer. Anchors:
/// - `cancelCompileTask()` during `BUILDING_NATIVE` destroys every started zig child, never
///   starts the link step, maps to `CANCELED`, and lets no process start after completion;
/// - `API.close()` cancels the same way and returns only after the runner (and with it every
///   compiler worker and child process) is gone.
/// Pure Java: no real zig process is started and zig discovery is faked through the compiler
/// injection seam.
class ApiZigCcCompilerCancellationTest {

    @Test
    void cancelDestroysAllStartedZigProcessesAndNeverStartsLink(@TempDir Path tempDir) throws Exception {
        var launcher = new FakeProcessLauncher().blockProcessesUntilDestroyed();
        var api = ApiCompileTestSupport.newApi(new CProjectBuilder(launcher.newCompiler()));
        api.createModule("demo", "Zig Cancel Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("zig-cancel-project")));
        api.putFile("demo", "/src/demo.gd", validSource("ZigCancelDemo"));

        try {
            var taskId = api.compile("demo");
            ApiCompileTestSupport.awaitSnapshot(
                    api,
                    taskId,
                    snapshot -> snapshot.state() == CompileTaskSnapshot.State.RUNNING
                            && snapshot.stage() == CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                    "BUILDING_NATIVE"
            );
            assertTrue(launcher.awaitFirstProcess(), "at least one zig TU process must be running");

            api.cancelCompileTask(taskId);
            var canceledTask = ApiCompileTestSupport.awaitTask(api, taskId);

            assertEquals(CompileTaskSnapshot.State.CANCELED, canceledTask.state());
            assertEquals(CompileTaskSnapshot.Stage.BUILDING_NATIVE, canceledTask.stage());
            assertEquals(CompileResult.Outcome.CANCELED, Objects.requireNonNull(canceledTask.result()).outcome());
            var started = launcher.startedProcesses();
            assertFalse(started.isEmpty());
            assertTrue(started.stream().allMatch(FakeProcess::destroyForciblyCalled),
                    "every started zig child must be forcibly destroyed by the cancel protocol");
            assertEquals(0, launcher.countCommandsContaining("-shared"), "cancel must never start the link step");
            // The runner awaited all TU workers before returning: no late process may appear.
            var commandsAtCompletion = launcher.recordedCommands().size();
            Thread.sleep(150);
            assertEquals(commandsAtCompletion, launcher.recordedCommands().size(),
                    "no zig process may start after the cancelled task completed");
        } finally {
            api.close();
        }
    }

    @Test
    void closeCancelsNativeBuildAndReturnsAfterRunnerAndProcessesExit(@TempDir Path tempDir) throws Exception {
        var launcher = new FakeProcessLauncher().blockProcessesUntilDestroyed();
        var api = ApiCompileTestSupport.newApi(new CProjectBuilder(launcher.newCompiler()));
        api.createModule("demo", "Zig Close Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("zig-close-project")));
        api.putFile("demo", "/src/demo.gd", validSource("ZigCloseDemo"));

        var taskId = api.compile("demo");
        ApiCompileTestSupport.awaitSnapshot(
                api,
                taskId,
                snapshot -> snapshot.state() == CompileTaskSnapshot.State.RUNNING
                        && snapshot.stage() == CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                "BUILDING_NATIVE"
        );
        assertTrue(launcher.awaitFirstProcess(), "at least one zig TU process must be running");

        // close() cancels the running task, interrupts the runner, and joins it with the
        // bounded shutdown budget; the compiler's own cancel protocol destroys the children
        // and waits for its workers first, so a timely return proves the whole chain exited.
        api.close();

        // Read-only queries stay legal after close and expose the teardown outcome.
        var closedTask = api.getCompileTask(taskId);
        assertEquals(CompileTaskSnapshot.State.CANCELED, closedTask.state(), "the runner must have finished (as CANCELED) before close returned");
        assertEquals(CompileResult.Outcome.CANCELED, Objects.requireNonNull(closedTask.result()).outcome());
        assertTrue(launcher.startedProcesses().stream().allMatch(FakeProcess::destroyForciblyCalled),
                "every started zig child must be destroyed before close returns");
        assertEquals(0, launcher.countCommandsContaining("-shared"));
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
