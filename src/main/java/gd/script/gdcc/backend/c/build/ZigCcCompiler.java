package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.util.ProcessUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
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
/// - `buildLog` merges per-process output in a fixed order: the PCH fallback line (only when
///   the round degraded) first, then PCH-phase sections (build/probe), TU slots in `cFiles`
///   input order, then the link step. On failure only processes that were actually started get
///   a `Command:` section. A TU failure does not stop sibling TUs — they run to completion so
///   the failure log is complete — but the link step only starts after every TU succeeded.
/// - Cancellation: interrupting the runner thread closes the round's [CProcessRegistry],
///   forcibly destroys every started zig child (the version probe, PCH build/probe, TU
///   compiles, and a started link), never starts the link step, interrupts and awaits all TU
///   workers (queued workers never launch), and
///   then surfaces through the existing `success=false` + `Failed to run zig: interrupted`
///   channel with the interrupt status restored. A round that notices the interrupt flag
///   between steps stops before launching the next sub-process; the residual start/register
///   race is closed by the registry destroying any process registered after the close.
/// - PCH: a `godot_binding.h` precompiled header is cached under `<cacheRoot>/pch/<key>/`
///   (key = zig version + target + full language flags + ordered include-tree hashes) and
///   force-included only into the whitelisted TUs that actually include `godot_binding.h`
///   (`entry.c`, `godot_binding.c`, `gdcc_coroutine.c`; `minicoro.c` is excluded so the ABI
///   headers never leak into the isolated assembly-backend TU). Every PCH problem — version
///   probe failure, build/probe/rename failure, a poisoned installed entry that still fails
///   after one self-heal rebuild, or zig rejecting `-include-pch` mid-round — degrades to a
///   no-PCH round with one fixed fallback line at the top of `buildLog`; it never fails the
///   build. Interrupts during PCH work propagate through the cancellation channel instead.
///   PCH and no-PCH objects are never mixed in one link.
public class ZigCcCompiler implements CCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZigCcCompiler.class);
    private static final String PROJECT_CACHE_DIR_NAME = "compiler-cache";
    private static final String SHARED_CACHE_DIR_NAME = "shared-compiler-cache";
    private static final String SHARED_CACHE_ENV = "GDCC_SHARED_C_COMPILER_CACHE";
    private static final String MSVC_ABI_SUFFIX = "-windows-msvc";
    private static final String GNU_ABI_SUFFIX = "-windows-gnu";
    private static final String OBJ_DIR_NAME = "obj";
    private static final Duration OUTPUT_READER_JOIN_TIMEOUT = Duration.ofSeconds(1);

    /// PCH cache layout: `<cacheRoot>/pch/<key>/` holds the final-path prefix header, the
    /// precompiled header and the `.ready` marker published last; consumers only accept
    /// entries with all three present.
    private static final String PCH_DIR_NAME = "pch";
    private static final String PCH_PREFIX_HEADER_NAME = "gdcc_godot_prefix.h";
    private static final String PCH_FILE_NAME = "gdcc_godot_prefix.pch";
    private static final String PCH_READY_MARKER_NAME = ".ready";
    /// The prefix header may only pull in `godot_binding.h` — never gdcc tree headers —
    /// because `entry.h` requires `class_library` to be declared by its includer before
    /// `gdcc_helper.h` is seen (a PCH cannot satisfy that per-TU contract).
    static final String PCH_PREFIX_HEADER_CONTENT = "#include <godot_binding.h>\n";
    /// Fixed prefix-header mtime keeping clang's pch mtime validation deterministic across
    /// rebuilds (see [installPrefixHeader]).
    private static final Instant PCH_PREFIX_HEADER_MTIME = Instant.EPOCH;
    private static final String PCH_PROBE_SOURCE_PREFIX = "gdcc_pch_probe_";
    /// `%s` carries the round-unique suffix (a valid C identifier fragment), making every
    /// probe source content-distinct so zig's cache cannot replay a stale probe verdict.
    private static final String PCH_PROBE_SOURCE_CONTENT = "int gdcc_pch_probe_%s(void) { return 0; }\n";
    /// TUs force-including the PCH, by simple file name, anchored to the fixed
    /// `CProjectBuilder` native inputs. A TU that does not include `godot_binding.h`
    /// (`minicoro.c`) must never be added here.
    private static final Set<String> PCH_WHITELIST_TU_NAMES = Set.of("entry.c", "godot_binding.c", "gdcc_coroutine.c", "gdcc_hrx.c");
    private static final String PCH_FALLBACK_PREFIX = "[gdcc] PCH unavailable this round: ";
    private static final String PCH_REJECTED_RETRY_NOTE =
            "[gdcc] zig rejected the PCH during TU compilation; the whole round was retried without -include-pch\n";
    /// clang PCH-rejection diagnostics (lower-cased contains match). The pre-launch probe makes
    /// reaching these during real TU compiles near-impossible; the markers exist so the rare
    /// escape triggers one no-PCH retry instead of a spurious build failure. Covers both
    /// "precompiled file" and "precompiled header" phrasings of clang's validation errors.
    private static final List<String> PCH_REJECTION_MARKERS = List.of("precompiled file", "precompiled header", "-include-pch", "pch file", "ast file");
    /// SHA-256 truncated to 16 bytes (32 hex chars) for cache directory names.
    private static final int PCH_KEY_HASH_BYTES = 16;

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
    private final @NotNull ZigVersionProbe zigVersionProbe;
    private final @NotNull Function<Path, Path> cacheRootResolver;

    public ZigCcCompiler() {
        this(CProcessLauncher.processBuilder(), ZigUtil::findZig, null, ZigCcCompiler::resolveCompilerCacheRoot);
    }

    /// Package-private injection point for tests: a fake launcher drives the real round logic
    /// (registry, cancellation, log merging) without starting real zig processes. This legacy
    /// seam keeps PCH off (the version probe resolves to null) so pre-PCH test rounds keep
    /// their exact command sequences; PCH tests use the full seam below.
    ZigCcCompiler(@NotNull CProcessLauncher processLauncher) {
        this(processLauncher, ZigUtil::findZig, PCH_DISABLED_PROBE, ZigCcCompiler::resolveCompilerCacheRoot);
    }

    /// Full test seam: additionally fixes zig discovery, making fake-launcher tests pure Java
    /// (no real zig binary needs to be discoverable). Production uses `ZigUtil::findZig`.
    /// Same legacy PCH-off default as the single-argument seam.
    ZigCcCompiler(@NotNull CProcessLauncher processLauncher, @NotNull Supplier<@Nullable Path> zigDiscovery) {
        this(processLauncher, zigDiscovery, PCH_DISABLED_PROBE, ZigCcCompiler::resolveCompilerCacheRoot);
    }

    /// PCH test seam: fixes the zig version probe (a fixed version exercises the PCH path
    /// without spawning a probe process, keeping recorded command sequences deterministic;
    /// returning null disables PCH for the round) and the cache root resolver (tests pin an
    /// isolated temporary cache root because `@TempDir` cannot scrub a parent-process
    /// `GDCC_SHARED_C_COMPILER_CACHE`). A `null` probe selects the production implementation.
    ZigCcCompiler(@NotNull CProcessLauncher processLauncher, @NotNull Supplier<@Nullable Path> zigDiscovery, @Nullable ZigVersionProbe zigVersionProbe, @NotNull Function<Path, Path> cacheRootResolver) {
        this.processLauncher = processLauncher;
        this.zigDiscovery = zigDiscovery;
        this.zigVersionProbe = zigVersionProbe != null ? zigVersionProbe : this::probeZigVersion;
        this.cacheRootResolver = cacheRootResolver;
    }

    /// Legacy test seams never probe a version: PCH stays disabled for rounds that predate the
    /// PCH feature, so their process-command expectations are unchanged.
    private static final ZigVersionProbe PCH_DISABLED_PROBE = (zig, registry, projectDir, cachePath) -> null;

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
            var cachePath = cacheRootResolver.apply(projectDir);
            var targetResolution = resolveZigTarget(targetPlatform);
            var zigTarget = targetResolution.zigTarget();
            var ltoMode = resolveLtoMode(zigTarget, targetResolution.abiSubstituted(), optimizationLevel);

            // PCH preparation (build/reuse + probe) finishes before any parallel TU starts, so
            // a PCH that zig would reject is never force-included into the TU phase.
            var pch = preparePch(zig, zigTarget, ltoMode, optimizationLevel, includeDirs, projectDir, cachePath, registry);
            // Log shape: PCH fallback line first (only when degraded), then PCH-phase sections,
            // then TU slots in input order, then the link section.
            var logPrefix = pch.logPrefix() != null ? pch.logPrefix() : "";
            var startedCommands = new ArrayList<>(pch.startedCommands());
            var slotOutputs = new ArrayList<>(pch.outputs());

            var slots = createTuSlots(zig, zigTarget, ltoMode, optimizationLevel, includeDirs, cFiles, projectDir, pch.pchPath());
            runTuPhase(slots, registry, projectDir, cachePath);

            // The link consumes exactly this round's object paths in cFiles order; they are
            // configuration-namespaced and identical across the retry below.
            var objPaths = new ArrayList<Path>(slots.length);
            for (var slot : slots) {
                objPaths.add(slot.objPath);
            }
            var failedTuExitCode = collectTuResults(slots, startedCommands, slotOutputs);
            if (failedTuExitCode != null && pch.pchPath() != null && anyFailedTuBlamesPch(slots)) {
                // A TU rejecting the PCH after a passing probe must not fail the build: retry
                // the whole round without -include-pch. Every object is rebuilt, so PCH and
                // no-PCH objects never mix in one link.
                logPrefix = PCH_REJECTED_RETRY_NOTE;
                slots = createTuSlots(zig, zigTarget, ltoMode, optimizationLevel, includeDirs, cFiles, projectDir, null);
                runTuPhase(slots, registry, projectDir, cachePath);
                failedTuExitCode = collectTuResults(slots, startedCommands, slotOutputs);
            }
            if (failedTuExitCode != null) {
                // All TUs ran to completion for a complete log; the link step never started.
                return new CCompileResult(false, logPrefix + mergeCommandSections(startedCommands, slotOutputs), List.of());
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
                return new CCompileResult(false, logPrefix + mergeCommandSections(startedCommands, slotOutputs), List.of());
            }
            var artifacts = new ArrayList<Path>(2);
            artifacts.add(outputPath);
            if (targetPlatform.isWindows()) {
                var pdbPath = projectDir.resolve(outputBaseName + ".pdb").toAbsolutePath();
                if (Files.exists(pdbPath)) {
                    artifacts.add(pdbPath);
                }
            }
            return new CCompileResult(true, logPrefix + mergeSlotOutputs(slotOutputs), artifacts);
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

    /// One slot per TU in cFiles order: command and object path are precomputed on the runner
    /// thread, each worker then writes exactly its own slot, and the runner reads the slots
    /// only after the worker futures completed (Future.get provides the happens-before edge),
    /// keeping the build log order deterministic. `pchPath` is force-included only into
    /// whitelisted TUs; null disables PCH for the whole attempt (fallback and the
    /// rejection retry).
    private static @NotNull TuSlot[] createTuSlots(@NotNull Path zig, @NotNull String zigTarget, @NotNull CLtoMode ltoMode, @NotNull COptimizationLevel optimizationLevel, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles, @NotNull Path projectDir, @Nullable Path pchPath) throws IOException {
        var slots = new TuSlot[cFiles.size()];
        for (var i = 0; i < cFiles.size(); i++) {
            var cFile = cFiles.get(i);
            var objPath = resolveObjectPath(projectDir, optimizationLevel, zigTarget, i, cFile);
            Files.createDirectories(objPath.getParent());
            var tuPch = pchPath != null && isGodotBindingPchTu(cFile) ? pchPath : null;
            slots[i] = new TuSlot(buildTuCompileCommand(zig, zigTarget, ltoMode, optimizationLevel, includeDirs, objPath, cFile, tuPch), objPath);
        }
        return slots;
    }

    /// Collects worker results in slot order into the log lists and returns the first failing
    /// exit code, or null when every started TU produced its object. A TU whose process never
    /// started surfaces through the same IOException channel the serial implementation used
    /// (it gets no Command section).
    private static @Nullable Integer collectTuResults(TuSlot @NotNull [] slots, @NotNull List<List<String>> startedCommands, @NotNull List<String> outputs) throws IOException {
        Integer failedTuExitCode = null;
        for (var slot : slots) {
            if (slot.startFailure != null) {
                throw slot.startFailure;
            }
            if (slot.started) {
                startedCommands.add(slot.command);
                outputs.add(slot.output);
            }
            if (failedTuExitCode == null && slot.started && (slot.exitCode != 0 || !Files.exists(slot.objPath))) {
                failedTuExitCode = slot.exitCode;
            }
        }
        return failedTuExitCode;
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

    /// The complete language flag set shared — in the same order — by TU compiles, the PCH
    /// build, the PCH probe and the PCH cache key. clang rejects `-include-pch` when creation
    /// and usage options differ, and a key built from a diverging flag list would let an
    /// incompatible PCH poison the cache; funnelling all four consumers through this one list
    /// makes that drift impossible. `-c`/`-x`/`-o`/source paths/`-include-pch` are
    /// deliberately excluded: they distinguish the command kinds and are added by the callers.
    static @NotNull List<String> languageFlags(@NotNull CLtoMode ltoMode, @NotNull COptimizationLevel optimizationLevel) {
        var flags = new ArrayList<String>();
        flags.add("-std=c23");
        flags.add("-fPIC");
        var ltoFlag = ltoMode.cliFlag();
        if (ltoFlag != null) {
            flags.add(ltoFlag);
        }
        flags.add(optimizationFlag(optimizationLevel));
        flags.add("-Wno-macro-redefined");
        flags.add("-Wno-pointer-sign");
        return List.copyOf(flags);
    }

    /// Per-TU compile command:
    /// `zig cc -target <T> <languageFlags> -c -I... [-include-pch <pch>] -o <obj> <cFile>`.
    static @NotNull List<String> buildTuCompileCommand(@NotNull Path zig, @NotNull String zigTarget, @NotNull CLtoMode ltoMode, @NotNull COptimizationLevel optimizationLevel, @NotNull List<Path> includeDirs, @NotNull Path objPath, @NotNull Path cFile) {
        return buildTuCompileCommand(zig, zigTarget, ltoMode, optimizationLevel, includeDirs, objPath, cFile, null);
    }

    /// `includePch` is non-null only for whitelisted TUs of a PCH round (see
    /// [isGodotBindingPchTu]); the PCH probe command is this same shape with a trivial source.
    static @NotNull List<String> buildTuCompileCommand(@NotNull Path zig, @NotNull String zigTarget, @NotNull CLtoMode ltoMode, @NotNull COptimizationLevel optimizationLevel, @NotNull List<Path> includeDirs, @NotNull Path objPath, @NotNull Path cFile, @Nullable Path includePch) {
        var cmd = new ArrayList<String>();
        cmd.add(zig.toString());
        cmd.add("cc");
        cmd.add("-target");
        cmd.add(zigTarget);
        cmd.addAll(languageFlags(ltoMode, optimizationLevel));
        cmd.add("-c");
        for (var inc : includeDirs) {
            cmd.add("-I" + inc.toAbsolutePath());
        }
        if (includePch != null) {
            cmd.add("-include-pch");
            cmd.add(includePch.toString());
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

    /// Resolves the zig version string feeding the PCH cache key. `null` disables PCH for the
    /// round (a feature degradation, never a build failure); [InterruptedException] is the
    /// cancellation channel and must propagate rather than collapse into `null`.
    @FunctionalInterface
    interface ZigVersionProbe {
        @Nullable String probe(@NotNull Path zig, @NotNull CProcessRegistry registry, @NotNull Path projectDir, @NotNull Path cachePath) throws InterruptedException;
    }

    /// Result of the PCH preparation phase. `pchPath` non-null lets whitelisted TUs
    /// force-include it; otherwise `logPrefix` carries the fixed one-line fallback note that
    /// leads the build log. `startedCommands`/`outputs` hold the PCH-phase processes that
    /// actually started (build, probe, rebuild), ahead of the TU slots in the merged log.
    private record PchOutcome(@Nullable Path pchPath, @Nullable String logPrefix, @NotNull List<List<String>> startedCommands, @NotNull List<String> outputs) {
    }

    /// Resolves the PCH to force-include this round, or degrades to a no-PCH round. Failure
    /// handling is strictly layered: any PCH problem (version probe, hashing, build, probe,
    /// install, self-heal) produces the fixed fallback line and the build continues without
    /// PCH, while [InterruptedException] always propagates to the cancellation channel — a
    /// cancel is never swallowed into a fallback (that would report CANCELED only after the
    /// round finished and could leak orphan children).
    private @NotNull PchOutcome preparePch(@NotNull Path zig, @NotNull String zigTarget, @NotNull CLtoMode ltoMode, @NotNull COptimizationLevel optimizationLevel, @NotNull List<Path> includeDirs, @NotNull Path projectDir, @NotNull Path cachePath, @NotNull CProcessRegistry registry) throws InterruptedException {
        var startedCommands = new ArrayList<List<String>>();
        var outputs = new ArrayList<String>();

        var zigVersion = zigVersionProbe.probe(zig, registry, projectDir, cachePath);
        if (zigVersion == null) {
            return pchFallback("zig version probe failed", startedCommands, outputs);
        }
        final String key;
        try {
            key = resolvePchCacheKey(zigVersion, zigTarget, ltoMode, optimizationLevel, includeDirs);
        } catch (IOException exception) {
            return pchFallback("include tree hashing failed: " + exception.getMessage(), startedCommands, outputs);
        }
        var keyDir = cachePath.resolve(PCH_DIR_NAME).resolve(key);
        var pchPath = keyDir.resolve(PCH_FILE_NAME);

        if (isInstalledEntryComplete(keyDir)) {
            // Reused entry: probe it with this round's exact flag surface before any TU sees it.
            if (probePch(zig, zigTarget, ltoMode, optimizationLevel, includeDirs, keyDir, pchPath, projectDir, cachePath, registry, startedCommands, outputs)) {
                return new PchOutcome(pchPath, null, startedCommands, outputs);
            }
            // Self-heal: drop the poisoned entry and allow exactly one rebuild, so a single
            // crash or half-written file cannot disable PCH for this key forever.
            if (!deleteRecursively(keyDir)) {
                return pchFallback("installed pch failed the probe and the poisoned cache entry could not be deleted", startedCommands, outputs);
            }
            var rebuildFailure = buildAndInstallPch(zig, zigTarget, ltoMode, optimizationLevel, includeDirs, keyDir, projectDir, cachePath, registry, startedCommands, outputs);
            if (rebuildFailure != null) {
                return pchFallback("installed pch failed the probe and the one allowed rebuild also failed (" + rebuildFailure + ")", startedCommands, outputs);
            }
            return new PchOutcome(pchPath, null, startedCommands, outputs);
        }

        var buildFailure = buildAndInstallPch(zig, zigTarget, ltoMode, optimizationLevel, includeDirs, keyDir, projectDir, cachePath, registry, startedCommands, outputs);
        if (buildFailure != null) {
            return pchFallback(buildFailure, startedCommands, outputs);
        }
        return new PchOutcome(pchPath, null, startedCommands, outputs);
    }

    private static @NotNull PchOutcome pchFallback(@NotNull String reason, @NotNull List<List<String>> startedCommands, @NotNull List<String> outputs) {
        return new PchOutcome(null, PCH_FALLBACK_PREFIX + reason + "\n", startedCommands, outputs);
    }

    /// Builds the PCH into the cache entry and publishes it: the prefix header is written at
    /// its final path (clang records absolute paths inside the PCH, so the header must never
    /// move after the build), the PCH is compiled to a unique temporary name, probed, then
    /// renamed into place with the `.ready` marker written last. Same-key concurrent builds
    /// are interchangeable by construction (identical prefix header, flags and include trees),
    /// so a racing rename simply lets the last writer's equivalent entry win. Returns `null`
    /// on success, otherwise the failure reason for the fallback line.
    private @Nullable String buildAndInstallPch(@NotNull Path zig, @NotNull String zigTarget, @NotNull CLtoMode ltoMode, @NotNull COptimizationLevel optimizationLevel, @NotNull List<Path> includeDirs, @NotNull Path keyDir, @NotNull Path projectDir, @NotNull Path cachePath, @NotNull CProcessRegistry registry, @NotNull List<List<String>> startedCommands, @NotNull List<String> outputs) throws InterruptedException {
        try {
            Files.createDirectories(keyDir);
            var prefixHeader = keyDir.resolve(PCH_PREFIX_HEADER_NAME);
            installPrefixHeader(prefixHeader);
            var tmpPch = keyDir.resolve(PCH_FILE_NAME + ".tmp-" + randomSuffix());
            var buildCmd = buildPchBuildCommand(zig, zigTarget, ltoMode, optimizationLevel, includeDirs, prefixHeader, tmpPch);
            var buildRun = runPchProcess(buildCmd, projectDir, cachePath, registry, startedCommands, outputs);
            if (buildRun.exitCode() != 0 || !Files.isRegularFile(tmpPch)) {
                Files.deleteIfExists(tmpPch);
                return "pch build failed (exit " + buildRun.exitCode() + ")";
            }
            // Probe before publishing: an entry zig would reject never reaches consumers.
            if (!probePch(zig, zigTarget, ltoMode, optimizationLevel, includeDirs, keyDir, tmpPch, projectDir, cachePath, registry, startedCommands, outputs)) {
                Files.deleteIfExists(tmpPch);
                return "pch probe failed for the freshly built entry";
            }
            moveReplacing(tmpPch, keyDir.resolve(PCH_FILE_NAME));
            Files.writeString(keyDir.resolve(PCH_READY_MARKER_NAME), "");
            return null;
        } catch (IOException exception) {
            return "pch install failed: " + exception.getMessage();
        }
    }

    /// Compiles a trivial TU with the exact flag surface of a whitelisted TU plus
    /// `-include-pch <candidate>` — the cheap, faithful acceptance test clang itself performs
    /// on a PCH. Probe litter lives in the key directory (unique names, so concurrent
    /// processes never collide) and is best-effort removed afterwards; leftovers are inert.
    /// The probe source embeds the round-unique suffix so zig's content cache can never
    /// replay an earlier probe's verdict — every probe really compiles against the CURRENT
    /// candidate file.
    private boolean probePch(@NotNull Path zig, @NotNull String zigTarget, @NotNull CLtoMode ltoMode, @NotNull COptimizationLevel optimizationLevel, @NotNull List<Path> includeDirs, @NotNull Path keyDir, @NotNull Path pchCandidate, @NotNull Path projectDir, @NotNull Path cachePath, @NotNull CProcessRegistry registry, @NotNull List<List<String>> startedCommands, @NotNull List<String> outputs) throws InterruptedException {
        var suffix = randomSuffix().replace("-", "_");
        var probeSource = keyDir.resolve(PCH_PROBE_SOURCE_PREFIX + suffix + ".c");
        var probeObject = keyDir.resolve(PCH_PROBE_SOURCE_PREFIX + suffix + ".o");
        try {
            Files.writeString(probeSource, PCH_PROBE_SOURCE_CONTENT.formatted(suffix));
            var probeCmd = buildTuCompileCommand(zig, zigTarget, ltoMode, optimizationLevel, includeDirs, probeObject, probeSource, pchCandidate);
            var probeRun = runPchProcess(probeCmd, projectDir, cachePath, registry, startedCommands, outputs);
            return probeRun.exitCode() == 0 && Files.isRegularFile(probeObject);
        } catch (IOException exception) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(probeSource);
                Files.deleteIfExists(probeObject);
            } catch (IOException exception) {
                // Leftover probe files never affect the entry's validity.
            }
        }
    }

    /// Runs one PCH-phase process and records its log section. An [IOException] (process
    /// never started) escapes before recording, keeping the "no Command section for unstarted
    /// processes" rule.
    private @NotNull ProcessRun runPchProcess(@NotNull List<String> cmd, @NotNull Path projectDir, @NotNull Path cachePath, @NotNull CProcessRegistry registry, @NotNull List<List<String>> startedCommands, @NotNull List<String> outputs) throws IOException, InterruptedException {
        var run = runProcess(cmd, projectDir, cachePath, registry);
        startedCommands.add(cmd);
        outputs.add(run.output());
        return run;
    }

    /// Production version probe: routes `zig version` through this round's launcher and
    /// registry (the child is cancelled like every other zig process) under the round's zig
    /// cache environment. Only a successful parse is cached, process-wide, by [ZigUtil].
    private @Nullable String probeZigVersion(@NotNull Path zig, @NotNull CProcessRegistry registry, @NotNull Path projectDir, @NotNull Path cachePath) throws InterruptedException {
        return ZigUtil.findZigVersion(zig, processLauncher, registry, projectDir, zigCacheEnvironment(cachePath));
    }

    /// An installed entry is consumable only when marker, prefix header and PCH are all
    /// present; the marker is published last, so its presence implies a completed install.
    private static boolean isInstalledEntryComplete(@NotNull Path keyDir) {
        return Files.isRegularFile(keyDir.resolve(PCH_READY_MARKER_NAME))
                && Files.isRegularFile(keyDir.resolve(PCH_PREFIX_HEADER_NAME))
                && Files.isRegularFile(keyDir.resolve(PCH_FILE_NAME));
    }

    /// Writes the prefix header at its final path through a temporary file. An existing header
    /// with identical content (same-key entries share the constant content) is left untouched;
    /// a differing one is corruption and gets replaced. The mtime is then normalized to a
    /// fixed instant: clang validates the pch-recorded header mtime against the current file,
    /// and zig's content cache can replay a pch built against an older-mtime copy of the same
    /// content — a fixed instant makes that validation deterministic across rebuilds.
    private static void installPrefixHeader(@NotNull Path prefixHeader) throws IOException {
        if (!(Files.isRegularFile(prefixHeader) && Files.readString(prefixHeader).equals(PCH_PREFIX_HEADER_CONTENT))) {
            var tmp = prefixHeader.resolveSibling(PCH_PREFIX_HEADER_NAME + ".tmp-" + randomSuffix());
            Files.writeString(tmp, PCH_PREFIX_HEADER_CONTENT);
            moveReplacing(tmp, prefixHeader);
        }
        if (!Files.getLastModifiedTime(prefixHeader).toInstant().equals(PCH_PREFIX_HEADER_MTIME)) {
            Files.setLastModifiedTime(prefixHeader, FileTime.from(PCH_PREFIX_HEADER_MTIME));
        }
    }

    /// Renames `tmp` onto `target`, atomically where supported, replacing any existing entry —
    /// same-key racing entries are interchangeable, so last-writer-wins is safe.
    private static void moveReplacing(@NotNull Path tmp, @NotNull Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Best-effort recursive delete for the self-heal path; `false` means the poisoned entry
    /// could not be removed and the caller must fall back instead of rebuilding into a dirty
    /// directory.
    private static boolean deleteRecursively(@NotNull Path dir) {
        try (var walk = Files.walk(dir)) {
            var paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (var path : paths) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static @NotNull String randomSuffix() {
        return UUID.randomUUID().toString();
    }

    /// PCH cache key: SHA-256 (truncated to [PCH_KEY_HASH_BYTES] bytes) over a canonical
    /// document of the zig version, the resolved target, the full language flag list
    /// (optimization level and the actual LTO token included — debug and release never share
    /// a PCH, and a full-LTO fallback never reuses a ThinLTO entry) and the include
    /// directories in command order (`-I` order is semantic: swapping two directories that
    /// carry a same-named header must change the key). `GodotVersion`/`REAL_T_IS_DOUBLE`
    /// never appear: they reach zig only through header contents, which the tree hashes cover.
    static @NotNull String resolvePchCacheKey(@NotNull String zigVersion, @NotNull String zigTarget, @NotNull CLtoMode ltoMode, @NotNull COptimizationLevel optimizationLevel, @NotNull List<Path> includeDirs) throws IOException {
        var document = new StringBuilder();
        document.append("zig-version=").append(zigVersion).append('\n');
        document.append("zig-target=").append(zigTarget).append('\n');
        document.append("language-flags=").append(String.join(" ", languageFlags(ltoMode, optimizationLevel))).append('\n');
        for (var i = 0; i < includeDirs.size(); i++) {
            var dir = includeDirs.get(i).toAbsolutePath().normalize();
            document.append("include[").append(i).append("]=").append(dir).append('\n');
            document.append("include-tree-sha256[").append(i).append("]=").append(hashIncludeTree(dir)).append('\n');
        }
        var digest = newSha256().digest(document.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, PCH_KEY_HASH_BYTES);
    }

    /// Content hash of one include directory tree: regular files are visited in sorted
    /// relative-path order (sorting applies only inside a directory; the directory order
    /// itself is preserved by the caller) and each file contributes its length-prefixed
    /// relative path and content, so editing, adding or removing any header changes the hash.
    private static @NotNull String hashIncludeTree(@NotNull Path includeDir) throws IOException {
        var digest = newSha256();
        final List<Path> files;
        try (var walk = Files.walk(includeDir)) {
            files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> includeDir.relativize(path).toString()))
                    .toList();
        }
        for (var file : files) {
            var relative = includeDir.relativize(file).toString().replace(File.separatorChar, '/');
            updateLengthPrefixed(digest, relative.getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(digest, Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Length-prefixing removes any concatenation ambiguity between path/content boundaries.
    private static void updateLengthPrefixed(@NotNull MessageDigest digest, byte @NotNull [] bytes) {
        var length = (long) bytes.length;
        for (var i = Long.BYTES - 1; i >= 0; i--) {
            digest.update((byte) (length >>> (i * Byte.SIZE)));
        }
        digest.update(bytes);
    }

    private static @NotNull MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is a required algorithm of every Java runtime", exception);
        }
    }

    /// Name-based PCH whitelist anchored to the fixed `CProjectBuilder` native inputs: only
    /// TUs that actually include `godot_binding.h` may force-include the PCH. `minicoro.c`
    /// stays out so the Godot ABI headers never leak into the isolated assembly-backend TU.
    static boolean isGodotBindingPchTu(@NotNull Path cFile) {
        return PCH_WHITELIST_TU_NAMES.contains(cFile.getFileName().toString());
    }

    /// Heuristic for the near-impossible "zig rejects the PCH during a real TU compile after
    /// the probe passed": any failed TU whose diagnostics mention PCH machinery. A false
    /// positive costs one redundant no-PCH retry; a miss turns a PCH problem into a spurious
    /// build failure, so the markers err on the inclusive side.
    private static boolean anyFailedTuBlamesPch(TuSlot @NotNull [] slots) {
        for (var slot : slots) {
            if (slot.started && slot.exitCode != 0) {
                var output = slot.output.toLowerCase(Locale.ROOT);
                for (var marker : PCH_REJECTION_MARKERS) {
                    if (output.contains(marker)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /// PCH build command: the TU language flags with `-c`/source replaced by header mode —
    /// `zig cc -target <T> <languageFlags> -I... -x c-header <prefix.h> -o <pch>`.
    public static @NotNull List<String> buildPchBuildCommand(@NotNull Path zig, @NotNull String zigTarget, @NotNull CLtoMode ltoMode, @NotNull COptimizationLevel optimizationLevel, @NotNull List<Path> includeDirs, @NotNull Path prefixHeader, @NotNull Path pchOutput) {
        var cmd = new ArrayList<String>();
        cmd.add(zig.toString());
        cmd.add("cc");
        cmd.add("-target");
        cmd.add(zigTarget);
        cmd.addAll(languageFlags(ltoMode, optimizationLevel));
        for (var inc : includeDirs) {
            cmd.add("-I" + inc.toAbsolutePath());
        }
        cmd.add("-x");
        cmd.add("c-header");
        cmd.add(prefixHeader.toString());
        cmd.add("-o");
        cmd.add(pchOutput.toString());
        return cmd;
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
        var environmentOverrides = zigCacheEnvironment(cachePath);
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

    /// Both zig cache roots under the compiler cache root, passed to every zig sub-process of
    /// the round (TU compiles, PCH build/probe, link, version probe) identically.
    private static @NotNull Map<String, String> zigCacheEnvironment(@NotNull Path cachePath) {
        return Map.of(
                "ZIG_CACHE_DIR", cachePath.resolve("local").toString(),
                "ZIG_GLOBAL_CACHE_DIR", cachePath.resolve("global").toString()
        );
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
