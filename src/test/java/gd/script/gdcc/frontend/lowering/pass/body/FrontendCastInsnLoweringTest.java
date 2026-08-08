package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendBodyLoweringSupport;
import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.lowering.cfg.item.CastItem;
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
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.BuiltinCastInsn;
import gd.script.gdcc.lir.insn.ObjectCastInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Body-lowering contract for GDScript `value as T`.
///
/// Uses shared {@code analyze(...)} so tests isolate CFG/body lowering without requiring the full
/// compile-only pipeline. Compile-gate allowance for cast is covered by
/// {@link #analyzeForCompileAllowsCastExpression()}.
///
/// Assertions key off the published {@link CastItem} result slot. A following return may emit an
/// extra {@link AssignInsn} for the return value, so tests must not require a whole-function single
/// {@code AssignInsn}.
class FrontendCastInsnLoweringTest {

    @Test
    void lowersExactBuiltinIdentityToAssign() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name CastExactBuiltinIdentity
                        extends RefCounted
                        
                        func probe(value: int) -> int:
                            return value as int
                        """
        );

        var castResultSlot = requireCastResultSlot(lowered);
        var assign = requireInsnWriting(lowered.function(), castResultSlot, AssignInsn.class);
        assertAll(
                () -> assertEquals(castResultSlot, assign.resultId()),
                () -> assertEquals(0, countWriting(lowered.function(), castResultSlot, BuiltinCastInsn.class)),
                () -> assertEquals(0, countWriting(lowered.function(), castResultSlot, PackVariantInsn.class)),
                () -> assertEquals(0, countWriting(lowered.function(), castResultSlot, ObjectCastInsn.class)),
                () -> assertEquals(GdIntType.INT, requireVariableType(lowered.function(), castResultSlot))
        );
    }

    @Test
    void lowersVariantAsVariantToAssign() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name CastVariantAsVariant
                        extends RefCounted
                        
                        func probe(value: Variant) -> Variant:
                            return value as Variant
                        """
        );

        var castResultSlot = requireCastResultSlot(lowered);
        var assign = requireInsnWriting(lowered.function(), castResultSlot, AssignInsn.class);
        assertAll(
                () -> assertEquals(castResultSlot, assign.resultId()),
                () -> assertEquals(0, countWriting(lowered.function(), castResultSlot, PackVariantInsn.class)),
                () -> assertEquals(0, countWriting(lowered.function(), castResultSlot, BuiltinCastInsn.class)),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(lowered.function(), castResultSlot))
        );
    }

    @Test
    void lowersConcreteAsVariantToPackVariant() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name CastConcreteAsVariant
                        extends RefCounted
                        
                        func probe(value: int) -> Variant:
                            return value as Variant
                        """
        );

        var wiring = requireCastWiring(lowered);
        var pack = requireInsnWriting(lowered.function(), wiring.resultSlotId(), PackVariantInsn.class);
        assertAll(
                () -> assertEquals(wiring.resultSlotId(), pack.resultId()),
                () -> assertEquals(wiring.operandSlotId(), pack.valueId()),
                () -> assertEquals(0, countWriting(lowered.function(), wiring.resultSlotId(), BuiltinCastInsn.class)),
                () -> assertEquals(0, countWriting(lowered.function(), wiring.resultSlotId(), AssignInsn.class)),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(lowered.function(), wiring.resultSlotId()))
        );
    }

    @Test
    void lowersHardBuiltinCastToSingleBuiltinCast() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name CastHardBuiltin
                        extends RefCounted
                        
                        func probe(value: int) -> float:
                            return value as float
                        """
        );

        var wiring = requireCastWiring(lowered);
        var cast = requireInsnWriting(lowered.function(), wiring.resultSlotId(), BuiltinCastInsn.class);
        assertAll(
                () -> assertEquals("float", cast.targetTypeName()),
                () -> assertEquals(wiring.resultSlotId(), cast.resultId()),
                () -> assertEquals(wiring.operandSlotId(), cast.valueId()),
                () -> assertEquals(0, countWriting(lowered.function(), wiring.resultSlotId(), PackVariantInsn.class)),
                () -> assertEquals(0, countWriting(lowered.function(), wiring.resultSlotId(), ObjectCastInsn.class)),
                () -> assertEquals(1, count(lowered.function(), BuiltinCastInsn.class)),
                () -> assertEquals(GdFloatType.FLOAT, requireVariableType(lowered.function(), wiring.resultSlotId()))
        );
    }

    @Test
    void lowersVariantAsBuiltinToSingleBuiltinCast() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name CastVariantAsBuiltin
                        extends RefCounted
                        
                        func probe(value: Variant) -> int:
                            return value as int
                        """
        );

        var wiring = requireCastWiring(lowered);
        var cast = requireInsnWriting(lowered.function(), wiring.resultSlotId(), BuiltinCastInsn.class);
        assertAll(
                () -> assertEquals("int", cast.targetTypeName()),
                () -> assertEquals(wiring.operandSlotId(), cast.valueId()),
                () -> assertEquals(1, count(lowered.function(), BuiltinCastInsn.class)),
                () -> assertEquals(0, countWriting(lowered.function(), wiring.resultSlotId(), PackVariantInsn.class)),
                () -> assertEquals(GdIntType.INT, requireVariableType(lowered.function(), wiring.resultSlotId()))
        );
    }

    @Test
    void lowersParameterizedContainerTargetToBuiltinCastWithFullTypeText() throws Exception {
        var genericSource = lowerProbe(
                """
                        class_name CastGenericArrayToTyped
                        extends RefCounted
                        
                        func probe(value: Array) -> Array[int]:
                            return value as Array[int]
                        """
        );
        var genericSlot = requireCastResultSlot(genericSource);
        var genericCast = requireInsnWriting(genericSource.function(), genericSlot, BuiltinCastInsn.class);
        assertEquals("Array[int]", genericCast.targetTypeName());
        var genericResultType = requireVariableType(genericSource.function(), genericSlot);
        assertInstanceOf(GdArrayType.class, genericResultType);
        assertEquals("Array[int]", genericResultType.getTypeName());

        var differentParam = lowerProbe(
                """
                        class_name CastTypedArrayToOtherTyped
                        extends RefCounted
                        
                        func probe(value: Array[String]) -> Array[int]:
                            return value as Array[int]
                        """
        );
        var differentSlot = requireCastResultSlot(differentParam);
        var differentCast = requireInsnWriting(differentParam.function(), differentSlot, BuiltinCastInsn.class);
        assertEquals("Array[int]", differentCast.targetTypeName());

        var variantSource = lowerProbe(
                """
                        class_name CastVariantToTypedArray
                        extends RefCounted
                        
                        func probe(value: Variant) -> Array[int]:
                            return value as Array[int]
                        """
        );
        var variantSlot = requireCastResultSlot(variantSource);
        var variantCast = requireInsnWriting(variantSource.function(), variantSlot, BuiltinCastInsn.class);
        assertEquals("Array[int]", variantCast.targetTypeName());
        assertEquals(0, countWriting(variantSource.function(), variantSlot, PackVariantInsn.class));
    }

    @Test
    void lowersParameterizedArrayAsGenericArrayToAssign() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name CastTypedArrayAsGeneric
                        extends RefCounted
                        
                        func probe(value: Array[int]) -> Array:
                            return value as Array
                        """
        );

        var castResultSlot = requireCastResultSlot(lowered);
        var assign = requireInsnWriting(lowered.function(), castResultSlot, AssignInsn.class);
        assertAll(
                () -> assertEquals(castResultSlot, assign.resultId()),
                () -> assertEquals(0, countWriting(lowered.function(), castResultSlot, BuiltinCastInsn.class)),
                () -> assertEquals(0, countWriting(lowered.function(), castResultSlot, PackVariantInsn.class)),
                () -> assertInstanceOf(GdArrayType.class, requireVariableType(lowered.function(), castResultSlot))
        );
    }

    @Test
    void lowersObjectExactAndUpcastToAssign() throws Exception {
        var exact = lowerProbe(
                """
                        class_name CastObjectExact
                        extends RefCounted
                        
                        func probe(value: Node) -> Node:
                            return value as Node
                        """
        );
        var exactSlot = requireCastResultSlot(exact);
        var exactAssign = requireInsnWriting(exact.function(), exactSlot, AssignInsn.class);
        assertAll(
                () -> assertEquals(exactSlot, exactAssign.resultId()),
                () -> assertEquals(0, countWriting(exact.function(), exactSlot, ObjectCastInsn.class)),
                () -> assertEquals(0, countWriting(exact.function(), exactSlot, BuiltinCastInsn.class)),
                () -> assertEquals(new GdObjectType("Node"), requireVariableType(exact.function(), exactSlot))
        );

        var upcast = lowerProbe(
                """
                        class_name CastObjectUpcast
                        extends RefCounted
                        
                        func probe(value: Node2D) -> Node:
                            return value as Node
                        """
        );
        var upcastSlot = requireCastResultSlot(upcast);
        var upcastAssign = requireInsnWriting(upcast.function(), upcastSlot, AssignInsn.class);
        assertAll(
                () -> assertEquals(upcastSlot, upcastAssign.resultId()),
                () -> assertEquals(0, countWriting(upcast.function(), upcastSlot, ObjectCastInsn.class)),
                () -> assertEquals(new GdObjectType("Node"), requireVariableType(upcast.function(), upcastSlot))
        );
    }

    @Test
    void lowersObjectDowncastAndNilToSingleObjectCast() throws Exception {
        var downcast = lowerProbe(
                """
                        class_name CastObjectDowncast
                        extends RefCounted
                        
                        func probe(value: Node) -> Node2D:
                            return value as Node2D
                        """
        );
        var downcastWiring = requireCastWiring(downcast);
        var downcastInsn = requireInsnWriting(
                downcast.function(),
                downcastWiring.resultSlotId(),
                ObjectCastInsn.class
        );
        assertAll(
                () -> assertEquals("Node2D", downcastInsn.className()),
                () -> assertEquals(downcastWiring.resultSlotId(), downcastInsn.resultId()),
                () -> assertEquals(downcastWiring.operandSlotId(), downcastInsn.valueId()),
                () -> assertEquals(0, countWriting(downcast.function(), downcastWiring.resultSlotId(), AssignInsn.class)),
                () -> assertEquals(1, count(downcast.function(), ObjectCastInsn.class)),
                () -> assertEquals(
                        new GdObjectType("Node2D"),
                        requireVariableType(downcast.function(), downcastWiring.resultSlotId())
                )
        );

        var nilCast = lowerProbe(
                """
                        class_name CastNilAsObject
                        extends RefCounted
                        
                        func probe() -> Node:
                            return null as Node
                        """
        );
        var nilSlot = requireCastResultSlot(nilCast);
        var nilInsn = requireInsnWriting(nilCast.function(), nilSlot, ObjectCastInsn.class);
        assertAll(
                () -> assertEquals("Node", nilInsn.className()),
                () -> assertEquals(1, count(nilCast.function(), ObjectCastInsn.class)),
                // Plan: Nil as Object must not bypass to a separate literal-null path for the cast itself.
                () -> assertEquals(0, countWriting(nilCast.function(), nilSlot, AssignInsn.class)),
                () -> assertEquals(new GdObjectType("Node"), requireVariableType(nilCast.function(), nilSlot))
        );
    }

    @Test
    void lowersVariantAsObjectToSingleObjectCast() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name CastVariantAsObject
                        extends RefCounted
                        
                        func probe(value: Variant) -> Node:
                            return value as Node
                        """
        );

        var wiring = requireCastWiring(lowered);
        var cast = requireInsnWriting(lowered.function(), wiring.resultSlotId(), ObjectCastInsn.class);
        assertAll(
                () -> assertEquals("Node", cast.className()),
                () -> assertEquals(wiring.operandSlotId(), cast.valueId()),
                () -> assertEquals(0, countWriting(lowered.function(), wiring.resultSlotId(), BuiltinCastInsn.class)),
                () -> assertEquals(0, countWriting(lowered.function(), wiring.resultSlotId(), PackVariantInsn.class)),
                () -> assertEquals(new GdObjectType("Node"), requireVariableType(lowered.function(), wiring.resultSlotId()))
        );
    }

    @Test
    void operandIsEvaluatedOnceAndResultUsesTargetTypedTempSlot() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name CastOperandOnce
                        extends RefCounted
                        
                        func probe(value: int) -> float:
                            return value as float
                        """
        );

        var castItems = collectCastItems(lowered.functionContext().requireFrontendCfgGraph());
        assertEquals(1, castItems.size());
        var item = castItems.getFirst();
        var castResultSlot = FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueId());
        var cast = requireInsnWriting(lowered.function(), castResultSlot, BuiltinCastInsn.class);
        assertAll(
                () -> assertEquals(castResultSlot, cast.resultId()),
                () -> assertEquals(GdFloatType.FLOAT, requireVariableType(lowered.function(), castResultSlot)),
                () -> assertNotEquals(item.operandValueId(), item.resultValueId()),
                () -> assertEquals(1, item.operandValueIds().size())
        );
    }

    @Test
    void cfgPublishesCastItemBeforeBodyConsumesDecision() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name CastCfgItem
                        extends RefCounted
                        
                        func probe(value: Variant) -> int:
                            return value as int
                        """
        );
        var castItems = collectCastItems(lowered.functionContext().requireFrontendCfgGraph());
        assertEquals(1, castItems.size());
        var castResultSlot = FrontendBodyLoweringSupport.cfgTempSlotId(castItems.getFirst().resultValueId());
        assertEquals(
                "int",
                requireInsnWriting(lowered.function(), castResultSlot, BuiltinCastInsn.class).targetTypeName()
        );
    }

    @Test
    void chainedCastUsesLeftAssociationAndTwoInsns() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name CastChained
                        extends RefCounted
                        
                        func probe(value: String) -> float:
                            return value as int as float
                        """
        );

        var castItems = collectCastItems(lowered.functionContext().requireFrontendCfgGraph());
        assertEquals(2, castItems.size());
        var casts = allInstructions(lowered.function()).stream()
                .filter(BuiltinCastInsn.class::isInstance)
                .map(BuiltinCastInsn.class::cast)
                .toList();
        assertEquals(2, casts.size());
        assertEquals("int", casts.getFirst().targetTypeName());
        assertEquals("float", casts.getLast().targetTypeName());
        // Left-associated: outer cast consumes inner cast result; first cast consumes its operand temp.
        assertEquals(
                FrontendBodyLoweringSupport.cfgTempSlotId(castItems.getFirst().operandValueId()),
                casts.getFirst().valueId()
        );
        assertEquals(casts.getFirst().resultId(), casts.getLast().valueId());
    }

    @Test
    void castAsChainHeadStillLowersCastItemFirst() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name CastChainHead
                        extends RefCounted
                        
                        func probe(value: Node) -> String:
                            return (value as Node2D).name
                        """
        );

        var castResultSlot = requireCastResultSlot(lowered);
        var objectCast = requireInsnWriting(lowered.function(), castResultSlot, ObjectCastInsn.class);
        assertEquals("Node2D", objectCast.className());
        var consumerUsesCastResult = allInstructions(lowered.function()).stream()
                .filter(insn -> !(insn instanceof ObjectCastInsn))
                .anyMatch(insn -> instructionUsesSlot(insn, objectCast.resultId()));
        assertTrue(
                consumerUsesCastResult,
                () -> "Expected member path to consume cast result, insns=" + allInstructions(lowered.function())
        );
    }

    @Test
    void emitExplicitCastFailsFastOnInvalidDecision() throws Exception {
        // Guard rail for classifier/materialization mismatch: INVALID must not emit LIR.
        var lowered = lowerProbe(
                """
                        class_name CastInvalidGuardShell
                        extends RefCounted
                        
                        func probe(value: int) -> float:
                            return value as float
                        """
        );
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var session = new FrontendBodyLoweringSession(lowered.functionContext(), classRegistry);
        var block = new LirBasicBlock("cast_invalid_guard");
        var before = block.getInstructions().size();
        var castItems = collectCastItems(lowered.functionContext().requireFrontendCfgGraph());
        assertEquals(1, castItems.size());
        var item = castItems.getFirst();
        var error = assertThrows(
                IllegalStateException.class,
                () -> session.emitExplicitCast(
                        block,
                        item,
                        "cfg_tmp_invalid",
                        "value",
                        GdFloatVectorType.VECTOR2,
                        GdIntType.INT
                )
        );
        assertAll(
                () -> assertTrue(error.getMessage().contains("statically invalid"), error.getMessage()),
                () -> assertEquals(before, block.getInstructions().size())
        );
    }

    @Test
    void invalidHardCastDoesNotReachBodyLoweringOnSharedAnalyze() throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "cast_invalid_body_lowering.gd"),
                """
                        class_name CastInvalidHard
                        extends RefCounted
                        
                        func probe(value: Vector2) -> int:
                            return value as int
                        """,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var module = new FrontendModule("test_module", List.of(unit), Map.of());
        new FrontendSemanticAnalyzer().analyze(module, classRegistry, diagnostics);
        assertTrue(
                diagnostics.hasErrors(),
                "Invalid hard cast must be rejected by type-check before body lowering"
        );
        var typeCheckErrors = diagnostics.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.type_check"))
                .toList();
        assertFalse(typeCheckErrors.isEmpty(), () -> "Expected sema.type_check, got: " + diagnostics.snapshot());
    }

    @Test
    void analyzeForCompileAllowsCastExpression() throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "cast_gate.gd"),
                """
                        class_name CastGate
                        extends RefCounted
                        
                        func probe(value: int) -> float:
                            return value as float
                        """,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(
                new FrontendModule("test_module", List.of(unit)),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        var compileBlocks = analysisData.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .filter(diagnostic -> diagnostic.message().toLowerCase().contains("cast"))
                .toList();
        assertTrue(
                compileBlocks.isEmpty(),
                () -> "CastExpression should pass compile gate, got: " + analysisData.diagnostics()
        );
        assertFalse(analysisData.diagnostics().hasErrors(), () -> "Unexpected errors: " + analysisData.diagnostics());
    }

    private static @NotNull String requireCastResultSlot(@NotNull LoweredProbe lowered) {
        return requireCastWiring(lowered).resultSlotId();
    }

    /// Identifier/literal operands are OpaqueExpr TEMP producers; cast wiring uses those temps, not
    /// the parameter symbol directly. Resolve both ends from the published {@link CastItem}.
    private static @NotNull CastWiring requireCastWiring(@NotNull LoweredProbe lowered) {
        var castItems = collectCastItems(lowered.functionContext().requireFrontendCfgGraph());
        assertEquals(1, castItems.size(), () -> "Expected one CastItem, got " + castItems);
        var item = castItems.getFirst();
        return new CastWiring(
                FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueId()),
                FrontendBodyLoweringSupport.cfgTempSlotId(item.operandValueId())
        );
    }

    private record CastWiring(@NotNull String resultSlotId, @NotNull String operandSlotId) {
    }

    private static <T extends LirInstruction> @NotNull T requireInsnWriting(
            @NotNull LirFunctionDef function,
            @NotNull String resultSlotId,
            @NotNull Class<T> type
    ) {
        var matches = allInstructions(function).stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(insn -> resultSlotId.equals(insn.resultId()))
                .toList();
        assertEquals(
                1,
                matches.size(),
                () -> "Expected exactly one " + type.getSimpleName() + " writing " + resultSlotId
                        + " in " + allInstructions(function)
        );
        return matches.getFirst();
    }

    private static int countWriting(
            @NotNull LirFunctionDef function,
            @NotNull String resultSlotId,
            @NotNull Class<? extends LirInstruction> type
    ) {
        return (int) allInstructions(function).stream()
                .filter(type::isInstance)
                .filter(insn -> resultSlotId.equals(insn.resultId()))
                .count();
    }

    private static boolean instructionUsesSlot(@NotNull LirInstruction insn, @NotNull String slotId) {
        if (slotId.equals(insn.resultId())) {
            return true;
        }
        return insn.operands().stream().anyMatch(operand ->
                operand instanceof LirInstruction.VariableOperand(var id) && slotId.equals(id)
        );
    }

    private static @NotNull List<CastItem> collectCastItems(@NotNull FrontendCfgGraph graph) {
        var castItems = new ArrayList<CastItem>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (item instanceof CastItem castItem) {
                    castItems.add(castItem);
                }
            }
        }
        return castItems;
    }

    private static @NotNull LoweredProbe lowerProbe(@NotNull String source) throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "cast_body_lowering.gd"),
                source,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var module = new FrontendModule("test_module", List.of(unit), Map.of());
        // Shared semantic path: body lowering only needs published expression facts, not compile gate.
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

        var functionContext = context.requireFunctionLoweringContexts().stream()
                .filter(candidate -> candidate.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(candidate -> candidate.targetFunction().getName().equals("probe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing executable body context for probe"));
        return new LoweredProbe(functionContext, functionContext.targetFunction());
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

    private static @NotNull GdType requireVariableType(
            @NotNull LirFunctionDef function,
            @NotNull String variableId
    ) {
        var variable = function.getVariableById(variableId);
        assertNotNull(variable, () -> "Missing LIR variable " + variableId + " in " + function.getVariables());
        return variable.type();
    }

    private record LoweredProbe(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull LirFunctionDef function
    ) {
    }
}
