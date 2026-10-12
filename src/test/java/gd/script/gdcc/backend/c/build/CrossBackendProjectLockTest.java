package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Cross-backend mutual exclusion on ONE project directory: [CProjectBuildLock] is shared by
/// [ZigCcCompiler] and [TinyCcCompiler], so a tcc round and a zig round targeting the same output
/// name must never overlap — otherwise the tcc pre-output delete and the zig link could interleave
/// into a half-written artifact. The lock order contract (project lock before the tcc global lock)
/// is what makes this safe, and both directions are proven deterministically:
///
/// - tcc holds (blocked inside a fake native addFile) → the real zig round is observed QUEUED on
///   the project lock and its compile only starts after the tcc round fully completed;
/// - the shared project lock is held by the test thread → a tcc round queues and its native
///   factory is never invoked (the factory runs only after BOTH locks are acquired).
///
/// A final sequential alternation on the real compilers asserts the artifact is coherent after
/// every round. The class stays `same_thread`; concurrency is created inside the tests.
class CrossBackendProjectLockTest {

    @Test
    void zigRoundWaitsForTinyCcRoundHoldingTheProjectLock(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(ZigUtil.findZig() != null, "zig is required for the zig leg");
        var host = TargetPlatform.getNativePlatform();
        Assumptions.assumeTrue(host == TargetPlatform.LINUX_X86_64 || host == TargetPlatform.WINDOWS_X86_64,
                "tinycc supports only linux/windows x86_64 hosts");
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        // The zig TU carries a distinctive export so the final artifact's producer is provable.
        var zigTu = Files.writeString(projectDir.resolve("zig_entry.c"),
                "int gdcc_zig_round_symbol(void) { return 7; }\n"
                        + "int gdextension_entry(void) { return 0; }\n");
        var tccTu = Files.writeString(projectDir.resolve("tcc_entry.c"),
                "int gdextension_entry(void) { return 1; }\n");

        var probe = new BlockingNativeProbe();
        var tccCompiler = new TinyCcCompiler(() -> projectDir, (root, platform, arena) -> {
            probe.factoryCalls.incrementAndGet();
            return probe;
        });
        var zigCompiler = new ZigCcCompiler();

        var projectLock = CProjectBuildLock.require(projectDir);
        var tccResult = new AtomicReference<@Nullable CCompileResult>();
        var zigResult = new AtomicReference<@Nullable CCompileResult>();
        var tccThread = Thread.ofPlatform().name("gdcc-test-xbackend-tcc").unstarted(() ->
                tccResult.set(tccCompiler.compile(projectDir, List.of(), List.of(tccTu), "race", COptimizationLevel.DEBUG, host)));
        var zigThread = Thread.ofPlatform().name("gdcc-test-xbackend-zig").unstarted(() ->
                zigResult.set(zigCompiler.compile(projectDir, List.of(), List.of(zigTu), "race", COptimizationLevel.DEBUG, host)));
        try {
            tccThread.start();
            assertTrue(probe.insideAddFile.await(10, TimeUnit.SECONDS), "the tcc round never reached addFile");
            assertTrue(projectLock.isLocked() && TinyCcCompiler.tccGlobalLock().isLocked(),
                    "the tcc round must hold both locks while inside libtcc");

            zigThread.start();
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!projectLock.hasQueuedThread(zigThread)) {
                if (System.nanoTime() > deadline) {
                    fail("the zig round never queued on the project lock held by the tcc round");
                }
                if (!zigThread.isAlive()) {
                    fail("the zig round died before reaching the project lock");
                }
                Thread.sleep(10);
            }
            probe.release.countDown();

            tccThread.join(Duration.ofMinutes(1));
            zigThread.join(Duration.ofMinutes(2));
        } finally {
            // Unpark and converge both workers on every exit path so the shared static locks can
            // never leak into sibling test classes. Best-effort only.
            probe.release.countDown();
            tccThread.interrupt();
            zigThread.interrupt();
            tccThread.join(Duration.ofSeconds(30));
            if (zigThread.isAlive()) {
                zigThread.join(Duration.ofSeconds(30));
            }
        }

        assertNotNull(tccResult.get());
        assertTrue(tccResult.get().success(), () -> "tcc round failed:\n" + tccResult.get().buildLog());
        assertNotNull(zigResult.get());
        assertTrue(zigResult.get().success(), () -> "zig round failed:\n" + zigResult.get().buildLog());

        // The zig round ran LAST (it queued behind the tcc round), so the shared output file must
        // be the complete zig artifact — not the fake's marker text and not a half-written mix.
        var artifact = projectDir.resolve(host.sharedLibraryFileName("race"));
        NativeArtifactInspector.assertSharedLibraryShape(artifact);
        NativeArtifactInspector.assertExportsSymbol(artifact, "gdcc_zig_round_symbol");
    }

    @Test
    void tinyCcRoundNeverStartsWhileTheSharedProjectLockIsHeld(@TempDir Path tempDir) throws Exception {
        var host = TargetPlatform.getNativePlatform();
        Assumptions.assumeTrue(host == TargetPlatform.LINUX_X86_64 || host == TargetPlatform.WINDOWS_X86_64,
                "tinycc supports only linux/windows x86_64 hosts");
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        var tccTu = Files.writeString(projectDir.resolve("entry.c"), "int gdextension_entry(void) { return 1; }\n");
        var factoryCalls = new AtomicInteger();
        var resultRef = new AtomicReference<@Nullable CCompileResult>();
        var compiler = new TinyCcCompiler(() -> projectDir, (root, platform, arena) -> {
            factoryCalls.incrementAndGet();
            return new WritingFakeNative();
        });

        // Holding the shared per-project lock directly simulates ANY in-flight round (a zig round
        // holds the same lock): the tcc round must queue on it and never reach the native layer.
        var projectLock = CProjectBuildLock.require(projectDir);
        projectLock.lock();
        var waiter = Thread.ofPlatform().name("gdcc-test-xbackend-waiter").unstarted(() ->
                resultRef.set(compiler.compile(projectDir, List.of(), List.of(tccTu), "race", COptimizationLevel.DEBUG, host)));
        try {
            waiter.start();
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!projectLock.hasQueuedThread(waiter)) {
                if (System.nanoTime() > deadline) {
                    fail("the tcc round never queued on the held project lock");
                }
                if (!waiter.isAlive()) {
                    fail("the tcc round died before reaching the project lock");
                }
                Thread.sleep(10);
            }
            assertEquals(0, factoryCalls.get(), "the native layer must stay untouched while the project lock is held");
            // Handover: with the lock released the round runs to completion on its own.
            projectLock.unlock();
            waiter.join(Duration.ofMinutes(1));
            assertFalse(waiter.isAlive(), "the tcc round must converge after the lock is released");
        } finally {
            // Idempotent cleanup on every exit path: release the lock if still held, and never
            // leave a live worker behind (it could otherwise leak the shared static tcc global
            // lock into sibling test classes). Best-effort only — a primary failure wins.
            if (projectLock.isHeldByCurrentThread()) {
                projectLock.unlock();
            }
            if (waiter.isAlive()) {
                waiter.interrupt();
                try {
                    waiter.join(Duration.ofSeconds(30));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        var result = resultRef.get();
        assertNotNull(result);
        assertTrue(result.success(), () -> "tcc round failed after the lock handover:\n" + result.buildLog());
        assertEquals(1, factoryCalls.get(), "the native round runs exactly once after acquiring both locks");
    }

    @Test
    void alternatingRealBackendsOnOneOutputStayCoherent(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(ZigUtil.findZig() != null, "zig is required for the zig legs");
        var bundleRoot = TinyCcCliTestSupport.findTinyCcBundleRoot();
        Assumptions.assumeTrue(bundleRoot != null, "no tinycc bundle available; skipping the coherence rounds");
        var host = TargetPlatform.getNativePlatform();
        var projectDir = Files.createDirectories(tempDir.resolve("project"));
        var tu = Files.writeString(projectDir.resolve("entry.c"), "int gdextension_entry(void) { return 1; }\n");
        var zig = new ZigCcCompiler();
        var tcc = new TinyCcCompiler(() -> bundleRoot, TinyCcFfmNative::create);
        var artifact = projectDir.resolve(host.sharedLibraryFileName("shared"));

        // Back-to-back rounds of both backends on the same output name: every artifact must be a
        // complete, well-formed image — never a truncated mix of two writers.
        for (var round = 0; round < 3; round++) {
            final var roundNo = round;
            var zigResult = zig.compile(projectDir, List.of(), List.of(tu), "shared", COptimizationLevel.DEBUG, host);
            assertTrue(zigResult.success(), () -> "zig round " + roundNo + " failed:\n" + zigResult.buildLog());
            NativeArtifactInspector.assertSharedLibraryShape(artifact);
            NativeArtifactInspector.assertExportsSymbol(artifact, "gdextension_entry");

            var tccResult = tcc.compile(projectDir, List.of(), List.of(tu), "shared", COptimizationLevel.DEBUG, host);
            assertTrue(tccResult.success(), () -> "tcc round " + roundNo + " failed:\n" + tccResult.buildLog());
            NativeArtifactInspector.assertSharedLibraryShape(artifact);
            NativeArtifactInspector.assertExportsSymbol(artifact, "gdextension_entry");
        }
    }

    /// Probe state for the blocking-native test: the first (only) round parks inside addFile
    /// until released, holding both locks.
    private static final class BlockingNativeProbe implements TinyCcNative {
        final CountDownLatch insideAddFile = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger factoryCalls = new AtomicInteger();

        @Override
        public @Nullable MemorySegment newState() {
            return MemorySegment.ofAddress(1);
        }

        @Override
        public void delete(@NotNull MemorySegment state) {
        }

        @Override
        public void setLibPath(@NotNull MemorySegment state, @NotNull String path) {
        }

        @Override
        public int setOptions(@NotNull MemorySegment state, @NotNull String options) {
            return 0;
        }

        @Override
        public int setOutputType(@NotNull MemorySegment state, int outputType) {
            return 0;
        }

        @Override
        public int addIncludePath(@NotNull MemorySegment state, @NotNull String path) {
            return 0;
        }

        @Override
        public int addFile(@NotNull MemorySegment state, @NotNull String file) {
            insideAddFile.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the parked round");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the parked round was interrupted", e);
            }
            return 0;
        }

        @Override
        public int outputFile(@NotNull MemorySegment state, @NotNull String file) {
            WritingFakeNative.writeArtifact(file);
            return 0;
        }

        @Override
        public void setErrorHandler(@NotNull MemorySegment state, @NotNull ErrorHandler handler) {
        }

        @Override
        public @Nullable Throwable callbackFailure() {
            return null;
        }
    }

    /// Minimal fake that succeeds and writes a marker artifact at output time.
    private static final class WritingFakeNative implements TinyCcNative {
        static void writeArtifact(@NotNull String file) {
            try {
                Files.writeString(Path.of(file), "fake shared object");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public @Nullable MemorySegment newState() {
            return MemorySegment.ofAddress(1);
        }

        @Override
        public void delete(@NotNull MemorySegment state) {
        }

        @Override
        public void setLibPath(@NotNull MemorySegment state, @NotNull String path) {
        }

        @Override
        public int setOptions(@NotNull MemorySegment state, @NotNull String options) {
            return 0;
        }

        @Override
        public int setOutputType(@NotNull MemorySegment state, int outputType) {
            return 0;
        }

        @Override
        public int addIncludePath(@NotNull MemorySegment state, @NotNull String path) {
            return 0;
        }

        @Override
        public int addFile(@NotNull MemorySegment state, @NotNull String file) {
            return 0;
        }

        @Override
        public int outputFile(@NotNull MemorySegment state, @NotNull String file) {
            writeArtifact(file);
            return 0;
        }

        @Override
        public void setErrorHandler(@NotNull MemorySegment state, @NotNull ErrorHandler handler) {
        }

        @Override
        public @Nullable Throwable callbackFailure() {
            return null;
        }
    }
}
