package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.util.ProcessUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ZigUtil {
    private ZigUtil() {
    }

    private static Path ZigPath = null;
    /// Only a successfully probed version is cached; failures and interrupts re-probe next
    /// time, so a transient spawn problem never poisons the process-lifetime cache. Like
    /// [findZig], a single value is kept: production discovers exactly one zig per process.
    private static volatile @Nullable String ZigVersion = null;
    private static final Duration VERSION_READER_JOIN_TIMEOUT = Duration.ofSeconds(1);

    public static @Nullable Path findZig() {
        if (ZigPath == null) {
            ZigPath = findZigInternal();
        }
        return ZigPath;
    }

    /// Probes `zig version` and caches the trimmed version string, following the [findZig]
    /// non-throwing failure model. `null` means the version could not be determined (spawn
    /// failure, non-zero exit, empty output) — callers degrade the dependent feature (PCH
    /// caching) and must never read it as "zig is missing"; the negative result is not cached.
    /// [InterruptedException] is the cancellation channel: it propagates (the probe process is
    /// destroyed by [ProcessUtil.waitForInterruptibly] first) and is never converted to `null`.
    public static @Nullable String findZigVersion(@NotNull Path zig) throws InterruptedException {
        return findZigVersion(zig, CProcessLauncher.processBuilder(), new CProcessRegistry(),
                Path.of("").toAbsolutePath(), Map.of());
    }

    /// Same contract as [findZigVersion(Path)], but the probe runs through the caller's process
    /// manager: the launcher provides the spawn seam (real or fake) and the started probe is
    /// registered immediately, so a cancellation of the owning round destroys it like every
    /// other zig child. Package-private — only [ZigCcCompiler] wires a round's registry in.
    static @Nullable String findZigVersion(@NotNull Path zig, @NotNull CProcessLauncher launcher, @NotNull CProcessRegistry registry, @NotNull Path workingDir, @NotNull Map<String, String> environmentOverrides) throws InterruptedException {
        var cached = ZigVersion;
        if (cached != null) {
            return cached;
        }
        // The probe runs outside any monitor: waiting on a built-in lock is not interruptible,
        // and a cancelled round must converge promptly even when another thread's probe hangs.
        // Duplicate probes on a cold cache are cheap and harmless; the first success wins the
        // publish race below.
        var probed = probeZigVersion(zig, launcher, registry, workingDir, environmentOverrides);
        if (probed == null) {
            return null;
        }
        synchronized (ZigUtil.class) {
            if (ZigVersion == null) {
                ZigVersion = probed;
            }
            return ZigVersion;
        }
    }

    /// Test-only hook: drops the cached version so probe behavior can be exercised repeatedly.
    static void clearCachedZigVersionForTesting() {
        synchronized (ZigUtil.class) {
            ZigVersion = null;
        }
    }

    /// One uncached `zig version` invocation: exit 0 plus the first non-blank output line wins,
    /// anything else is a probe failure (`null`). Output is drained on a companion virtual
    /// thread even though the version line is tiny, keeping the same pipe discipline as the
    /// compile rounds; an interrupt destroys the child and converges the reader with a bounded
    /// join before propagating.
    private static @Nullable String probeZigVersion(@NotNull Path zig, @NotNull CProcessLauncher launcher, @NotNull CProcessRegistry registry, @NotNull Path workingDir, @NotNull Map<String, String> environmentOverrides) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled before the zig version probe started");
        }
        final Process process;
        try {
            process = launcher.start(List.of(zig.toString(), "version"), workingDir, environmentOverrides);
        } catch (IOException exception) {
            return null;
        }
        registry.register(process);
        var outputBytes = new ByteArrayOutputStream();
        var outputReader = Thread.ofVirtual()
                .name("gdcc-zig-version")
                .start(() -> {
                    try (var input = process.getInputStream()) {
                        input.transferTo(outputBytes);
                    } catch (IOException exception) {
                        // A truncated version stream surfaces as an unparseable (blank) output.
                    }
                });
        int exit;
        try {
            exit = ProcessUtil.waitForInterruptibly(process, outputReader);
        } catch (InterruptedException exception) {
            outputReader.interrupt();
            try {
                ProcessUtil.joinThreadAfterInterrupt(outputReader, VERSION_READER_JOIN_TIMEOUT);
            } catch (InterruptedException joinException) {
                // The original cancellation is already being propagated.
            }
            throw exception;
        }
        try {
            outputReader.join();
        } catch (InterruptedException exception) {
            // The cancel channel outranks the parse: restore and propagate like the wait path.
            Thread.currentThread().interrupt();
            throw new InterruptedException("interrupted while joining the zig version output reader");
        }
        if (exit != 0) {
            return null;
        }
        for (var line : outputBytes.toString(StandardCharsets.UTF_8).split("\\R")) {
            var trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    @SuppressWarnings("DuplicateExpressions")
    public static @Nullable Path findZigInternal() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        var exeName = os.contains("win") ? "zig.exe" : "zig";

        // where / which
        var byTool = findByWhichOrWhere(exeName, os);
        if (byTool != null) return byTool;

        var env = System.getenv();

        // env
        for (var key : List.of("ZIG", "ZIG_HOME", "ZIG_ROOT", "zig")) {
            var val = env.get(key);
            if (val == null || val.isBlank()) continue;
            var p = Paths.get(val);
            if (Files.isExecutable(p)) return p;
            if (Files.isDirectory(p)) {
                var candidate = p.resolve(exeName);
                if (Files.isExecutable(candidate)) return candidate;
            }
        }

        // path
        var pathEnv = env.get("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            pathEnv = env.get("Path");
        }
        if (pathEnv == null || pathEnv.isBlank()) {
            pathEnv = env.get("path");
        }
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (var part : pathEnv.split(File.pathSeparator)) {
                if (part.isBlank()) continue;
                var candidate = Paths.get(part).resolve(exeName);
                if (Files.isExecutable(candidate)) return candidate;
            }
        }

        // common locations
        var home = System.getProperty("user.home");
        var candidates = new ArrayList<Path>();

        if (os.contains("win")) {
            var prog = env.get("ProgramFiles");
            var progX86 = env.get("ProgramFiles(x86)");
            var localApp = env.get("LOCALAPPDATA");
            var programData = env.get("ProgramData");
            var userProfile = env.get("USERPROFILE");

            if (prog != null) candidates.add(Paths.get(prog, "Zig", exeName));
            if (progX86 != null) candidates.add(Paths.get(progX86, "Zig", exeName));
            if (localApp != null) candidates.add(Paths.get(localApp, "Programs", "Zig", exeName));
            if (programData != null) candidates.add(Paths.get(programData, "chocolatey", "bin", exeName));
            if (userProfile != null) candidates.add(Paths.get(userProfile, "scoop", "apps", "zig", "current", exeName));
            if (home != null) candidates.add(Paths.get(home, ".zigup", "bin", exeName));
        } else if (os.contains("mac")) {
            candidates.add(Paths.get("/usr/local/bin", exeName));
            candidates.add(Paths.get("/opt/homebrew/bin", exeName));
            candidates.add(Paths.get("/opt/homebrew/opt/zig/bin", exeName));
            if (home != null) candidates.add(Paths.get(home, ".zigup", "bin", exeName));
            if (home != null) candidates.add(Paths.get(home, ".local", "bin", exeName));
        } else { // linux / unix
            candidates.add(Paths.get("/usr/bin", exeName));
            candidates.add(Paths.get("/usr/local/bin", exeName));
            candidates.add(Paths.get("/snap/bin", exeName));
            if (home != null) candidates.add(Paths.get(home, ".zigup", "bin", exeName));
            if (home != null) candidates.add(Paths.get(home, ".local", "bin", exeName));
        }

        for (var c : candidates) {
            if (c != null && Files.isExecutable(c)) return c;
        }

        return null;
    }

    private static @Nullable Path findByWhichOrWhere(String exeName, String os) {
        var cmd = os.contains("win") ? List.of("where", exeName) : List.of("which", exeName);
        try {
            var pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            var p = pb.start();
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (!out.isBlank()) {
                var first = out.split("\\r?\\n")[0].trim();
                var path = Paths.get(first);
                if (Files.isExecutable(path)) return path;
            }
        } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return null;
    }
}
