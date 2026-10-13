package gd.script.gdcc.api;

import gd.script.gdcc.api.task.CompileTaskHooks;
import gd.script.gdcc.backend.c.build.CProjectBuilder;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Concurrency and snapshot-publication contract of the three-phase `API.analyze(...)`
/// (`frontend_lsp_foundation_implementation.md` §2.3): version identity on every outcome,
/// gate-free analysis, conditional publish, and delete/recreate isolation. Determinism comes from
/// the package-private `AnalysisRunSeam` instead of sleep-based timing.
class ApiAnalysisSnapshotConcurrencyTest {
    private static final long TASK_TIMEOUT_MILLIS = 30_000;

    @Test
    void analyzePublishesSnapshotWhoseVersionTracksContentWrites() {
        var api = newSeamApi(null);
        api.createModule("demo", "Version Demo");
        api.putFile("demo", "/src/demo.gd", validSource("VersionDemo"));

        var first = api.analyze("demo");
        var firstVersion = api.getModuleContentVersion("demo");
        var firstSnapshot = api.getLatestAnalysisSnapshot("demo");
        assertAll(
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, first.outcome()),
                () -> assertEquals(firstVersion.moduleGeneration(), first.moduleGeneration()),
                () -> assertEquals(firstVersion.contentVersion(), first.snapshotVersion()),
                () -> assertNotNull(firstSnapshot),
                () -> assertEquals(first.moduleGeneration(), firstSnapshot.moduleGeneration()),
                () -> assertEquals(first.snapshotVersion(), firstSnapshot.snapshotVersion()),
                () -> assertEquals(first.diagnostics(), firstSnapshot.diagnostics()),
                () -> assertEquals(1, firstSnapshot.sourceViews().size()),
                () -> assertEquals("/src/demo.gd", firstSnapshot.sourceViews().getFirst().displayPath()),
                () -> assertTrue(firstSnapshot.sourceViews().getFirst().source().contains("VersionDemo"))
        );

        api.putFile("demo", "/src/second.gd", validSource("SecondFile"));
        var second = api.analyze("demo");
        var secondSnapshot = api.getLatestAnalysisSnapshot("demo");
        assertAll(
                () -> assertTrue(second.snapshotVersion() > first.snapshotVersion(),
                        "snapshot versions must increase monotonically with content writes"),
                () -> assertEquals(api.getModuleContentVersion("demo").contentVersion(), second.snapshotVersion()),
                () -> assertNotNull(secondSnapshot),
                () -> assertEquals(second.snapshotVersion(), secondSnapshot.snapshotVersion()),
                () -> assertEquals(2, secondSnapshot.sourceViews().size())
        );
    }

    @Test
    void sourceCollectionFailureStillCarriesFrozenVersion() {
        var api = newSeamApi(null);
        api.createModule("demo", "Failure Version Demo");
        // Broken virtual link: source collection fails inside the frozen request.
        api.createLink("demo", "/src/alias.gd", VfsEntrySnapshot.LinkKind.VIRTUAL, "/missing.gd");

        var frozenBefore = api.getModuleContentVersion("demo");
        var result = api.analyze("demo");
        assertAll(
                () -> assertEquals(AnalysisResult.Outcome.SOURCE_COLLECTION_FAILED, result.outcome()),
                () -> assertEquals(frozenBefore.moduleGeneration(), result.moduleGeneration()),
                () -> assertEquals(frozenBefore.contentVersion(), result.snapshotVersion()),
                () -> assertNull(api.getLatestAnalysisSnapshot("demo"),
                        "failed outcomes publish no snapshot")
        );
    }

    @Test
    void putFileDuringAnalysisReturnsBeforeAnalysisCompletes() throws Exception {
        var analysisEntered = new CountDownLatch(1);
        var releaseAnalysis = new CountDownLatch(1);
        var api = newSeamApi(new BlockingSeam(analysisEntered, releaseAnalysis));
        api.createModule("demo", "Write During Analysis Demo");
        api.putFile("demo", "/src/demo.gd", validSource("WriteDuringAnalysisDemo"));
        var frozenVersion = api.getModuleContentVersion("demo");

        var resultRef = new AtomicReference<AnalysisResult>();
        var analyzeThread = Thread.ofVirtual().start(() -> resultRef.set(api.analyze("demo")));
        try {
            assertTrue(analysisEntered.await(TASK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                    "analysis must enter the blocked seam");
            // The whole point of the three-phase contract: VFS writes contend only with the brief
            // freeze, never with the off-latch analysis run.
            api.putFile("demo", "/src/second.gd", validSource("SecondFile"));
            assertNull(resultRef.get(), "analysis is still blocked in the seam");
        } finally {
            releaseAnalysis.countDown();
        }
        analyzeThread.join(Duration.ofSeconds(30));

        var result = resultRef.get();
        assertAll(
                () -> assertNotNull(result),
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, result.outcome()),
                () -> assertEquals(frozenVersion.contentVersion(), result.snapshotVersion(),
                        "the result must correspond to the frozen version captured before the write"),
                () -> assertTrue(api.getModuleContentVersion("demo").contentVersion() > result.snapshotVersion()),
                () -> assertEquals(result.snapshotVersion(),
                        api.getLatestAnalysisSnapshot("demo").snapshotVersion())
        );
    }

    @Test
    void analyzeDoesNotWaitForQueuedOrActiveCompile() throws Exception {
        var compiler = ApiCompileTestSupport.RecordingCompiler.blockingSuccess();
        var api = ApiCompileTestSupport.newApi(compiler);
        api.createModule("demo", "No Gate Wait Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(
                Files.createTempDirectory("analyze-no-gate-wait")
        ));
        api.putFile("demo", "/src/demo.gd", validSource("NoGateWaitDemo"));

        try (var blocker = ApiCompileTestSupport.blockModuleOperation(api, "demo")) {
            assertTrue(blocker.awaitEntered());
            var taskId = api.compile("demo");
            assertEquals(CompileTaskSnapshot.State.QUEUED, api.getCompileTask(taskId).state());

            // A queued compile must not block analysis anymore
            // (`frontend_lsp_foundation_implementation.md` §2.3.3).
            var whileQueued = api.analyze("demo");
            assertEquals(AnalysisResult.Outcome.COMPLETED, whileQueued.outcome());
            assertEquals(CompileTaskSnapshot.State.QUEUED, api.getCompileTask(taskId).state());

            blocker.release();
            assertTrue(compiler.awaitEntered(), "compile should enter the blocked native build");

            // An active compile holding the module gate must not block analysis either.
            var whileActive = api.analyze("demo");
            assertEquals(AnalysisResult.Outcome.COMPLETED, whileActive.outcome());

            compiler.release();
            ApiCompileTestSupport.awaitResult(api, taskId);
        } finally {
            compiler.release();
        }
    }

    @Test
    void sameVersionConcurrentAnalysesKeepSingleConsistentSnapshot() throws Exception {
        var api = newSeamApi(null);
        api.createModule("demo", "Concurrent Analyze Demo");
        api.putFile("demo", "/src/demo.gd", validSource("ConcurrentAnalyzeDemo"));

        var gate = new CountDownLatch(1);
        var first = new AtomicReference<AnalysisResult>();
        var second = new AtomicReference<AnalysisResult>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstFuture = executor.submit(() -> {
                awaitQuietly(gate);
                first.set(api.analyze("demo"));
            });
            var secondFuture = executor.submit(() -> {
                awaitQuietly(gate);
                second.set(api.analyze("demo"));
            });
            gate.countDown();
            firstFuture.get(TASK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            secondFuture.get(TASK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        var snapshot = api.getLatestAnalysisSnapshot("demo");
        assertAll(
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, first.get().outcome()),
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, second.get().outcome()),
                () -> assertEquals(first.get().snapshotVersion(), second.get().snapshotVersion()),
                () -> assertNotNull(snapshot),
                () -> assertEquals(first.get().snapshotVersion(), snapshot.snapshotVersion())
        );
    }

    @Test
    void olderVersionFinishingLateDoesNotOverwriteNewerSnapshot() throws Exception {
        var firstVersionEntered = new CountDownLatch(1);
        var releaseFirstVersion = new CountDownLatch(1);
        var runner = new AnalysisRunner(new GdScriptParserService());
        // Ordered strictly by frozen content version: only the v1 run blocks, the v2 run passes.
        AnalysisRunSeam seam = (request, options) -> {
            if (request.contentVersion() == 1) {
                firstVersionEntered.countDown();
                awaitInterruptibly(releaseFirstVersion);
            }
            return runner.analyzeRich(request, options);
        };
        var api = newSeamApi(seam);
        api.createModule("demo", "Out Of Order Demo");
        api.putFile("demo", "/src/demo.gd", validSource("OutOfOrderDemo"));

        var firstResult = new AtomicReference<AnalysisResult>();
        var firstThread = Thread.ofVirtual().start(() -> firstResult.set(api.analyze("demo")));
        assertTrue(firstVersionEntered.await(TASK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));

        // Advance the content version and let the newer analysis finish first.
        api.putFile("demo", "/src/second.gd", validSource("SecondFile"));
        var secondResult = api.analyze("demo");
        var newSnapshot = api.getLatestAnalysisSnapshot("demo");
        assertEquals(secondResult.snapshotVersion(), newSnapshot.snapshotVersion());

        releaseFirstVersion.countDown();
        firstThread.join(Duration.ofSeconds(30));

        assertAll(
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, firstResult.get().outcome()),
                () -> assertTrue(firstResult.get().snapshotVersion() < secondResult.snapshotVersion()),
                () -> assertEquals(secondResult.snapshotVersion(),
                        api.getLatestAnalysisSnapshot("demo").snapshotVersion(),
                        "the late older run must not roll back the newer snapshot")
        );
    }

    @Test
    void deleteDuringAnalysisDropsSnapshotAndRecreatedModuleKeepsIsolation() throws Exception {
        var analysisEntered = new CountDownLatch(1);
        var releaseAnalysis = new CountDownLatch(1);
        var api = newSeamApi(new BlockingSeam(analysisEntered, releaseAnalysis));
        api.createModule("demo", "Delete During Analysis Demo");
        api.putFile("demo", "/src/demo.gd", validSource("DeleteDuringAnalysisDemo"));
        var oldVersion = api.getModuleContentVersion("demo");

        var oldResult = new AtomicReference<AnalysisResult>();
        var analyzeThread = Thread.ofVirtual().start(() -> oldResult.set(api.analyze("demo")));
        try {
            assertTrue(analysisEntered.await(TASK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            api.deleteModule("demo");
            api.createModule("demo", "Recreated Demo");
        } finally {
            releaseAnalysis.countDown();
        }
        analyzeThread.join(Duration.ofSeconds(30));

        var newVersion = api.getModuleContentVersion("demo");
        assertAll(
                // The interrupted analysis still returns its own result normally.
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, oldResult.get().outcome()),
                () -> assertEquals(oldVersion.moduleGeneration(), oldResult.get().moduleGeneration()),
                // Instance-identity guard: the old run must not publish into the recreated module.
                () -> assertNull(api.getLatestAnalysisSnapshot("demo")),
                // Same-id recreation carries a fresh, higher generation, so callers comparing
                // (generation, version) pairs never misread the old result as current.
                () -> assertTrue(newVersion.moduleGeneration() > oldVersion.moduleGeneration()),
                () -> assertEquals(0, newVersion.contentVersion())
        );

        // The recreated module publishes normally afterwards.
        api.putFile("demo", "/src/recreated.gd", validSource("RecreatedDemo"));
        var recreated = api.analyze("demo");
        var recreatedSnapshot = api.getLatestAnalysisSnapshot("demo");
        assertAll(
                () -> assertEquals(AnalysisResult.Outcome.COMPLETED, recreated.outcome()),
                () -> assertEquals(newVersion.moduleGeneration(), recreated.moduleGeneration()),
                () -> assertNotNull(recreatedSnapshot),
                () -> assertEquals(recreated.snapshotVersion(), recreatedSnapshot.snapshotVersion())
        );
    }

    /// Seam that blocks every analysis run between freeze and publication until released.
    private record BlockingSeam(
            @NotNull CountDownLatch entered,
            @NotNull CountDownLatch release
    ) implements AnalysisRunSeam {
        private final static AnalysisRunner RUNNER = new AnalysisRunner(new GdScriptParserService());

        @Override
        public @NotNull AnalysisRunResult run(
                @NotNull ModuleState.CompileRequest request,
                @NotNull AnalyzeOptions analyzeOptions
        ) {
            entered.countDown();
            awaitInterruptibly(release);
            return RUNNER.analyzeRich(request, analyzeOptions);
        }
    }

    private static @NotNull API newSeamApi(@Nullable AnalysisRunSeam seam) {
        return new API(
                Clock.systemUTC(),
                new GdScriptParserService(),
                new CProjectBuilder(),
                CompileTaskHooks.none(),
                Duration.ofSeconds(5),
                Duration.ofMillis(50),
                seam
        );
    }

    private static void awaitInterruptibly(@NotNull CountDownLatch latch) {
        try {
            if (!latch.await(TASK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for test latch", exception);
        }
    }

    private static void awaitQuietly(@NotNull CountDownLatch latch) {
        awaitInterruptibly(latch);
    }

    private static @NotNull String validSource(@NotNull String className) {
        return "class_name " + className + "\nextends RefCounted\n\nfunc ping() -> int:\n\treturn 1\n";
    }
}
