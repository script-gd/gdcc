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

public class FrontendLoweringToCTypedArrayAbiIntegrationTest {
    @Test
    void lowerFrontendTypedArrayMethodAbiAcceptsExactAndRejectsPlainArrayAtRuntime() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed array method ABI integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_array_method_abi_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_array_method_abi_smoke.gd"),
                typedArrayMethodAbiSource(),
                Map.of("TypedArrayMethodAbiSmoke", "RuntimeTypedArrayMethodAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_array_method_abi_runtime",
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
        assertContainsAll(
                entryHeader,
                "godot_bool typed_mismatch = godot_Array_get_typed_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT;",
                "godot_Array_get_typed_class_name(&probe0);",
                "godot_Array_get_typed_script(&probe0);",
                "godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &probe0_script, &probe0_script_nil, &probe0_script_is_null_result, &probe0_script_is_null_valid);",
                "expected = GDEXTENSION_VARIANT_TYPE_ARRAY;"
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedArrayMethodAbiSmokeNode",
                        "RuntimeTypedArrayMethodAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedArrayMethodPlainGuardTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed array method ABI exact call check passed."),
                () -> "Exact typed-array call should reach native body.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed array method ABI plain before bad call."),
                () -> "The plain-array guard probe should reach the failing call site.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed array method ABI plain after bad call."),
                () -> "The plain-array guard should stop control flow before the after-call marker.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                hasTypedArrayGuardFailureSignal(combinedOutput),
                () -> "Typed-array mismatch should surface through a stable Godot type-guard failure category.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed array method ABI exact call check failed."),
                () -> "The exact typed-array positive path should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendTypedArrayMethodAbiRejectsWrongTypedArrayAtRuntime() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed array method ABI mismatch integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_array_method_wrong_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_array_method_wrong_smoke.gd"),
                typedArrayMethodAbiSource(),
                Map.of("TypedArrayMethodAbiSmoke", "RuntimeTypedArrayMethodAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_array_method_wrong_runtime",
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
        assertContainsAll(
                entryHeader,
                "godot_bool typed_mismatch = godot_Array_get_typed_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT;",
                "godot_Array_get_typed_class_name(&probe0);",
                "godot_Array_get_typed_script(&probe0);",
                "godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &probe0_script, &probe0_script_nil, &probe0_script_is_null_result, &probe0_script_is_null_valid);"
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedArrayMethodAbiSmokeNode",
                        "RuntimeTypedArrayMethodAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedArrayMethodWrongTypedGuardTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed array method ABI class-name guard setup passed."),
                () -> "The setup call should confirm the positive path before the mismatch probe.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed array method ABI wrong before bad call."),
                () -> "The wrong-typed guard probe should reach the failing call site.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed array method ABI wrong after bad call."),
                () -> "The wrong-typed guard should stop control flow before the after-call marker.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                hasTypedArrayGuardFailureSignal(combinedOutput),
                () -> "Typed-array mismatch should surface through a stable Godot type-guard failure category.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendTypedArrayReturnAbiBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed array return ABI integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_array_return_abi_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_array_return_abi_smoke.gd"),
                typedArrayReturnAbiSource(),
                Map.of("TypedArrayReturnAbiSmoke", "RuntimeTypedArrayReturnAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_array_return_abi_runtime",
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
        assertContainsAll(
                entryHeader,
                "GDExtensionPropertyInfo return_info = gdcc_make_property_full(GDEXTENSION_VARIANT_TYPE_ARRAY",
                "godot_PROPERTY_HINT_ARRAY_TYPE",
                "GD_STATIC_S(u8\"Node\")"
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedArrayReturnAbiSmokeNode",
                        "RuntimeTypedArrayReturnAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedArrayReturnAbiTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed array return ABI check passed."),
                () -> "Typed-array return metadata should survive the direct method surface.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed array return ABI runtime class check passed."),
                () -> "Typed-array return runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed array return ABI check failed."),
                () -> "Typed-array return ABI should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed array return ABI runtime class check failed."),
                () -> "Typed-array return runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendTypedArrayPropertyAbiBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed array property ABI integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_array_property_abi_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_array_property_abi_smoke.gd"),
                typedArrayPropertyAbiSource(),
                Map.of("TypedArrayPropertyAbiSmoke", "RuntimeTypedArrayPropertyAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_array_property_abi_runtime",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        var entrySource = Files.readString(projectDir.resolve("entry.c"));

        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        assertContainsAll(
                entrySource,
                "gdcc_bind_property_full(class_name, GD_STATIC_SN(u8\"payloads\"), GDEXTENSION_VARIANT_TYPE_ARRAY",
                "godot_PROPERTY_HINT_ARRAY_TYPE",
                "GD_STATIC_S(u8\"Node\")"
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedArrayPropertyAbiSmokeNode",
                        "RuntimeTypedArrayPropertyAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedArrayPropertyAbiTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed array property ABI direct get/set check passed."),
                () -> "Typed-array property direct get/set should preserve typedness.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed array property ABI runtime class check passed."),
                () -> "Typed-array property runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed array property ABI direct get/set check failed."),
                () -> "Typed-array property direct get/set should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed array property ABI runtime class check failed."),
                () -> "Typed-array property runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendTypedArrayPropertyAbiRejectsPlainArraySetAtRuntime() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed array property plain-guard integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_array_property_plain_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_array_property_plain_smoke.gd"),
                typedArrayPropertyAbiSource(),
                Map.of("TypedArrayPropertyAbiSmoke", "RuntimeTypedArrayPropertyAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_array_property_plain_runtime",
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
        assertContainsAll(
                entryHeader,
                "godot_bool typed_mismatch = godot_Array_get_typed_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT;",
                "godot_Array_get_typed_class_name(&probe0);",
                "godot_Array_get_typed_script(&probe0);",
                "godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &probe0_script, &probe0_script_nil, &probe0_script_is_null_result, &probe0_script_is_null_valid);"
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedArrayPropertyAbiSmokeNode",
                        "RuntimeTypedArrayPropertyAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedArrayPropertyPlainGuardTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed array property ABI plain guard setup passed."),
                () -> "The positive setup should confirm the direct property path before the mismatch probe.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed array property ABI plain before bad set."),
                () -> "The plain-array property guard probe should reach the failing set site.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed array property ABI plain after bad set."),
                () -> "The plain-array property guard should stop control flow before the after-set marker.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                hasTypedArrayGuardFailureSignal(combinedOutput),
                () -> "Typed-array property mismatch should surface through a stable Godot type-guard failure category.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendTypedArrayPropertyAbiRejectsWrongTypedArraySetAtRuntime() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed array property mismatch integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_array_property_wrong_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_array_property_wrong_smoke.gd"),
                typedArrayPropertyAbiSource(),
                Map.of("TypedArrayPropertyAbiSmoke", "RuntimeTypedArrayPropertyAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_array_property_wrong_runtime",
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
        assertContainsAll(
                entryHeader,
                "godot_bool typed_mismatch = godot_Array_get_typed_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT;",
                "godot_Array_get_typed_class_name(&probe0);",
                "godot_Array_get_typed_script(&probe0);",
                "godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &probe0_script, &probe0_script_nil, &probe0_script_is_null_result, &probe0_script_is_null_valid);"
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedArrayPropertyAbiSmokeNode",
                        "RuntimeTypedArrayPropertyAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedArrayPropertyWrongTypedGuardTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed array property ABI wrong guard setup passed."),
                () -> "The positive setup should confirm the direct property path before the class-name mismatch probe.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed array property ABI wrong before bad set."),
                () -> "The wrong-typed property guard probe should reach the failing set site.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed array property ABI wrong after bad set."),
                () -> "The wrong-typed property guard should stop control flow before the after-set marker.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                hasTypedArrayGuardFailureSignal(combinedOutput),
                () -> "Typed-array property mismatch should surface through a stable Godot type-guard failure category.\nOutput:\n" + combinedOutput
        );
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
        return new FrontendModule("frontend_typed_array_abi_module", List.of(unit), topLevelCanonicalNameMap);
    }

    private static @NotNull String typedArrayMethodAbiSource() {
        return """
                class_name TypedArrayMethodAbiSmoke
                extends Node
                
                var accepted_calls: int
                
                func accept_payloads(payloads: Array[Node]) -> int:
                    accepted_calls += 1
                    return accepted_calls * 10 + payloads.size()
                
                func read_accept_calls() -> int:
                    return accepted_calls
                """;
    }

    private static @NotNull String typedArrayMethodPlainGuardTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedArrayMethodAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Array[Node] = [Node.new(), null]
                    var accepted = int(target.call("accept_payloads", exact))
                    var accepted_calls = int(target.call("read_accept_calls"))
                    if accepted == 12 and accepted_calls == 1:
                        print("frontend typed array method ABI exact call check passed.")
                    else:
                        push_error("frontend typed array method ABI exact call check failed.")
                
                    print("frontend typed array method ABI plain before bad call.")
                    target.call("accept_payloads", [Node.new()])
                    print("frontend typed array method ABI plain after bad call.")
                """;
    }

    private static @NotNull String typedArrayMethodWrongTypedGuardTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedArrayMethodAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Array[Node] = [Node.new()]
                    var accepted = int(target.call("accept_payloads", exact))
                    var accepted_calls = int(target.call("read_accept_calls"))
                    if accepted == 11 and accepted_calls == 1:
                        print("frontend typed array method ABI class-name guard setup passed.")
                    else:
                        push_error("frontend typed array method ABI class-name guard setup failed.")
                
                    print("frontend typed array method ABI wrong before bad call.")
                    var wrong: Array[RefCounted] = [RefCounted.new()]
                    target.call("accept_payloads", wrong)
                    print("frontend typed array method ABI wrong after bad call.")
                """;
    }

    private static @NotNull String typedArrayReturnAbiSource() {
        return """
                class_name TypedArrayReturnAbiSmoke
                extends Node
                
                func echo_payloads(payloads: Array[Node]) -> Array[Node]:
                    return payloads
                """;
    }

    private static @NotNull String typedArrayPropertyAbiSource() {
        return """
                class_name TypedArrayPropertyAbiSmoke
                extends Node
                
                var payloads: Array[Node]
                
                func read_payload_size() -> int:
                    return payloads.size()
                """;
    }

    private static @NotNull String typedArrayReturnAbiTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedArrayReturnAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Array[Node] = [target, null]
                    var payloads: Array[Node] = target.echo_payloads(exact)
                    if payloads.get_typed_builtin() == TYPE_OBJECT and payloads.get_typed_class_name() == &"Node" and payloads.get_typed_script() == null and payloads.size() == 2 and payloads[0] == target and payloads[1] == null:
                        print("frontend typed array return ABI check passed.")
                    else:
                        push_error("frontend typed array return ABI check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeTypedArrayReturnAbiSmoke" and target.is_class("RuntimeTypedArrayReturnAbiSmoke") and not target.is_class("TypedArrayReturnAbiSmoke"):
                        print("frontend typed array return ABI runtime class check passed.")
                    else:
                        push_error("frontend typed array return ABI runtime class check failed.")
                """;
    }

    private static @NotNull String typedArrayPropertyAbiTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedArrayPropertyAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Array[Node] = [Node.new(), null]
                    target.payloads = exact
                    var payloads: Array[Node] = target.payloads
                    if payloads.get_typed_builtin() == TYPE_OBJECT and payloads.get_typed_class_name() == &"Node" and payloads.get_typed_script() == null and payloads.size() == 2 and payloads[1] == null and int(target.call("read_payload_size")) == 2:
                        print("frontend typed array property ABI direct get/set check passed.")
                    else:
                        push_error("frontend typed array property ABI direct get/set check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeTypedArrayPropertyAbiSmoke" and target.is_class("RuntimeTypedArrayPropertyAbiSmoke") and not target.is_class("TypedArrayPropertyAbiSmoke"):
                        print("frontend typed array property ABI runtime class check passed.")
                    else:
                        push_error("frontend typed array property ABI runtime class check failed.")
                """;
    }

    private static @NotNull String typedArrayPropertyPlainGuardTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedArrayPropertyAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Array[Node] = [Node.new()]
                    target.payloads = exact
                    if int(target.call("read_payload_size")) == 1:
                        print("frontend typed array property ABI plain guard setup passed.")
                    else:
                        push_error("frontend typed array property ABI plain guard setup failed.")
                
                    print("frontend typed array property ABI plain before bad set.")
                    target.payloads = [Node.new()]
                    print("frontend typed array property ABI plain after bad set.")
                """;
    }

    private static @NotNull String typedArrayPropertyWrongTypedGuardTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedArrayPropertyAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Array[Node] = [Node.new()]
                    target.payloads = exact
                    if int(target.call("read_payload_size")) == 1:
                        print("frontend typed array property ABI wrong guard setup passed.")
                    else:
                        push_error("frontend typed array property ABI wrong guard setup failed.")
                
                    print("frontend typed array property ABI wrong before bad set.")
                    var wrong: Array[RefCounted] = [RefCounted.new()]
                    target.payloads = wrong
                    print("frontend typed array property ABI wrong after bad set.")
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

    private static boolean hasTypedArrayGuardFailureSignal(@NotNull String output) {
        return output.contains("Invalid type in function") ||
                output.contains("Invalid assignment of property or key");
    }
}
