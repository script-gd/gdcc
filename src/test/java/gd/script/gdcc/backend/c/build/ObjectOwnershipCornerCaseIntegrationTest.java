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

/// Engine integration test anchoring object ownership corner cases end-to-end against a real Godot
/// runtime via `get_reference_count()`.
///
/// Each scenario leaves the object under test in a reachable place (a field, a returned value, or a
/// GDScript local) and the probe asserts its reference count matches the ownership contract. Reading a
/// singly-held field back through a returning getter adds one temporary reference, so a balanced
/// field-held object measures exactly 2 (field + GDScript receiver); a freshly returned object that
/// GDScript solely owns measures exactly 1.
///
/// Note: `is_instance_id_valid(...)` is deliberately NOT used to assert that a displaced object is
/// freed — for RefCounted ids (ObjectDB reference bit set) it proved unreliable in this harness and
/// produced flaky false positives. Reachable-refcount assertions are the stable signal here.
public class ObjectOwnershipCornerCaseIntegrationTest {
    @Test
    void objectOwnershipCornerCasesShouldStayBalancedInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping object ownership corner case integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/object_ownership_corner_case");
        Files.createDirectories(tempDir);

        var source = """
                class_name ObjectCornerCaseSmoke
                extends Node

                var probe: RefCounted

                func read_probe() -> RefCounted:
                    return probe

                # Rebinding x must release the first object; probe ends holding the second alone.
                func local_reassign() -> void:
                    var x: RefCounted = RefCounted.new()
                    probe = x
                    x = RefCounted.new()

                # Self-assignment must keep the object alive (no self-release); probe holds it alone.
                func self_assign() -> void:
                    var x: RefCounted = RefCounted.new()
                    x = x
                    probe = x

                # Assigning null must release the old value; probe holds a fresh object alone.
                func assign_null() -> void:
                    var x: RefCounted = RefCounted.new()
                    probe = x
                    x = null

                # Field overwrite must release the previous value; probe holds the latest alone.
                func field_overwrite() -> void:
                    probe = RefCounted.new()
                    probe = RefCounted.new()

                # Returning a borrowed parameter hands the same object back to the caller.
                func return_param(r: RefCounted) -> RefCounted:
                    return r

                # Loop reassignment must release each previous object; only the last survives in probe.
                func loop_reassign() -> void:
                    var x: RefCounted = null
                    for i in range(64):
                        x = RefCounted.new()
                    probe = x

                # Early return cleans up the live local and returns a fresh object either way.
                func early_return(flag: bool) -> RefCounted:
                    var live_local: RefCounted = RefCounted.new()
                    if flag:
                        return RefCounted.new()
                    return live_local

                # Conditional reassignment must release the displaced object and hold the chosen one.
                func branch_assign(flag: bool) -> void:
                    var x: RefCounted = RefCounted.new()
                    if flag:
                        x = RefCounted.new()
                    probe = x

                func make_worker() -> CornerWorker:
                    return CornerWorker.new()
                """;
        var workerSource = """
                class_name CornerWorker
                extends RefCounted

                var value: int

                func _init() -> void:
                    value = 5

                func read() -> int:
                    return value
                """;

        var module = parseModule(
                "object_ownership_corner_case_module",
                List.of(
                        new SourceFileSpec(tempDir.resolve("object_corner_case_smoke.gd"), source),
                        new SourceFileSpec(tempDir.resolve("corner_worker.gd"), workerSource)
                ),
                Map.of(
                        "ObjectCornerCaseSmoke", "RuntimeObjectCornerCaseSmoke",
                        "CornerWorker", "RuntimeCornerWorker"
                )
        );
        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "object_ownership_corner_case",
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
                        "ObjectCornerCaseNode",
                        "RuntimeObjectCornerCaseSmoke",
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
        assertCornerCasePassed(combinedOutput, "corner local reassign");
        assertCornerCasePassed(combinedOutput, "corner self assign");
        assertCornerCasePassed(combinedOutput, "corner assign null");
        assertCornerCasePassed(combinedOutput, "corner field overwrite");
        assertCornerCasePassed(combinedOutput, "corner loop reassign");
        assertCornerCasePassed(combinedOutput, "corner branch assign");
        assertCornerCasePassed(combinedOutput, "corner early return");
        assertCornerCasePassed(combinedOutput, "corner return param");
        assertCornerCasePassed(combinedOutput, "corner gdcc worker");
        assertFalse(
                combinedOutput.contains("check failed."),
                () -> "Corner case output should not include failure markers.\nOutput:\n" + combinedOutput
        );
    }

    private static void assertCornerCasePassed(@NotNull String combinedOutput, @NotNull String marker) {
        assertTrue(
                combinedOutput.contains(marker + " passed."),
                () -> "Expected \"" + marker + "\" to pass.\nOutput:\n" + combinedOutput
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

                const TARGET_NODE_NAME = "ObjectCornerCaseNode"

                # A singly-held probe field reads back as exactly 2 (field + GDScript receiver).
                func _check_probe(target: Node, marker: String) -> void:
                    var m = target.call("read_probe")
                    var c = int(m.get_reference_count()) if m != null else -1
                    if c == 2:
                        print(marker + " passed.")
                    else:
                        push_error(marker + " check failed: count=" + str(c))

                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return

                    # Local reassignment: probe ends holding the second object alone.
                    target.call("local_reassign")
                    _check_probe(target, "corner local reassign")

                    # Self-assignment: the object survives aliasing; probe holds it alone.
                    target.call("self_assign")
                    _check_probe(target, "corner self assign")

                    # Assign null: the old value is released; probe holds a fresh object alone.
                    target.call("assign_null")
                    _check_probe(target, "corner assign null")

                    # Field overwrite: the previous value is released; probe holds the latest alone.
                    target.call("field_overwrite")
                    _check_probe(target, "corner field overwrite")

                    # Loop reassignment: only the last object survives in probe.
                    target.call("loop_reassign")
                    _check_probe(target, "corner loop reassign")

                    # Conditional reassignment: the chosen object is held alone in probe.
                    target.call("branch_assign", true)
                    _check_probe(target, "corner branch assign")

                    # Early return: both paths hand back a fresh object GDScript solely owns (count 1).
                    var er1 = target.call("early_return", true)
                    var er1c = int(er1.get_reference_count()) if er1 != null else -1
                    var er2 = target.call("early_return", false)
                    var er2c = int(er2.get_reference_count()) if er2 != null else -1
                    if er1c == 1 and er2c == 1:
                        print("corner early return passed.")
                    else:
                        push_error("corner early return check failed: er1=" + str(er1c) + " er2=" + str(er2c))

                    # Return borrowed parameter: the same object is handed back to the caller.
                    var inp = RefCounted.new()
                    var out = target.call("return_param", inp)
                    if out != null and out == inp:
                        print("corner return param passed.")
                    else:
                        push_error("corner return param check failed: same=" + str(out == inp))

                    # GDCC RefCounted subclass: a freshly returned worker is usable and owned.
                    var w = target.call("make_worker")
                    var wv = int(w.call("read")) if w != null else -1
                    var wc = int(w.get_reference_count()) if w != null else -1
                    if wv == 5 and wc >= 1:
                        print("corner gdcc worker passed.")
                    else:
                        push_error("corner gdcc worker check failed: value=" + str(wv) + " count=" + str(wc))
                """;
    }
}
