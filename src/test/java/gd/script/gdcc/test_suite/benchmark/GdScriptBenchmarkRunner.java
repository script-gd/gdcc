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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.time.Duration.ofNanos;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Step 1 / Step 2 benchmark harness scaffolding.
///
/// This runner keeps the benchmark implementation inside the existing test-suite architecture:
/// it discovers benchmark fixtures from classpath resources, validates the three-tree contract,
/// parses and lowers the compiled benchmark source, and builds a release native artifact through
/// the current C backend.
public final class GdScriptBenchmarkRunner {
    public static final String SCRIPT_RESOURCE_ROOT = "benchmark/script";
    public static final String INTERPRETER_RESOURCE_ROOT = "benchmark/interpreter";
    public static final String MEASUREMENT_RESOURCE_ROOT = "benchmark/measurement";
    static final String COMPILED_TARGET_NODE_NAME = "CompiledTarget";
    static final String INTERPRETER_TARGET_NODE_NAME = "InterpreterTarget";
    static final String MEASUREMENT_NODE_NAME = "BenchmarkMeasurement";
    static final String INTERPRETER_SCRIPT_PLACEHOLDER = "__GDCC_BENCHMARK_INTERPRETER_SCRIPT__";
    static final String COMPILED_TARGET_NODE_PLACEHOLDER = "__GDCC_BENCHMARK_COMPILED_TARGET_NODE__";
    static final String INTERPRETER_TARGET_NODE_PLACEHOLDER = "__GDCC_BENCHMARK_INTERPRETER_TARGET_NODE__";
    static final String BENCHMARK_DIRECTIVE_PREFIX = "# gdcc-benchmark:";
    static final String BENCHMARK_NAME_DIRECTIVE = "name=";
    static final String BENCHMARK_ITERATIONS_DIRECTIVE = "iterations=";
    static final String BENCHMARK_WARMUPS_DIRECTIVE = "warmups=";
    static final String BENCHMARK_SAMPLES_DIRECTIVE = "samples=";
    static final String BENCHMARK_MIN_BATCH_US_DIRECTIVE = "min_batch_us=";
    static final String BENCHMARK_OUTPUT_CONTAINS_DIRECTIVE = "output_contains=";
    static final String BENCHMARK_OUTPUT_NOT_CONTAINS_DIRECTIVE = "output_not_contains=";

    private static final Path WORK_ROOT = Path.of("tmp/test/test_suite/benchmark");

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
        return new CaseBuildResult(scriptResourcePath, runtimeClassName, projectDir, nativeBuild.result(), projectSetup, timing);
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
        return new BenchmarkCaseResources(
                scriptResourcePath,
                readRequiredResourceText(SCRIPT_RESOURCE_ROOT + "/" + scriptResourcePath),
                readRequiredResourceText(INTERPRETER_RESOURCE_ROOT + "/" + scriptResourcePath),
                readRequiredResourceText(MEASUREMENT_RESOURCE_ROOT + "/" + scriptResourcePath)
        );
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
                                stripBenchmarkDirectives(resources.interpreterSource())
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
        var directiveParseResult = stripAndValidateBenchmarkDirectives(scriptResourcePath, resources.measurementSource());
        return directiveParseResult.scriptBody()
                .replace(INTERPRETER_SCRIPT_PLACEHOLDER, interpreterResourcePath)
                .replace(COMPILED_TARGET_NODE_PLACEHOLDER, COMPILED_TARGET_NODE_NAME)
                .replace(INTERPRETER_TARGET_NODE_PLACEHOLDER, INTERPRETER_TARGET_NODE_NAME);
    }

    private static @NotNull String stripBenchmarkDirectives(@NotNull String scriptSource) {
        return stripAndValidateBenchmarkDirectives(null, scriptSource).scriptBody();
    }

    /// Step 3 only needs directive validation and stripping so Godot receives plain executable scripts.
    private static @NotNull DirectiveParseResult stripAndValidateBenchmarkDirectives(
            @Nullable String scriptResourcePath,
            @NotNull String scriptSource
    ) {
        var scriptBody = new StringBuilder();
        var lines = scriptSource.split("\\R", -1);
        for (var line : lines) {
            var trimmedLine = line.trim();
            if (trimmedLine.startsWith(BENCHMARK_DIRECTIVE_PREFIX)) {
                validateBenchmarkDirective(
                        scriptResourcePath == null ? "<inline>" : scriptResourcePath,
                        trimmedLine.substring(BENCHMARK_DIRECTIVE_PREFIX.length()).trim()
                );
                continue;
            }
            scriptBody.append(line).append('\n');
        }
        return new DirectiveParseResult(scriptBody.toString(), scriptBody.isEmpty());
    }

    private static void validateBenchmarkDirective(@NotNull String scriptResourcePath, @NotNull String directive) {
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

    private static @NotNull Duration elapsedSince(long startNanos) {
        return ofNanos(System.nanoTime() - startNanos);
    }

    public record CaseBuildResult(
            @NotNull String scriptResourcePath,
            @NotNull String runtimeClassName,
            @NotNull Path projectDir,
            @NotNull CBuildResult buildResult,
            @NotNull GodotGdextensionTestRunner.ProjectSetup projectSetup,
            @NotNull Timing timing
    ) {
        public CaseBuildResult {
            scriptResourcePath = Objects.requireNonNull(scriptResourcePath);
            runtimeClassName = Objects.requireNonNull(runtimeClassName);
            Objects.requireNonNull(projectDir);
            Objects.requireNonNull(buildResult);
            Objects.requireNonNull(projectSetup);
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

    private record BenchmarkCaseResources(
            @NotNull String scriptResourcePath,
            @NotNull String compiledSource,
            @NotNull String interpreterSource,
            @NotNull String measurementSource
    ) {
        private BenchmarkCaseResources {
            Objects.requireNonNull(scriptResourcePath);
            Objects.requireNonNull(compiledSource);
            Objects.requireNonNull(interpreterSource);
            Objects.requireNonNull(measurementSource);
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

    private record DirectiveParseResult(@NotNull String scriptBody, boolean empty) {
        private DirectiveParseResult {
            Objects.requireNonNull(scriptBody);
        }
    }
}
