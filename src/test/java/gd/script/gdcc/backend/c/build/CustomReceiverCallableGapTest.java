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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CustomReceiverCallableGapTest {
    /// Minimal shape: an inner custom class with a method, converted to a Callable.
    /// `probe_immediate` calls it while the receiver local is alive; `arm`/`fire` split the
    /// creation and the call across scopes so only the Callable could retain the receiver.
    private static @NotNull String gapSource(@NotNull String callableExpression) {
        return """
                class_name CallableGapProbe
                extends Node

                var _pending: Callable

                class Token extends RefCounted:
                    var count: int = 0

                    func bump() -> int:
                        count += 1
                        return count

                func probe_immediate() -> int:
                    var token: Token = Token.new()
                    var cb: Callable = %s
                    var result: Variant = cb.call()
                    if result is int:
                        return int(result)
                    return -1

                func arm() -> void:
                    var token: Token = Token.new()
                    _pending = %s
                    # `token` has no strong owner after this point; only the Callable could
                    # retain the receiver.

                func fire() -> int:
                    var result: Variant = _pending.call()
                    if result is int:
                        return int(result)
                    return -1
                """.formatted(callableExpression, callableExpression);
    }

    @Test
    void methodReferenceOnCustomInstanceDoesNotRetainReceiver() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping custom-receiver Callable runtime probe");
            return;
        }

        var tempDir = Path.of("tmp/test/custom_receiver_callable_sugar_gap");
        Files.createDirectories(tempDir);

        // The frontend accepts the sugar with zero diagnostics — the gap is purely backend.
        var lowered = lowerModule(
                "custom_receiver_callable_sugar_gap",
                tempDir.resolve("callable_gap_probe.gd"),
                gapSource("token.bump"),
                Map.of("CallableGapProbe", "RuntimeCallableGapProbe")
        );

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(tempDir.resolve("project"));
        var projectInfo = new CProjectInfo(
                "custom_receiver_callable_sugar_gap",
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
                            var immediate := int(target.call("probe_immediate"))
                            if immediate == 1:
                                print("callable sugar same-scope check passed.")
                            else:
                                print("callable sugar same-scope UNEXPECTEDLY broken: result=%d" % immediate)
                            target.call("arm")
                            var deferred := int(target.call("fire"))
                            if deferred == 1:
                                print("callable sugar cross-scope check passed.")
                            else:
                                print("callable sugar cross-scope gap reproduced: result=%d" % deferred)
                        """)
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();
        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit the stop signal.\nOutput:\n" + combinedOutput
        );
        // Control: the same-scope call must work — the Callable is created correctly.
        assertTrue(
                combinedOutput.contains("callable sugar same-scope check passed."),
                () -> "The same-scope control should pass; something else broke.\nOutput:\n" + combinedOutput
        );
        // Characterization of the gap: once the creating scope returned, the receiver is
        // gone and the deferred call fails (probe returns -1). A backend fix must flip this
        // to "cross-scope check passed" and update this test.
        assertTrue(
                combinedOutput.contains("callable sugar cross-scope gap reproduced: result=-1"),
                () -> "Expected the receiver-retention gap to reproduce (fire result -1).\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("callable sugar cross-scope check passed."),
                () -> "The retention gap unexpectedly disappeared; update this characterization test.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void explicitCallableConstructionFromCustomInstanceFailsCodegen() throws Exception {
        var tempDir = Path.of("tmp/test/custom_receiver_callable_explicit_gap");
        Files.createDirectories(tempDir);

        // The frontend accepts the explicit construction (clean lowering) — the rejection is
        // purely a C-backend boundary.
        var lowered = lowerModule(
                "custom_receiver_callable_explicit_gap",
                tempDir.resolve("callable_gap_probe.gd"),
                gapSource("Callable(token, &\"bump\")"),
                Map.of("CallableGapProbe", "RuntimeCallableGapProbe")
        );

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "custom_receiver_callable_explicit_gap",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, lowered.classRegistry()), lowered.module());
        // Generation happens while the builder writes sources; a fake compiler keeps the
        // failure on the codegen side (no Zig needed). The builder wraps the codegen
        // InvalidInsnException, so the ExtensionBuiltinClass message lives in the cause chain.
        var thrown = assertThrows(
                RuntimeException.class,
                () -> new CProjectBuilder(fakeCompiler()).buildProject(projectInfo, codegen),
                "Expected codegen to reject Callable(customInstance, &\"bump\")"
        );
        var chain = new StringBuilder();
        for (Throwable cursor = thrown; cursor != null; cursor = cursor.getCause()) {
            chain.append(cursor.getMessage()).append('\n');
        }
        assertTrue(
                chain.toString().contains("'Callable' with args [RuntimeCallableGapProbe__sub__Token, StringName]"
                        + " is not defined in ExtensionBuiltinClass"),
                () -> "Unexpected failure chain:\n" + chain
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
