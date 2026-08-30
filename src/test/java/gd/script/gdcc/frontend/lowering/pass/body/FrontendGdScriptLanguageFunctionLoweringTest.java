package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBodyInsnPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.CallGlobalInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Body-lowering contract for the synthetic GDScript language functions `len`/`char`/`ord`:
/// direct calls lower to `call_global` with the bare function name (backend routing to `gdcc_*`
/// is a backend concern), and arguments whose static type is not Variant-assignable are packed
/// through `pack_variant` before the call.
class FrontendGdScriptLanguageFunctionLoweringTest {
    @Test
    void lenCallPacksStringArgumentIntoVariant() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name LanguageFunctionLenPack
                        extends RefCounted
                        
                        func probe() -> int:
                            return len("abc")
                        """
        );

        var call = requireOnly(lowered, CallGlobalInsn.class);
        var pack = requireOnly(lowered, PackVariantInsn.class);
        assertAll(
                () -> assertEquals("len", call.functionName()),
                () -> assertEquals(1, call.args().size()),
                // The call must consume the packed Variant slot, not the raw String literal.
                () -> assertEquals(pack.resultId(), operandId(call.args().getFirst())),
                () -> assertTrue(
                        indexOf(lowered, pack) < indexOf(lowered, call),
                        "pack_variant must precede the call_global"
                )
        );
    }

    @Test
    void charAndOrdCallsLowerToCallGlobalWithoutPacking() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name LanguageFunctionCharOrd
                        extends RefCounted
                        
                        func probe() -> int:
                            var s: String = char(65)
                            return ord(s)
                        """
        );

        var calls = allInstructions(lowered).stream()
                .filter(CallGlobalInsn.class::isInstance)
                .map(CallGlobalInsn.class::cast)
                .toList();
        assertEquals(2, calls.size(), () -> "expected char + ord call_global pair in " + allInstructions(lowered));
        // Both parameter types (`int`/`String`) are directly assignable, so no pack_variant may
        // appear in the function.
        assertEquals(0, count(lowered, PackVariantInsn.class));
        assertAll(
                () -> assertEquals("char", calls.get(0).functionName()),
                () -> assertEquals("ord", calls.get(1).functionName())
        );
    }

    private static @NotNull LirFunctionDef lowerProbe(@NotNull String source) throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "language_function_lowering.gd"),
                source,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var module = new FrontendModule("test_module", List.of(unit), Map.of());
        // Shared semantic path: body lowering tests only need expression facts, not the compile gate.
        var analysisData = new FrontendSemanticAnalyzer().analyze(module, classRegistry, diagnostics);
        assertFalse(
                diagnostics.hasErrors(),
                () -> "Unexpected semantic errors before body lowering: " + diagnostics.snapshot()
        );

        var context = new FrontendLoweringContext(module, classRegistry, diagnostics);
        context.publishAnalysisData(analysisData);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
        new FrontendLoweringBuildCfgPass().run(context);
        new FrontendLoweringBodyInsnPass().run(context);

        return context.requireFunctionLoweringContexts().stream()
                .filter(candidate -> candidate.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(candidate -> candidate.targetFunction().getName().equals("probe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing executable body context for probe"))
                .targetFunction();
    }

    private static @NotNull String operandId(@NotNull LirInstruction.Operand operand) {
        return assertInstanceOf(LirInstruction.VariableOperand.class, operand).id();
    }

    private static int indexOf(@NotNull LirFunctionDef function, @NotNull LirInstruction instruction) {
        return allInstructions(function).indexOf(instruction);
    }

    private static <T extends LirInstruction> @NotNull T requireOnly(
            @NotNull LirFunctionDef function,
            @NotNull Class<T> type
    ) {
        var matches = allInstructions(function).stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, matches.size(), () -> "Expected exactly one " + type.getSimpleName()
                + " in " + allInstructions(function));
        return matches.getFirst();
    }

    private static int count(@NotNull LirFunctionDef function, @NotNull Class<? extends LirInstruction> type) {
        return (int) allInstructions(function).stream().filter(type::isInstance).count();
    }

    private static @NotNull List<LirInstruction> allInstructions(@NotNull LirFunctionDef function) {
        var instructions = new ArrayList<LirInstruction>();
        for (var block : function) {
            instructions.addAll(block.getInstructions());
        }
        return List.copyOf(instructions);
    }
}
