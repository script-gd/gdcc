package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPassManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// End-to-end acceptance of the tcc backend on the host: a minimal GDScript module goes through
/// the real frontend -> lowering -> C codegen -> [CProjectBuilder] pipeline dispatched by
/// [CCompilerKind#TINY_CC], the `.gdextension` metadata is generated from the produced artifact,
/// and — when `GODOT_BIN` is available — a real Godot process loads the library, exercises the
/// class through the Variant boundary (method calls and a property read) and shuts down with
/// exit code 0.
///
/// Isolation contracts: the build runs in a per-test directory with the shared include tree
/// disabled (`setIgnoreSharedInclude(true)`), and the Godot project is a private copy of the
/// `test_project` fixture conventions — the shared fixture directory is never written by this
/// class. The compiler dispatch map is injected explicitly so the round selects the bundle found
/// by [TinyCcCliTestSupport#findTinyCcBundleRoot] instead of relying on environment or cache.
class TinyCcCompilerIntegrationTest {

    private static final String PASS_MARKER = "TCCSMOKE_PASS";
    private static final String FAIL_MARKER = "TCCSMOKE_FAIL";
    /// Anchored twice: asserted against the lowered class def and mounted as the scene node class.
    private static final String RUNTIME_CLASS_NAME = "TccIntegrationProbe";
    /// Base files of the `test_project` fixture that a private project copy needs; everything
    /// else there (bin/, main.tscn, the .gdextension, test scripts) is regenerated per run.
    private static final List<String> GODOT_FIXTURE_FILES = List.of(
            "project.godot", "root.gd", "root.gd.uid", "icon.svg", "icon.svg.import");

    @TempDir
    private Path tempDir;

    private static Path bundleRoot;

    @BeforeAll
    static void requireBundle() {
        bundleRoot = TinyCcCliTestSupport.findTinyCcBundleRoot();
        Assumptions.assumeTrue(bundleRoot != null,
                "no tinycc bundle available (GDCC_TINYCC_HOME unset, no build/tinycc-bundle); skipping the tcc integration test");
    }

    @Test
    void fullPipelineCompilesAndGeneratesGdextension() throws Exception {
        var prepared = buildAndPrepareProject();

        var artifact = prepared.buildResult().artifacts().getFirst();
        assertTrue(Files.isRegularFile(artifact) && Files.size(artifact) > 0, "the tcc artifact must exist");
        NativeArtifactInspector.assertSharedLibraryShape(artifact);

        var gdextension = prepared.godotProjectDir().resolve("GDExtensionTest.gdextension");
        assertTrue(Files.isRegularFile(gdextension), "prepareProject must write the .gdextension metadata");
        var metadata = Files.readString(gdextension, StandardCharsets.UTF_8);
        assertTrue(metadata.contains("entry_symbol = \"" + GdextensionMetadataFile.ENTRY_SYMBOL + "\""), metadata);
        assertTrue(metadata.contains("reloadable = true"), metadata);
        var host = TargetPlatform.getNativePlatform();
        var libraryKey = GdextensionMetadataFile.libraryKey(COptimizationLevel.DEBUG, host);
        var expectedLibraryLine = libraryKey + " = \"res://bin/" + artifact.getFileName() + "\"";
        assertTrue(metadata.contains(expectedLibraryLine),
                () -> "the metadata must bind the host library key to the tcc artifact; expected line:\n"
                        + expectedLibraryLine + "\n--- metadata:\n" + metadata);
        // The extension list is how the headless runtime discovers the extension (no editor scan).
        var extensionList = prepared.godotProjectDir().resolve(".godot").resolve("extension_list.cfg");
        assertTrue(Files.isRegularFile(extensionList), "extension_list.cfg must exist");
        assertTrue(Files.readString(extensionList, StandardCharsets.UTF_8).contains("GDExtensionTest.gdextension"),
                "extension_list.cfg must reference the generated metadata");
    }

    @Test
    void godotLoadsRunsAndShutsDownWithTccArtifact() throws Exception {
        var godot = GodotGdextensionTestRunner.findGodotBinaryFromEnv();
        Assumptions.assumeTrue(godot != null,
                "GODOT_BIN not found; skipping the Godot loading/shutdown leg (compile-only leg still runs)");
        var prepared = buildAndPrepareProject();
        var runner = new GodotGdextensionTestRunner(prepared.godotProjectDir());

        // Leg 1: behavior — the engine loads the tcc artifact and the validation script passes.
        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();
        assertTrue(runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains(PASS_MARKER),
                () -> "validation script did not pass.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains(FAIL_MARKER),
                () -> "validation script reported a failure.\nOutput:\n" + combinedOutput);

        // Leg 2: shutdown — a full process exit with code 0 after the stop signal (the runner's
        // fast path returns at the signal and may never observe the exit code). The shared
        // process lifecycle helper carries the timeout/kill/converge discipline.
        var shutdown = RuntimeProbeToolchain.runProcess(List.of(
                godot.toString(),
                "--upwards",
                prepared.godotProjectDir().resolve("main.tscn").toAbsolutePath().toString(),
                "--headless",
                "--quit-after",
                "5"
        ), Duration.ofSeconds(60), prepared.godotProjectDir());
        assertEquals(0, shutdown.exitCode(), () -> "Godot must shut down cleanly.\nOutput:\n" + shutdown.output());
        assertTrue(shutdown.output().contains(GodotGdextensionTestRunner.TEST_STOP_SIGNAL), shutdown::output);
        assertTrue(shutdown.output().contains(PASS_MARKER), shutdown::output);
    }

    // ---------------------------------------------------------------------------

    private record PreparedProject(@NotNull CBuildResult buildResult, @NotNull Path godotProjectDir) {
    }

    /// Builds the fixture module with the tcc backend and installs it into a private Godot
    /// project copy with the validation script mounted.
    private @NotNull PreparedProject buildAndPrepareProject() throws IOException {
        var buildDir = Files.createDirectories(tempDir.resolve("build"));
        var buildResult = buildFixtureModule(buildDir);

        var godotProjectDir = Files.createDirectories(tempDir.resolve("godot_project"));
        copyGodotFixture(godotProjectDir);
        var runner = new GodotGdextensionTestRunner(godotProjectDir);
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TccProbeNode",
                        RUNTIME_CLASS_NAME,
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(validationScript())
        ));
        return new PreparedProject(buildResult, godotProjectDir);
    }

    /// Real pipeline: parse -> lower -> codegen -> dispatching [CProjectBuilder] whose tcc slot is
    /// bound to the discovered bundle. The 6-arg [CProjectInfo] carries TINY_CC so the dispatch
    /// path itself is exercised (the zig slot stays untouched).
    private @NotNull CBuildResult buildFixtureModule(@NotNull Path buildDir) throws IOException {
        var parser = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var sourcePath = buildDir.resolve("tcc_integration_probe.gd");
        var unit = parser.parseUnit(sourcePath, fixtureSource(), parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "parse diagnostics: " + parseDiagnostics.snapshot());

        var module = new FrontendModule("tcc_integration_probe", List.of(unit), Map.of());
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);
        if (lowered == null || diagnostics.hasErrors()) {
            fail("lowering failed: " + diagnostics.snapshot());
        }
        assertEquals(RUNTIME_CLASS_NAME, lowered.getClassDefs().getFirst().getName(),
                "the fixture must lower to its declared class_name");

        var host = TargetPlatform.getNativePlatform();
        var projectInfo = new CProjectInfo(
                "tcc_integration_probe",
                GodotVersion.V451,
                buildDir,
                COptimizationLevel.DEBUG,
                host,
                CCompilerKind.TINY_CC
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var builder = new CProjectBuilder(
                Map.of(
                        CCompilerKind.ZIG_CC, new ZigCcCompiler(),
                        CCompilerKind.TINY_CC, new TinyCcCompiler(() -> bundleRoot, TinyCcFfmNative::create)
                ),
                Map.of()
        );
        builder.setIgnoreSharedInclude(true);
        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), () -> "tcc pipeline build failed:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "the tcc build must produce the shared library artifact");
        return buildResult;
    }

    /// Copies the stable base of the shared `test_project` fixture into a private directory.
    private static void copyGodotFixture(@NotNull Path godotProjectDir) throws IOException {
        var fixtureRoot = Path.of("test_project").toAbsolutePath();
        for (var name : GODOT_FIXTURE_FILES) {
            var source = fixtureRoot.resolve(name);
            if (Files.isRegularFile(source)) {
                Files.copy(source, godotProjectDir.resolve(name));
            }
        }
        assertTrue(Files.isRegularFile(godotProjectDir.resolve("project.godot")), "project.godot must be copied");
        assertTrue(Files.isRegularFile(godotProjectDir.resolve("root.gd")), "root.gd must be copied");
    }

    /// The fixture keeps state transitions fully inside method calls (no `_ready` ordering
    /// dependency against the validation script): add(40) -> add(2) leaves `hits` at 42.
    private static @NotNull String fixtureSource() {
        return """
                class_name TccIntegrationProbe
                extends Node
                
                var hits: int = 0
                
                func add(value: int) -> int:
                    hits += value
                    return hits
                
                func greet() -> String:
                    return "tcc-" + str(hits)
                """;
    }

    /// Interpreted validation script (attached by the runner). The markers must stay in sync with
    /// [PASS_MARKER]/[FAIL_MARKER]; every failed check reports one FAIL line via push_error.
    private static @NotNull String validationScript() {
        return """
                extends Node
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null("TccProbeNode")
                    if target == null:
                        push_error("TCCSMOKE_FAIL: fixture node missing")
                        return
                    var sum = target.call("add", 40)
                    if sum != 40:
                        push_error("TCCSMOKE_FAIL: first add returned " + str(sum))
                        return
                    sum = target.call("add", 2)
                    if sum != 42:
                        push_error("TCCSMOKE_FAIL: second add returned " + str(sum))
                        return
                    var greeting = target.call("greet")
                    if greeting != "tcc-42":
                        push_error("TCCSMOKE_FAIL: greet returned " + str(greeting))
                        return
                    if target.get("hits") != 42:
                        push_error("TCCSMOKE_FAIL: property read returned " + str(target.get("hits")))
                        return
                    print("TCCSMOKE_PASS")
                """;
    }
}
