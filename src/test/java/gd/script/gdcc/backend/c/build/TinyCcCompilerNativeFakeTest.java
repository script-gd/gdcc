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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Behavioral anchor for [TinyCcCompiler] driven by a pure-Java [TinyCcNative] fake — no native
/// library, so nothing here is environment-gated beyond the host-platform check the compiler
/// itself enforces. The bundle resolver points at a real temp directory carrying a `VERSION`
/// file so the object cache is available unless a test says otherwise. Anchored contracts:
/// - one round is per-TU fresh OBJ states plus one fresh DLL link state; a cache-hit TU never
///   creates an OBJ state;
/// - every libtcc `int` result aborts the round on `!= 0` and no later step runs after it;
/// - the diagnostic-callback failure flag fails the round even when every native call returned
///   0 and the artifact exists on disk;
/// - cleanup order is always `tcc_delete` while the round arena is still alive, then arena close;
/// - cancellation is cooperative (lock waits, before each TU's object compilation, before the
///   final output) and always surfaces as the fixed `Failed to run tcc: interrupted` channel;
/// - the cache degrades, never fails a build: round-level outage (VERSION/include tree/cache
///   root) emits one fallback line, a publish failure links the temp artifact, a linker
///   rejection of a cache-hit object triggers exactly one self-heal (evict, recompile, re-link
///   from a new state);
/// - the build log starts with the fixed statistics line when the cache is available;
/// - lock order during a round: per-project build lock and the tcc global lock are both held.
class TinyCcCompilerNativeFakeTest {

    private static final String STATS_PREFIX = "[gdcc] tcc object cache: ";
    private static final String FALLBACK_PREFIX = "[gdcc] tcc object cache unavailable this round: ";

    /// The compiler refuses non-host-native or unsupported targets before any native work, so
    /// these tests must run on a supported host to reach the behavior they anchor.
    private static @NotNull TargetPlatform assumeSupportedHost() {
        var host = TargetPlatform.getNativePlatform();
        Assumptions.assumeTrue(host == TargetPlatform.LINUX_X86_64 || host == TargetPlatform.WINDOWS_X86_64,
                "tinycc supports only linux/windows x86_64 hosts");
        return host;
    }

    /// A real directory with a `VERSION` file so key preparation succeeds and the cache is
    /// available; the cache root resolves to `<project>/compiler-cache` (no shared sibling).
    private static @NotNull Path newBundleRoot(@NotNull Path tempDir) throws IOException {
        var bundleRoot = Files.createDirectories(tempDir.resolve("bundle"));
        Files.writeString(bundleRoot.resolve(TinyCcBundle.VERSION_FILE_NAME), "bundleFormat=1\n");
        return bundleRoot;
    }

    private static @NotNull TinyCcCompiler newCompiler(@NotNull Path bundleRoot, @NotNull AtomicReference<@Nullable FakeTinyCcNative> created, @NotNull Consumer<FakeTinyCcNative> program) {
        return new TinyCcCompiler(() -> bundleRoot, (root, targetPlatform, roundArena) -> {
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

    /// A step that returned non-zero aborts the round immediately: no later step runs and every
    /// created state is deleted while the arena is alive.
    @ParameterizedTest(name = "{0}")
    @MethodSource("stepFailures")
    void stepFailureAbortsRound(@NotNull StepFailure scenario, @TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var includeDir = Files.createDirectories(tempDir.resolve("include"));
        var tuA = writeTu(tempDir, "a.c");
        var tuB = writeTu(tempDir, "b.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(newBundleRoot(tempDir), created, scenario.program());

        var result = compiler.compile(tempDir, List.of(includeDir), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty());
        assertTrue(result.buildLog().contains(scenario.channelFragment()),
                () -> "the failure channel must name the failed step, got:\n" + result.buildLog());
        var fake = created.get();
        assertNotNull(fake);
        scenario.verify().accept(fake);
        assertTrue(fake.arenaAliveAtLastDelete, "tcc_delete must run while the round arena is still alive");
        assertFalse(fake.roundArena.scope().isAlive(), "the round arena must be closed after tcc_delete");
    }

    private record StepFailure(@NotNull String name, @NotNull Consumer<FakeTinyCcNative> program,
                               @NotNull Consumer<FakeTinyCcNative> verify, @NotNull String channelFragment) {
        @Override
        public String toString() {
            return name;
        }
    }

    static @NotNull List<StepFailure> stepFailures() {
        var objSetupPrefix = List.of("setErrorHandler", "setLibPath", "setOptions", "setOutputType", "addIncludePath");
        return List.of(
                new StepFailure("objSetOptions", fake -> fake.optionsStatus = -1,
                        expectStates(List.of(concat(objSetupPrefixHead(), "delete"))), "tcc_set_options"),
                new StepFailure("objSetOutputType", fake -> fake.outputTypeStatus = -1,
                        expectStates(List.of(concat(List.of("setErrorHandler", "setLibPath", "setOptions", "setOutputType"), "delete"))), "tcc_set_output_type"),
                new StepFailure("objAddIncludePath", fake -> fake.includePathStatus = -1,
                        expectStates(List.of(concat(objSetupPrefix, "delete"))), "tcc_add_include_path"),
                // FILE_NOT_RECOGNIZED (-3) on the second TU proves any non-zero status aborts,
                // and the first TU's OBJ state has already completed and published.
                new StepFailure("objAddFile", fake -> fake.tuFileStatuses.addAll(List.of(0, -3)),
                        expectStates(List.of(
                                concat(objSetupPrefix, "addFile", "outputFile", "delete"),
                                concat(objSetupPrefix, "addFile", "delete"))), "tcc_add_file"),
                new StepFailure("objOutputFile", fake -> fake.objOutputStatus = -1,
                        expectStates(List.of(concat(objSetupPrefix, "addFile", "outputFile", "delete"))), "tcc_output_file"),
                new StepFailure("dllOutputFile", fake -> fake.dllOutputStatus = -1,
                        expectStates(List.of(
                                concat(objSetupPrefix, "addFile", "outputFile", "delete"),
                                concat(objSetupPrefix, "addFile", "outputFile", "delete"),
                                concat(List.of("setErrorHandler", "setLibPath", "setOptions", "setOutputType", "addFile", "addFile", "outputFile"), "delete"))), "tcc_output_file")
        );
    }

    private static @NotNull List<String> objSetupPrefixHead() {
        return List.of("setErrorHandler", "setLibPath", "setOptions");
    }

    private static @NotNull Consumer<FakeTinyCcNative> expectStates(@NotNull List<List<String>> expectedCallsPerState) {
        return fake -> {
            assertEquals(expectedCallsPerState.size(), fake.states.size(),
                    () -> "state count mismatch; states:\n" + fake.describeStates());
            for (var i = 0; i < expectedCallsPerState.size(); i++) {
                final var stateIndex = i;
                assertEquals(expectedCallsPerState.get(i), fake.states.get(i).calls,
                        () -> "state " + stateIndex + " calls mismatch; states:\n" + fake.describeStates());
            }
        };
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
        var bundleRoot = newBundleRoot(tempDir);
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(bundleRoot, created, fake -> {
        });

        var result = compiler.compile(tempDir, List.of(includeA, includeB), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "fake round failed:\n" + result.buildLog());
        var expectedOutput = tempDir.resolve(host.sharedLibraryFileName("probe")).toAbsolutePath();
        assertEquals(List.of(expectedOutput), result.artifacts());
        assertEquals(STATS_PREFIX + "0 hits, 2 misses\n", result.buildLog(),
                "a clean cache-available round starts with the fixed statistics line");
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(3, fake.states.size(), () -> "two OBJ states plus one link state:\n" + fake.describeStates());
        for (var i = 0; i < 2; i++) {
            var state = fake.states.get(i);
            assertEquals(List.of("setErrorHandler", "setLibPath", "setOptions", "setOutputType", "addIncludePath", "addIncludePath", "addFile", "outputFile", "delete"), state.calls);
            assertEquals(bundleRoot.toString(), state.libPathSeen, "tcc_set_lib_path must point at the bundle root");
            assertEquals("-std=c11", state.optionsSeen);
            assertEquals(TinyCcLibrary.TCC_OUTPUT_OBJ, state.outputTypeSeen);
            assertEquals(List.of(includeA.toString(), includeB.toString()), state.includePathsSeen,
                    "include paths are added to OBJ states in input order");
        }
        assertEquals(List.of(tuA.toString()), fake.states.get(0).filesSeen);
        assertEquals(List.of(tuB.toString()), fake.states.get(1).filesSeen);
        var link = fake.states.get(2);
        assertEquals(List.of("setErrorHandler", "setLibPath", "setOptions", "setOutputType", "addFile", "addFile", "outputFile", "delete"), link.calls);
        assertEquals(TinyCcLibrary.TCC_OUTPUT_DLL, link.outputTypeSeen);
        assertTrue(link.includePathsSeen.isEmpty(), "the link state consumes objects and needs no include paths");
        assertEquals(expectedOutput.toString(), link.outputPathSeen);
        // Both OBJ outputs went through the cache temp name and were published as final entries.
        var cacheDir = TinyCcObjCache.objectDirectory(tempDir.resolve("compiler-cache"));
        try (var stream = Files.list(cacheDir)) {
            var entries = stream.map(path -> path.getFileName().toString()).sorted().toList();
            assertEquals(2, entries.size(), () -> "both misses must publish one entry each: " + entries);
            assertTrue(entries.stream().allMatch(name -> name.matches("[0-9a-f]{32}\\.o")), () -> "entries are content keys: " + entries);
        }
        // The link consumed this round's exclusive temp objects (the published entries are only
        // read by later rounds), so an external deletion of a fresh entry can never fail us.
        var cacheRoot = tempDir.resolve("compiler-cache");
        assertTrue(link.filesSeen.stream().allMatch(file -> file.endsWith(".o") && !file.startsWith(cacheRoot.toString())),
                () -> "freshly compiled TUs link from round-exclusive temps: " + link.filesSeen);
        assertTrue(Files.isRegularFile(TinyCcObjCache.finalPath(cacheRoot, keyOf(tempDir, tuA, List.of(includeA, includeB), bundleRoot, host))),
                "the miss was published under its content key");
        assertTrue(fake.arenaAliveAtLastDelete);
        assertFalse(fake.roundArena.scope().isAlive());
    }

    private static @NotNull String keyOf(@NotNull Path tempDir, @NotNull Path tu, @NotNull List<Path> includeDirs, @NotNull Path bundleRoot, @NotNull TargetPlatform host) throws IOException {
        return TinyCcObjCache.requireKey(tu, Files.readAllBytes(tu), includeDirs,
                TinyCcObjCache.hashIncludeTrees(includeDirs),
                Files.readString(bundleRoot.resolve(TinyCcBundle.VERSION_FILE_NAME)), host);
    }

    @Test
    void aCacheHitTuNeverCreatesAnObjState(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var includeDir = Files.createDirectories(tempDir.resolve("include"));
        var tuA = writeTu(tempDir, "a.c");
        var tuB = writeTu(tempDir, "b.c");
        var bundleRoot = newBundleRoot(tempDir);
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(bundleRoot, created, fake -> {
        });

        var first = compiler.compile(tempDir, List.of(includeDir), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);
        assertTrue(first.success(), () -> "first round failed:\n" + first.buildLog());
        assertEquals(3, created.get().states.size(), "first round: two OBJ states plus one link state");

        var second = compiler.compile(tempDir, List.of(includeDir), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(second.success(), () -> "second round failed:\n" + second.buildLog());
        assertEquals(STATS_PREFIX + "2 hits, 0 misses\n", second.buildLog());
        assertEquals(1, created.get().states.size(), () -> "a full-hit round creates only the link state:\n" + created.get().describeStates());
        var link = created.get().states.getFirst();
        assertEquals(TinyCcLibrary.TCC_OUTPUT_DLL, link.outputTypeSeen);
        assertEquals(2, link.filesSeen.size(), "the link consumes both cached objects");
        assertTrue(link.filesSeen.stream().allMatch(file -> file.endsWith(".o")));
    }

    @Test
    void hitTusDoNotReplayTheirDiagnostics(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var bundleRoot = newBundleRoot(tempDir);
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(bundleRoot, created, fake -> fake.diagnosticOnTuAddFile = "a.c:1: warning: suspicious");

        var first = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);
        assertTrue(first.success(), () -> "first round failed:\n" + first.buildLog());
        assertEquals(STATS_PREFIX + "0 hits, 1 misses\na.c:1: warning: suspicious\n", first.buildLog());

        var second = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        // The hit TU is never recompiled, so its warnings do not reappear (documented contract).
        assertEquals(STATS_PREFIX + "1 hits, 0 misses\n", second.buildLog());
    }

    @Test
    void linkerRejectionOfACacheHitSelfHealsExactlyOnce(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tuA = writeTu(tempDir, "a.c");
        var tuB = writeTu(tempDir, "b.c");
        var bundleRoot = newBundleRoot(tempDir);
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(bundleRoot, created, fake -> {
        });
        var first = compiler.compile(tempDir, List.of(), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);
        assertTrue(first.success(), () -> "seeding round failed:\n" + first.buildLog());

        // Second round: both TUs hit, but the linker rejects the first cached object. The round
        // must evict the poisoned entry, recompile that TU with a fresh OBJ state, drop the
        // rejected link state and re-link exactly once from a new one.
        var selfHealCompiler = newCompiler(bundleRoot, created, fake -> {
            fake.objectLinkFailures = 1;
            fake.diagnosticOnObjectLinkFailure = "ld: cannot use " + tuA;
        });
        var second = selfHealCompiler.compile(tempDir, List.of(), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(second.success(), () -> "the self-healed round must succeed:\n" + second.buildLog());
        assertEquals(STATS_PREFIX + "1 hits, 1 misses\n", second.buildLog(),
                "the rejected hit flips to a miss in the statistics");
        assertFalse(second.buildLog().contains("cannot use"),
                "the rejected link attempt's diagnostics are dropped after a successful heal");
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(List.of(TinyCcLibrary.TCC_OUTPUT_DLL, TinyCcLibrary.TCC_OUTPUT_OBJ, TinyCcLibrary.TCC_OUTPUT_DLL),
                fake.states.stream().map(state -> state.outputTypeSeen).toList(),
                () -> "rejected link state discarded, TU recompiled, fresh link state:\n" + fake.describeStates());
        assertTrue(fake.states.stream().allMatch(state -> state.deleted),
                "every state — including the discarded link state — is deleted exactly once");
        assertTrue(fake.states.stream().flatMap(state -> state.calls.stream()).filter("delete"::equals).count() == 3);

        // The recompiled TU was published again: a third round is a full hit.
        var third = newCompiler(bundleRoot, created, unused -> {
        }).compile(tempDir, List.of(), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);
        assertTrue(third.success(), () -> "third round failed:\n" + third.buildLog());
        assertEquals(STATS_PREFIX + "2 hits, 0 misses\n", third.buildLog());
    }

    @Test
    void linkerRejectionSelfHealsAtMostOnce(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tuA = writeTu(tempDir, "a.c");
        var tuB = writeTu(tempDir, "b.c");
        var bundleRoot = newBundleRoot(tempDir);
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(bundleRoot, created, fake -> {
        });
        assertTrue(compiler.compile(tempDir, List.of(), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host).success());

        // Both cached objects are poisoned: the first rejection self-heals, the second hits the
        // re-link attempt and must fail the round — an object rejected twice never loops.
        var failing = newCompiler(bundleRoot, created, fake -> fake.objectLinkFailures = 2);
        var result = failing.compile(tempDir, List.of(), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertTrue(result.buildLog().contains("tcc_add_file"), () -> "got:\n" + result.buildLog());
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(2, fake.states.stream().filter(state -> state.outputTypeSeen == TinyCcLibrary.TCC_OUTPUT_DLL).count(),
                "exactly one discarded link state plus exactly one re-link state");
        assertEquals(1, fake.states.stream().filter(state -> state.outputTypeSeen == TinyCcLibrary.TCC_OUTPUT_OBJ).count(),
                "only the first rejected TU was recompiled before the second rejection failed the round");
    }

    @Test
    void missingBundleVersionDegradesTheWholeRoundToCacheless(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var bundleRoot = Files.createDirectories(tempDir.resolve("bundle"));
        // No VERSION file: the compiler identity is unknown, so no entry may be read or written.
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(bundleRoot, created, fake -> {
        });

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "a cache outage must never fail the build:\n" + result.buildLog());
        assertTrue(result.buildLog().startsWith(FALLBACK_PREFIX), () -> "fixed fallback line at the top:\n" + result.buildLog());
        assertFalse(result.buildLog().contains(STATS_PREFIX), "a cacheless round has no statistics line");
        assertFalse(Files.exists(tempDir.resolve("compiler-cache")), "a degraded round never touches the cache directory");
    }

    @Test
    void includeTreeHashingFailureDegradesTheWholeRoundToCacheless(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var missingInclude = tempDir.resolve("does-not-exist");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> {
        });

        var result = compiler.compile(tempDir, List.of(missingInclude), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "include-tree hashing failure must degrade, not fail:\n" + result.buildLog());
        assertTrue(result.buildLog().startsWith(FALLBACK_PREFIX), () -> "got:\n" + result.buildLog());
        assertFalse(result.buildLog().contains(STATS_PREFIX));
    }

    @Test
    void aPublishStagingFailureLinksTheTempArtifactDirectly(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var bundleRoot = newBundleRoot(tempDir);
        // Squat the cache object directory with a plain file: staging the cache copy fails, so
        // the round links the round-exclusive temp artifact and keeps compiling.
        var cacheRoot = tempDir.resolve("compiler-cache");
        Files.createDirectories(cacheRoot);
        Files.writeString(cacheRoot.resolve(TinyCcObjCache.OBJ_DIR_NAME), "not a directory");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(bundleRoot, created, fake -> {
        });

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "a publish-leg failure must degrade, not fail:\n" + result.buildLog());
        assertTrue(result.buildLog().startsWith(FALLBACK_PREFIX + "could not publish the object cache entry: "),
                () -> "got:\n" + result.buildLog());
        assertTrue(result.buildLog().contains(STATS_PREFIX + "0 hits, 1 misses"),
                "the cache was available; only the publish leg degraded");
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(2, fake.states.size(), "the TU still compiled with its own OBJ state");
        assertTrue(fake.states.get(1).filesSeen.getFirst().endsWith(".o"), "the link consumed the temp object");
    }

    @Test
    void linkerRejectionWithABrokenCallbackFailsInsteadOfSelfHealing(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var bundleRoot = newBundleRoot(tempDir);
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        assertTrue(newCompiler(bundleRoot, created, unused -> {
        }).compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host).success());

        // The rejection coincides with a broken diagnostic upcall: the round must fail through
        // the callback channel — never evict the entry or start a recompile on a sick state.
        var failing = newCompiler(bundleRoot, created, fake -> {
            fake.objectLinkFailures = 1;
            fake.callbackFailureAtStep = "addFile";
        });
        var result = failing.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertTrue(result.buildLog().contains("diagnostic callback failed"), () -> "got:\n" + result.buildLog());
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(1, fake.states.size(), "no recompile state may be created after the callback failure");
        assertTrue(Files.isRegularFile(TinyCcObjCache.finalPath(tempDir.resolve("compiler-cache"),
                keyOf(tempDir, tu, List.of(), bundleRoot, host))), "the entry must not be evicted on a callback failure");
    }

    @Test
    void linkerRejectionWithAPendingInterruptSurfacesCancellation(@TempDir Path tempDir) throws Exception {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var bundleRoot = newBundleRoot(tempDir);
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        assertTrue(newCompiler(bundleRoot, created, unused -> {
        }).compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host).success());

        // The rejection lands with the interrupt flag already set: the round must surface the
        // fixed cancellation channel without evicting or recompiling.
        var cancelling = newCompiler(bundleRoot, created, fake -> {
            fake.objectLinkFailures = 1;
            fake.interruptOnObjectLinkFailure = true;
        });
        var round = runOnOwnThread(() -> cancelling.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host));
        round.joinUninterrupted();

        var result = round.result();
        assertNotNull(result);
        assertFalse(result.success());
        assertEquals("Failed to run tcc: interrupted", result.buildLog());
        assertTrue(round.interruptRestored());
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(1, fake.states.size(), "no recompile state may be created once cancellation is pending");
        assertTrue(Files.isRegularFile(TinyCcObjCache.finalPath(tempDir.resolve("compiler-cache"),
                keyOf(tempDir, tu, List.of(), bundleRoot, host))), "the entry must not be evicted on the cancellation path");
    }

    @Test
    void publishMoveFailureDegradesToTheTempArtifact(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var bundleRoot = newBundleRoot(tempDir);
        // Squat the final entry path with a non-empty directory: staging succeeds but the
        // atomic move fails — the round must still link the round-exclusive temp artifact.
        var key = keyOf(tempDir, tu, List.of(), bundleRoot, host);
        var cacheRoot = tempDir.resolve("compiler-cache");
        Files.createDirectories(TinyCcObjCache.finalPath(cacheRoot, key).resolve("child"));
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(bundleRoot, created, unused -> {
        });

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "a publish failure must degrade, not fail:\n" + result.buildLog());
        assertTrue(result.buildLog().startsWith(FALLBACK_PREFIX + "could not publish the object cache entry: "),
                () -> "got:\n" + result.buildLog());
        assertTrue(result.buildLog().contains(STATS_PREFIX + "0 hits, 1 misses"));
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(2, fake.states.size(), "the TU compiled with its own OBJ state, then linked");
        var linkedObject = fake.states.get(1).filesSeen.getFirst();
        assertTrue(linkedObject.endsWith(".o"), "the link consumed the temp object");
        assertFalse(linkedObject.startsWith(cacheRoot.toString()),
                "the link must consume the round-exclusive temp, not a cache path whose entry is squatted");
    }

    @Test
    void directorySymlinkInAnIncludeTreeDegradesTheRoundToCacheless(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var includeDir = Files.createDirectories(tempDir.resolve("inc"));
        var linkTarget = Files.createDirectories(tempDir.resolve("external-headers"));
        Files.writeString(linkTarget.resolve("config.h"), "#define X 1\n");
        var link = includeDir.resolve("vendor");
        try {
            Files.createSymbolicLink(link, linkTarget);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("symlinks unavailable here: " + e);
            return;
        }
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(newBundleRoot(tempDir), created, unused -> {
        });

        // Directory symlinks are followed by the native include search but not by the hashing
        // walk — the round must degrade instead of risking a wrong hit.
        var result = compiler.compile(tempDir, List.of(includeDir), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "got:\n" + result.buildLog());
        assertTrue(result.buildLog().startsWith(FALLBACK_PREFIX), () -> "got:\n" + result.buildLog());
        assertFalse(result.buildLog().contains(STATS_PREFIX));
    }

    @Test
    void nonNormalizedTuPathsAreNormalizedBeforeKeysAndNativeCalls(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var bundleRoot = newBundleRoot(tempDir);
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(bundleRoot, created, unused -> {
        });
        // The same file spelled with a `..` segment: keys and `tcc_add_file` must both see the
        // normalized spelling, or __FILE__ would differ from what the key assumes.
        var nonNormalized = tempDir.resolve("sub").resolve("..").resolve("a.c");

        var result = compiler.compile(tempDir, List.of(), List.of(nonNormalized), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "got:\n" + result.buildLog());
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(tu.toAbsolutePath().normalize().toString(), fake.states.getFirst().filesSeen.getFirst(),
                "the native call receives the normalized path");
        // And the published entry sits under the normalized-path key: a second round with the
        // plain spelling hits it.
        var second = newCompiler(bundleRoot, created, unused -> {
        }).compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);
        assertTrue(second.success(), () -> "got:\n" + second.buildLog());
        assertEquals(STATS_PREFIX + "1 hits, 0 misses\n", second.buildLog());
    }

    @Test
    void anUnreadableTuIsAPlainMissWithoutDegradation(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        // The TU path never lands on disk: key preparation marks it keyless, the round stays
        // cache-available, and the (fake) compile of the missing file still succeeds.
        var missingTu = tempDir.resolve("ghost.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> {
        });

        var result = compiler.compile(tempDir, List.of(), List.of(missingTu), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "got:\n" + result.buildLog());
        assertEquals(STATS_PREFIX + "0 hits, 1 misses\n", result.buildLog(),
                "an unreadable TU is a miss, not a cache outage");
    }

    @Test
    void diagnosticsArePassedIntoTheBuildLog(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> fake.diagnosticOnTuAddFile = "a.c:1: warning: suspicious");

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertTrue(result.success(), () -> "fake round failed:\n" + result.buildLog());
        assertEquals(STATS_PREFIX + "0 hits, 1 misses\na.c:1: warning: suspicious\n", result.buildLog(),
                "the statistics header precedes the diagnostics, which keep their native file:line: prefix");
    }

    @Test
    void callbackFailureFailsTheRoundEvenWhenNativeSucceededAndArtifactExists(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        // The second outputFile call is the DLL output: native fully succeeded and the artifact
        // exists, yet the broken upcall must fail the round with empty artifacts.
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> {
            fake.callbackFailureAtStep = "outputFile";
            fake.callbackFailureAtOccurrence = 2;
        });

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success(), "a broken diagnostic upcall must fail the round despite native success");
        assertTrue(result.artifacts().isEmpty(), "the artifact on disk must never be reported after a callback failure");
        assertTrue(result.buildLog().contains("diagnostic callback failed"), () -> "got:\n" + result.buildLog());
        var fake = created.get();
        assertNotNull(fake);
        assertTrue(Files.isRegularFile(tempDir.resolve(host.sharedLibraryFileName("probe"))),
                "the fake really wrote the artifact — the failure is purely the upcall flag");
        assertEquals(TinyCcLibrary.TCC_OUTPUT_DLL, fake.states.getLast().outputTypeSeen);
    }

    @Test
    void callbackFailureDuringSetupAbortsBeforeAnyFileIsAdded(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> fake.callbackFailureAtStep = "setOptions");

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        var fake = created.get();
        assertNotNull(fake);
        assertTrue(fake.allFilesSeen().isEmpty(), "the callback failure flag is checked after every downcall");
    }

    @Test
    void nullStateFailsBeforeAnySetupRuns(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> fake.nullState = true);

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertTrue(result.buildLog().contains("tcc_new"), () -> "got:\n" + result.buildLog());
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(1, fake.newStateCalls);
        assertTrue(fake.states.isEmpty(), "a NULL state has nothing to delete and nothing to set up");
        assertFalse(fake.roundArena.scope().isAlive(), "the arena is still closed");
    }

    @Test
    void objOutputSuccessStatusWithoutAFileFailsTheRound(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        // outputFile reports success for the OBJ state but writes nothing: the post-output
        // existence check must fail the round instead of trusting the status.
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> fake.writeObjOutput = false);

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty());
        assertTrue(result.buildLog().contains("was not created"), () -> "got:\n" + result.buildLog());
    }

    @Test
    void staleArtifactIsDeletedBeforeOutputAndMissingDllAfterSuccessStatusFails(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var output = tempDir.resolve(host.sharedLibraryFileName("probe"));
        Files.writeString(output, "stale artifact from an earlier round");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> fake.writeDllOutput = false);

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, host);

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty());
        assertFalse(Files.exists(output), "the stale artifact must be deleted before the DLL output runs");
        assertTrue(result.buildLog().contains("was not created"), () -> "got:\n" + result.buildLog());
    }

    @Test
    void staleArtifactDoesNotMaskOutputFailure(@TempDir Path tempDir) throws IOException {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var output = tempDir.resolve(host.sharedLibraryFileName("probe"));
        Files.writeString(output, "stale artifact from an earlier round");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        // The stale artifact is deleted before the DLL output and output then fails without
        // writing: the round must fail with empty artifacts and no file left to masquerade.
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> {
            fake.dllOutputStatus = -1;
            fake.writeDllOutput = false;
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
        var compiler = new TinyCcCompiler(() -> Path.of("unused-bundle-root"), (root, targetPlatform, roundArena) -> {
            fail("the native factory must not run for an empty input set");
            throw new IOException("unreachable");
        });

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
        var compiler = new TinyCcCompiler(() -> Path.of("unused-bundle-root"), (root, targetPlatform, roundArena) -> {
            fail("the native factory must not run for an unsupported target");
            throw new IOException("unreachable");
        });

        var result = compiler.compile(tempDir, List.of(), List.of(tu), "probe", COptimizationLevel.DEBUG, otherTarget);

        assertFalse(result.success());
        assertTrue(result.buildLog().contains("use the zig backend"), () -> "got:\n" + result.buildLog());
        assertTrue(result.artifacts().isEmpty());
        assertNull(created.get());
    }

    @Test
    void interruptBeforeTheSecondTuAbortsTheRound(@TempDir Path tempDir) throws Exception {
        var host = assumeSupportedHost();
        var tuA = writeTu(tempDir, "a.c");
        var tuB = writeTu(tempDir, "b.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> fake.interruptOnTuIndex = 0);

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
        // The interrupt lands during the first TU's compile; the TU boundary check catches it
        // before the second TU's OBJ state is ever created, and no link state exists.
        assertEquals(1, fake.states.size(), () -> "states:\n" + fake.describeStates());
        assertEquals(TinyCcLibrary.TCC_OUTPUT_OBJ, fake.states.getFirst().outputTypeSeen);
    }

    @Test
    void interruptAfterTheFinalTuAbortsBeforeTheDllOutput(@TempDir Path tempDir) throws Exception {
        var host = assumeSupportedHost();
        var tuA = writeTu(tempDir, "a.c");
        var tuB = writeTu(tempDir, "b.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> fake.interruptOnTuIndex = 1);

        var round = runOnOwnThread(() -> compiler.compile(tempDir, List.of(), List.of(tuA, tuB), "probe", COptimizationLevel.DEBUG, host));
        round.joinUninterrupted();

        var result = round.result();
        assertNotNull(result);
        assertFalse(result.success());
        assertEquals("Failed to run tcc: interrupted", result.buildLog());
        assertTrue(round.interruptRestored());
        var fake = created.get();
        assertNotNull(fake);
        assertEquals(3, fake.states.size(), () -> "both OBJ states and the link state ran:\n" + fake.describeStates());
        var link = fake.states.getLast();
        assertEquals(TinyCcLibrary.TCC_OUTPUT_DLL, link.outputTypeSeen);
        assertFalse(link.calls.contains("outputFile"), "the pre-output interrupt check must stop the round");
        assertTrue(link.calls.contains("delete"), "the link state is still deleted on the cancellation path");
    }

    @Test
    void interruptWhileWaitingForTheGlobalLockAbortsTheRound(@TempDir Path tempDir) throws Exception {
        var host = assumeSupportedHost();
        var tu = writeTu(tempDir, "a.c");
        var created = new AtomicReference<@Nullable FakeTinyCcNative>();
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> {
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
        var compiler = newCompiler(newBundleRoot(tempDir), new AtomicReference<>(), fake -> {
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
        var compiler = newCompiler(newBundleRoot(tempDir), created, fake -> fake.addFileBlock = new CountDownLatch[]{insideAddFile, releaseAddFile});
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

    /// Multi-state fake: every `tcc_new` appends one [FakeState] that records its own call
    /// sequence, so tests pin both the per-state step order and the cross-state round shape
    /// (OBJ states per missed TU, one DLL link state, a discarded link state on self-heal).
    /// `delete` captures whether the round arena was still alive, pinning the cleanup order
    /// (delete → arena close) without any real native library.
    private static final class FakeTinyCcNative implements TinyCcNative {
        final Arena roundArena;
        final List<FakeState> states = new ArrayList<>();
        int newStateCalls;
        /// Programmed outcomes below; defaults are the all-success path.
        boolean nullState;
        int optionsStatus;
        int outputTypeStatus;
        int includePathStatus;
        /// Consumed in order by OBJ-state `addFile(.c)` calls.
        final Deque<Integer> tuFileStatuses = new ArrayDeque<>();
        /// The next N link-state `addFile(.o)` calls return -1 (a poisoned cache entry).
        int objectLinkFailures;
        int objOutputStatus;
        int dllOutputStatus;
        boolean writeObjOutput = true;
        boolean writeDllOutput = true;
        /// When set, `callbackFailure()` starts reporting a failure after the Nth step with this
        /// name ran (occurrence counting is global across states).
        @Nullable String callbackFailureAtStep;
        int callbackFailureAtOccurrence = 1;
        /// When set, the fake interrupts the compile thread during the Nth TU's `addFile(.c)`.
        int interruptOnTuIndex = -1;
        /// When set, the first `addFile` signals the first latch then awaits the second — a
        /// deterministic window for the test to observe the round's lock state.
        CountDownLatch @Nullable [] addFileBlock;
        /// When set, the error handler is invoked with this message on every TU `addFile(.c)`.
        @Nullable String diagnosticOnTuAddFile;
        /// When set, the error handler is invoked with this message on a rejected object link.
        @Nullable String diagnosticOnObjectLinkFailure;
        /// When set, a rejected object link also interrupts the compile thread — the self-heal
        /// entry must honor the pending interrupt instead of starting the recompilation.
        boolean interruptOnObjectLinkFailure;
        boolean arenaAliveAtLastDelete;
        private int tuAddFileCalls;
        private int callbackStepMatches;
        private boolean callbackFailed;

        FakeTinyCcNative(@NotNull Arena roundArena) {
            this.roundArena = roundArena;
        }

        /// Every operation resolves its state from the handle it was given: using a deleted or
        /// unknown handle fails the test immediately, so a cleanup-order bug cannot hide behind
        /// "the last state happens to match".
        private @NotNull FakeState stateFor(@NotNull MemorySegment state) {
            var index = (int) state.address() - 1;
            if (index < 0 || index >= states.size()) {
                fail("operation on an unknown state handle " + state.address());
            }
            var fakeState = states.get(index);
            if (fakeState.deleted) {
                fail("operation on an already deleted state (index " + index + ")");
            }
            return fakeState;
        }

        private void step(@NotNull MemorySegment state, @NotNull String name) {
            stateFor(state).calls.add(name);
            if (name.equals(callbackFailureAtStep) && ++callbackStepMatches == callbackFailureAtOccurrence) {
                callbackFailed = true;
            }
        }

        @NotNull String describeStates() {
            var description = new StringBuilder();
            for (var i = 0; i < states.size(); i++) {
                var state = states.get(i);
                description.append("state ").append(i).append(" (outputType=").append(state.outputTypeSeen).append("): ")
                        .append(state.calls).append('\n');
            }
            return description.toString();
        }

        @NotNull List<String> allFilesSeen() {
            return states.stream().flatMap(state -> state.filesSeen.stream()).toList();
        }

        @Override
        public @Nullable MemorySegment newState() {
            newStateCalls++;
            if (nullState) {
                return null;
            }
            states.add(new FakeState());
            return MemorySegment.ofAddress(states.size());
        }

        @Override
        public void delete(@NotNull MemorySegment state) {
            var fakeState = stateFor(state);
            fakeState.calls.add("delete");
            fakeState.deleted = true;
            arenaAliveAtLastDelete = roundArena.scope().isAlive();
        }

        @Override
        public void setLibPath(@NotNull MemorySegment state, @NotNull String path) {
            step(state, "setLibPath");
            stateFor(state).libPathSeen = path;
        }

        @Override
        public int setOptions(@NotNull MemorySegment state, @NotNull String options) {
            step(state, "setOptions");
            stateFor(state).optionsSeen = options;
            return optionsStatus;
        }

        @Override
        public int setOutputType(@NotNull MemorySegment state, int outputType) {
            step(state, "setOutputType");
            stateFor(state).outputTypeSeen = outputType;
            return outputTypeStatus;
        }

        @Override
        public int addIncludePath(@NotNull MemorySegment state, @NotNull String path) {
            step(state, "addIncludePath");
            stateFor(state).includePathsSeen.add(path);
            return includePathStatus;
        }

        @Override
        public int addFile(@NotNull MemorySegment state, @NotNull String file) {
            step(state, "addFile");
            var fakeState = stateFor(state);
            fakeState.filesSeen.add(file);
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
            if (file.endsWith(".o")) {
                // Link-state object input.
                if (objectLinkFailures > 0) {
                    objectLinkFailures--;
                    if (diagnosticOnObjectLinkFailure != null && fakeState.errorHandler != null) {
                        fakeState.errorHandler.onError(diagnosticOnObjectLinkFailure);
                    }
                    if (interruptOnObjectLinkFailure) {
                        Thread.currentThread().interrupt();
                    }
                    return -1;
                }
                return 0;
            }
            // OBJ-state TU input.
            if (diagnosticOnTuAddFile != null && fakeState.errorHandler != null) {
                fakeState.errorHandler.onError(diagnosticOnTuAddFile);
            }
            if (tuAddFileCalls++ == interruptOnTuIndex) {
                Thread.currentThread().interrupt();
            }
            var status = tuFileStatuses.poll();
            return status != null ? status : 0;
        }

        @Override
        public int outputFile(@NotNull MemorySegment state, @NotNull String file) {
            step(state, "outputFile");
            stateFor(state).outputPathSeen = file;
            var objOutput = file.endsWith(".o");
            if (objOutput ? writeObjOutput : writeDllOutput) {
                try {
                    Files.writeString(Path.of(file), objOutput ? "fake object" : "fake shared object");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return objOutput ? objOutputStatus : dllOutputStatus;
        }

        @Override
        public void setErrorHandler(@NotNull MemorySegment state, @NotNull ErrorHandler handler) {
            step(state, "setErrorHandler");
            stateFor(state).errorHandler = handler;
        }

        @Override
        public @Nullable Throwable callbackFailure() {
            return callbackFailed ? new RuntimeException("programmed diagnostic callback failure") : null;
        }
    }

    /// Per-`tcc_new` record; `outputTypeSeen == -1` means the state died before setup finished.
    private static final class FakeState {
        final List<String> calls = new ArrayList<>();
        final List<String> includePathsSeen = new ArrayList<>();
        final List<String> filesSeen = new ArrayList<>();
        int outputTypeSeen = -1;
        boolean deleted;
        @Nullable String libPathSeen;
        @Nullable String optionsSeen;
        @Nullable String outputPathSeen;
        @Nullable TinyCcNative.ErrorHandler errorHandler;
    }
}
