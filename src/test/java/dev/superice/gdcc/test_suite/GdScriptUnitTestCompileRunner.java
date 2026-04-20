package dev.superice.gdcc.test_suite;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.build.CBuildResult;
import dev.superice.gdcc.backend.c.build.COptimizationLevel;
import dev.superice.gdcc.backend.c.build.CProjectBuilder;
import dev.superice.gdcc.backend.c.build.CProjectInfo;
import dev.superice.gdcc.backend.c.build.GodotGdextensionTestRunner;
import dev.superice.gdcc.backend.c.build.TargetPlatform;
import dev.superice.gdcc.backend.c.gen.CCodegen;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPassManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.util.ResourceExtractor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        Objects.requireNonNull(scriptResourcePath);

        var source = readRequiredResourceText(SCRIPT_RESOURCE_ROOT + "/" + scriptResourcePath);
        var validation = prepareValidation(
                scriptResourcePath,
                readRequiredResourceText(VALIDATION_RESOURCE_ROOT + "/" + scriptResourcePath)
        );
        var caseName = sanitizeCaseName(stripExtension(scriptResourcePath));
        var sourcePath = WORK_ROOT.resolve("sources").resolve(scriptResourcePath);
        var projectDir = WORK_ROOT.resolve("build").resolve(caseName);
        Files.createDirectories(projectDir);

        var lowered = lowerModule(sourcePath, source, caseName);
        var runtimeClassName = requireRuntimeClassName(lowered, scriptResourcePath);
        var buildResult = buildNativeLibrary(lowered.module(), lowered.classRegistry(), projectDir, caseName);
        assertTrue(buildResult.success(), () -> "Native build failed for " + scriptResourcePath + ".\nBuild log:\n" + buildResult.buildLog());

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
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

        var runResult = runner.run(true);
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

        return new CaseResult(scriptResourcePath, runtimeClassName, buildResult.artifacts(), runResult);
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

    private @NotNull CBuildResult buildNativeLibrary(
            @NotNull LirModule lowered,
            @NotNull ClassRegistry classRegistry,
            @NotNull Path projectDir,
            @NotNull String caseName
    ) throws IOException {
        var projectInfo = new CProjectInfo(
                caseName,
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);
        return new CProjectBuilder().buildProject(projectInfo, codegen);
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

    public record CaseResult(
            @NotNull String scriptResourcePath,
            @NotNull String runtimeClassName,
            @NotNull List<Path> artifacts,
            @NotNull GodotGdextensionTestRunner.GodotRunResult runResult
    ) {
        public CaseResult {
            scriptResourcePath = Objects.requireNonNull(scriptResourcePath);
            runtimeClassName = Objects.requireNonNull(runtimeClassName);
            artifacts = List.copyOf(artifacts);
            Objects.requireNonNull(runResult);
        }
    }

    private record LoweredCase(@NotNull LirModule module, @NotNull ClassRegistry classRegistry) {
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
