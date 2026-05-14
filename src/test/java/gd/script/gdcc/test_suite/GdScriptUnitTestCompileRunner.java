package gd.script.gdcc.test_suite;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.build.CBuildResult;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.CProjectBuilder;
import gd.script.gdcc.backend.c.build.CProjectInfo;
import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static java.time.Duration.ofNanos;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Compiles each GDScript resource under `unit_test/script` into a native library and validates
/// the runtime behavior with the same relative-path script under `unit_test/validation`.
///
/// Validation scripts are regular GDScript files. The runner substitutes two placeholders before
/// installing them into the Godot test project:
/// - `__UNIT_TEST_TARGET_NODE_NAME__`
/// - `__UNIT_TEST_PASS_MARKER__`
public final class GdScriptUnitTestCompileRunner {
    public static final String SCRIPT_RESOURCE_ROOT = "unit_test/script";
    public static final String VALIDATION_RESOURCE_ROOT = "unit_test/validation";

    private static final String TARGET_NODE_NAME = "TargetNode";
    private static final String TARGET_NODE_NAME_PLACEHOLDER = "__UNIT_TEST_TARGET_NODE_NAME__";
    private static final String PASS_MARKER_PLACEHOLDER = "__UNIT_TEST_PASS_MARKER__";
    private static final String VALIDATION_DIRECTIVE_PREFIX = "# gdcc-test:";
    private static final String OUTPUT_CONTAINS_DIRECTIVE = "output_contains=";
    private static final String OUTPUT_NOT_CONTAINS_DIRECTIVE = "output_not_contains=";
    private static final String OUTPUT_CONTAINS_ANY_DIRECTIVE = "output_contains_any=";
    private static final Path WORK_ROOT = Path.of("tmp/test/test_suite/gdscript_unit");

    private final @NotNull ClassLoader loader;
    private final @NotNull GdScriptParserService parser;
    private final @NotNull FrontendLoweringPassManager loweringPassManager;

    public GdScriptUnitTestCompileRunner() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public GdScriptUnitTestCompileRunner(@NotNull ClassLoader loader) {
        this.loader = Objects.requireNonNull(loader);
        parser = new GdScriptParserService();
        loweringPassManager = new FrontendLoweringPassManager();
    }

    public @NotNull List<String> listScriptResourcePaths() throws IOException {
        var scriptResourcePaths = ResourceExtractor.listResourceFilesRecursively(SCRIPT_RESOURCE_ROOT, loader);
        assertFalse(scriptResourcePaths.isEmpty(), "No GDScript unit-test resources found under " + SCRIPT_RESOURCE_ROOT);
        return scriptResourcePaths;
    }

    public @NotNull CaseResult compileAndValidate(@NotNull String scriptResourcePath) throws Exception {
        return compileAndValidate(scriptResourcePath, GodotGdextensionTestRunner.defaultRunOptions(true));
    }

    public @NotNull CaseResult compileAndValidate(
            @NotNull String scriptResourcePath,
            @NotNull GodotGdextensionTestRunner.RunOptions runOptions
    ) throws Exception {
        Objects.requireNonNull(scriptResourcePath);
        Objects.requireNonNull(runOptions);

        var totalStart = System.nanoTime();
        var sourceResourceReadStart = System.nanoTime();
        var source = readRequiredResourceText(SCRIPT_RESOURCE_ROOT + "/" + scriptResourcePath);
        var sourceResourceReadDuration = elapsedSince(sourceResourceReadStart);

        var validationResourceReadStart = System.nanoTime();
        var validationTemplate = readRequiredResourceText(VALIDATION_RESOURCE_ROOT + "/" + scriptResourcePath);
        var validationResourceReadDuration = elapsedSince(validationResourceReadStart);

        var validationPrepareStart = System.nanoTime();
        var validation = prepareValidation(
                scriptResourcePath,
                validationTemplate
        );
        var validationPrepareDuration = elapsedSince(validationPrepareStart);

        var caseName = sanitizeCaseName(stripExtension(scriptResourcePath));
        var sourcePath = WORK_ROOT.resolve("sources").resolve(scriptResourcePath);
        var projectDir = WORK_ROOT.resolve("build").resolve(caseName);
        var workDirectoryPrepareStart = System.nanoTime();
        Files.createDirectories(projectDir);
        var workDirectoryPrepareDuration = elapsedSince(workDirectoryPrepareStart);

        var frontendLoweringStart = System.nanoTime();
        var lowered = lowerModule(sourcePath, source, caseName);
        var frontendLoweringDuration = elapsedSince(frontendLoweringStart);

        var runtimeClassValidationStart = System.nanoTime();
        var runtimeClassName = requireRuntimeClassName(lowered, scriptResourcePath);
        var runtimeClassValidationDuration = elapsedSince(runtimeClassValidationStart);

        var nativeBuild = buildNativeLibrary(lowered.module(), lowered.classRegistry(), projectDir, caseName);
        var buildResult = nativeBuild.result();
        assertTrue(buildResult.success(), () -> "Native build failed for " + scriptResourcePath + ".\nBuild log:\n" + buildResult.buildLog());

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        var projectPrepareStart = System.nanoTime();
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        TARGET_NODE_NAME,
                        runtimeClassName,
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(validation.script())
        ));
        var projectPrepareDuration = elapsedSince(projectPrepareStart);

        var runResult = runner.run(runOptions);
        var outputValidationStart = System.nanoTime();
        var combinedOutput = runResult.combinedOutput();
        var passMarker = expectedPassMarker(scriptResourcePath);
        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run for " + scriptResourcePath + " did not emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains(passMarker),
                () -> "Validation script for " + scriptResourcePath + " did not report success marker \"" + passMarker + "\".\nOutput:\n" + combinedOutput
        );
        validation.expectations().assertSatisfied(scriptResourcePath, combinedOutput);
        var outputValidationDuration = elapsedSince(outputValidationStart);

        var timing = new CaseResult.Timing(
                sourceResourceReadDuration,
                validationResourceReadDuration,
                validationPrepareDuration,
                workDirectoryPrepareDuration,
                frontendLoweringDuration,
                runtimeClassValidationDuration,
                nativeBuild.timing(),
                projectPrepareDuration,
                runResult.timing(),
                outputValidationDuration,
                elapsedSince(totalStart)
        );
        return new CaseResult(scriptResourcePath, runtimeClassName, buildResult.artifacts(), runResult, timing);
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

    /// Unit-test scripts may lower additional inner classes. The mounted runtime root remains the
    /// first published top-level class and must stay `Node`-derived so Godot installs it as a real
    /// scene node instead of a placeholder.
    private @NotNull String requireRuntimeClassName(@NotNull LoweredCase lowered, @NotNull String scriptResourcePath) {
        var classDefs = lowered.module().getClassDefs();
        assertFalse(classDefs.isEmpty(), () -> "Each unit-test script must lower to at least one class: " + scriptResourcePath);

        var runtimeRootClass = classDefs.getFirst();
        assertTrue(
                lowered.classRegistry().checkAssignable(
                        new GdObjectType(runtimeRootClass.getName()),
                        new GdObjectType("Node")
                ),
                () -> "Mounted unit-test root class must remain Node-derived, but "
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
                COptimizationLevel.DEBUG,
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
        var builder = new CProjectBuilder();
        var builderCreateDuration = elapsedSince(builderCreateStart);

        var projectBuildStart = System.nanoTime();
        var result = builder.buildProject(projectInfo, codegen);
        var projectBuildDuration = elapsedSince(projectBuildStart);
        var timing = new CaseResult.NativeBuildTiming(
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

    /// Validation templates may declare runner-side output assertions with `# gdcc-test:` comments.
    /// The directives are stripped before the script is installed into the Godot project, so only
    /// the remaining GDScript source is executed at runtime.
    private static @NotNull PreparedValidation prepareValidation(@NotNull String scriptResourcePath, @NotNull String validationTemplate) {
        return PreparedValidation.parse(renderValidationTemplate(scriptResourcePath, validationTemplate));
    }

    private static @NotNull String renderValidationTemplate(@NotNull String scriptResourcePath, @NotNull String validationTemplate) {
        return validationTemplate
                .replace(TARGET_NODE_NAME_PLACEHOLDER, TARGET_NODE_NAME)
                .replace(PASS_MARKER_PLACEHOLDER, expectedPassMarker(scriptResourcePath));
    }

    private static @NotNull String expectedPassMarker(@NotNull String scriptResourcePath) {
        return "UNIT_TEST_PASS::" + scriptResourcePath;
    }

    private static @NotNull String stripExtension(@NotNull String resourcePath) {
        var extensionIndex = resourcePath.lastIndexOf('.');
        return extensionIndex < 0 ? resourcePath : resourcePath.substring(0, extensionIndex);
    }

    /// Keeps per-case build directories readable while remaining safe on Windows paths.
    private static @NotNull String sanitizeCaseName(@NotNull String caseName) {
        return caseName.replace('\\', '_').replace('/', '_').replace(':', '_').replace('.', '_');
    }

    private static @NotNull String requireDirectiveValue(@NotNull String directive, @NotNull String prefix) {
        var value = directive.substring(prefix.length()).trim();
        assertFalse(value.isEmpty(), () -> "Validation directive `" + directive + "` must provide a non-empty value.");
        return value;
    }

    private static @NotNull Duration elapsedSince(long startNanos) {
        return ofNanos(System.nanoTime() - startNanos);
    }

    private static @NotNull String formatNullableDuration(@Nullable Duration duration) {
        return duration == null ? "n/a" : formatDuration(duration);
    }

    private static @NotNull String formatDuration(@NotNull Duration duration) {
        return String.format(Locale.ROOT, "%.3fms", duration.toNanos() / 1_000_000.0);
    }

    public record CaseResult(
            @NotNull String scriptResourcePath,
            @NotNull String runtimeClassName,
            @NotNull List<Path> artifacts,
            @NotNull GodotGdextensionTestRunner.GodotRunResult runResult,
            @NotNull Timing timing
    ) {
        public CaseResult {
            scriptResourcePath = Objects.requireNonNull(scriptResourcePath);
            runtimeClassName = Objects.requireNonNull(runtimeClassName);
            artifacts = List.copyOf(artifacts);
            Objects.requireNonNull(runResult);
            Objects.requireNonNull(timing);
        }

        public CaseResult(
                @NotNull String scriptResourcePath,
                @NotNull String runtimeClassName,
                @NotNull List<Path> artifacts,
                @NotNull GodotGdextensionTestRunner.GodotRunResult runResult
        ) {
            this(scriptResourcePath, runtimeClassName, artifacts, runResult, Timing.zero());
        }

        public record Timing(
                @NotNull Duration sourceResourceRead,
                @NotNull Duration validationResourceRead,
                @NotNull Duration validationPrepare,
                @NotNull Duration workDirectoryPrepare,
                @NotNull Duration frontendLowering,
                @NotNull Duration runtimeClassValidation,
                @NotNull NativeBuildTiming nativeBuild,
                @NotNull Duration godotProjectPrepare,
                @NotNull GodotGdextensionTestRunner.GodotRunResult.Timing godotRun,
                @NotNull Duration outputValidation,
                @NotNull Duration total
        ) {
            public Timing {
                Objects.requireNonNull(sourceResourceRead);
                Objects.requireNonNull(validationResourceRead);
                Objects.requireNonNull(validationPrepare);
                Objects.requireNonNull(workDirectoryPrepare);
                Objects.requireNonNull(frontendLowering);
                Objects.requireNonNull(runtimeClassValidation);
                Objects.requireNonNull(nativeBuild);
                Objects.requireNonNull(godotProjectPrepare);
                Objects.requireNonNull(godotRun);
                Objects.requireNonNull(outputValidation);
                Objects.requireNonNull(total);
            }

            public static @NotNull Timing zero() {
                return new Timing(
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        NativeBuildTiming.zero(),
                        Duration.ZERO,
                        GodotGdextensionTestRunner.GodotRunResult.Timing.zero(),
                        Duration.ZERO,
                        Duration.ZERO
                );
            }

            public @NotNull String summaryLine(@NotNull String scriptResourcePath) {
                return "[gdcc-test-timing] case=" + scriptResourcePath
                        + " total=" + formatDuration(total)
                        + " resources.source=" + formatDuration(sourceResourceRead)
                        + " resources.validation=" + formatDuration(validationResourceRead)
                        + " validation.prepare=" + formatDuration(validationPrepare)
                        + " workdir.prepare=" + formatDuration(workDirectoryPrepare)
                        + " frontend.lower=" + formatDuration(frontendLowering)
                        + " runtime_class.check=" + formatDuration(runtimeClassValidation)
                        + " build.full=" + formatDuration(nativeBuild.total())
                        + " build.target_platform=" + formatDuration(nativeBuild.targetPlatform())
                        + " build.project_info=" + formatDuration(nativeBuild.projectInfo())
                        + " build.codegen_create=" + formatDuration(nativeBuild.codegenCreate())
                        + " build.context_create=" + formatDuration(nativeBuild.contextCreate())
                        + " build.prepare=" + formatDuration(nativeBuild.codegenPrepare())
                        + " build.builder_create=" + formatDuration(nativeBuild.builderCreate())
                        + " build.project_build=" + formatDuration(nativeBuild.projectBuild())
                        + " build.total=" + formatDuration(nativeBuild.projectBuildDetails().total())
                        + " build.include=" + formatDuration(nativeBuild.projectBuildDetails().includeExtraction())
                        + " build.codegen=" + formatDuration(nativeBuild.projectBuildDetails().codeGeneration())
                        + " build.write=" + formatDuration(nativeBuild.projectBuildDetails().generatedFileWrite())
                        + " build.inputs=" + formatDuration(nativeBuild.projectBuildDetails().compileInputCollection())
                        + " build.native_compile=" + formatDuration(nativeBuild.projectBuildDetails().nativeCompile())
                        + " godot_project.prepare=" + formatDuration(godotProjectPrepare)
                        + " godot.total=" + formatDuration(godotRun.total())
                        + " godot.binary_lookup=" + formatDuration(godotRun.binaryLookup())
                        + " godot.process_start=" + formatDuration(godotRun.processStart())
                        + " godot.first_output=" + formatNullableDuration(godotRun.firstOutputLatency())
                        + " godot.run_until_stop=" + formatNullableDuration(godotRun.stopSignalLatency())
                        + " godot.process_wait=" + formatDuration(godotRun.processWait())
                        + " godot.stream_collect=" + formatDuration(godotRun.streamCollection())
                        + " godot.executor_close=" + formatDuration(godotRun.executorClose())
                        + " output.assert=" + formatDuration(outputValidation);
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

            public static @NotNull NativeBuildTiming zero() {
                return new NativeBuildTiming(
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        CBuildResult.Timing.zero(),
                        Duration.ZERO
                );
            }
        }
    }

    private record LoweredCase(@NotNull LirModule module, @NotNull ClassRegistry classRegistry) {
    }

    private record NativeBuild(@NotNull CBuildResult result, @NotNull CaseResult.NativeBuildTiming timing) {
        private NativeBuild {
            Objects.requireNonNull(result);
            Objects.requireNonNull(timing);
        }
    }

    private record PreparedValidation(@NotNull String script, @NotNull OutputExpectations expectations) {
        private PreparedValidation {
            Objects.requireNonNull(script);
            Objects.requireNonNull(expectations);
        }

        private static @NotNull PreparedValidation parse(@NotNull String renderedTemplate) {
            var normalizedTemplate = renderedTemplate.replace("\r\n", "\n").replace('\r', '\n');
            var scriptLines = new ArrayList<String>();
            var requiredSubstrings = new ArrayList<String>();
            var forbiddenSubstrings = new ArrayList<String>();
            var anyOfRequiredSubstrings = new ArrayList<List<String>>();

            for (var line : normalizedTemplate.split("\n", -1)) {
                var trimmed = line.trim();
                if (!trimmed.startsWith(VALIDATION_DIRECTIVE_PREFIX)) {
                    scriptLines.add(line);
                    continue;
                }

                var directive = trimmed.substring(VALIDATION_DIRECTIVE_PREFIX.length()).trim();
                if (directive.startsWith(OUTPUT_CONTAINS_DIRECTIVE)) {
                    requiredSubstrings.add(requireDirectiveValue(directive, OUTPUT_CONTAINS_DIRECTIVE));
                    continue;
                }
                if (directive.startsWith(OUTPUT_NOT_CONTAINS_DIRECTIVE)) {
                    forbiddenSubstrings.add(requireDirectiveValue(directive, OUTPUT_NOT_CONTAINS_DIRECTIVE));
                    continue;
                }
                if (directive.startsWith(OUTPUT_CONTAINS_ANY_DIRECTIVE)) {
                    var options = new ArrayList<String>();
                    for (var option : requireDirectiveValue(directive, OUTPUT_CONTAINS_ANY_DIRECTIVE).split("\\s*\\|\\|\\s*")) {
                        if (!option.isBlank()) {
                            options.add(option);
                        }
                    }
                    assertFalse(options.isEmpty(), () -> "Validation directive `" + directive + "` must provide at least one non-blank alternative.");
                    anyOfRequiredSubstrings.add(List.copyOf(options));
                    continue;
                }

                throw new AssertionError("Unsupported validation directive `" + directive + "`.");
            }

            return new PreparedValidation(
                    String.join("\n", scriptLines),
                    new OutputExpectations(requiredSubstrings, forbiddenSubstrings, anyOfRequiredSubstrings)
            );
        }
    }

    private record OutputExpectations(
            @NotNull List<String> requiredSubstrings,
            @NotNull List<String> forbiddenSubstrings,
            @NotNull List<List<String>> anyOfRequiredSubstrings
    ) {
        private OutputExpectations {
            requiredSubstrings = List.copyOf(requiredSubstrings);
            forbiddenSubstrings = List.copyOf(forbiddenSubstrings);
            anyOfRequiredSubstrings = List.copyOf(anyOfRequiredSubstrings);
        }

        private void assertSatisfied(@NotNull String scriptResourcePath, @NotNull String output) {
            for (var required : requiredSubstrings) {
                assertTrue(
                        output.contains(required),
                        () -> "Output for " + scriptResourcePath + " did not contain required fragment `" + required + "`.\nOutput:\n" + output
                );
            }
            for (var forbidden : forbiddenSubstrings) {
                assertFalse(
                        output.contains(forbidden),
                        () -> "Output for " + scriptResourcePath + " unexpectedly contained forbidden fragment `" + forbidden + "`.\nOutput:\n" + output
                );
            }
            for (var anyGroup : anyOfRequiredSubstrings) {
                assertTrue(
                        anyGroup.stream().anyMatch(output::contains),
                        () -> "Output for " + scriptResourcePath + " did not contain any acceptable fragment from " + anyGroup + ".\nOutput:\n" + output
                );
            }
        }
    }
}
