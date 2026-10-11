package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Behavioral anchor for [TinyCcCompiler] driven by a pure-Java [TinyCcNative] fake — no native
/// library, no bundle, so nothing here is environment-gated beyond the host-platform check the
/// compiler itself enforces. Anchored contracts:
/// - every libtcc `int` result aborts the round on `!= 0` (including `tcc_add_file`'s -2/-3),
///   and no later step runs after a failure;
/// - the diagnostic-callback failure flag fails the round even when every native call returned
///   0 and the artifact exists on disk;
/// - cleanup order is always `tcc_delete` while the round arena is still alive, then arena close;
/// - cancellation is cooperative (lock waits, TU boundary, before output) and always surfaces as
///   the fixed `Failed to run tcc: interrupted` channel with the interrupt status restored;
/// - a stale artifact is deleted before output, so a failed output never masquerades as success;
/// - lock order during a round: per-project build lock and the tcc global lock are both held.
class TinyCcCompilerNativeFakeTest {

    /// The compiler refuses non-host-native or unsupported targets before any native work, so
    /// these tests must run on a supported host to reach the behavior they anchor.
    private static @NotNull TargetPlatform assumeSupportedHost() {
        var host = TargetPlatform.getNativePlatform();
        Assumptions.assumeTrue(host == TargetPlatform.LINUX_X86_64 || host == TargetPlatform.WINDOWS_X86_64,
                "tinycc supports only linux/windows x86_64 hosts");
        return host;
    }

    private static @NotNull TinyCcCompiler newCompiler(@NotNull AtomicReference<@Nullable FakeTinyCcNative> created, @NotNull Consumer<FakeTinyCcNative> program) {
        return new TinyCcCompiler(() -> Path.of("unused-bundle-root"), (bundleRoot, targetPlatform, roundArena) -> {
            var fake = new FakeTinyCcNative(roundArena);
            program.accept(fake);
            created.set(fake);
            return fake;
        });
    }

    private static @NotNull Path writeTu(@NotNull Path dir, @NotNull String name) throws IOException {
        var file = dir.resolve(name);
        Files.writeString(file, "int " + name.replace('.', '_') + "(void) { return 0; }\n");
        return file;
    }

    /// A step that returned non-zero aborts the round immediately: no later step runs, the state
    /// is still deleted while the arena is alive, and the arena is closed afterwards.
    @ParameterizedTest(name = "{0}")
    @MethodSource("stepFailures")
    void stepFailureAbortsRound(@NotNull StepFailure scenario, @TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var includeDir = Files.createDirectories(tempDir.resolve("include"));
        var tuA = writeTu(tempDir, "a.c");
        var tuB = writeTu(tempDir, "b.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(created, scenario.program());

        var result = compiler.compile(tempDir, List.of(includeDir), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty());
        assertTrue(result.buildLog().contains(scenario.channelFragment()),
                () -> "the failure channel must name the failed step, got:\n" + result.buildLog());
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(scenario.expectedCalls(), fake.calls, "a failed step must abort the round without running later steps");
        assertTrue(fake.arenaAliveAtDelete, "tcc_delete must run while the round arena is still alive");
        assertFalse(fake.roundArena.scope().isAlive(), "the round arena must be closed after tcc_delete");
    }

    static @NotNull List<StepFailure> stepFailures() {
        var setupPrefix = List.of("newState", "setErrorHandler", "setLibPath", "setOptions", "setOutputType", "addIncludePath");
        return List.of(
                new StepFailure("setOptions", fake -> fake.optionsStatus = -1,
                        List.of("newState", "setErrorHandler", "setLibPath", "setOptions", "delete"), "tcc_set_options"),
                new StepFailure("setOutputType", fake -> fake.outputTypeStatus = -1,
                        List.of("newState", "setErrorHandler", "setLibPath", "setOptions", "setOutputType", "delete"), "tcc_set_output_type"),
                new StepFailure("addIncludePath", fake -> fake.includePathStatus = -1,
                        concat(setupPrefix, "delete"), "tcc_add_include_path"),
                // FILE_NOT_RECOGNIZED (-3) proves any non-zero status aborts, not only -1.
                new StepFailure("addFile", fake -> fake.fileStatuses.addAll(List.of(0, -3)),
                        concat(setupPrefix, "addFile", "addFile", "delete"), "tcc_add_file"),
                new StepFailure("outputFile", fake -> fake.outputStatus = -1,
                        concat(setupPrefix, "addFile", "addFile", "outputFile", "delete"), "tcc_output_file")
        );
    }

    private record StepFailure(@NotNull String name, @NotNull Consumer<FakeTinyCcNative> program,
                               @NotNull List<String> expectedCalls, @NotNull String channelFragment) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static @NotNull List<String> concat(@NotNull List<String> prefix, @NotNull String... tail) {
        var calls = new ArrayList<>(prefix);
        calls.addAll(List.of(tail));
        return calls;
    }

    @Test
    void successfulRoundRunsEveryStepInOrder(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var includeA = Files.createDirectories(tempDir.resolve("inc_a"));
        var includeB = Files.createDirectories(tempDir.resolve("inc_b"));
        var tuA = writeTu(tempDir, "a.c");
        var tuB = writeTu(tempDir, "b.c");
        var bundleRoot = tempDir.resolve("bundle");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = new TinyCcCompiler(() -> bundleRoot, (root, targetPlatform, roundArena) -> {
            var fake = new FakeTinyCcNative(roundArena);
            created.set(fake);
            return fake;
        });

        var result = compiler.compile(tempDir, List.of(includeA, includeB), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "fake round failed:\n" + result.buildLog());
        var expectedOutput = tempDir.resolve(host.sharedLibraryFileName("probe")).toAbsolutePath();
        assertEquals(List.of(expectedOutput), result.artifacts());
        assertEquals("", result.buildLog(), "a clean round without diagnostics must produce an empty build log");
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(List.of("newState", "setErrorHandler", "setLibPath", "setOptions", "setOutputType",
                "addIncludePath", "addIncludePath", "addFile", "addFile", "outputFile", "delete"), fake.calls);
        assertEquals(bundleRoot.toString(), fake.libPathSeen, "tcc_set_lib_path must point at the bundle root");
        assertEquals("-std=c11", fake.optionsSeen);
        assertEquals(TinyCcLibrary.TCC_OUTPUT_DLL, fake.outputTypeSeen);
        assertEquals(List.of(includeA.toString(), includeB.toString()), fake.includePathsSeen,
                "include paths are added in input order");
        assertEquals(List.of(tuA.toString(), tuB.toString()), fake.filesSeen, "translation units compile in cFiles order");
        assertEquals(expectedOutput.toString(), fake.outputPathSeen);
        assertTrue(fake.arenaAliveAtDelete);
        assertFalse(fake.roundArena.scope().isAlive());
    }

    @Test
    void diagnosticsArePassedIntoTheBuildLog(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(created, fake -> fake.diagnosticOnAddFile = "a.c:1: warning: suspicious");

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "fake round failed:\n" + result.buildLog());
        assertEquals("a.c:1: warning: suspicious\n", result.buildLog(),
                "diagnostics keep their native file:line: prefix, one per line");
    }

    @Test
    void callbackFailureFailsTheRoundEvenWhenNativeSucceededAndArtifactExists(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(created, fake -> fake.callbackFailureAtStep = "outputFile");

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success(), "a broken diagnostic upcall must fail the round despite native success");
        assertTrue(result.artifacts().isEmpty(), "the artifact on disk must never be reported after a callback failure");
        assertTrue(result.buildLog().contains("diagnostic callback failed"), () -> "got:\n" + result.buildLog());
        var fake = created.get();
        assertNotNull(fake);
        // The artifact really was written by the fake — the failure is purely the upcall flag.
        assertTrue(Files.isRegularFile(tempDir.resolve(host.sharedLibraryFileName("probe"))));
        assertTrue(fake.calls.contains("outputFile"));
    }

    @Test
    void callbackFailureDuringSetupAbortsBeforeAnyFileIsAdded(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(created, fake -> fake.callbackFailureAtStep = "setOptions");

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        var fake = created.get();
        assertNotNull(fake);
        assertFalse(fake.calls.contains("addFile"), "the callback failure flag is checked after every downcall");
        assertFalse(fake.calls.contains("outputFile"));
    }

    @Test
    void nullStateFailsBeforeAnySetupRuns(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(created, fake -> fake.nullState = true);

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertTrue(result.buildLog().contains("tcc_new"), () -> "got:\n" + result.buildLog());
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(List.of("newState"), fake.calls, "a NULL state has nothing to delete and nothing to set up");
        assertFalse(fake.roundArena.scope().isAlive(), "the arena is still closed");
    }

    @Test
    void staleArtifactIsDeletedAndMissingOutputAfterSuccessStatusFails(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var output = tempDir.resolve(host.sharedLibraryFileName("probe"));
        Files.writeString(output, "stale artifact from an earlier round");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        // outputFile reports success but writes nothing: with the stale artifact gone, the
        // post-output existence check must fail the round instead of trusting the status.
        var compiler = newCompiler(created, fake -> fake.writeOutputFile = false);

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty());
        assertFalse(Files.exists(output), "the stale artifact must be deleted before output runs");
        assertTrue(result.buildLog().contains("was not created"), () -> "got:\n" + result.buildLog());
    }

    @Test
    void staleArtifactDoesNotMaskOutputFailure(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var output = tempDir.resolve(host.sharedLibraryFileName("probe"));
        Files.writeString(output, "stale artifact from an earlier round");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        // The stale artifact is deleted before output and output then fails without writing:
        // the round must fail with empty artifacts and no file left to masquerade as success.
        var compiler = newCompiler(created, fake -> {
            fake.outputStatus = -1;
            fake.writeOutputFile = false;
        });

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty());
        assertFalse(Files.exists(output), "the stale artifact must be deleted and the failed output must not recreate it");
    }

    @Test
    void emptyCFilesFailBeforeAnyNativeWork(@TempDir Path tempDir) {
        var host = assumeSupportedHost();
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(created, fake -> fail("the native factory must not run for an empty input set"));

        var result = compiler.compile(tempDir, List.of(), List.of(), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertEquals("No C files to compile", result.buildLog());
        assertTrue(result.artifacts().isEmpty());
        assertNull(created.get());
    }

    @Test
    void bundleResolutionFailureCarriesTheResolverMessage(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = new TinyCcCompiler(() -> {
            throw new IOException("no bundle on the classpath; point GDCC_TINYCC_HOME at a bundle directory");
        }, (root, targetPlatform, roundArena) -> {
            fail("the native factory must not run when bundle resolution failed");
            throw new IOException("unreachable");
        });

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertTrue(result.buildLog().contains("GDCC_TINYCC_HOME"),
                () -> "the failure must explain the bundle override mechanism:\n" + result.buildLog());
        assertTrue(result.artifacts().isEmpty());
        assertNull(created.get());
    }

    @Test
    void interruptDuringBundleResolutionSurfacesTheFixedChannel(@TempDir Path tempDir) throws Exception {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        // TinyCcBundle's install-lock waits restore the interrupt status and then throw an
        // IOException; the compiler must route that to the fixed cancellation channel.
        var compiler = new TinyCcCompiler(() -> {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the tinycc bundle install lock");
        }, (root, targetPlatform, roundArena) -> {
            throw new AssertionError("the native factory must not run when bundle resolution failed");
        });

        var round = runOnOwnThread(() -> compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host));
        round.joinUninterrupted();

        var result = round.result();
        assertNotNull(result);
        assertFalse(result.success());
        assertEquals("Failed to run tcc: interrupted", result.buildLog());
        assertTrue(result.artifacts().isEmpty());
        assertTrue(round.interruptRestored(), "the interrupt status must be restored for the caller");
        assertNull(created.get());
    }

    @Test
    void nonHostTargetFailsFastBeforeAnyNativeWork(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var otherTarget = host == TargetPlatform.LINUX_X86_64 ? TargetPlatform.WINDOWS_X86_64 : TargetPlatform.LINUX_X86_64;
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(created, fake -> fail("the native factory must not run for an unsupported target"));

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, otherTarget);

        assertFalse(result.success());
        assertTrue(result.buildLog().contains("use the zig backend"), () -> "got:\n" + result.buildLog());
        assertTrue(result.artifacts().isEmpty());
        assertNull(created.get());
    }

    @Test
    void interruptBetweenTranslationUnitsAbortsTheRound(@TempDir Path tempDir) throws Exception {
        var host = assumeSupportedHost();
        var tuA = writeTu(tempDir, "a.c");
        var tuB = writeTu(tempDir, "b.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(created, fake -> fake.interruptOnFileIndex = 0);

        var round = runOnOwnThread(() -> compiler.compile(tempDir, List.of(), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host));
        round.joinUninterrupted();

        var result = round.result();
        assertNotNull(result);
        assertFalse(result.success());
        assertEquals("Failed to run tcc: interrupted", result.buildLog());
        assertTrue(result.artifacts().isEmpty());
        assertTrue(round.interruptRestored(), "the interrupt status must be restored for the caller");
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(1, fake.filesSeen.size(), "the second TU must never start after the interrupt");
        assertFalse(fake.calls.contains("outputFile"));
    }

    @Test
    void interruptAfterTheFinalTuAbortsBeforeOutput(@TempDir Path tempDir) throws Exception {
        var host = assumeSupportedHost();
        var tuA = writeTu(tempDir, "a.c");
        var tuB = writeTu(tempDir, "b.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(created, fake -> fake.interruptOnFileIndex = 1);

        var round = runOnOwnThread(() -> compiler.compile(tempDir, List.of(), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host));
        round.joinUninterrupted();

        var result = round.result();
        assertNotNull(result);
        assertFalse(result.success());
        assertEquals("Failed to run tcc: interrupted", result.buildLog());
        assertTrue(round.interruptRestored());
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(2, fake.filesSeen.size(), "all TUs compiled; only the output step is skipped");
        assertFalse(fake.calls.contains("outputFile"), "the pre-output interrupt check must stop the round");
        assertTrue(fake.deleteCalled, "the state is still deleted on the cancellation path");
    }

    @Test
    void interruptWhileWaitingForTheGlobalLockAbortsTheRound(@TempDir Path tempDir) throws Exception {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(created, fake -> {
        });
        var globalLock = TinyCcCompiler.tccGlobalLock();
        globalLock.lock();
        var round = runOnOwnThread(() -> compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host));
        try {
            awaitQueued(globalLock, round.thread());
            round.thread().interrupt();
            round.joinUninterrupted();
            var result = round.result();
            assertNotNull(result);
            assertFalse(result.success());
            assertEquals("Failed to run tcc: interrupted", result.buildLog());
            assertTrue(round.interruptRestored());
            assertNull(created.get(), "a round cancelled at the lock never reaches the native factory");
        } finally {
            globalLock.unlock();
            round.joinUninterrupted();
        }
        // The lock stays usable: a later round runs normally.
        var followUp = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);
        assertTrue(followUp.success(), () -> "round after lock release failed:\n" + followUp.buildLog());
    }

    @Test
    void interruptWhileWaitingForTheProjectLockAbortsTheRound(@TempDir Path tempDir) throws Exception {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(created, fake -> {
        });
        var projectLock = CProjectBuildLock.require(tempDir);
        projectLock.lock();
        var round = runOnOwnThread(() -> compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host));
        try {
            awaitQueued(projectLock, round.thread());
            round.thread().interrupt();
            round.joinUninterrupted();
            var result = round.result();
            assertNotNull(result);
            assertFalse(result.success());
            assertEquals("Failed to run tcc: interrupted", result.buildLog());
            assertTrue(round.interruptRestored());
        } finally {
            projectLock.unlock();
            round.joinUninterrupted();
        }
    }

    @Test
    void bothLocksAreHeldForTheWholeRound(@TempDir Path tempDir) throws Exception {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var insideAddFile = new CountDownLatch(1);
        var releaseAddFile = new CountDownLatch(1);
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(created, fake -> fake.addFileBlock = new CountDownLatch[]{insideAddFile, releaseAddFile});
        var round = runOnOwnThread(() -> compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host));
        try {
            assertTrue(insideAddFile.await(10, TimeUnit.SECONDS), "the round never reached addFile");
            assertTrue(CProjectBuildLock.require(tempDir).isLocked(), "the per-project build lock must be held during the round");
            assertTrue(TinyCcCompiler.tccGlobalLock().isLocked(), "the tcc global lock must be held during the round");
        } finally {
            releaseAddFile.countDown();
            round.joinUninterrupted();
        }
        var result = round.result();
        assertNotNull(result);
        assertTrue(result.success(), () -> "blocked round failed:\n" + result.buildLog());
    }

    private static void awaitQueued(@NotNull java.util.concurrent.locks.ReentrantLock lock, @NotNull Thread waiter) {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!lock.hasQueuedThreads()) {
            if (System.nanoTime() > deadline) {
                fail("the waiting round never blocked on the lock");
            }
            if (!waiter.isAlive()) {
                fail("the waiting round died before blocking on the lock");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("test interrupted while awaiting the lock queue");
            }
        }
    }

    /// Runs one compile on its own platform thread so the interrupt flag of the test thread is
    /// never involved and the round's final interrupt status can be asserted.
    private static @NotNull RoundThread runOnOwnThread(@NotNull java.util.concurrent.Callable<CCompileResult> round) {
        var roundThread = new RoundThread(round);
        roundThread.thread().start();
        return roundThread;
    }

    private static final class RoundThread {
        private final Thread thread;
        private final AtomicReference<@Nullable CCompileResult> result = new AtomicReference<>();
        private final AtomicBoolean interruptRestored = new AtomicBoolean();

        RoundThread(java.util.concurrent.@NotNull Callable<CCompileResult> round) {
            this.thread = Thread.ofPlatform().name("gdcc-test-tinycc-round").unstarted(() -> {
                try {
                    result.set(round.call());
                } catch (Exception e) {
                    throw new RuntimeException("the compile round threw instead of reporting a result", e);
                }
                interruptRestored.set(Thread.currentThread().isInterrupted());
            });
        }

        @NotNull Thread thread() {
            return thread;
        }

        @Nullable CCompileResult result() {
            return result.get();
        }

        boolean interruptRestored() {
            return interruptRestored.get();
        }

        void joinUninterrupted() {
            try {
                thread.join(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("test interrupted while joining the compile round");
            }
            assertFalse(thread.isAlive(), "the compile round must converge");
        }
    }

    /// Records every call in order and fails exactly where a test programs it to. `delete`
    /// captures whether the round arena was still alive, pinning the cleanup order
    /// (delete → arena close) without any real native library.
    private static final class FakeTinyCcNative implements TinyCcNative {
        final Arena roundArena;
        final List<String> calls = new ArrayList<>();
        final List<String> includePathsSeen = new ArrayList<>();
        final List<String> filesSeen = new ArrayList<>();
        final Deque<Integer> fileStatuses = new ArrayDeque<>();
        boolean nullState;
        int optionsStatus;
        int outputTypeStatus;
        int includePathStatus;
        int outputStatus;
        boolean writeOutputFile = true;
        /// When set, `callbackFailure()` starts reporting a failure after this step ran.
        @Nullable String callbackFailureAtStep;
        /// When set, the fake interrupts the compile thread while adding the file at this index.
        int interruptOnFileIndex = -1;
        /// When set, addFile signals the first latch then awaits the second — a deterministic
        /// window for the test to observe the round's lock state.
        CountDownLatch @Nullable [] addFileBlock;
        /// When set, the error handler is invoked with this message during addFile.
        @Nullable String diagnosticOnAddFile;
        @Nullable String libPathSeen;
        @Nullable String optionsSeen;
        int outputTypeSeen;
        @Nullable String outputPathSeen;
        boolean deleteCalled;
        boolean arenaAliveAtDelete;
        private @Nullable ErrorHandler errorHandler;
        private boolean callbackFailed;

        FakeTinyCcNative(@NotNull Arena roundArena) {
            this.roundArena = roundArena;
        }

        private void step(@NotNull String name) {
            calls.add(name);
            if (name.equals(callbackFailureAtStep)) {
                callbackFailed = true;
            }
        }

        @Override
        public @Nullable MemorySegment newState() {
            step("newState");
            return nullState ? null : MemorySegment.ofAddress(1);
        }

        @Override
        public void delete(@NotNull MemorySegment state) {
            step("delete");
            deleteCalled = true;
            arenaAliveAtDelete = roundArena.scope().isAlive();
        }

        @Override
        public void setLibPath(@NotNull MemorySegment state, @NotNull String path) {
            step("setLibPath");
            libPathSeen = path;
        }

        @Override
        public int setOptions(@NotNull MemorySegment state, @NotNull String options) {
            step("setOptions");
            optionsSeen = options;
            return optionsStatus;
        }

        @Override
        public int setOutputType(@NotNull MemorySegment state, int outputType) {
            step("setOutputType");
            outputTypeSeen = outputType;
            return outputTypeStatus;
        }

        @Override
        public int addIncludePath(@NotNull MemorySegment state, @NotNull String path) {
            step("addIncludePath");
            includePathsSeen.add(path);
            return includePathStatus;
        }

        @Override
        public int addFile(@NotNull MemorySegment state, @NotNull String file) {
            step("addFile");
            filesSeen.add(file);
            if (diagnosticOnAddFile != null && errorHandler != null) {
                errorHandler.onError(diagnosticOnAddFile);
            }
            if (addFileBlock != null) {
                addFileBlock[0].countDown();
                try {
                    if (!addFileBlock[1].await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never released the addFile block");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("the addFile block was interrupted", e);
                }
            }
            if (filesSeen.size() - 1 == interruptOnFileIndex) {
                Thread.currentThread().interrupt();
            }
            var status = fileStatuses.poll();
            return status != null ? status : 0;
        }

        @Override
        public int outputFile(@NotNull MemorySegment state, @NotNull String file) {
            step("outputFile");
            outputPathSeen = file;
            if (writeOutputFile) {
                try {
                    Files.writeString(Path.of(file), "fake shared object");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return outputStatus;
        }

        @Override
        public void setErrorHandler(@NotNull MemorySegment state, @NotNull ErrorHandler handler) {
            step("setErrorHandler");
            errorHandler = handler;
        }

        @Override
        public @Nullable Throwable callbackFailure() {
            return callbackFailed ? new RuntimeException("programmed diagnostic callback failure") : null;
        }
    }
}
