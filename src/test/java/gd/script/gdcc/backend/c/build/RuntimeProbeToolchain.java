package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.util.ZigTestUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/// Dual-compiler harness for the pure-C runtime probes: one fixture source is compiled, linked
/// into a host executable and run by either the zig baseline backend (`zig cc -std=c23`) or the
/// vendored tinycc CLI (`tcc -std=c11`). Sharing one harness keeps include paths, defines and the
/// runtime TU list identical across compilers, so a behavioral difference between the two legs is
/// attributable to the compiler alone.
///
/// The tinycc leg links `-lm -lpthread` on Linux (tcc resolves host libc/CRT through its
/// configured multiarch paths — the host dev-package dependency is a documented tcc backend
/// difference) and nothing extra on Windows, where the runtime archive's startup objects and the
/// bundled import definitions cover CRT/Win32. Executables get the `.exe` suffix on Windows.
final class RuntimeProbeToolchain {
    static final Path GODOT_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/godot").toAbsolutePath().normalize();
    static final Path GDCC_INCLUDE_DIR = Path.of("src/main/c/codegen/include_451/gdcc").toAbsolutePath().normalize();

    private static final Duration COMPILE_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);

    private final boolean tcc;
    private final @NotNull Path binary;
    /// `-B` runtime root for the tcc leg; null keeps the compiler's built-in search paths.
    private final @Nullable Path runtimeRoot;

    private RuntimeProbeToolchain(boolean tcc, @NotNull Path binary, @Nullable Path runtimeRoot) {
        this.tcc = tcc;
        this.binary = binary;
        this.runtimeRoot = runtimeRoot;
    }

    /// The zig baseline leg, or null when no zig is discoverable on this host.
    static @Nullable RuntimeProbeToolchain zig() {
        var zig = ZigUtil.findZig();
        return zig == null ? null : new RuntimeProbeToolchain(false, zig, null);
    }

    /// The tinycc leg, or null when no patched CLI work tree is available (see
    /// [TinyCcCliTestSupport#findTinyCcCli]).
    static @Nullable RuntimeProbeToolchain tcc() {
        var cli = TinyCcCliTestSupport.findTinyCcCli();
        if (cli == null) {
            return null;
        }
        return new RuntimeProbeToolchain(true, cli, TinyCcCliTestSupport.resolveRuntimeRoot(cli));
    }

    boolean isTcc() {
        return tcc;
    }

    /// Distinguishes probe artifacts of the two legs inside one shared work directory.
    @NotNull String label() {
        return tcc ? "tcc" : "zig";
    }

    /// Compiles `source` plus the given runtime TUs into one executable, runs it, and returns the
    /// run result. A compile/link failure fails the test immediately with the full command and
    /// compiler output; the caller asserts on the run's exit code and output.
    @NotNull ProbeRun compileLinkAndRun(
            @NotNull Path workDir,
            @NotNull String probeName,
            @NotNull String source,
            @NotNull List<Path> runtimeSources,
            @NotNull List<String> extraDefines
    ) throws IOException, InterruptedException {
        var probeSource = workDir.resolve(probeName + "_" + label() + ".c");
        Files.writeString(probeSource, source, StandardCharsets.UTF_8);
        var executable = workDir.resolve(probeName + "_" + label() + (isWindowsHost() ? ".exe" : ""));

        var command = new ArrayList<String>();
        command.add(binary.toString());
        if (!tcc) {
            command.add("cc");
            command.add("-std=c23");
        } else {
            if (runtimeRoot != null) {
                command.add("-B");
                command.add(runtimeRoot.toString());
            }
            command.add("-std=c11");
        }
        command.add("-D_DEFAULT_SOURCE");
        command.add("-I" + GODOT_INCLUDE_DIR);
        command.add("-I" + GDCC_INCLUDE_DIR);
        for (var define : extraDefines) {
            command.add("-D" + define);
        }
        command.add(probeSource.toString());
        for (var runtimeSource : runtimeSources) {
            command.add(runtimeSource.toString());
        }
        if (tcc && !isWindowsHost()) {
            command.add("-lm");
            command.add("-lpthread");
        }
        command.add("-o");
        command.add(executable.toString());
        if (!tcc) {
            ZigTestUtil.addMacosNativeTarget(command);
        }

        var build = runProcess(command, COMPILE_TIMEOUT, null);
        assertEquals(0, build.exitCode(), build::diagnostic);
        return runProcess(List.of(executable.toString()), RUN_TIMEOUT, null);
    }

    /// Runs a process to completion with a hard deadline. Output goes to a temporary FILE (merged
    /// stdout/stderr) instead of a pipe + reader thread: after the process is reaped the file is
    /// complete, so there is no reader to strand and no cross-thread `close()` cancellation
    /// question (Linux does not guarantee that closing a pipe fd wakes a blocked native read; a
    /// grandchild holding the pipe could then outlive the whole cleanup path). A timeout or
    /// interruption destroys the child forcibly and converges it before this method returns,
    /// fails or propagates — no probe/compiler process can outlive its test (on Windows a survivor
    /// would also keep the temporary directory's DLLs locked). Interrupts are deferred through
    /// cleanup, restored, and the original [InterruptedException] then propagates.
    static @NotNull ProbeRun runProcess(@NotNull List<String> command, @NotNull Duration timeout, @Nullable Path workingDirectory)
            throws IOException, InterruptedException {
        var outputFile = Files.createTempFile("gdcc-probe-", ".log");
        try {
            var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(outputFile.toFile());
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            var process = builder.start();
            final boolean exited;
            try {
                exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // Cancel path: kill + reap the child, then restore the status and let the original
                // exception propagate.
                process.destroyForcibly();
                awaitProcessQuietly(process, CLEANUP_TIMEOUT);
                Thread.currentThread().interrupt();
                throw e;
            }
            if (!exited) {
                process.destroyForcibly();
                // The kill is only a request; the reap must be confirmed before the timeout
                // failure can be trusted not to leak anything.
                if (!awaitProcessQuietly(process, CLEANUP_TIMEOUT)) {
                    fail("process did not die within " + CLEANUP_TIMEOUT + " after destroyForcibly:\n" + String.join(" ", command));
                }
                fail("process did not exit within " + timeout + ":\n" + String.join(" ", command) + "\n" + Files.readString(outputFile, StandardCharsets.UTF_8));
            }
            return new ProbeRun(command, process.exitValue(), Files.readString(outputFile, StandardCharsets.UTF_8));
        } finally {
            // Best-effort log cleanup that can never replace the process outcome: on Windows a
            // surviving descendant holding the inherited file handle makes deletion fail, and an
            // IOException here must not mask the round's result. The file then stays for the OS
            // temp sweeper (deleteOnExit is only a JVM-lifetime backstop, not a guarantee).
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException cleanupFailure) {
                outputFile.toFile().deleteOnExit();
            }
        }
    }

    /// Waits for the child to die, swallowing (and re-asserting afterwards) interrupts so a
    /// cleanup round cannot be abandoned mid-reap.
    private static boolean awaitProcessQuietly(@NotNull Process process, @NotNull Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        var interrupted = false;
        try {
            while (true) {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    return process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /// One process invocation: the command line, its exit code and the merged stdout/stderr text.
    record ProbeRun(@NotNull List<String> command, int exitCode, @NotNull String output) {
        String diagnostic() {
            return String.join(" ", command) + "\n" + output;
        }

        /// Output lines with CR/LF normalized, so assertions compare equal on Windows hosts where
        /// the C runtime translates `\n` to `\r\n` on text streams.
        @NotNull List<String> outputLines() {
            return output.replace("\r\n", "\n").lines().toList();
        }
    }
}
