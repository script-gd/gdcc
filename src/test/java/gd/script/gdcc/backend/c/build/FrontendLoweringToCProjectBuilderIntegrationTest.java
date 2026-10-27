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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FrontendLoweringToCProjectBuilderIntegrationTest {
    @Test
    void lowerFrontendModuleBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping frontend-to-native Godot integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_lowering_build_smoke");
        Files.createDirectories(tempDir);

        var source = """
                class_name FrontendBuildSmoke
                extends Node
                
                func ping() -> int:
                    var obj: Object = null;
                    return 1
                """;
        var module = parseModule(
                tempDir.resolve("frontend_build_smoke.gd"),
                source,
                Map.of("FrontendBuildSmoke", "RuntimeFrontendBuildSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        assertEquals("RuntimeFrontendBuildSmoke", lowered.getClassDefs().getFirst().getName());
        assertEquals(1, lowered.getClassDefs().getFirst().getFunctions().size());
        assertEquals("ping", lowered.getClassDefs().getFirst().getFunctions().getFirst().getName());
        assertFalse(lowered.getClassDefs().getFirst().getFunctions().getFirst().getEntryBlockId().isBlank());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_build_smoke",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        var entrySource = Files.readString(projectDir.resolve("entry.c"));
        var entryHeader = Files.readString(projectDir.resolve("entry.h"));
        var expectedArtifactName = expectedArtifactFileName(projectInfo);

        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        assertTrue(Files.exists(projectDir.resolve("entry.c")));
        assertTrue(Files.exists(projectDir.resolve("engine_method_binds.h")));
        assertTrue(Files.exists(projectDir.resolve("entry.h")));
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeFrontendBuildSmoke\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"FrontendBuildSmoke\")"), entrySource);
        assertTrue(entryHeader.contains("#include \"engine_method_binds.h\""), entryHeader);
        assertTrue(
                buildResult.artifacts().stream()
                        .anyMatch(artifact -> artifact.getFileName().toString().equals(expectedArtifactName)),
                () -> "Expected native library artifact '" + expectedArtifactName + "', got " + buildResult.artifacts()
        );
        assertTrue(buildResult.artifacts().stream().allMatch(Files::exists));

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "FrontendBuildSmokeNode",
                        lowered.getClassDefs().getFirst().getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(testScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend lowering runtime ping check passed."),
                () -> "Godot output should confirm ping result.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend lowering runtime class remap check passed."),
                () -> "Godot output should confirm mapped runtime class name.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend lowering runtime ping check failed."),
                () -> "Ping check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend lowering runtime class remap check failed."),
                () -> "Mapped runtime class-name check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendParameterDefaultsBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping parameter-default integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_parameter_default");
        Files.createDirectories(tempDir);

        // The gdcc module itself exercises the exact/static/bare caller routes with omitted
        // trailing arguments; the Godot-side script only invokes full-argument probe methods
        // (dynamic-route omission lands in the callee-prologue wrapper).
        var source = """
                class_name ParamDefaultSmoke
                extends Node
                
                var marker: int = 5
                
                func ping(a: int, count: int = 40) -> int:
                    return a + count
                
                static func sping(a: int, count: int = 7) -> int:
                    return a + count
                
                func multi(a: int, b: int = 1, c: int = 2) -> int:
                    return a * 100 + b * 10 + c
                
                func marker_default(value: int = marker) -> int:
                    return value
                
                func append_probe(items: Array[int] = [0]) -> int:
                    items.append(1)
                    return items.size()
                
                func run_exact_checks() -> int:
                    var sum = 0
                    sum += ping(1)
                    sum += ping(1, 2)
                    sum += sping(1)
                    sum += sping(1, 1)
                    sum += multi(1)
                    sum += multi(1, 5)
                    sum += marker_default()
                    return sum
                
                func run_reeval_checks() -> int:
                    var first = append_probe()
                    var second = append_probe()
                    return first * 10 + second
                
                # gdcc-internal dynamic route: a Variant receiver forces the VARIANT_DYNAMIC
                # lowering path, which reaches the same callee-prologue wrapper through the engine.
                func run_dynamic_checks() -> int:
                    var v: Variant = self
                    return int(v.ping(1)) * 1000 + int(v.ping(1, 2))
                """;
        var module = parseModule(
                tempDir.resolve("param_default_smoke.gd"),
                source,
                Map.of("ParamDefaultSmoke", "RuntimeParamDefaultSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        var className = lowered.getClassDefs().getFirst().getName();
        assertEquals("RuntimeParamDefaultSmoke", className);

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_parameter_default",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        var entrySource = Files.readString(projectDir.resolve("entry.c"));
        var entryHeader = Files.readString(projectDir.resolve("entry.h"));

        // The raw `$` file-scope symbols must compile and link under zig cc; definition
        // (entry.c), prototype (entry.h) and call sites share one spelling.
        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        assertTrue(entrySource.contains("RuntimeParamDefaultSmoke__default_ping$count("), entrySource);
        assertTrue(entrySource.contains("RuntimeParamDefaultSmoke__default_s_sping$count("), entrySource);
        assertTrue(entryHeader.contains("RuntimeParamDefaultSmoke__default_ping$count("), entryHeader);

        // The bind registration channel stays empty — no bind-time default Variants and
        // no default_N_value formals anywhere in the generated registration surface.
        assertFalse(entryHeader.contains("default_argument_count"), entryHeader);
        assertFalse(entryHeader.contains("default_0_value"), entryHeader);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "ParamDefaultSmokeNode",
                        className,
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(parameterDefaultTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("parameter default exact-route check passed."),
                () -> "Exact/static/bare default completion should be correct.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("parameter default re-evaluation check passed."),
                () -> "Mutable defaults must be re-evaluated per call.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("parameter default exact-route check failed."),
                () -> "Exact-route check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("parameter default re-evaluation check failed."),
                () -> "Re-evaluation check should not fail.\nOutput:\n" + combinedOutput
        );

        // Dynamic route (Object.call) runtime completion through the argc-aware wrapper.
        assertTrue(
                combinedOutput.contains("parameter default dynamic-route check passed."),
                () -> "Dynamic-route default fill should be correct.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("parameter default dynamic multi-slot check passed."),
                () -> "Dynamic-route multi-slot fill should follow declaration order.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("parameter default dynamic re-evaluation check passed."),
                () -> "Dynamic-route defaults must be re-evaluated per call.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("parameter default gdcc dynamic-route check passed."),
                () -> "gdcc-internal VARIANT_DYNAMIC route should reach the wrapper fill.\nOutput:\n" + combinedOutput
        );
        // Too-few below the required prefix: the wrapper rejects with expected = required_count
        // (1), and Godot surfaces that exact expectation. The failing `call` aborts the script
        // function, so the probe marker right before it anchors that the error is ours.
        assertTrue(
                combinedOutput.contains("parameter default dynamic too-few probe reached."),
                () -> "Too-few probe should be reached.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("Expected 1 argument(s)."),
                () -> "TOO_FEW should report expected = required_count (1).\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("parameter default dynamic-route check failed."),
                () -> "Dynamic-route check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerVectorIToVectorBoundariesBuildConstructorIntrinsicAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping Vector*i-to-Vector integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_vectori_to_vector_boundary_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name VectorIToVectorBoundarySmoke
                extends Node
                
                var assigned: Vector3 = Vector3(0.0, 0.0, 0.0)
                
                func take_vector(value: Vector3) -> float:
                    return value.x + value.y + value.z
                
                func return_vector(value: Vector3i) -> Vector3:
                    return value
                
                func local_sum() -> float:
                    var value: Vector3 = Vector3i(1, 2, 3)
                    return value.x + value.y + value.z
                
                func assignment_sum(value: Vector3i) -> float:
                    assigned = value
                    return assigned.x + assigned.y + assigned.z
                
                func fixed_arg_sum(value: Vector3i) -> float:
                    return take_vector(value)
                """;
        var module = parseModule(
                tempDir.resolve("vectori_to_vector_boundary_smoke.gd"),
                source,
                Map.of("VectorIToVectorBoundarySmoke", "RuntimeVectorIToVectorBoundarySmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_vectori_to_vector_boundary_runtime",
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
        assertTrue(
                entrySource.contains("godot_new_Vector3_with_Vector3i"),
                () -> "Vector3i-to-Vector3 ordinary boundary should use the generated builtin constructor.\nSource:\n" + entrySource
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "VectorIToVectorBoundaryNode",
                        "RuntimeVectorIToVectorBoundarySmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(vectorIToVectorBoundaryTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend Vector*i-to-Vector source boundary check passed."),
                () -> "Godot output should confirm ordinary source-boundary results.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend Vector*i-to-Vector source boundary check failed."),
                () -> "Ordinary source-boundary runtime check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerStringFamilyInboundCallWrapperBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping String/StringName inbound wrapper integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_string_family_inbound_wrapper_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name StringFamilyInboundWrapperSmoke
                extends Node
                
                func take_string_name(value: StringName) -> StringName:
                    return value
                
                func take_string(value: String) -> String:
                    return value
                """;
        var module = parseModule(
                tempDir.resolve("string_family_inbound_wrapper_smoke.gd"),
                source,
                Map.of("StringFamilyInboundWrapperSmoke", "RuntimeStringFamilyInboundWrapperSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_string_family_inbound_wrapper_runtime",
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
        assertTrue(
                entryHeader.contains("gdcc_new_StringName_from_call_arg_variant"),
                () -> "StringName call wrapper should use the wrapper-local materializer.\nHeader:\n" + entryHeader
        );
        assertTrue(
                entryHeader.contains("gdcc_new_String_from_call_arg_variant"),
                () -> "String call wrapper should use the wrapper-local materializer.\nHeader:\n" + entryHeader
        );
        assertTrue(
                entryHeader.contains("arg0_type == GDEXTENSION_VARIANT_TYPE_STRING_NAME || arg0_type == GDEXTENSION_VARIANT_TYPE_STRING"),
                () -> "StringName call wrapper gate should accept exact and String payloads.\nHeader:\n" + entryHeader
        );
        assertTrue(
                entryHeader.contains("arg0_type == GDEXTENSION_VARIANT_TYPE_STRING || arg0_type == GDEXTENSION_VARIANT_TYPE_STRING_NAME"),
                () -> "String call wrapper gate should accept exact and StringName payloads.\nHeader:\n" + entryHeader
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "StringFamilyInboundWrapperNode",
                        "RuntimeStringFamilyInboundWrapperSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(stringFamilyInboundCallWrapperTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend String/StringName inbound dynamic call check passed."),
                () -> "Godot output should confirm String/StringName inbound wrapper compatibility.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("Cannot convert argument 2 from NodePath to StringName."),
                () -> "Godot output should confirm adjacent string-family payloads still fail the StringName gate.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend String/StringName inbound dynamic call check failed."),
                () -> "String/StringName inbound wrapper compatibility check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend String/StringName inbound dynamic call after bad call."),
                () -> "Runtime script should not continue past the intentionally invalid boundary call.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendBuiltinChainedPropertyWritebackBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping builtin chained writeback integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_builtin_chained_writeback_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name BuiltinChainWritebackSmoke
                extends Node2D
                
                func assign_position_x(value: float) -> float:
                    position.x = value
                    return position.x
                
                func bump_position_x(delta: float) -> float:
                    position.x += delta
                    return position.x
                """;
        var module = parseModule(
                tempDir.resolve("builtin_chain_writeback_smoke.gd"),
                source,
                Map.of("BuiltinChainWritebackSmoke", "RuntimeBuiltinChainWritebackSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        assertEquals("RuntimeBuiltinChainWritebackSmoke", lowered.getClassDefs().getFirst().getName());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_builtin_chained_writeback_runtime",
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
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeBuiltinChainWritebackSmoke\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"BuiltinChainWritebackSmoke\")"), entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "BuiltinChainWritebackNode",
                        "RuntimeBuiltinChainWritebackSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(builtinChainedWritebackTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend builtin chained writeback assignment check passed."),
                () -> "Builtin chained assignment runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend builtin chained writeback compound check passed."),
                () -> "Builtin chained compound runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend builtin chained writeback runtime class check passed."),
                () -> "Builtin chained runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend builtin chained writeback assignment check failed."),
                () -> "Builtin chained assignment runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend builtin chained writeback compound check failed."),
                () -> "Builtin chained compound runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend builtin chained writeback runtime class check failed."),
                () -> "Builtin chained runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendPropertyInitializerModuleBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping frontend property initializer integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_property_init_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name PropertyInitSmoke
                extends Node
                
                var ready_value: int = (1 + 2) * (3 + 4)
                var ready_angle: float = deg_to_rad(180.0)
                var ready_flag: bool = true
                var ready_node: Node = Node.new()
                var ready_obj: Object = Node.new()
                var ready_ref: RefCounted = RefCounted.new()
                var ready_worker: Worker = Worker.new()
                
                func read_value() -> int:
                    return ready_value
                
                func read_angle() -> float:
                    return ready_angle
                
                func read_flag() -> bool:
                    return ready_flag
                
                func read_node_class() -> String:
                    return ready_node.get_class()
                
                func read_object_class() -> String:
                    return ready_obj.get_class()
                
                func read_ref_count() -> int:
                    return ready_ref.get_reference_count()
                
                func read_worker_value() -> int:
                    return ready_worker.read()
                
                func read_worker_ref_count() -> int:
                    return ready_worker.get_reference_count()
                
                func read_worker_runtime_class() -> String:
                    return ready_worker.get_class()
                """;
        var workerSource = """
                class_name Worker
                extends RefCounted
                
                func read() -> int:
                    return 7
                """;
        var module = parseModule(
                "frontend_property_init_runtime",
                List.of(
                        new SourceFileSpec(tempDir.resolve("property_init_smoke.gd"), source),
                        new SourceFileSpec(tempDir.resolve("worker.gd"), workerSource)
                ),
                Map.of(
                        "PropertyInitSmoke", "RuntimePropertyInitSmoke",
                        "Worker", "RuntimePropertyInitWorker"
                )
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(2, lowered.getClassDefs().size());

        var loweredClass = lowered.getClassDefs().stream()
                .filter(classDef -> classDef.getName().equals("RuntimePropertyInitSmoke"))
                .findFirst()
                .orElseThrow();
        var loweredWorkerClass = lowered.getClassDefs().stream()
                .filter(classDef -> classDef.getName().equals("RuntimePropertyInitWorker"))
                .findFirst()
                .orElseThrow();
        assertEquals("RuntimePropertyInitSmoke", loweredClass.getName());
        assertEquals("RuntimePropertyInitWorker", loweredWorkerClass.getName());
        assertEquals(7, loweredClass.getProperties().size());
        assertTrue(
                loweredClass.getProperties().stream()
                        .allMatch(property -> property.getInitFunc() != null && !property.getInitFunc().isBlank())
        );
        for (var property : loweredClass.getProperties()) {
            var initFunc = loweredClass.getFunctions().stream()
                    .filter(function -> function.getName().equals(property.getInitFunc()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(initFunc.isHidden());
            assertFalse(initFunc.getEntryBlockId().isBlank());
            assertTrue(initFunc.getBasicBlockCount() > 0);
        }

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_property_init_runtime",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        var entrySource = Files.readString(projectDir.resolve("entry.c"));
        var expectedArtifactName = expectedArtifactFileName(projectInfo);

        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        assertTrue(Files.exists(projectDir.resolve("entry.c")));
        assertTrue(Files.exists(projectDir.resolve("entry.h")));
        assertTrue(
                entrySource.contains("self->ready_value = RuntimePropertyInitSmoke__field_init_ready_value(gdcc_RuntimePropertyInitSmoke_fat_ptr_from_raw(RuntimePropertyInitSmoke_object_ptr(self)));"),
                entrySource
        );
        assertTrue(
                entrySource.contains("self->ready_angle = RuntimePropertyInitSmoke__field_init_ready_angle(gdcc_RuntimePropertyInitSmoke_fat_ptr_from_raw(RuntimePropertyInitSmoke_object_ptr(self)));"),
                entrySource
        );
        assertTrue(
                entrySource.contains("self->ready_flag = RuntimePropertyInitSmoke__field_init_ready_flag(gdcc_RuntimePropertyInitSmoke_fat_ptr_from_raw(RuntimePropertyInitSmoke_object_ptr(self)));"),
                entrySource
        );
        assertTrue(
                entrySource.contains("self->ready_node = RuntimePropertyInitSmoke__field_init_ready_node(gdcc_RuntimePropertyInitSmoke_fat_ptr_from_raw(RuntimePropertyInitSmoke_object_ptr(self)));"),
                entrySource
        );
        assertTrue(
                entrySource.contains("self->ready_obj = RuntimePropertyInitSmoke__field_init_ready_obj(gdcc_RuntimePropertyInitSmoke_fat_ptr_from_raw(RuntimePropertyInitSmoke_object_ptr(self)));"),
                entrySource
        );
        assertTrue(
                entrySource.contains("self->ready_ref = RuntimePropertyInitSmoke__field_init_ready_ref(gdcc_RuntimePropertyInitSmoke_fat_ptr_from_raw(RuntimePropertyInitSmoke_object_ptr(self)));"),
                entrySource
        );
        assertTrue(
                entrySource.contains("self->ready_worker = RuntimePropertyInitSmoke__field_init_ready_worker(gdcc_RuntimePropertyInitSmoke_fat_ptr_from_raw(RuntimePropertyInitSmoke_object_ptr(self)));"),
                entrySource
        );
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"_field_init_ready_value\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"_field_init_ready_angle\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"_field_init_ready_flag\")"), entrySource);
        assertTrue(
                buildResult.artifacts().stream()
                        .anyMatch(artifact -> artifact.getFileName().toString().equals(expectedArtifactName)),
                () -> "Expected native library artifact '" + expectedArtifactName + "', got " + buildResult.artifacts()
        );
        assertTrue(buildResult.artifacts().stream().allMatch(Files::exists));

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "PropertyInitSmokeNode",
                        loweredClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(propertyInitTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend property init runtime check passed."),
                () -> "Godot output should confirm property initializer values.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend property init object runtime check passed."),
                () -> "Godot output should confirm object-valued property initializer values.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend property init runtime class check passed."),
                () -> "Godot output should confirm mapped runtime class name.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend property init runtime check failed."),
                () -> "Property initializer runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend property init runtime class check failed."),
                () -> "Mapped runtime class-name check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend property init object runtime check failed."),
                () -> "Object-valued property initializer runtime check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendConstructorRoutesBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping frontend constructor integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_constructor_runtime");
        Files.createDirectories(tempDir);

        var constructorSource = """
                class_name ConstructorSmoke
                extends Node
                
                func make_vector() -> Vector3i:
                    return Vector3i(1, 2, 3)
                
                func make_node_class_name() -> String:
                    return Node.new().get_class()
                
                func make_ref_counted() -> RefCounted:
                    return RefCounted.new()
                
                func measure_ref_counted_reference_count() -> int:
                    return RefCounted.new().get_reference_count()
                
                func make_worker_value() -> int:
                    return Worker.new().read()
                
                func make_worker_runtime_class_name() -> String:
                    return Worker.new().get_class()
                
                func forward_worker_value(worker: Worker) -> int:
                    return worker.read()
                
                func make_worker_value_via_source_name_parameter() -> int:
                    var worker: Worker = Worker.new()
                    return forward_worker_value(worker)
                
                func measure_worker_reference_count() -> int:
                    return Worker.new().get_reference_count()
                
                func make_plain_worker_value() -> int:
                    return PlainWorker.new().id()
                """;
        var workerSource = """
                class_name Worker
                extends RefCounted
                
                var seed: int
                
                func _init() -> void:
                    seed = 7
                
                func read() -> int:
                    return seed
                """;
        var plainWorkerSource = """
                class_name PlainWorker
                extends Object
                
                func id() -> int:
                    return 9
                """;
        var module = parseModule(
                "frontend_constructor_runtime_module",
                List.of(
                        new SourceFileSpec(tempDir.resolve("constructor_smoke.gd"), constructorSource),
                        new SourceFileSpec(tempDir.resolve("worker.gd"), workerSource),
                        new SourceFileSpec(tempDir.resolve("plain_worker.gd"), plainWorkerSource)
                ),
                Map.of(
                        "ConstructorSmoke", "RuntimeConstructorSmoke",
                        "Worker", "RuntimeConstructorWorker",
                        "PlainWorker", "RuntimePlainWorker"
                )
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(3, lowered.getClassDefs().size());
        assertTrue(
                lowered.getClassDefs().stream().anyMatch(classDef -> "RuntimeConstructorSmoke".equals(classDef.getName()))
        );
        assertTrue(
                lowered.getClassDefs().stream().anyMatch(classDef -> "RuntimeConstructorWorker".equals(classDef.getName()))
        );
        assertTrue(
                lowered.getClassDefs().stream().anyMatch(classDef -> "RuntimePlainWorker".equals(classDef.getName()))
        );

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_constructor_runtime",
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
        assertTrue(entrySource.contains("godot_new_Vector3i_with_int_int_int"), entrySource);
        assertTrue(entrySource.contains("godot_new_Node()"), entrySource);
        assertTrue(entrySource.contains("godot_new_RefCounted()"), entrySource);
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeConstructorWorker\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"Worker\")"), entrySource);
        assertTrue(
                entrySource.contains("gdcc_ref_counted_init_raw(RuntimeConstructorWorker_class_create_instance(NULL, false), true)"),
                entrySource
        );
        assertTrue(entrySource.contains("RuntimePlainWorker_class_create_instance(NULL, true)"), entrySource);
        assertTrue(entrySource.contains("RuntimeConstructorWorker__init(gdcc_RuntimeConstructorWorker_fat_ptr_from_raw(RuntimeConstructorWorker_object_ptr(self)));"), entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "FrontendConstructorNode",
                        "RuntimeConstructorSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(constructorTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend constructor builtin check passed."),
                () -> "Builtin constructor check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend constructor engine object check passed."),
                () -> "Engine constructor check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend constructor engine refcounted lifecycle check passed."),
                () -> "Engine RefCounted runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend constructor gdcc refcounted lifecycle check passed."),
                () -> "GDCC RefCounted runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend constructor mapped cross-file worker route check passed."),
                () -> "Mapped cross-file worker route check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend constructor gdcc plain object lifecycle check passed."),
                () -> "GDCC plain object runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("check failed."),
                () -> "Constructor integration output should not include failure markers.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendBuiltinPropertyAbiModuleBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping frontend builtin property ABI integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_builtin_property_abi_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name BuiltinPropertyAbiProbe
                extends Node
                
                func color_r(color: Color) -> float:
                    return color.r
                
                func vector_x(vector: Vector3) -> float:
                    return vector.x
                
                func constructed_color_r() -> float:
                    return Color(0.25, 0.5, 0.75, 1.0).r
                
                func constructed_vector_y() -> float:
                    return Vector3(1.0, 2.5, 3.0).y
                
                func local_color_g() -> float:
                    var color: Color = Color(0.1, 0.4, 0.7, 1.0)
                    return color.g
                
                func local_vector_z() -> float:
                    var vector: Vector3 = Vector3(7.0, 8.0, 9.0)
                    return vector.z
                """;
        var module = parseModule(
                tempDir.resolve("builtin_property_abi_probe.gd"),
                source,
                Map.of("BuiltinPropertyAbiProbe", "RuntimeBuiltinPropertyAbiProbe")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        assertEquals("RuntimeBuiltinPropertyAbiProbe", lowered.getClassDefs().getFirst().getName());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_builtin_property_abi_runtime",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        var entrySource = Files.readString(projectDir.resolve("entry.c"));
        var entryHeader = Files.readString(projectDir.resolve("entry.h"));

        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        assertTrue(
                Pattern.compile(
                                "RuntimeBuiltinPropertyAbiProbe_color_r\\s*\\(\\s*gdcc_RuntimeBuiltinPropertyAbiProbe_fat_ptr \\$self\\s*,\\s*godot_Color\\* \\$color\\s*\\)",
                                Pattern.DOTALL
                        )
                        .matcher(entryHeader)
                        .find(),
                () -> "Color parameter getter should use pointer ABI in generated signature.\nHeader:\n" + entryHeader
        );
        assertTrue(
                Pattern.compile(
                                "RuntimeBuiltinPropertyAbiProbe_vector_x\\s*\\(\\s*gdcc_RuntimeBuiltinPropertyAbiProbe_fat_ptr \\$self\\s*,\\s*godot_Vector3\\* \\$vector\\s*\\)",
                                Pattern.DOTALL
                        )
                        .matcher(entryHeader)
                        .find(),
                () -> "Vector3 parameter getter should use pointer ABI in generated signature.\nHeader:\n" + entryHeader
        );
        assertTrue(entrySource.contains("godot_new_Color_with_Color($color)"), () -> "Color parameter should be materialized from the pointer ABI into a value slot before property load.\nSource:\n" + entrySource);
        assertTrue(entrySource.contains("godot_new_Vector3_with_Vector3($vector)"), () -> "Vector3 parameter should be materialized from the pointer ABI into a value slot before property load.\nSource:\n" + entrySource);
        assertTrue(entrySource.contains("godot_Color_get_r(&"), () -> "Constructed/local Color receivers should pass an address.\nSource:\n" + entrySource);
        assertTrue(entrySource.contains("godot_Color_get_g(&"), () -> "Local Color receiver should pass an address.\nSource:\n" + entrySource);
        assertTrue(entrySource.contains("godot_Vector3_get_y(&"), () -> "Constructed Vector3 receiver should pass an address.\nSource:\n" + entrySource);
        assertTrue(entrySource.contains("godot_Vector3_get_z(&"), () -> "Local Vector3 receiver should pass an address.\nSource:\n" + entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "BuiltinPropertyAbiProbeNode",
                        "RuntimeBuiltinPropertyAbiProbe",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(builtinPropertyAbiTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend builtin property abi parameter check passed."),
                () -> "Builtin parameter property read should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend builtin property abi temporary check passed."),
                () -> "Builtin temporary/local property read should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend builtin property abi runtime class check passed."),
                () -> "Builtin property ABI runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend builtin property abi parameter check failed."),
                () -> "Builtin parameter property read should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend builtin property abi temporary check failed."),
                () -> "Builtin temporary/local property read should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend builtin property abi runtime class check failed."),
                () -> "Builtin property ABI runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendCompoundAssignmentRoutesBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping frontend compound-assignment integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_compound_assignment_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name CompoundAssignmentSmoke
                extends Node
                
                var hp: int
                
                func _init() -> void:
                    hp = 10
                
                func run_compound_flow() -> int:
                    var values: Variant = Vector3i(1, 2, 3)
                    var total := 0
                    var index := 0
                    while index < 3:
                        total += values[index]
                        index += 1
                    hp += total
                    return hp * 100 + total
                """;
        var module = parseModule(
                tempDir.resolve("compound_assignment_smoke.gd"),
                source,
                Map.of("CompoundAssignmentSmoke", "RuntimeCompoundAssignmentSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        assertEquals("RuntimeCompoundAssignmentSmoke", lowered.getClassDefs().getFirst().getName());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_compound_assignment_runtime",
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
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeCompoundAssignmentSmoke\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"CompoundAssignmentSmoke\")"), entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "CompoundAssignmentSmokeNode",
                        "RuntimeCompoundAssignmentSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(compoundAssignmentTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend compound assignment runtime flow check passed."),
                () -> "Compound-assignment runtime flow check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend compound assignment runtime class check passed."),
                () -> "Compound-assignment runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend compound assignment runtime flow check failed."),
                () -> "Compound-assignment runtime flow check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend compound assignment runtime class check failed."),
                () -> "Compound-assignment runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendDynamicInstanceCallRoutesBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping frontend dynamic-call integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_dynamic_call_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name DynamicCallSmoke
                extends Node
                
                func dynamic_size() -> int:
                    var values
                    values = PackedInt32Array()
                    return values.size()
                
                func typed_size(values: PackedInt32Array) -> int:
                    return values.size()
                """;
        var module = parseModule(
                tempDir.resolve("dynamic_call_smoke.gd"),
                source,
                Map.of("DynamicCallSmoke", "RuntimeDynamicCallSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        assertEquals("RuntimeDynamicCallSmoke", lowered.getClassDefs().getFirst().getName());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_dynamic_call_runtime",
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
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeDynamicCallSmoke\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"DynamicCallSmoke\")"), entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "DynamicCallSmokeNode",
                        "RuntimeDynamicCallSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(dynamicCallTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend dynamic call runtime dispatch check passed."),
                () -> "Dynamic dispatch runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend dynamic call exact route check passed."),
                () -> "Exact-route runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend dynamic call runtime class check passed."),
                () -> "Dynamic-call runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend dynamic call runtime dispatch check failed."),
                () -> "Dynamic dispatch runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend dynamic call exact route check failed."),
                () -> "Exact-route runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend dynamic call runtime class check failed."),
                () -> "Dynamic-call runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendVariantMethodAbiBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping Variant method ABI integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_variant_method_abi_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name VariantMethodAbiSmoke
                extends Node
                
                var variant_calls: int
                
                func accept_variant(value: Variant) -> int:
                    variant_calls += 1
                    return typeof(value) * 10 + variant_calls
                
                func echo_variant(value: Variant) -> Variant:
                    return value
                
                func accept_int(value: int) -> int:
                    return value + 1
                
                func read_variant_calls() -> int:
                    return variant_calls
                """;
        var module = parseModule(
                tempDir.resolve("variant_method_abi_smoke.gd"),
                source,
                Map.of("VariantMethodAbiSmoke", "RuntimeVariantMethodAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        assertEquals("RuntimeVariantMethodAbiSmoke", lowered.getClassDefs().getFirst().getName());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_variant_method_abi_runtime",
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
        assertTrue(
                entryHeader.contains("godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT"),
                () -> "Variant method binding metadata should publish the NIL_IS_VARIANT flag.\nHeader:\n" + entryHeader
        );
        assertTrue(
                entryHeader.contains("GDExtensionPropertyInfo return_info = gdcc_make_property_full(GDEXTENSION_VARIANT_TYPE_NIL, GD_STATIC_SN(u8\"\"), godot_PROPERTY_HINT_NONE, GD_STATIC_S(u8\"\"), GD_STATIC_SN(u8\"\"), godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT);"),
                () -> "Variant return info should use outward NIL metadata plus NIL_IS_VARIANT usage.\nHeader:\n" + entryHeader
        );
        assertFalse(
                entryHeader.contains("expected = GDEXTENSION_VARIANT_TYPE_NIL;"),
                () -> "Variant parameters must not keep the exact NIL runtime gate.\nHeader:\n" + entryHeader
        );
        assertTrue(
                entryHeader.contains("expected = GDEXTENSION_VARIANT_TYPE_INT;"),
                () -> "Non-Variant parameters must keep their exact runtime gate.\nHeader:\n" + entryHeader
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "VariantMethodAbiSmokeNode",
                        "RuntimeVariantMethodAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(variantMethodAbiTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend Variant method ABI dynamic call check passed."),
                () -> "Variant dynamic call check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend Variant method ABI direct return check passed."),
                () -> "Variant direct return check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend Variant method ABI runtime class check passed."),
                () -> "Variant method ABI runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend Variant method ABI dynamic call check failed."),
                () -> "Variant dynamic call check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend Variant method ABI direct return check failed."),
                () -> "Variant direct return check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend Variant method ABI runtime class check failed."),
                () -> "Variant method ABI runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendNonVariantMethodGuardRejectsPackedArrayAtRuntime() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping non-Variant method guard integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_non_variant_method_guard_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name NonVariantMethodGuardSmoke
                extends Node
                
                func accept_int(value: int) -> int:
                    return value + 1
                """;
        var module = parseModule(
                tempDir.resolve("non_variant_method_guard_smoke.gd"),
                source,
                Map.of("NonVariantMethodGuardSmoke", "RuntimeNonVariantMethodGuardSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        assertEquals("RuntimeNonVariantMethodGuardSmoke", lowered.getClassDefs().getFirst().getName());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_non_variant_method_guard_runtime",
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
                        "NonVariantMethodGuardSmokeNode",
                        "RuntimeNonVariantMethodGuardSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(nonVariantMethodGuardTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("Cannot convert argument 2 from PackedInt32Array to int."),
                () -> "The exact non-Variant runtime gate should still reject PackedInt32Array.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("Cannot convert argument 2 from PackedInt32Array to Nil."),
                () -> "The negative guard must still be the non-Variant exact gate, not a Variant/Nil regression.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendVariantPropertyAbiBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping Variant property ABI integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_variant_property_abi_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name VariantPropertyAbiSmoke
                extends Node
                
                var payload: Variant
                @export var visible_payload: Variant
                var score: int
                
                func read_payload_size() -> int:
                    return payload.size()
                
                func read_visible_payload_size() -> int:
                    return visible_payload.size()
                
                func read_score() -> int:
                    return score
                """;
        var module = parseModule(
                tempDir.resolve("variant_property_abi_smoke.gd"),
                source,
                Map.of("VariantPropertyAbiSmoke", "RuntimeVariantPropertyAbiSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        assertEquals("RuntimeVariantPropertyAbiSmoke", lowered.getClassDefs().getFirst().getName());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_variant_property_abi_runtime",
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
        assertTrue(
                entrySource.contains("gdcc_bind_property_full(class_name, GD_STATIC_SN(u8\"payload\"), GDEXTENSION_VARIANT_TYPE_NIL, godot_PROPERTY_HINT_NONE, GD_STATIC_S(u8\"\"), GD_STATIC_SN(u8\"\"), godot_PROPERTY_USAGE_NO_EDITOR | godot_PROPERTY_USAGE_NIL_IS_VARIANT,"),
                () -> "Non-export Variant property should publish NIL + NO_EDITOR | NIL_IS_VARIANT with an empty property class_name slot.\nSource:\n" + entrySource
        );
        assertTrue(
                entrySource.contains("gdcc_bind_property_full(class_name, GD_STATIC_SN(u8\"visible_payload\"), GDEXTENSION_VARIANT_TYPE_NIL, godot_PROPERTY_HINT_NONE, GD_STATIC_S(u8\"\"), GD_STATIC_SN(u8\"\"), godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT,"),
                () -> "Export Variant property should publish NIL + DEFAULT | NIL_IS_VARIANT with an empty property class_name slot.\nSource:\n" + entrySource
        );
        assertTrue(
                entrySource.contains("gdcc_bind_property_full(class_name, GD_STATIC_SN(u8\"score\"), GDEXTENSION_VARIANT_TYPE_INT, godot_PROPERTY_HINT_NONE, GD_STATIC_S(u8\"\"), GD_STATIC_SN(u8\"\"), godot_PROPERTY_USAGE_NO_EDITOR,"),
                () -> "Non-Variant property should keep its metadata shape with an empty property class_name slot.\nSource:\n" + entrySource
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "VariantPropertyAbiSmokeNode",
                        "RuntimeVariantPropertyAbiSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(variantPropertyAbiTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend Variant property ABI hidden property check passed."),
                () -> "Hidden Variant property check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend Variant property ABI exported property check passed."),
                () -> "Exported Variant property check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend Variant property ABI non-Variant property check passed."),
                () -> "Non-Variant property check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend Variant property ABI runtime class check passed."),
                () -> "Variant property ABI runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend Variant property ABI hidden property check failed."),
                () -> "Hidden Variant property check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend Variant property ABI exported property check failed."),
                () -> "Exported Variant property check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend Variant property ABI non-Variant property check failed."),
                () -> "Non-Variant property check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend Variant property ABI runtime class check failed."),
                () -> "Variant property ABI runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendDynamicVariantReceiverWritebackBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping frontend dynamic Variant writeback integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_dynamic_variant_writeback_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name DynamicVariantWritebackSmoke
                extends Node
                
                var packed_payload: Variant
                var array_payload: Variant
                
                func reset_payloads() -> void:
                    packed_payload = PackedInt32Array()
                    array_payload = Array()
                
                func append_packed(seed: int) -> int:
                    packed_payload.push_back(seed)
                    return packed_payload.size()
                
                func append_array(seed: int) -> int:
                    array_payload.push_back(seed)
                    return array_payload.size()
                """;
        var module = parseModule(
                tempDir.resolve("dynamic_variant_writeback_smoke.gd"),
                source,
                Map.of("DynamicVariantWritebackSmoke", "RuntimeDynamicVariantWritebackSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        assertEquals("RuntimeDynamicVariantWritebackSmoke", lowered.getClassDefs().getFirst().getName());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_dynamic_variant_writeback_runtime",
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
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeDynamicVariantWritebackSmoke\")"), entrySource);
        assertTrue(
                Pattern.compile("gdcc_variant_requires_writeback\\(&\\$[A-Za-z0-9_]+\\)").matcher(entrySource).find(),
                () -> "Generated C should call the runtime writeback helper.\nSource:\n" + entrySource
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "DynamicVariantWritebackSmokeNode",
                        "RuntimeDynamicVariantWritebackSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(dynamicVariantWritebackTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend dynamic Variant writeback packed check passed."),
                () -> "Packed Variant writeback runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend dynamic Variant writeback array check passed."),
                () -> "Array Variant writeback runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend dynamic Variant writeback runtime class check passed."),
                () -> "Dynamic Variant writeback runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend dynamic Variant writeback packed check failed."),
                () -> "Packed Variant writeback runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend dynamic Variant writeback array check failed."),
                () -> "Array Variant writeback runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend dynamic Variant writeback runtime class check failed."),
                () -> "Dynamic Variant writeback runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendDirectSlotSnapshotWritebackBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping direct-slot snapshot writeback integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_direct_slot_snapshot_writeback_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name DirectSlotSnapshotWritebackSmoke
                extends Node
                
                func identity(value: String) -> String:
                    return value
                
                func join_parts(parts: PackedStringArray) -> String:
                    var parr := PackedStringArray()
                    for part in parts:
                        parr.append(identity(part))
                    return "".join(parr)
                
                func append_via_helper(raw: String) -> int:
                    var encoded := PackedStringArray(["x"])
                    encoded.append(identity(raw))
                    return encoded.size()
                
                func append_dynamic(source: Variant, seed: Variant) -> int:
                    var payload: Variant = source
                    payload.append(str(seed))
                    return payload.size()
                """;
        var module = parseModule(
                tempDir.resolve("direct_slot_snapshot_writeback_smoke.gd"),
                source,
                Map.of("DirectSlotSnapshotWritebackSmoke", "RuntimeDirectSlotSnapshotWritebackSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        assertEquals("RuntimeDirectSlotSnapshotWritebackSmoke", lowered.getClassDefs().getFirst().getName());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_direct_slot_snapshot_writeback_runtime",
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
                        "DirectSlotSnapshotWritebackSmokeNode",
                        "RuntimeDirectSlotSnapshotWritebackSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(directSlotSnapshotWritebackTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("direct-slot snapshot writeback loop check passed."),
                () -> "Loop snapshot writeback runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("direct-slot snapshot writeback nested check passed."),
                () -> "Nested-argument snapshot writeback runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("direct-slot snapshot writeback dynamic check passed."),
                () -> "Dynamic Variant snapshot writeback runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("direct-slot snapshot writeback dynamic shared check passed."),
                () -> "Dynamic Variant shared-carrier skip runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("direct-slot snapshot writeback loop check failed."),
                () -> "Loop snapshot writeback runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("direct-slot snapshot writeback nested check failed."),
                () -> "Nested-argument snapshot writeback runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("direct-slot snapshot writeback dynamic check failed."),
                () -> "Dynamic Variant snapshot writeback runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("direct-slot snapshot writeback dynamic shared check failed."),
                () -> "Dynamic Variant shared-carrier skip runtime check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendWritableRouteRuntimeEdgesBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping writable-route edge integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_writable_route_runtime_edges");
        Files.createDirectories(tempDir);

        var source = """
                class_name WritableRouteRuntimeEdgeSmoke
                extends Node
                
                var typed_payload: PackedInt32Array
                var keys: Variant
                
                func set_typed_payload(value: PackedInt32Array) -> void:
                    typed_payload = value
                
                func reset_side_effect_keys() -> void:
                    keys = PackedInt32Array()
                
                func append_typed(seed: int) -> int:
                    typed_payload.push_back(seed)
                    return typed_payload.size()
                
                func next_slot(seed: int) -> int:
                    keys.push_back(seed)
                    return 0
                
                func measure_side_effect_key_route(
                    payloads: Dictionary,
                    seed: int
                ) -> int:
                    var slot_key: Variant = next_slot(seed)
                    payloads[slot_key].push_back(seed)
                    var zero_key: Variant = 0
                    return payloads[zero_key].size() * 10 + keys.size()
                
                func typed_payload_size() -> int:
                    return typed_payload.size()
                """;
        var module = parseModule(
                tempDir.resolve("writable_route_runtime_edge_smoke.gd"),
                source,
                Map.of("WritableRouteRuntimeEdgeSmoke", "RuntimeWritableRouteRuntimeEdgeSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());
        assertEquals("RuntimeWritableRouteRuntimeEdgeSmoke", lowered.getClassDefs().getFirst().getName());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_writable_route_runtime_edges",
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
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeWritableRouteRuntimeEdgeSmoke\")"), entrySource);
        assertTrue(
                entrySource.contains("godot_variant_get(") && entrySource.contains("godot_variant_set("),
                () -> "Generated C should lower the side-effect key route through generic variant get/set.\nSource:\n" + entrySource
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "WritableRouteRuntimeEdgeNode",
                        "RuntimeWritableRouteRuntimeEdgeSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(writableRouteRuntimeEdgeTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend typed property receiver writeback check passed."),
                () -> "Typed property-backed mutating receiver check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend side-effect key route first check passed."),
                () -> "Side-effect key route first-pass check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend side-effect key route repeat check passed."),
                () -> "Side-effect key route repeat check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend writable route runtime class check passed."),
                () -> "Writable-route runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend typed property receiver writeback check failed."),
                () -> "Typed property-backed mutating receiver check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend side-effect key route first check failed."),
                () -> "Side-effect key route first-pass check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend side-effect key route repeat check failed."),
                () -> "Side-effect key route repeat check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend writable route runtime class check failed."),
                () -> "Writable-route runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendMappedCrossFileComplexControlFlowBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping mapped cross-file frontend integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_mapped_cross_file_complex_runtime");
        Files.createDirectories(tempDir);

        var hostSource = """
                class_name ComplexMappedCoordinator
                extends Node
                
                func run_cross_file_flow() -> int:
                    var alpha: Alpha = Alpha.new()
                    var beta: Beta = Beta.new()
                    return alpha.mix(beta, 2) + beta.respond(alpha, 2) + alpha.base + beta.offset
                
                func alpha_runtime_class_name() -> String:
                    return Alpha.new().get_class()
                
                func beta_runtime_class_name() -> String:
                    return Beta.new().get_class()
                """;
        var alphaSource = """
                class_name Alpha
                extends RefCounted
                
                var base: int
                
                func _init() -> void:
                    base = 4
                
                func own_value() -> int:
                    return base
                
                func mix(beta: Beta, depth: int) -> int:
                    var result := 0
                    if depth <= 0:
                        result = base + beta.offset
                    elif depth == 1:
                        result = beta.respond(self, depth - 1) + beta.offset
                    else:
                        var total := 0
                        var cursor := depth
                        while cursor > 0:
                            if cursor > 1:
                                total = total + beta.offset
                            elif cursor == 1:
                                total = total + beta.peek_alpha(self)
                            else:
                                total = total + 1000
                            cursor = cursor - 1
                        result = total + beta.respond(self, depth - 1)
                    return result
                """;
        var betaSource = """
                class_name Beta
                extends RefCounted
                
                var offset: int
                
                func _init() -> void:
                    offset = 3
                
                func own_value() -> int:
                    return offset
                
                func peek_alpha(alpha: Alpha) -> int:
                    return alpha.base + alpha.own_value()
                
                func respond(alpha: Alpha, depth: int) -> int:
                    var result := 0
                    if depth < 0:
                        result = -100
                    elif depth == 0:
                        result = offset + alpha.base
                    else:
                        var sum := 0
                        var index := 0
                        while index < depth:
                            if index == 0:
                                sum = sum + alpha.base
                            elif index == 1:
                                sum = sum + alpha.base + offset
                            else:
                                sum = sum + alpha.own_value()
                            index = index + 1
                        result = sum + alpha.mix(self, depth - 1)
                    return result
                """;
        var module = parseModule(
                "frontend_mapped_cross_file_complex_runtime_module",
                List.of(
                        new SourceFileSpec(tempDir.resolve("complex_mapped_coordinator.gd"), hostSource),
                        new SourceFileSpec(tempDir.resolve("alpha.gd"), alphaSource),
                        new SourceFileSpec(tempDir.resolve("beta.gd"), betaSource)
                ),
                Map.of(
                        "ComplexMappedCoordinator", "RuntimeComplexMappedCoordinator",
                        "Alpha", "RuntimeComplexAlpha",
                        "Beta", "RuntimeComplexBeta"
                )
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(3, lowered.getClassDefs().size());
        assertTrue(lowered.getClassDefs().stream().anyMatch(classDef -> "RuntimeComplexMappedCoordinator".equals(classDef.getName())));
        assertTrue(lowered.getClassDefs().stream().anyMatch(classDef -> "RuntimeComplexAlpha".equals(classDef.getName())));
        assertTrue(lowered.getClassDefs().stream().anyMatch(classDef -> "RuntimeComplexBeta".equals(classDef.getName())));

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_mapped_cross_file_complex_runtime",
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
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeComplexMappedCoordinator\")"), entrySource);
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeComplexAlpha\")"), entrySource);
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeComplexBeta\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"ComplexMappedCoordinator\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"Alpha\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"Beta\")"), entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "ComplexMappedCoordinatorNode",
                        "RuntimeComplexMappedCoordinator",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(complexMappedCrossFileTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend complex mapped cross-file flow check passed."),
                () -> "Complex mapped cross-file flow check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend complex mapped runtime class check passed."),
                () -> "Mapped runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend complex mapped cross-file flow check failed."),
                () -> "Complex mapped cross-file flow check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend complex mapped runtime class check failed."),
                () -> "Mapped runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendMappedTopLevelInnerClassBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping mapped top-level inner-class frontend integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_mapped_top_level_inner_runtime");
        Files.createDirectories(tempDir);

        var source = """
                class_name MappedInnerRuntimeProbe
                extends Node
                
                class Shared extends RefCounted:
                    func base_value() -> int:
                        return 9
                
                class Leaf extends Shared:
                    func read() -> int:
                        return base_value() + 4
                
                class SceneChild extends Node:
                    var leaf: Leaf = Leaf.new()
                
                    func leaf_value() -> int:
                        return leaf.read()
                
                    func leaf_runtime_surface_ok() -> bool:
                        var runtime_class = leaf.get_class()
                        var matches_leaf = leaf.is_class("RuntimeMappedInnerRuntimeProbe__sub__Leaf")
                        var matches_shared = leaf.is_class("RuntimeMappedInnerRuntimeProbe__sub__Shared")
                        var matches_ref_counted = leaf.is_class("RefCounted")
                        var leaks_leaf_source_name = leaf.is_class("Leaf")
                        var leaks_shared_source_name = leaf.is_class("Shared")
                        return runtime_class == "RuntimeMappedInnerRuntimeProbe__sub__Leaf" and matches_leaf and matches_shared and matches_ref_counted and not leaks_leaf_source_name and not leaks_shared_source_name
                
                func mount_inner_child() -> bool:
                    var child_name: StringName = StringName("SceneChild")
                    var child_path: NodePath = NodePath("SceneChild")
                    var child: SceneChild = SceneChild.new()
                    var child_slot: Node = child
                    child_slot.name = child_name
                    self.add_child(child_slot)
                    return self.has_node(child_path)
                
                func mounted_child_runtime_surface_ok() -> bool:
                    var child_path: NodePath = NodePath("SceneChild")
                    var mounted_child = self.get_node_or_null(child_path)
                    if mounted_child == null:
                        return false
                    var runtime_class = mounted_child.get_class()
                    var matches_child = mounted_child.is_class("RuntimeMappedInnerRuntimeProbe__sub__SceneChild")
                    var matches_node = mounted_child.is_class("Node")
                    var leaks_source_name = mounted_child.is_class("SceneChild")
                    return runtime_class == "RuntimeMappedInnerRuntimeProbe__sub__SceneChild" and matches_child and matches_node and not leaks_source_name
                
                func mounted_child_leaf_value() -> int:
                    var child_path: NodePath = NodePath("SceneChild")
                    var mounted_child = self.get_node_or_null(child_path)
                    return int(mounted_child.call("leaf_value"))
                
                func mounted_child_leaf_runtime_surface_ok() -> bool:
                    var child_path: NodePath = NodePath("SceneChild")
                    var mounted_child = self.get_node_or_null(child_path)
                    return bool(mounted_child.call("leaf_runtime_surface_ok"))
                """;
        var module = parseModule(
                tempDir.resolve("mapped_inner_runtime_probe.gd"),
                source,
                Map.of("MappedInnerRuntimeProbe", "RuntimeMappedInnerRuntimeProbe")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(4, lowered.getClassDefs().size());
        assertTrue(lowered.getClassDefs().stream().anyMatch(classDef -> "RuntimeMappedInnerRuntimeProbe".equals(classDef.getName())));
        assertTrue(lowered.getClassDefs().stream().anyMatch(classDef -> "RuntimeMappedInnerRuntimeProbe__sub__Shared".equals(classDef.getName())));
        assertTrue(lowered.getClassDefs().stream().anyMatch(classDef -> "RuntimeMappedInnerRuntimeProbe__sub__Leaf".equals(classDef.getName())));
        assertTrue(lowered.getClassDefs().stream().anyMatch(classDef -> "RuntimeMappedInnerRuntimeProbe__sub__SceneChild".equals(classDef.getName())));

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_mapped_top_level_inner_runtime",
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
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeMappedInnerRuntimeProbe\")"), entrySource);
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeMappedInnerRuntimeProbe__sub__Shared\")"), entrySource);
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeMappedInnerRuntimeProbe__sub__Leaf\")"), entrySource);
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeMappedInnerRuntimeProbe__sub__SceneChild\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"MappedInnerRuntimeProbe__sub__Shared\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"MappedInnerRuntimeProbe__sub__Leaf\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"MappedInnerRuntimeProbe__sub__SceneChild\")"), entrySource);
        assertTrue(entrySource.contains("godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL"), entrySource);
        assertFalse(entrySource.contains("godot_new_Variant_with_Nil"), entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "MappedInnerRuntimeProbeNode",
                        "RuntimeMappedInnerRuntimeProbe",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(mappedTopLevelInnerClassRuntimeTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend mapped inner outer runtime class check passed."),
                () -> "Mapped outer runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend mapped inner scene mount check passed."),
                () -> "Mapped inner scene mount check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend mapped inner scene child runtime class check passed."),
                () -> "Mapped inner scene child runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend mapped inner refcounted runtime class check passed."),
                () -> "Mapped inner refcounted runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend mapped inner outer runtime class check failed."),
                () -> "Mapped outer runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend mapped inner scene mount check failed."),
                () -> "Mapped inner scene mount check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend mapped inner scene child runtime class check failed."),
                () -> "Mapped inner scene child runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend mapped inner refcounted runtime class check failed."),
                () -> "Mapped inner refcounted runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void lowerFrontendCrossFileInheritanceBuildsNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping cross-file inheritance frontend integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_cross_file_inheritance_runtime");
        Files.createDirectories(tempDir);

        // The child source is listed before its base on purpose: module class order follows
        // source order, so this fixture forces the derived-first hazard that requires the
        // backend to emit base-before-derived struct definitions and ClassDB registration.
        var childSource = """
                class_name CrossFileChild
                extends CrossFileBase
                
                func child_value() -> int:
                    return base_value() + 1
                """;
        var baseSource = """
                class_name CrossFileBase
                extends RefCounted
                
                func base_value() -> int:
                    return 41
                """;
        var hostSource = """
                class_name CrossFileInheritanceHost
                extends Node
                
                func make_child_sum() -> int:
                    var child: CrossFileChild = CrossFileChild.new()
                    return child.base_value() + child.child_value()
                
                func dispatch_via_base(base_ref: CrossFileBase) -> int:
                    return base_ref.base_value()
                """;
        var module = parseModule(
                "frontend_cross_file_inheritance_runtime_module",
                List.of(
                        new SourceFileSpec(tempDir.resolve("cross_file_child.gd"), childSource),
                        new SourceFileSpec(tempDir.resolve("cross_file_base.gd"), baseSource),
                        new SourceFileSpec(tempDir.resolve("cross_file_inheritance_host.gd"), hostSource)
                ),
                Map.of(
                        "CrossFileChild", "RuntimeCrossFileChild",
                        "CrossFileBase", "RuntimeCrossFileBase",
                        "CrossFileInheritanceHost", "RuntimeCrossFileInheritanceHost"
                )
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(3, lowered.getClassDefs().size());
        // Pin the hazard setup: the module stays derived-first, so the C assertions below
        // actually observe the backend reorder instead of a lucky input order.
        assertEquals("RuntimeCrossFileChild", lowered.getClassDefs().getFirst().getName());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_cross_file_inheritance_runtime",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        var entrySource = Files.readString(projectDir.resolve("entry.c"));
        var headerSource = Files.readString(projectDir.resolve("entry.h"));

        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        // The child wrapper embeds `RuntimeCrossFileBase _super` by value as its first field,
        // so the base struct definition must be complete first (C has no by-value incomplete
        // fields); raw module order would fail the C compile here.
        var baseStructIndex = headerSource.indexOf("struct RuntimeCrossFileBase {");
        var childStructIndex = headerSource.indexOf("struct RuntimeCrossFileChild {");
        assertTrue(baseStructIndex >= 0 && childStructIndex > baseStructIndex, headerSource);
        assertTrue(headerSource.contains("RuntimeCrossFileBase _super;"), headerSource);
        // Godot rejects registering an extension class whose parent extension class is not
        // registered yet, so ClassDB registration follows the same base-before-derived order.
        // The `();` anchor matches only the call sites inside initialize(), never the later
        // `void <Class>_class_bind_methods() {` definitions.
        var baseRegistrationIndex = entrySource.indexOf("RuntimeCrossFileBase_class_bind_methods();");
        var childRegistrationIndex = entrySource.indexOf("RuntimeCrossFileChild_class_bind_methods();");
        assertTrue(baseRegistrationIndex >= 0 && childRegistrationIndex > baseRegistrationIndex, entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "CrossFileInheritanceHostNode",
                        "RuntimeCrossFileInheritanceHost",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(crossFileInheritanceTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend cross-file inheritance instantiate dispatch check passed."),
                () -> "Cross-file instantiation and method dispatch check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend cross-file inheritance upcast dispatch check passed."),
                () -> "Base-typed upcast dispatch check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend cross-file inheritance is_class check passed."),
                () -> "Cross-file is/is_class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend cross-file inheritance instantiate dispatch check failed."),
                () -> "Cross-file instantiation and method dispatch check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend cross-file inheritance upcast dispatch check failed."),
                () -> "Base-typed upcast dispatch check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend cross-file inheritance is_class check failed."),
                () -> "Cross-file is/is_class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    private static @NotNull String crossFileInheritanceTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "CrossFileInheritanceHostNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    # Engine-side instantiation of a class that extends another file's class,
                    # plus inherited/own method dispatch on the gdcc-compiled host.
                    var child = RuntimeCrossFileChild.new()
                    var sum = int(target.call("make_child_sum"))
                    if sum == 83 and int(child.base_value()) == 41 and int(child.child_value()) == 42:
                        print("frontend cross-file inheritance instantiate dispatch check passed.")
                    else:
                        push_error("frontend cross-file inheritance instantiate dispatch check failed.")
                
                    # A derived instance passed through the base-typed parameter boundary must
                    # still dispatch to the base owner method via the safe upcast path.
                    var via_base = int(target.call("dispatch_via_base", child))
                    if via_base == 41:
                        print("frontend cross-file inheritance upcast dispatch check passed.")
                    else:
                        push_error("frontend cross-file inheritance upcast dispatch check failed.")
                
                    var is_ok = child is RuntimeCrossFileBase
                    var classdb_ok = ClassDB.is_parent_class("RuntimeCrossFileChild", "RuntimeCrossFileBase")
                    var is_class_ok = child.is_class("RuntimeCrossFileChild") and child.is_class("RuntimeCrossFileBase") and child.is_class("RefCounted") and not child.is_class("CrossFileChild") and not child.is_class("CrossFileBase")
                    if is_ok and classdb_ok and is_class_ok:
                        print("frontend cross-file inheritance is_class check passed.")
                    else:
                        push_error("frontend cross-file inheritance is_class check failed.")
                """;
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull Path sourcePath,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        return parseModule(
                "frontend_build_smoke_module",
                List.of(new SourceFileSpec(sourcePath, source)),
                topLevelCanonicalNameMap
        );
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull String moduleName,
            @NotNull List<SourceFileSpec> sources,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var parser = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var units = sources.stream()
                .map(sourceFile -> parser.parseUnit(sourceFile.sourcePath(), sourceFile.source(), parseDiagnostics))
                .toList();
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule(moduleName, units, topLevelCanonicalNameMap);
    }

    private static @NotNull String expectedArtifactFileName(@NotNull CProjectInfo projectInfo) {
        var outputBaseName = projectInfo.projectName() + "_"
                + projectInfo.getOptimizationLevel().name().toLowerCase() + "_"
                + projectInfo.getTargetPlatform().architecture.name().toLowerCase();
        return projectInfo.getTargetPlatform().sharedLibraryFileName(outputBaseName);
    }

    private static @NotNull String testScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "FrontendBuildSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var result = int(target.call("ping"))
                    if result == 1:
                        print("frontend lowering runtime ping check passed.")
                    else:
                        push_error("frontend lowering runtime ping check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeFrontendBuildSmoke" and target.is_class("RuntimeFrontendBuildSmoke") and not target.is_class("FrontendBuildSmoke"):
                        print("frontend lowering runtime class remap check passed.")
                    else:
                        push_error("frontend lowering runtime class remap check failed.")
                """;
    }

    private static @NotNull String parameterDefaultTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "ParamDefaultSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var exact_result = int(target.call("run_exact_checks"))
                    if exact_result == 323:
                        print("parameter default exact-route check passed.")
                    else:
                        push_error("parameter default exact-route check failed: got %s." % exact_result)
                
                    var reeval_result = int(target.call("run_reeval_checks"))
                    if reeval_result == 22:
                        print("parameter default re-evaluation check passed.")
                    else:
                        push_error("parameter default re-evaluation check failed: got %s." % reeval_result)
                
                    # The dynamic route (Object.call -> GDExtensionMethodBind::call ->
                    # callee-prologue wrapper) fills omitted trailing arguments at runtime.
                    var dynamic_result = int(target.call("ping", 1))
                    if dynamic_result == 41:
                        print("parameter default dynamic-route check passed.")
                    else:
                        push_error("parameter default dynamic-route check failed: got %s." % dynamic_result)
                
                    var dynamic_multi = int(target.call("multi", 1))
                    if dynamic_multi == 112:
                        print("parameter default dynamic multi-slot check passed.")
                    else:
                        push_error("parameter default dynamic multi-slot check failed: got %s." % dynamic_multi)
                
                    var dynamic_reeval = int(target.call("append_probe")) * 10 + int(target.call("append_probe"))
                    if dynamic_reeval == 22:
                        print("parameter default dynamic re-evaluation check passed.")
                    else:
                        push_error("parameter default dynamic re-evaluation check failed: got %s." % dynamic_reeval)
                
                    var gdcc_dynamic = int(target.call("run_dynamic_checks"))
                    if gdcc_dynamic == 41003:
                        print("parameter default gdcc dynamic-route check passed.")
                    else:
                        push_error("parameter default gdcc dynamic-route check failed: got %s." % gdcc_dynamic)
                
                    # Too-few below the required prefix surfaces as a call error whose
                    # `expected` is the required count (1), not the full arity (2). The failed
                    # call aborts this GDScript function, so this probe must stay LAST.
                    print("parameter default dynamic too-few probe reached.")
                    target.call("ping")
                """;
    }

    private static @NotNull String builtinChainedWritebackTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "BuiltinChainWritebackNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var assigned = float(target.call("assign_position_x", 6.5))
                    if is_equal_approx(assigned, 6.5) and is_equal_approx(target.position.x, 6.5):
                        print("frontend builtin chained writeback assignment check passed.")
                    else:
                        push_error("frontend builtin chained writeback assignment check failed.")
                
                    var bumped = float(target.call("bump_position_x", 1.25))
                    if is_equal_approx(bumped, 7.75) and is_equal_approx(target.position.x, 7.75):
                        print("frontend builtin chained writeback compound check passed.")
                    else:
                        push_error("frontend builtin chained writeback compound check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeBuiltinChainWritebackSmoke" and target.is_class("RuntimeBuiltinChainWritebackSmoke") and not target.is_class("BuiltinChainWritebackSmoke"):
                        print("frontend builtin chained writeback runtime class check passed.")
                    else:
                        push_error("frontend builtin chained writeback runtime class check failed.")
                """;
    }

    private static @NotNull String propertyInitTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "PropertyInitSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var value = int(target.call("read_value"))
                    var angle = float(target.call("read_angle"))
                    var flag = bool(target.call("read_flag"))
                    if value == 21 and is_equal_approx(angle, PI) and flag:
                        print("frontend property init runtime check passed.")
                    else:
                        push_error("frontend property init runtime check failed.")
                
                    var node_class = String(target.call("read_node_class"))
                    var object_class = String(target.call("read_object_class"))
                    var ref_count = int(target.call("read_ref_count"))
                    var worker_value = int(target.call("read_worker_value"))
                    var worker_ref_count = int(target.call("read_worker_ref_count"))
                    var worker_runtime_class = String(target.call("read_worker_runtime_class"))
                    if node_class == "Node" and object_class == "Node" and ref_count >= 1 and worker_value == 7 and worker_ref_count >= 1 and worker_runtime_class == "RuntimePropertyInitWorker":
                        print("frontend property init object runtime check passed.")
                    else:
                        push_error("frontend property init object runtime check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimePropertyInitSmoke" and target.is_class("RuntimePropertyInitSmoke") and not target.is_class("PropertyInitSmoke"):
                        print("frontend property init runtime class check passed.")
                    else:
                        push_error("frontend property init runtime class check failed.")
                """;
    }

    private static @NotNull String constructorTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "FrontendConstructorNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var vector = target.call("make_vector")
                    if typeof(vector) == TYPE_VECTOR3I and vector == Vector3i(1, 2, 3):
                        print("frontend constructor builtin check passed.")
                    else:
                        push_error("frontend constructor builtin check failed.")
                
                    var node_class = String(target.call("make_node_class_name"))
                    if node_class == "Node":
                        print("frontend constructor engine object check passed.")
                    else:
                        push_error("frontend constructor engine object check failed.")
                
                    var engine_ref_count = int(target.call("measure_ref_counted_reference_count"))
                    if engine_ref_count >= 1:
                        print("frontend constructor engine refcounted lifecycle check passed.")
                    else:
                        push_error("frontend constructor engine refcounted lifecycle check failed.")
                
                    var worker_value = int(target.call("make_worker_value"))
                    var worker_ref_count = int(target.call("measure_worker_reference_count"))
                    if worker_value == 7 and worker_ref_count >= 1:
                        print("frontend constructor gdcc refcounted lifecycle check passed.")
                    else:
                        push_error("frontend constructor gdcc refcounted lifecycle check failed.")
                
                    var worker_runtime_class = String(target.call("make_worker_runtime_class_name"))
                    var worker_value_via_source_name = int(target.call("make_worker_value_via_source_name_parameter"))
                    if worker_runtime_class == "RuntimeConstructorWorker" and worker_value_via_source_name == 7:
                        print("frontend constructor mapped cross-file worker route check passed.")
                    else:
                        push_error("frontend constructor mapped cross-file worker route check failed.")
                
                    var plain_worker_value = int(target.call("make_plain_worker_value"))
                    if plain_worker_value == 9:
                        print("frontend constructor gdcc plain object lifecycle check passed.")
                    else:
                        push_error("frontend constructor gdcc plain object lifecycle check failed.")
                """;
    }

    private static @NotNull String compoundAssignmentTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "CompoundAssignmentSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var total = int(target.run_compound_flow())
                    if total == 1606:
                        print("frontend compound assignment runtime flow check passed.")
                    else:
                        push_error("frontend compound assignment runtime flow check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeCompoundAssignmentSmoke" and target.is_class("RuntimeCompoundAssignmentSmoke") and not target.is_class("CompoundAssignmentSmoke"):
                        print("frontend compound assignment runtime class check passed.")
                    else:
                        push_error("frontend compound assignment runtime class check failed.")
                """;
    }

    private static @NotNull String complexMappedCrossFileTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "ComplexMappedCoordinatorNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var total = int(target.call("run_cross_file_flow"))
                    if total == 50:
                        print("frontend complex mapped cross-file flow check passed.")
                    else:
                        push_error("frontend complex mapped cross-file flow check failed.")
                
                    var alpha_runtime_class = String(target.call("alpha_runtime_class_name"))
                    var beta_runtime_class = String(target.call("beta_runtime_class_name"))
                    if alpha_runtime_class == "RuntimeComplexAlpha" and beta_runtime_class == "RuntimeComplexBeta":
                        print("frontend complex mapped runtime class check passed.")
                    else:
                        push_error("frontend complex mapped runtime class check failed.")
                """;
    }

    private static @NotNull String mappedTopLevelInnerClassRuntimeTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "MappedInnerRuntimeProbeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var outer_runtime_class = String(target.get_class())
                    if outer_runtime_class == "RuntimeMappedInnerRuntimeProbe" and target.is_class("RuntimeMappedInnerRuntimeProbe") and not target.is_class("MappedInnerRuntimeProbe"):
                        print("frontend mapped inner outer runtime class check passed.")
                    else:
                        push_error("frontend mapped inner outer runtime class check failed.")
                
                    var mounted = bool(target.call("mount_inner_child"))
                    if mounted:
                        print("frontend mapped inner scene mount check passed.")
                    else:
                        push_error("frontend mapped inner scene mount check failed.")
                
                    var child_surface_ok = bool(target.call("mounted_child_runtime_surface_ok"))
                    if child_surface_ok:
                        print("frontend mapped inner scene child runtime class check passed.")
                    else:
                        push_error("frontend mapped inner scene child runtime class check failed.")
                
                    var leaf_value = int(target.call("mounted_child_leaf_value"))
                    var leaf_surface_ok = bool(target.call("mounted_child_leaf_runtime_surface_ok"))
                    if leaf_value == 13 and leaf_surface_ok:
                        print("frontend mapped inner refcounted runtime class check passed.")
                    else:
                        push_error("frontend mapped inner refcounted runtime class check failed.")
                """;
    }

    private static @NotNull String dynamicCallTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "DynamicCallSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var dynamic_size = int(target.call("dynamic_size"))
                    if dynamic_size == 0:
                        print("frontend dynamic call runtime dispatch check passed.")
                    else:
                        push_error("frontend dynamic call runtime dispatch check failed.")
                
                    var typed_size = int(target.call("typed_size", PackedInt32Array([4, 5])))
                    if typed_size == 2:
                        print("frontend dynamic call exact route check passed.")
                    else:
                        push_error("frontend dynamic call exact route check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeDynamicCallSmoke" and target.is_class("RuntimeDynamicCallSmoke") and not target.is_class("DynamicCallSmoke"):
                        print("frontend dynamic call runtime class check passed.")
                    else:
                        push_error("frontend dynamic call runtime class check failed.")
                """;
    }

    private static @NotNull String variantMethodAbiTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "VariantMethodAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var accepted = int(target.call("accept_variant", PackedInt32Array([4, 5])))
                    var variant_calls = int(target.call("read_variant_calls"))
                    if accepted == TYPE_PACKED_INT32_ARRAY * 10 + 1 and variant_calls == 1:
                        print("frontend Variant method ABI dynamic call check passed.")
                    else:
                        push_error("frontend Variant method ABI dynamic call check failed.")
                
                    var echoed = target.echo_variant(PackedInt32Array([6, 7, 8]))
                    if typeof(echoed) == TYPE_PACKED_INT32_ARRAY and echoed.size() == 3 and echoed[2] == 8:
                        print("frontend Variant method ABI direct return check passed.")
                    else:
                        push_error("frontend Variant method ABI direct return check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeVariantMethodAbiSmoke" and target.is_class("RuntimeVariantMethodAbiSmoke") and not target.is_class("VariantMethodAbiSmoke"):
                        print("frontend Variant method ABI runtime class check passed.")
                    else:
                        push_error("frontend Variant method ABI runtime class check failed.")
                """;
    }

    private static @NotNull String nonVariantMethodGuardTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "NonVariantMethodGuardSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    target.call("accept_int", PackedInt32Array([9]))
                """;
    }

    private static @NotNull String variantPropertyAbiTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "VariantPropertyAbiSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    target.payload = PackedInt32Array([4, 5])
                    var payload = target.payload
                    var payload_size = int(target.call("read_payload_size"))
                    if typeof(payload) == TYPE_PACKED_INT32_ARRAY and payload.size() == 2 and payload[1] == 5 and payload_size == 2:
                        print("frontend Variant property ABI hidden property check passed.")
                    else:
                        push_error("frontend Variant property ABI hidden property check failed.")
                
                    target.visible_payload = PackedInt32Array([6, 7, 8])
                    var visible_payload = target.visible_payload
                    var visible_payload_size = int(target.call("read_visible_payload_size"))
                    if typeof(visible_payload) == TYPE_PACKED_INT32_ARRAY and visible_payload.size() == 3 and visible_payload[0] == 6 and visible_payload_size == 3:
                        print("frontend Variant property ABI exported property check passed.")
                    else:
                        push_error("frontend Variant property ABI exported property check failed.")
                
                    target.score = 9
                    if int(target.score) == 9 and int(target.call("read_score")) == 9:
                        print("frontend Variant property ABI non-Variant property check passed.")
                    else:
                        push_error("frontend Variant property ABI non-Variant property check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeVariantPropertyAbiSmoke" and target.is_class("RuntimeVariantPropertyAbiSmoke") and not target.is_class("VariantPropertyAbiSmoke"):
                        print("frontend Variant property ABI runtime class check passed.")
                    else:
                        push_error("frontend Variant property ABI runtime class check failed.")
                """;
    }

    private static @NotNull String dynamicVariantWritebackTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "DynamicVariantWritebackSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    target.call("reset_payloads")
                    var packed_first = int(target.call("append_packed", 4))
                    var packed_second = int(target.call("append_packed", 5))
                    if packed_first == 1 and packed_second == 2:
                        print("frontend dynamic Variant writeback packed check passed.")
                    else:
                        push_error("frontend dynamic Variant writeback packed check failed.")
                
                    target.call("reset_payloads")
                    var array_first = int(target.call("append_array", 4))
                    var array_second = int(target.call("append_array", 5))
                    if array_first == 1 and array_second == 2:
                        print("frontend dynamic Variant writeback array check passed.")
                    else:
                        push_error("frontend dynamic Variant writeback array check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeDynamicVariantWritebackSmoke" and target.is_class("RuntimeDynamicVariantWritebackSmoke") and not target.is_class("DynamicVariantWritebackSmoke"):
                        print("frontend dynamic Variant writeback runtime class check passed.")
                    else:
                        push_error("frontend dynamic Variant writeback runtime class check failed.")
                """;
    }

    private static @NotNull String directSlotSnapshotWritebackTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "DirectSlotSnapshotWritebackSmokeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var joined = String(target.call("join_parts", PackedStringArray(["a", "b", "c"])))
                    if joined == "abc":
                        print("direct-slot snapshot writeback loop check passed.")
                    else:
                        push_error("direct-slot snapshot writeback loop check failed.")
                
                    var grown = int(target.call("append_via_helper", "y"))
                    if grown == 2:
                        print("direct-slot snapshot writeback nested check passed.")
                    else:
                        push_error("direct-slot snapshot writeback nested check failed.")
                
                    var dynamic_packed = int(target.call("append_dynamic", PackedStringArray(), "s"))
                    if dynamic_packed == 1:
                        print("direct-slot snapshot writeback dynamic check passed.")
                    else:
                        push_error("direct-slot snapshot writeback dynamic check failed.")
                
                    var dynamic_array = int(target.call("append_dynamic", Array(), "s"))
                    if dynamic_array == 1:
                        print("direct-slot snapshot writeback dynamic shared check passed.")
                    else:
                        push_error("direct-slot snapshot writeback dynamic shared check failed.")
                """;
    }

    private static @NotNull String writableRouteRuntimeEdgeTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "WritableRouteRuntimeEdgeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    target.call("set_typed_payload", PackedInt32Array())
                    var typed_first = int(target.call("append_typed", 4))
                    var typed_second = int(target.call("append_typed", 5))
                    var typed_size = int(target.call("typed_payload_size"))
                    if typed_first == 1 and typed_second == 2 and typed_size == 2:
                        print("frontend typed property receiver writeback check passed.")
                    else:
                        push_error("frontend typed property receiver writeback check failed.")
                
                    # This fixture keeps the dynamic key carrier inside the native object so
                    # the coverage stays focused on writable-route continuation semantics
                    # instead of mixing in separate outer-boundary ABI concerns.
                    target.call("reset_side_effect_keys")
                    var payloads = {0: PackedInt32Array()}
                    var first_route = int(target.call("measure_side_effect_key_route", payloads, 4))
                    if first_route == 11:
                        print("frontend side-effect key route first check passed.")
                    else:
                        push_error("frontend side-effect key route first check failed.")
                
                    var second_route = int(target.call("measure_side_effect_key_route", payloads, 5))
                    if second_route == 22:
                        print("frontend side-effect key route repeat check passed.")
                    else:
                        push_error("frontend side-effect key route repeat check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeWritableRouteRuntimeEdgeSmoke" and target.is_class("RuntimeWritableRouteRuntimeEdgeSmoke") and not target.is_class("WritableRouteRuntimeEdgeSmoke"):
                        print("frontend writable route runtime class check passed.")
                    else:
                        push_error("frontend writable route runtime class check failed.")
                """;
    }

    private static @NotNull String builtinPropertyAbiTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "BuiltinPropertyAbiProbeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var color_r = float(target.call("color_r", Color(0.25, 0.5, 0.75, 1.0)))
                    var vector_x = float(target.call("vector_x", Vector3(4.0, 5.0, 6.0)))
                    if is_equal_approx(color_r, 0.25) and is_equal_approx(vector_x, 4.0):
                        print("frontend builtin property abi parameter check passed.")
                    else:
                        push_error("frontend builtin property abi parameter check failed.")
                
                    var constructed_color_r = float(target.call("constructed_color_r"))
                    var constructed_vector_y = float(target.call("constructed_vector_y"))
                    var local_color_g = float(target.call("local_color_g"))
                    var local_vector_z = float(target.call("local_vector_z"))
                    if is_equal_approx(constructed_color_r, 0.25) and is_equal_approx(constructed_vector_y, 2.5) and is_equal_approx(local_color_g, 0.4) and is_equal_approx(local_vector_z, 9.0):
                        print("frontend builtin property abi temporary check passed.")
                    else:
                        push_error("frontend builtin property abi temporary check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeBuiltinPropertyAbiProbe" and target.is_class("RuntimeBuiltinPropertyAbiProbe") and not target.is_class("BuiltinPropertyAbiProbe"):
                        print("frontend builtin property abi runtime class check passed.")
                    else:
                        push_error("frontend builtin property abi runtime class check failed.")
                """;
    }

    private static @NotNull String vectorIToVectorBoundaryTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "VectorIToVectorBoundaryNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var local_sum = float(target.call("local_sum"))
                    var assignment_sum = float(target.call("assignment_sum", Vector3i(4, 5, 6)))
                    var fixed_arg_sum = float(target.call("fixed_arg_sum", Vector3i(7, 8, 9)))
                    var returned = target.call("return_vector", Vector3i(10, 11, 12))
                    if is_equal_approx(local_sum, 6.0) and is_equal_approx(assignment_sum, 15.0) and is_equal_approx(fixed_arg_sum, 24.0) and typeof(returned) == TYPE_VECTOR3 and returned == Vector3(10.0, 11.0, 12.0):
                        print("frontend Vector*i-to-Vector source boundary check passed.")
                    else:
                        push_error("frontend Vector*i-to-Vector source boundary check failed.")
                """;
    }

    private static @NotNull String stringFamilyInboundCallWrapperTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "StringFamilyInboundWrapperNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var name_from_text = target.call("take_string_name", "from-text")
                    var text_from_name = target.call("take_string", &"from-name")
                    var exact_name = target.call("take_string_name", &"exact-name")
                    var exact_text = target.call("take_string", "exact-text")
                    if typeof(name_from_text) == TYPE_STRING_NAME and String(name_from_text) == "from-text" and typeof(text_from_name) == TYPE_STRING and String(text_from_name) == "from-name" and typeof(exact_name) == TYPE_STRING_NAME and String(exact_name) == "exact-name" and typeof(exact_text) == TYPE_STRING and String(exact_text) == "exact-text":
                        print("frontend String/StringName inbound dynamic call check passed.")
                    else:
                        push_error("frontend String/StringName inbound dynamic call check failed.")
                
                    target.call("take_string_name", NodePath("bad"))
                    print("frontend String/StringName inbound dynamic call after bad call.")
                """;
    }

    @Test
    void lowerFrontendOnreadySyncLifecycleBuildsNativeLibraryAndRunsInGodot() throws Exception {
        if (ZigUtil.findZig() == null || GodotGdextensionTestRunner.findGodotBinaryFromEnv() == null) {
            Assumptions.abort("Zig or GODOT_BIN not found; skipping onready sync lifecycle integration test");
            return;
        }

        var tempParent = Path.of("tmp/test");
        Files.createDirectories(tempParent);
        var tempDir = Files.createTempDirectory(tempParent, "onready_sync_runtime_");
        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var runtimeDir = Files.createTempDirectory(tempParent, "onready_sync_project_");
        Files.copy(Path.of("test_project/project.godot"), runtimeDir.resolve("project.godot"));
        Files.copy(Path.of("test_project/root.gd"), runtimeDir.resolve("root.gd"));

        // Derived classes are listed before their bases on purpose — module class order
        // follows source order, forcing the backend's base-before-derived emission.
        var syncChildSource = """
                class_name OnreadySyncChild
                extends OnreadySyncBase

                @onready var value: int = 41

                func sample() -> int:
                    return value
                """;
        var syncBaseSource = """
                class_name OnreadySyncBase
                extends Node

                var calls: int = 0
                var observed: int = -1

                func _ready() -> void:
                    calls += 1
                    observed = sample()

                func sample() -> int:
                    return 0

                func manual() -> void:
                    _ready()
                """;
        var noReadySource = """
                class_name OnreadyNoReady
                extends Node

                @onready var value: int = 17
                """;
        var ctorChildSource = """
                class_name OnreadyCtorChild
                extends OnreadyCtorBase

                @onready var text: String = "ready"
                """;
        var ctorBaseSource = """
                class_name OnreadyCtorBase
                extends Node

                @onready var value: int = 11
                var observed: int = -1
                var calls: int = 0

                func _init() -> void:
                    _ready()
                    notification(Node.NOTIFICATION_READY)

                func _ready() -> void:
                    observed = value
                    calls += 1
                """;
        // Super scenario: both classes declare `_ready` and the child forwards via
        // `super._ready()`, so one DONE flag must dedup the vtable and super entries.
        var superChildSource = """
                class_name OnreadySuperChild
                extends OnreadySuperBase

                var child_calls: int = 0

                func _ready() -> void:
                    child_calls += 1
                    value += 10
                    super._ready()
                """;
        var superBaseSource = """
                class_name OnreadySuperBase
                extends Node

                @onready var value: int = 7
                var base_calls: int = 0
                var observed: int = 0

                func _ready() -> void:
                    base_calls += 1
                    observed = value

                func manual() -> void:
                    _ready()
                """;
        var module = parseModule(
                "onready_sync_runtime_module",
                List.of(
                        new SourceFileSpec(tempDir.resolve("onready_sync_child.gd"), syncChildSource),
                        new SourceFileSpec(tempDir.resolve("onready_sync_base.gd"), syncBaseSource),
                        new SourceFileSpec(tempDir.resolve("onready_no_ready.gd"), noReadySource),
                        new SourceFileSpec(tempDir.resolve("onready_ctor_child.gd"), ctorChildSource),
                        new SourceFileSpec(tempDir.resolve("onready_ctor_base.gd"), ctorBaseSource),
                        new SourceFileSpec(tempDir.resolve("onready_super_child.gd"), superChildSource),
                        new SourceFileSpec(tempDir.resolve("onready_super_base.gd"), superBaseSource)
                ),
                Map.of(
                        "OnreadySyncChild", "RuntimeOnreadySyncChild",
                        "OnreadySyncBase", "RuntimeOnreadySyncBase",
                        "OnreadyNoReady", "RuntimeOnreadyNoReady",
                        "OnreadyCtorChild", "RuntimeOnreadyCtorChild",
                        "OnreadyCtorBase", "RuntimeOnreadyCtorBase",
                        "OnreadySuperChild", "RuntimeOnreadySuperChild",
                        "OnreadySuperBase", "RuntimeOnreadySuperBase"
                )
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        // Pin the derived-first hazard setup the assertions below rely on.
        assertEquals("RuntimeOnreadySyncChild", lowered.getClassDefs().getFirst().getName());

        var projectInfo = new CProjectInfo(
                "onready_sync_runtime",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());

        GodotGdextensionTestRunner.GodotRunResult runResult;
        try (var runner = new GodotGdextensionTestRunner(runtimeDir)) {
            runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                    buildResult.artifacts(),
                    List.of(),
                    new GodotGdextensionTestRunner.TestScriptSpec(onreadySyncLifecycleTestScript())
            ));
            runResult = runner.run(true);
        }
        var combinedOutput = runResult.combinedOutput();
        var godotLog = tempDir.resolve("godot.log");
        Files.writeString(godotLog, combinedOutput);
        IO.println("Godot combined output: " + godotLog.toAbsolutePath());

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("onready sync lifecycle scenario passed."),
                () -> "Godot output should confirm the sync lifecycle scenario.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("SCRIPT ERROR"),
                () -> "Godot output should not contain script errors.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("ERROR"),
                () -> "Godot output should not contain errors.\nOutput:\n" + combinedOutput
        );
    }

    /// Sync scenario contract: constructor defaults; `manual()` calls `_ready()` through
    /// the non-engine `_ready` entry initialization which applies the chain once and latches DONE; engine READY
    /// replays the chain on each dispatch; `request_ready()` is the only re-arm path;
    /// `_init`-time `_ready`/notification calls run the user body but skip onready apply.
    private static @NotNull String onreadySyncLifecycleTestScript() {
        return """
                extends Node

                func _ready() -> void:
                    var child = RuntimeOnreadySyncChild.new()
                    assert(child.value == 0, "SyncChild value should be type-default before lifecycle")
                    assert(child.calls == 0, "SyncChild calls should start at 0")
                    child.manual()
                    assert(child.value == 41, "first manual call should apply the child onready chain")
                    assert(child.observed == 41, "inherited _ready body should observe the applied value")
                    assert(child.calls == 1, "user _ready should have run once")
                    child.value = 99
                    child.manual()
                    assert(child.value == 99, "DONE must suppress replay on repeat manual calls")
                    assert(child.observed == 99, "user _ready still observes the mutated value")
                    assert(child.calls == 2, "user _ready still runs on repeat manual calls")
                    add_child(child)
                    assert(child.value == 41, "engine READY should replay the chain despite DONE")
                    assert(child.observed == 41, "engine READY should re-observe the applied value")
                    assert(child.calls == 3, "engine READY should forward the user _ready")
                    child.value = 99
                    remove_child(child)
                    add_child(child)
                    assert(child.value == 99, "re-entry without request_ready must not replay")
                    assert(child.calls == 3, "re-entry without request_ready must not call _ready")
                    remove_child(child)
                    child.request_ready()
                    add_child(child)
                    assert(child.value == 41, "request_ready should re-arm and replay the chain")
                    assert(child.calls == 4, "request_ready should forward the user _ready again")
                    remove_child(child)
                    child.free()

                    var no_ready = RuntimeOnreadyNoReady.new()
                    assert(no_ready.value == 0, "NoReady value should be type-default before tree entry")
                    add_child(no_ready)
                    assert(no_ready.value == 17, "engine READY should apply onready without user _ready")
                    no_ready.value = 99
                    remove_child(no_ready)
                    no_ready.request_ready()
                    add_child(no_ready)
                    assert(no_ready.value == 17, "request_ready should replay onready without user _ready")
                    remove_child(no_ready)
                    no_ready.free()

                    var ctor = RuntimeOnreadyCtorChild.new()
                    assert(ctor.value == 0, "CtorChild value should stay default through constructor _ready")
                    assert(ctor.observed == 0, "constructor _ready observes the first-written default")
                    assert(ctor.calls == 2, "both constructor paths run the user body without applying")
                    assert(ctor.text == "", "descendant slot must stay default during constructor _ready")
                    add_child(ctor)
                    assert(ctor.value == 11, "engine READY applies the base onready after construction")
                    assert(ctor.text == "ready", "engine READY applies the descendant onready after construction")
                    assert(ctor.observed == 11, "real _ready observes the applied base value")
                    assert(ctor.calls == 3, "real _ready forwards the user body once")
                    remove_child(ctor)
                    ctor.free()

                    var super_child = RuntimeOnreadySuperChild.new()
                    assert(super_child.value == 0, "SuperChild value should be type-default before lifecycle")
                    super_child.manual()
                    assert(super_child.value == 17, "chain applies 7 then child body adds 10")
                    assert(super_child.observed == 17, "super._ready must observe the mutated value, not a replay")
                    assert(super_child.base_calls == 1, "super._ready forwards the base body once")
                    assert(super_child.child_calls == 1, "vtable-ready runs the child body once")
                    super_child.manual()
                    assert(super_child.value == 27, "DONE must suppress the chain in both entries")
                    assert(super_child.observed == 27, "base body still observes the accumulated value")
                    assert(super_child.base_calls == 2, "repeat manual call still runs the base body")
                    assert(super_child.child_calls == 2, "repeat manual call still runs the child body")
                    add_child(super_child)
                    assert(super_child.value == 17, "engine replay resets 7 then child adds 10")
                    assert(super_child.observed == 17, "super._ready must not reset value a second time")
                    assert(super_child.base_calls == 3, "engine replay forwards the base body once")
                    assert(super_child.child_calls == 3, "engine replay forwards the child body once")
                    remove_child(super_child)
                    super_child.request_ready()
                    add_child(super_child)
                    assert(super_child.value == 17, "request_ready replay resets 7 then adds 10")
                    assert(super_child.observed == 17)
                    assert(super_child.base_calls == 4)
                    assert(super_child.child_calls == 4)
                    remove_child(super_child)
                    super_child.free()

                    print("onready sync lifecycle scenario passed.")
                """;
    }

    @Test
    void lowerFrontendOnreadyNodeLiteralGeneratesC() throws Exception {
        // Environment-independent gate: real frontend lowering plus `CCodegen.generate()`
        // only — no Zig toolchain or Godot binary is required, so this test must never be
        // skipped on missing dependencies. Anchors stay scoped per generated C function
        // body so same-named temps cannot satisfy another function's expectation.
        var tempDir = Path.of("tmp/test/onready_node_literal_codegen");
        Files.createDirectories(tempDir);

        var source = """
                class_name OnreadyNodeLiteralSmoke
                extends Node
                
                @onready var child: Node = $Child
                @onready var unique_name: StringName = %Unique.get_name()
                @onready var child_name: StringName = $Child.name
                @onready var get_name_callable: Callable = $Child.get_name
                @onready var renamed_signal: Signal = $Child.renamed
                """;
        var module = parseModule(
                "onready_node_literal_codegen",
                List.of(new SourceFileSpec(tempDir.resolve("onready_node_literal.gd"), source)),
                Map.of("OnreadyNodeLiteralSmoke", "RuntimeOnreadyNodeLiteralSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "onready_node_literal_codegen",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);
        var entrySource = codegen.generate().stream()
                .filter(file -> file.filePath().equals("entry.c"))
                .findFirst()
                .map(file -> new String(file.contentWriter(), StandardCharsets.UTF_8))
                .orElseThrow(() -> new AssertionError("entry.c not found in generated files"));

        // Each source init helper emits the decoded NodePath literal before the pinned
        // native `Node.get_node` engine call; the leaf lookup needs no liveness guard.
        var childInit = requireFunctionCBody(entrySource, "RuntimeOnreadyNodeLiteralSmoke__field_init_child");
        assertOrderedIn(childInit, "godot_new_NodePath_with_utf8_chars", "gdcc_engine_call_node_get_node_");
        assertFalse(childInit.contains("assert_object_live failed"), childInit);

        // Suffix instructions keep their order after the engine call: the live guard runs
        // first, then the property read resolves through the `get_name` accessor.
        var childNameInit = requireFunctionCBody(entrySource, "RuntimeOnreadyNodeLiteralSmoke__field_init_child_name");
        assertOrderedIn(
                childNameInit,
                "godot_new_NodePath_with_utf8_chars",
                "gdcc_engine_call_node_get_node_",
                "assert_object_live failed",
                "gdcc_engine_call_node_get_name_"
        );
        var uniqueNameInit = requireFunctionCBody(entrySource, "RuntimeOnreadyNodeLiteralSmoke__field_init_unique_name");
        assertOrderedIn(
                uniqueNameInit,
                "\"%Unique\"",
                "gdcc_engine_call_node_get_node_",
                "assert_object_live failed",
                "gdcc_engine_call_node_get_name_"
        );

        // Method-reference and signal routes construct their values through the engine
        // factories after the same guard.
        var callableInit = requireFunctionCBody(entrySource, "RuntimeOnreadyNodeLiteralSmoke__field_init_get_name_callable");
        assertOrderedIn(
                callableInit,
                "gdcc_engine_call_node_get_node_",
                "assert_object_live failed",
                "godot_new_Callable_with_Object_StringName"
        );
        var signalInit = requireFunctionCBody(entrySource, "RuntimeOnreadyNodeLiteralSmoke__field_init_renamed_signal");
        assertOrderedIn(
                signalInit,
                "gdcc_engine_call_node_get_node_",
                "assert_object_live failed",
                "godot_new_Signal_with_Object_StringName"
        );

        // Constructor-time default apply must not invoke the source helper; ready-time
        // apply calls it and writes the backing field directly (the setter route is
        // deliberately not used for onready initialization).
        var defaultApply = requireFunctionCBody(
                entrySource,
                "RuntimeOnreadyNodeLiteralSmoke_class_apply_property_default_child"
        );
        assertTrue(defaultApply.contains("RuntimeOnreadyNodeLiteralSmoke__onready_field_default_child("), defaultApply);
        assertFalse(defaultApply.contains("RuntimeOnreadyNodeLiteralSmoke__field_init_child("), defaultApply);
        var readyApply = requireFunctionCBody(
                entrySource,
                "RuntimeOnreadyNodeLiteralSmoke_class_apply_onready_init_child"
        );
        assertTrue(readyApply.contains("RuntimeOnreadyNodeLiteralSmoke__field_init_child("), readyApply);
        assertTrue(readyApply.contains("self->child"), readyApply);
        assertFalse(readyApply.contains("set_child"), readyApply);

        // The ready chain applies every onready initializer in source declaration order,
        // and the class registers its root adapter in the engine lookup table.
        var chainBody = requireFunctionCBody(entrySource, "RuntimeOnreadyNodeLiteralSmoke_class_apply_onready_inits");
        assertOrderedIn(
                chainBody,
                "RuntimeOnreadyNodeLiteralSmoke_class_apply_onready_init_child(self);",
                "RuntimeOnreadyNodeLiteralSmoke_class_apply_onready_init_unique_name(self);",
                "RuntimeOnreadyNodeLiteralSmoke_class_apply_onready_init_child_name(self);",
                "RuntimeOnreadyNodeLiteralSmoke_class_apply_onready_init_get_name_callable(self);",
                "RuntimeOnreadyNodeLiteralSmoke_class_apply_onready_init_renamed_signal(self);"
        );
        assertTrue(
                entrySource.contains(
                        "{ &RuntimeOnreadyNodeLiteralSmoke__gdcc_onready_class_name, "
                                + "&RuntimeOnreadyNodeLiteralSmoke__gdcc_onready_apply_root }"
                ),
                entrySource
        );
    }

    @Test
    void lowerFrontendOnreadyNodeLiteralBuildsNativeLibraryAndRunsInGodot() throws Exception {
        if (ZigUtil.findZig() == null || GodotGdextensionTestRunner.findGodotBinaryFromEnv() == null) {
            Assumptions.abort("Zig or GODOT_BIN not found; skipping onready node literal integration test");
            return;
        }

        var tempParent = Path.of("tmp/test");
        Files.createDirectories(tempParent);
        var tempDir = Files.createTempDirectory(tempParent, "onready_node_literal_runtime_");
        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var runtimeDir = Files.createTempDirectory(tempParent, "onready_node_literal_project_");
        Files.copy(Path.of("test_project/project.godot"), runtimeDir.resolve("project.godot"));
        Files.copy(Path.of("test_project/root.gd"), runtimeDir.resolve("root.gd"));

        var probeSource = """
                class_name OnreadyNodeLiteralProbe
                extends Node
                
                @onready var child_node: Node = $Child
                @onready var nested_leaf: Node = $Branch/Leaf
                @onready var quoted_node: Node = $"Name With Space"
                @onready var unique_node: Node = %Unique
                @onready var spaced_unique_node: Node = %"Unique Name"
                @onready var untyped_child = $Child
                
                @onready var child_name: StringName = $Child.name
                @onready var unique_name: StringName = %Unique.get_name()
                @onready var get_name_callable: Callable = $Child.get_name
                @onready var renamed_signal: Signal = $Child.renamed
                
                @onready var self_name: StringName = $".".get_name()
                @onready var parent_name: StringName = $Child.get_parent().get_name()
                
                var ready_child: Node
                var ready_name: StringName
                
                func _ready() -> void:
                    ready_child = child_node
                    ready_name = child_name
                """;
        var plainSource = """
                class_name OnreadyNodeLiteralPlain
                extends Node
                
                @onready var child_node: Node = $Child
                """;
        // A narrow second probe keeps the replay swap small: only `Child` and `%Unique`
        // are re-resolved, so no unrelated lookup can emit a spurious "Node not found".
        var replaySource = """
                class_name OnreadyNodeLiteralReplayProbe
                extends Node
                
                @onready var child_node: Node = $Child
                @onready var unique_node: Node = %Unique
                """;
        var module = parseModule(
                "onready_node_literal_runtime_module",
                List.of(
                        new SourceFileSpec(tempDir.resolve("onready_node_literal_probe.gd"), probeSource),
                        new SourceFileSpec(tempDir.resolve("onready_node_literal_plain.gd"), plainSource),
                        new SourceFileSpec(tempDir.resolve("onready_node_literal_replay.gd"), replaySource)
                ),
                Map.of(
                        "OnreadyNodeLiteralProbe", "RuntimeOnreadyNodeLiteralProbe",
                        "OnreadyNodeLiteralPlain", "RuntimeOnreadyNodeLiteralPlain",
                        "OnreadyNodeLiteralReplayProbe", "RuntimeOnreadyNodeLiteralReplayProbe"
                )
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectInfo = new CProjectInfo(
                "onready_node_literal_runtime",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());

        // Empty sceneNodes: the test script creates un-entered instances of the registered
        // runtime classes itself, checks constructor defaults, prepares children/unique
        // owners and only then mounts the instances to trigger real engine READY.
        GodotGdextensionTestRunner.GodotRunResult runResult;
        try (var runner = new GodotGdextensionTestRunner(runtimeDir)) {
            runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                    buildResult.artifacts(),
                    List.of(),
                    new GodotGdextensionTestRunner.TestScriptSpec(onreadyNodeLiteralTestScript())
            ));
            runResult = runner.run(true);
        }
        var combinedOutput = runResult.combinedOutput();
        var godotLog = tempDir.resolve("godot.log");
        Files.writeString(godotLog, combinedOutput);
        IO.println("Godot combined output: " + godotLog.toAbsolutePath());

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("onready node literal scenario passed."),
                () -> "Godot output should confirm the node literal scenario.\nOutput:\n" + combinedOutput
        );
        for (var forbidden : List.of(
                "SCRIPT ERROR",
                "ERROR",
                "engine method call failed",
                "assert_object_live failed",
                "Node not found"
        )) {
            assertFalse(
                    combinedOutput.contains(forbidden),
                    () -> "Godot output should not contain \"" + forbidden + "\".\nOutput:\n" + combinedOutput
            );
        }
    }

    /// Node literal scenario contract: constructor defaults (typed `Node` null, untyped
    /// nil); runtime children created after construction are still found at READY; all
    /// five lookup forms compare by object identity; the four suffix routes assert exact
    /// member semantics; `$"."` and `get_parent()` aliases resolve the probe's own name;
    /// user `_ready` observes initialized fields; a class without user `_ready` still
    /// applies; swapping children only takes effect through an armed `request_ready()`
    /// replay, proving the helpers re-evaluate instead of caching.
    private static @NotNull String onreadyNodeLiteralTestScript() {
        return """
                extends Node
                
                func _ready() -> void:
                    var probe = RuntimeOnreadyNodeLiteralProbe.new()
                    assert(probe.child_node == null, "typed Node field should default to null before tree entry")
                    assert(probe.untyped_child == null, "untyped field should default to nil before tree entry")
                    assert(String(probe.child_name) == "", "StringName field should stay default before tree entry")
                    assert(probe.get_name_callable.is_null(), "Callable field should stay default before tree entry")
                    assert(probe.renamed_signal.is_null(), "Signal field should stay default before tree entry")
                
                    probe.name = "ProbeName"
                    var child = Node.new()
                    child.name = "Child"
                    probe.add_child(child)
                    var branch = Node.new()
                    branch.name = "Branch"
                    probe.add_child(branch)
                    var leaf = Node.new()
                    leaf.name = "Leaf"
                    branch.add_child(leaf)
                    var spaced = Node.new()
                    spaced.name = "Name With Space"
                    probe.add_child(spaced)
                    var unique = Node.new()
                    unique.name = "Unique"
                    probe.add_child(unique)
                    unique.owner = probe
                    unique.unique_name_in_owner = true
                    var spaced_unique = Node.new()
                    spaced_unique.name = "Unique Name"
                    probe.add_child(spaced_unique)
                    spaced_unique.owner = probe
                    spaced_unique.unique_name_in_owner = true
                
                    add_child(probe)
                    assert(probe.child_node == child, "$Child should resolve to the runtime child")
                    assert(probe.nested_leaf == leaf, "nested path should resolve to the leaf")
                    assert(probe.quoted_node == spaced, "quoted form should resolve the spaced name")
                    assert(probe.unique_node == unique, "%Unique should resolve the unique child")
                    assert(probe.spaced_unique_node == spaced_unique, "quoted % form should resolve")
                    assert(probe.untyped_child == child, "untyped slot should hold the child")
                    assert(probe.child_name == child.name, "property suffix should read the child name")
                    assert(probe.unique_name == StringName("Unique"), "call suffix should return the unique name")
                    assert(probe.get_name_callable.get_object() == child, "Callable should bind the child")
                    assert(probe.get_name_callable.get_method() == StringName("get_name"), "Callable should name get_name")
                    assert(probe.get_name_callable.call() == child.name, "Callable should invoke get_name")
                    assert(probe.renamed_signal.get_object() == child, "Signal should bind the child")
                    assert(probe.renamed_signal.get_name() == StringName("renamed"), "Signal should be renamed")
                    assert(probe.self_name == probe.name, "$. lookup should resolve the probe name")
                    assert(probe.parent_name == probe.name, "get_parent suffix should resolve the probe name")
                    assert(probe.ready_child == child, "user _ready should observe the applied child")
                    assert(probe.ready_name == child.name, "user _ready should observe the applied suffix")
                
                    var plain = RuntimeOnreadyNodeLiteralPlain.new()
                    var plain_child = Node.new()
                    plain_child.name = "Child"
                    plain.add_child(plain_child)
                    add_child(plain)
                    assert(plain.child_node == plain_child, "class without user _ready should still apply")
                
                    var replay = RuntimeOnreadyNodeLiteralReplayProbe.new()
                    var old_child = Node.new()
                    old_child.name = "Child"
                    replay.add_child(old_child)
                    var old_unique = Node.new()
                    old_unique.name = "Unique"
                    replay.add_child(old_unique)
                    old_unique.owner = replay
                    old_unique.unique_name_in_owner = true
                    add_child(replay)
                    assert(replay.child_node == old_child, "replay probe should resolve the first child")
                    assert(replay.unique_node == old_unique, "replay probe should resolve the first unique")
                
                    # Swap the children at the same paths while keeping the old nodes alive:
                    # without an armed replay the fields must keep pointing at the old
                    # objects even though they are detached from the probe.
                    remove_child(replay)
                    replay.remove_child(old_child)
                    old_unique.unique_name_in_owner = false
                    replay.remove_child(old_unique)
                    var new_child = Node.new()
                    new_child.name = "Child"
                    replay.add_child(new_child)
                    var new_unique = Node.new()
                    new_unique.name = "Unique"
                    replay.add_child(new_unique)
                    new_unique.owner = replay
                    new_unique.unique_name_in_owner = true
                
                    add_child(replay)
                    assert(replay.child_node == old_child, "re-entry without request_ready must not re-evaluate")
                    assert(replay.unique_node == old_unique, "re-entry must keep the old unique object")
                
                    remove_child(replay)
                    replay.request_ready()
                    add_child(replay)
                    assert(replay.child_node == new_child, "armed replay should re-evaluate $Child")
                    assert(replay.unique_node == new_unique, "armed replay should re-evaluate %Unique")
                
                    probe.free()
                    plain.free()
                    replay.free()
                    old_child.free()
                    old_unique.free()
                    print("onready node literal scenario passed.")
                """;
    }

    /// Extracts one generated C function body by name via brace matching, skipping
    /// prototypes so anchors stay scoped to the definition.
    private static @NotNull String requireFunctionCBody(@NotNull String entrySource, @NotNull String cFunctionName) {
        var searchFromIndex = 0;
        var signatureIndex = -1;
        while ((signatureIndex = entrySource.indexOf(cFunctionName + "(", searchFromIndex)) >= 0) {
            var openBraceIndex = entrySource.indexOf('{', signatureIndex);
            var semicolonIndex = entrySource.indexOf(';', signatureIndex);
            if (semicolonIndex < 0 || semicolonIndex > openBraceIndex) {
                break;
            }
            searchFromIndex = semicolonIndex + 1;
        }
        assertTrue(signatureIndex >= 0, () -> "C function not found: " + cFunctionName);
        var openBraceIndex = entrySource.indexOf('{', signatureIndex);
        assertTrue(openBraceIndex >= 0, () -> "No body for C function: " + cFunctionName);
        var depth = 0;
        for (var index = openBraceIndex; index < entrySource.length(); index++) {
            var current = entrySource.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return entrySource.substring(signatureIndex, index + 1);
            }
        }
        throw new AssertionError("Unbalanced braces after C function: " + cFunctionName);
    }

    /// Every fragment must appear strictly after the previous one (ordered containment).
    private static void assertOrderedIn(@NotNull String text, @NotNull String... fragmentsInOrder) {
        var searchFromIndex = 0;
        for (var fragment : fragmentsInOrder) {
            var index = text.indexOf(fragment, searchFromIndex);
            assertTrue(index >= 0, () -> "Missing fragment: " + fragment + "\n" + text);
            searchFromIndex = index + fragment.length();
        }
    }

    @Test
    void lowerFrontendOnreadyCoroutineLifecycleBuildsNativeLibraryAndRunsInGodot() throws Exception {
        if (ZigUtil.findZig() == null || GodotGdextensionTestRunner.findGodotBinaryFromEnv() == null) {
            Assumptions.abort("Zig or GODOT_BIN not found; skipping onready coroutine lifecycle integration test");
            return;
        }

        var tempParent = Path.of("tmp/test");
        Files.createDirectories(tempParent);
        var tempDir = Files.createTempDirectory(tempParent, "onready_coro_runtime_");
        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var runtimeDir = Files.createTempDirectory(tempParent, "onready_coro_project_");
        Files.copy(Path.of("test_project/project.godot"), runtimeDir.resolve("project.godot"));
        Files.copy(Path.of("test_project/root.gd"), runtimeDir.resolve("root.gd"));

        // Derived class first: `_ready` lives only on the base as a coroutine, so the
        // child exercises both the thunk entry initialization and the composite→shell→thunk path.
        var coroChildSource = """
                class_name OnreadyCoroChild
                extends OnreadyCoroBase

                @onready var value: int = 41

                func sample() -> int:
                    return value
                """;
        var coroBaseSource = """
                class_name OnreadyCoroBase
                extends Node

                signal proceed

                var calls: int = 0
                var observed: int = -1
                var completed: int = 0

                func _ready() -> void:
                    calls += 1
                    observed = sample()
                    await proceed
                    completed += 1

                func sample() -> int:
                    return 0

                func manual() -> void:
                    _ready()
                """;
        var module = parseModule(
                "onready_coro_runtime_module",
                List.of(
                        new SourceFileSpec(tempDir.resolve("onready_coro_child.gd"), coroChildSource),
                        new SourceFileSpec(tempDir.resolve("onready_coro_base.gd"), coroBaseSource)
                ),
                Map.of(
                        "OnreadyCoroChild", "RuntimeOnreadyCoroChild",
                        "OnreadyCoroBase", "RuntimeOnreadyCoroBase"
                )
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals("RuntimeOnreadyCoroChild", lowered.getClassDefs().getFirst().getName());

        var projectInfo = new CProjectInfo(
                "onready_coro_runtime",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());

        GodotGdextensionTestRunner.GodotRunResult runResult;
        try (var runner = new GodotGdextensionTestRunner(runtimeDir)) {
            runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                    buildResult.artifacts(),
                    List.of(),
                    new GodotGdextensionTestRunner.TestScriptSpec(onreadyCoroutineLifecycleTestScript())
            ));
            runResult = runner.run(true);
        }
        var combinedOutput = runResult.combinedOutput();
        var godotLog = tempDir.resolve("godot.log");
        Files.writeString(godotLog, combinedOutput);
        IO.println("Godot combined output: " + godotLog.toAbsolutePath());

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("onready coroutine lifecycle scenario passed."),
                () -> "Godot output should confirm the coroutine lifecycle scenario.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("SCRIPT ERROR"),
                () -> "Godot output should not contain script errors.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("ERROR"),
                () -> "Godot output should not contain errors.\nOutput:\n" + combinedOutput
        );
    }

    /// Coroutine scenario contract: `manual()` runs the detached `_ready` coroutine through
    /// the start-thunk entry initialization (chain applied once, DONE latched); the engine READY path
    /// goes composite impl → synchronous shell → thunk and replays the chain; each run
    /// suspends at `await proceed` and completes on the next emit.
    private static @NotNull String onreadyCoroutineLifecycleTestScript() {
        return """
                extends Node

                func _ready() -> void:
                    var coro = RuntimeOnreadyCoroChild.new()
                    assert(coro.value == 0, "CoroChild value should be type-default before lifecycle")
                    coro.manual()
                    assert(coro.value == 41, "thunk entry initialization should apply the child chain")
                    assert(coro.observed == 41, "coroutine _ready observes the applied value")
                    assert(coro.calls == 1, "coroutine _ready ran once")
                    assert(coro.completed == 0, "coroutine must suspend at await proceed")
                    coro.proceed.emit()
                    assert(coro.completed == 1, "signal resumption should complete the first run")
                    coro.value = 99
                    coro.manual()
                    assert(coro.value == 99, "DONE must suppress replay on repeat manual calls")
                    assert(coro.observed == 99, "user coroutine still observes the mutated value")
                    assert(coro.calls == 2, "user coroutine still runs on repeat manual calls")
                    coro.proceed.emit()
                    assert(coro.completed == 2, "second detached run completes on emit")
                    add_child(coro)
                    assert(coro.value == 41, "engine READY should replay the chain despite DONE")
                    assert(coro.observed == 41, "engine READY should re-observe the applied value")
                    assert(coro.calls == 3, "engine READY should forward the user coroutine")
                    assert(coro.completed == 2, "the engine-driven run still suspends")
                    coro.proceed.emit()
                    assert(coro.completed == 3, "the engine-driven run completes on emit")
                    coro.value = 99
                    remove_child(coro)
                    coro.request_ready()
                    add_child(coro)
                    assert(coro.value == 41, "request_ready should re-arm and replay the chain")
                    assert(coro.observed == 41, "request_ready should re-observe the applied value")
                    assert(coro.calls == 4, "request_ready should forward the user coroutine again")
                    coro.proceed.emit()
                    assert(coro.completed == 4, "the re-armed run completes on emit")
                    remove_child(coro)
                    coro.free()

                    print("onready coroutine lifecycle scenario passed.")
                """;
    }

    @Test
    void lowerFrontendOnreadyMixedScriptSuperUsesGdccRuntimeClassName() throws Exception {
        if (ZigUtil.findZig() == null || GodotGdextensionTestRunner.findGodotBinaryFromEnv() == null) {
            Assumptions.abort("Zig or GODOT_BIN not found; skipping mixed onready class-name integration test");
            return;
        }

        var tempParent = Path.of("tmp/test");
        Files.createDirectories(tempParent);
        var tempDir = Files.createTempDirectory(tempParent, "onready_mixed_runtime_");
        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var runtimeDir = Files.createTempDirectory(tempParent, "onready_mixed_project_");
        Files.copy(Path.of("test_project/project.godot"), runtimeDir.resolve("project.godot"));
        Files.copy(Path.of("test_project/root.gd"), runtimeDir.resolve("root.gd"));

        var mixedChildSource = """
                class_name OnreadyMixedChild
                extends OnreadyMixedBase

                @onready var value: int = 41

                func sample() -> int:
                    return value
                """;
        var mixedBaseSource = """
                class_name OnreadyMixedBase
                extends Node

                var calls: int = 0
                var observed: int = -1

                func _ready() -> void:
                    calls += 1
                    observed = sample()

                func sample() -> int:
                    return 0
                """;
        var module = parseModule(
                "onready_mixed_runtime_module",
                List.of(
                        new SourceFileSpec(tempDir.resolve("onready_mixed_child.gd"), mixedChildSource),
                        new SourceFileSpec(tempDir.resolve("onready_mixed_base.gd"), mixedBaseSource)
                ),
                Map.of(
                        "OnreadyMixedChild", "RuntimeOnreadyMixedChild",
                        "OnreadyMixedBase", "RuntimeOnreadyMixedBase"
                )
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals("RuntimeOnreadyMixedChild", lowered.getClassDefs().getFirst().getName());

        var projectInfo = new CProjectInfo(
                "onready_mixed_runtime",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), lowered);

        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());

        GodotGdextensionTestRunner.GodotRunResult runResult;
        try (var runner = new GodotGdextensionTestRunner(runtimeDir)) {
            runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                    buildResult.artifacts(),
                    List.of(),
                    List.of(new ScriptResourceSpec("res://onready_mixed_child.gd", onreadyMixedInterpretedChildSource())),
                    new GodotGdextensionTestRunner.TestScriptSpec(onreadyMixedRuntimeTestScript()),
                    COptimizationLevel.DEBUG
            ));
            runResult = runner.run(true);
        }
        var combinedOutput = runResult.combinedOutput();
        var godotLog = tempDir.resolve("godot.log");
        Files.writeString(godotLog, combinedOutput);
        IO.println("Godot combined output: " + godotLog.toAbsolutePath());

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("onready mixed GDScript super scenario passed."),
                () -> "Godot output should confirm the mixed class-name scenario.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("SCRIPT ERROR"),
                () -> "Godot output should not contain script errors.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("ERROR"),
                () -> "Godot output should not contain errors.\nOutput:\n" + combinedOutput
        );
    }

    private static @NotNull String onreadyMixedInterpretedChildSource() {
        return """
                class_name OnreadyInterpretedDescendant
                extends RuntimeOnreadyMixedChild

                var script_calls: int = 0

                @warning_ignore("native_method_override")
                func _ready() -> void:
                    script_calls += 1
                    super._ready()

                func manual_super() -> void:
                    super._ready()
                """;
    }

    private static @NotNull String onreadyMixedRuntimeTestScript() {
        return """
                extends Node

                func _ready() -> void:
                    var interpreted_script = load("res://onready_mixed_child.gd")
                    assert(interpreted_script != null, "mixed interpreted child script should load")

                    var manual = interpreted_script.new()
                    assert(manual.value == 0, "mixed onready value should start at its type default")
                    manual.manual_super()
                    assert(manual.value == 41, "first GDScript super call should initialize the GDCC child")
                    assert(manual.observed == 41, "base _ready should observe the initialized virtual sample")
                    assert(manual.calls == 1, "first GDScript super call should run the GDCC base body once")
                    manual.value = 99
                    manual.manual_super()
                    assert(manual.value == 99, "DONE should suppress a repeated GDScript super initialization")
                    assert(manual.observed == 99, "repeated super call should observe the preserved value")
                    assert(manual.calls == 2, "repeated super call should still run the GDCC base body")

                    add_child(manual)
                    assert(manual.value == 99, "late script READY super should not replay onready")
                    assert(manual.observed == 99, "late script READY super should observe the preserved value")
                    assert(manual.calls == 3, "script READY should run the GDCC base body once")
                    assert(manual.script_calls == 1, "first engine READY should run the interpreted override")
                    remove_child(manual)
                    manual.request_ready()
                    add_child(manual)
                    assert(manual.value == 99, "request_ready through script super should remain DONE-gated")
                    assert(manual.observed == 99, "request_ready super should observe the preserved value")
                    assert(manual.calls == 4, "request_ready should run the GDCC base body once more")
                    assert(manual.script_calls == 2, "request_ready should run the interpreted override again")
                    remove_child(manual)
                    manual.free()

                    var direct = interpreted_script.new()
                    assert(direct.value == 0, "fresh mixed instance should start at its type default")
                    add_child(direct)
                    assert(direct.value == 41, "first engine READY super should initialize a fresh GDCC child")
                    assert(direct.observed == 41, "fresh base _ready should observe the initialized value")
                    assert(direct.calls == 1, "fresh engine READY should run the GDCC base body once")
                    assert(direct.script_calls == 1, "fresh engine READY should run the interpreted override")
                    remove_child(direct)
                    direct.free()

                    print("onready mixed GDScript super scenario passed.")
                """;
    }

    private record SourceFileSpec(@NotNull Path sourcePath, @NotNull String source) {
    }
}
