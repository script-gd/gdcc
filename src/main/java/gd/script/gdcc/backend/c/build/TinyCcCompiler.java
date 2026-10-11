package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/// [CCompiler] driving the vendored tinycc in-process through libtcc's FFM binding
/// ([TinyCcLibrary]). One round compiles all translation units merged in a single fresh
/// `TCCState` and links them straight to the shared library — there is no object-file phase.
///
/// Round lifecycle and FFM invariants:
/// - Every round creates a new `TCCState` (`tcc_new`) and deletes it in `finally`; a state that
///   saw a failure is never reused, because libtcc's internal error state cannot be trusted to
///   reset. `tcc_delete` runs before the round's [Arena#ofConfined] closes, so the upcall stub
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
///   interrupt flag is polled between translation units and before the output step; an active
///   downcall cannot be interrupted. Cancellation always surfaces as the fixed
///   `Failed to run tcc: interrupted` channel with the interrupt status restored.
/// - The output artifact is deleted before `tcc_output_file` runs, so a stale artifact from an
///   earlier round can never masquerade as this round's success; afterwards the artifact must
///   exist as a regular file.
///
/// Deliberate contract differences from the zig backend: no `Command:` sections in `buildLog`
/// (there is no subprocess; libtcc diagnostics already carry their own `file:line: error:`
/// prefixes), no PDB artifact, and `optimizationLevel` does not change compilation flags — tcc
/// has no effective optimization pipeline, so only the output base name differs between DEBUG
/// and RELEASE (the caller encodes the level into `outputBaseName`).
public class TinyCcCompiler implements CCompiler {

    /// Serializes every libtcc round in this process (libtcc keeps process-global state; its
    /// internal locks are not a concurrency guarantee). Always acquired AFTER the per-project
    /// build lock, never the other way around.
    private static final ReentrantLock TCC_GLOBAL_LOCK = new ReentrantLock();

    /// Platforms with a verified libtcc backend bundle; cross-compilation is not possible
    /// because libtcc's target is fixed by how the library itself was built.
    private static final List<TargetPlatform> SUPPORTED_PLATFORMS = List.of(TargetPlatform.LINUX_X86_64, TargetPlatform.WINDOWS_X86_64);

    private static final String INTERRUPTED_CHANNEL = "Failed to run tcc: interrupted";
    private static final String FAILED_PREFIX = "Failed to run tcc: ";

    private final @NotNull BundleResolver bundleResolver;
    private final @NotNull NativeFactory nativeFactory;

    public TinyCcCompiler() {
        this(TinyCcBundle::requireBundleRoot, TinyCcFfmNative::create);
    }

    /// Test seam: a fake bundle resolver and a pure-Java [TinyCcNative] drive failure paths
    /// without touching the native library.
    TinyCcCompiler(@NotNull BundleResolver bundleResolver, @NotNull NativeFactory nativeFactory) {
        this.bundleResolver = Objects.requireNonNull(bundleResolver, "bundleResolver must not be null");
        this.nativeFactory = Objects.requireNonNull(nativeFactory, "nativeFactory must not be null");
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
                return compileLocked(bundleRoot, projectDir, includeDirs, cFiles, outputBaseName, targetPlatform);
            } finally {
                TCC_GLOBAL_LOCK.unlock();
            }
        } finally {
            projectLock.unlock();
        }
    }

    /// One locked round. Every failure path returns diagnostics collected so far plus the
    /// failure channel; every return path — success or failure — deletes the state and closes
    /// the round arena in the right order via `finally`/try-with-resources.
    private @NotNull CCompileResult compileLocked(@NotNull Path bundleRoot, @NotNull Path projectDir, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles, @NotNull String outputBaseName, @NotNull TargetPlatform targetPlatform) {
        var diagnostics = new ArrayList<String>();
        var outputPath = projectDir.resolve(targetPlatform.sharedLibraryFileName(outputBaseName)).toAbsolutePath();
        try (var arena = Arena.ofConfined()) {
            final TinyCcNative tcc;
            try {
                tcc = nativeFactory.create(bundleRoot, targetPlatform, arena);
            } catch (IOException e) {
                return failure(diagnostics, FAILED_PREFIX + e.getMessage());
            }
            var state = tcc.newState();
            if (state == null) {
                return failure(diagnostics, FAILED_PREFIX + "tcc_new failed to allocate a compiler state");
            }
            try {
                // The handler must be in place before any call that can emit diagnostics.
                tcc.setErrorHandler(state, diagnostics::add);
                tcc.setLibPath(state, bundleRoot.toString());
                var optionsStatus = tcc.setOptions(state, "-std=c11");
                if (optionsStatus != 0) {
                    return failure(diagnostics, FAILED_PREFIX + "tcc_set_options(\"-std=c11\") failed with status " + optionsStatus);
                }
                var callbackProblem = callbackFailureResult(tcc, diagnostics);
                if (callbackProblem != null) {
                    return callbackProblem;
                }
                var outputTypeStatus = tcc.setOutputType(state, TinyCcLibrary.TCC_OUTPUT_DLL);
                if (outputTypeStatus != 0) {
                    return failure(diagnostics, FAILED_PREFIX + "tcc_set_output_type(TCC_OUTPUT_DLL) failed with status " + outputTypeStatus);
                }
                callbackProblem = callbackFailureResult(tcc, diagnostics);
                if (callbackProblem != null) {
                    return callbackProblem;
                }
                for (var includeDir : includeDirs) {
                    var includeStatus = tcc.addIncludePath(state, includeDir.toString());
                    if (includeStatus != 0) {
                        return failure(diagnostics, FAILED_PREFIX + "tcc_add_include_path failed for " + includeDir + " with status " + includeStatus);
                    }
                    callbackProblem = callbackFailureResult(tcc, diagnostics);
                    if (callbackProblem != null) {
                        return callbackProblem;
                    }
                }
                for (var cFile : cFiles) {
                    // Cooperative cancellation at the TU boundary; an active downcall below
                    // cannot be interrupted.
                    if (Thread.currentThread().isInterrupted()) {
                        return interruptedResult();
                    }
                    var fileStatus = tcc.addFile(state, cFile.toString());
                    if (fileStatus != 0) {
                        return failure(diagnostics, FAILED_PREFIX + "tcc_add_file failed for " + cFile + " with status " + fileStatus);
                    }
                    callbackProblem = callbackFailureResult(tcc, diagnostics);
                    if (callbackProblem != null) {
                        return callbackProblem;
                    }
                }
                // Last cancellation point before the artifact is touched.
                if (Thread.currentThread().isInterrupted()) {
                    return interruptedResult();
                }
                // A stale artifact from an earlier round must not survive an output failure and
                // masquerade as this round's success.
                try {
                    Files.deleteIfExists(outputPath);
                } catch (IOException e) {
                    return failure(diagnostics, FAILED_PREFIX + "could not remove the stale artifact " + outputPath + ": " + e.getMessage());
                }
                var outputStatus = tcc.outputFile(state, outputPath.toString());
                if (outputStatus != 0) {
                    return failure(diagnostics, FAILED_PREFIX + "tcc_output_file failed for " + outputPath + " with status " + outputStatus);
                }
                callbackProblem = callbackFailureResult(tcc, diagnostics);
                if (callbackProblem != null) {
                    return callbackProblem;
                }
                if (!Files.isRegularFile(outputPath)) {
                    return failure(diagnostics, FAILED_PREFIX + "tcc_output_file reported success but " + outputPath + " was not created");
                }
                return new CCompileResult(true, diagnosticsLog(diagnostics), List.of(outputPath));
            } finally {
                tcc.delete(state);
            }
        }
    }

    /// A broken diagnostic upcall fails the round even when every native call returned success:
    /// a lost diagnostic makes the round's success unverifiable.
    private static @Nullable CCompileResult callbackFailureResult(@NotNull TinyCcNative tcc, @NotNull List<String> diagnostics) {
        var callbackFailure = tcc.callbackFailure();
        if (callbackFailure == null) {
            return null;
        }
        return failure(diagnostics, FAILED_PREFIX + "the diagnostic callback failed: " + callbackFailure);
    }

    private static @NotNull CCompileResult failure(@NotNull List<String> diagnostics, @NotNull String channel) {
        return new CCompileResult(false, diagnosticsLog(diagnostics) + channel, List.of());
    }

    private static @NotNull CCompileResult interruptedResult() {
        Thread.currentThread().interrupt();
        return new CCompileResult(false, INTERRUPTED_CHANNEL, List.of());
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
}
