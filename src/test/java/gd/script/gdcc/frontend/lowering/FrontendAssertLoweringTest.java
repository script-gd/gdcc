package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.AssertInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.insn.UnpackVariantInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdStringType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// End-to-end frontend coverage for the compile-ready `assert` statement:
/// compile gate release, CFG `AssertItem` recording, truthiness normalization into a bool slot,
/// the optional String message contract, and the unsupported-position negative boundary.
class FrontendAssertLoweringTest {
    @Test
    void lowersBoolLiteralAssertWithoutMessageOrConversion() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = lowerModule("assert_bool_literal.gd", """
                class_name AssertBoolLiteral
                extends RefCounted
                
                func check() -> void:
                    assert(true)
                """, diagnostics);

        var function = requireFunction(lowered, "check");
        var instructions = allInstructions(function);
        var assertInsn = requireSingleAssert(instructions);

        assertAll(
                () -> assertFalse(diagnostics.hasErrors(), () -> diagnostics.snapshot().toString()),
                () -> assertTrue(diagnostics.snapshot().asList().stream()
                        .noneMatch(diagnostic -> diagnostic.category().equals("sema.compile_check"))),
                () -> assertNull(assertInsn.messageId()),
                // Already-bool conditions are consumed in place: no truthiness conversion.
                () -> assertEquals(GdBoolType.BOOL,
                        function.getVariableById(assertInsn.conditionId()).type()),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void lowersVariantConditionAssertWithUnpackOnly() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = lowerModule("assert_variant_condition.gd", """
                class_name AssertVariantCondition
                extends RefCounted
                
                func check(box: Variant) -> void:
                    assert(box)
                """, diagnostics);

        var function = requireFunction(lowered, "check");
        var instructions = allInstructions(function);
        var assertInsn = requireSingleAssert(instructions);
        var unpackInsn = instructions.stream()
                .filter(UnpackVariantInsn.class::isInstance)
                .map(UnpackVariantInsn.class::cast)
                .toList();

        assertAll(
                () -> assertFalse(diagnostics.hasErrors(), () -> diagnostics.snapshot().toString()),
                () -> assertEquals(1, unpackInsn.size()),
                // Variant truthiness is a single unpack into the bool condition slot.
                () -> assertEquals(assertInsn.conditionId(), unpackInsn.getFirst().resultId()),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(GdBoolType.BOOL,
                        function.getVariableById(assertInsn.conditionId()).type())
        );
    }

    @Test
    void lowersIntConditionAssertWithPackThenUnpack() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = lowerModule("assert_int_condition.gd", """
                class_name AssertIntCondition
                extends RefCounted
                
                func check(count: int) -> void:
                    assert(count)
                """, diagnostics);

        var function = requireFunction(lowered, "check");
        var instructions = allInstructions(function);
        var assertInsn = requireSingleAssert(instructions);
        var packInsn = instructions.stream()
                .filter(PackVariantInsn.class::isInstance)
                .map(PackVariantInsn.class::cast)
                .toList();
        var unpackInsn = instructions.stream()
                .filter(UnpackVariantInsn.class::isInstance)
                .map(UnpackVariantInsn.class::cast)
                .toList();

        assertAll(
                () -> assertFalse(diagnostics.hasErrors(), () -> diagnostics.snapshot().toString()),
                // Non-bool stable types normalize through pack + unpack, exactly like `if count:`.
                () -> assertEquals(1, packInsn.size()),
                () -> assertEquals(1, unpackInsn.size()),
                () -> assertEquals(packInsn.getFirst().resultId(), unpackInsn.getFirst().variantId()),
                () -> assertEquals(assertInsn.conditionId(), unpackInsn.getFirst().resultId()),
                () -> assertEquals(GdBoolType.BOOL,
                        function.getVariableById(assertInsn.conditionId()).type())
        );
    }

    @Test
    void lowersAssertWithStringMessage() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = lowerModule("assert_with_message.gd", """
                class_name AssertWithMessage
                extends RefCounted
                
                func check() -> void:
                    assert(false, "m")
                """, diagnostics);

        var function = requireFunction(lowered, "check");
        var assertInsn = requireSingleAssert(allInstructions(function));

        assertAll(
                () -> assertFalse(diagnostics.hasErrors(), () -> diagnostics.snapshot().toString()),
                () -> assertNotNull(assertInsn.messageId()),
                () -> assertEquals(GdStringType.STRING,
                        function.getVariableById(assertInsn.messageId()).type()),
                () -> assertEquals(GdBoolType.BOOL,
                        function.getVariableById(assertInsn.conditionId()).type())
        );
    }

    @Test
    void lowersShortCircuitConditionAssertReadingMergeSlot() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = lowerModule("assert_short_circuit_condition.gd", """
                class_name AssertShortCircuitCondition
                extends RefCounted
                
                func check(a: bool, b: bool) -> void:
                    assert(a and b)
                """, diagnostics);

        var function = requireFunction(lowered, "check");
        var instructions = allInstructions(function);
        var assertInsn = requireSingleAssert(instructions);

        assertAll(
                () -> assertFalse(diagnostics.hasErrors(), () -> diagnostics.snapshot().toString()),
                // Value-built short-circuit conditions are merge-backed: the assert must consume
                // the real cfg_merge_* slot, not a never-written cfg_tmp_* slot.
                () -> assertTrue(assertInsn.conditionId().startsWith("cfg_merge_"),
                        () -> "condition must read the merge slot: " + assertInsn.conditionId()),
                () -> assertEquals(GdBoolType.BOOL,
                        function.getVariableById(assertInsn.conditionId()).type()),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void lowersTernaryConditionAssertPackingFromMergeSlot() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = lowerModule("assert_ternary_condition.gd", """
                class_name AssertTernaryCondition
                extends RefCounted
                
                func check(flag: bool) -> void:
                    assert(1 if flag else 0)
                """, diagnostics);

        var function = requireFunction(lowered, "check");
        var instructions = allInstructions(function);
        var assertInsn = requireSingleAssert(instructions);
        var packInsn = instructions.stream()
                .filter(PackVariantInsn.class::isInstance)
                .map(PackVariantInsn.class::cast)
                .toList();
        var unpackInsn = instructions.stream()
                .filter(UnpackVariantInsn.class::isInstance)
                .map(UnpackVariantInsn.class::cast)
                .toList();

        assertAll(
                () -> assertFalse(diagnostics.hasErrors(), () -> diagnostics.snapshot().toString()),
                () -> assertEquals(1, packInsn.size()),
                () -> assertEquals(1, unpackInsn.size()),
                // The ternary merge result is int-typed and merge-backed: normalization packs from
                // the cfg_merge_* slot and unpacks into the bool condition slot.
                () -> assertTrue(packInsn.getFirst().valueId().startsWith("cfg_merge_"),
                        () -> "pack source must be the merge slot: " + packInsn.getFirst().valueId()),
                () -> assertEquals(packInsn.getFirst().resultId(), unpackInsn.getFirst().variantId()),
                () -> assertEquals(assertInsn.conditionId(), unpackInsn.getFirst().resultId()),
                () -> assertEquals(GdBoolType.BOOL,
                        function.getVariableById(assertInsn.conditionId()).type())
        );
    }

    @Test
    void rejectsNonStringAssertMessageAtTypeCheck() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = lowerModuleOrNull("assert_bad_message.gd", """
                class_name AssertBadMessage
                extends RefCounted
                
                func check() -> void:
                    assert(1, 123)
                """, diagnostics);

        var typeCheckDiagnostics = diagnostics.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.type_check"))
                .toList();

        assertAll(
                () -> assertNull(lowered, () -> "Lowering must stop on the message type error"),
                () -> assertTrue(diagnostics.hasErrors()),
                () -> assertEquals(1, typeCheckDiagnostics.size(), typeCheckDiagnostics::toString),
                () -> assertTrue(typeCheckDiagnostics.getFirst().message().contains("not assignable to 'String'"),
                        typeCheckDiagnostics.getFirst().message())
        );
    }

    @Test
    void keepsAssertInsideUnsupportedPropertyInitializerLambdaGated() throws Exception {
        // Assert statements inside an unsupported property-initializer lambda never reach the CFG:
        // the upstream lambda-subtree gate remains the diagnostic owner, so this position must not
        // be released into lowering.
        var diagnostics = new DiagnosticManager();
        var lowered = lowerModuleOrNull("assert_property_init_lambda.gd", """
                class_name AssertPropertyInitLambdaGate
                extends Node
                
                var cb = func():
                    assert(true)
                """, diagnostics);

        assertAll(
                () -> assertNull(lowered),
                () -> assertTrue(diagnostics.hasErrors()),
                () -> assertTrue(diagnostics.snapshot().asList().stream().anyMatch(diagnostic ->
                        diagnostic.category().equals("sema.unsupported_expression_route"))),
                () -> assertTrue(diagnostics.snapshot().asList().stream()
                        .noneMatch(diagnostic -> diagnostic.category().equals("sema.compile_check")))
        );
    }

    private static @NotNull LirModule lowerModule(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull DiagnosticManager diagnostics
    ) throws Exception {
        var lowered = lowerModuleOrNull(fileName, source, diagnostics);
        assertNotNull(lowered, () -> "Lowering must succeed: " + diagnostics.snapshot());
        return lowered;
    }

    private static LirModule lowerModuleOrNull(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull DiagnosticManager diagnostics
    ) throws Exception {
        var parseDiagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        var module = new FrontendModule("test_module", List.of(unit), Map.of());
        return new FrontendLoweringPassManager().lower(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
    }

    private static @NotNull LirFunctionDef requireFunction(@NotNull LirModule module, @NotNull String functionName) {
        return module.getClassDefs().stream()
                .flatMap(classDef -> classDef.getFunctions().stream())
                .filter(function -> function.getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing lowered function " + functionName));
    }

    private static @NotNull AssertInsn requireSingleAssert(@NotNull List<LirInstruction> instructions) {
        var asserts = instructions.stream()
                .filter(AssertInsn.class::isInstance)
                .map(AssertInsn.class::cast)
                .toList();
        assertEquals(1, asserts.size(), () -> "Expected exactly one AssertInsn in " + instructions);
        return asserts.getFirst();
    }

    private static @NotNull List<LirInstruction> allInstructions(@NotNull LirFunctionDef function) {
        var instructions = new ArrayList<LirInstruction>();
        for (var block : function) {
            instructions.addAll(block.getInstructions());
        }
        return instructions;
    }

    private static long countInstructions(
            @NotNull List<LirInstruction> instructions,
            @NotNull Class<? extends LirInstruction> instructionType
    ) {
        return instructions.stream().filter(instructionType::isInstance).count();
    }
}
