package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.util.ProcessUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/// Zig-backed [CCompiler] that builds the shared library in two phases: every translation
/// unit is compiled to an object file first (in parallel, capped at
/// `min(cFiles.size, availableProcessors)` workers), then all objects of this round are linked
/// once. Splitting the monolithic `zig cc -shared <all .c>` invocation lets zig's content-hash
/// per-TU cache absorb rebuilds of user-independent TUs (above all `godot_binding.c`) when
/// only user code changes.
///
/// External contracts kept by this implementation:
/// - [CCompiler] signature unchanged; `artifacts()` first element is always the final shared
///   library, a Windows PDB (produced only by the link step) is listed second when present,
///   and intermediate objects never appear in `artifacts()`.
/// - `buildLog` merges per-process output in a fixed slot order (TU slots in `cFiles` input
///   order, then the link step); on failure only processes that were actually started get a
///   `Command:` section. A TU failure does not stop sibling TUs — they run to completion so
///   the failure log is complete — but the link step only starts after every TU succeeded.
/// - Cancellation: interrupting the runner thread closes the round's [CProcessRegistry],
///   forcibly destroys every started zig child (TU compiles and a started link), never starts
///   the link step, interrupts and awaits all TU workers (queued workers never launch), and
///   then surfaces through the existing `success=false` + `Failed to run zig: interrupted`
///   channel with the interrupt status restored. A round that notices the interrupt flag
///   between steps stops before launching the next sub-process; the residual start/register
///   race is closed by the registry destroying any process registered after the close.
public class ZigCcCompiler implements CCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZigCcCompiler.class);
    private static final String PROJECT_CACHE_DIR_NAME = "compiler-cache";
    private static final String SHARED_CACHE_DIR_NAME = "shared-compiler-cache";
    private static final String SHARED_CACHE_ENV = "GDCC_SHARED_C_COMPILER_CACHE";
    private static final String MSVC_ABI_SUFFIX = "-windows-msvc";
    private static final String GNU_ABI_SUFFIX = "-windows-gnu";
    private static final String OBJ_DIR_NAME = "obj";
    private static final Duration OUTPUT_READER_JOIN_TIMEOUT = Duration.ofSeconds(1);

    /// Zig targets whose lld backend is known to reject `-flto=thin`; RELEASE builds for these
    /// fall back to full LTO (the two-phase per-TU bitcode + single link shape is unchanged, so
    /// the per-TU cache benefit is kept). Populated from the cross-target ThinLTO smoke results;
    /// empty so far because every buildable target passed. web-wasm32/android must never be
    /// listed here: their failures are sysroot/runtime limitations (minicoro locks
    /// `MCO_USE_ASM`, android linking needs the NDK/Bionic), not ThinLTO problems.
    private static final Set<String> THIN_LTO_UNSUPPORTED_ZIG_TARGETS = Set.of();

    /// In-process serialization of native build rounds per project directory. Concurrent rounds
    /// on the same directory would overwrite each other's objects under `obj/` (even for
    /// different output names, objects share the `<opt>/<target>/<index>_<file>.o` shape) and
    /// the final artifact. External gdcc instances are out of scope: cross-process coordination
    /// on a shared directory is the caller's own responsibility. Entries are kept for the
    /// process lifetime — one small lock per project directory ever built.
    private static final ConcurrentHashMap<Path, ReentrantLock> PROJECT_BUILD_LOCKS = new ConcurrentHashMap<>();

    private final CProcessLauncher processLauncher;
    private final @NotNull Supplier<@Nullable Path> zigDiscovery;

    public ZigCcCompiler() {
        this(CProcessLauncher.processBuilder(), ZigUtil::findZig);
    }

    /// Package-private injection point for tests: a fake launcher drives the real round logic
    /// (registry, cancellation, log merging) without starting real zig processes.
    ZigCcCompiler(@NotNull CProcessLauncher processLauncher) {
        this(processLauncher, ZigUtil::findZig);
    }

    /// Full test seam: additionally fixes zig discovery, making fake-launcher tests pure Java
    /// (no real zig binary needs to be discoverable). Production uses `ZigUtil::findZig`.
    ZigCcCompiler(@NotNull CProcessLauncher processLauncher, @NotNull Supplier<@Nullable Path> zigDiscovery) {
        this.processLauncher = processLauncher;
        this.zigDiscovery = zigDiscovery;
    }

    @Override
    public CCompileResult compile(@NotNull Path projectDir, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles, @NotNull String outputBaseName, @NotNull COptimizationLevel optimizationLevel, @NotNull TargetPlatform targetPlatform) {
        var zig = zigDiscovery.get();
        if (zig == null) {
            return new CCompileResult(false, "Zig executable not found on PATH or known locations", List.of());
        }

        if (cFiles.isEmpty()) {
            return new CCompileResult(false, "No C files to compile", List.of());
        }

        var projectLock = requireProjectBuildLock(projectDir);
        try {
            // The lock covers the whole round (all TU compiles + link + artifact probing);
            // waiting is interruptible so a cancelled round never starts compiling late.
            projectLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CCompileResult(false, "Failed to run zig: interrupted", List.of());
        }
        var outName = targetPlatform.sharedLibraryFileName(outputBaseName);
        // Tracks every zig child started this round (all TU workers + the link step). Created
        // before the try so the interrupt catch can defensively destroy anything left.
        var registry = new CProcessRegistry();

        try {
            // Everything below runs under the lock: path/cache resolution touches the file
            // system too, so the unlock must guard the whole round, not just process execution.
            var outputPath = projectDir.resolve(outName).toAbsolutePath();
            var cachePath = resolveCompilerCacheRoot(projectDir);
            var targetResolution = resolveZigTarget(targetPlatform);
            var zigTarget = targetResolution.zigTarget();
            var ltoMode = resolveLtoMode(zigTarget, targetResolution.abiSubstituted(), optimizationLevel);

            // One slot per TU in cFiles order: command and object path are precomputed on the
            // runner thread, each worker then writes exactly its own slot, and the runner reads
            // the slots only after the worker futures completed (Future.get provides the
            // happens-before edge), keeping the build log order deterministic.
            var slots = new TuSlot[cFiles.size()];
            for (var i = 0; i < cFiles.size(); i++) {
                var cFile = cFiles.get(i);
                var objPath = resolveObjectPath(projectDir, optimizationLevel, zigTarget, i, cFile);
                Files.createDirectories(objPath.getParent());
                slots[i] = new TuSlot(buildTuCompileCommand(zig, zigTarget, ltoMode, optimizationLevel, includeDirs, objPath, cFile), objPath);
            }
            runTuPhase(slots, registry, projectDir, cachePath);

            var startedCommands = new ArrayList<List<String>>(slots.length + 1);
            var slotOutputs = new ArrayList<String>(slots.length + 1);
            var objPaths = new ArrayList<Path>(slots.length);
            Integer failedTuExitCode = null;
            for (var slot : slots) {
                if (slot.startFailure != null) {
                    // A TU whose process never started gets no Command section; surface through
                    // the same IOException channel the serial implementation used.
                    throw slot.startFailure;
                }
                objPaths.add(slot.objPath);
                if (slot.started) {
                    startedCommands.add(slot.command);
                    slotOutputs.add(slot.output);
                }
                if (failedTuExitCode == null && slot.started && (slot.exitCode != 0 || !Files.exists(slot.objPath))) {
                    failedTuExitCode = slot.exitCode;
                }
            }
            if (failedTuExitCode != null) {
                // All TUs ran to completion for a complete log; the link step never started.
                return new CCompileResult(false, mergeCommandSections(startedCommands, slotOutputs), List.of());
            }

            // A cancelled round must not launch the link; the check narrows the interrupt
            // window to the inherent check-then-start race closed by the registry.
            checkNotInterrupted();
            var linkCmd = buildLinkCommand(zig, zigTarget, ltoMode, optimizationLevel, outputPath, objPaths);
            var linkRun = runProcess(linkCmd, projectDir, cachePath, registry);
            startedCommands.add(linkCmd);
            slotOutputs.add(linkRun.output());
            var success = linkRun.exitCode() == 0 && Files.exists(outputPath);
            if (!success) {
                return new CCompileResult(false, mergeCommandSections(startedCommands, slotOutputs), List.of());
            }
            var artifacts = new ArrayList<Path>(2);
            artifacts.add(outputPath);
            if (targetPlatform.isWindows()) {
                var pdbPath = projectDir.resolve(outputBaseName + ".pdb").toAbsolutePath();
                if (Files.exists(pdbPath)) {
                    artifacts.add(pdbPath);
                }
            }
            return new CCompileResult(true, mergeSlotOutputs(slotOutputs), artifacts);
        } catch (InterruptedException e) {
            // Paths that throw without passing runTuPhase (link wait, post-TU check) have no
            // live TU workers left; close + destroy defensively so no registered child survives.
            destroyRegisteredProcesses(registry);
            Thread.currentThread().interrupt();
            return new CCompileResult(false, "Failed to run zig: interrupted", List.of());
        } catch (IOException e) {
            return new CCompileResult(false, "Failed to run zig: " + e.getMessage(), List.of());
        } finally {
            projectLock.unlock();
        }
    }

    /// Runs all TU compile workers in parallel and blocks until every worker finished. An
    /// interrupt while waiting cancels the round (registry closed, started children destroyed,
    /// workers interrupted and awaited) and propagates as [InterruptedException]; a worker
    /// anomaly that escaped its slot surfaces as [IOException]. Results are collected in
    /// completion order (not submission order) so an unchecked escape is seen — and cancels the
    /// siblings — even while an earlier TU is still blocked.
    private void runTuPhase(@NotNull TuSlot[] slots, @NotNull CProcessRegistry registry, @NotNull Path projectDir, @NotNull Path cachePath) throws InterruptedException, IOException {
        var parallelism = Math.clamp(Runtime.getRuntime().availableProcessors(), 1, slots.length);
        var executor = Executors.newFixedThreadPool(parallelism, Thread.ofVirtual().name("gdcc-zig-tu-", 0).factory());
        try (executor) {
            var completions = new ExecutorCompletionService<Void>(executor);
            for (var i = 0; i < slots.length; i++) {
                var index = i;
                completions.submit(() -> compileTu(slots[index], registry, projectDir, cachePath), null);
            }
            try {
                for (var i = 0; i < slots.length; i++) {
                    try {
                        completions.take().get();
                    } catch (ExecutionException exception) {
                        // Workers record expected failures in their slot; reaching here means an
                        // unchecked escape (a bug) — cancel the siblings instead of waiting for
                        // them, then fail through the IOException channel.
                        throw new IOException("TU worker failed unexpectedly: " + exception.getCause(), exception.getCause());
                    }
                }
            } catch (InterruptedException | IOException exception) {
                cancelRound(registry, executor);
                throw exception;
            }
        }
    }

    /// Worker body of one TU compile. Cancellation interrupts worker threads: a worker parked
    /// in `waitFor` then takes the standard [ProcessUtil] destroy path, while one whose child
    /// was already destroyed cross-thread finishes promptly. Expected outcomes are recorded in
    /// the slot; [InterruptedException] marks the slot started (the child was started and has
    /// already been destroyed by `ProcessUtil` at that point).
    private void compileTu(@NotNull TuSlot slot, @NotNull CProcessRegistry registry, @NotNull Path projectDir, @NotNull Path cachePath) {
        // A queued worker that gets a pool thread after cancellation must not launch late.
        if (registry.isClosed() || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            var run = runProcess(slot.command, projectDir, cachePath, registry);
            slot.started = true;
            slot.output = run.output();
            slot.exitCode = run.exitCode();
        } catch (IOException exception) {
            slot.startFailure = exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            slot.started = true;
        }
    }

    /// Cancellation protocol: close the registry (racing `register` calls now destroy on
    /// arrival), forcibly destroy every child started so far — never starting the link — and
    /// interrupt the workers (`shutdownNow` also drops queued tasks, so no TU can launch late).
    /// Then wait for actual thread termination, deliberately ignoring further interrupts, so
    /// `compile()` never returns before every zig child is gone (an embedding `API.close()`
    /// joins the runner and would otherwise leak orphan processes); the caller restores the
    /// interrupt status. A worker blocked inside `launcher.start()` is the inherent
    /// uninterruptible window — `ProcessBuilder.start()` is not interrupt-responsive, exactly
    /// as in the previous serial implementation; its late process is destroyed by the closed
    /// `register` call, so no orphan can escape even there.
    private static void cancelRound(@NotNull CProcessRegistry registry, @NotNull ExecutorService executor) {
        destroyRegisteredProcesses(registry);
        executor.shutdownNow();
        var terminated = false;
        while (!terminated) {
            try {
                terminated = executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                // Keep waiting; the interrupt status is restored by the caller.
            }
        }
    }

    private static void destroyRegisteredProcesses(@NotNull CProcessRegistry registry) {
        for (var process : registry.cancelAndSnapshot()) {
            process.destroyForcibly();
        }
    }

    /// Resolves the lock serializing native build rounds of one project directory within this
    /// process. Keys are normalized absolute paths, so textual variants of the same directory
    /// share one lock.
    static @NotNull ReentrantLock requireProjectBuildLock(@NotNull Path projectDir) {
        return PROJECT_BUILD_LOCKS.computeIfAbsent(projectDir.toAbsolutePath().normalize(), key -> new ReentrantLock());
    }

    /// LTO token decision with a fixed priority: an ABI-substituted build (msvc→gnu on a
    /// non-Windows host) never gets any `-flto*` flag — zig's LTO link for windows-gnu fails
    /// to pull in libmingwex/compiler-rt symbols — and never enters the ThinLTO fallback
    /// check; DEBUG never uses LTO; RELEASE uses ThinLTO unless the target is known to lack
    /// ThinLTO support, in which case it falls back to full LTO.
    static @NotNull CLtoMode resolveLtoMode(@NotNull String zigTarget, boolean abiSubstituted, @NotNull COptimizationLevel optimizationLevel) {
        return resolveLtoMode(zigTarget, abiSubstituted, optimizationLevel, THIN_LTO_UNSUPPORTED_ZIG_TARGETS);
    }

    static @NotNull CLtoMode resolveLtoMode(@NotNull String zigTarget, boolean abiSubstituted, @NotNull COptimizationLevel optimizationLevel, @NotNull Set<String> thinLtoUnsupportedZigTargets) {
        if (abiSubstituted) {
            return CLtoMode.NONE;
        }
        return switch (optimizationLevel) {
            case DEBUG -> CLtoMode.NONE;
            case RELEASE -> thinLtoUnsupportedZigTargets.contains(zigTarget) ? CLtoMode.FULL : CLtoMode.THIN;
        };
    }

    /// Object files live under `<projectDir>/obj/<debug|release>/<zigTarget>/<index>_<fileName>.o`.
    /// The `cFiles` index avoids collisions between same-named sources from different
    /// directories; embedding opt level and resolved target prevents stale objects from a
    /// different configuration being mistaken for current output. Objects are intermediates:
    /// they are always recompiled (caching is delegated to zig's content-hash per-TU cache,
    /// whose key does not include `-o`), never globbed into the link, and never published.
    static @NotNull Path resolveObjectPath(@NotNull Path projectDir, @NotNull COptimizationLevel optimizationLevel, @NotNull String zigTarget, int tuIndex, @NotNull Path cFile) {
        var optDir = optimizationLevel.name().toLowerCase(Locale.ROOT);
        var objFileName = tuIndex + "_" + cFile.getFileName() + ".o";
        return projectDir.toAbsolutePath().normalize()
                .resolve(OBJ_DIR_NAME).resolve(optDir).resolve(zigTarget).resolve(objFileName);
    }

    /// Per-TU compile command:
    /// `zig cc -target <T> -std=c23 -fPIC -c [lto] <-O0|-O2> -Wno-... -I... -o <obj> <cFile>`.
    static @NotNull List<String> buildTuCompileCommand(@NotNull Path zig, @NotNull String zigTarget, @NotNull CLtoMode ltoMode, @NotNull COptimizationLevel optimizationLevel, @NotNull List<Path> includeDirs, @NotNull Path objPath, @NotNull Path cFile) {
        var cmd = new ArrayList<String>();
        cmd.add(zig.toString());
        cmd.add("cc");
        cmd.add("-target");
        cmd.add(zigTarget);
        cmd.add("-std=c23");
        cmd.add("-fPIC");
        cmd.add("-c");
        var ltoFlag = ltoMode.cliFlag();
        if (ltoFlag != null) {
            cmd.add(ltoFlag);
        }
        cmd.add(optimizationFlag(optimizationLevel));
        cmd.add("-Wno-macro-redefined");
        cmd.add("-Wno-pointer-sign");
        for (var inc : includeDirs) {
            cmd.add("-I" + inc.toAbsolutePath());
        }
        cmd.add("-o");
        cmd.add(objPath.toString());
        cmd.add(cFile.toAbsolutePath().toString());
        return cmd;
    }

    /// Link command: `zig cc -target <T> -shared [lto [-O2]] -o <out> <objs...>`.
    /// `-O2` is passed only when LTO is active for a RELEASE build, because that is where the
    /// link-time code generation happens; without LTO the objects are already final machine
    /// code. Inputs are exactly this round's object paths in `cFiles` order — never a glob of
    /// the obj directory — so stale objects can never leak into the artifact.
    static @NotNull List<String> buildLinkCommand(@NotNull Path zig, @NotNull String zigTarget, @NotNull CLtoMode ltoMode, @NotNull COptimizationLevel optimizationLevel, @NotNull Path outputPath, @NotNull List<Path> objPaths) {
        var cmd = new ArrayList<String>();
        cmd.add(zig.toString());
        cmd.add("cc");
        cmd.add("-target");
        cmd.add(zigTarget);
        cmd.add("-shared");
        var ltoFlag = ltoMode.cliFlag();
        if (ltoFlag != null) {
            cmd.add(ltoFlag);
            if (optimizationLevel == COptimizationLevel.RELEASE) {
                cmd.add(optimizationFlag(optimizationLevel));
            }
        }
        cmd.add("-o");
        cmd.add(outputPath.toString());
        for (var objPath : objPaths) {
            cmd.add(objPath.toString());
        }
        return cmd;
    }

    private static @NotNull String optimizationFlag(@NotNull COptimizationLevel optimizationLevel) {
        return switch (optimizationLevel) {
            case DEBUG -> "-O0";
            case RELEASE -> "-O2";
        };
    }

    /// Success path: raw outputs concatenated in slot order, empty outputs contribute nothing
    /// (no `Command:` lines, matching the previous single-command behavior).
    static @NotNull String mergeSlotOutputs(@NotNull List<String> slotOutputs) {
        var log = new StringBuilder();
        for (var output : slotOutputs) {
            appendNormalized(log, output);
        }
        return log.toString();
    }

    /// Failure path: one `Command: <argv>` + raw output section per started process, in slot
    /// order. Sections for processes that never started simply do not exist.
    static @NotNull String mergeCommandSections(@NotNull List<List<String>> startedCommands, @NotNull List<String> slotOutputs) {
        var log = new StringBuilder();
        for (var i = 0; i < startedCommands.size(); i++) {
            log.append("Command: ").append(String.join(" ", startedCommands.get(i))).append('\n');
            appendNormalized(log, slotOutputs.get(i));
        }
        return log.toString();
    }

    /// Appends one output block, guaranteeing a trailing newline so consecutive blocks never
    /// glue together; empty blocks are skipped entirely.
    private static void appendNormalized(@NotNull StringBuilder log, @NotNull String output) {
        if (output.isEmpty()) {
            return;
        }
        log.append(output);
        if (!output.endsWith("\n")) {
            log.append('\n');
        }
    }

    /// Throws once the current thread carries the interrupt flag, so a cancelled round stops
    /// before launching the next sub-process instead of only noticing inside `waitFor`.
    private static void checkNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
    }

    /// Runs one zig sub-process with the shared environment contract: the launcher applies the
    /// working directory and merged-stream setup, and both zig cache roots are passed as
    /// environment overrides. The started child is registered immediately — a cancel racing
    /// between `start()` and `register` finds it either in the registry snapshot or destroys it
    /// inside the closed `register` call. Output is drained on a companion virtual thread so a
    /// verbose compiler cannot fill the pipe and block shutdown; an interrupt of the calling
    /// worker destroys the child via [ProcessUtil.waitForInterruptibly] and propagates as
    /// [InterruptedException]. An [IOException] escapes only when the process could not be
    /// started at all — once started, a drained-output read failure is degraded to an inline
    /// diagnostic so the round keeps the process's exit code and its Command section.
    private @NotNull ProcessRun runProcess(@NotNull List<String> cmd, @NotNull Path projectDir, @NotNull Path cachePath, @NotNull CProcessRegistry registry) throws IOException, InterruptedException {
        var environmentOverrides = Map.of(
                "ZIG_CACHE_DIR", cachePath.resolve("local").toString(),
                "ZIG_GLOBAL_CACHE_DIR", cachePath.resolve("global").toString()
        );
        var p = processLauncher.start(cmd, projectDir, environmentOverrides);
        registry.register(p);
        var outputBytes = new ByteArrayOutputStream();
        var outputFailure = new AtomicReference<IOException>();
        var outputReader = Thread.ofVirtual()
                .name("gdcc-zig-output")
                .start(() -> {
                    try (var input = p.getInputStream()) {
                        input.transferTo(outputBytes);
                    } catch (IOException exception) {
                        outputFailure.set(exception);
                    }
                });
        var interrupted = false;
        int exit;
        try {
            exit = ProcessUtil.waitForInterruptibly(p, outputReader);
        } catch (InterruptedException exception) {
            interrupted = true;
            throw exception;
        } finally {
            if (interrupted || registry.isClosed()) {
                // Cancel path: the child was destroyed (possibly cross-thread, e.g. by
                // `zig cc` while an orphaned grandchild still holds the pipe open), so the
                // drain may never see EOF — interrupt it and bound the join instead of
                // parking the worker forever.
                outputReader.interrupt();
                ProcessUtil.joinThreadAfterInterrupt(outputReader, OUTPUT_READER_JOIN_TIMEOUT);
            } else {
                outputReader.join();
            }
        }
        var readFailure = outputFailure.get();
        var output = outputBytes.toString(StandardCharsets.UTF_8);
        if (readFailure != null) {
            // The process itself already finished; a drained-output read failure must not mask
            // its exit code or drop its Command section from the failure log. Degrade to an
            // inline diagnostic instead of failing the whole round from here.
            if (!output.isEmpty() && !output.endsWith("\n")) {
                output += "\n";
            }
            output += "[gdcc] incomplete compiler output: " + readFailure + "\n";
        }
        return new ProcessRun(exit, output);
    }

    private record ProcessRun(int exitCode, @NotNull String output) {
    }

    /// Mutable per-TU round state. `command`/`objPath` are assigned once on the runner thread
    /// before workers start; every other field is written by exactly one worker and read by the
    /// runner only after that worker's `Future` completed (`Future.get` establishes the
    /// happens-before edge), so the fields need no synchronization of their own.
    private static final class TuSlot {
        private final @NotNull List<String> command;
        private final @NotNull Path objPath;
        private boolean started;
        private @NotNull String output = "";
        private int exitCode = -1;
        private @Nullable IOException startFailure;

        private TuSlot(@NotNull List<String> command, @NotNull Path objPath) {
            this.command = command;
            this.objPath = objPath;
        }
    }

    /// Resolves the zig target triple actually passed to `zig cc`. The declared
    /// `TargetPlatform.zigTarget` is passed through untouched except in one case: on a
    /// non-Windows host, `-windows-msvc` cannot work because zig only provides libc for the
    /// MinGW ABI (`-windows-gnu`), never for MSVC (that requires an installed Windows SDK +
    /// MSVC libraries). Cross-compiling Windows builds from Linux/macOS therefore substitutes
    /// the GNU ABI and warns — the produced DLL stays a valid self-contained GDExtension (the
    /// GDExtension boundary is a plain C ABI), matching the zig launcher's own ABI choice.
    static @NotNull ZigTargetResolution resolveZigTarget(@NotNull TargetPlatform targetPlatform) {
        return resolveZigTarget(targetPlatform, isWindowsHost());
    }

    static @NotNull ZigTargetResolution resolveZigTarget(@NotNull TargetPlatform targetPlatform, boolean windowsHost) {
        var zigTarget = Objects.requireNonNull(targetPlatform, "targetPlatform must not be null").zigTarget;
        if (!windowsHost && zigTarget.endsWith(MSVC_ABI_SUFFIX)) {
            var substituted = zigTarget.substring(0, zigTarget.length() - MSVC_ABI_SUFFIX.length())
                    + GNU_ABI_SUFFIX;
            LOGGER.warn("zig cannot provide libc for {} on a non-Windows host; substituting {} "
                    + "(MinGW ABI, matching the zig launcher, LTO disabled for this build). "
                    + "The GDExtension DLL remains loadable.", zigTarget, substituted);
            return new ZigTargetResolution(substituted, true);
        }
        return new ZigTargetResolution(zigTarget, false);
    }

    /// `abiSubstituted` marks the non-Windows-host MinGW substitution, which also disables LTO.
    record ZigTargetResolution(@NotNull String zigTarget, boolean abiSubstituted) {
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static @NotNull Path resolveCompilerCacheRoot(@NotNull Path projectDir) {
        return resolveCompilerCacheRoot(projectDir, System.getenv());
    }

    static @NotNull Path resolveCompilerCacheRoot(@NotNull Path projectDir, @NotNull Map<String, String> environment) {
        var normalizedProjectDir = projectDir.toAbsolutePath().normalize();
        var envCacheValue = environment.get(SHARED_CACHE_ENV);
        if (envCacheValue != null && !envCacheValue.isBlank()) {
            try {
                var envCacheDir = Path.of(envCacheValue).toAbsolutePath().normalize();
                Files.createDirectories(envCacheDir);
                if (Files.isDirectory(envCacheDir)) {
                    return envCacheDir;
                }
            } catch (IOException | InvalidPathException exception) {
                // Fall back to the project-location cache rule below.
            }
        }

        var projectParent = normalizedProjectDir.getParent();
        if (projectParent == null) {
            return normalizedProjectDir.resolve(PROJECT_CACHE_DIR_NAME);
        }

        var sharedCacheDir = projectParent.resolve(SHARED_CACHE_DIR_NAME);
        if (Files.exists(sharedCacheDir) && !Files.isDirectory(sharedCacheDir)) {
            return normalizedProjectDir.resolve(PROJECT_CACHE_DIR_NAME);
        }
        if (Files.isDirectory(sharedCacheDir)) {
            return sharedCacheDir.toAbsolutePath().normalize();
        }

        return normalizedProjectDir.resolve(PROJECT_CACHE_DIR_NAME);
    }
}
