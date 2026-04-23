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
import java.util.concurrent.atomic.AtomicInteger;

final class ApiCompileTestSupport {
    static final Instant FIXED_TIME = Instant.parse("2026-04-23T10:15:30Z");
    static final Clock FIXED_CLOCK = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
    private static final long TASK_TIMEOUT_MILLIS = 30_000;

    private ApiCompileTestSupport() {
    }

    static @NotNull API newApi(@NotNull RecordingCompiler compiler) {
        return new API(
                FIXED_CLOCK,
                new GdScriptParserService(),
                new FrontendLoweringPassManager(),
                new CProjectBuilder(compiler)
        );
    }

    static @NotNull CompileOptions compileOptions(@NotNull Path projectPath) {
        return compileOptions(projectPath, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform(), false);
    }

    static @NotNull CompileOptions compileOptions(
            @NotNull Path projectPath,
            @NotNull COptimizationLevel optimizationLevel,
            @NotNull TargetPlatform targetPlatform,
            boolean strictMode
    ) {
        return new CompileOptions(
                GodotVersion.V451,
                projectPath,
                optimizationLevel,
                targetPlatform,
                strictMode,
                CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT
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

    private static void sleepBriefly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for compile task", exception);
        }
    }

    static final class RecordingCompiler implements CCompiler {
        private final boolean success;
        private final @NotNull String buildLog;
        private final @Nullable CountDownLatch enteredLatch;
        private final @Nullable CountDownLatch releaseLatch;
        private volatile boolean ranOnVirtualThread;
        private final @NotNull AtomicInteger invocationCount = new AtomicInteger();
        private volatile @Nullable String lastOutputBaseName;
        private volatile @Nullable COptimizationLevel lastOptimizationLevel;
        private volatile @Nullable TargetPlatform lastTargetPlatform;
        private volatile @NotNull List<Path> lastIncludeDirs = List.of();
        private volatile @NotNull List<Path> lastCFiles = List.of();

        private RecordingCompiler(
                boolean success,
                @NotNull String buildLog,
                @Nullable CountDownLatch enteredLatch,
                @Nullable CountDownLatch releaseLatch
        ) {
            this.success = success;
            this.buildLog = buildLog;
            this.enteredLatch = enteredLatch;
            this.releaseLatch = releaseLatch;
        }

        static @NotNull RecordingCompiler succeeding() {
            return new RecordingCompiler(true, "ok", null, null);
        }

        static @NotNull RecordingCompiler failing(@NotNull String buildLog) {
            return new RecordingCompiler(false, buildLog, null, null);
        }

        static @NotNull RecordingCompiler blockingSuccess() {
            return new RecordingCompiler(true, "ok", new CountDownLatch(1), new CountDownLatch(1));
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
            invocationCount.incrementAndGet();
            ranOnVirtualThread = Thread.currentThread().isVirtual();
            lastIncludeDirs = List.copyOf(includeDirs);
            lastCFiles = List.copyOf(cFiles);
            lastOutputBaseName = outputBaseName;
            lastOptimizationLevel = optimizationLevel;
            lastTargetPlatform = targetPlatform;
            if (enteredLatch != null) {
                enteredLatch.countDown();
            }
            awaitReleaseIfNeeded();
            if (!success) {
                return new CBuildResult(false, buildLog, List.of());
            }

            Files.createDirectories(projectDir);
            var artifact = projectDir.resolve(targetPlatform.sharedLibraryFileName(outputBaseName));
            Files.writeString(artifact, "dummy");
            return new CBuildResult(true, buildLog, List.of(artifact));
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
    }
}
