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
        var librarySuffix = projectInfo.getTargetPlatform().sharedLibraryFileName("artifact").replace("artifact", "");

        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        assertTrue(Files.exists(projectDir.resolve("entry.c")));
        assertTrue(Files.exists(projectDir.resolve("entry.h")));
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeFrontendBuildSmoke\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"FrontendBuildSmoke\")"), entrySource);
        assertTrue(
                buildResult.artifacts().stream()
                        .anyMatch(artifact -> artifact.getFileName().toString().endsWith(librarySuffix)),
                () -> "Expected a native library artifact with suffix '" + librarySuffix + "', got " + buildResult.artifacts()
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
        var librarySuffix = projectInfo.getTargetPlatform().sharedLibraryFileName("artifact").replace("artifact", "");

        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        assertTrue(Files.exists(projectDir.resolve("entry.c")));
        assertTrue(Files.exists(projectDir.resolve("entry.h")));
        assertTrue(
                entrySource.contains("self->ready_value = RuntimePropertyInitSmoke__field_init_ready_value(self);"),
                entrySource
        );
        assertTrue(
                entrySource.contains("self->ready_angle = RuntimePropertyInitSmoke__field_init_ready_angle(self);"),
                entrySource
        );
        assertTrue(
                entrySource.contains("self->ready_flag = RuntimePropertyInitSmoke__field_init_ready_flag(self);"),
                entrySource
        );
        assertTrue(
                entrySource.contains("self->ready_node = RuntimePropertyInitSmoke__field_init_ready_node(self);"),
                entrySource
        );
        assertTrue(
                entrySource.contains("self->ready_obj = RuntimePropertyInitSmoke__field_init_ready_obj(self);"),
                entrySource
        );
        assertTrue(
                entrySource.contains("self->ready_ref = RuntimePropertyInitSmoke__field_init_ready_ref(self);"),
                entrySource
        );
        assertTrue(
                entrySource.contains("self->ready_worker = RuntimePropertyInitSmoke__field_init_ready_worker(self);"),
                entrySource
        );
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"_field_init_ready_value\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"_field_init_ready_angle\")"), entrySource);
        assertFalse(entrySource.contains("GD_STATIC_SN(u8\"_field_init_ready_flag\")"), entrySource);
        assertTrue(
                buildResult.artifacts().stream()
                        .anyMatch(artifact -> artifact.getFileName().toString().endsWith(librarySuffix)),
                () -> "Expected a native library artifact with suffix '" + librarySuffix + "', got " + buildResult.artifacts()
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
        assertTrue(entrySource.contains("RuntimeConstructorWorker__init(self);"), entrySource);
        assertFalse(
                entrySource.contains("gdcc_ref_counted_init_raw(godot_classdb_construct_object2(GD_STATIC_SN(u8\"RefCounted\")))"),
                entrySource
        );

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

    private record SourceFileSpec(@NotNull Path sourcePath, @NotNull String source) {
    }
}
