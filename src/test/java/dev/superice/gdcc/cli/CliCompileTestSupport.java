package dev.superice.gdcc.cli;

import dev.superice.gdcc.api.API;
import dev.superice.gdcc.api.CompileResult;
import dev.superice.gdcc.api.CompileTaskEvent;
import dev.superice.gdcc.api.task.CompileTaskHooks;
import dev.superice.gdcc.backend.c.build.CBuildResult;
import dev.superice.gdcc.backend.c.build.CCompiler;
import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.CProjectBuilder;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPassManager;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
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
        return newApi(compiler, CompileTaskHooks.none());
    }

    static @NotNull API newApi(@NotNull TestCompiler compiler, @NotNull CompileTaskHooks hooks) {
        try {
            var constructor = API.class.getDeclaredConstructor(
                    Clock.class,
                    GdScriptParserService.class,
                    FrontendLoweringPassManager.class,
                    CProjectBuilder.class,
                    CompileTaskHooks.class,
                    Duration.class,
                    Duration.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(
                    Clock.systemUTC(),
                    new GdScriptParserService(),
                    new FrontendLoweringPassManager(),
                    new CProjectBuilder(compiler),
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
        public @NotNull CBuildResult compile(
                @NotNull Path projectDir,
                @NotNull List<Path> includeDirs,
                @NotNull List<Path> cFiles,
                @NotNull String outputBaseName,
                @NotNull COptimizationLevel optimizationLevel,
                @NotNull TargetPlatform targetPlatform
        ) throws IOException {
            invocationCount.incrementAndGet();
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
