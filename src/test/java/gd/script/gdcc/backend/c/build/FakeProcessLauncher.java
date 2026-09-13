package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/// Fake [CProcessLauncher] driving the real [ZigCcCompiler] round logic without real zig
/// processes. It records every `start()` call in order, simulates zig's `-o` file side effect
/// for succeeding commands (the compiler checks object/artifact existence), and can be
/// configured to fail specific commands by exit code, fail process starts with [IOException],
/// or block processes until destroyed (cancellation tests).
///
/// Public with a public [newCompiler] factory so tests outside this package (API-level wiring
/// tests) can inject a real `ZigCcCompiler` driven by this fake through the public
/// `CProjectBuilder(CCompiler)` constructor — the launcher seam itself stays package-private
/// in main code.
public final class FakeProcessLauncher implements CProcessLauncher {
    private final List<List<String>> recordedCommands = Collections.synchronizedList(new ArrayList<>());
    private final List<FakeProcess> startedProcesses = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch firstStartLatch = new CountDownLatch(1);
    private volatile boolean blockProcessesUntilDestroyed;
    private volatile @NotNull Predicate<List<String>> blockingPredicate = _ -> false;
    private volatile @NotNull Predicate<List<String>> orphanPipePredicate = _ -> false;
    private volatile @NotNull Predicate<List<String>> exitOnePredicate = _ -> false;
    private volatile @NotNull Predicate<List<String>> startFailurePredicate = _ -> false;
    private volatile @NotNull Function<List<String>, String> outputProvider = _ -> "";

    @Override
    public @NotNull Process start(@NotNull List<String> cmd, @NotNull Path workingDir, @NotNull Map<String, String> environmentOverrides) throws IOException {
        recordedCommands.add(List.copyOf(cmd));
        if (startFailurePredicate.test(cmd)) {
            throw new IOException("simulated process start failure");
        }
        var failing = exitOnePredicate.test(cmd);
        var orphanPipe = orphanPipePredicate.test(cmd);
        var blocking = blockProcessesUntilDestroyed || blockingPredicate.test(cmd) || orphanPipe;
        var process = new FakeProcess(failing ? 1 : 0, outputProvider.apply(cmd), blocking, orphanPipe);
        startedProcesses.add(process);
        firstStartLatch.countDown();
        if (!failing) {
            createDeclaredOutput(cmd);
        }
        return process;
    }

    /// zig's only observed side effect: the file named after `-o` must exist afterwards,
    /// otherwise the compiler round treats the step as failed.
    private static void createDeclaredOutput(@NotNull List<String> cmd) throws IOException {
        var outputIndex = cmd.indexOf("-o");
        if (outputIndex < 0 || outputIndex + 1 >= cmd.size()) {
            return;
        }
        var outputPath = Path.of(cmd.get(outputIndex + 1));
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.writeString(outputPath, "fake process output");
    }

    public @NotNull FakeProcessLauncher blockProcessesUntilDestroyed() {
        blockProcessesUntilDestroyed = true;
        return this;
    }

    /// Blocks only the matching commands until their process is destroyed; other commands
    /// complete immediately (e.g. one fast-failing TU next to blocking siblings).
    public @NotNull FakeProcessLauncher blockCommandsUntilDestroyed(@NotNull Predicate<List<String>> predicate) {
        blockingPredicate = predicate;
        return this;
    }

    /// Matching commands block until destroyed AND their stdout never reaches EOF afterwards —
    /// the destroyed `zig cc` wrapper left an orphaned grandchild holding the pipe open.
    public @NotNull FakeProcessLauncher orphanPipeCommands(@NotNull Predicate<List<String>> predicate) {
        orphanPipePredicate = predicate;
        return this;
    }

    public @NotNull FakeProcessLauncher failCommandsWithExitOne(@NotNull Predicate<List<String>> predicate) {
        exitOnePredicate = predicate;
        return this;
    }

    public @NotNull FakeProcessLauncher failStartWith(@NotNull Predicate<List<String>> predicate) {
        startFailurePredicate = predicate;
        return this;
    }

    public @NotNull FakeProcessLauncher outputProvider(@NotNull Function<List<String>, String> provider) {
        outputProvider = provider;
        return this;
    }

    /// Wraps this launcher in a real [ZigCcCompiler] through the package-private injection
    /// points, with zig discovery faked as well — tests using this compiler are pure Java and
    /// never require a real zig binary.
    public @NotNull CCompiler newCompiler() {
        return new ZigCcCompiler(this, () -> Path.of("zig"));
    }

    /// Every `start()` call in call order, including calls that then failed to "start".
    public @NotNull List<List<String>> recordedCommands() {
        synchronized (recordedCommands) {
            return List.copyOf(recordedCommands);
        }
    }

    public @NotNull List<FakeProcess> startedProcesses() {
        synchronized (startedProcesses) {
            return List.copyOf(startedProcesses);
        }
    }

    public long countCommandsContaining(@NotNull String token) {
        return recordedCommands().stream().filter(cmd -> cmd.contains(token)).count();
    }

    public boolean awaitFirstProcess() throws InterruptedException {
        return firstStartLatch.await(30, TimeUnit.SECONDS);
    }

    /// Polls until at least `count` processes were started (30s bound, then AssertionError).
    public void awaitStartedProcessCount(int count) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (startedProcesses.size() >= count) {
                return;
            }
            //noinspection BusyWait
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for " + count + " started processes, got " + startedProcesses.size());
    }
}
