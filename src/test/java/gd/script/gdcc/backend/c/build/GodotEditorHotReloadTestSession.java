package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/// Orchestrates one headless Godot editor hot-reload test session.
///
/// The session is the editor-side counterpart of `GodotGdextensionTestRunner` (which stays
/// untouched and keeps serving plain runtime launches). It implements the orchestration protocol
/// from `doc/module_impl/backend/hot_reload_implementation_plan.md` §HR-9:
///
/// - `prepareProject` writes a minimal editor project (project.godot, `bin/` native library,
///   `reloadable = true` `.gdextension`, interpreted SceneTree driver script).
/// - `start` launches `godot --headless --editor --path <project> --script res://driver.gd`
///   and collects both output streams line by line.
/// - The driver instantiates the gdcc classes under test via `ClassDB.instantiate`, asserts
///   pre-reload behavior and prints a phase marker (e.g. `HR_PHASE1_OK`).
/// - `swapLibrary` atomically renames a freshly built library over the loaded one and drops the
///   swap flag file the driver polls.
/// - The driver then calls `GDExtensionManager.reload_extension(...)` (synchronous: instance
///   recreation and property restore complete before it returns), asserts post-reload behavior,
///   prints the next marker and quits with a result code.
///
/// All editor-side assertions live in the driver; Java only arbitrates markers, the library
/// swap, the exit code and engine-side error substrings. Editor startup is much heavier than a
/// plain runtime launch, so marker/exit timeouts are sized accordingly.
public final class GodotEditorHotReloadTestSession implements AutoCloseable {
    public static final String PHASE1_MARKER = "HR_PHASE1_OK";
    public static final String PHASE2_MARKER = "HR_PHASE2_OK";
    public static final String PHASE3_MARKER = "HR_PHASE3_OK";
    public static final String FAIL_MARKER = "HR_FAIL";
    public static final String GDEXTENSION_FILE_NAME = "HotReloadTest.gdextension";
    public static final String GDEXTENSION_RESOURCE_PATH = "res://" + GDEXTENSION_FILE_NAME;
    public static final String SWAP_FLAG_FILE_NAME = "hr_swap.flag";
    public static final Duration DEFAULT_MARKER_TIMEOUT = Duration.ofSeconds(240);
    public static final Duration DEFAULT_EXIT_TIMEOUT = Duration.ofSeconds(60);

    private static final String DRIVER_FILE_NAME = "driver.gd";
    private static final String DRIVER_RESOURCE_PATH = "res://" + DRIVER_FILE_NAME;
    private static final Duration PROCESS_START_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CAPABILITY_PROBE_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration CLOSE_KILL_GRACE = Duration.ofSeconds(5);
    private static final String MINIMAL_PROJECT_GODOT = """
            ; Engine configuration file.
            config_version=5

            [application]

            config/name="GdccEditorHotReloadTest"
            config/features=PackedStringArray("4.5")
            """;

    private static @Nullable Path probedGodotBinary;
    private static boolean probedGodotBinaryCapable;

    private final @NotNull Path projectDir;
    private final StringBuffer stdoutBuffer = new StringBuffer();
    private final StringBuffer stderrBuffer = new StringBuffer();
    private final Object lifecycleLock = new Object();
    private final List<String> failLines = new ArrayList<>();
    private final Set<String> seenMarkers = new HashSet<>();
    private final CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
    private final AtomicInteger exitCode = new AtomicInteger(-1);
    private @Nullable CompletableFuture<Void> markerFuture;
    private @Nullable String awaitedMarker;
    private @Nullable String libraryFileName;
    private @Nullable Process process;
    private @Nullable Thread stdoutReader;
    private @Nullable Thread stderrReader;

    /// Creates a session bound to the Godot project directory that `prepareProject` will (re)create.
    public GodotEditorHotReloadTestSession(@NotNull Path projectDir) {
        this.projectDir = Objects.requireNonNull(projectDir).toAbsolutePath();
    }

    /// Resolves `GODOT_BIN` and verifies it is an editor-capable binary.
    ///
    /// Hot reload only exists in editor builds; a runtime export template rejects `--editor`.
    /// Both cases skip the calling test through JUnit assumptions. The capability probe result is
    /// cached process-wide so a test class with several editor cases pays it once.
    public static @NotNull Path requireEditorCapableGodotOrAbort() {
        var godotBinary = GodotGdextensionTestRunner.findGodotBinaryFromEnv();
        if (godotBinary == null) {
            Assumptions.abort("GODOT_BIN not found; skipping editor hot reload integration test");
            throw new IllegalStateException("Unreachable after assumption abort");
        }
        if (probeEditorCapability(godotBinary)) {
            return godotBinary;
        }
        Assumptions.abort("GODOT_BIN is not editor-capable (--editor --quit failed); skipping editor hot reload integration test");
        throw new IllegalStateException("Unreachable after assumption abort");
    }

    /// (Re)creates the editor project with the v1 library and the scenario driver script.
    public void prepareProject(@NotNull Path v1Library, @NotNull String driverSource) throws IOException {
        Objects.requireNonNull(v1Library);
        Objects.requireNonNull(driverSource);
        if (!Files.isRegularFile(v1Library)) {
            throw new IOException("v1 library not found: " + v1Library);
        }
        recreateProjectDir();
        Files.writeString(projectDir.resolve("project.godot"), MINIMAL_PROJECT_GODOT, StandardCharsets.UTF_8);
        var binDir = projectDir.resolve("bin");
        Files.createDirectories(binDir);
        var installedLibrary = binDir.resolve(v1Library.getFileName().toString());
        Files.copy(v1Library, installedLibrary, StandardCopyOption.REPLACE_EXISTING);
        libraryFileName = installedLibrary.getFileName().toString();
        Files.writeString(
                projectDir.resolve(GDEXTENSION_FILE_NAME),
                GdextensionMetadataFile.render(
                        "res://bin/" + libraryFileName,
                        COptimizationLevel.DEBUG,
                        TargetPlatform.getNativePlatform()
                ),
                StandardCharsets.UTF_8
        );
        Files.writeString(projectDir.resolve(DRIVER_FILE_NAME), driverSource, StandardCharsets.UTF_8);
    }

    /// Launches the headless editor and starts stream/process supervision.
    public void start() throws IOException, InterruptedException {
        if (process != null) {
            throw new IllegalStateException("Session already started");
        }
        if (libraryFileName == null) {
            throw new IllegalStateException("prepareProject must run before start");
        }
        var godotBinary = requireEditorCapableGodotOrAbort();
        var command = List.of(
                godotBinary.toString(),
                "--headless",
                "--editor",
                "--path",
                projectDir.toString(),
                "--script",
                DRIVER_RESOURCE_PATH
        );
        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectDir.toFile());
        process = startProcess(processBuilder, PROCESS_START_TIMEOUT);
        Consumer<String> onLine = this::observeLine;
        stdoutReader = startStreamReader("stdout", process.getInputStream(), stdoutBuffer, onLine);
        stderrReader = startStreamReader("stderr", process.getErrorStream(), stderrBuffer, onLine);
        startProcessWaiter(process);
    }

    /// Waits until the driver prints the given phase marker.
    ///
    /// Markers printed before this call are honored (the three phase constants via `seenMarkers`,
    /// any other marker via a buffered-output line scan), so Java/editor scheduling never loses a
    /// phase. A driver `HR_FAIL` line, an early editor exit, or a timeout all surface as
    /// `IOException` carrying the collected editor output; on timeout the editor process is
    /// force-killed first so it never outlives the test.
    public void awaitMarker(@NotNull String marker) throws IOException, InterruptedException {
        awaitMarker(marker, DEFAULT_MARKER_TIMEOUT);
    }

    public void awaitMarker(@NotNull String marker, @NotNull Duration timeout) throws IOException, InterruptedException {
        Objects.requireNonNull(marker);
        Objects.requireNonNull(timeout);
        var future = new CompletableFuture<Void>();
        synchronized (lifecycleLock) {
            if (seenMarkers.contains(marker) || bufferedOutputHasLine(marker)) {
                return;
            }
            if (exitFuture.isDone()) {
                throw new IOException(
                        "Godot exited with code " + exitCode.get() + " before marker '" + marker + "'\n" + combinedOutput()
                );
            }
            markerFuture = future;
            awaitedMarker = marker;
        }
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abortProcess();
            throw new IOException("Timed out waiting for marker '" + marker + "'\n" + combinedOutput(), e);
        } catch (ExecutionException e) {
            // The wait failed (early exit, HR_FAIL, or stream error); drain the pipes first so
            // trailing engine output lands in the buffers used for diagnostics below.
            try {
                joinStreamReaders();
            } catch (IOException | InterruptedException joinFailure) {
                if (joinFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
            var cause = e.getCause();
            var message = cause != null ? cause.getMessage() : String.valueOf(e.getCause());
            throw new IOException("Failed while waiting for marker '" + marker + "': " + message + "\n" + combinedOutput(), e);
        } finally {
            synchronized (lifecycleLock) {
                if (markerFuture == future) {
                    markerFuture = null;
                    awaitedMarker = null;
                }
            }
        }
    }

    /// Atomically swaps the loaded library for a newly built one and drops the swap flag file.
    ///
    /// The rename is mandatory: overwriting a mapped `.so` in place re-fills its clean pages with
    /// new content while dirty GOT pages keep the old version, so the next old-library call jumps
    /// into mismatched code and SIGSEGVs. Unlink does not affect existing mappings, and the
    /// engine's dlclose/dlopen pair then resolves the same path to the new inode.
    public void swapLibrary(@NotNull Path newLibrary) throws IOException {
        Objects.requireNonNull(newLibrary);
        var name = libraryFileName;
        if (name == null) {
            throw new IllegalStateException("prepareProject must run before swapLibrary");
        }
        if (!newLibrary.getFileName().toString().equals(name)) {
            throw new IOException(
                    "Swap library name mismatch: expected " + name + " (same module output), got " + newLibrary.getFileName()
            );
        }
        var binDir = projectDir.resolve("bin");
        var stagedPath = binDir.resolve("." + name + ".new");
        var targetPath = binDir.resolve(name);
        Files.copy(newLibrary, stagedPath, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(stagedPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Windows: the engine maps its own `~xxx.dll` copy, so the library path itself is not
            // mapped and a non-atomic replace is safe there; POSIX keeps the mandatory rename.
            Files.move(stagedPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(projectDir.resolve(SWAP_FLAG_FILE_NAME), "swap\n", StandardCharsets.UTF_8);
    }

    /// Waits for the editor process to exit and returns its exit code.
    public int awaitExit() throws IOException, InterruptedException {
        return awaitExit(DEFAULT_EXIT_TIMEOUT);
    }

    public int awaitExit(@NotNull Duration timeout) throws IOException, InterruptedException {
        Objects.requireNonNull(timeout);
        try {
            var code = exitFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            // The process has exited, so its streams are at EOF; join the readers (bounded) so
            // trailing shutdown/HR_FAIL/SCRIPT ERROR lines land in the buffers before assertions.
            joinStreamReaders();
            return code;
        } catch (TimeoutException e) {
            abortProcess();
            throw new IOException("Timed out waiting for Godot editor exit\n" + combinedOutput(), e);
        } catch (ExecutionException e) {
            throw new IOException("Failed while waiting for Godot editor exit\n" + combinedOutput(), e);
        }
    }

    private void joinStreamReaders() throws IOException, InterruptedException {
        joinStreamReader(stdoutReader, "stdout");
        joinStreamReader(stderrReader, "stderr");
    }

    private static void joinStreamReader(@Nullable Thread reader, @NotNull String streamName) throws IOException, InterruptedException {
        if (reader == null) {
            return;
        }
        reader.join(5_000);
        if (reader.isAlive()) {
            throw new IOException("Timed out while collecting " + streamName + " stream");
        }
    }

    /// Driver-reported `HR_FAIL` lines, in arrival order. Empty means the driver never failed.
    public @NotNull List<String> failLines() {
        synchronized (lifecycleLock) {
            return List.copyOf(failLines);
        }
    }

    public @NotNull String combinedOutput() {
        return stdoutBuffer + System.lineSeparator() + stderrBuffer;
    }

    /// Force-kills the editor if it is still alive. Never throws, safe to call repeatedly.
    @Override
    public void close() {
        abortProcess();
    }

    private void abortProcess() {
        var godotProcess = process;
        if (godotProcess == null) {
            return;
        }
        if (godotProcess.isAlive()) {
            godotProcess.destroyForcibly();
            try {
                if (!godotProcess.waitFor(CLOSE_KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS) && godotProcess.isAlive()) {
                    // A headless editor is expected to die on the first SIGKILL; escalate once and
                    // report instead of silently leaking a process into the next test case.
                    godotProcess.destroyForcibly();
                    if (!godotProcess.waitFor(CLOSE_KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS) && godotProcess.isAlive()) {
                        System.err.println("GodotEditorHotReloadTestSession: Godot editor still alive after repeated SIGKILL: " + godotProcess);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Even when the editor is already dead, its readers may still be draining the pipes;
        // join them best-effort so trailing lines land in the buffers for diagnostics.
        try {
            joinStreamReaders();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void observeLine(@NotNull String line) {
        synchronized (lifecycleLock) {
            // Failure wins over any phase token on the same line: a line that carries HR_FAIL
            // must never complete a pending phase wait as success.
            if (line.contains(FAIL_MARKER)) {
                failLines.add(line);
                var pending = markerFuture;
                if (pending != null && !pending.isDone()) {
                    pending.completeExceptionally(new IOException("Driver reported failure: " + line));
                }
                return;
            }
            // Phase markers are whole-line tokens printed via print(); exact equality keeps
            // engine echo or source dumps from being mistaken for a phase transition.
            if (line.equals(PHASE1_MARKER) || line.equals(PHASE2_MARKER) || line.equals(PHASE3_MARKER)) {
                seenMarkers.add(line);
            }
            var future = markerFuture;
            if (future != null && line.equals(awaitedMarker) && !future.isDone()) {
                seenMarkers.add(line);
                future.complete(null);
            }
        }
    }

    private boolean bufferedOutputHasLine(@NotNull String marker) {
        var separator = System.lineSeparator();
        var needle = separator + marker + separator;
        return (separator + stdoutBuffer + separator).contains(needle)
                || (separator + stderrBuffer + separator).contains(needle);
    }

    private void startProcessWaiter(@NotNull Process godotProcess) {
        Thread.ofVirtual().name("gdcc-editor-hr-wait-", 0).start(() -> {
            try {
                var code = godotProcess.waitFor();
                exitCode.set(code);
                synchronized (lifecycleLock) {
                    exitFuture.complete(code);
                    var future = markerFuture;
                    if (future != null && !future.isDone()) {
                        future.completeExceptionally(new IOException(
                                "Godot exited with code " + code + " while waiting for marker '" + awaitedMarker + "'"
                        ));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                var failure = new IOException("Interrupted while waiting for Godot editor exit", e);
                synchronized (lifecycleLock) {
                    exitFuture.completeExceptionally(failure);
                    var future = markerFuture;
                    if (future != null && !future.isDone()) {
                        future.completeExceptionally(failure);
                    }
                }
            }
        });
    }

    private @NotNull Thread startStreamReader(
            @NotNull String streamName,
            @NotNull InputStream stream,
            @NotNull StringBuffer output,
            @NotNull Consumer<String> onLine
    ) {
        return Thread.ofVirtual().name("gdcc-editor-hr-" + streamName + "-", 0).start(() -> {
            try {
                readLinesInto(stream, output, onLine);
            } catch (IOException e) {
                var failure = new IOException("Failed to collect " + streamName + " stream", e);
                synchronized (lifecycleLock) {
                    exitFuture.completeExceptionally(failure);
                    var future = markerFuture;
                    if (future != null && !future.isDone()) {
                        future.completeExceptionally(failure);
                    }
                }
            }
        });
    }

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

    private static @NotNull Process startProcess(
            @NotNull ProcessBuilder processBuilder,
            @NotNull Duration timeout
    ) throws IOException, InterruptedException {
        var started = new CompletableFuture<Process>();
        Thread.ofVirtual().name("gdcc-editor-hr-start-", 0).start(() -> {
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
            throw new IOException("Godot editor process start failed", cause);
        } catch (TimeoutException e) {
            // The spawn attempt may still complete after we give up; make sure a late-started
            // editor is killed instead of being left unsupervised.
            started.thenAccept(lateProcess -> {
                if (lateProcess != null) {
                    lateProcess.destroyForcibly();
                }
            });
            throw new IOException("Timed out while starting Godot editor process", e);
        }
    }

    private void recreateProjectDir() throws IOException {
        if (Files.exists(projectDir)) {
            clearDirectory(projectDir);
        }
        Files.createDirectories(projectDir);
    }

    private static void clearDirectory(@NotNull Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (path.equals(dir)) {
                    continue;
                }
                var retriesRemaining = 10;
                while (true) {
                    try {
                        Files.deleteIfExists(path);
                        break;
                    } catch (AccessDeniedException e) {
                        retriesRemaining--;
                        if (retriesRemaining == 0) {
                            throw e;
                        }
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while clearing " + path, interruptedException);
                        }
                    }
                }
            }
        }
    }

    private static synchronized boolean probeEditorCapability(@NotNull Path godotBinary) {
        if (godotBinary.equals(probedGodotBinary)) {
            return probedGodotBinaryCapable;
        }
        var capable = runEditorCapabilityProbe(godotBinary);
        if (capable == null) {
            // Inconclusive probes (timeout/interrupt/spawn failure) are not cached: a slow first
            // editor launch must not poison the whole test JVM into skipping every HR test.
            return false;
        }
        probedGodotBinary = godotBinary;
        probedGodotBinaryCapable = capable;
        return capable;
    }

    /// Probes whether the binary accepts `--editor`. Returns null when the probe itself could
    /// not produce a definitive exit code (timeout, interrupt, or spawn failure).
    private static @Nullable Boolean runEditorCapabilityProbe(@NotNull Path godotBinary) {
        Process probeProcess;
        try {
            probeProcess = new ProcessBuilder(
                    godotBinary.toString(),
                    "--headless",
                    "--editor",
                    "--quit"
            )
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            return null;
        }
        try {
            if (!probeProcess.waitFor(CAPABILITY_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                probeProcess.destroyForcibly();
                probeProcess.waitFor(CLOSE_KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                return null;
            }
            return probeProcess.exitValue() == 0;
        } catch (InterruptedException e) {
            // Reap before restoring the interrupt flag, otherwise waitFor rethrows immediately.
            probeProcess.destroyForcibly();
            try {
                probeProcess.waitFor(CLOSE_KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException _) {
                // Interrupted again during reap; the flag is restored once below.
            }
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
