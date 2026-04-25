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
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.ConstructBuiltinInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Narrow fake-build anchors for the former "array constructor lowered to void" investigation.
///
/// Broader end-to-end coverage now lives in `FrontendVoidReturnCallIntegrationTest`; this class stays
/// focused on the former Array-only probe so the original codegen/build regression remains easy to diagnose.
public class FrontendArrayVoidReturnCallRegressionTest {
    @Test
    void localArrayConstructorKeepsArrayTypeAcrossIndexedMutationFlow(@TempDir Path tempDir) throws Exception {
        var lowered = lowerModule(
                tempDir.resolve("local_array_indexed_mutation.gd"),
                """
                        class_name LocalArrayIndexedMutationSmoke
                        extends RefCounted
                        
                        func compute() -> int:
                            var values: Array = Array()
                            values[1] = 6
                            var first: int = values[0]
                            return first
                        """,
                Map.of("LocalArrayIndexedMutationSmoke", "RuntimeLocalArrayIndexedMutationSmoke")
        );

        assertArrayConstructBuiltinResults(lowered.function());
        assertNoEmittedVoidResultSlots(lowered.function());

        var projectInfo = new CProjectInfo(
                "frontend_array_void_return_indexed_regression",
                GodotVersion.V451,
                tempDir.resolve("project_indexed"),
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        assertBuildSucceedsWithArrayConstructor(projectInfo, lowered);
    }

    @Test
    void localArrayConstructorKeepsArrayTypeAcrossDynamicHelperFlowAfterVoidCallResultFix(@TempDir Path tempDir)
            throws Exception {
        var lowered = lowerModule(
                tempDir.resolve("local_array_dynamic_helper.gd"),
                """
                        class_name LocalArrayDynamicHelperSmoke
                        extends RefCounted
                        
                        func dynamic_size(value):
                            return value.size()
                        
                        func compute() -> int:
                            var plain: Array = Array()
                            plain.push_back(1)
                            return dynamic_size(plain)
                        """,
                Map.of("LocalArrayDynamicHelperSmoke", "RuntimeLocalArrayDynamicHelperSmoke")
        );

        assertArrayConstructBuiltinResults(lowered.function());

        var projectInfo = new CProjectInfo(
                "frontend_array_void_return_dynamic_helper_regression",
                GodotVersion.V451,
                tempDir.resolve("project_dynamic"),
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        assertPushBackCallsDiscardResultSlots(lowered.function());
        assertNoEmittedVoidResultSlots(lowered.function());
        assertBuildSucceedsWithArrayConstructor(projectInfo, lowered);
    }

    @Test
    void localArrayConstructorKeepsArrayTypeAcrossDirectPushBackFlowAfterVoidCallResultFix(@TempDir Path tempDir)
            throws Exception {
        var lowered = lowerModule(
                tempDir.resolve("local_array_push_back_only.gd"),
                """
                        class_name LocalArrayPushBackOnlySmoke
                        extends RefCounted
                        
                        func compute() -> int:
                            var plain: Array = Array()
                            plain.push_back(1)
                            return plain.size()
                        """,
                Map.of("LocalArrayPushBackOnlySmoke", "RuntimeLocalArrayPushBackOnlySmoke")
        );

        assertArrayConstructBuiltinResults(lowered.function());

        var projectInfo = new CProjectInfo(
                "frontend_array_void_return_push_back_regression",
                GodotVersion.V451,
                tempDir.resolve("project_push_back"),
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        assertPushBackCallsDiscardResultSlots(lowered.function());
        assertNoEmittedVoidResultSlots(lowered.function());
        assertBuildSucceedsWithArrayConstructor(projectInfo, lowered);
    }

    private static void assertArrayConstructBuiltinResults(@NotNull LirFunctionDef function) {
        var functionName = function.getName();
        var constructBuiltins = allInstructions(function).stream()
                .filter(ConstructBuiltinInsn.class::isInstance)
                .map(ConstructBuiltinInsn.class::cast)
                .toList();

        assertEquals(1, constructBuiltins.size(), () -> "Expected exactly one construct_builtin in " + functionName);

        var constructBuiltin = constructBuiltins.getFirst();
        var resultId = constructBuiltin.resultId();
        assertNotNull(resultId, () -> "construct_builtin result id is missing in " + functionName);
        var resultVar = function.getVariableById(resultId);
        assertNotNull(resultVar, () -> "construct_builtin result variable is missing in " + functionName);
        assertInstanceOf(
                GdArrayType.class,
                resultVar.type(),
                () -> "construct_builtin result should stay Array-typed, but was " + resultVar.type().getTypeName()
        );
    }

    private static void assertPushBackCallsDiscardResultSlots(@NotNull LirFunctionDef function) {
        var pushBackCalls = allInstructions(function).stream()
                .filter(CallMethodInsn.class::isInstance)
                .map(CallMethodInsn.class::cast)
                .filter(insn -> insn.methodName().equals("push_back"))
                .toList();

        assertEquals(1, pushBackCalls.size());
        for (var callInsn : pushBackCalls) {
            assertNull(callInsn.resultId(), () -> "push_back should discard its void result in " + function.getName());
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

    private static void assertBuildSucceedsWithArrayConstructor(
            @NotNull CProjectInfo projectInfo,
            @NotNull RegressionFixture lowered
    ) throws IOException {
        var buildResult = buildWithFakeCompiler(projectInfo, lowered);
        var entrySource = Files.readString(projectInfo.projectPath().resolve("entry.c"));
        assertTrue(buildResult.success(), buildResult::buildLog);
        assertTrue(entrySource.contains("godot_new_Array()"), entrySource);
        assertFalse(entrySource.contains("Builtin constructor validation failed: 'void'"), entrySource);
    }

    private static @NotNull RegressionFixture lowerModule(
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
        var module = new FrontendModule(
                "frontend_array_void_return_call_regression",
                List.of(unit),
                topLevelCanonicalNameMap
        );
        var lowered = new FrontendLoweringPassManager().lower(module, classRegistry, diagnostics);

        assertNotNull(lowered, () -> "Lowering returned null with diagnostics: " + diagnostics.snapshot());
        assertFalse(diagnostics.hasErrors(), () -> "Unexpected frontend diagnostics: " + diagnostics.snapshot());
        assertEquals(1, lowered.getClassDefs().size());

        var lirClass = lowered.getClassDefs().getFirst();
        var function = requireComputeFunction(lirClass);
        return new RegressionFixture(lowered, classRegistry, lirClass, function);
    }

    private static @NotNull CBuildResult buildWithFakeCompiler(
            @NotNull CProjectInfo projectInfo,
            @NotNull RegressionFixture lowered
    ) throws IOException {
        var fakeCompiler = new CCompiler() {
            @Override
            public CCompileResult compile(
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

        Files.createDirectories(projectInfo.projectPath());
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, lowered.classRegistry()), lowered.module());
        return new CProjectBuilder(fakeCompiler).buildProject(projectInfo, codegen);
    }

    private static @NotNull LirFunctionDef requireComputeFunction(@NotNull LirClassDef lirClass) {
        return lirClass.getFunctions().stream()
                .filter(function -> function.getName().equals("compute"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing function compute in " + lirClass.getName()));
    }

    private static @NotNull List<LirInstruction> allInstructions(@NotNull LirFunctionDef function) {
        var instructions = new ArrayList<LirInstruction>();
        for (var basicBlock : function) {
            instructions.addAll(basicBlock.getInstructions());
        }
        return List.copyOf(instructions);
    }

    private record RegressionFixture(
            @NotNull dev.superice.gdcc.lir.LirModule module,
            @NotNull ClassRegistry classRegistry,
            @NotNull LirClassDef lirClass,
            @NotNull LirFunctionDef function
    ) {
    }
}
