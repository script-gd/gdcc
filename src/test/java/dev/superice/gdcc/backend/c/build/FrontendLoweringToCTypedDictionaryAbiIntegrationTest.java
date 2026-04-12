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

public class FrontendLoweringToCTypedDictionaryAbiIntegrationTest {
    @Test
    void lowerFrontendTypedDictionaryMethodAbiAcceptsExactAndRejectsPlainDictionaryAtRuntime() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed dictionary method ABI integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_dictionary_method_abi_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_dictionary_method_abi_smoke.gd"),
                typedDictionaryMethodAbiSource(),
                Map.of("TypedDictionaryMethodAbiSmoke", "RuntimeTypedDictionaryMethodAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_dictionary_method_abi_runtime",
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
                "godot_bool typed_mismatch = false;",
                "godot_Dictionary_get_typed_key_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_STRING_NAME;",
                "godot_Dictionary_get_typed_value_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT;",
                "godot_Dictionary_get_typed_value_class_name(&probe0);",
                "godot_Dictionary_get_typed_value_script(&probe0);",
                "godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &probe0_value_script, &probe0_value_script_nil, &probe0_value_script_is_null_result, &probe0_value_script_is_null_valid);",
                "godot_new_bool_with_Variant(&probe0_value_script_is_null_result)",
                "expected = GDEXTENSION_VARIANT_TYPE_DICTIONARY;"
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedDictionaryMethodAbiSmokeNode",
                        "RuntimeTypedDictionaryMethodAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedDictionaryMethodPlainGuardTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary method ABI exact call check passed."),
                () -> "Exact typed-dictionary call should reach native body.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary method ABI plain before bad call."),
                () -> "The plain-dictionary guard probe should reach the failing call site.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed dictionary method ABI plain after bad call."),
                () -> "The plain-dictionary guard should stop control flow before the after-call marker.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                hasTypedDictionaryGuardFailureSignal(combinedOutput),
                () -> "Typed-dictionary mismatch should surface through a stable Godot type-guard failure category.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed dictionary method ABI exact call check failed."),
                () -> "The exact-typed positive path should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendTypedDictionaryMethodAbiRejectsWrongTypedDictionaryAtRuntime() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed dictionary method ABI mismatch integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_dictionary_method_abi_wrong_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_dictionary_method_abi_wrong_smoke.gd"),
                typedDictionaryMethodAbiSource(),
                Map.of("TypedDictionaryMethodAbiSmoke", "RuntimeTypedDictionaryMethodAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_dictionary_method_abi_wrong_runtime",
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
                "godot_bool typed_mismatch = false;",
                "godot_Dictionary_get_typed_key_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_STRING_NAME;",
                "godot_Dictionary_get_typed_value_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT;",
                "godot_Dictionary_get_typed_value_class_name(&probe0);",
                "godot_Dictionary_get_typed_value_script(&probe0);",
                "godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &probe0_value_script, &probe0_value_script_nil, &probe0_value_script_is_null_result, &probe0_value_script_is_null_valid);"
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedDictionaryMethodAbiSmokeNode",
                        "RuntimeTypedDictionaryMethodAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedDictionaryMethodWrongTypedGuardTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary method ABI class-name guard setup passed."),
                () -> "The setup call should confirm the positive path before the mismatch probe.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary method ABI wrong before bad call."),
                () -> "The wrong-typed guard probe should reach the failing call site.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed dictionary method ABI wrong after bad call."),
                () -> "The wrong-typed guard should stop control flow before the after-call marker.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                hasTypedDictionaryGuardFailureSignal(combinedOutput),
                () -> "Typed-dictionary mismatch should surface through a stable Godot type-guard failure category.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendTypedDictionaryScalarMethodAbiAcceptsExactAndRejectsPlainDictionaryAtRuntime() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed dictionary scalar method ABI integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_dictionary_scalar_method_abi_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_dictionary_scalar_method_abi_smoke.gd"),
                typedDictionaryScalarMethodAbiSource(),
                Map.of("TypedDictionaryScalarMethodAbiSmoke", "RuntimeTypedDictionaryScalarMethodAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_dictionary_scalar_method_abi_runtime",
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
                "godot_bool typed_mismatch = false;",
                "godot_Dictionary_get_typed_key_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_STRING_NAME;",
                "godot_Dictionary_get_typed_value_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_INT;"
        );
        assertFalse(entryHeader.contains("godot_Dictionary_get_typed_value_class_name(&probe0)"), entryHeader);
        assertFalse(entryHeader.contains("godot_Dictionary_get_typed_value_script(&probe0)"), entryHeader);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedDictionaryScalarMethodAbiSmokeNode",
                        "RuntimeTypedDictionaryScalarMethodAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedDictionaryScalarMethodAbiTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary scalar method ABI exact call check passed."),
                () -> "Exact scalar typed-dictionary call should reach native body.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary scalar method ABI plain before bad call."),
                () -> "The scalar plain-dictionary guard probe should reach the failing call site.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed dictionary scalar method ABI plain after bad call."),
                () -> "The scalar plain-dictionary guard should stop control flow before the after-call marker.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                hasTypedDictionaryGuardFailureSignal(combinedOutput),
                () -> "Scalar typed-dictionary mismatch should surface through a stable Godot type-guard failure category.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendTypedDictionaryScalarMethodAbiNoTouchRuntimeProbe() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed dictionary scalar no-touch probe");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_dictionary_scalar_method_no_touch_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_dictionary_scalar_method_no_touch_probe.gd"),
                typedDictionaryScalarMethodNoTouchSource(),
                Map.of("TypedDictionaryScalarMethodNoTouchProbe", "RuntimeTypedDictionaryScalarMethodNoTouchProbe")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_dictionary_scalar_method_no_touch_runtime",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedDictionaryScalarMethodNoTouchProbeNode",
                        "RuntimeTypedDictionaryScalarMethodNoTouchProbe",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedDictionaryScalarMethodNoTouchTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary scalar no-touch probe passed."),
                () -> "Typed-dictionary scalar no-touch probe should complete without crashing.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendTypedDictionaryReturnAbiBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed dictionary return ABI integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_dictionary_return_abi_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_dictionary_return_abi_smoke.gd"),
                typedDictionaryReturnAbiSource(),
                Map.of("TypedDictionaryReturnAbiSmoke", "RuntimeTypedDictionaryReturnAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_dictionary_return_abi_runtime",
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
                "GDExtensionPropertyInfo return_info = gdcc_make_property_full(GDEXTENSION_VARIANT_TYPE_DICTIONARY",
                "godot_PROPERTY_HINT_DICTIONARY_TYPE",
                "GD_STATIC_S(u8\"StringName;Node\")"
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedDictionaryReturnAbiSmokeNode",
                        "RuntimeTypedDictionaryReturnAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedDictionaryReturnAbiTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary return ABI check passed."),
                () -> "Typed-dictionary return metadata should survive the direct method surface.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary return ABI runtime class check passed."),
                () -> "Typed-dictionary return runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed dictionary return ABI check failed."),
                () -> "Typed-dictionary return ABI should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed dictionary return ABI runtime class check failed."),
                () -> "Typed-dictionary return runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendTypedDictionaryPropertyAbiBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed dictionary property ABI integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_dictionary_property_abi_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_dictionary_property_abi_smoke.gd"),
                typedDictionaryPropertyAbiSource(),
                Map.of("TypedDictionaryPropertyAbiSmoke", "RuntimeTypedDictionaryPropertyAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_dictionary_property_abi_runtime",
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
                "gdcc_bind_property_full(class_name, GD_STATIC_SN(u8\"payloads\"), GDEXTENSION_VARIANT_TYPE_DICTIONARY",
                "godot_PROPERTY_HINT_DICTIONARY_TYPE",
                "GD_STATIC_S(u8\"StringName;Node\")"
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedDictionaryPropertyAbiSmokeNode",
                        "RuntimeTypedDictionaryPropertyAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedDictionaryPropertyAbiTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary property ABI direct get/set check passed."),
                () -> "Typed-dictionary property direct get/set should preserve typedness.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary property ABI runtime class check passed."),
                () -> "Typed-dictionary property runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed dictionary property ABI direct get/set check failed."),
                () -> "Typed-dictionary property direct get/set should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed dictionary property ABI runtime class check failed."),
                () -> "Typed-dictionary property runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendTypedDictionaryPropertyAbiRejectsPlainDictionarySetAtRuntime() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed dictionary property plain-guard integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_dictionary_property_plain_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_dictionary_property_plain_smoke.gd"),
                typedDictionaryPropertyAbiSource(),
                Map.of("TypedDictionaryPropertyAbiSmoke", "RuntimeTypedDictionaryPropertyAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_dictionary_property_plain_runtime",
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
                "godot_bool typed_mismatch = false;",
                "godot_Dictionary_get_typed_key_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_STRING_NAME;",
                "godot_Dictionary_get_typed_value_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT;",
                "godot_Dictionary_get_typed_value_class_name(&probe0);",
                "godot_Dictionary_get_typed_value_script(&probe0);",
                "godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &probe0_value_script, &probe0_value_script_nil, &probe0_value_script_is_null_result, &probe0_value_script_is_null_valid);"
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedDictionaryPropertyAbiSmokeNode",
                        "RuntimeTypedDictionaryPropertyAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedDictionaryPropertyPlainGuardTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary property ABI plain guard setup passed."),
                () -> "The positive setup should confirm the direct property path before the mismatch probe.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary property ABI plain before bad set."),
                () -> "The plain-dictionary property guard probe should reach the failing set site.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed dictionary property ABI plain after bad set."),
                () -> "The plain-dictionary property guard should stop control flow before the after-set marker.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                hasTypedDictionaryGuardFailureSignal(combinedOutput),
                () -> "Typed-dictionary property mismatch should surface through a stable Godot type-guard failure category.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendTypedDictionaryPropertyAbiRejectsWrongTypedDictionarySetAtRuntime() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping typed dictionary property mismatch integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_typed_dictionary_property_wrong_runtime");
        Files.createDirectories(tempDir);

        var module = parseModule(
                tempDir.resolve("typed_dictionary_property_wrong_smoke.gd"),
                typedDictionaryPropertyAbiSource(),
                Map.of("TypedDictionaryPropertyAbiSmoke", "RuntimeTypedDictionaryPropertyAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_typed_dictionary_property_wrong_runtime",
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
                "godot_bool typed_mismatch = false;",
                "godot_Dictionary_get_typed_key_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_STRING_NAME;",
                "godot_Dictionary_get_typed_value_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT;",
                "godot_Dictionary_get_typed_value_class_name(&probe0);",
                "godot_Dictionary_get_typed_value_script(&probe0);",
                "godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &probe0_value_script, &probe0_value_script_nil, &probe0_value_script_is_null_result, &probe0_value_script_is_null_valid);"
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "TypedDictionaryPropertyAbiSmokeNode",
                        "RuntimeTypedDictionaryPropertyAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(typedDictionaryPropertyWrongTypedGuardTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary property ABI wrong guard setup passed."),
                () -> "The positive setup should confirm the direct property path before the class-name mismatch probe.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed dictionary property ABI wrong before bad set."),
                () -> "The wrong-typed property guard probe should reach the failing set site.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed dictionary property ABI wrong after bad set."),
                () -> "The wrong-typed property guard should stop control flow before the after-set marker.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                hasTypedDictionaryGuardFailureSignal(combinedOutput),
                () -> "Typed-dictionary property mismatch should surface through a stable Godot type-guard failure category.\nOutput:\n" + combinedOutput
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
        return new FrontendModule("frontend_typed_dictionary_abi_module", List.of(unit), topLevelCanonicalNameMap);
    }

    private static @NotNull String typedDictionaryMethodAbiSource() {
        return """
                class_name TypedDictionaryMethodAbiSmoke
                extends Node
                
                var accepted_calls: int
                
                func accept_payloads(payloads: Dictionary[StringName, Node]) -> int:
                    accepted_calls += 1
                    return accepted_calls * 10 + payloads.size()
                
                func read_accept_calls() -> int:
                    return accepted_calls
                """;
    }

    private static @NotNull String typedDictionaryReturnAbiSource() {
        return """
                class_name TypedDictionaryReturnAbiSmoke
                extends Node
                
                func echo_payloads(payloads: Dictionary[StringName, Node]) -> Dictionary[StringName, Node]:
                    return payloads
                """;
    }

    private static @NotNull String typedDictionaryScalarMethodAbiSource() {
        return """
                class_name TypedDictionaryScalarMethodAbiSmoke
                extends Node
                
                var accepted_calls: int
                
                func accept_payloads(payloads: Dictionary[StringName, int]) -> int:
                    accepted_calls += 1
                    return accepted_calls * 10 + payloads.size()
                
                func read_accept_calls() -> int:
                    return accepted_calls
                """;
    }

    private static @NotNull String typedDictionaryScalarMethodNoTouchSource() {
        return """
                class_name TypedDictionaryScalarMethodNoTouchProbe
                extends Node
                
                func accept_payloads(payloads: Dictionary[StringName, int]) -> int:
                    return 7
                """;
    }

    private static @NotNull String typedDictionaryPropertyAbiSource() {
        return """
                class_name TypedDictionaryPropertyAbiSmoke
                extends Node
                
                var payloads: Dictionary[StringName, Node]
                
                func read_payload_size() -> int:
                    return payloads.size()
                """;
    }

    private static @NotNull String typedDictionaryMethodPlainGuardTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedDictionaryMethodAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Dictionary[StringName, Node] = {
                        &"root": Node.new(),
                        &"leaf": null,
                    }
                    var accepted = int(target.call("accept_payloads", exact))
                    var accepted_calls = int(target.call("read_accept_calls"))
                    if accepted == 12 and accepted_calls == 1:
                        print("frontend typed dictionary method ABI exact call check passed.")
                    else:
                        push_error("frontend typed dictionary method ABI exact call check failed.")
                
                    print("frontend typed dictionary method ABI plain before bad call.")
                    target.call("accept_payloads", {
                        &"root": Node.new(),
                    })
                    print("frontend typed dictionary method ABI plain after bad call.")
                """;
    }

    private static @NotNull String typedDictionaryMethodWrongTypedGuardTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedDictionaryMethodAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Dictionary[StringName, Node] = {
                        &"root": Node.new(),
                    }
                    var accepted = int(target.call("accept_payloads", exact))
                    var accepted_calls = int(target.call("read_accept_calls"))
                    if accepted == 11 and accepted_calls == 1:
                        print("frontend typed dictionary method ABI class-name guard setup passed.")
                    else:
                        push_error("frontend typed dictionary method ABI class-name guard setup failed.")
                
                    print("frontend typed dictionary method ABI wrong before bad call.")
                    var wrong: Dictionary[StringName, RefCounted] = {
                        &"worker": RefCounted.new(),
                    }
                    target.call("accept_payloads", wrong)
                    print("frontend typed dictionary method ABI wrong after bad call.")
                """;
    }

    private static @NotNull String typedDictionaryReturnAbiTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedDictionaryReturnAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Dictionary[StringName, Node] = {
                        &"root": target,
                        &"leaf": null,
                    }
                    var payloads: Dictionary[StringName, Node] = target.echo_payloads(exact)
                    if payloads.get_typed_key_builtin() == TYPE_STRING_NAME and payloads.get_typed_value_builtin() == TYPE_OBJECT and payloads.get_typed_value_class_name() == &"Node" and payloads.get_typed_value_script() == null and payloads.size() == 2 and payloads[&"root"] == target and payloads[&"leaf"] == null:
                        print("frontend typed dictionary return ABI check passed.")
                    else:
                        push_error("frontend typed dictionary return ABI check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeTypedDictionaryReturnAbiSmoke" and target.is_class("RuntimeTypedDictionaryReturnAbiSmoke") and not target.is_class("TypedDictionaryReturnAbiSmoke"):
                        print("frontend typed dictionary return ABI runtime class check passed.")
                    else:
                        push_error("frontend typed dictionary return ABI runtime class check failed.")
                """;
    }

    private static @NotNull String typedDictionaryScalarMethodAbiTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedDictionaryScalarMethodAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Dictionary[StringName, int] = {
                        &"root": 1,
                        &"leaf": 2,
                    }
                    var accepted = int(target.call("accept_payloads", exact))
                    var accepted_calls = int(target.call("read_accept_calls"))
                    if accepted == 12 and accepted_calls == 1:
                        print("frontend typed dictionary scalar method ABI exact call check passed.")
                    else:
                        push_error("frontend typed dictionary scalar method ABI exact call check failed.")
                
                    print("frontend typed dictionary scalar method ABI plain before bad call.")
                    target.call("accept_payloads", {
                        &"root": 1,
                    })
                    print("frontend typed dictionary scalar method ABI plain after bad call.")
                """;
    }

    private static @NotNull String typedDictionaryScalarMethodNoTouchTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedDictionaryScalarMethodNoTouchProbeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Dictionary[StringName, int] = {
                        &"root": 1,
                        &"leaf": 2,
                    }
                    if int(target.call("accept_payloads", exact)) == 7:
                        print("frontend typed dictionary scalar no-touch probe passed.")
                    else:
                        push_error("frontend typed dictionary scalar no-touch probe failed.")
                """;
    }

    private static @NotNull String typedDictionaryPropertyAbiTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedDictionaryPropertyAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Dictionary[StringName, Node] = {
                        &"root": Node.new(),
                        &"leaf": null,
                    }
                    target.payloads = exact
                    var payloads: Dictionary[StringName, Node] = target.payloads
                    if payloads.get_typed_key_builtin() == TYPE_STRING_NAME and payloads.get_typed_value_builtin() == TYPE_OBJECT and payloads.get_typed_value_class_name() == &"Node" and payloads.get_typed_value_script() == null and payloads.size() == 2 and int(target.call("read_payload_size")) == 2:
                        print("frontend typed dictionary property ABI direct get/set check passed.")
                    else:
                        push_error("frontend typed dictionary property ABI direct get/set check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeTypedDictionaryPropertyAbiSmoke" and target.is_class("RuntimeTypedDictionaryPropertyAbiSmoke") and not target.is_class("TypedDictionaryPropertyAbiSmoke"):
                        print("frontend typed dictionary property ABI runtime class check passed.")
                    else:
                        push_error("frontend typed dictionary property ABI runtime class check failed.")
                """;
    }

    private static @NotNull String typedDictionaryPropertyPlainGuardTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedDictionaryPropertyAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Dictionary[StringName, Node] = {
                        &"root": Node.new(),
                    }
                    target.payloads = exact
                    if int(target.call("read_payload_size")) == 1:
                        print("frontend typed dictionary property ABI plain guard setup passed.")
                    else:
                        push_error("frontend typed dictionary property ABI plain guard setup failed.")
                
                    print("frontend typed dictionary property ABI plain before bad set.")
                    target.payloads = {
                        &"root": Node.new(),
                    }
                    print("frontend typed dictionary property ABI plain after bad set.")
                """;
    }

    private static @NotNull String typedDictionaryPropertyWrongTypedGuardTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "TypedDictionaryPropertyAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact: Dictionary[StringName, Node] = {
                        &"root": Node.new(),
                    }
                    target.payloads = exact
                    if int(target.call("read_payload_size")) == 1:
                        print("frontend typed dictionary property ABI wrong guard setup passed.")
                    else:
                        push_error("frontend typed dictionary property ABI wrong guard setup failed.")
                
                    print("frontend typed dictionary property ABI wrong before bad set.")
                    var wrong: Dictionary[StringName, RefCounted] = {
                        &"worker": RefCounted.new(),
                    }
                    target.payloads = wrong
                    print("frontend typed dictionary property ABI wrong after bad set.")
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

    private static boolean hasTypedDictionaryGuardFailureSignal(@NotNull String output) {
        return output.contains("Invalid type in function") ||
                output.contains("Invalid assignment of property or key");
    }

}
