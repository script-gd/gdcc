package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/// [CCompiler] driving the vendored tinycc in-process through libtcc's FFM binding
/// ([TinyCcLibrary]). One round compiles each translation unit with its own fresh `TCCState`
/// into an object file (`TCC_OUTPUT_OBJ` — libtcc always emits ELF objects, so the extension
/// is `.o` on every platform) and then links all objects with another fresh `TCC_OUTPUT_DLL`
/// state into the shared library. Objects are content-addressed in [TinyCcObjCache]: a TU
/// whose key hits is never recompiled, a miss compiles to a unique temp name and publishes
/// atomically.
///
/// Round lifecycle and FFM invariants:
/// - Every state is created with `tcc_new` and deleted in `finally`; a state that saw a
///   failure is never reused, because libtcc's internal error state cannot be trusted to
///   reset. A round uses at most N+1 states (one per missed TU plus the linker), each exactly
///   once. `tcc_delete` runs before the round's [Arena#ofConfined] closes, so the upcall stub
///   and every marshalled string are still alive while libtcc tears the state down.
/// - All downcalls and upcalls of a round run on the single thread holding [tccGlobalLock] —
///   the confined arena only allows access from its creating thread, and libtcc keeps
///   process-global state that is not concurrency-safe.
/// - Every libtcc `int` result is checked with `!= 0` (failures are not only `-1`:
///   `tcc_add_file` also reports `FILE_NOT_FOUND=-2` / `FILE_NOT_RECOGNIZED=-3`), and the
///   diagnostic-callback failure flag is checked after every downcall — a native success with a
///   broken upcall never counts as success. `tcc_output_file` resets libtcc's error counter
///   before doing its work, so its return value alone can never vouch for the steps before it.
/// - Cancellation is cooperative only: interrupt waits happen at both lock acquisitions and the
///   interrupt flag is polled before each TU's object compilation and before the final output
///   step; an active downcall cannot be interrupted. Cancellation always surfaces as the fixed
///   `Failed to run tcc: interrupted` channel with the interrupt status restored and never
///   carries cache statistics (the round did not complete).
/// - The cache is never a build failure source: key preparation runs inside both locks (no
///   libtcc calls), an include-tree/bundle-VERSION/cache-root failure degrades the whole round
///   to cacheless with one fixed fallback line, a publish failure degrades only that TU to its
///   temp artifact, and a TU read failure is a plain miss. When the linker rejects a cache-hit
///   object, the entry is evicted, the TU recompiled fresh, and the link retried exactly once
///   from a new state; an object compiled this round never triggers a retry.
/// - The output artifact is deleted before the final `tcc_output_file` runs, so a stale
///   artifact from an earlier round can never masquerade as this round's success; afterwards
///   the artifact must exist as a regular file.
///
/// `buildLog` contract: when the cache is available the log starts with the fixed line
/// `[gdcc] tcc object cache: <hits> hits, <misses> misses`; a degraded round starts with
/// `[gdcc] tcc object cache unavailable this round: <reason>` (both lines when only the
/// publish leg degraded). Diagnostics keep their native `file:line: error:` shape after the
/// header lines. Deliberate differences from the zig backend: no `Command:` sections (there
/// is no subprocess), no PDB artifact, and `optimizationLevel` does not change compilation
/// flags — tcc has no effective optimization pipeline, so DEBUG and RELEASE share cache
/// entries and only the output base name differs (the caller encodes the level into it).
public class TinyCcCompiler implements CCompiler {

    /// Serializes every libtcc round in this process (libtcc keeps process-global state; its
    /// internal locks are not a concurrency guarantee). Always acquired AFTER the per-project
    /// build lock, never the other way around.
    private static final ReentrantLock TCC_GLOBAL_LOCK = new ReentrantLock();

    /// Platforms with a verified libtcc backend bundle; cross-compilation is not possible
    /// because libtcc's target is fixed by how the library itself was built.
    private static final List<TargetPlatform> SUPPORTED_PLATFORMS = List.of(
            TargetPlatform.LINUX_X86_64,
            TargetPlatform.WINDOWS_X86_64,
            TargetPlatform.LINUX_AARCH64,
            TargetPlatform.MACOS_AARCH64
    );

    private static final String INTERRUPTED_CHANNEL = "Failed to run tcc: interrupted";
    private static final String FAILED_PREFIX = "Failed to run tcc: ";
    private static final String CACHE_STATS_PREFIX = "[gdcc] tcc object cache: ";
    private static final String CACHE_FALLBACK_PREFIX = "[gdcc] tcc object cache unavailable this round: ";

    private final @NotNull BundleResolver bundleResolver;
    private final @NotNull NativeFactory nativeFactory;
    private final @NotNull CacheRootResolver cacheRootResolver;

    public TinyCcCompiler() {
        this(TinyCcBundle::requireBundleRoot, TinyCcFfmNative::create);
    }

    /// Test seam: a fake bundle resolver and a pure-Java [TinyCcNative] drive failure paths
    /// without touching the native library.
    TinyCcCompiler(@NotNull BundleResolver bundleResolver, @NotNull NativeFactory nativeFactory) {
        this(bundleResolver, nativeFactory, CCompilerCacheRoot::resolve);
    }

    /// Full test seam: additionally pins the cache root resolution (see [CacheRootResolver]).
    TinyCcCompiler(@NotNull BundleResolver bundleResolver, @NotNull NativeFactory nativeFactory, @NotNull CacheRootResolver cacheRootResolver) {
        this.bundleResolver = Objects.requireNonNull(bundleResolver, "bundleResolver must not be null");
        this.nativeFactory = Objects.requireNonNull(nativeFactory, "nativeFactory must not be null");
        this.cacheRootResolver = Objects.requireNonNull(cacheRootResolver, "cacheRootResolver must not be null");
    }

    /// Resolves the tinycc bundle root for this round (installing it from the classpath on first
    /// use in production). Failures report the missing bundle and the `GDCC_TINYCC_HOME`
    /// override mechanism.
    @FunctionalInterface
    interface BundleResolver {
        @NotNull Path resolve() throws IOException;
    }

    /// Creates the round-scoped native bridge. Production ties every marshalled string and the
    /// diagnostic upcall stub to `roundArena`.
    @FunctionalInterface
    interface NativeFactory {
        @NotNull TinyCcNative create(@NotNull Path bundleRoot, @NotNull TargetPlatform targetPlatform, @NotNull Arena roundArena) throws IOException;
    }

    /// Resolves the cache root of a round. Production delegates to [CCompilerCacheRoot]; tests
    /// pin an isolated temporary root because `@TempDir` cannot scrub a parent-process
    /// `GDCC_SHARED_C_COMPILER_CACHE` (CI sets it for the zig cache), which would otherwise
    /// redirect the round's entries away from the temporary project tree.
    @FunctionalInterface
    interface CacheRootResolver {
        @NotNull Path resolve(@NotNull Path projectDir);
    }

    @Override
    public CCompileResult compile(@NotNull Path projectDir, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles, @NotNull String outputBaseName, @NotNull COptimizationLevel optimizationLevel, @NotNull TargetPlatform targetPlatform) {
        final Path bundleRoot;
        try {
            bundleRoot = bundleResolver.resolve();
        } catch (IOException | IllegalStateException e) {
            // A cancellation while waiting for the bundle install lock surfaces here as an
            // IOException with the interrupt status already restored by the resolver — route it
            // to the fixed cancellation channel like every other interruption point.
            if (Thread.currentThread().isInterrupted()) {
                return interruptedResult();
            }
            // IllegalStateException covers hosts without any bundle layout (the resolver's
            // platform gate); its message already points at the zig backend.
            return new CCompileResult(false, FAILED_PREFIX + e.getMessage(), List.of());
        }
        final TargetPlatform host;
        try {
            host = TargetPlatform.getNativePlatform();
        } catch (IllegalStateException e) {
            return new CCompileResult(false, FAILED_PREFIX + e.getMessage(), List.of());
        }
        // Fail fast without touching the native library: libtcc can only target the platform it
        // was built for, and only platforms with a verified bundle are supported at all.
        if (targetPlatform != host || !SUPPORTED_PLATFORMS.contains(targetPlatform)) {
            return new CCompileResult(false, "tinycc supports only host-native builds on " + SUPPORTED_PLATFORMS
                    + "; target " + targetPlatform + " on host " + host + " is not supported — use the zig backend", List.of());
        }
        if (cFiles.isEmpty()) {
            return new CCompileResult(false, "No C files to compile", List.of());
        }

        // Normalize once so the cache keys and the native calls see the exact same path
        // spelling: `__FILE__`/`STT_FILE` bake the string passed to `tcc_add_file` into the
        // object, so a normalized key over a non-normalized native path would alias distinct
        // objects.
        cFiles = cFiles.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        includeDirs = includeDirs.stream().map(path -> path.toAbsolutePath().normalize()).toList();

        // Lock order is fixed: per-project build lock first, the tcc global lock second, never
        // the reverse. The project lock serializes zig/tcc rounds on the same output directory;
        // the global lock serializes libtcc's process-global state.
        var projectLock = CProjectBuildLock.require(projectDir);
        try {
            projectLock.lockInterruptibly();
        } catch (InterruptedException e) {
            return interruptedResult();
        }
        try {
            try {
                TCC_GLOBAL_LOCK.lockInterruptibly();
            } catch (InterruptedException e) {
                return interruptedResult();
            }
            try {
                // Key preparation runs inside both locks: the content hashed for the key must be
                // the content libtcc later compiles — hashing outside the locks would open a
                // window where a rewrite publishes new bytes under the old content's key. The
                // locks cover this round's reads; the writers of the generated sources are
                // outside the lock protocol (they run before compile and rely on one build task
                // per project directory at a time — an accepted residual risk).
                var cachePlan = prepareCachePlan(bundleRoot, projectDir, includeDirs, cFiles, targetPlatform);
                return compileLocked(bundleRoot, projectDir, includeDirs, cFiles, outputBaseName, targetPlatform, cachePlan);
            } finally {
                TCC_GLOBAL_LOCK.unlock();
            }
        } finally {
            projectLock.unlock();
        }
    }

    /// Reads every cache-key input up front (no libtcc calls, no extra cancellation points).
    /// A TU that cannot be read becomes a `null` key (a plain miss — tcc's own diagnostic names
    /// the file if its compile also fails); any other failure (include-tree hashing, bundle
    /// VERSION, cache root) degrades the whole round to cacheless, so the cache can never fail
    /// a build.
    private @NotNull CachePlan prepareCachePlan(@NotNull Path bundleRoot, @NotNull Path projectDir, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles, @NotNull TargetPlatform targetPlatform) {
        try {
            var cacheRoot = cacheRootResolver.resolve(projectDir);
            var bundleVersion = Files.readString(bundleRoot.resolve(TinyCcBundle.VERSION_FILE_NAME), StandardCharsets.UTF_8);
            // Hash each include tree once per round, not once per TU.
            var includeTreeHashes = TinyCcObjCache.hashIncludeTrees(includeDirs);
            var keys = new ArrayList<@Nullable String>(cFiles.size());
            for (var cFile : cFiles) {
                final byte[] content;
                try {
                    content = Files.readAllBytes(cFile);
                } catch (IOException e) {
                    keys.add(null);
                    continue;
                }
                keys.add(TinyCcObjCache.requireKey(cFile, content, includeDirs, includeTreeHashes, bundleVersion, targetPlatform));
            }
            return new CachePlan(cacheRoot, keys, null);
        } catch (IOException | RuntimeException e) {
            return CachePlan.unavailable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /// One locked round. Every failure path returns the cache header, the diagnostics collected
    /// so far and the failure channel; every return path — success or failure — deletes each
    /// state and closes the round arena in the right order via `finally`/try-with-resources.
    private @NotNull CCompileResult compileLocked(@NotNull Path bundleRoot, @NotNull Path projectDir, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles, @NotNull String outputBaseName, @NotNull TargetPlatform targetPlatform, @NotNull CachePlan cachePlan) {
        var diagnostics = new ArrayList<String>();
        var log = new RoundCacheLog(cachePlan);
        var outputPath = projectDir.resolve(targetPlatform.sharedLibraryFileName(outputBaseName)).toAbsolutePath();
        var borrowedTemps = new ArrayList<Path>();
        try (var arena = Arena.ofConfined()) {
            final TinyCcNative tcc;
            try {
                tcc = nativeFactory.create(bundleRoot, targetPlatform, arena);
            } catch (IOException e) {
                return failure(log, diagnostics, FAILED_PREFIX + e.getMessage());
            }
            // Per TU: collect the cached object on a hit, or compile it fresh on a miss.
            var objects = new ArrayList<LinkedObject>(cFiles.size());
            for (var i = 0; i < cFiles.size(); i++) {
                // Cooperative cancellation before each TU's object compilation; an active
                // downcall cannot be interrupted.
                if (Thread.currentThread().isInterrupted()) {
                    return interruptedResult();
                }
                var key = cachePlan.available() ? cachePlan.keys().get(i) : null;
                if (key != null) {
                    var hit = TinyCcObjCache.findHit(cachePlan.cacheRoot(), key);
                    if (hit != null) {
                        log.hits++;
                        objects.add(new LinkedObject(hit, key));
                        continue;
                    }
                }
                log.misses++;
                var outcome = compileTuToObject(tcc, bundleRoot, diagnostics, includeDirs, cFiles.get(i), key, cachePlan, borrowedTemps);
                if (outcome.fallbackReason() != null) {
                    log.degradeOnce(outcome.fallbackReason());
                }
                if (outcome.failureChannel() != null) {
                    return failure(log, diagnostics, outcome.failureChannel());
                }
                // A TU compiled this round never counts as a cache hit for the self-heal rule.
                objects.add(new LinkedObject(outcome.objectPath(), null));
            }

            // Linking: one fresh DLL state consuming the objects in cFiles order (object inputs
            // need no include paths). A rejected cache-hit entry triggers exactly one self-heal:
            // evict, recompile that TU fresh, and re-link from a new state.
            for (var attempt = 0; ; attempt++) {
                var linkState = tcc.newState();
                if (linkState == null) {
                    return failure(log, diagnostics, FAILED_PREFIX + "tcc_new failed to allocate a compiler state");
                }
                try {
                    var setupProblem = setupState(tcc, linkState, bundleRoot, diagnostics, List.of(), TinyCcLibrary.TCC_OUTPUT_DLL);
                    if (setupProblem != null) {
                        return failure(log, diagnostics, setupProblem);
                    }
                    var rejectedHit = -1;
                    var linkDiagnosticsMark = diagnostics.size();
                    for (var i = 0; i < objects.size(); i++) {
                        var obj = objects.get(i);
                        var fileStatus = tcc.addFile(linkState, obj.path().toString());
                        if (fileStatus != 0 && obj.cacheKey() != null && attempt == 0) {
                            // A broken diagnostic upcall is a round failure, not a cache
                            // poisoning — check it before any self-heal touches the cache.
                            var callbackProblem = callbackFailureChannel(tcc);
                            if (callbackProblem != null) {
                                return failure(log, diagnostics, callbackProblem);
                            }
                            rejectedHit = i;
                            break;
                        }
                        if (fileStatus != 0) {
                            return failure(log, diagnostics, FAILED_PREFIX + "tcc_add_file failed for " + obj.path() + " with status " + fileStatus);
                        }
                        var callbackProblem = callbackFailureChannel(tcc);
                        if (callbackProblem != null) {
                            return failure(log, diagnostics, callbackProblem);
                        }
                    }
                    if (rejectedHit >= 0) {
                        // Self-heal is still round work: honor a pending interrupt before
                        // starting the recompilation.
                        if (Thread.currentThread().isInterrupted()) {
                            return interruptedResult();
                        }
                        // Drop the rejected link attempt's diagnostics so the log cannot carry a
                        // misleading error after the heal succeeded.
                        diagnostics.subList(linkDiagnosticsMark, diagnostics.size()).clear();
                        log.hits--;
                        log.misses++;
                        var rejected = objects.get(rejectedHit);
                        try {
                            TinyCcObjCache.evict(cachePlan.cacheRoot(), rejected.cacheKey());
                        } catch (IOException e) {
                            log.degradeOnce("could not evict the rejected cache entry: " + e.getMessage());
                        }
                        var recompiled = compileTuToObject(tcc, bundleRoot, diagnostics, includeDirs, cFiles.get(rejectedHit), rejected.cacheKey(), cachePlan, borrowedTemps);
                        if (recompiled.fallbackReason() != null) {
                            log.degradeOnce(recompiled.fallbackReason());
                        }
                        if (recompiled.failureChannel() != null) {
                            return failure(log, diagnostics, recompiled.failureChannel());
                        }
                        objects.set(rejectedHit, new LinkedObject(recompiled.objectPath(), null));
                        continue;
                    }
                    // Last cancellation point before the artifact is touched.
                    if (Thread.currentThread().isInterrupted()) {
                        return interruptedResult();
                    }
                    // A stale artifact from an earlier round must not survive an output failure
                    // and masquerade as this round's success.
                    try {
                        Files.deleteIfExists(outputPath);
                    } catch (IOException e) {
                        return failure(log, diagnostics, FAILED_PREFIX + "could not remove the stale artifact " + outputPath + ": " + e.getMessage());
                    }
                    var outputStatus = tcc.outputFile(linkState, outputPath.toString());
                    if (outputStatus != 0) {
                        return failure(log, diagnostics, FAILED_PREFIX + "tcc_output_file failed for " + outputPath + " with status " + outputStatus);
                    }
                    var callbackProblem = callbackFailureChannel(tcc);
                    if (callbackProblem != null) {
                        return failure(log, diagnostics, callbackProblem);
                    }
                    if (!Files.isRegularFile(outputPath)) {
                        return failure(log, diagnostics, FAILED_PREFIX + "tcc_output_file reported success but " + outputPath + " was not created");
                    }
                    return new CCompileResult(true, log.header() + diagnosticsLog(diagnostics), List.of(outputPath));
                } finally {
                    tcc.delete(linkState);
                }
            }
        } finally {
            // Temp objects borrowed by degraded rounds are only needed until the link finished;
            // published entries are not tracked here — the cache owns them.
            for (var temp : borrowedTemps) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Leftover temp files in the system/cache temp area are harmless.
                }
            }
        }
    }

    /// Compiles one TU to an object with a dedicated fresh state. The output always lands in a
    /// round-exclusive temp file (system temp area), so a cache filesystem failure can never
    /// fail the build; the link consumes this exclusive copy. With a key, a copy is then staged
    /// into the cache directory and atomically published — any failure of that leg degrades to
    /// the fallback line only.
    private static @NotNull TuOutcome compileTuToObject(@NotNull TinyCcNative tcc, @NotNull Path bundleRoot, @NotNull List<String> diagnostics, @NotNull List<Path> includeDirs, @NotNull Path cFile, @Nullable String key, @NotNull CachePlan cachePlan, @NotNull List<Path> borrowedTemps) {
        var state = tcc.newState();
        if (state == null) {
            return TuOutcome.failure(FAILED_PREFIX + "tcc_new failed to allocate a compiler state");
        }
        try {
            var setupProblem = setupState(tcc, state, bundleRoot, diagnostics, includeDirs, TinyCcLibrary.TCC_OUTPUT_OBJ);
            if (setupProblem != null) {
                return TuOutcome.failure(setupProblem);
            }
            var fileStatus = tcc.addFile(state, cFile.toString());
            if (fileStatus != 0) {
                return TuOutcome.failure(FAILED_PREFIX + "tcc_add_file failed for " + cFile + " with status " + fileStatus);
            }
            var callbackProblem = callbackFailureChannel(tcc);
            if (callbackProblem != null) {
                return TuOutcome.failure(callbackProblem);
            }
            final Path objectOut;
            try {
                objectOut = Files.createTempFile("gdcc-tcc-obj-", ".o");
            } catch (IOException e) {
                return TuOutcome.failure(FAILED_PREFIX + "could not create a temp object file for " + cFile + ": " + e.getMessage());
            }
            borrowedTemps.add(objectOut);
            // createTempFile pre-creates an empty file; remove it so the post-output existence
            // check really proves that tcc_output_file created the object.
            try {
                Files.deleteIfExists(objectOut);
            } catch (IOException e) {
                return TuOutcome.failure(FAILED_PREFIX + "could not prepare the temp object path " + objectOut + ": " + e.getMessage());
            }
            var outputStatus = tcc.outputFile(state, objectOut.toString());
            if (outputStatus != 0) {
                return TuOutcome.failure(FAILED_PREFIX + "tcc_output_file failed for " + objectOut + " with status " + outputStatus);
            }
            callbackProblem = callbackFailureChannel(tcc);
            if (callbackProblem != null) {
                return TuOutcome.failure(callbackProblem);
            }
            if (!Files.isRegularFile(objectOut)) {
                return TuOutcome.failure(FAILED_PREFIX + "tcc_output_file reported success but " + objectOut + " was not created");
            }
            if (key == null) {
                return new TuOutcome(objectOut, null, null);
            }
            try {
                var cacheTemp = TinyCcObjCache.newTempObjectPath(cachePlan.cacheRoot(), key);
                try {
                    Files.copy(objectOut, cacheTemp, StandardCopyOption.REPLACE_EXISTING);
                    TinyCcObjCache.publish(cachePlan.cacheRoot(), key, cacheTemp);
                } catch (IOException | RuntimeException e) {
                    deleteQuietly(cacheTemp);
                    throw e;
                }
            } catch (IOException | RuntimeException e) {
                // Publish-leg failure (staging, copy, or ATOMIC_MOVE unsupported): the round
                // links the exclusive temp artifact — never overwrite the final path
                // non-atomically.
                return new TuOutcome(objectOut, "could not publish the object cache entry: " + e.getMessage(), null);
            }
            return new TuOutcome(objectOut, null, null);
        } finally {
            tcc.delete(state);
        }
    }

    /// Shared state setup: error handler first (before any call that can emit diagnostics),
    /// then lib path, options and output type, then the include paths in input order. Returns
    /// the failure channel on the first failing step, or null when the state is ready.
    private static @Nullable String setupState(@NotNull TinyCcNative tcc, @NotNull MemorySegment state, @NotNull Path bundleRoot, @NotNull List<String> diagnostics, @NotNull List<Path> includeDirs, int outputType) {
        tcc.setErrorHandler(state, diagnostics::add);
        tcc.setLibPath(state, bundleRoot.toString());
        var optionsStatus = tcc.setOptions(state, "-std=c11");
        if (optionsStatus != 0) {
            return FAILED_PREFIX + "tcc_set_options(\"-std=c11\") failed with status " + optionsStatus;
        }
        var callbackProblem = callbackFailureChannel(tcc);
        if (callbackProblem != null) {
            return callbackProblem;
        }
        var outputTypeStatus = tcc.setOutputType(state, outputType);
        if (outputTypeStatus != 0) {
            return FAILED_PREFIX + "tcc_set_output_type(" + outputType + ") failed with status " + outputTypeStatus;
        }
        callbackProblem = callbackFailureChannel(tcc);
        if (callbackProblem != null) {
            return callbackProblem;
        }
        for (var includeDir : includeDirs) {
            var includeStatus = tcc.addIncludePath(state, includeDir.toString());
            if (includeStatus != 0) {
                return FAILED_PREFIX + "tcc_add_include_path failed for " + includeDir + " with status " + includeStatus;
            }
            callbackProblem = callbackFailureChannel(tcc);
            if (callbackProblem != null) {
                return callbackProblem;
            }
        }
        return null;
    }

    /// A broken diagnostic upcall fails the round even when every native call returned success:
    /// a lost diagnostic makes the round's success unverifiable.
    private static @Nullable String callbackFailureChannel(@NotNull TinyCcNative tcc) {
        var callbackFailure = tcc.callbackFailure();
        if (callbackFailure == null) {
            return null;
        }
        return FAILED_PREFIX + "the diagnostic callback failed: " + callbackFailure;
    }

    private static @NotNull CCompileResult failure(@NotNull RoundCacheLog log, @NotNull List<String> diagnostics, @NotNull String channel) {
        return new CCompileResult(false, log.header() + diagnosticsLog(diagnostics) + channel, List.of());
    }

    private static @NotNull CCompileResult interruptedResult() {
        Thread.currentThread().interrupt();
        return new CCompileResult(false, INTERRUPTED_CHANNEL, List.of());
    }

    private static void deleteQuietly(@NotNull Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover temp object is harmless; the final artifact checks guard correctness.
        }
    }

    /// Diagnostics are one message per libtcc callback, without a trailing newline.
    private static @NotNull String diagnosticsLog(@NotNull List<String> diagnostics) {
        if (diagnostics.isEmpty()) {
            return "";
        }
        var log = new StringBuilder();
        for (var diagnostic : diagnostics) {
            log.append(diagnostic).append('\n');
        }
        return log.toString();
    }

    /// Test-facing accessor: lets tests hold the global lock to exercise the lock-wait
    /// cancellation channel deterministically, and to assert it is held during a round.
    static @NotNull ReentrantLock tccGlobalLock() {
        return TCC_GLOBAL_LOCK;
    }

    /// Cache inputs resolved inside both locks. `available()` is false for a cacheless round
    /// (`cacheRoot`/`keys` are then meaningless); `keys` otherwise parallels `cFiles`, a null
    /// element marking a TU that could not be read and compiles as a plain miss.
    private record CachePlan(@Nullable Path cacheRoot, @NotNull List<@Nullable String> keys,
                             @Nullable String unavailableReason) {
        boolean available() {
            return cacheRoot != null;
        }

        static @NotNull CachePlan unavailable(@NotNull String reason) {
            return new CachePlan(null, List.of(), reason);
        }
    }

    /// One object consumed by the link. `cacheKey` is non-null only for an object consumed as a
    /// cache hit — the self-heal retry applies exclusively to those, never to objects compiled
    /// during this round.
    private record LinkedObject(@NotNull Path path, @Nullable String cacheKey) {
    }

    /// `objectPath` is set on success; `failureChannel` on failure; `fallbackReason` records
    /// the first cache degradation for the round's fallback line.
    private record TuOutcome(@Nullable Path objectPath, @Nullable String fallbackReason,
                             @Nullable String failureChannel) {
        static @NotNull TuOutcome failure(@NotNull String channel) {
            return new TuOutcome(null, null, channel);
        }
    }

    /// Mutable per-round cache log state: the fixed statistics line is emitted only when the
    /// cache was available, the fallback line only on the first degradation.
    private static final class RoundCacheLog {
        private final boolean available;
        private int hits;
        private int misses;
        private @Nullable String fallbackReason;

        RoundCacheLog(@NotNull CachePlan plan) {
            this.available = plan.available();
            this.fallbackReason = plan.unavailableReason();
        }

        void degradeOnce(@NotNull String reason) {
            if (fallbackReason == null) {
                fallbackReason = reason;
            }
        }

        @NotNull String header() {
            var header = new StringBuilder();
            if (fallbackReason != null) {
                header.append(CACHE_FALLBACK_PREFIX).append(fallbackReason).append('\n');
            }
            if (available) {
                header.append(CACHE_STATS_PREFIX).append(hits).append(" hits, ").append(misses).append(" misses\n");
            }
            return header.toString();
        }
    }
}
