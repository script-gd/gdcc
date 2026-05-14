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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static java.time.Duration.ofNanos;

/// Prepares and runs the shared Godot GDExtension test project for Java integration tests.
///
/// The runner has two separate responsibilities:
///
/// - `prepareProject` rewrites `test_project` so the generated native library is installed
///   through a `.gdextension` file and the requested test nodes are present in `main.tscn`.
/// - `run` launches Godot, watches stdout/stderr for `TEST_STOP_SIGNAL`, and returns as soon
///   as the test contract has enough output to validate the case.
///
/// Godot process management is intentionally asymmetric. The Java test thread waits only for
/// a stop signal, a process exit, or the configured process timeout. Once `TEST_STOP_SIGNAL`
/// appears, a background virtual thread gives Godot a short grace period to exit naturally and
/// then force-kills it if needed. This keeps the test suite fast while still preventing stuck
/// Godot processes from outliving the test run indefinitely.
public final class GodotGdextensionTestRunner {
    /// Output marker printed by `test_project/root.gd` during Godot tree shutdown.
    ///
    /// Runtime tests treat this as the Java-side completion signal. Validation scripts still
    /// provide their own pass markers; this signal only proves Godot reached the shared project
    /// shutdown path.
    public static final String TEST_STOP_SIGNAL = "Test stop.";

    /// Grace period given to Godot after `TEST_STOP_SIGNAL` before the background cleanup thread
    /// force-kills the process.
    public static final Duration DEFAULT_FORCE_KILL_DELAY = Duration.ofSeconds(1);

    /// Maximum time the Java caller waits for either a stop signal or full process exit.
    public static final Duration DEFAULT_PROCESS_TIMEOUT = Duration.ofSeconds(30);

    /// Default Godot frame budget used by runtime tests that do not need a custom delay.
    public static final int DEFAULT_QUIT_AFTER_FRAMES = 10;
    private static final String GDEXTENSION_FILE_NAME = "GDExtensionTest.gdextension";
    private static final String GDEXTENSION_RESOURCE_PATH = "res://" + GDEXTENSION_FILE_NAME;

    private final @NotNull Path testProjectDir;
    private final @NotNull Path mainScenePath;

    /// Creates a runner bound to one Godot project directory.
    ///
    /// The path is normalized eagerly because generated scene and resource paths are resolved
    /// relative to this directory throughout project preparation and launch.
    public GodotGdextensionTestRunner(@NotNull Path testProjectDir) {
        this.testProjectDir = Objects.requireNonNull(testProjectDir).toAbsolutePath();
        this.mainScenePath = this.testProjectDir.resolve("main.tscn").toAbsolutePath();
    }

    /// Resolves `GODOT_BIN` to an existing local executable path.
    ///
    /// Returning `null` is intentional: runtime integration tests use this to skip themselves
    /// through JUnit assumptions when the machine does not have Godot configured.
    public static @Nullable Path findGodotBinaryFromEnv() {
        var value = System.getenv("GODOT_BIN");
        if (value == null || value.isBlank()) {
            return null;
        }
        var candidate = Path.of(value).toAbsolutePath();
        return Files.exists(candidate) ? candidate : null;
    }

    /// Rewrites the Godot project assets for one test case.
    ///
    /// Preparation is destructive within the generated parts of `testProjectDir`: stale native
    /// artifacts under `bin/` are removed, the GDExtension metadata is regenerated, and `main.tscn`
    /// is replaced with the requested scene graph. The rest of the reusable Godot fixture stays
    /// untouched.
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

    /// Runs Godot with the default runtime options for the requested display mode.
    public @NotNull GodotRunResult run(boolean headless) throws IOException, InterruptedException {
        return run(defaultRunOptions(headless));
    }

    /// Builds the standard runtime options used by most integration tests.
    ///
    /// Individual tests can keep these defaults and override only the frame budget with
    /// `RunOptions.withQuitAfterFrames`.
    public static @NotNull RunOptions defaultRunOptions(boolean headless) {
        return new RunOptions(DEFAULT_QUIT_AFTER_FRAMES, headless, DEFAULT_PROCESS_TIMEOUT, DEFAULT_FORCE_KILL_DELAY);
    }

    /// Launches Godot and returns the output collected up to the completion point.
    ///
    /// Completion can happen in three ways:
    ///
    /// - `STOP_SIGNAL`: stdout or stderr contains `TEST_STOP_SIGNAL`. This is the normal fast
    ///   path. The method returns immediately after the signal is observed; process cleanup keeps
    ///   running on a background virtual thread.
    /// - `PROCESS_EXIT`: Godot exits before the stop signal appears. In this path both streams are
    ///   joined so the failure message contains complete process output.
    /// - `TIMEOUT`: neither signal nor exit arrives before `processTimeout`. The process is
    ///   destroyed forcibly and the returned result is marked timed out.
    ///
    /// On the stop-signal path, `exitCode` can remain `-1` because the process may still be
    /// shutting down after the Java test has already received enough output to continue.
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
        var process = startProcess(processBuilder, options.processTimeout());
        var processStartDuration = elapsedSince(processStart);
        var processStartedAt = System.nanoTime();

        var runCompletion = new CompletableFuture<RunCompletion>();
        var stopSignalSeen = new AtomicBoolean(false);
        var forceKilled = new AtomicBoolean(false);
        var firstOutputAt = new AtomicLong(-1);
        var stopSignalAt = new AtomicLong(-1);
        var exitCode = new AtomicInteger(-1);
        var stdoutBuffer = new StringBuffer();
        var stderrBuffer = new StringBuffer();

        RunCompletion completedBy;
        Duration processWaitDuration;
        Duration streamCollectionDuration;
        try {
            Consumer<String> onLine = line -> {
                firstOutputAt.compareAndSet(-1, System.nanoTime());
                if (!line.contains(TEST_STOP_SIGNAL)) {
                    return;
                }
                if (!stopSignalSeen.compareAndSet(false, true)) {
                    return;
                }
                stopSignalAt.compareAndSet(-1, System.nanoTime());
                startProcessExitAfterStopSignalWatcher(process, options.forceKillDelay(), forceKilled);
                runCompletion.complete(RunCompletion.STOP_SIGNAL);
            };

            var stdoutReader = startStreamReader("stdout", process.getInputStream(), stdoutBuffer, onLine, runCompletion);
            var stderrReader = startStreamReader("stderr", process.getErrorStream(), stderrBuffer, onLine, runCompletion);
            startProcessWaiter(process, exitCode, runCompletion);

            var processWaitStart = System.nanoTime();
            completedBy = awaitRunCompletion(runCompletion, options.processTimeout());
            processWaitDuration = elapsedSince(processWaitStart);
            if (completedBy == RunCompletion.TIMEOUT && process.isAlive()) {
                forceKilled.set(true);
                process.destroyForcibly();
            }
            if (!process.isAlive()) {
                exitCode.compareAndSet(-1, process.exitValue());
            }
            var streamCollectionStart = System.nanoTime();
            if (completedBy == RunCompletion.PROCESS_EXIT) {
                joinStreamReader(stdoutReader, "stdout");
                joinStreamReader(stderrReader, "stderr");
            }
            streamCollectionDuration = elapsedSince(streamCollectionStart);
        } catch (IOException | InterruptedException e) {
            if (process.isAlive()) {
                forceKilled.set(true);
                process.destroyForcibly();
            }
            throw e;
        }
        var stdout = stdoutBuffer.toString();
        var stderr = stderrBuffer.toString();

        var timing = new GodotRunResult.Timing(
                binaryLookupDuration,
                processStartDuration,
                durationBetween(processStartedAt, firstOutputAt.get()),
                durationBetween(processStartedAt, stopSignalAt.get()),
                processWaitDuration,
                streamCollectionDuration,
                Duration.ZERO,
                elapsedSince(totalStart)
        );
        return new GodotRunResult(
                exitCode.get(),
                stopSignalSeen.get(),
                forceKilled.get(),
                completedBy == RunCompletion.TIMEOUT,
                stdout,
                stderr,
                command,
                timing
        );
    }

    private static @NotNull Path requireGodotBinaryOrAbort() {
        var godotBinary = findGodotBinaryFromEnv();
        if (godotBinary != null) {
            return godotBinary;
        }
        Assumptions.abort("GODOT_BIN not found; skipping runtime integration test");
        throw new IllegalStateException("Unreachable after assumption abort");
    }

    /// Starts the Godot process from a virtual thread and waits only for the process handle.
    ///
    /// Process creation is normally quick, but keeping it off the caller thread makes the launcher
    /// follow the same timeout shape as the rest of the Godot lifecycle. A failure to spawn is
    /// reported as `IOException`, matching `ProcessBuilder.start`.
    private static @NotNull Process startProcess(
            @NotNull ProcessBuilder processBuilder,
            @NotNull Duration timeout
    ) throws IOException, InterruptedException {
        var started = new CompletableFuture<Process>();
        Thread.ofVirtual().name("gdcc-godot-start-", 0).start(() -> {
            try {
                started.complete(processBuilder.start());
            } catch (IOException e) {
                started.completeExceptionally(e);
            }
        });
        try {
            return started.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Godot process start failed", cause);
        } catch (TimeoutException e) {
            throw new IOException("Timed out while starting Godot process", e);
        }
    }

    /// Renders `main.tscn` with the shared root script, generated runtime nodes, and an optional
    /// validation script node.
    ///
    /// The generated scene is intentionally small: it only contains enough structure for Godot to
    /// instantiate the compiled classes and let the validation script exercise them.
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

    /// Appends one scene node block to the generated `.tscn` file.
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

    /// Writes the optional validation script into the Godot project as a `res://` resource.
    private void writeTestScript(@NotNull TestScriptSpec testScript) throws IOException {
        var scriptPath = resolveResourcePath(testScript.resourcePath());
        var parentDir = scriptPath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }
        Files.writeString(scriptPath, testScript.scriptContent(), StandardCharsets.UTF_8);
    }

    /// Converts a Godot `res://` path into a filesystem path inside `testProjectDir`.
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

    /// Starts a virtual-thread reader for one process stream.
    ///
    /// Each line is appended to the shared output buffer before being inspected by `onLine`, so
    /// the stop signal line is always available to assertion failures even when the caller returns
    /// before the underlying process exits.
    private static @NotNull Thread startStreamReader(
            @NotNull String streamName,
            @NotNull InputStream stream,
            @NotNull StringBuffer output,
            @NotNull Consumer<String> onLine,
            @NotNull CompletableFuture<RunCompletion> runCompletion
    ) {
        return Thread.ofVirtual().name("gdcc-godot-" + streamName + "-", 0).start(() -> {
            try {
                readLinesInto(stream, output, onLine);
            } catch (IOException e) {
                runCompletion.completeExceptionally(
                        new IOException("Failed to collect " + streamName + " stream", e)
                );
            }
        });
    }

    /// Starts a virtual thread that waits for full Godot process exit.
    ///
    /// This waiter is not joined on the normal stop-signal path. It exists to capture early exits
    /// and to complete the same lifecycle future if Godot terminates before printing
    /// `TEST_STOP_SIGNAL`.
    private static void startProcessWaiter(
            @NotNull Process process,
            @NotNull AtomicInteger exitCode,
            @NotNull CompletableFuture<RunCompletion> runCompletion
    ) {
        Thread.ofVirtual().name("gdcc-godot-process-wait-", 0).start(() -> {
            try {
                exitCode.set(process.waitFor());
                runCompletion.complete(RunCompletion.PROCESS_EXIT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                runCompletion.completeExceptionally(e);
            }
        });
    }

    /// Starts the post-stop cleanup watcher.
    ///
    /// Once Java-side validation has seen `TEST_STOP_SIGNAL`, the caller can return immediately.
    /// This watcher gives Godot `forceKillDelay` to perform its own shutdown and then force-kills
    /// it if it is still alive. Because the watcher runs on a virtual thread, a stuck process does
    /// not pin the JUnit execution thread.
    private static void startProcessExitAfterStopSignalWatcher(
            @NotNull Process process,
            @NotNull Duration forceKillDelay,
            @NotNull AtomicBoolean forceKilled
    ) {
        Thread.ofVirtual().name("gdcc-godot-stop-wait-", 0).start(() -> {
            try {
                if (process.waitFor(forceKillDelay.toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
                if (process.isAlive()) {
                    forceKilled.set(true);
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (process.isAlive()) {
                    forceKilled.set(true);
                    process.destroyForcibly();
                }
            }
        });
    }

    /// Waits for the lifecycle future to finish or maps a timeout into `RunCompletion.TIMEOUT`.
    ///
    /// Stream-reader failures are surfaced through the same future, so the main runner does not
    /// need to poll background thread state separately.
    private static @NotNull RunCompletion awaitRunCompletion(
            @NotNull CompletableFuture<RunCompletion> runCompletion,
            @NotNull Duration timeout
    ) throws IOException, InterruptedException {
        try {
            return runCompletion.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw interruptedException;
            }
            throw new IOException("Godot runner failed", cause);
        } catch (TimeoutException e) {
            return RunCompletion.TIMEOUT;
        }
    }

    /// Joins a stream reader after the process has already exited.
    ///
    /// This is only used on the early-exit path, where complete output is more important than
    /// shaving off a few milliseconds because the test will usually fail and needs diagnostics.
    private static void joinStreamReader(@NotNull Thread reader, @NotNull String streamName) throws IOException, InterruptedException {
        reader.join(5_000);
        if (reader.isAlive()) {
            throw new IOException("Timed out while collecting " + streamName + " stream");
        }
    }

    /// Reads one process stream line by line and forwards each line to the lifecycle observer.
    private static void readLinesInto(
            @NotNull InputStream stream,
            @NotNull StringBuffer output,
            @NotNull Consumer<String> onLine
    ) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
                onLine.accept(line);
            }
        }
    }

    /// Finds the copied dynamic library that best matches the current target platform.
    ///
    /// Some build outputs include several platform artifacts. The platform suffix is preferred,
    /// but any dynamic library is accepted as a fallback for tests that only create placeholder
    /// artifacts.
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

    /// Returns whether the artifact name can be loaded through Godot's GDExtension library table.
    private static boolean isDynamicLibrary(@NotNull String artifactName) {
        return artifactName.endsWith(".dll")
                || artifactName.endsWith(".so")
                || artifactName.endsWith(".dylib")
                || artifactName.endsWith(".wasm");
    }

    /// Writes the generated `.gdextension` metadata that points Godot at the selected library.
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

    /// Writes Godot's extension cache file for plain runtime launches.
    ///
    /// The editor can discover `.gdextension` files through a filesystem scan, but headless test
    /// launches do not run that import step. Writing this file makes the test project independent
    /// from a previously opened editor cache.
    private void writeExtensionListFile() throws IOException {
        var extensionListPath = testProjectDir.resolve(".godot").resolve("extension_list.cfg");
        Files.createDirectories(extensionListPath.getParent());
        // Plain runtime launches do not perform the editor filesystem scan that discovers .gdextension files.
        Files.writeString(extensionListPath, GDEXTENSION_RESOURCE_PATH + "\n", StandardCharsets.UTF_8);
    }

    /// Copies native build outputs into the Godot project's `bin/` directory.
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

    /// Removes stale files from a generated directory while keeping the directory itself.
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

    /// Returns the preferred dynamic-library suffix for the platform-specific artifact selection.
    private static @NotNull String librarySuffix(@NotNull TargetPlatform targetPlatform) {
        return switch (targetPlatform) {
            case WINDOWS_X86_64, WINDOWS_AARCH64 -> ".dll";
            case LINUX_X86_64, LINUX_AARCH64, LINUX_RISCV64, ANDROID_X86_64, ANDROID_AARCH64 -> ".so";
            case WEB_WASM32 -> ".wasm";
        };
    }

    /// Event that completed the Java-side wait for a Godot run.
    ///
    /// `STOP_SIGNAL` is the normal success path for the unit-test runner. `PROCESS_EXIT` usually
    /// means Godot exited before the shared stop marker was printed. `TIMEOUT` means Java gave up
    /// waiting and destroyed the process.
    private enum RunCompletion {
        STOP_SIGNAL,
        PROCESS_EXIT,
        TIMEOUT
    }

    /// Scene node declaration that will be written into `main.tscn`.
    ///
    /// `nodeType` can name either a built-in Godot type or a GDExtension class registered by the
    /// generated library. `parentPath` defaults to `.` so tests can mount nodes directly under the
    /// generated root without repeating that path at every call site.
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

    /// Optional validation script resource that can be attached to a scene node.
    ///
    /// The default constructor writes `res://test_script.gd` as a `Node` under the generated root.
    /// Tests that need a different script node type or path can provide the full record.
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

    /// Setup data used to update `test_project` assets before running Godot.
    ///
    /// `artifacts` are copied into `bin/`, `sceneNodes` become runtime nodes in `main.tscn`, and
    /// `testScript` is optional validation logic executed by the Godot scene.
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

    /// Runtime options used to launch and supervise Godot.
    ///
    /// `processTimeout` bounds the Java-side wait for either a stop signal or an early process
    /// exit. `forceKillDelay` is only used after the stop signal; it does not delay the caller,
    /// because cleanup happens on a background virtual thread.
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

        /// Returns a copy with a different `--quit-after` frame budget.
        public @NotNull RunOptions withQuitAfterFrames(int quitAfterFrames) {
            return new RunOptions(quitAfterFrames, headless, processTimeout, forceKillDelay);
        }
    }

    /// Result returned from a single Godot runtime launch.
    ///
    /// `stopSignalSeen` is the primary success signal for this test runner. `exitCode` may remain
    /// `-1` on that path because the Java caller returns before full process shutdown. `timedOut`
    /// is reserved for the process-timeout path where no stop signal or exit arrived in time.
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

        /// Returns stdout and stderr in the same text blob used by assertion diagnostics.
        public @NotNull String combinedOutput() {
            if (stderr.isBlank()) {
                return stdout;
            }
            if (stdout.isBlank()) {
                return stderr;
            }
            return stdout + System.lineSeparator() + stderr;
        }

        /// Timing breakdown for one Godot launch.
        ///
        /// `processWait` measures the caller-visible wait for `STOP_SIGNAL`, `PROCESS_EXIT`, or
        /// `TIMEOUT`. `executorClose` is kept for compatibility with earlier timing output, but is
        /// now always zero because process cleanup no longer closes a blocking executor.
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

            /// Empty timing used by tests that construct `GodotRunResult` directly.
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

    /// Validates required record text fields at the public setup boundary.
    private static @NotNull String requireNotBlank(@Nullable String value, @NotNull String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    /// Returns the elapsed duration since a `System.nanoTime` sample.
    private static @NotNull Duration elapsedSince(long startNanos) {
        return ofNanos(System.nanoTime() - startNanos);
    }

    /// Returns a duration between two nano-time samples, or `null` if the event never happened.
    private static @Nullable Duration durationBetween(long startNanos, long endNanos) {
        return endNanos < 0 ? null : ofNanos(endNanos - startNanos);
    }
}
