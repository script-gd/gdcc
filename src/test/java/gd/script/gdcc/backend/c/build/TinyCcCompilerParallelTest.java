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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Concurrency contract of [TinyCcCompiler]: the tcc global lock serializes every libtcc round
/// (libtcc keeps process-global state), while independent project directories still proceed
/// concurrently up to that lock and each produce correct results. The class stays on the default
/// `same_thread` execution; all concurrency is created inside the tests (one dedicated pool per
/// test) so the shared static lock can never deadlock against a sibling test class.
class TinyCcCompilerParallelTest {

    /// Two rounds on DIFFERENT project directories running truly concurrently must never be
    /// inside libtcc at the same time. Deterministic: round A provably blocks inside the fake's
    /// addFile (holding the tcc global lock), and round B must then be observable as QUEUED on
    /// the global lock — if the lock did not serialize, B would enter libtcc while A is inside
    /// and the test fails instead of waiting out the race.
    @Test
    void globalLockSerializesConcurrentRounds(@TempDir Path tempDir) throws Exception {
        var host = assumeSupportedHost();
        var projectA = Files.createDirectories(tempDir.resolve("project_a"));
        var projectB = Files.createDirectories(tempDir.resolve("project_b"));
        var tuA = writeTu(projectA, "a.c");
        var tuB = writeTu(projectB, "b.c");
        var probe = new SerializationProbe();
        TinyCcCompiler.NativeFactory factory = (bundleRoot, targetPlatform, roundArena) -> {
            probe.active.incrementAndGet();
            return new BlockingFakeTinyCcNative(probe);
        };
        var compiler = new TinyCcCompiler(() -> tempDir, factory);

        // A dedicated two-thread pool: the common pool may have parallelism 1 on small hosts,
        // which would serialize the rounds even without the lock and void the teeth.
        var executor = Executors.newFixedThreadPool(2);
        final CCompileResult resultA;
        final CCompileResult resultB;
        Thread threadB = null;
        try {
            var roundA = CompletableFuture.supplyAsync(() ->
                    compiler.compile(projectA, List.of(), List.of(tuA), "parallel_a", COptimizationLevel.DEBUG, host), executor);
            assertTrue(probe.firstInsideAddFile.await(10, TimeUnit.SECONDS), "round A never reached addFile");

            threadB = Thread.ofPlatform().name("gdcc-test-tinycc-round-b").unstarted(() ->
                    probe.resultB.set(compiler.compile(projectB, List.of(), List.of(tuB), "parallel_b", COptimizationLevel.DEBUG, host)));
            threadB.start();
            var globalLock = TinyCcCompiler.tccGlobalLock();
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!globalLock.hasQueuedThread(threadB)) {
                if (probe.secondInsideAddFile.getCount() == 0) {
                    fail("round B entered libtcc while round A was inside — the global lock is not serializing");
                }
                if (System.nanoTime() > deadline) {
                    fail("round B never queued on the tcc global lock while round A held it");
                }
                if (!threadB.isAlive()) {
                    fail("round B died before reaching the tcc global lock");
                }
                Thread.sleep(10);
            }
            assertEquals(1, probe.active.get(), "only round A may be inside libtcc");
            probe.releaseFirst.countDown();
            resultA = roundA.get(2, TimeUnit.MINUTES);
            threadB.join(Duration.ofMinutes(2));
            resultB = probe.resultB.get();
        } finally {
            // Unpark and stop both workers on every exit path: a leaked round would keep holding
            // the shared static tcc global lock into sibling test classes. Best-effort only — a
            // primary failure above must not be masked by cleanup assertions.
            probe.releaseFirst.countDown();
            if (threadB != null) {
                threadB.interrupt();
            }
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
            if (threadB != null) {
                threadB.join(Duration.ofSeconds(30));
            }
        }

        assertTrue(resultA.success(), () -> "round A failed:\n" + resultA.buildLog());
        assertNotNull(resultB, "round B must complete after round A released the global lock");
        assertTrue(resultB.success(), () -> "round B failed:\n" + resultB.buildLog());
        assertTrue(Files.isRegularFile(resultA.artifacts().getFirst()));
        assertTrue(Files.isRegularFile(resultB.artifacts().getFirst()));
    }

    /// End-to-end: two projects compile concurrently against the real libtcc and each artifact
    /// exports only its own symbol.
    @Test
    void concurrentRoundsOnDifferentProjectsBothSucceed(@TempDir Path tempDir) throws Exception {
        var bundleRoot = TinyCcCliTestSupport.findTinyCcBundleRoot();
        Assumptions.assumeTrue(bundleRoot != null,
                "no tinycc bundle available; skipping the concurrent real-compile test");
        var host = TargetPlatform.getNativePlatform();
        var projectA = Files.createDirectories(tempDir.resolve("project_a"));
        var projectB = Files.createDirectories(tempDir.resolve("project_b"));
        var tuA = projectA.resolve("a.c");
        var tuB = projectB.resolve("b.c");
        Files.writeString(tuA, NativeArtifactInspector.EXPORT_MACRO_DEFINITION + "GDCC_TEST_EXPORT int gdcc_parallel_a(void) { return 1; }\n");
        Files.writeString(tuB, NativeArtifactInspector.EXPORT_MACRO_DEFINITION + "GDCC_TEST_EXPORT int gdcc_parallel_b(void) { return 2; }\n");
        var compiler = new TinyCcCompiler(() -> bundleRoot, TinyCcFfmNative::create);

        var executor = Executors.newFixedThreadPool(2);
        final CCompileResult resultA;
        final CCompileResult resultB;
        try {
            var roundA = CompletableFuture.supplyAsync(() ->
                    compiler.compile(projectA, List.of(), List.of(tuA), "parallel_a", COptimizationLevel.DEBUG, host), executor);
            var roundB = CompletableFuture.supplyAsync(() ->
                    compiler.compile(projectB, List.of(), List.of(tuB), "parallel_b", COptimizationLevel.DEBUG, host), executor);
            resultA = roundA.get(2, TimeUnit.MINUTES);
            resultB = roundB.get(2, TimeUnit.MINUTES);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(resultA.success(), () -> "round A failed:\n" + resultA.buildLog());
        assertTrue(resultB.success(), () -> "round B failed:\n" + resultB.buildLog());
        assertTrue(Files.isRegularFile(resultA.artifacts().getFirst()));
        assertTrue(Files.isRegularFile(resultB.artifacts().getFirst()));
        NativeArtifactInspector.assertExportsOnlyOwnSymbol(resultA.artifacts().getFirst(), "gdcc_parallel_a", "gdcc_parallel_b");
        NativeArtifactInspector.assertExportsOnlyOwnSymbol(resultB.artifacts().getFirst(), "gdcc_parallel_b", "gdcc_parallel_a");
    }

    private static @NotNull TargetPlatform assumeSupportedHost() {
        var host = TargetPlatform.getNativePlatform();
        Assumptions.assumeTrue(host == TargetPlatform.LINUX_X86_64 || host == TargetPlatform.WINDOWS_X86_64
                        || host == TargetPlatform.LINUX_AARCH64 || host == TargetPlatform.MACOS_AARCH64,
                "tinycc supports only linux-x86_64, windows-x86_64, linux-aarch64 and macos-aarch64 hosts");
        return host;
    }

    private static @NotNull Path writeTu(@NotNull Path dir, @NotNull String name) throws IOException {
        var file = dir.resolve(name);
        Files.writeString(file, "int " + name.replace('.', '_') + "(void) { return 0; }\n");
        return file;
    }

    /// Shared observation state for the serialization gate: `active` spans each round's native
    /// section (factory create → delete); the first round into addFile parks on `releaseFirst`
    /// while the test proves the second round is queued on the tcc global lock.
    private static final class SerializationProbe {
        final AtomicInteger active = new AtomicInteger();
        final CountDownLatch firstInsideAddFile = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        /// Counted down by any round that reaches addFile after the first parked there. It is
        /// only OBSERVED while the first round is parked: a countdown in that window means the
        /// global lock failed to serialize; a countdown after the release is the second round's
        /// normal progress and is never read.
        final CountDownLatch secondInsideAddFile = new CountDownLatch(1);
        final AtomicBoolean firstTaken = new AtomicBoolean();
        final AtomicReference<@Nullable CCompileResult> resultB = new AtomicReference<>();
    }

    /// A [TinyCcNative] whose first addFile call blocks until released; the second one signals
    /// `secondInsideAddFile` (unreachable when the global lock serializes the rounds).
    private static final class BlockingFakeTinyCcNative implements TinyCcNative {
        private final SerializationProbe probe;

        BlockingFakeTinyCcNative(@NotNull SerializationProbe probe) {
            this.probe = probe;
        }

        @Override
        public @Nullable MemorySegment newState() {
            return MemorySegment.ofAddress(1);
        }

        @Override
        public void delete(@NotNull MemorySegment state) {
            probe.active.decrementAndGet();
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
            if (probe.firstTaken.compareAndSet(false, true)) {
                probe.firstInsideAddFile.countDown();
                try {
                    if (!probe.releaseFirst.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never released the parked round");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("the parked round was interrupted", e);
                }
            } else {
                probe.secondInsideAddFile.countDown();
            }
            return 0;
        }

        @Override
        public int outputFile(@NotNull MemorySegment state, @NotNull String file) {
            try {
                Files.writeString(Path.of(file), "fake shared object");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
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
