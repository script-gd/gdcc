package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.time.Duration.ofNanos;

public final class GodotGdextensionTestRunner {
    public static final String TEST_STOP_SIGNAL = "Test stop.";
    public static final Duration DEFAULT_FORCE_KILL_DELAY = Duration.ofMillis(100);
    public static final Duration DEFAULT_PROCESS_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_QUIT_AFTER_FRAMES = 10;
    private static final String GDEXTENSION_FILE_NAME = "GDExtensionTest.gdextension";
    private static final String GDEXTENSION_RESOURCE_PATH = "res://" + GDEXTENSION_FILE_NAME;

    private final @NotNull Path testProjectDir;
    private final @NotNull Path mainScenePath;

    public GodotGdextensionTestRunner(@NotNull Path testProjectDir) {
        this.testProjectDir = Objects.requireNonNull(testProjectDir).toAbsolutePath();
        this.mainScenePath = this.testProjectDir.resolve("main.tscn").toAbsolutePath();
    }

    public static @Nullable Path findGodotBinaryFromEnv() {
        var value = System.getenv("GODOT_BIN");
        if (value == null || value.isBlank()) {
            return null;
        }
        var candidate = Path.of(value).toAbsolutePath();
        return Files.exists(candidate) ? candidate : null;
    }

    public void prepareProject(@NotNull ProjectSetup setup) throws IOException {
        Objects.requireNonNull(setup);

        var binDir = testProjectDir.resolve("bin");
        Files.createDirectories(binDir);
        clearDirectory(binDir);

        var copiedArtifacts = copyArtifacts(setup.artifacts(), binDir);
        var targetPlatform = TargetPlatform.getNativePlatform();
        var dynamicLibrary = findDynamicLibrary(copiedArtifacts, librarySuffix(targetPlatform));
        var dynamicLibraryPath = testProjectDir.relativize(dynamicLibrary).toString();
        writeGdextensionFile("res://" + dynamicLibraryPath, targetPlatform);
        writeExtensionListFile();
        if (setup.testScript() != null) {
            writeTestScript(setup.testScript());
        }
        writeMainScene(setup.sceneNodes(), setup.testScript());
    }

    public @NotNull GodotRunResult run(boolean headless) throws IOException, InterruptedException {
        return run(defaultRunOptions(headless));
    }

    public static @NotNull RunOptions defaultRunOptions(boolean headless) {
        return new RunOptions(DEFAULT_QUIT_AFTER_FRAMES, headless, DEFAULT_PROCESS_TIMEOUT, DEFAULT_FORCE_KILL_DELAY);
    }

    public @NotNull GodotRunResult run(@NotNull RunOptions options) throws IOException, InterruptedException {
        Objects.requireNonNull(options);

        var totalStart = System.nanoTime();
        var binaryLookupStart = System.nanoTime();
        var godotBinary = requireGodotBinaryOrAbort();
        var binaryLookupDuration = elapsedSince(binaryLookupStart);
        var command = List.of(
                godotBinary.toString(),
                "--upwards",
                mainScenePath.toString(),
                options.headless ? "--headless" : "",
                "--quit-after",
                Integer.toString(options.quitAfterFrames())
        );

        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(testProjectDir.toFile());
        var processStart = System.nanoTime();
        var process = processBuilder.start();
        var processStartDuration = elapsedSince(processStart);
        var processStartedAt = System.nanoTime();

        var stopSignalSeen = new AtomicBoolean(false);
        var forceKillScheduled = new AtomicBoolean(false);
        var forceKilled = new AtomicBoolean(false);
        var firstOutputAt = new AtomicLong(-1);
        var stopSignalAt = new AtomicLong(-1);
        var forceKillFuture = new AtomicReference<ScheduledFuture<?>>();

        int exitCode;
        boolean timedOut;
        String stdout;
        String stderr;
        Duration processWaitDuration;
        Duration streamCollectionDuration;
        Duration executorCloseDuration;
        var streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
        var killer = Executors.newSingleThreadScheduledExecutor();
        try {
            Consumer<String> onLine = line -> {
                firstOutputAt.compareAndSet(-1, System.nanoTime());
                if (!line.contains(TEST_STOP_SIGNAL)) {
                    return;
                }
                stopSignalSeen.set(true);
                stopSignalAt.compareAndSet(-1, System.nanoTime());
                if (!forceKillScheduled.compareAndSet(false, true)) {
                    return;
                }
                var scheduled = killer.schedule(() -> {
                    if (!process.isAlive()) {
                        return;
                    }
                    forceKilled.set(true);
                    process.destroyForcibly();
                }, options.forceKillDelay().toMillis(), TimeUnit.MILLISECONDS);
                forceKillFuture.set(scheduled);
            };

            var stdoutFuture = streamExecutor.submit(() -> readAllLines(process.getInputStream(), onLine));
            var stderrFuture = streamExecutor.submit(() -> readAllLines(process.getErrorStream(), onLine));

            var processWaitStart = System.nanoTime();
            var exited = process.waitFor(options.processTimeout().toMillis(), TimeUnit.MILLISECONDS);
            processWaitDuration = elapsedSince(processWaitStart);
            timedOut = !exited;
            if (timedOut) {
                forceKilled.set(true);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }

            exitCode = process.isAlive() ? -1 : process.exitValue();
            var streamCollectionStart = System.nanoTime();
            stdout = getFutureText(stdoutFuture, "stdout");
            stderr = getFutureText(stderrFuture, "stderr");
            streamCollectionDuration = elapsedSince(streamCollectionStart);
            cancelScheduledForceKill(forceKillFuture);
        } finally {
            cancelScheduledForceKill(forceKillFuture);
            var executorCloseStart = System.nanoTime();
            try {
                streamExecutor.close();
            } finally {
                killer.close();
            }
            executorCloseDuration = elapsedSince(executorCloseStart);
        }

        var timing = new GodotRunResult.Timing(
                binaryLookupDuration,
                processStartDuration,
                durationBetween(processStartedAt, firstOutputAt.get()),
                durationBetween(processStartedAt, stopSignalAt.get()),
                processWaitDuration,
                streamCollectionDuration,
                executorCloseDuration,
                elapsedSince(totalStart)
        );
        return new GodotRunResult(exitCode, stopSignalSeen.get(), forceKilled.get(), timedOut, stdout, stderr, command, timing);
    }

    private static @NotNull Path requireGodotBinaryOrAbort() {
        var godotBinary = findGodotBinaryFromEnv();
        if (godotBinary != null) {
            return godotBinary;
        }
        Assumptions.abort("GODOT_BIN not found; skipping runtime integration test");
        throw new IllegalStateException("Unreachable after assumption abort");
    }

    private void writeMainScene(@NotNull List<SceneNodeSpec> sceneNodes, @Nullable TestScriptSpec testScript) throws IOException {
        var content = new StringBuilder();
        var loadSteps = testScript == null ? 2 : 3;
        content.append("[gd_scene load_steps=").append(loadSteps).append(" format=3]\n\n");
        content.append("[ext_resource type=\"Script\" path=\"res://root.gd\" id=\"1_root\"]\n");
        if (testScript != null) {
            content.append("[ext_resource type=\"Script\" path=\"")
                    .append(testScript.resourcePath())
                    .append("\" id=\"2_test\"]\n");
        }
        content.append("\n[node name=\"Root\" type=\"Node3D\"]\n");
        content.append("script = ExtResource(\"1_root\")\n\n");

        for (var sceneNode : sceneNodes) {
            appendSceneNode(content, sceneNode);
        }

        if (testScript != null) {
            var scriptNode = new SceneNodeSpec(
                    testScript.nodeName(),
                    testScript.nodeType(),
                    testScript.parentPath(),
                    Map.of("script", "ExtResource(\"2_test\")")
            );
            appendSceneNode(content, scriptNode);
        }

        Files.writeString(mainScenePath, content.toString(), StandardCharsets.UTF_8);
    }

    private static void appendSceneNode(@NotNull StringBuilder content, @NotNull SceneNodeSpec sceneNode) {
        content.append("[node name=\"")
                .append(sceneNode.nodeName())
                .append("\" type=\"")
                .append(sceneNode.nodeType())
                .append("\" parent=\"")
                .append(sceneNode.parentPath())
                .append("\"]\n");
        for (var property : sceneNode.properties().entrySet()) {
            content.append(property.getKey()).append(" = ").append(property.getValue()).append("\n");
        }
        content.append("\n");
    }

    private void writeTestScript(@NotNull TestScriptSpec testScript) throws IOException {
        var scriptPath = resolveResourcePath(testScript.resourcePath());
        var parentDir = scriptPath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }
        Files.writeString(scriptPath, testScript.scriptContent(), StandardCharsets.UTF_8);
    }

    private @NotNull Path resolveResourcePath(@NotNull String resourcePath) throws IOException {
        if (!resourcePath.startsWith("res://")) {
            throw new IOException("Resource path must start with res:// : " + resourcePath);
        }
        var relativePath = resourcePath.substring("res://".length());
        if (relativePath.isBlank()) {
            throw new IOException("Resource path cannot be empty: " + resourcePath);
        }
        return testProjectDir.resolve(relativePath).normalize();
    }

    private static @NotNull String getFutureText(
            @NotNull java.util.concurrent.Future<String> future,
            @NotNull String streamName
    ) throws IOException, InterruptedException {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IOException("Failed to collect " + streamName + " stream", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("Timed out while collecting " + streamName + " stream", e);
        }
    }

    private static @NotNull String readAllLines(@NotNull InputStream stream, @NotNull Consumer<String> onLine) throws IOException {
        var builder = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append(System.lineSeparator());
                onLine.accept(line);
            }
        }
        return builder.toString();
    }

    private static @NotNull Path findDynamicLibrary(@NotNull List<Path> copiedArtifacts, @NotNull String preferredSuffix) throws IOException {
        Path fallback = null;
        for (var artifact : copiedArtifacts) {
            var artifactName = artifact.getFileName().toString();
            if (!isDynamicLibrary(artifactName)) {
                continue;
            }
            if (artifactName.endsWith(preferredSuffix)) {
                return artifact;
            }
            if (fallback == null) {
                fallback = artifact;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IOException("No dynamic library artifact found in build output");
    }

    private static boolean isDynamicLibrary(@NotNull String artifactName) {
        return artifactName.endsWith(".dll")
                || artifactName.endsWith(".so")
                || artifactName.endsWith(".dylib")
                || artifactName.endsWith(".wasm");
    }

    private void writeGdextensionFile(@NotNull String libraryPath, @NotNull TargetPlatform targetPlatform) throws IOException {
        Files.writeString(
                testProjectDir.resolve(GDEXTENSION_FILE_NAME),
                GdextensionMetadataFile.render(
                        libraryPath.replace('\\', '/'),
                        COptimizationLevel.DEBUG,
                        targetPlatform
                ),
                StandardCharsets.UTF_8
        );
    }

    private void writeExtensionListFile() throws IOException {
        var extensionListPath = testProjectDir.resolve(".godot").resolve("extension_list.cfg");
        Files.createDirectories(extensionListPath.getParent());
        // Plain runtime launches do not perform the editor filesystem scan that discovers .gdextension files.
        Files.writeString(extensionListPath, GDEXTENSION_RESOURCE_PATH + "\n", StandardCharsets.UTF_8);
    }

    private static @NotNull List<Path> copyArtifacts(@NotNull List<Path> artifacts, @NotNull Path binDir) throws IOException {
        var copied = new ArrayList<Path>(artifacts.size());
        for (var artifact : artifacts) {
            if (!Files.exists(artifact)) {
                throw new IOException("Artifact not found: " + artifact);
            }
            var targetPath = binDir.resolve(artifact.getFileName().toString());
            Files.copy(artifact, targetPath, StandardCopyOption.REPLACE_EXISTING);
            copied.add(targetPath);
        }
        return copied;
    }

    private static void clearDirectory(@NotNull Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (path.equals(dir)) {
                    continue;
                }
                Files.deleteIfExists(path);
            }
        }
    }

    private static @NotNull String librarySuffix(@NotNull TargetPlatform targetPlatform) {
        return switch (targetPlatform) {
            case WINDOWS_X86_64, WINDOWS_AARCH64 -> ".dll";
            case LINUX_X86_64, LINUX_AARCH64, LINUX_RISCV64, ANDROID_X86_64, ANDROID_AARCH64 -> ".so";
            case WEB_WASM32 -> ".wasm";
        };
    }

    /// Scene node declaration that will be written into main.tscn.
    public record SceneNodeSpec(
            @NotNull String nodeName,
            @NotNull String nodeType,
            @NotNull String parentPath,
            @NotNull Map<String, String> properties
    ) {
        public SceneNodeSpec {
            nodeName = requireNotBlank(nodeName, "nodeName");
            nodeType = requireNotBlank(nodeType, "nodeType");
            parentPath = parentPath.isBlank() ? "." : parentPath;
            properties = new LinkedHashMap<>(Objects.requireNonNull(properties));
        }
    }

    /// Optional test script resource that can be attached to a scene node.
    public record TestScriptSpec(
            @NotNull String resourcePath,
            @NotNull String scriptContent,
            @NotNull String nodeName,
            @NotNull String nodeType,
            @NotNull String parentPath
    ) {
        public TestScriptSpec {
            resourcePath = requireNotBlank(resourcePath, "resourcePath");
            scriptContent = Objects.requireNonNull(scriptContent);
            nodeName = requireNotBlank(nodeName, "nodeName");
            nodeType = nodeType.isBlank() ? "Node" : nodeType;
            parentPath = parentPath.isBlank() ? "." : parentPath;
            if (!resourcePath.startsWith("res://")) {
                throw new IllegalArgumentException("resourcePath must start with res:// : " + resourcePath);
            }
        }

        public TestScriptSpec(@NotNull String scriptContent) {
            this("res://test_script.gd", scriptContent, "TestScriptNode", "Node", ".");
        }
    }

    /// Setup data used to update test_project assets before running Godot.
    public record ProjectSetup(
            @NotNull List<Path> artifacts,
            @NotNull List<SceneNodeSpec> sceneNodes,
            @Nullable TestScriptSpec testScript
    ) {
        public ProjectSetup {
            artifacts = List.copyOf(Objects.requireNonNull(artifacts));
            sceneNodes = List.copyOf(Objects.requireNonNull(sceneNodes));
        }
    }

    /// Runtime options used to launch Godot.
    public record RunOptions(
            int quitAfterFrames,
            boolean headless,
            @NotNull Duration processTimeout,
            @NotNull Duration forceKillDelay
    ) {
        public RunOptions {
            if (quitAfterFrames <= 0) {
                throw new IllegalArgumentException("quitAfterFrames must be > 0");
            }
        }

        public @NotNull RunOptions withQuitAfterFrames(int quitAfterFrames) {
            return new RunOptions(quitAfterFrames, headless, processTimeout, forceKillDelay);
        }
    }

    public record GodotRunResult(
            int exitCode,
            boolean stopSignalSeen,
            boolean forceKilled,
            boolean timedOut,
            @NotNull String stdout,
            @NotNull String stderr,
            @NotNull List<String> command,
            @NotNull Timing timing
    ) {
        public GodotRunResult {
            stdout = Objects.requireNonNull(stdout);
            stderr = Objects.requireNonNull(stderr);
            command = List.copyOf(command);
            Objects.requireNonNull(timing);
        }

        public GodotRunResult(
                int exitCode,
                boolean stopSignalSeen,
                boolean forceKilled,
                boolean timedOut,
                @NotNull String stdout,
                @NotNull String stderr,
                @NotNull List<String> command
        ) {
            this(exitCode, stopSignalSeen, forceKilled, timedOut, stdout, stderr, command, Timing.zero());
        }

        public @NotNull String combinedOutput() {
            if (stderr.isBlank()) {
                return stdout;
            }
            if (stdout.isBlank()) {
                return stderr;
            }
            return stdout + System.lineSeparator() + stderr;
        }

        public record Timing(
                @NotNull Duration binaryLookup,
                @NotNull Duration processStart,
                @Nullable Duration firstOutputLatency,
                @Nullable Duration stopSignalLatency,
                @NotNull Duration processWait,
                @NotNull Duration streamCollection,
                @NotNull Duration executorClose,
                @NotNull Duration total
        ) {
            public Timing {
                Objects.requireNonNull(binaryLookup);
                Objects.requireNonNull(processStart);
                Objects.requireNonNull(processWait);
                Objects.requireNonNull(streamCollection);
                Objects.requireNonNull(executorClose);
                Objects.requireNonNull(total);
            }

            public static @NotNull Timing zero() {
                return new Timing(
                        Duration.ZERO,
                        Duration.ZERO,
                        null,
                        null,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO
                );
            }
        }
    }

    private static @NotNull String requireNotBlank(@Nullable String value, @NotNull String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static @NotNull Duration elapsedSince(long startNanos) {
        return ofNanos(System.nanoTime() - startNanos);
    }

    private static @Nullable Duration durationBetween(long startNanos, long endNanos) {
        return endNanos < 0 ? null : ofNanos(endNanos - startNanos);
    }

    private static void cancelScheduledForceKill(@NotNull AtomicReference<ScheduledFuture<?>> forceKillFuture) {
        var scheduled = forceKillFuture.getAndSet(null);
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }
}
