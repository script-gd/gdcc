package gd.script.gdcc.cli;

import gd.script.gdcc.api.API;
import gd.script.gdcc.api.CompileResult;
import gd.script.gdcc.api.CompileTaskEvent;
import gd.script.gdcc.api.task.CompileTaskHooks;
import gd.script.gdcc.backend.c.build.CCompileResult;
import gd.script.gdcc.backend.c.build.CCompiler;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.CProjectBuilder;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class CliCompileTestSupport {
    private static final @NotNull Duration COMPLETED_TASK_TTL = Duration.ofSeconds(5);
    private static final @NotNull Duration TASK_SWEEP_INTERVAL = Duration.ofMillis(50);

    private CliCompileTestSupport() {
    }

    static @NotNull API newApi(@NotNull TestCompiler compiler) {
        return newApi(new CProjectBuilder(compiler), CompileTaskHooks.none());
    }

    static @NotNull API newApi(@NotNull CProjectBuilder projectBuilder) {
        return newApi(projectBuilder, CompileTaskHooks.none());
    }

    static @NotNull API newApi(@NotNull TestCompiler compiler, @NotNull CompileTaskHooks hooks) {
        return newApi(new CProjectBuilder(compiler), hooks);
    }

    static @NotNull API newApi(@NotNull CProjectBuilder projectBuilder, @NotNull CompileTaskHooks hooks) {
        try {
            var constructor = API.class.getDeclaredConstructor(
                    Clock.class,
                    GdScriptParserService.class,
                    CProjectBuilder.class,
                    CompileTaskHooks.class,
                    Duration.class,
                    Duration.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(
                    Clock.systemUTC(),
                    new GdScriptParserService(),
                    projectBuilder,
                    hooks,
                    COMPLETED_TASK_TTL,
                    TASK_SWEEP_INTERVAL
            );
        } catch (InvocationTargetException exception) {
            var cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError("Failed to create test API", cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to create test API", exception);
        }
    }

    static @NotNull CompileResult awaitLastResult(@NotNull API api, @NotNull String moduleId) {
        var deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            var result = api.getLastCompileResult(moduleId);
            if (result != null) {
                return result;
            }
            sleepBriefly();
        }
        throw new AssertionError("Timed out waiting for last compile result");
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test state", exception);
        }
    }

    static final class TestCompiler implements CCompiler {
        private final boolean success;
        private final @NotNull String buildLog;
        private final @NotNull List<CompileTaskEvent> events;
        private final @Nullable CountDownLatch enteredLatch;
        private final @Nullable CountDownLatch releaseLatch;
        private final @NotNull AtomicInteger invocationCount = new AtomicInteger();
        private volatile @NotNull List<Path> lastIncludeDirs = List.of();
        private volatile @NotNull List<Path> lastCFiles = List.of();

        private TestCompiler(
                boolean success,
                @NotNull String buildLog,
                @NotNull List<CompileTaskEvent> events,
                @Nullable CountDownLatch enteredLatch,
                @Nullable CountDownLatch releaseLatch
        ) {
            this.success = success;
            this.buildLog = Objects.requireNonNull(buildLog, "buildLog must not be null");
            this.events = List.copyOf(events);
            this.enteredLatch = enteredLatch;
            this.releaseLatch = releaseLatch;
        }

        static @NotNull TestCompiler succeeding() {
            return new TestCompiler(true, "ok", List.of(), null, null);
        }

        static @NotNull TestCompiler succeeding(@NotNull String buildLog) {
            return new TestCompiler(true, buildLog, List.of(), null, null);
        }

        static @NotNull TestCompiler failing(@NotNull String buildLog) {
            return new TestCompiler(false, buildLog, List.of(), null, null);
        }

        static @NotNull TestCompiler succeedingWithEvents(@NotNull CompileTaskEvent... events) {
            return new TestCompiler(true, "ok", List.of(events), null, null);
        }

        static @NotNull TestCompiler blockingSuccess() {
            return new TestCompiler(true, "ok", List.of(), new CountDownLatch(1), new CountDownLatch(1));
        }

        @Override
        public @NotNull CCompileResult compile(
                @NotNull Path projectDir,
                @NotNull List<Path> includeDirs,
                @NotNull List<Path> cFiles,
                @NotNull String outputBaseName,
                @NotNull COptimizationLevel optimizationLevel,
                @NotNull TargetPlatform targetPlatform
        ) throws IOException {
            invocationCount.incrementAndGet();
            lastIncludeDirs = List.copyOf(includeDirs);
            lastCFiles = List.copyOf(cFiles);
            for (var event : events) {
                if (!API.recordCurrentCompileTaskEvent(event.category(), event.detail())) {
                    throw new AssertionError("Expected task event recorder to be bound");
                }
            }
            if (enteredLatch != null) {
                enteredLatch.countDown();
            }
            awaitReleaseIfNeeded();
            if (!success) {
                return new CCompileResult(false, buildLog, List.of());
            }

            Files.createDirectories(projectDir);
            var artifact = projectDir.resolve(targetPlatform.sharedLibraryFileName(outputBaseName));
            Files.writeString(artifact, "dummy");
            return new CCompileResult(true, buildLog, List.of(artifact));
        }

        int invocationCount() {
            return invocationCount.get();
        }

        @NotNull List<Path> lastIncludeDirs() {
            return lastIncludeDirs;
        }

        @NotNull List<Path> lastCFiles() {
            return lastCFiles;
        }

        boolean awaitEntered() {
            if (enteredLatch == null) {
                return true;
            }
            try {
                return enteredLatch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for compiler entry", exception);
            }
        }

        void release() {
            if (releaseLatch != null) {
                releaseLatch.countDown();
            }
        }

        private void awaitReleaseIfNeeded() throws IOException {
            if (releaseLatch == null) {
                return;
            }
            try {
                if (!releaseLatch.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for test compiler release");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for test compiler release", exception);
            }
        }
    }
}
