package dev.superice.gdcc.backend.c.build;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.gen.CCodegen;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPassManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.insn.CallGlobalInsn;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.CallStaticMethodInsn;
import dev.superice.gdcc.lir.insn.ConstructObjectInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// End-to-end anchors for discarded void-return call contracts beyond the former Array-only probe.
/// This keeps the runtime-visible surface aligned across frontend lowering, LIR, backend build, and Godot execution.
public class FrontendVoidReturnCallIntegrationTest {
    @Test
    void discardedVoidReturnCallEdgesBuildNativeLibraryAndRunInGodot() throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping void-return call edge integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/frontend_void_return_call_edges_runtime");
        Files.createDirectories(tempDir);

        var lowered = lowerModule(
                "frontend_void_return_call_edges",
                tempDir.resolve("void_return_call_edge_smoke.gd"),
                """
                        class_name VoidReturnCallEdgeSmoke
                        extends Node
                        
                        var payload: Array
                        
                        func set_payload(value: Array) -> void:
                            payload = value
                        
                        func exercise(seed: int) -> int:
                            print(seed)
                            var local: Array = Array()
                            local.push_back(seed)
                            payload.push_back(seed + 10)
                            return local.size() * 100 + payload.size() * 10
                        
                        func payload_size() -> int:
                            return payload.size()
                        
                        func payload_last_matches(expected: int) -> bool:
                            return payload[payload.size() - 1] == expected
                        
                        func fresh_worker_class() -> String:
                            var worker: Node = Node.new()
                            return worker.get_class()
                        """,
                Map.of("VoidReturnCallEdgeSmoke", "RuntimeVoidReturnCallEdgeSmoke")
        );
        var exercise = requireFunction(lowered.lirClass(), "exercise");
        var freshWorkerClass = requireFunction(lowered.lirClass(), "fresh_worker_class");

        assertDiscardedGlobalCalls(exercise, "print", 1);
        assertDiscardedMethodCalls(exercise, "push_back", 2);
        assertNoEmittedVoidResultSlots(exercise);
        assertNodeConstructorShape(freshWorkerClass, 1);

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_void_return_call_edges_runtime",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = prepareCodegen(projectInfo, lowered);
        var buildResult = new CProjectBuilder().buildProject(projectInfo, codegen);
        var entrySource = Files.readString(projectDir.resolve("entry.c"));

        assertTrue(buildResult.success(), () -> "Native build should succeed. Build log:\n" + buildResult.buildLog());
        assertTrue(entrySource.contains("GD_STATIC_SN(u8\"RuntimeVoidReturnCallEdgeSmoke\")"), entrySource);
        assertTrue(entrySource.contains("godot_new_Node()"), entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "VoidReturnCallEdgeNode",
                        "RuntimeVoidReturnCallEdgeSmoke",
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(voidReturnCallEdgeTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(
                runResult.stopSignalSeen(),
                () -> "Godot run should emit \"" + GodotGdextensionTestRunner.TEST_STOP_SIGNAL + "\".\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend void-return local/global contract check passed."),
                () -> "Void-return local/global runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend void-return writable-route writeback check passed."),
                () -> "Void-return writable-route runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend void-return constructor boundary check passed."),
                () -> "Void-return constructor-boundary runtime check should pass.\nOutput:\n" + combinedOutput
        );
        assertTrue(
                combinedOutput.contains("frontend void-return runtime class check passed."),
                () -> "Void-return runtime class check should pass.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend void-return local/global contract check failed."),
                () -> "Void-return local/global runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend void-return writable-route writeback check failed."),
                () -> "Void-return writable-route runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend void-return constructor boundary check failed."),
                () -> "Void-return constructor-boundary runtime check should not fail.\nOutput:\n" + combinedOutput
        );
        assertFalse(
                combinedOutput.contains("frontend void-return runtime class check failed."),
                () -> "Void-return runtime class check should not fail.\nOutput:\n" + combinedOutput
        );
    }

    @Test
    void staticVoidReturnTypeMetaHeadStillFailsAtUnsupportedBackendOpcode(@TempDir Path tempDir) throws Exception {
        var lowered = lowerModule(
                "frontend_static_void_return_type_meta_head",
                tempDir.resolve("static_void_return_type_meta_head.gd"),
                """
                        class_name StaticVoidReturnTypeMetaHeadSmoke
                        extends Node
                        
                        func probe() -> int:
                            Node.print_orphan_nodes()
                            return 1
                        """,
                Map.of("StaticVoidReturnTypeMetaHeadSmoke", "RuntimeStaticVoidReturnTypeMetaHeadSmoke")
        );
        var probe = requireFunction(lowered.lirClass(), "probe");

        assertDiscardedStaticCalls(probe, "Node", "print_orphan_nodes", 1);
        assertNoEmittedVoidResultSlots(probe);

        var projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo(
                "frontend_static_void_return_type_meta_head",
                GodotVersion.V451,
                projectDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var codegen = prepareCodegen(projectInfo, lowered);

        var exception = assertThrows(
                RuntimeException.class,
                () -> new CProjectBuilder(fakeCompiler()).buildProject(projectInfo, codegen)
        );
        assertTrue(
                containsMessageInCauseChain(exception, "Unsupported instruction opcode: call_static_method"),
                () -> renderCauseChain(exception)
        );
    }

    private static void assertDiscardedGlobalCalls(
            @NotNull LirFunctionDef function,
            @NotNull String callableName,
            int expectedCount
    ) {
        var calls = allInstructions(function).stream()
                .filter(CallGlobalInsn.class::isInstance)
                .map(CallGlobalInsn.class::cast)
                .filter(insn -> insn.functionName().equals(callableName))
                .toList();

        assertEquals(expectedCount, calls.size(), () -> "Unexpected global call count in " + function.getName());
        for (var call : calls) {
            assertNull(call.resultId(), () -> callableName + " should discard its void result in " + function.getName());
        }
    }

    private static void assertDiscardedMethodCalls(
            @NotNull LirFunctionDef function,
            @NotNull String methodName,
            int expectedCount
    ) {
        var calls = allInstructions(function).stream()
                .filter(CallMethodInsn.class::isInstance)
                .map(CallMethodInsn.class::cast)
                .filter(insn -> insn.methodName().equals(methodName))
                .toList();

        assertEquals(expectedCount, calls.size(), () -> "Unexpected method call count in " + function.getName());
        for (var call : calls) {
            assertNull(call.resultId(), () -> methodName + " should discard its void result in " + function.getName());
        }
    }

    private static void assertDiscardedStaticCalls(
            @NotNull LirFunctionDef function,
            @NotNull String className,
            @NotNull String methodName,
            int expectedCount
    ) {
        var calls = allInstructions(function).stream()
                .filter(CallStaticMethodInsn.class::isInstance)
                .map(CallStaticMethodInsn.class::cast)
                .filter(insn -> insn.className().equals(className) && insn.methodName().equals(methodName))
                .toList();

        assertEquals(expectedCount, calls.size(), () -> "Unexpected static call count in " + function.getName());
        for (var call : calls) {
            assertNull(call.resultId(), () -> methodName + " should discard its void result in " + function.getName());
        }
    }

    private static void assertNodeConstructorShape(@NotNull LirFunctionDef function, int expectedCount) {
        var constructors = allInstructions(function).stream()
                .filter(ConstructObjectInsn.class::isInstance)
                .map(ConstructObjectInsn.class::cast)
                .filter(insn -> insn.className().equals("Node"))
                .toList();

        assertEquals(expectedCount, constructors.size(), () -> "Unexpected Node constructor count in " + function.getName());
        for (var constructor : constructors) {
            assertNotNull(constructor.resultId(), () -> "Node constructor result id is missing in " + function.getName());
            var resultVar = function.getVariableById(constructor.resultId());
            assertNotNull(resultVar, () -> "Node constructor result variable is missing in " + function.getName());
            var objectType = assertInstanceOf(GdObjectType.class, resultVar.type());
            assertEquals("Node", objectType.className(), () -> "Node constructor result type drifted in " + function.getName());
        }
    }

    private static void assertNoEmittedVoidResultSlots(@NotNull LirFunctionDef function) {
        var voidResultIds = allInstructions(function).stream()
                .map(LirInstruction::resultId)
                .filter(java.util.Objects::nonNull)
                .filter(resultId -> {
                    var variable = function.getVariableById(resultId);
                    return variable != null && variable.type() instanceof GdVoidType;
                })
                .toList();
        assertEquals(0, voidResultIds.size(), () -> "Unexpected emitted void result slots: " + voidResultIds);
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
        assertEquals(1, lowered.getClassDefs().size());

        return new LoweredFixture(lowered, classRegistry, lowered.getClassDefs().getFirst());
    }

    private static @NotNull CCodegen prepareCodegen(
            @NotNull CProjectInfo projectInfo,
            @NotNull LoweredFixture lowered
    ) {
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, lowered.classRegistry()), lowered.module());
        return codegen;
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

    private static @NotNull LirFunctionDef requireFunction(
            @NotNull LirClassDef lirClass,
            @NotNull String functionName
    ) {
        return lirClass.getFunctions().stream()
                .filter(function -> function.getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing function " + functionName + " in " + lirClass.getName()));
    }

    private static @NotNull List<LirInstruction> allInstructions(@NotNull LirFunctionDef function) {
        var instructions = new ArrayList<LirInstruction>();
        for (var basicBlock : function) {
            instructions.addAll(basicBlock.getInstructions());
        }
        return instructions;
    }

    private static boolean containsMessageInCauseChain(@NotNull Throwable throwable, @NotNull String expectedFragment) {
        for (var current = throwable; current != null; current = current.getCause()) {
            var message = current.getMessage();
            if (message != null && message.contains(expectedFragment)) {
                return true;
            }
        }
        return false;
    }

    private static @NotNull String renderCauseChain(@NotNull Throwable throwable) {
        var lines = new ArrayList<String>();
        for (var current = throwable; current != null; current = current.getCause()) {
            lines.add(current.getClass().getSimpleName() + ": " + current.getMessage());
        }
        return String.join("\ncaused by: ", lines);
    }

    private static @NotNull String voidReturnCallEdgeTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "VoidReturnCallEdgeNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    target.call("set_payload", [])
                
                    var first = int(target.call("exercise", 4))
                    if first == 110:
                        print("frontend void-return local/global contract check passed.")
                    else:
                        push_error("frontend void-return local/global contract check failed: first=%d" % first)
                
                    var first_size = int(target.call("payload_size"))
                    var first_last_matches = bool(target.call("payload_last_matches", 14))
                    var second = int(target.call("exercise", 5))
                    var second_size = int(target.call("payload_size"))
                    var second_last_matches = bool(target.call("payload_last_matches", 15))
                    if first_size == 1 and first_last_matches and second == 120 and second_size == 2 and second_last_matches:
                        print("frontend void-return writable-route writeback check passed.")
                    else:
                        push_error(
                            "frontend void-return writable-route writeback check failed: first_size=%d first_last_matches=%s second=%d second_size=%d second_last_matches=%s"
                            % [first_size, str(first_last_matches), second, second_size, str(second_last_matches)]
                        )
                
                    var worker_class = String(target.call("fresh_worker_class"))
                    if worker_class == "Node":
                        print("frontend void-return constructor boundary check passed.")
                    else:
                        push_error("frontend void-return constructor boundary check failed.")
                
                    var runtime_class = String(target.get_class())
                    if runtime_class == "RuntimeVoidReturnCallEdgeSmoke" and target.is_class("RuntimeVoidReturnCallEdgeSmoke") and not target.is_class("VoidReturnCallEdgeSmoke"):
                        print("frontend void-return runtime class check passed.")
                    else:
                        push_error("frontend void-return runtime class check failed.")
                """;
    }

    private record LoweredFixture(
            @NotNull dev.superice.gdcc.lir.LirModule module,
            @NotNull ClassRegistry classRegistry,
            @NotNull LirClassDef lirClass
    ) {
    }
}
