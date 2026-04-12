package dev.superice.gdcc.backend.c.build;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.gen.CCodegen;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPassManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FrontendLoweringToCTypedArrayAbiInvestigationTest {
    @Test
    void lowerFrontendTypedArrayOutwardMetadataStillCollapsesToPlainArray() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed array ABI metadata investigation");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_array_metadata_probe");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_array_metadata_probe.gd"),
                typedArrayMetadataProbeSource(),
                Map.of("TypedArrayMetadataProbe", "RuntimeTypedArrayMetadataProbe")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_array_metadata_probe",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        var entryHeader = Files.readString(projectDir.resolve("entry.h"));
        var entrySource = Files.readString(projectDir.resolve("entry.c"));

        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(entryHeader.contains("godot_PROPERTY_HINT_ARRAY_TYPE"), entryHeader);
        assertFalse(entrySource.contains("godot_PROPERTY_HINT_ARRAY_TYPE"), entrySource);
        assertFalse(entryHeader.contains("godot_Array_get_typed_builtin"), entryHeader);
        assertFalse(entryHeader.contains("godot_Array_get_typed_class_name"), entryHeader);
        assertFalse(entryHeader.contains("godot_Array_get_typed_script"), entryHeader);
    }

    @Test
    void lowerFrontendTypedArrayMethodAbiExactTypedArrayNoLongerCrashesBeforeMetadataFix() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed array method ABI investigation");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_array_method_abi_probe");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_array_method_abi_probe.gd"),
                typedArrayMethodAbiSource(),
                Map.of("TypedArrayMethodAbiProbe", "RuntimeTypedArrayMethodAbiProbe")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_array_method_abi_probe",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        var entryHeader = Files.readString(projectDir.resolve("entry.h"));

        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(entryHeader.contains("godot_PROPERTY_HINT_ARRAY_TYPE"), entryHeader);
        assertFalse(entryHeader.contains("godot_Array_get_typed_builtin"), entryHeader);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedArrayMethodAbiProbeNode",
                        "RuntimeTypedArrayMethodAbiProbe",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedArrayMethodExactProbeScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "The exact typed-array method probe should now reach the stop signal.\nOutput:\n" + combinedOutput
        );
        assertContainsAll(
                combinedOutput,
                "frontend typed array method ABI exact setup passed.",
                "frontend typed array method ABI exact before call.",
                "frontend typed array method ABI exact after call.",
                GodotGdextensionTestRunner.TEST_STOP_SIGNAL
        );
        assertFalse(combinedOutput.contains("CrashHandlerException"), combinedOutput);
        assertFalse(combinedOutput.contains("signal 11"), combinedOutput);
    }

    @Test
    void lowerFrontendTypedArrayPropertyAbiExactTypedArrayNoLongerCrashesDuringNodeConstruction() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed array property ABI investigation");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_array_property_abi_probe");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_array_property_abi_probe.gd"),
                typedArrayPropertyAbiSource(),
                Map.of("TypedArrayPropertyAbiProbe", "RuntimeTypedArrayPropertyAbiProbe")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_array_property_abi_probe",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        var entryHeader = Files.readString(projectDir.resolve("entry.h"));
        var entrySource = Files.readString(projectDir.resolve("entry.c"));

        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(entryHeader.contains("godot_PROPERTY_HINT_ARRAY_TYPE"), entryHeader);
        assertFalse(entrySource.contains("godot_PROPERTY_HINT_ARRAY_TYPE"), entrySource);
        assertFalse(entryHeader.contains("godot_Array_get_typed_builtin"), entryHeader);
        assertContainsAll(
                entrySource,
                "RuntimeTypedArrayPropertyAbiProbe__field_init_payloads",
                "godot_Variant __gdcc_tmp_array_script_",
                "godot_new_Variant_nil();"
        );
        assertFalse(
                entrySource.contains("godot_new_Array_with_Array_int_StringName_Variant(&__gdcc_tmp_array_1, (godot_int)GDEXTENSION_VARIANT_TYPE_STRING_NAME, GD_STATIC_SN(u8\\\"\\\"), NULL);"),
                entrySource
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedArrayPropertyAbiProbeNode",
                        "RuntimeTypedArrayPropertyAbiProbe",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedArrayPropertyExactProbeScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "The exact typed-array property probe should now reach the stop signal.\nOutput:\n" + combinedOutput
        );
        assertContainsAll(
                combinedOutput,
                "frontend typed array property ABI exact before set.",
                "frontend typed array property ABI exact after set.",
                GodotGdextensionTestRunner.TEST_STOP_SIGNAL
        );
        assertFalse(combinedOutput.contains("CrashHandlerException"), combinedOutput);
        assertFalse(combinedOutput.contains("signal 11"), combinedOutput);
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull Path sourcePath,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var parser = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parser.parseUnit(sourcePath, source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule("frontend_typed_array_abi_probe_module", List.of(unit), topLevelCanonicalNameMap);
    }

    private static @NotNull String typedArrayMetadataProbeSource() {
        return """
                class_name TypedArrayMetadataProbe
                extends Node
                
                var payloads: Array[StringName]
                
                func accept_payloads(values: Array[StringName]) -> int:
                    return values.size()
                
                func echo_payloads(values: Array[StringName]) -> Array[StringName]:
                    return values
                """;
    }

    private static @NotNull String typedArrayMethodAbiSource() {
        return """
                class_name TypedArrayMethodAbiProbe
                extends Node
                
                var accepted_calls: int
                
                func accept_payloads(payloads: Array[StringName]) -> int:
                    accepted_calls += 1
                    return accepted_calls * 10 + payloads.size()
                
                func read_accept_calls() -> int:
                    return accepted_calls
                """;
    }

    private static @NotNull String typedArrayPropertyAbiSource() {
        return """
                class_name TypedArrayPropertyAbiProbe
                extends Node
                
                var payloads: Array[StringName]
                
                func read_payload_size() -> int:
                    return payloads.size()
                """;
    }

    private static @NotNull String typedArrayMethodExactProbeScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedArrayMethodAbiProbeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Array[StringName] = [&"root", &"leaf"]
                    if exact.get_typed_builtin() == TYPE_STRING_NAME:
                        print("frontend typed array method ABI exact setup passed.")
                    else:
                        push_error("frontend typed array method ABI exact setup failed.")
                
                    print("frontend typed array method ABI exact before call.")
                    target.call("accept_payloads", exact)
                    print("frontend typed array method ABI exact after call.")
                    print("Test stop.")
                """;
    }

    private static @NotNull String typedArrayPropertyExactProbeScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedArrayPropertyAbiProbeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Array[StringName] = [&"root"]
                    if exact.get_typed_builtin() == TYPE_STRING_NAME:
                        print("frontend typed array property ABI exact setup passed.")
                    else:
                        push_error("frontend typed array property ABI exact setup failed.")
                
                    print("frontend typed array property ABI exact before set.")
                    target.payloads = exact
                    print("frontend typed array property ABI exact after set.")
                    print("Test stop.")
                """;
    }

    private static void assertContainsAll(@NotNull String text, @NotNull String... needles) {
        for (var needle : needles) {
            assertTrue(
                    text.contains(needle),
                    () -> "Missing fragment `" + needle + "` in:\n" + text
            );
        }
    }
}
