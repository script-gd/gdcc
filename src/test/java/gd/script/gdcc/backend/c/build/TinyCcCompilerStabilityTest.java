package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Stability gate for [TinyCcCompiler] under alternating success / compile-failure / cancellation
/// rounds against the real libtcc: every round must converge to its expected result shape, and
/// neither the FFM per-round arenas nor libtcc state may leak across rounds. Cancel rounds are
/// deterministic — the wrapping [TinyCcNative] interrupts the round thread at the second TU
/// boundary, so a partially populated state goes through the `tcc_delete` → arena-close cleanup
/// path on every cycle.
///
/// Observation channels: post-GC heap used and the `/proc/self/fd` descriptor count (Linux; the
/// sharp handle-leak anchor — Windows has no equivalent JDK surface, so that leg is skipped
/// there). Committed VM is printed but deliberately NOT asserted: it is a process-wide high-water
/// metric that sibling test classes in the shared Gradle test JVM can move arbitrarily during the
/// measurement window, while the Java heap after an explicit GC and the fd count are attributable
/// to this test alone (verified: +0K committed when the class runs isolated).
class TinyCcCompilerStabilityTest {

    private static final int WARMUP_CYCLES = 3;
    private static final int MEASURED_CYCLES = 12;
    private static final long MAX_HEAP_GROWTH_BYTES = 32L * 1024 * 1024;
    private static final long MAX_FD_GROWTH = 2;

    @TempDir
    private static Path sharedDir;

    private static Path bundleRoot;

    @BeforeAll
    static void requireBundle() {
        bundleRoot = TinyCcCliTestSupport.findTinyCcBundleRoot();
        Assumptions.assumeTrue(bundleRoot != null,
                "no tinycc bundle available (GDCC_TINYCC_HOME unset, no build/tinycc-bundle); skipping the stability rounds");
    }

    @Test
    void alternatingSuccessFailureCancelRoundsDoNotLeakMemoryOrHandles() throws IOException {
        var host = TargetPlatform.getNativePlatform();
        var tuA = Files.writeString(sharedDir.resolve("round_a.c"), "int gdcc_stability_a(void) { return 1; }\n");
        var tuB = Files.writeString(sharedDir.resolve("round_b.c"), "int gdcc_stability_b(void) { return 2; }\n");
        var broken = Files.writeString(sharedDir.resolve("round_broken.c"), "int broken(\n");
        var goodTus = List.of(tuA, tuB);
        var brokenTus = List.of(tuA, broken);

        var compiler = new TinyCcCompiler(() -> bundleRoot, TinyCcFfmNative::create);
        var cancellingCompiler = new TinyCcCompiler(() -> bundleRoot,
                (root, platform, arena) -> new InterruptAtSecondFile(TinyCcFfmNative.create(root, platform, arena)));

        for (var cycle = 0; cycle < WARMUP_CYCLES; cycle++) {
            runCycle(compiler, cancellingCompiler, host, goodTus, brokenTus);
        }
        var baseline = Sample.take();

        for (var cycle = 0; cycle < MEASURED_CYCLES; cycle++) {
            runCycle(compiler, cancellingCompiler, host, goodTus, brokenTus);
        }
        assertFalse(Thread.currentThread().isInterrupted(),
                "the test thread must not leak the cancel rounds' interrupt status");
        var after = Sample.take();

        System.out.printf("[gdcc-tcc-stability] cycles=%d heap=%+dK committed=%+dK fds=%+d%n",
                MEASURED_CYCLES,
                (after.heapUsed - baseline.heapUsed) / 1024,
                (after.committedVm - baseline.committedVm) / 1024,
                (after.openFds == -1 || baseline.openFds == -1) ? 0 : after.openFds - baseline.openFds);
        assertTrue(after.heapUsed - baseline.heapUsed <= MAX_HEAP_GROWTH_BYTES,
                () -> "heap grew by " + (after.heapUsed - baseline.heapUsed) + " bytes over " + MEASURED_CYCLES + " cycles");
        if (baseline.openFds >= 0 && after.openFds >= 0) {
            assertTrue(after.openFds - baseline.openFds <= MAX_FD_GROWTH,
                    () -> "open file descriptors grew from " + baseline.openFds + " to " + after.openFds);
        }
    }

    private static void runCycle(
            @NotNull TinyCcCompiler compiler,
            @NotNull TinyCcCompiler cancellingCompiler,
            @NotNull TargetPlatform host,
            @NotNull List<Path> goodTus,
            @NotNull List<Path> brokenTus
    ) {
        var success = compiler.compile(sharedDir, List.of(), goodTus, "stability", COptimizationLevel.DEBUG, host);
        assertTrue(success.success(), () -> "success round failed:\n" + success.buildLog());
        assertTrue(Files.isRegularFile(success.artifacts().getFirst()));

        var failure = compiler.compile(sharedDir, List.of(), brokenTus, "stability", COptimizationLevel.DEBUG, host);
        assertFalse(failure.success());
        assertTrue(failure.artifacts().isEmpty());
        assertTrue(failure.buildLog().contains("error"), () -> "failure round must carry diagnostics:\n" + failure.buildLog());

        final CCompileResult cancelled;
        final boolean interruptRestored;
        try {
            cancelled = cancellingCompiler.compile(sharedDir, List.of(), goodTus, "stability", COptimizationLevel.DEBUG, host);
            // Capture and clear the restored interrupt status BEFORE any assertion: the wrapper
            // interrupts this thread mid-round, and letting the status survive an assertion failure
            // would poison every later test on this executor thread.
            interruptRestored = Thread.interrupted();
        } finally {
            Thread.interrupted();
        }
        assertFalse(cancelled.success());
        assertTrue(cancelled.artifacts().isEmpty());
        assertEquals("Failed to run tcc: interrupted", cancelled.buildLog());
        assertTrue(interruptRestored, "the cancel round must leave the interrupt status restored");
    }

    /// A measurement snapshot after an explicit GC. `openFds` is -1 off Linux.
    private record Sample(long heapUsed, long committedVm, long openFds) {
        static @NotNull Sample take() {
            System.gc();
            System.gc();
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            var committed = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
                    ? os.getCommittedVirtualMemorySize()
                    : -1;
            return new Sample(heap, committed, openFileDescriptors());
        }

        /// `/proc/self/fd` counting keeps this free of JDK-internal management module wiring.
        private static long openFileDescriptors() {
            var fdDir = Path.of("/proc/self/fd");
            if (!Files.isDirectory(fdDir)) {
                return -1;
            }
            try (var stream = Files.list(fdDir)) {
                return stream.count();
            } catch (IOException e) {
                return -1;
            }
        }
    }

    /// Interrupts the round thread at the second `tcc_add_file` boundary: the interrupt flag is
    /// then observed by the compiler loop before the output step, deterministically exercising the
    /// mid-round cancellation cleanup with a partially populated state.
    private static final class InterruptAtSecondFile implements TinyCcNative {
        private final TinyCcNative delegate;
        private int addFileCalls;

        InterruptAtSecondFile(@NotNull TinyCcNative delegate) {
            this.delegate = delegate;
        }

        @Override
        public @Nullable MemorySegment newState() {
            return delegate.newState();
        }

        @Override
        public void delete(@NotNull MemorySegment state) {
            delegate.delete(state);
        }

        @Override
        public void setLibPath(@NotNull MemorySegment state, @NotNull String path) {
            delegate.setLibPath(state, path);
        }

        @Override
        public int setOptions(@NotNull MemorySegment state, @NotNull String options) {
            return delegate.setOptions(state, options);
        }

        @Override
        public int setOutputType(@NotNull MemorySegment state, int outputType) {
            return delegate.setOutputType(state, outputType);
        }

        @Override
        public int addIncludePath(@NotNull MemorySegment state, @NotNull String path) {
            return delegate.addIncludePath(state, path);
        }

        @Override
        public int addFile(@NotNull MemorySegment state, @NotNull String file) {
            var status = delegate.addFile(state, file);
            if (++addFileCalls == 2) {
                Thread.currentThread().interrupt();
            }
            return status;
        }

        @Override
        public int outputFile(@NotNull MemorySegment state, @NotNull String file) {
            return delegate.outputFile(state, file);
        }

        @Override
        public void setErrorHandler(@NotNull MemorySegment state, @NotNull ErrorHandler handler) {
            delegate.setErrorHandler(state, handler);
        }

        @Override
        public @Nullable Throwable callbackFailure() {
            return delegate.callbackFailure();
        }
    }
}
