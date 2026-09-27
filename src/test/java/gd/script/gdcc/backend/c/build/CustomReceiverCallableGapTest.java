package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringPassManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirModule;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Boundary anchors for custom-receiver Callables, originally written as minimal
/// reproductions of two gaps found while building per-window reply tokens for the editor
/// addon (see `doc/module_impl/editor_addon/gd3_editor_integration_implementation.md` §5):
///
///   * GAP A (receiver retention) is PARITY with the official interpreter, not a bug:
///     standard Callables store only `(ObjectID, StringName)` and never retain the receiver
///     (probed on Godot 4.5.1 with isolated receivers; only lambdas retain, via
///     `GDScriptLambdaSelfCallable`). Our compiled `token.bump` matches exactly: works in
///     the creating scope, fails cross-scope with
///     `Attempt to call function 'null::bump (Callable)' on a null instance`.
///     Do NOT "fix" retention — parity is the goal.
///   * GAP B (explicit `Callable(token, &"bump")` rejected by the C backend's
///     ExtensionBuiltinClass constructor table) was FIXED by PR #83 (object-upcast
///     materialization at fixed call arguments). These tests now pin the acceptance and the
///     runtime parity of the explicit form.
public class CustomReceiverCallableGapTest {
    /// Minimal shape: an inner custom class with a method, converted to a Callable via both
    /// the method-reference sugar and the explicit constructor. `*_immediate` calls happen
    /// while the receiver local is alive; `arm_*`/`fire_*` split creation and call across
    /// scopes so only the Callable could retain the receiver (upstream: it does not).
    private static final String PROBE_SOURCE = """
            class_name CallableGapProbe
            extends Node

            var _pending_sugar: Callable
            var _pending_explicit: Callable

            class Token extends RefCounted:
                var count: int = 0

                func bump() -> int:
                    count += 1
                    return count

            func _call_or_negative(cb: Callable) -> int:
                var result: Variant = cb.call()
                if result is int:
                    return int(result)
                return -1

            func probe_sugar_immediate() -> int:
                var token: Token = Token.new()
                return _call_or_negative(token.bump)

            func probe_explicit_immediate() -> int:
                var token: Token = Token.new()
                return _call_or_negative(Callable(token, &"bump"))

            func arm_sugar() -> void:
                var token: Token = Token.new()
                _pending_sugar = token.bump
                # `token` has no strong owner after this point; only the Callable could
                # retain the receiver (upstream semantics: it does not).

            func fire_sugar() -> int:
                return _call_or_negative(_pending_sugar)

            func arm_explicit() -> void:
                var token: Token = Token.new()
                _pending_explicit = Callable(token, &"bump")

            func fire_explicit() -> int:
                return _call_or_negative(_pending_explicit)
            """;

    @Test
    void customReceiverCallablesMatchOfficialRuntimeSemantics() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping custom-receiver Callable runtime probe");
            return;
        }

        var tempDir = Path.of("tmp/test/custom_receiver_callable_runtime");
        Files.createDirectories(tempDir);

        var lowered = lowerModule(
                "custom_receiver_callable_runtime",
                tempDir.resolve("callable_gap_probe.gd"),
                PROBE_SOURCE,
                Map.of("CallableGapProbe", "RuntimeCallableGapProbe")
        );

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "custom_receiver_callable_runtime",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, lowered.classRegistry()), lowered.module());
        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "CallableGapNode",
                        "RuntimeCallableGapProbe",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec("""
                        extends Node

                        func _ready() -> void:
                            var target = get_parent().get_node_or_null("CallableGapNode")
                            if target == null:
                                push_error("Target node missing.")
                                return
                            _probe(target, "probe_sugar_immediate", "sugar same-scope")
                            _probe(target, "probe_explicit_immediate", "explicit same-scope")
                            target.call("arm_sugar")
                            _probe(target, "fire_sugar", "sugar cross-scope")
                            target.call("arm_explicit")
                            _probe(target, "fire_explicit", "explicit cross-scope")

                        func _probe(target: Object, method: String, label: String) -> void:
                            var result := int(target.call(method))
                            if result == 1:
                                print("callable %s check passed." % label)
                            else:
                                print("callable %s gap reproduced: result=%d" % [label, result])
                        """)
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();
        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit the stop signal.\nOutput:\n" + combinedOutput
        );
        // Controls: both creation forms must work while the receiver is in scope.
        for (var label : List.of("sugar same-scope", "explicit same-scope")) {
            assertTrue(
                    combinedOutput.contains("callable " + label + " check passed."),
                    () -> "The " + label + " control should pass; something else broke.\nOutput:\n" + combinedOutput
            );
        }
        // Parity anchors: cross-scope, neither form retains the receiver (official 4.5.1
        // semantics) — both probes return -1 after the dead-receiver error.
        for (var label : List.of("sugar cross-scope", "explicit cross-scope")) {
            assertTrue(
                    combinedOutput.contains("callable " + label + " gap reproduced: result=-1"),
                    () -> "Expected the " + label + " null-receiver parity to reproduce.\nOutput:\n" + combinedOutput
            );
            assertFalse(
                    combinedOutput.contains("callable " + label + " check passed."),
                    () -> "The " + label + " probe unexpectedly retained the receiver — upstream diverged "
                            + "or the backend grew retention; re-probe the official engine before changing this.\nOutput:\n"
                            + combinedOutput
            );
        }
    }

    /// GAP B fix anchor (PR #83): the explicit `Callable(Object, StringName)` constructor
    /// with a custom-class receiver lowers cleanly and passes C code generation. A fake
    /// compiler keeps this off the Zig path.
    @Test
    void explicitCallableConstructionFromCustomInstanceBuilds() throws Exception {
        var tempDir = Path.of("tmp/test/custom_receiver_callable_explicit_build");
        Files.createDirectories(tempDir);

        var lowered = lowerModule(
                "custom_receiver_callable_explicit_build",
                tempDir.resolve("callable_gap_probe.gd"),
                PROBE_SOURCE,
                Map.of("CallableGapProbe", "RuntimeCallableGapProbe")
        );

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "custom_receiver_callable_explicit_build",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, lowered.classRegistry()), lowered.module());
        var buildResult = new CProjectBuilder(fakeCompiler()).buildProject(projectInfo, codegen);
        assertTrue(
                buildResult.success(),
                () -> "Callable(customInstance, &\"bump\") should pass codegen since PR #83. Log:\n"
                        + buildResult.buildLog()
        );
        var entrySource = Files.readString(projectDir.resolve("entry.c"));
        assertFalse(
                entrySource.isEmpty(),
                "Generated entry.c should exist after a successful build"
        );
    }

    private static @NotNull CCompiler fakeCompiler() {
        return new CCompiler() {
            @Override
            public @NotNull CCompileResult compile(
                    @NotNull Path projectDir,
                    @NotNull List<Path> includeDirs,
                    @NotNull List<Path> cFiles,
                    @NotNull String outputBaseName,
                    @NotNull COptimizationLevel optimizationLevel,
                    @NotNull TargetPlatform targetPlatform
            ) throws IOException {
                var out = projectDir.resolve(outputBaseName + ".dll");
                Files.createDirectories(projectDir);
                Files.writeString(out, "dummy");
                return new CCompileResult(true, "ok", List.of(out));
            }
        };
    }

    private static @NotNull LoweredFixture lowerModule(
            @NotNull String moduleName,
            @NotNull Path sourcePath,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws IOException {
        var parser = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parser.parseUnit(sourcePath, source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());

        var diagnostics = new DiagnosticManager();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadVersion(GodotVersion.V451));
        var module = new FrontendModule(moduleName, List.of(unit), topLevelCanonicalNameMap);
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());

        // The inner class lowers to its own class def, so locate the top-level class by name.
        var topLevel = lowered.getClassDefs().stream()
                .filter(def -> def.getName().equals("RuntimeCallableGapProbe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing top-level class def in " + lowered.getClassDefs()));

        return new LoweredFixture(lowered, classRegistry, topLevel);
    }

    private record LoweredFixture(
            @NotNull LirModule module,
            @NotNull ClassRegistry classRegistry,
            @NotNull LirClassDef lirClass
    ) {
    }
}
