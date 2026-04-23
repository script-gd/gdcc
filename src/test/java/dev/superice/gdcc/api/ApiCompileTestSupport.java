package dev.superice.gdcc.api;

import dev.superice.gdcc.backend.c.build.CBuildResult;
import dev.superice.gdcc.backend.c.build.CCompiler;
import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.CProjectBuilder;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPassManager;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

final class ApiCompileTestSupport {
    static final Instant FIXED_TIME = Instant.parse("2026-04-23T10:15:30Z");
    static final Clock FIXED_CLOCK = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
    private static final long TASK_TIMEOUT_MILLIS = 30_000;

    private ApiCompileTestSupport() {
    }

    static @NotNull API newApi(@NotNull RecordingCompiler compiler) {
        return newApi(compiler, API.CompileTaskHooks.none());
    }

    static @NotNull API newApi(
            @NotNull RecordingCompiler compiler,
            @NotNull API.CompileTaskHooks compileTaskHooks
    ) {
        return new API(
                FIXED_CLOCK,
                new GdScriptParserService(),
                new FrontendLoweringPassManager(),
                new CProjectBuilder(compiler),
                compileTaskHooks
        );
    }

    static @NotNull CompileOptions compileOptions(@NotNull Path projectPath) {
        return compileOptions(
                projectPath,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform(),
                false,
                CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT
        );
    }

    static @NotNull CompileOptions compileOptions(
            @NotNull Path projectPath,
            @NotNull COptimizationLevel optimizationLevel,
            @NotNull TargetPlatform targetPlatform,
            boolean strictMode
    ) {
        return compileOptions(
                projectPath,
                optimizationLevel,
                targetPlatform,
                strictMode,
                CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT
        );
    }

    static @NotNull CompileOptions compileOptions(
            @NotNull Path projectPath,
            @NotNull COptimizationLevel optimizationLevel,
            @NotNull TargetPlatform targetPlatform,
            boolean strictMode,
            @NotNull String outputMountRoot
    ) {
        return new CompileOptions(
                GodotVersion.V451,
                projectPath,
                optimizationLevel,
                targetPlatform,
                strictMode,
                outputMountRoot
        );
    }

    static @NotNull CompileTaskSnapshot awaitTask(@NotNull API api, long taskId) {
        var deadline = System.currentTimeMillis() + TASK_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            var snapshot = api.getCompileTask(taskId);
            if (snapshot.completed()) {
                return snapshot;
            }
            sleepBriefly();
        }
        throw new AssertionError("Timed out waiting for compile task " + taskId);
    }

    static @NotNull CompileResult awaitResult(@NotNull API api, long taskId) {
        return Objects.requireNonNull(awaitTask(api, taskId).result());
    }

    static @NotNull CompileTaskSnapshot awaitSnapshot(
            @NotNull API api,
            long taskId,
            @NotNull Predicate<CompileTaskSnapshot> predicate,
            @NotNull String description
    ) {
        var deadline = System.currentTimeMillis() + TASK_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            var snapshot = api.getCompileTask(taskId);
            if (predicate.test(snapshot)) {
                return snapshot;
            }
            sleepBriefly();
        }
        throw new AssertionError("Timed out waiting for compile task " + taskId + " to reach " + description);
    }

    static void sleepForProgressPolling() {
        sleepBriefly();
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for compile task", exception);
        }
    }

    static final class SnapshotBlocker {
        private final @NotNull Predicate<CompileTaskSnapshot> predicate;
        private final @NotNull CountDownLatch enteredLatch = new CountDownLatch(1);
        private final @NotNull CountDownLatch releaseLatch = new CountDownLatch(1);
        private final @NotNull AtomicBoolean blocked = new AtomicBoolean();
        private final @NotNull AtomicReference<CompileTaskSnapshot> matchedSnapshot = new AtomicReference<>();

        SnapshotBlocker(@NotNull Predicate<CompileTaskSnapshot> predicate) {
            this.predicate = Objects.requireNonNull(predicate, "predicate must not be null");
        }

        @NotNull API.CompileTaskHooks hooks() {
            return API.CompileTaskHooks.afterSnapshotUpdate(snapshot -> {
                if (!predicate.test(snapshot) || !blocked.compareAndSet(false, true)) {
                    return;
                }
                matchedSnapshot.set(snapshot);
                enteredLatch.countDown();
                awaitReleaseIfNeeded();
            });
        }

        boolean awaitEntered() {
            return awaitLatch(enteredLatch);
        }

        void release() {
            releaseLatch.countDown();
        }

        @NotNull CompileTaskSnapshot matchedSnapshot() {
            return Objects.requireNonNull(matchedSnapshot.get(), "matchedSnapshot must not be null");
        }

        private void awaitReleaseIfNeeded() {
            if (!awaitLatch(releaseLatch)) {
                throw new AssertionError("Timed out waiting for snapshot blocker release");
            }
        }
    }

    static final class RecordingCompiler implements CCompiler {
        private final @NotNull List<InvocationOutcome> invocationOutcomes;
        private final @Nullable CountDownLatch enteredLatch;
        private final @Nullable CountDownLatch releaseLatch;
        private final @NotNull List<CompileTaskEvent> eventsToRecord;
        private volatile boolean ranOnVirtualThread;
        private final @NotNull AtomicInteger invocationCount = new AtomicInteger();
        private volatile @Nullable String lastOutputBaseName;
        private volatile @Nullable COptimizationLevel lastOptimizationLevel;
        private volatile @Nullable TargetPlatform lastTargetPlatform;
        private volatile @NotNull List<Path> lastIncludeDirs = List.of();
        private volatile @NotNull List<Path> lastCFiles = List.of();

        private RecordingCompiler(
                @NotNull List<InvocationOutcome> invocationOutcomes,
                @Nullable CountDownLatch enteredLatch,
                @Nullable CountDownLatch releaseLatch,
                @NotNull List<CompileTaskEvent> eventsToRecord
        ) {
            this.invocationOutcomes = List.copyOf(invocationOutcomes);
            this.enteredLatch = enteredLatch;
            this.releaseLatch = releaseLatch;
            this.eventsToRecord = List.copyOf(eventsToRecord);
        }

        static @NotNull RecordingCompiler succeeding() {
            return new RecordingCompiler(List.of(new InvocationOutcome(true, "ok")), null, null, List.of());
        }

        static @NotNull RecordingCompiler failing(@NotNull String buildLog) {
            return new RecordingCompiler(List.of(new InvocationOutcome(false, buildLog)), null, null, List.of());
        }

        static @NotNull RecordingCompiler succeedingThenFailing(@NotNull String secondBuildLog) {
            return new RecordingCompiler(
                    List.of(
                            new InvocationOutcome(true, "ok"),
                            new InvocationOutcome(false, secondBuildLog)
                    ),
                    null,
                    null,
                    List.of()
            );
        }

        static @NotNull RecordingCompiler blockingSuccess() {
            return new RecordingCompiler(
                    List.of(new InvocationOutcome(true, "ok")),
                    new CountDownLatch(1),
                    new CountDownLatch(1),
                    List.of()
            );
        }

        static @NotNull RecordingCompiler blockingSuccessWithEvents(@NotNull CompileTaskEvent... events) {
            return new RecordingCompiler(
                    List.of(new InvocationOutcome(true, "ok")),
                    new CountDownLatch(1),
                    new CountDownLatch(1),
                    List.of(events)
            );
        }

        @Override
        public @NotNull CBuildResult compile(
                @NotNull Path projectDir,
                @NotNull List<Path> includeDirs,
                @NotNull List<Path> cFiles,
                @NotNull String outputBaseName,
                @NotNull COptimizationLevel optimizationLevel,
                @NotNull TargetPlatform targetPlatform
        ) throws IOException {
            var invocationIndex = invocationCount.getAndIncrement();
            var outcome = invocationOutcome(invocationIndex);
            ranOnVirtualThread = Thread.currentThread().isVirtual();
            lastIncludeDirs = List.copyOf(includeDirs);
            lastCFiles = List.copyOf(cFiles);
            lastOutputBaseName = outputBaseName;
            lastOptimizationLevel = optimizationLevel;
            lastTargetPlatform = targetPlatform;
            for (var event : eventsToRecord) {
                if (!API.recordCurrentCompileTaskEvent(event.category(), event.detail())) {
                    throw new AssertionError("Expected compile task event recorder to be bound on compiler thread");
                }
            }
            if (enteredLatch != null) {
                enteredLatch.countDown();
            }
            awaitReleaseIfNeeded();
            if (!outcome.success()) {
                return new CBuildResult(false, outcome.buildLog(), List.of());
            }

            Files.createDirectories(projectDir);
            var artifact = projectDir.resolve(targetPlatform.sharedLibraryFileName(outputBaseName));
            Files.writeString(artifact, "dummy");
            return new CBuildResult(true, outcome.buildLog(), List.of(artifact));
        }

        int invocationCount() {
            return invocationCount.get();
        }

        @NotNull String lastOutputBaseName() {
            return Objects.requireNonNullElse(lastOutputBaseName, "");
        }

        @Nullable COptimizationLevel lastOptimizationLevel() {
            return lastOptimizationLevel;
        }

        @Nullable TargetPlatform lastTargetPlatform() {
            return lastTargetPlatform;
        }

        @NotNull List<Path> lastIncludeDirs() {
            return lastIncludeDirs;
        }

        @NotNull List<Path> lastCFiles() {
            return lastCFiles;
        }

        boolean ranOnVirtualThread() {
            return ranOnVirtualThread;
        }

        boolean awaitEntered() {
            return awaitLatch(enteredLatch);
        }

        void release() {
            if (releaseLatch != null) {
                releaseLatch.countDown();
            }
        }

        private void awaitReleaseIfNeeded() throws IOException {
            if (!awaitLatch(releaseLatch)) {
                throw new IOException("Timed out waiting for test compiler release");
            }
        }

        private boolean awaitLatch(@Nullable CountDownLatch latch) {
            if (latch == null) {
                return true;
            }
            try {
                return latch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for test latch", exception);
            }
        }

        private @NotNull InvocationOutcome invocationOutcome(int invocationIndex) {
            return invocationOutcomes.get(Math.min(invocationIndex, invocationOutcomes.size() - 1));
        }

        private record InvocationOutcome(boolean success, @NotNull String buildLog) {
            private InvocationOutcome {
                Objects.requireNonNull(buildLog, "buildLog must not be null");
            }
        }
    }

    private static boolean awaitLatch(@NotNull CountDownLatch latch) {
        try {
            return latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", exception);
        }
    }
}
