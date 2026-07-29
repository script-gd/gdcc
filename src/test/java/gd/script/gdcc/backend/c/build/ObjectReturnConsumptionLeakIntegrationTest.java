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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Engine integration test anchoring that OWNED object returns are consumed without reference-count
/// leaks across every consumer path: slot write, discard, and public-wrapper return.
///
/// `RefCounted.new()` is an exact constructor route producing an OWNED object result, so it funnels
/// through the same balancing code (`emitObjectSlotWrite(..., OWNED)` / `emitDiscardedCall` / wrapper
/// consume stmt) as the exact-engine and vararg dynamic-call return paths. Each consumer must leave
/// exactly one owning reference where expected and release the producer's temporary reference.
public class ObjectReturnConsumptionLeakIntegrationTest {
    @Test
    void objectReturnConsumptionPathsShouldNotLeakInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping object return consumption leak integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/object_return_consumption_leak");
        Files.createDirectories(tempDir);

        var source = """
                class_name ObjectReturnLeakSmoke
                extends Node
                
                var held: RefCounted
                var captured: RefCounted
                
                func _init() -> void:
                    held = RefCounted.new()
                
                func slot_write_capture() -> void:
                    var local_ref: RefCounted = RefCounted.new()
                    captured = local_ref
                
                func captured_ref_count() -> int:
                    return captured.get_reference_count()
                
                func return_new_ref() -> RefCounted:
                    return RefCounted.new()
                
                func return_new_ref_as_object() -> Object:
                    return RefCounted.new()
                
                func return_held_field() -> RefCounted:
                    return held
                
                func echo_object(o: Object) -> Object:
                    return o
                
                func discard_new_ref() -> void:
                    RefCounted.new()
                """;

        var module = parseModule(
                "object_return_consumption_leak_module",
                List.of(new SourceFileSpec(tempDir.resolve("object_return_leak_smoke.gd"), source)),
                Map.of("ObjectReturnLeakSmoke", "RuntimeObjectReturnLeakSmoke")
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "object_return_consumption_leak",
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
                        "ObjectReturnLeakNode",
                        "RuntimeObjectReturnLeakSmoke",
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
                combinedOutput.contains("object return slot write refcount check passed."),
                () -> "Slot-write consumer should leave exactly the field reference after exit.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("object return wrapper consume fresh refcount check passed."),
                () -> "Wrapper consume of a fresh object should hand the caller exactly one reference.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("object return wrapper consume Object-typed fresh refcount check passed."),
                () -> "Wrapper consume of a fresh Object-typed RC return should hand the caller exactly one reference.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("object return wrapper consume borrowed refcount check passed."),
                () -> "Wrapper consume of a borrowed field should hand the caller exactly two references.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("object return wrapper consume Object-typed borrowed refcount check passed."),
                () -> "Wrapper consume of a borrowed Object return should hand the caller field+temp references.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("object return discard refcount check passed."),
                () -> "Discard consumer should release the producer reference cleanly under stress.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("check failed."),
                () -> "Object return consumption output should not include failure markers.\nOutput:\n" + combinedOutput
        );
    }

    private record SourceFileSpec(@NotNull Path sourcePath, @NotNull String source) {
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
                
                const TARGET_NODE_NAME = "ObjectReturnLeakNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    # Slot write: a fresh object is stored into a local slot then captured into a field.
                    # After the function returns, the local slot is released in __finally__, leaving only
                    # the field reference. Reading it back through the field getter adds one temporary
                    # reference, so a balanced (no-leak) slot write measures exactly 2 (field + getter
                    # temp); a leaked local would push it to 3, an over-release would drop it to 1/null.
                    target.call("slot_write_capture")
                    var captured_count = int(target.call("captured_ref_count"))
                    if captured_count == 2:
                        print("object return slot write refcount check passed.")
                    else:
                        push_error("object return slot write refcount check failed: count=" + str(captured_count))
                
                    # Wrapper consume of a fresh move-returned object: GDScript must receive exactly one
                    # reference (the wrapper packing transfers ownership net-zero, no extra release).
                    var returned = target.call("return_new_ref")
                    var returned_count = int(returned.get_reference_count()) if returned != null else -1
                    if returned_count == 1:
                        print("object return wrapper consume fresh refcount check passed.")
                    else:
                        push_error("object return wrapper consume fresh refcount check failed: count=" + str(returned_count))
                
                    # Same producer, static return type Object (UNKNOWN -> try_release after pack).
                    var returned_as_object = target.call("return_new_ref_as_object")
                    var returned_as_object_count = int(returned_as_object.get_reference_count()) if returned_as_object != null else -1
                    if returned_as_object_count == 1:
                        print("object return wrapper consume Object-typed fresh refcount check passed.")
                    else:
                        push_error("object return wrapper consume Object-typed fresh refcount check failed: count=" + str(returned_as_object_count))
                
                    # Wrapper consume of a borrowed field: GDScript receives one reference plus the field's
                    # existing reference, so the count must be exactly 2.
                    var held_out = target.call("return_held_field")
                    var held_count = int(held_out.get_reference_count()) if held_out != null else -1
                    if held_count == 2:
                        print("object return wrapper consume borrowed refcount check passed.")
                    else:
                        push_error("object return wrapper consume borrowed refcount check failed: count=" + str(held_count))
                
                    # BORROWED Object return: body try_own + wrapper try_release must balance.
                    # Local `inp` stays live across the call (like the field in return_held_field),
                    # so a balanced path leaves exactly inp + returned = 2.
                    # Missing body try_own + still try_release would under-count / UAF;
                    # missing wrapper try_release would leave 3.
                    var inp = RefCounted.new()
                    var echoed = target.call("echo_object", inp)
                    var echoed_count = int(echoed.get_reference_count()) if echoed != null else -1
                    if echoed_count == 2:
                        print("object return wrapper consume Object-typed borrowed refcount check passed.")
                    else:
                        push_error("object return wrapper consume Object-typed borrowed refcount check failed: count=" + str(echoed_count))
                
                    # Discard: a missing release would leak one RefCounted per call; stress the path and
                    # rely on a clean run plus Godot's leaked-instance reporting at shutdown.
                    for i in range(5000):
                        target.call("discard_new_ref")
                    print("object return discard refcount check passed.")
                """;
    }
}
