package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Parallel-execution and cancellation gate for [ZigCcCompiler], driving the real round logic
/// with a [FakeProcessLauncher] (pure Java: no real zig process is started and zig discovery
/// is faked too). Anchors:
/// - success path runs all TUs plus exactly one link, objects stay out of artifacts, and at
///   least two TUs are concurrently in flight (parallelism is proven, not assumed);
/// - interrupt closes the registry, destroys every started child, never starts the link, waits
///   for workers, and surfaces through the `Failed to run zig: interrupted` channel with the
///   interrupt status restored — including when a destroyed child leaves an orphaned
///   grandchild holding the output pipe open (no EOF);
/// - a failing TU does not stop its siblings: all run to completion, the failure log keeps the
///   `cFiles` slot order, and the link never starts;
/// - an interrupt during failure collection preempts the failure report;
/// - a TU that cannot even start surfaces through the IOException channel without Command
///   sections.
class ZigCcCompilerParallelTest {

    @Test
    void parallelCompileAndLinkSucceedsWithDeterministicLog(@TempDir Path tempDir) throws IOException {
        var sources = writeTuSources(tempDir, "probe_a.c", "probe_b.c", "probe_c.c");
        var launcher = new FakeProcessLauncher()
                .outputProvider(cmd -> "output-of-" + sourceToken(cmd) + "\n");
        var compiler = launcher.newCompiler();

        var result = compiler.compile(tempDir, List.of(), sources, "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertTrue(result.success(), () -> "all fake steps succeed:\n" + result.buildLog());
        assertEquals(4, launcher.recordedCommands().size(), "three TUs plus exactly one link");
        assertEquals(1, launcher.countCommandsContaining("-shared"));
        assertEquals(3, launcher.recordedCommands().stream().filter(cmd -> cmd.contains("-c")).count());
        var targetPlatform = TargetPlatform.getNativePlatform();
        assertEquals(targetPlatform.sharedLibraryFileName("probe"), result.artifacts().getFirst().getFileName().toString());
        assertTrue(result.artifacts().stream().noneMatch(path -> path.toString().contains("/obj/")),
                "intermediate objects must never enter artifacts");
        // Success path: raw outputs concatenated in cFiles slot order, link last, no Command lines.
        var buildLog = result.buildLog();
        assertFalse(buildLog.contains("Command: "), () -> "success log has no command sections:\n" + buildLog);
        var indexA = buildLog.indexOf("output-of-probe_a.c");
        var indexB = buildLog.indexOf("output-of-probe_b.c");
        var indexC = buildLog.indexOf("output-of-probe_c.c");
        var indexLink = buildLog.indexOf("output-of-<link>");
        assertTrue(indexA >= 0 && indexB > indexA && indexC > indexB && indexLink > indexC,
                () -> "outputs merge in slot order regardless of completion order:\n" + buildLog);
        assertTrue(launcher.startedProcesses().stream().noneMatch(FakeProcess::destroyForciblyCalled),
                "a clean round must not destroy any process");
    }

    @Test
    void tusRunConcurrentlyNotSequentially(@TempDir Path tempDir) throws Exception {
        // Both TUs block until destroyed: they can only be alive at the same time when the
        // implementation actually runs them in parallel (needs at least two cores for the
        // parallelism cap to allow two workers).
        Assumptions.assumeTrue(Runtime.getRuntime().availableProcessors() >= 2,
                "needs at least two cores for two concurrent TU workers");
        var sources = writeTuSources(tempDir, "probe_a.c", "probe_b.c");
        var launcher = new FakeProcessLauncher().blockProcessesUntilDestroyed();
        var compiler = launcher.newCompiler();
        var resultRef = new AtomicReference<CCompileResult>();
        var interruptRestored = new AtomicBoolean();
        var compileThread = startCompileThread(compiler, tempDir, sources, resultRef, interruptRestored);

        launcher.awaitStartedProcessCount(2);
        var tus = launcher.startedProcesses();
        assertTrue(tus.stream().allMatch(FakeProcess::isAlive),
                "both TU processes must be concurrently in flight — a serial implementation can never reach this");
        tus.forEach(FakeProcess::destroyForcibly);
        // The blocking policy applies to the link process too: release it once it starts.
        launcher.awaitStartedProcessCount(3);
        launcher.startedProcesses().stream().filter(FakeProcess::isAlive).forEach(FakeProcess::destroyForcibly);
        compileThread.join(Duration.ofSeconds(30));
        assertFalse(compileThread.isAlive());
        assertTrue(resultRef.get().success(), () -> "released TUs (exit 0) link successfully:\n" + resultRef.get().buildLog());
        assertEquals(1, launcher.countCommandsContaining("-shared"));
    }

    @Test
    void interruptDestroysAllStartedProcessesSkipsLinkAndWaitsForWorkers(@TempDir Path tempDir) throws Exception {
        var sources = writeTuSources(tempDir, "probe_a.c", "probe_b.c", "probe_c.c");
        var launcher = new FakeProcessLauncher().blockProcessesUntilDestroyed();
        var compiler = launcher.newCompiler();
        var resultRef = new AtomicReference<CCompileResult>();
        var interruptRestored = new AtomicBoolean();
        var compileThread = startCompileThread(compiler, tempDir, sources, resultRef, interruptRestored);

        assertTrue(launcher.awaitFirstProcess(), "at least one TU process must be running before the cancel");
        compileThread.interrupt();
        compileThread.join(Duration.ofSeconds(30));
        assertFalse(compileThread.isAlive(), "compile must return promptly once children are destroyed");

        var result = resultRef.get();
        assertFalse(result.success());
        assertEquals("Failed to run zig: interrupted", result.buildLog(), "cancel surfaces through the interrupted channel");
        assertTrue(result.artifacts().isEmpty());
        assertTrue(interruptRestored.get(), "compile must restore the interrupt status for the runner's CANCELED mapping");
        var started = launcher.startedProcesses();
        assertFalse(started.isEmpty());
        assertTrue(started.stream().allMatch(FakeProcess::destroyForciblyCalled),
                "every started zig child must be forcibly destroyed on cancel");
        assertEquals(0, launcher.countCommandsContaining("-shared"), "cancel must never start the link step");
        // compile() awaited all workers before returning: no late TU launch may appear afterwards.
        var commandsAtReturn = launcher.recordedCommands().size();
        Thread.sleep(150);
        assertEquals(commandsAtReturn, launcher.recordedCommands().size(),
                "workers must have exited before compile returned; no process may start after the cancel");
    }

    @Test
    void cancelConvergesWhenDestroyedProcessLeavesOrphanedPipeOpen(@TempDir Path tempDir) throws Exception {
        // The destroyed zig wrapper left an orphaned grandchild holding the output pipe open:
        // waitFor returns on destroy, but the drain thread never sees EOF. The cancel path
        // must interrupt the drain and bound its join instead of parking the worker forever.
        var sources = writeTuSources(tempDir, "probe_a.c", "probe_b.c");
        var launcher = new FakeProcessLauncher().orphanPipeCommands(_ -> true);
        var compiler = launcher.newCompiler();
        var resultRef = new AtomicReference<CCompileResult>();
        var interruptRestored = new AtomicBoolean();
        var compileThread = startCompileThread(compiler, tempDir, sources, resultRef, interruptRestored);

        launcher.awaitStartedProcessCount(2);
        compileThread.interrupt();
        compileThread.join(Duration.ofSeconds(30));
        assertFalse(compileThread.isAlive(),
                "compile must converge even when a destroyed child's output pipe never reaches EOF");

        var result = resultRef.get();
        assertFalse(result.success());
        assertEquals("Failed to run zig: interrupted", result.buildLog());
        assertTrue(launcher.startedProcesses().stream().allMatch(FakeProcess::destroyForciblyCalled));
    }

    @Test
    void failingTuRunsSiblingsToCompletionWithOrderedLog(@TempDir Path tempDir) throws IOException {
        var sources = writeTuSources(tempDir, "probe_a.c", "probe_b.c", "probe_c.c");
        var launcher = new FakeProcessLauncher()
                .failCommandsWithExitOne(cmd -> sourceToken(cmd).equals("probe_b.c"))
                .outputProvider(cmd -> sourceToken(cmd).equals("probe_b.c") ? "error: simulated broken TU\n" : "");
        var compiler = launcher.newCompiler();

        var result = compiler.compile(tempDir, List.of(), sources, "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty());
        var buildLog = result.buildLog();
        assertEquals(3, countCommandSections(buildLog),
                () -> "siblings run to completion for a complete log; all three TUs get sections:\n" + buildLog);
        var indexA = buildLog.indexOf("probe_a.c");
        var indexB = buildLog.indexOf("probe_b.c");
        var indexC = buildLog.indexOf("probe_c.c");
        assertTrue(indexA >= 0 && indexB > indexA && indexC > indexB,
                () -> "failure sections keep the cFiles slot order:\n" + buildLog);
        assertTrue(buildLog.contains("error: simulated broken TU"));
        assertEquals(0, launcher.countCommandsContaining("-shared"), "a failed TU round never starts the link");
        assertTrue(launcher.startedProcesses().stream().noneMatch(FakeProcess::destroyForciblyCalled),
                "the non-cancel failure path must not destroy already-finished sibling processes");
    }

    @Test
    void interruptDuringFailureCollectionPreemptsTheFailureReport(@TempDir Path tempDir) throws Exception {
        // probe_a fails immediately; its sibling blocks. Two TUs keep the scenario reachable
        // even at a parallelism of one (single-core CI): the failing TU frees the pool thread,
        // then the blocking sibling starts. The interrupt arrives while the runner waits for
        // the blocking sibling — it must cancel, not report the probe_a failure.
        var sources = writeTuSources(tempDir, "probe_a.c", "probe_b.c");
        var launcher = new FakeProcessLauncher()
                .failCommandsWithExitOne(cmd -> sourceToken(cmd).equals("probe_a.c"))
                .blockCommandsUntilDestroyed(cmd -> sourceToken(cmd).equals("probe_b.c"));
        var compiler = launcher.newCompiler();
        var resultRef = new AtomicReference<CCompileResult>();
        var interruptRestored = new AtomicBoolean();
        var compileThread = startCompileThread(compiler, tempDir, sources, resultRef, interruptRestored);

        launcher.awaitStartedProcessCount(2);
        compileThread.interrupt();
        compileThread.join(Duration.ofSeconds(30));
        assertFalse(compileThread.isAlive());

        var result = resultRef.get();
        assertFalse(result.success());
        assertEquals("Failed to run zig: interrupted", result.buildLog(),
                "an interrupt during failure collection preempts the failure report");
        assertTrue(result.artifacts().isEmpty());
        assertTrue(interruptRestored.get(), "compile must restore the interrupt status");
        assertTrue(launcher.startedProcesses().stream().allMatch(FakeProcess::destroyForciblyCalled),
                "cancel destroys the whole registry snapshot, including the already-dead failing TU");
        assertEquals(0, launcher.countCommandsContaining("-shared"));
    }

    @Test
    void tuStartFailureSurfacesThroughIoExceptionChannelWithoutCommandSections(@TempDir Path tempDir) throws IOException {
        var sources = writeTuSources(tempDir, "probe_a.c", "probe_b.c", "probe_c.c");
        var launcher = new FakeProcessLauncher()
                .failStartWith(cmd -> sourceToken(cmd).equals("probe_b.c"));
        var compiler = launcher.newCompiler();

        var result = compiler.compile(tempDir, List.of(), sources, "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty());
        assertTrue(result.buildLog().startsWith("Failed to run zig: "),
                () -> "a process that never started uses the IOException channel:\n" + result.buildLog());
        assertTrue(result.buildLog().contains("simulated process start failure"));
        assertFalse(result.buildLog().contains("Command: "),
                () -> "the IOException channel carries no Command sections (serial parity):\n" + result.buildLog());
    }

    private static List<Path> writeTuSources(Path dir, String... fileNames) throws IOException {
        var sources = new ArrayList<Path>();
        var index = 0;
        for (var fileName : fileNames) {
            var source = dir.resolve(fileName);
            Files.writeString(source, "int gdcc_probe_%d(void) { return %d; }\n".formatted(index, index));
            sources.add(source);
            index++;
        }
        return sources;
    }

    private static Thread startCompileThread(CCompiler compiler, Path projectDir, List<Path> sources, AtomicReference<CCompileResult> resultRef, AtomicBoolean interruptRestored) {
        return Thread.ofVirtual().name("gdcc-test-compile").start(() -> {
            try {
                var result = compiler.compile(projectDir, List.of(), sources, "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
                interruptRestored.set(Thread.currentThread().isInterrupted());
                resultRef.set(result);
            } catch (IOException exception) {
                throw new AssertionError("compile must surface failures through CCompileResult, not IOException", exception);
            }
        });
    }

    private static String sourceToken(List<String> cmd) {
        var last = cmd.getLast();
        return last.endsWith(".c") ? Path.of(last).getFileName().toString() : "<link>";
    }

    private static int countCommandSections(String buildLog) {
        var count = 0;
        for (var line : buildLog.split("\n")) {
            if (line.startsWith("Command: ")) {
                count++;
            }
        }
        return count;
    }
}
