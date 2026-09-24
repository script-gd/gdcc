package gd.script.gdcc.backend.c.build.packedref;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.build.CBuildResult;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.CProjectBuilder;
import gd.script.gdcc.backend.c.build.CProjectInfo;
import gd.script.gdcc.backend.c.build.GdextensionMetadataFile;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.ZigUtil;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPassManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/// Orchestrates the Packed*Array reference-semantics dual-run comparison
/// (packed_array_reference_semantics_plan.md §6 Phase A).
///
/// The same probe source runs twice side by side per module:
///
/// - interpreter run: a standalone Godot project where the probe library stays plain GDScript
///   and a `SceneTree` driver `preload`s it (headless `-s` launches have no editor-generated
///   global class cache, so `class_name` lookup would fail there);
/// - gdcc run: the identical library source is compiled by gdcc into a GDExtension library and
///   the same driver body instantiates the registered class instead.
///
/// The probe surface is split into two modules because `STATIC_VAR` exercises a construct the
/// current gdcc rejects at compile time (fail-closed static bare-property route, see the
/// companion library header): the main module must always compile, while the blocked module
/// is compiled best-effort — a failure records the case as compile-blocked, and an unexpected
/// success is reported so the case can be migrated back into the main module.
///
/// Each side's outputs are concatenated in registry order (main module first, blocked module
/// last) before parsing, so one golden file covers both. Raw transcripts of every run are
/// written under `TRANSCRIPTS_DIR` for failure triage.
///
/// This harness is a new dual-project comparison facility; it deliberately does not reuse the
/// scene-based `GodotGdextensionTestRunner` (which targets `main.tscn` node fixtures) or the
/// gdscript-unit verifier scripts.
public final class PackedRefSemanticsDualRunHarness {
    /// Classpath directory holding the shared probe libraries and the golden file.
    public static final @NotNull String RESOURCE_DIR = "/packed_ref_semantics";
    public static final @NotNull String PROBE_LIBRARY_RESOURCE = RESOURCE_DIR + "/packed_ref_probes.gd";
    public static final @NotNull String BLOCKED_PROBE_LIBRARY_RESOURCE = RESOURCE_DIR + "/packed_ref_probes_blocked.gd";
    public static final @NotNull String GOLDEN_RESOURCE = RESOURCE_DIR + "/packed_ref_semantics_golden.txt";

    /// Fixed workspace for this harness (existing convention: deterministic, not cleaned up).
    public static final @NotNull Path WORK_DIR = Path.of("tmp/test/packed_ref_semantics_dual_run").toAbsolutePath();
    public static final @NotNull Path TRANSCRIPTS_DIR = WORK_DIR.resolve("transcripts");

    /// Cases living in the compile-blocked companion module (subset of the disabled cases).
    public static final @NotNull Set<String> GDCC_COMPILE_BLOCKED_CASE_NAMES = Set.of("STATIC_VAR", "LAMBDA_CAPTURE");

    private static final @NotNull String DRIVER_SCRIPT_NAME = "driver.gd";
    private static final @NotNull Duration PROCESS_TIMEOUT = Duration.ofSeconds(60);

    private static final @NotNull ProbeModuleSpec MAIN_MODULE = new ProbeModuleSpec(
            "packed_ref_semantics",
            "PackedRefProbes",
            PROBE_LIBRARY_RESOURCE,
            "probes.run_all(self)",
            "packed_ref_semantics.gdextension",
            false
    );
    private static final @NotNull ProbeModuleSpec BLOCKED_MODULE = new ProbeModuleSpec(
            "packed_ref_semantics_blocked",
            "PackedRefProbesBlocked",
            BLOCKED_PROBE_LIBRARY_RESOURCE,
            "probes.run_all()",
            "packed_ref_semantics_blocked.gdextension",
            true
    );

    private PackedRefSemanticsDualRunHarness() {
    }

    /// One probe module: gdcc module name, registered class name, library resource, the driver
    /// body line invoking the probes, the `.gdextension` file name for the gdcc side, and
    /// whether the driver calls `quit()` itself (main module: the library's coroutine
    /// `run_all` quits the tree after its final probe because the interpreter cannot await a
    /// compiled void coroutine; blocked module: fully synchronous, so the driver quits).
    private record ProbeModuleSpec(
            @NotNull String moduleName,
            @NotNull String className,
            @NotNull String libraryResource,
            @NotNull String driverInvocation,
            @NotNull String gdextensionFileName,
            boolean driverQuitsAfter
    ) {
    }

    /// Outputs of one dual run. `gdccOutput`/`gdccStdout` are null when Zig was unavailable;
    /// `gdccBlockedModuleCompiled` records whether the fail-closed companion module
    /// unexpectedly compiled (tripwire signal for migrating its cases back into the main module).
    public record DualRunResult(
            @NotNull ProbeOutput interpreterOutput,
            @NotNull String interpreterStdout,
            @Nullable ProbeOutput gdccOutput,
            @Nullable String gdccStdout,
            boolean gdccBlockedModuleCompiled
    ) {
    }

    /// Raw streams of one `godot -s driver.gd` process run. PROBE lines are parsed from
    /// `stdout`; `stderr` is kept for transcripts (engine script errors land there).
    private record ScriptProjectRun(@NotNull String stdout, @NotNull String stderr) {
    }

    /// Runs both sides end to end and stores transcripts (transcripts are written even when a
    /// run fails, so timeout/crash triage always has the partial output; once the gdcc side has
    /// started, its transcript files are always rewritten so a stale previous run cannot be
    /// mistaken for the current one). The gdcc side is skipped (null result fields) when
    /// `ZigUtil.findZig()` cannot locate a Zig toolchain.
    public static @NotNull DualRunResult runBothSides(@NotNull Path godotBinary) throws IOException, InterruptedException {
        Objects.requireNonNull(godotBinary, "godotBinary must not be null");
        var interpreterStdout = new StringBuilder();
        var interpreterStderr = new StringBuilder();
        var gdccStdout = new StringBuilder();
        var gdccStderr = new StringBuilder();
        var gdccStarted = false;
        try {
            // Interpreter side: both modules always run (plain GDScript has no compile gate).
            var interpreterMain = runInterpreterModule(godotBinary, MAIN_MODULE);
            interpreterStdout.append(interpreterMain.stdout());
            interpreterStderr.append(interpreterMain.stderr());
            var interpreterBlocked = runInterpreterModule(godotBinary, BLOCKED_MODULE);
            interpreterStdout.append(interpreterBlocked.stdout());
            interpreterStderr.append(interpreterBlocked.stderr());
            var interpreterOutput = ProbeOutput.parse(interpreterStdout.toString());

            ProbeOutput gdccOutput = null;
            var blockedModuleCompiled = false;
            if (ZigUtil.findZig() != null) {
                gdccStarted = true;
                var gdccMain = runBuiltGdccModule(godotBinary, MAIN_MODULE, compileModuleChecked(MAIN_MODULE));
                gdccStdout.append(gdccMain.stdout());
                gdccStderr.append(gdccMain.stderr());

                // The blocked module is only compile-blocked while its constructs stay
                // fail-closed. Compile/lowering failures are recorded; a module that compiled
                // and then fails at runtime is a real regression and its IOException propagates
                // (the already-collected main-module transcript still lands via finally).
                CBuildResult blockedBuild = null;
                try {
                    blockedBuild = compileModuleChecked(BLOCKED_MODULE);
                } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                    gdccStderr.append("\n[gdcc harness] compile-blocked module skipped: ").append(e).append('\n');
                }
                if (blockedBuild != null) {
                    var gdccBlocked = runBuiltGdccModule(godotBinary, BLOCKED_MODULE, blockedBuild);
                    gdccStdout.append(gdccBlocked.stdout());
                    gdccStderr.append(gdccBlocked.stderr());
                    blockedModuleCompiled = true;
                }
                gdccOutput = ProbeOutput.parse(gdccStdout.toString());
            }
            return new DualRunResult(
                    interpreterOutput,
                    interpreterStdout.toString(),
                    gdccOutput,
                    gdccStarted ? gdccStdout.toString() : null,
                    blockedModuleCompiled
            );
        } finally {
            writeTranscriptsQuietly(
                    interpreterStdout.toString(),
                    interpreterStderr.toString(),
                    gdccStarted ? gdccStdout.toString() : null,
                    gdccStarted ? gdccStderr.toString() : null
            );
        }
    }

    /// Loads and parses the committed golden resource.
    public static @NotNull ProbeOutput loadGolden() throws IOException {
        return ProbeOutput.parse(loadResource(GOLDEN_RESOURCE));
    }

    /// Assembles and runs the interpreter project for one module, returning its streams.
    private static @NotNull ScriptProjectRun runInterpreterModule(@NotNull Path godotBinary, @NotNull ProbeModuleSpec spec) throws IOException, InterruptedException {
        var projectDir = WORK_DIR.resolve("interpreter_" + spec.moduleName());
        recreateDirectory(projectDir);
        Files.writeString(projectDir.resolve("project.godot"), projectGodotSource(), StandardCharsets.UTF_8);
        Files.writeString(projectDir.resolve(libraryFileName(spec)), loadResource(spec.libraryResource()), StandardCharsets.UTF_8);
        Files.writeString(projectDir.resolve(DRIVER_SCRIPT_NAME), interpreterDriverSource(spec), StandardCharsets.UTF_8);
        return runScriptProject(godotBinary, projectDir);
    }

    /// Compiles one probe module and rejects an unsuccessful native build.
    private static @NotNull CBuildResult compileModuleChecked(@NotNull ProbeModuleSpec spec) throws IOException {
        var buildResult = compileModule(spec);
        if (!buildResult.success()) {
            throw new IOException("gdcc native build failed for module " + spec.moduleName() + ". Build log:\n" + buildResult.buildLog());
        }
        return buildResult;
    }

    /// Assembles the GDExtension project for an already-compiled module and runs it.
    private static @NotNull ScriptProjectRun runBuiltGdccModule(@NotNull Path godotBinary, @NotNull ProbeModuleSpec spec, @NotNull CBuildResult buildResult) throws IOException, InterruptedException {
        var projectDir = WORK_DIR.resolve("gdcc_" + spec.moduleName());
        recreateDirectory(projectDir);
        Files.writeString(projectDir.resolve("project.godot"), projectGodotSource(), StandardCharsets.UTF_8);
        Files.writeString(projectDir.resolve(DRIVER_SCRIPT_NAME), gdccDriverSource(spec), StandardCharsets.UTF_8);

        var binDir = projectDir.resolve("bin");
        Files.createDirectories(binDir);
        var libraryName = copyArtifacts(buildResult.artifacts(), binDir);
        Files.writeString(
                projectDir.resolve(spec.gdextensionFileName()),
                GdextensionMetadataFile.render(
                        "res://bin/" + libraryName,
                        COptimizationLevel.DEBUG,
                        TargetPlatform.getNativePlatform()
                ),
                StandardCharsets.UTF_8
        );
        // Plain runtime launches skip the editor filesystem scan that discovers .gdextension
        // files, so the extension list must be written explicitly (same contract as
        // GodotGdextensionTestRunner.writeExtensionListFile).
        var extensionListPath = projectDir.resolve(".godot").resolve("extension_list.cfg");
        Files.createDirectories(extensionListPath.getParent());
        Files.writeString(extensionListPath, "res://" + spec.gdextensionFileName() + "\n", StandardCharsets.UTF_8);
        return runScriptProject(godotBinary, projectDir);
    }

    /// Compiles one probe module through the standard frontend -> C codegen -> Zig pipeline,
    /// registering the compiled class under the same name the gdcc-side driver references.
    static @NotNull CBuildResult compileModule(@NotNull ProbeModuleSpec spec) throws IOException {
        var parser = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var units = List.of(parser.parseUnit(
                WORK_DIR.resolve(libraryFileName(spec)),
                loadResource(spec.libraryResource()),
                parseDiagnostics
        ));
        if (!parseDiagnostics.isEmpty()) {
            throw new IOException("Unexpected probe library parse diagnostics: " + parseDiagnostics.snapshot());
        }
        var module = new FrontendModule(spec.moduleName(), units, Map.of(spec.className(), spec.className()));

        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);
        if (lowered == null || diagnostics.hasErrors()) {
            throw new IOException("Probe library lowering failed: " + diagnostics.snapshot());
        }

        var buildDir = WORK_DIR.resolve("build_" + spec.moduleName());
        Files.createDirectories(buildDir);
        var projectInfo = new CProjectInfo(
                spec.moduleName(),
                GodotVersion.V451,
                buildDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);
        return new CProjectBuilder().buildProject(projectInfo, codegen);
    }

    /// Runs `godot --headless --path <projectDir> -s driver.gd` and returns the captured
    /// streams. The process is expected to `quit()` itself; the timeout only guards against
    /// wedged runs (e.g. a probe regression that skips the quit path).
    static @NotNull ScriptProjectRun runScriptProject(@NotNull Path godotBinary, @NotNull Path projectDir) throws IOException, InterruptedException {
        var command = List.of(
                godotBinary.toString(),
                "--headless",
                "--path",
                projectDir.toString(),
                "-s",
                DRIVER_SCRIPT_NAME
        );
        var process = new ProcessBuilder(command).directory(projectDir.toFile()).start();
        // Virtual-thread readers (same approach as GodotGdextensionTestRunner): never the
        // common pool, so a pipe-full stream cannot starve the sibling reader and wedge the
        // Godot process on 1-2 core machines. InputStreamReader decodes UTF-8 across chunk
        // boundaries, which a per-read `new String(bytes)` cannot do.
        var stdoutBuffer = new StringBuffer();
        var stderrBuffer = new StringBuffer();
        var stdoutReader = startStreamReader(process.getInputStream(), stdoutBuffer);
        var stderrReader = startStreamReader(process.getErrorStream(), stderrBuffer);
        boolean exited;
        try {
            exited = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            joinStreamReadersQuietly(stdoutReader, stderrReader);
            throw e;
        }
        if (!exited) {
            process.destroyForcibly();
            joinStreamReadersQuietly(stdoutReader, stderrReader);
            throw new IOException(
                    "Godot script project run timed out after " + PROCESS_TIMEOUT + ": " + command
                            + "\n--- partial stdout ---\n" + stdoutBuffer + "\n--- partial stderr ---\n" + stderrBuffer);
        }
        joinStreamReader(stdoutReader, "stdout");
        joinStreamReader(stderrReader, "stderr");
        var stdout = stdoutBuffer.toString();
        var stderr = stderrBuffer.toString();
        if (process.exitValue() != 0) {
            throw new IOException(
                    "Godot script project run failed with exit code " + process.exitValue() + ": " + command
                            + "\n--- stdout ---\n" + stdout + "\n--- stderr ---\n" + stderr);
        }
        return new ScriptProjectRun(stdout, stderr);
    }

    /// Starts a virtual thread draining one process stream into `buffer`, so content read
    /// before a timeout kill is retained for diagnostics.
    private static @NotNull Thread startStreamReader(@NotNull InputStream stream, @NotNull StringBuffer buffer) {
        return Thread.ofVirtual().start(() -> {
            var chunk = new char[4096];
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                int read;
                while ((read = reader.read(chunk)) != -1) {
                    buffer.append(chunk, 0, read);
                }
            } catch (IOException e) {
                // Stream closed while the process was being destroyed; partial content is kept.
            }
        });
    }

    /// Strict join for the success path: a still-running reader means the returned output would
    /// be silently truncated, so fail instead (same contract as GodotGdextensionTestRunner).
    private static void joinStreamReader(@NotNull Thread reader, @NotNull String streamName) throws IOException {
        try {
            reader.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted collecting Godot " + streamName + " stream", e);
        }
        if (reader.isAlive()) {
            throw new IOException("Timed out collecting Godot " + streamName + " stream");
        }
    }

    /// Best-effort join for the timeout/interrupt teardown path, where the run has already
    /// failed and any partial output is a bonus.
    private static void joinStreamReadersQuietly(@NotNull Thread... readers) {
        for (var reader : readers) {
            try {
                reader.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static @NotNull String loadResource(@NotNull String resourcePath) throws IOException {
        try (var stream = PackedRefSemanticsDualRunHarness.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("Test resource not found on classpath: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /// Removes and recreates a project directory so stale artifacts from previous runs cannot
    /// leak into the next comparison.
    private static void recreateDirectory(@NotNull Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.equals(dir)) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
        Files.createDirectories(dir);
    }

    /// Copies build artifacts into `bin/`, returning the dynamic library file name preferred
    /// for the current platform (same selection rule as GodotGdextensionTestRunner).
    private static @NotNull String copyArtifacts(@NotNull List<Path> artifacts, @NotNull Path binDir) throws IOException {
        var preferredSuffix = platformLibrarySuffix();
        String fallback = null;
        var copied = new ArrayList<String>();
        for (var artifact : artifacts) {
            if (!Files.exists(artifact)) {
                throw new IOException("Artifact not found: " + artifact);
            }
            var fileName = artifact.getFileName().toString();
            Files.copy(artifact, binDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            copied.add(fileName);
            if (!isDynamicLibrary(fileName)) {
                continue;
            }
            if (fileName.endsWith(preferredSuffix)) {
                return fileName;
            }
            if (fallback == null) {
                fallback = fileName;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IOException("No dynamic library artifact found in build output: " + copied);
    }

    private static @NotNull String platformLibrarySuffix() {
        var fileName = TargetPlatform.getNativePlatform().sharedLibraryFileName("x");
        return fileName.substring(fileName.lastIndexOf('.'));
    }

    private static boolean isDynamicLibrary(@NotNull String fileName) {
        return fileName.endsWith(".dll") || fileName.endsWith(".so") || fileName.endsWith(".dylib") || fileName.endsWith(".wasm");
    }

    /// Best-effort transcript write from `runBothSides`' finally path: transcript failures must
    /// never mask the primary run result.
    private static void writeTranscriptsQuietly(
            @NotNull String interpreterStdout,
            @NotNull String interpreterStderr,
            @Nullable String gdccStdout,
            @Nullable String gdccStderr
    ) {
        try {
            Files.createDirectories(TRANSCRIPTS_DIR);
            Files.writeString(TRANSCRIPTS_DIR.resolve("interpreter_stdout.txt"), interpreterStdout, StandardCharsets.UTF_8);
            Files.writeString(TRANSCRIPTS_DIR.resolve("interpreter_stderr.txt"), interpreterStderr, StandardCharsets.UTF_8);
            if (gdccStdout != null) {
                Files.writeString(TRANSCRIPTS_DIR.resolve("gdcc_stdout.txt"), gdccStdout, StandardCharsets.UTF_8);
                Files.writeString(TRANSCRIPTS_DIR.resolve("gdcc_stderr.txt"), gdccStderr != null ? gdccStderr : "", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("[packed-ref-semantics harness] failed to write transcripts: " + e);
        }
    }

    private static @NotNull String libraryFileName(@NotNull ProbeModuleSpec spec) {
        var resourcePath = spec.libraryResource();
        return resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
    }

    private static @NotNull String projectGodotSource() {
        return """
                config_version=5

                [application]
                config/name="PackedRefSemanticsDualRun"
                config/features=PackedStringArray("4.5")
                """;
    }

    /// Interpreter-side driver: headless `-s` launches have no editor-generated global class
    /// cache, so the probe library is acquired through `preload` of the identical source file.
    private static @NotNull String interpreterDriverSource(@NotNull ProbeModuleSpec spec) {
        return driverSource("preload(\"res://" + libraryFileName(spec) + "\").new()", spec);
    }

    /// gdcc-side driver: identical orchestration, but the probe library arrives as the compiled
    /// GDExtension class registered under the same name.
    private static @NotNull String gdccDriverSource(@NotNull ProbeModuleSpec spec) {
        return driverSource(spec.className() + ".new()", spec);
    }

    /// Renders the shared `SceneTree` driver skeleton. Two lifetime/ownership contracts:
    /// `probes` is an instance variable because `_initialize` returns while the coroutine probe
    /// chain is still suspended, and releasing the probe object there would kill the pending
    /// coroutine states with it; `quit()` ownership is per module — the main module's coroutine
    /// `run_all` quits the tree itself (the interpreter cannot await a gdcc-compiled void
    /// coroutine, so the driver must fire-and-forget), while the blocked module is fully
    /// synchronous and the driver quits after it returns.
    private static @NotNull String driverSource(@NotNull String acquisitionExpression, @NotNull ProbeModuleSpec spec) {
        var body = new StringBuilder("extends SceneTree\n\nvar probes\n\nfunc _initialize() -> void:\n");
        body.append('\t').append("probes = ").append(acquisitionExpression).append('\n');
        body.append('\t').append(spec.driverInvocation()).append('\n');
        if (spec.driverQuitsAfter()) {
            body.append("\tquit()\n");
        }
        return body.toString();
    }
}
