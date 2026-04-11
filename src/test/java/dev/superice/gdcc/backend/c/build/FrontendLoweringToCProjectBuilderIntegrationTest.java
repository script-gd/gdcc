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
                                "RuntimeBuiltinPropertyAbiProbe_color_r\\s*\\(\\s*RuntimeBuiltinPropertyAbiProbe\\* \\$self\\s*,\\s*godot_Color\\* \\$color\\s*\\)",
                                Pattern.DOTALL
                        )
                        .matcher(entryHeader)
                        .find(),
                () -> "Color parameter getter should use pointer ABI in generated signature.\nHeader:\n" + entryHeader
        );
        assertTrue(
                Pattern.compile(
                                "RuntimeBuiltinPropertyAbiProbe_vector_x\\s*\\(\\s*RuntimeBuiltinPropertyAbiProbe\\* \\$self\\s*,\\s*godot_Vector3\\* \\$vector\\s*\\)",
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

    private record SourceFileSpec(@NotNull Path sourcePath, @NotNull String source) {
    }
}
