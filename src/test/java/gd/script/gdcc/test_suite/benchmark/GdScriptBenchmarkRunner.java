package gd.script.gdcc.test_suite.benchmark;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.build.CBuildResult;
import gd.script.gdcc.backend.c.build.CCompiler;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.CProjectBuilder;
import gd.script.gdcc.backend.c.build.CProjectInfo;
import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.ScriptResourceSpec;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.ZigUtil;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPassManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.util.ResourceExtractor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.net.URLDecoder;
import java.net.URLEncoder;

import static java.time.Duration.ofNanos;
import static org.junit.jupiter.api.Assertions.*;

/// Godot-backed benchmark harness for `src/test/test_suite/benchmark`.
///
/// The runner keeps benchmark execution inside the existing runtime test project. It reuses the
/// native build and Godot process runner, while adding benchmark-specific contracts:
///
/// - `# gdcc-benchmark:` directives are parsed on the Java side and stripped before scripts are
///   installed into the project.
/// - Godot emits one machine-readable line per measured sample.
/// - Java validates, aggregates, and persists the structured result into one merged
///   `tmp/test/.../report.json` file.
public final class GdScriptBenchmarkRunner {
    public static final String SCRIPT_RESOURCE_ROOT = "benchmark/script";
    public static final String INTERPRETER_RESOURCE_ROOT = "benchmark/interpreter";
    public static final String MEASUREMENT_RESOURCE_ROOT = "benchmark/measurement";
    public static final String MEASUREMENT_TEMPLATE_RESOURCE = "benchmark/template/measurement.gd";
    public static final String RESULT_LINE_PREFIX = "GDCC_BENCHMARK_RESULT ";
    public static final String HEADER_LINE_PREFIX = "GDCC_BENCHMARK_HEADER ";
    public static final String PASS_MARKER_PREFIX = "GDCC_BENCHMARK_PASS::";
    public static final String WARNING_NEGATIVE_BODY = "negative_adjusted_sample";
    public static final String WARNING_SHORT_BATCH = "batch_below_min_duration";
    public static final String WARNING_MISSING_CHECK = "missing_behavior_check";
    static final String COMPILED_TARGET_NODE_NAME = "CompiledTarget";
    static final String INTERPRETER_TARGET_NODE_NAME = "InterpreterTarget";
    static final String MEASUREMENT_NODE_NAME = "BenchmarkMeasurement";
    static final String INTERPRETER_SCRIPT_PLACEHOLDER = "__GDCC_BENCHMARK_INTERPRETER_SCRIPT__";
    static final String COMPILED_TARGET_NODE_PLACEHOLDER = "__GDCC_BENCHMARK_COMPILED_TARGET_NODE__";
    static final String INTERPRETER_TARGET_NODE_PLACEHOLDER = "__GDCC_BENCHMARK_INTERPRETER_TARGET_NODE__";
    static final String CASE_PATH_PLACEHOLDER = "__GDCC_BENCHMARK_CASE_PATH__";
    static final String CASE_NAME_PLACEHOLDER = "__GDCC_BENCHMARK_CASE_NAME__";
    static final String ITERATIONS_PLACEHOLDER = "__GDCC_BENCHMARK_ITERATIONS__";
    static final String WARMUPS_PLACEHOLDER = "__GDCC_BENCHMARK_WARMUPS__";
    static final String SAMPLES_PLACEHOLDER = "__GDCC_BENCHMARK_SAMPLES__";
    static final String MIN_BATCH_US_PLACEHOLDER = "__GDCC_BENCHMARK_MIN_BATCH_US__";
    static final String PASS_MARKER_PLACEHOLDER = "__GDCC_BENCHMARK_PASS_MARKER__";
    static final String BENCHMARK_DIRECTIVE_PREFIX = "# gdcc-benchmark:";
    static final String BENCHMARK_NAME_DIRECTIVE = "name=";
    static final String BENCHMARK_ITERATIONS_DIRECTIVE = "iterations=";
    static final String BENCHMARK_WARMUPS_DIRECTIVE = "warmups=";
    static final String BENCHMARK_SAMPLES_DIRECTIVE = "samples=";
    static final String BENCHMARK_MIN_BATCH_US_DIRECTIVE = "min_batch_us=";
    static final String BENCHMARK_OUTPUT_CONTAINS_DIRECTIVE = "output_contains=";
    static final String BENCHMARK_OUTPUT_NOT_CONTAINS_DIRECTIVE = "output_not_contains=";

    private static final Path WORK_ROOT = Path.of("tmp/test/test_suite/benchmark");
    private static final Path RUNTIME_PROJECT_TEMPLATE_DIR = Path.of("test_project");
    private static final int DEFAULT_ITERATIONS = 1_000;
    private static final int DEFAULT_WARMUPS = 3;
    private static final int DEFAULT_SAMPLES = 10;
    private static final int DEFAULT_MIN_BATCH_US = 1_000;
    private static final Duration DEFAULT_PROCESS_TIMEOUT = Duration.ofSeconds(90);
    private static final int DEFAULT_QUIT_AFTER_FRAMES = 120;
    private static final Pattern SPLIT_PATTERN = Pattern.compile("\\s+");
    private static final DateTimeFormatter UTC_FORMATTER = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private final @NotNull ClassLoader loader;
    private final @NotNull GdScriptParserService parser;
    private final @NotNull FrontendLoweringPassManager loweringPassManager;
    private final @Nullable CCompiler cCompiler;

    public GdScriptBenchmarkRunner() {
        this(Thread.currentThread().getContextClassLoader(), null);
    }

    public GdScriptBenchmarkRunner(@NotNull ClassLoader loader) {
        this(loader, null);
    }

    GdScriptBenchmarkRunner(@NotNull ClassLoader loader, @Nullable CCompiler cCompiler) {
        this.loader = Objects.requireNonNull(loader);
        this.cCompiler = cCompiler;
        parser = new GdScriptParserService();
        loweringPassManager = new FrontendLoweringPassManager();
    }

    public @NotNull List<String> listBenchmarkResourcePaths() throws IOException {
        var scriptResourcePaths = ResourceExtractor.listResourceFilesRecursively(SCRIPT_RESOURCE_ROOT, loader);
        assertFalse(scriptResourcePaths.isEmpty(), "No benchmark script resources found under " + SCRIPT_RESOURCE_ROOT);
        checkCounterpartPaths(scriptResourcePaths, INTERPRETER_RESOURCE_ROOT, "interpreter");
        checkCounterpartPaths(scriptResourcePaths, MEASUREMENT_RESOURCE_ROOT, "measurement");
        return scriptResourcePaths;
    }

    public @NotNull CaseBuildResult compileBenchmarkCase(@NotNull String scriptResourcePath) throws IOException {
        Objects.requireNonNull(scriptResourcePath);

        var totalStart = System.nanoTime();
        var resourceReadStart = System.nanoTime();
        var resources = loadCaseResources(scriptResourcePath);
        var resourceReadDuration = elapsedSince(resourceReadStart);

        var caseName = sanitizeCaseName(stripExtension(scriptResourcePath));
        var sourcePath = WORK_ROOT.resolve("sources").resolve(scriptResourcePath);
        var projectDir = WORK_ROOT.resolve("build").resolve(caseName);
        var workDirectoryPrepareStart = System.nanoTime();
        Files.createDirectories(projectDir);
        var workDirectoryPrepareDuration = elapsedSince(workDirectoryPrepareStart);

        var frontendLoweringStart = System.nanoTime();
        var lowered = lowerModule(sourcePath, resources.compiledSource(), caseName);
        var frontendLoweringDuration = elapsedSince(frontendLoweringStart);

        var runtimeClassValidationStart = System.nanoTime();
        var runtimeClassName = requireRuntimeClassName(lowered, scriptResourcePath);
        var runtimeClassValidationDuration = elapsedSince(runtimeClassValidationStart);

        var nativeBuild = buildNativeLibrary(lowered.module(), lowered.classRegistry(), projectDir, caseName);
        assertTrue(
                nativeBuild.result().success(),
                () -> "Native build failed for " + scriptResourcePath + ".\nBuild log:\n" + nativeBuild.result().buildLog()
        );

        var projectSetupPrepareStart = System.nanoTime();
        var projectSetup = prepareProjectSetup(resources, scriptResourcePath, runtimeClassName, nativeBuild.result().artifacts());
        var projectSetupPrepareDuration = elapsedSince(projectSetupPrepareStart);

        var timing = new CaseBuildResult.Timing(
                resourceReadDuration,
                workDirectoryPrepareDuration,
                frontendLoweringDuration,
                runtimeClassValidationDuration,
                projectSetupPrepareDuration,
                nativeBuild.timing(),
                elapsedSince(totalStart)
        );
        return new CaseBuildResult(
                scriptResourcePath,
                runtimeClassName,
                projectDir,
                nativeBuild.result(),
                projectSetup,
                resources.config(),
                timing
        );
    }

    public @NotNull CaseRuntimeResult compileAndRunBenchmarkCase(@NotNull String scriptResourcePath) throws Exception {
        return compileAndRunBenchmarkCase(scriptResourcePath, defaultRunOptions());
    }

    public @NotNull CaseRuntimeResult compileAndRunBenchmarkCase(
            @NotNull String scriptResourcePath,
            @NotNull GodotGdextensionTestRunner.RunOptions runOptions
    ) throws Exception {
        Objects.requireNonNull(scriptResourcePath);
        Objects.requireNonNull(runOptions);

        var totalStart = System.nanoTime();
        var buildResult = compileBenchmarkCase(scriptResourcePath);
        var runtimeProjectDir = prepareRuntimeProjectDirectory(scriptResourcePath);
        var runner = new GodotGdextensionTestRunner(runtimeProjectDir);
        var projectPrepareStart = System.nanoTime();
        runner.prepareProject(buildResult.projectSetup());
        var projectPrepareDuration = elapsedSince(projectPrepareStart);

        var runResult = runner.run(runOptions);
        var outputValidationStart = System.nanoTime();
        var combinedOutput = runResult.combinedOutput();
        var reportPath = reportPath();
        BenchmarkReport report;
        try {
            assertStopSignalSeen(scriptResourcePath, runResult);
            report = parseCaseOutput(scriptResourcePath, buildResult.config(), runResult, combinedOutput);
        } catch (AssertionError error) {
            try {
                report = failedReport(scriptResourcePath, buildResult.config(), runResult, combinedOutput, error);
                appendReportCase(reportPath, report);
            } catch (IOException writeError) {
                error.addSuppressed(writeError);
            }
            throw error;
        }
        report = appendReportCase(reportPath, report);
        System.out.println(summaryLine(report.cases().getLast()));
        var outputValidationDuration = elapsedSince(outputValidationStart);
        var timing = new CaseRuntimeResult.Timing(
                buildResult.timing(),
                projectPrepareDuration,
                runResult.timing(),
                outputValidationDuration,
                elapsedSince(totalStart)
        );
        return new CaseRuntimeResult(buildResult, runResult, report, reportPath, timing);
    }

    /// Benchmarks reuse the checked-in Godot fixture as a template but run each case inside its own
    /// generated project directory so late process shutdown from a previous case cannot rewrite the
    /// next case's scene or scripts.
    static @NotNull Path prepareRuntimeProjectDirectory(@NotNull String scriptResourcePath) throws IOException {
        var targetDir = runtimeProjectDirForCase(scriptResourcePath);
        if (Files.exists(targetDir)) {
            clearDirectory(targetDir);
        }
        Files.createDirectories(targetDir);
        copyDirectory(targetDir);
        return targetDir;
    }

    static @NotNull Path runtimeProjectDirForCase(@NotNull String scriptResourcePath) {
        return WORK_ROOT.resolve("runtime").resolve(sanitizeCaseName(stripExtension(scriptResourcePath)));
    }

    static @NotNull Path reportPath() {
        return WORK_ROOT.resolve("report.json");
    }

    void resetReport() throws IOException {
        Files.deleteIfExists(reportPath());
    }

    static @NotNull GodotGdextensionTestRunner.RunOptions defaultRunOptions() {
        return new GodotGdextensionTestRunner.RunOptions(
                DEFAULT_QUIT_AFTER_FRAMES,
                true,
                DEFAULT_PROCESS_TIMEOUT,
                GodotGdextensionTestRunner.DEFAULT_FORCE_KILL_DELAY
        );
    }

    static @NotNull BenchmarkReport parseCaseOutput(
            @NotNull String scriptResourcePath,
            @NotNull BenchmarkConfig expectedConfig,
            @NotNull GodotGdextensionTestRunner.GodotRunResult runResult,
            @NotNull String combinedOutput
    ) {
        // Godot-side scripts deliberately use fixed machine-readable lines so Java can validate
        // every timing sample without scraping human-oriented logs.
        var lines = combinedOutput.lines().toList();
        var resultLines = new ArrayList<SampleLine>();
        HeaderLine header = null;
        var passMarkerSeen = false;
        for (var line : lines) {
            if (line.startsWith(HEADER_LINE_PREFIX)) {
                assertNull(header, () -> "Duplicate benchmark header line for " + scriptResourcePath + ": " + line);
                header = parseHeaderLine(scriptResourcePath, line);
                continue;
            }
            if (line.startsWith(RESULT_LINE_PREFIX)) {
                resultLines.add(parseResultLine(scriptResourcePath, line));
                continue;
            }
            if (line.equals(expectedPassMarker(scriptResourcePath))) {
                passMarkerSeen = true;
            }
        }

        assertNotNull(header, () -> "Missing benchmark header line for " + scriptResourcePath + ".\nOutput:\n" + combinedOutput);
        assertTrue(passMarkerSeen, () -> "Missing benchmark pass marker for " + scriptResourcePath + ".\nOutput:\n" + combinedOutput);
        expectedConfig.outputExpectations().assertSatisfied(scriptResourcePath, combinedOutput);
        assertEqualsConfig(expectedConfig, header);

        var groupedSamples = groupSamples(scriptResourcePath, expectedConfig, resultLines);
        var caseSummary = buildCaseSummary(scriptResourcePath, expectedConfig, groupedSamples, runResult, combinedOutput);
        return new BenchmarkReport(
                1,
                UTC_FORMATTER.format(Instant.now()),
                currentEnvironment(),
                List.of(caseSummary)
        );
    }

    static void assertStopSignalSeen(
            @NotNull String scriptResourcePath,
            @NotNull GodotGdextensionTestRunner.GodotRunResult runResult
    ) {
        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot benchmark run for " + scriptResourcePath + " did not emit \""
                        + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\" before completion."
                        + "\nTimed out: " + runResult.timedOut()
                        + "\nExit code: " + runResult.exitCode()
                        + "\nCommand: " + runResult.command()
                        + "\nOutput:\n" + runResult.combinedOutput()
        );
    }

    static @NotNull BenchmarkReport failedReport(
            @NotNull String scriptResourcePath,
            @NotNull BenchmarkConfig config,
            @NotNull GodotGdextensionTestRunner.GodotRunResult runResult,
            @NotNull String combinedOutput,
            @NotNull AssertionError error
    ) {
        return new BenchmarkReport(
                1,
                UTC_FORMATTER.format(Instant.now()),
                currentEnvironment(),
                List.of(new BenchmarkReport.CaseSummary(
                        scriptResourcePath.replace('\\', '/'),
                        config.name(),
                        toReportConfig(config),
                        "failed",
                        List.of(),
                        failureMessage(error),
                        null,
                        null,
                        null,
                        combinedOutput.contains(expectedPassMarker(scriptResourcePath)),
                        runResult.command(),
                        combinedOutput
                ))
        );
    }

    static @NotNull BenchmarkReport appendReportCase(
            @NotNull Path reportPath,
            @NotNull BenchmarkReport report
    ) throws IOException {
        if (!Files.exists(reportPath)) {
            BenchmarkReportWriter.writeReports(reportPath, report);
            return report;
        }

        var existing = BenchmarkReportWriter.readReport(reportPath);
        var mergedCases = new ArrayList<>(existing.cases());
        mergedCases.removeIf(caseSummary -> caseSummary.casePath().equals(report.cases().getFirst().casePath()));
        mergedCases.add(report.cases().getFirst());
        var merged = new BenchmarkReport(
                report.schemaVersion(),
                report.generatedAt(),
                report.environment(),
                mergedCases
        );
        BenchmarkReportWriter.writeReports(reportPath, merged);
        return merged;
    }

    private static @NotNull String failureMessage(@NotNull AssertionError error) {
        var message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getName() : message;
    }

    private void checkCounterpartPaths(
            @NotNull List<String> scriptResourcePaths,
            @NotNull String counterpartRoot,
            @NotNull String counterpartLabel
    ) throws IOException {
        var counterpartPaths = ResourceExtractor.listResourceFilesRecursively(counterpartRoot, loader);
        var missingPaths = scriptResourcePaths.stream()
                .filter(scriptResourcePath -> !counterpartPaths.contains(scriptResourcePath))
                .toList();
        var unexpectedPaths = counterpartPaths.stream()
                .filter(counterpartPath -> !scriptResourcePaths.contains(counterpartPath))
                .toList();
        assertTrue(
                missingPaths.isEmpty() && unexpectedPaths.isEmpty(),
                () -> formatCounterpartMismatch(counterpartRoot, counterpartLabel, missingPaths, unexpectedPaths)
        );
    }

    private @NotNull BenchmarkCaseResources loadCaseResources(@NotNull String scriptResourcePath) throws IOException {
        var compiledSource = readRequiredResourceText(SCRIPT_RESOURCE_ROOT + "/" + scriptResourcePath);
        var interpreterSource = readRequiredResourceText(INTERPRETER_RESOURCE_ROOT + "/" + scriptResourcePath);
        var measurementSource = readRequiredResourceText(MEASUREMENT_RESOURCE_ROOT + "/" + scriptResourcePath);
        var measurementTemplate = readRequiredResourceText(MEASUREMENT_TEMPLATE_RESOURCE);
        var config = parseBenchmarkConfig(scriptResourcePath, measurementSource);
        return new BenchmarkCaseResources(scriptResourcePath, compiledSource, interpreterSource, measurementSource, measurementTemplate, config);
    }

    private @NotNull LoweredCase lowerModule(@NotNull Path sourcePath, @NotNull String source, @NotNull String moduleName) throws IOException {
        var parseDiagnostics = new DiagnosticManager();
        var unit = parser.parseUnit(sourcePath, source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics for " + sourcePath + ": " + parseDiagnostics.snapshot());

        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = loweringPassManager.lower(new FrontendModule(moduleName, List.of(unit), Map.of()), classRegistry, diagnostics);
        assertNotNull(lowered, () -> "Lowering returned null for " + sourcePath + " with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics for " + sourcePath + ": " + diagnostics.snapshot());
        return new LoweredCase(lowered, classRegistry);
    }

    /// Benchmark runtime roots are mounted into a Godot scene just like unit-test roots, so the
    /// compiled top-level class must stay instantiable as a `Node`.
    private @NotNull String requireRuntimeClassName(@NotNull LoweredCase lowered, @NotNull String scriptResourcePath) {
        var classDefs = lowered.module().getClassDefs();
        assertFalse(classDefs.isEmpty(), () -> "Each benchmark script must lower to at least one class: " + scriptResourcePath);

        var runtimeRootClass = classDefs.getFirst();
        assertTrue(
                lowered.classRegistry().checkAssignable(
                        new GdObjectType(runtimeRootClass.getName()),
                        new GdObjectType("Node")
                ),
                () -> "Mounted benchmark root class must remain Node-derived, but "
                        + scriptResourcePath + " lowered root '" + runtimeRootClass.getName() + "' is not assignable to Node"
        );
        return runtimeRootClass.getName();
    }

    private @NotNull NativeBuild buildNativeLibrary(
            @NotNull LirModule lowered,
            @NotNull ClassRegistry classRegistry,
            @NotNull Path projectDir,
            @NotNull String caseName
    ) throws IOException {
        var totalStart = System.nanoTime();
        var targetPlatformStart = System.nanoTime();
        var targetPlatform = TargetPlatform.getNativePlatform();
        var targetPlatformDuration = elapsedSince(targetPlatformStart);

        var projectInfoStart = System.nanoTime();
        var projectInfo = new CProjectInfo(
                caseName,
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.RELEASE,
                targetPlatform
        );
        var projectInfoDuration = elapsedSince(projectInfoStart);

        var codegenCreateStart = System.nanoTime();
        var codegen = new CCodegen();
        var codegenCreateDuration = elapsedSince(codegenCreateStart);

        var contextCreateStart = System.nanoTime();
        var context = new CodegenContext(projectInfo, classRegistry);
        var contextCreateDuration = elapsedSince(contextCreateStart);

        var codegenPrepareStart = System.nanoTime();
        codegen.prepare(context, lowered);
        var codegenPrepareDuration = elapsedSince(codegenPrepareStart);

        var builderCreateStart = System.nanoTime();
        var builder = cCompiler == null ? new CProjectBuilder() : new CProjectBuilder(cCompiler);
        var builderCreateDuration = elapsedSince(builderCreateStart);

        var projectBuildStart = System.nanoTime();
        var result = builder.buildProject(projectInfo, codegen);
        var projectBuildDuration = elapsedSince(projectBuildStart);

        var timing = new CaseBuildResult.NativeBuildTiming(
                targetPlatformDuration,
                projectInfoDuration,
                codegenCreateDuration,
                contextCreateDuration,
                codegenPrepareDuration,
                builderCreateDuration,
                projectBuildDuration,
                result.timing(),
                elapsedSince(totalStart)
        );
        return new NativeBuild(result, timing);
    }

    private @NotNull String readRequiredResourceText(@NotNull String resourcePath) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("Required resource not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static @NotNull String formatCounterpartMismatch(
            @NotNull String counterpartRoot,
            @NotNull String counterpartLabel,
            @NotNull List<String> missingPaths,
            @NotNull List<String> unexpectedPaths
    ) {
        if (!missingPaths.isEmpty() && unexpectedPaths.isEmpty()) {
            return "Missing benchmark " + counterpartLabel + " resource(s) under "
                    + counterpartRoot + ": " + String.join(", ", missingPaths);
        }
        if (missingPaths.isEmpty()) {
            return "Unexpected benchmark " + counterpartLabel + " resource(s) without compiled counterpart under "
                    + counterpartRoot + ": " + String.join(", ", unexpectedPaths);
        }
        return "Benchmark " + counterpartLabel + " resource mismatch under " + counterpartRoot
                + ". Missing: " + String.join(", ", missingPaths)
                + ". Unexpected: " + String.join(", ", unexpectedPaths);
    }

    /// Step 3 keeps project preparation benchmark-specific inside this runner while reusing the shared
    /// Godot project writer for the actual file generation.
    private @NotNull GodotGdextensionTestRunner.ProjectSetup prepareProjectSetup(
            @NotNull BenchmarkCaseResources resources,
            @NotNull String scriptResourcePath,
            @NotNull String runtimeClassName,
            @NotNull List<Path> artifacts
    ) {
        var interpreterResourcePath = benchmarkResourcePath(INTERPRETER_RESOURCE_ROOT, scriptResourcePath);
        var measurementResourcePath = benchmarkResourcePath(MEASUREMENT_RESOURCE_ROOT, scriptResourcePath);
        return new GodotGdextensionTestRunner.ProjectSetup(
                artifacts,
                List.of(
                        new GodotGdextensionTestRunner.SceneNodeSpec(
                                COMPILED_TARGET_NODE_NAME,
                                runtimeClassName,
                                ".",
                                Map.of()
                        ),
                        new GodotGdextensionTestRunner.SceneNodeSpec(
                                INTERPRETER_TARGET_NODE_NAME,
                                "Node",
                                ".",
                                Map.of(),
                                interpreterResourcePath
                        ),
                        new GodotGdextensionTestRunner.SceneNodeSpec(
                                MEASUREMENT_NODE_NAME,
                                "Node",
                                ".",
                                Map.of(),
                                measurementResourcePath
                        )
                ),
                List.of(
                        new ScriptResourceSpec(
                                interpreterResourcePath,
                                stripBenchmarkDirectives(resources.interpreterSource()).scriptBody()
                        ),
                        new ScriptResourceSpec(
                                measurementResourcePath,
                                prepareMeasurementScript(resources, scriptResourcePath, interpreterResourcePath)
                        )
                ),
                null,
                COptimizationLevel.RELEASE
        );
    }

    private static @NotNull String prepareMeasurementScript(
            @NotNull BenchmarkCaseResources resources,
            @NotNull String scriptResourcePath,
            @NotNull String interpreterResourcePath
    ) {
        var descriptor = stripAndValidateBenchmarkDirectives(scriptResourcePath, resources.measurementSource());
        checkMeasurementDescriptorOnly(scriptResourcePath, descriptor.scriptBody());
        return resources.measurementTemplate()
                .replace(INTERPRETER_SCRIPT_PLACEHOLDER, interpreterResourcePath)
                .replace(COMPILED_TARGET_NODE_PLACEHOLDER, COMPILED_TARGET_NODE_NAME)
                .replace(INTERPRETER_TARGET_NODE_PLACEHOLDER, INTERPRETER_TARGET_NODE_NAME)
                .replace(CASE_PATH_PLACEHOLDER, scriptResourcePath)
                .replace(CASE_NAME_PLACEHOLDER, encodeStructuredFieldValue(resources.config().name()))
                .replace(ITERATIONS_PLACEHOLDER, Integer.toString(resources.config().iterations()))
                .replace(WARMUPS_PLACEHOLDER, Integer.toString(resources.config().warmups()))
                .replace(SAMPLES_PLACEHOLDER, Integer.toString(resources.config().samples()))
                .replace(MIN_BATCH_US_PLACEHOLDER, Integer.toString(resources.config().minBatchUs()))
                .replace(PASS_MARKER_PLACEHOLDER, expectedPassMarker(scriptResourcePath));
    }

    private static void checkMeasurementDescriptorOnly(@NotNull String scriptResourcePath, @NotNull String scriptBody) {
        assertTrue(
                scriptBody.isBlank(),
                () -> "Benchmark measurement resource must contain only "
                        + BENCHMARK_DIRECTIVE_PREFIX + " directives because the executable measurement script "
                        + "is generated from " + MEASUREMENT_TEMPLATE_RESOURCE + ": " + scriptResourcePath
        );
    }

    private static @NotNull DirectiveParseResult stripBenchmarkDirectives(@NotNull String scriptSource) {
        return stripAndValidateBenchmarkDirectives(null, scriptSource);
    }

    /// Benchmark scripts stay plain executable GDScript after installation, so directives are parsed
    /// entirely on the Java side and removed before the resource is written into `test_project`.
    private static @NotNull DirectiveParseResult stripAndValidateBenchmarkDirectives(
            @Nullable String scriptResourcePath,
            @NotNull String scriptSource
    ) {
        var scriptBody = new StringBuilder();
        var directives = new ArrayList<DirectiveValue>();
        var lines = scriptSource.split("\\R", -1);
        for (var line : lines) {
            var trimmedLine = line.trim();
            if (trimmedLine.startsWith(BENCHMARK_DIRECTIVE_PREFIX)) {
                directives.add(validateBenchmarkDirective(
                        scriptResourcePath == null ? "<inline>" : scriptResourcePath,
                        trimmedLine.substring(BENCHMARK_DIRECTIVE_PREFIX.length()).trim()
                ));
                continue;
            }
            scriptBody.append(line).append('\n');
        }
        return new DirectiveParseResult(scriptBody.toString(), directives);
    }

    private static @NotNull DirectiveValue validateBenchmarkDirective(@NotNull String scriptResourcePath, @NotNull String directive) {
        assertFalse(
                directive.isBlank(),
                () -> "Benchmark directive must provide a non-empty value in " + scriptResourcePath
        );
        var knownPrefixes = List.of(
                BENCHMARK_NAME_DIRECTIVE,
                BENCHMARK_ITERATIONS_DIRECTIVE,
                BENCHMARK_WARMUPS_DIRECTIVE,
                BENCHMARK_SAMPLES_DIRECTIVE,
                BENCHMARK_MIN_BATCH_US_DIRECTIVE,
                BENCHMARK_OUTPUT_CONTAINS_DIRECTIVE,
                BENCHMARK_OUTPUT_NOT_CONTAINS_DIRECTIVE
        );
        var matchedPrefix = knownPrefixes.stream().filter(directive::startsWith).findFirst().orElse(null);
        assertNotNull(
                matchedPrefix,
                () -> "Unknown benchmark directive `" + directive + "` in " + scriptResourcePath
        );
        var value = directive.substring(matchedPrefix.length()).trim();
        assertFalse(
                value.isBlank(),
                () -> "Benchmark directive `" + directive + "` must provide a non-empty value in " + scriptResourcePath
        );
        return new DirectiveValue(matchedPrefix, value);
    }

    static @NotNull BenchmarkConfig parseBenchmarkConfig(@NotNull String scriptResourcePath, @NotNull String scriptSource) {
        var directives = stripAndValidateBenchmarkDirectives(scriptResourcePath, scriptSource).directives();
        var name = displayNameFor(scriptResourcePath);
        var iterations = DEFAULT_ITERATIONS;
        var warmups = DEFAULT_WARMUPS;
        var samples = DEFAULT_SAMPLES;
        var minBatchUs = DEFAULT_MIN_BATCH_US;
        var outputContains = new ArrayList<String>();
        var outputNotContains = new ArrayList<String>();

        for (var directive : directives) {
            switch (directive.prefix()) {
                case BENCHMARK_NAME_DIRECTIVE -> name = directive.value();
                case BENCHMARK_ITERATIONS_DIRECTIVE ->
                        iterations = requirePositiveInt(scriptResourcePath, "iterations", directive.value());
                case BENCHMARK_WARMUPS_DIRECTIVE ->
                        warmups = requireNonNegativeInt(scriptResourcePath, "warmups", directive.value());
                case BENCHMARK_SAMPLES_DIRECTIVE ->
                        samples = requirePositiveInt(scriptResourcePath, "samples", directive.value());
                case BENCHMARK_MIN_BATCH_US_DIRECTIVE ->
                        minBatchUs = requirePositiveInt(scriptResourcePath, "min_batch_us", directive.value());
                case BENCHMARK_OUTPUT_CONTAINS_DIRECTIVE -> outputContains.add(directive.value());
                case BENCHMARK_OUTPUT_NOT_CONTAINS_DIRECTIVE -> outputNotContains.add(directive.value());
                default -> throw new IllegalStateException("Unhandled directive prefix: " + directive.prefix());
            }
        }
        return new BenchmarkConfig(
                name,
                iterations,
                warmups,
                samples,
                minBatchUs,
                new OutputExpectations(outputContains, outputNotContains)
        );
    }

    private static int requirePositiveInt(@NotNull String scriptResourcePath, @NotNull String fieldName, @NotNull String value) {
        var parsed = requireInteger(scriptResourcePath, fieldName, value);
        assertTrue(parsed > 0, () -> "Benchmark directive `" + fieldName + "` must be > 0 in " + scriptResourcePath + ", got: " + value);
        return parsed;
    }

    private static int requireNonNegativeInt(@NotNull String scriptResourcePath, @NotNull String fieldName, @NotNull String value) {
        var parsed = requireInteger(scriptResourcePath, fieldName, value);
        assertTrue(parsed >= 0, () -> "Benchmark directive `" + fieldName + "` must be >= 0 in " + scriptResourcePath + ", got: " + value);
        return parsed;
    }

    private static int requireInteger(@NotNull String scriptResourcePath, @NotNull String fieldName, @NotNull String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new AssertionError("Benchmark directive `" + fieldName + "` must be an integer in "
                    + scriptResourcePath + ", got: " + value, e);
        }
    }

    private static @NotNull String benchmarkResourcePath(@NotNull String resourceRoot, @NotNull String scriptResourcePath) {
        return "res://" + resourceRoot + "/" + scriptResourcePath;
    }

    private static @NotNull String stripExtension(@NotNull String resourcePath) {
        var extensionIndex = resourcePath.lastIndexOf('.');
        return extensionIndex < 0 ? resourcePath : resourcePath.substring(0, extensionIndex);
    }

    /// Keeps per-case build directories readable while remaining safe on Windows paths.
    private static @NotNull String sanitizeCaseName(@NotNull String caseName) {
        return caseName.replace('\\', '_').replace('/', '_').replace(':', '_').replace('.', '_');
    }

    private static void copyDirectory(@NotNull Path targetDir) throws IOException {
        try (var walk = Files.walk(RUNTIME_PROJECT_TEMPLATE_DIR)) {
            for (var source : walk.toList()) {
                var relative = RUNTIME_PROJECT_TEMPLATE_DIR.relativize(source);
                var target = targetDir.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                    continue;
                }
                var parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void clearDirectory(@NotNull Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (path.equals(dir)) {
                    continue;
                }
                Files.deleteIfExists(path);
            }
        }
    }

    private static @NotNull Duration elapsedSince(long startNanos) {
        return ofNanos(System.nanoTime() - startNanos);
    }

    private static @NotNull HeaderLine parseHeaderLine(@NotNull String scriptResourcePath, @NotNull String line) {
        var fields = parseStructuredFields(HEADER_LINE_PREFIX, line);
        var casePath = requireField(scriptResourcePath, fields, "case");
        assertEqualsPath(scriptResourcePath, casePath, "header case");
        return new HeaderLine(
                casePath,
                decodeStructuredFieldValue(requireField(scriptResourcePath, fields, "name")),
                requirePositiveIntField(scriptResourcePath, fields, "iterations"),
                requireNonNegativeIntField(scriptResourcePath, fields, "warmups"),
                requirePositiveIntField(scriptResourcePath, fields, "samples"),
                requirePositiveIntField(scriptResourcePath, fields, "min_batch_us")
        );
    }

    private static @NotNull SampleLine parseResultLine(@NotNull String scriptResourcePath, @NotNull String line) {
        var fields = parseStructuredFields(RESULT_LINE_PREFIX, line);
        var casePath = requireField(scriptResourcePath, fields, "case");
        assertEqualsPath(scriptResourcePath, casePath, "sample case");
        var path = switch (requireField(scriptResourcePath, fields, "path")) {
            case "compiled" -> BenchmarkPath.COMPILED;
            case "interpreter" -> BenchmarkPath.INTERPRETER;
            default -> throw new AssertionError("Unknown benchmark path in " + scriptResourcePath + ": " + line);
        };
        return new SampleLine(
                casePath,
                path,
                requireNonNegativeIntField(scriptResourcePath, fields, "sample"),
                requirePositiveIntField(scriptResourcePath, fields, "iterations"),
                requireNonNegativeLongField(scriptResourcePath, fields, "baseline_us"),
                requireNonNegativeLongField(scriptResourcePath, fields, "benchmark_us"),
                requireLongField(scriptResourcePath, fields, "body_ns"),
                requireBooleanField(scriptResourcePath, fields, "check_ran"),
                requireBooleanField(scriptResourcePath, fields, "check_passed")
        );
    }

    private static @NotNull Map<String, String> parseStructuredFields(@NotNull String prefix, @NotNull String line) {
        assertTrue(line.startsWith(prefix), () -> "Expected line prefix `" + prefix + "`, got: " + line);
        var payload = line.substring(prefix.length()).trim();
        var fields = new LinkedHashMap<String, String>();
        for (var part : SPLIT_PATTERN.split(payload)) {
            if (part.isBlank()) {
                continue;
            }
            var separatorIndex = part.indexOf('=');
            assertTrue(separatorIndex > 0 && separatorIndex < part.length() - 1, () -> "Malformed benchmark field `" + part + "` in line: " + line);
            fields.put(part.substring(0, separatorIndex), part.substring(separatorIndex + 1));
        }
        return fields;
    }

    private static @NotNull String requireField(@NotNull String scriptResourcePath, @NotNull Map<String, String> fields, @NotNull String fieldName) {
        var value = fields.get(fieldName);
        assertNotNull(value, () -> "Missing benchmark field `" + fieldName + "` for " + scriptResourcePath + ": " + fields);
        return value;
    }

    private static int requirePositiveIntField(@NotNull String scriptResourcePath, @NotNull Map<String, String> fields, @NotNull String fieldName) {
        var value = requireField(scriptResourcePath, fields, fieldName);
        return requirePositiveInt(scriptResourcePath, fieldName, value);
    }

    private static int requireNonNegativeIntField(@NotNull String scriptResourcePath, @NotNull Map<String, String> fields, @NotNull String fieldName) {
        var value = requireField(scriptResourcePath, fields, fieldName);
        return requireNonNegativeInt(scriptResourcePath, fieldName, value);
    }

    private static long requireLongField(@NotNull String scriptResourcePath, @NotNull Map<String, String> fields, @NotNull String fieldName) {
        var value = requireField(scriptResourcePath, fields, fieldName);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new AssertionError("Benchmark field `" + fieldName + "` must be a long in " + scriptResourcePath + ", got: " + value, e);
        }
    }

    private static long requireNonNegativeLongField(@NotNull String scriptResourcePath, @NotNull Map<String, String> fields, @NotNull String fieldName) {
        var value = requireLongField(scriptResourcePath, fields, fieldName);
        assertTrue(value >= 0, () -> "Benchmark field `" + fieldName + "` must be >= 0 in " + scriptResourcePath + ", got: " + value);
        return value;
    }

    private static boolean requireBooleanField(@NotNull String scriptResourcePath, @NotNull Map<String, String> fields, @NotNull String fieldName) {
        return switch (requireField(scriptResourcePath, fields, fieldName)) {
            case "true" -> true;
            case "false" -> false;
            default ->
                    throw new AssertionError("Benchmark field `" + fieldName + "` must be `true` or `false` in " + scriptResourcePath);
        };
    }

    private static void assertEqualsPath(@NotNull String expectedPath, @NotNull String actualPath, @NotNull String label) {
        assertEquals(expectedPath, actualPath, () -> "Unexpected " + label + ". Expected " + expectedPath + ", got: " + actualPath);
    }

    private static void assertEqualsConfig(@NotNull BenchmarkConfig expectedConfig, @NotNull HeaderLine header) {
        assertEquals(expectedConfig.name(), header.name(), () -> "Benchmark header name mismatch. Expected " + expectedConfig.name() + ", got: " + header.name());
        assertEquals(expectedConfig.iterations(), header.iterations(), () -> "Benchmark header iterations mismatch. Expected " + expectedConfig.iterations() + ", got: " + header.iterations());
        assertEquals(expectedConfig.warmups(), header.warmups(), () -> "Benchmark header warmups mismatch. Expected " + expectedConfig.warmups() + ", got: " + header.warmups());
        assertEquals(expectedConfig.samples(), header.samples(), () -> "Benchmark header samples mismatch. Expected " + expectedConfig.samples() + ", got: " + header.samples());
        assertEquals(expectedConfig.minBatchUs(), header.minBatchUs(), () -> "Benchmark header min_batch_us mismatch. Expected " + expectedConfig.minBatchUs() + ", got: " + header.minBatchUs());
    }

    private static @NotNull Map<BenchmarkPath, List<SampleLine>> groupSamples(
            @NotNull String scriptResourcePath,
            @NotNull BenchmarkConfig expectedConfig,
            @NotNull List<SampleLine> resultLines
    ) {
        var grouped = new LinkedHashMap<BenchmarkPath, List<SampleLine>>();
        grouped.put(BenchmarkPath.COMPILED, new ArrayList<>());
        grouped.put(BenchmarkPath.INTERPRETER, new ArrayList<>());
        for (var line : resultLines) {
            grouped.get(line.path()).add(line);
        }
        for (var path : BenchmarkPath.values()) {
            var samples = grouped.get(path);
            assertEquals(samples.size(), expectedConfig.samples(), () -> "Expected " + expectedConfig.samples()
                    + " benchmark samples for " + path.jsonValue() + " in " + scriptResourcePath + ", got: " + samples.size());
            for (var index = 0; index < samples.size(); index++) {
                var sample = samples.get(index);
                var expectedIndex = index;
                assertEquals(sample.sample(), expectedIndex, () -> "Unexpected sample index for " + path.jsonValue() + " in " + scriptResourcePath
                        + ". Expected " + expectedIndex + ", got: " + sample.sample());
                assertEquals(sample.iterations(), expectedConfig.iterations(), () -> "Unexpected iterations for " + path.jsonValue()
                        + " sample " + expectedIndex + " in " + scriptResourcePath + ". Expected " + expectedConfig.iterations()
                        + ", got: " + sample.iterations());
            }
        }
        return grouped;
    }

    private static @NotNull BenchmarkReport.CaseSummary buildCaseSummary(
            @NotNull String scriptResourcePath,
            @NotNull BenchmarkConfig config,
            @NotNull Map<BenchmarkPath, List<SampleLine>> groupedSamples,
            @NotNull GodotGdextensionTestRunner.GodotRunResult runResult,
            @NotNull String combinedOutput
    ) {
        var compiledStats = summarizePath(config, groupedSamples.get(BenchmarkPath.COMPILED));
        var interpreterStats = summarizePath(config, groupedSamples.get(BenchmarkPath.INTERPRETER));
        var warnings = new ArrayList<String>();
        warnings.addAll(compiledStats.warnings());
        warnings.addAll(interpreterStats.warnings());
        var ratio = new BenchmarkReport.RatioSummary(
                divide(compiledStats.meanBodyNs(), interpreterStats.meanBodyNs()),
                divide(interpreterStats.meanBodyNs(), compiledStats.meanBodyNs())
        );
        return new BenchmarkReport.CaseSummary(
                scriptResourcePath.replace('\\', '/'),
                config.name(),
                toReportConfig(config),
                "passed",
                deduplicateWarnings(warnings),
                null,
                compiledStats,
                interpreterStats,
                ratio,
                true,
                runResult.command(),
                combinedOutput
        );
    }

    private static @NotNull BenchmarkReport.PathStatistics summarizePath(
            @NotNull BenchmarkConfig config,
            @NotNull List<SampleLine> samples
    ) {
        var bodyValues = samples.stream().mapToLong(SampleLine::bodyNs).toArray();
        var overheadValues = samples.stream().mapToDouble(SampleLine::meanOverheadNs).toArray();
        var warnings = new ArrayList<String>();
        for (var sample : samples) {
            if (sample.bodyNs() < 0) {
                warnings.add(WARNING_NEGATIVE_BODY + ":" + sample.path().jsonValue() + ":" + sample.sample());
            }
            if (sample.baselineUs() < config.minBatchUs() || sample.benchmarkUs() < config.minBatchUs()) {
                warnings.add(WARNING_SHORT_BATCH + ":" + sample.path().jsonValue() + ":" + sample.sample());
            }
            if (!sample.checkRan()) {
                warnings.add(WARNING_MISSING_CHECK + ":" + sample.path().jsonValue() + ":" + sample.sample());
            }
            assertTrue(sample.checkPassed(), () -> "Benchmark behavior check failed for " + sample.casePath()
                    + " on " + sample.path().jsonValue() + " sample " + sample.sample());
        }

        return new BenchmarkReport.PathStatistics(
                samples.size(),
                mean(bodyValues),
                sampleStddev(bodyValues),
                min(bodyValues),
                max(bodyValues),
                mean(overheadValues),
                samples
                        .stream()
                        .map(sample -> new BenchmarkReport.RawSample(
                                sample.sample(),
                                sample.iterations(),
                                sample.baselineUs(),
                                sample.benchmarkUs(),
                                sample.bodyNs()
                        ))
                        .toList(),
                deduplicateWarnings(warnings)
        );
    }

    private static @NotNull List<String> deduplicateWarnings(@NotNull List<String> warnings) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(warnings));
    }

    private static double mean(long @NotNull [] values) {
        assertTrue(values.length > 0, "Cannot compute mean of empty sample set");
        long sum = 0;
        for (var value : values) {
            sum += value;
        }
        return sum / (double) values.length;
    }

    private static double mean(double @NotNull [] values) {
        assertTrue(values.length > 0, "Cannot compute mean of empty sample set");
        double sum = 0;
        for (var value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double sampleStddev(long @NotNull [] values) {
        if (values.length <= 1) {
            return 0.0;
        }
        var mean = mean(values);
        double squaredDeltaSum = 0.0;
        for (var value : values) {
            var delta = value - mean;
            squaredDeltaSum += delta * delta;
        }
        return Math.sqrt(squaredDeltaSum / (values.length - 1));
    }

    private static long min(long @NotNull [] values) {
        assertTrue(values.length > 0, "Cannot compute min of empty sample set");
        var min = values[0];
        for (var value : values) {
            if (value < min) {
                min = value;
            }
        }
        return min;
    }

    private static long max(long @NotNull [] values) {
        assertTrue(values.length > 0, "Cannot compute max of empty sample set");
        var max = values[0];
        for (var value : values) {
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    private static double divide(double numerator, double denominator) {
        if (denominator == 0.0d) {
            return numerator == 0.0d ? 0.0d : Double.POSITIVE_INFINITY;
        }
        return numerator / denominator;
    }

    private static @NotNull BenchmarkReport.EnvironmentSummary currentEnvironment() {
        // The report captures the host/toolchain snapshot once per run so persisted benchmark JSON
        // can be compared later without guessing which binaries or optimization level were used.
        var godotBinary = GodotGdextensionTestRunner.findGodotBinaryFromEnv();
        var zig = ZigUtil.findZig();
        return new BenchmarkReport.EnvironmentSummary(
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                System.getProperty("java.version"),
                godotBinary == null ? null : godotBinary.toAbsolutePath().toString().replace('\\', '/'),
                GodotVersion.V451.version,
                zig == null ? null : zig.toAbsolutePath().toString().replace('\\', '/'),
                TargetPlatform.getNativePlatform().name(),
                COptimizationLevel.RELEASE.name()
        );
    }

    static @NotNull String expectedPassMarker(@NotNull String scriptResourcePath) {
        return PASS_MARKER_PREFIX + scriptResourcePath;
    }

    private static @NotNull String displayNameFor(@NotNull String scriptResourcePath) {
        var base = stripExtension(scriptResourcePath);
        var slashIndex = base.lastIndexOf('/');
        var name = slashIndex >= 0 ? base.substring(slashIndex + 1) : base;
        return name.replace('_', ' ');
    }

    private static @NotNull String encodeStructuredFieldValue(@NotNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static @NotNull String decodeStructuredFieldValue(@NotNull String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    static @NotNull String summaryLine(
            @NotNull BenchmarkReport.CaseSummary caseSummary
    ) {
        var compiled = Objects.requireNonNull(caseSummary.compiled());
        var interpreter = Objects.requireNonNull(caseSummary.interpreter());
        var ratio = Objects.requireNonNull(caseSummary.ratio());
        return "[gdcc-benchmark] case=" + caseSummary.casePath()
                + " compiled.mean=" + formatDurationNs(compiled.meanBodyNs())
                + " compiled.stddev=" + formatDurationNs(compiled.stddevBodyNs())
                + " interpreter.mean=" + formatDurationNs(interpreter.meanBodyNs())
                + " interpreter.stddev=" + formatDurationNs(interpreter.stddevBodyNs())
                + " ratio=" + formatRatio(ratio.compiledToInterpreterMean())
                + " samples=" + caseSummary.config().samples()
                + " iterations=" + caseSummary.config().iterations();
    }

    private static @NotNull BenchmarkReport.ReportConfig toReportConfig(@NotNull BenchmarkConfig config) {
        return new BenchmarkReport.ReportConfig(
                config.warmups(),
                config.samples(),
                config.iterations(),
                config.minBatchUs()
        );
    }

    private static @NotNull String formatDurationNs(double nanoseconds) {
        return String.format(Locale.ROOT, "%.3fus", nanoseconds / 1_000.0);
    }

    private static @NotNull String formatRatio(double value) {
        if (Double.isInfinite(value)) {
            return "inf";
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    public record CaseBuildResult(
            @NotNull String scriptResourcePath,
            @NotNull String runtimeClassName,
            @NotNull Path projectDir,
            @NotNull CBuildResult buildResult,
            @NotNull GodotGdextensionTestRunner.ProjectSetup projectSetup,
            @NotNull BenchmarkConfig config,
            @NotNull Timing timing
    ) {
        public CaseBuildResult {
            scriptResourcePath = Objects.requireNonNull(scriptResourcePath);
            runtimeClassName = Objects.requireNonNull(runtimeClassName);
            Objects.requireNonNull(projectDir);
            Objects.requireNonNull(buildResult);
            Objects.requireNonNull(projectSetup);
            Objects.requireNonNull(config);
            Objects.requireNonNull(timing);
        }

        public @NotNull Path requireDynamicLibraryArtifact() {
            return buildResult.artifacts().stream()
                    .filter(CaseBuildResult::isDynamicLibraryArtifact)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No dynamic library artifact found for " + scriptResourcePath + ": " + buildResult.artifacts()
                    ));
        }

        private static boolean isDynamicLibraryArtifact(@NotNull Path artifact) {
            var artifactName = artifact.getFileName().toString();
            return artifactName.endsWith(".dll")
                    || artifactName.endsWith(".so")
                    || artifactName.endsWith(".dylib")
                    || artifactName.endsWith(".wasm");
        }

        public record Timing(
                @NotNull Duration resourceRead,
                @NotNull Duration workDirectoryPrepare,
                @NotNull Duration frontendLowering,
                @NotNull Duration runtimeClassValidation,
                @NotNull Duration projectSetupPrepare,
                @NotNull NativeBuildTiming nativeBuild,
                @NotNull Duration total
        ) {
            public Timing {
                Objects.requireNonNull(resourceRead);
                Objects.requireNonNull(workDirectoryPrepare);
                Objects.requireNonNull(frontendLowering);
                Objects.requireNonNull(runtimeClassValidation);
                Objects.requireNonNull(projectSetupPrepare);
                Objects.requireNonNull(nativeBuild);
                Objects.requireNonNull(total);
            }
        }

        public record NativeBuildTiming(
                @NotNull Duration targetPlatform,
                @NotNull Duration projectInfo,
                @NotNull Duration codegenCreate,
                @NotNull Duration contextCreate,
                @NotNull Duration codegenPrepare,
                @NotNull Duration builderCreate,
                @NotNull Duration projectBuild,
                @NotNull CBuildResult.Timing projectBuildDetails,
                @NotNull Duration total
        ) {
            public NativeBuildTiming {
                Objects.requireNonNull(targetPlatform);
                Objects.requireNonNull(projectInfo);
                Objects.requireNonNull(codegenCreate);
                Objects.requireNonNull(contextCreate);
                Objects.requireNonNull(codegenPrepare);
                Objects.requireNonNull(builderCreate);
                Objects.requireNonNull(projectBuild);
                Objects.requireNonNull(projectBuildDetails);
                Objects.requireNonNull(total);
            }
        }
    }

    public record CaseRuntimeResult(
            @NotNull CaseBuildResult buildResult,
            @NotNull GodotGdextensionTestRunner.GodotRunResult runResult,
            @NotNull BenchmarkReport report,
            @NotNull Path reportPath,
            @NotNull Timing timing
    ) {
        public CaseRuntimeResult {
            Objects.requireNonNull(buildResult);
            Objects.requireNonNull(runResult);
            Objects.requireNonNull(report);
            Objects.requireNonNull(reportPath);
            Objects.requireNonNull(timing);
        }

        public record Timing(
                @NotNull CaseBuildResult.Timing build,
                @NotNull Duration godotProjectPrepare,
                @NotNull GodotGdextensionTestRunner.GodotRunResult.Timing godotRun,
                @NotNull Duration outputValidation,
                @NotNull Duration total
        ) {
            public Timing {
                Objects.requireNonNull(build);
                Objects.requireNonNull(godotProjectPrepare);
                Objects.requireNonNull(godotRun);
                Objects.requireNonNull(outputValidation);
                Objects.requireNonNull(total);
            }
        }
    }

    public record BenchmarkConfig(
            @NotNull String name,
            int iterations,
            int warmups,
            int samples,
            int minBatchUs,
            @NotNull OutputExpectations outputExpectations
    ) {
        public BenchmarkConfig {
            name = Objects.requireNonNull(name);
            assertFalse(name.isBlank(), "Benchmark name must not be blank");
            assertTrue(iterations > 0, "Benchmark iterations must be > 0");
            assertTrue(warmups >= 0, "Benchmark warmups must be >= 0");
            assertTrue(samples > 0, "Benchmark samples must be > 0");
            assertTrue(minBatchUs > 0, "Benchmark min_batch_us must be > 0");
            Objects.requireNonNull(outputExpectations);
        }
    }

    public record OutputExpectations(
            @NotNull List<String> outputContains,
            @NotNull List<String> outputNotContains
    ) {
        public OutputExpectations {
            outputContains = List.copyOf(Objects.requireNonNull(outputContains));
            outputNotContains = List.copyOf(Objects.requireNonNull(outputNotContains));
        }

        public void assertSatisfied(@NotNull String scriptResourcePath, @NotNull String output) {
            for (var expected : outputContains) {
                assertTrue(output.contains(expected), () -> "Benchmark output for " + scriptResourcePath
                        + " did not contain expected text `" + expected + "`.\nOutput:\n" + output);
            }
            for (var unexpected : outputNotContains) {
                assertFalse(output.contains(unexpected), () -> "Benchmark output for " + scriptResourcePath
                        + " unexpectedly contained text `" + unexpected + "`.\nOutput:\n" + output);
            }
        }
    }

    enum BenchmarkPath {
        COMPILED("compiled"),
        INTERPRETER("interpreter");

        private final @NotNull String jsonValue;

        BenchmarkPath(@NotNull String jsonValue) {
            this.jsonValue = jsonValue;
        }

        public @NotNull String jsonValue() {
            return jsonValue;
        }
    }

    private record BenchmarkCaseResources(
            @NotNull String scriptResourcePath,
            @NotNull String compiledSource,
            @NotNull String interpreterSource,
            @NotNull String measurementSource,
            @NotNull String measurementTemplate,
            @NotNull BenchmarkConfig config
    ) {
        private BenchmarkCaseResources {
            Objects.requireNonNull(scriptResourcePath);
            Objects.requireNonNull(compiledSource);
            Objects.requireNonNull(interpreterSource);
            Objects.requireNonNull(measurementSource);
            Objects.requireNonNull(measurementTemplate);
            Objects.requireNonNull(config);
        }
    }

    private record LoweredCase(@NotNull LirModule module, @NotNull ClassRegistry classRegistry) {
    }

    private record NativeBuild(@NotNull CBuildResult result, @NotNull CaseBuildResult.NativeBuildTiming timing) {
        private NativeBuild {
            Objects.requireNonNull(result);
            Objects.requireNonNull(timing);
        }
    }

    private record DirectiveParseResult(@NotNull String scriptBody, @NotNull List<DirectiveValue> directives) {
        private DirectiveParseResult {
            Objects.requireNonNull(scriptBody);
            directives = List.copyOf(Objects.requireNonNull(directives));
        }
    }

    private record DirectiveValue(@NotNull String prefix, @NotNull String value) {
        private DirectiveValue {
            Objects.requireNonNull(prefix);
            Objects.requireNonNull(value);
        }
    }

    private record HeaderLine(
            @NotNull String casePath,
            @NotNull String name,
            int iterations,
            int warmups,
            int samples,
            int minBatchUs
    ) {
    }

    private record SampleLine(
            @NotNull String casePath,
            @NotNull BenchmarkPath path,
            int sample,
            int iterations,
            long baselineUs,
            long benchmarkUs,
            long bodyNs,
            boolean checkRan,
            boolean checkPassed
    ) {
        private double meanOverheadNs() {
            return baselineUs * 1_000.0 / iterations;
        }
    }
}
