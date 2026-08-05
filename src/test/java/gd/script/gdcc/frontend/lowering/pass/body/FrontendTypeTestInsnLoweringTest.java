package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.lowering.cfg.item.TypeTestItem;
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
import gd.script.gdcc.lir.insn.GetVariantTypeInsn;
import gd.script.gdcc.lir.insn.IsInstanceOfInsn;
import gd.script.gdcc.lir.insn.LiteralBoolInsn;
import gd.script.gdcc.lir.insn.UnaryOpInsn;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Body-lowering contract for GDScript `is` / `is not`.
///
/// Uses shared `analyze(...)` (not `analyzeForCompile`) to isolate CFG/body lowering from the
/// compile-only final gate.
class FrontendTypeTestInsnLoweringTest {

    @Test
    void lowersVariantIsKnownObjectToSingleIsInstanceOf() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name TypeTestVariantIsObject
                        extends RefCounted
                        
                        func probe(value) -> bool:
                            return value is Node
                        """
        );

        var insn = requireOnly(lowered.function(), IsInstanceOfInsn.class);
        assertAll(
                () -> assertEquals("Node", insn.typeName()),
                () -> assertEquals(0, count(lowered.function(), UnaryOpInsn.class)),
                () -> assertEquals(0, count(lowered.function(), GetVariantTypeInsn.class)),
                () -> assertEquals(0, count(lowered.function(), LiteralBoolInsn.class))
        );
    }

    @Test
    void lowersVariantIsNotBuiltinToIsInstanceOfThenUnaryNot() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name TypeTestVariantIsNotBuiltin
                        extends RefCounted
                        
                        func probe(value) -> bool:
                            return value is not int
                        """
        );

        var isInstanceOf = requireOnly(lowered.function(), IsInstanceOfInsn.class);
        var notInsn = requireOnly(lowered.function(), UnaryOpInsn.class);
        assertAll(
                () -> assertEquals("int", isInstanceOf.typeName()),
                () -> assertEquals(GodotOperator.NOT, notInsn.op()),
                () -> assertEquals(isInstanceOf.resultId(), notInsn.operandId()),
                () -> assertEquals(0, count(lowered.function(), GetVariantTypeInsn.class))
        );
    }

    @Test
    void foldsExactBuiltinMatchAndMismatchIncludingNegated() throws Exception {
        var same = lowerProbe(
                """
                        class_name TypeTestFoldSameBuiltin
                        extends RefCounted
                        
                        func probe(value: int) -> bool:
                            return value is int
                        """
        );
        var sameBool = requireOnly(same.function(), LiteralBoolInsn.class);
        assertTrue(sameBool.value());
        assertEquals(0, count(same.function(), IsInstanceOfInsn.class));

        var mismatch = lowerProbe(
                """
                        class_name TypeTestFoldMismatchBuiltin
                        extends RefCounted
                        
                        func probe(value: int) -> bool:
                            return value is float
                        """
        );
        var mismatchBool = requireOnly(mismatch.function(), LiteralBoolInsn.class);
        assertFalse(mismatchBool.value());
        assertEquals(0, count(mismatch.function(), IsInstanceOfInsn.class));

        var isNotMismatch = lowerProbe(
                """
                        class_name TypeTestFoldIsNotMismatch
                        extends RefCounted
                        
                        func probe(value: int) -> bool:
                            return value is not float
                        """
        );
        var isNotBool = requireOnly(isNotMismatch.function(), LiteralBoolInsn.class);
        assertTrue(isNotBool.value());
        assertEquals(0, count(isNotMismatch.function(), IsInstanceOfInsn.class));
    }

    @Test
    void foldsObjectVsNonObjectAndDefiniteObjectUpcast() throws Exception {
        var intIsNode = lowerProbe(
                """
                        class_name TypeTestFoldIntIsNode
                        extends RefCounted
                        
                        func probe(value: int) -> bool:
                            return value is Node
                        """
        );
        assertFalse(requireOnly(intIsNode.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(intIsNode.function(), IsInstanceOfInsn.class));

        var nodeIsInt = lowerProbe(
                """
                        class_name TypeTestFoldNodeIsInt
                        extends RefCounted
                        
                        func probe(value: Node) -> bool:
                            return value is int
                        """
        );
        assertFalse(requireOnly(nodeIsInt.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(nodeIsInt.function(), IsInstanceOfInsn.class));

        var node2dIsNode = lowerProbe(
                """
                        class_name TypeTestFoldNode2dIsNode
                        extends RefCounted
                        
                        func probe(value: Node2D) -> bool:
                            return value is Node
                        """
        );
        var upcastInsn = requireOnly(node2dIsNode.function(), IsInstanceOfInsn.class);
        assertEquals("Node", upcastInsn.typeName());
        assertEquals(0, count(node2dIsNode.function(), LiteralBoolInsn.class));
    }

    @Test
    void doesNotFoldParentObjectToChildOrVariantOperand() throws Exception {
        var parentToChild = lowerProbe(
                """
                        class_name TypeTestNoFoldParentToChild
                        extends RefCounted
                        
                        func probe(value: Node) -> bool:
                            return value is Node2D
                        """
        );
        var parentInsn = requireOnly(parentToChild.function(), IsInstanceOfInsn.class);
        assertEquals("Node2D", parentInsn.typeName());
        assertEquals(0, count(parentToChild.function(), LiteralBoolInsn.class));

        var variantOperand = lowerProbe(
                """
                        class_name TypeTestNoFoldVariantOperand
                        extends RefCounted
                        
                        func probe(value) -> bool:
                            return value is int
                        """
        );
        var variantInsn = requireOnly(variantOperand.function(), IsInstanceOfInsn.class);
        assertEquals("int", variantInsn.typeName());
        assertEquals(0, count(variantOperand.function(), LiteralBoolInsn.class));
    }

    @Test
    void unresolvedObjectTargetAlwaysEmitsRuntimeIsInstanceOfWithoutFolding() throws Exception {
        var unresolved = lowerProbe(
                """
                        class_name TypeTestUnresolvedObject
                        extends RefCounted
                        
                        func probe(value) -> bool:
                            return value is FutureEnemy
                        """
        );
        var insn = requireOnly(unresolved.function(), IsInstanceOfInsn.class);
        assertEquals("FutureEnemy", insn.typeName());
        assertEquals(0, count(unresolved.function(), LiteralBoolInsn.class));

        // Even with a static non-object operand, unresolved object targets stay runtime-open.
        var intOperandUnresolved = lowerProbe(
                """
                        class_name TypeTestUnresolvedWithIntOperand
                        extends RefCounted
                        
                        func probe(value: int) -> bool:
                            return value is FutureEnemy
                        """
        );
        var runtimeInsn = requireOnly(intOperandUnresolved.function(), IsInstanceOfInsn.class);
        assertEquals("FutureEnemy", runtimeInsn.typeName());
        assertEquals(0, count(intOperandUnresolved.function(), LiteralBoolInsn.class));
    }

    @Test
    void lowersParameterizedAndBareContainerTargetsAsTypeNameText() throws Exception {
        var typedArray = lowerProbe(
                """
                        class_name TypeTestTypedArray
                        extends RefCounted
                        
                        func probe(value) -> bool:
                            return value is Array[int]
                        """
        );
        assertEquals("Array[int]", requireOnly(typedArray.function(), IsInstanceOfInsn.class).typeName());

        var bareArray = lowerProbe(
                """
                        class_name TypeTestBareArray
                        extends RefCounted
                        
                        func probe(value) -> bool:
                            return value is Array
                        """
        );
        assertEquals("Array", requireOnly(bareArray.function(), IsInstanceOfInsn.class).typeName());

        var typedDict = lowerProbe(
                """
                        class_name TypeTestTypedDict
                        extends RefCounted
                        
                        func probe(value) -> bool:
                            return value is Dictionary[String, int]
                        """
        );
        assertEquals(
                "Dictionary[String, int]",
                requireOnly(typedDict.function(), IsInstanceOfInsn.class).typeName()
        );
    }

    @Test
    void foldsExactContainerMatchAndDirectionalBareTarget() throws Exception {
        var exactTyped = lowerProbe(
                """
                        class_name TypeTestExactTypedArray
                        extends RefCounted
                        
                        func probe(value: Array[int]) -> bool:
                            return value is Array[int]
                        """
        );
        assertTrue(requireOnly(exactTyped.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(exactTyped.function(), IsInstanceOfInsn.class));

        // Typed container is bare Array → true (variant family).
        // Reverse bare→parameterized stays runtime-open: the bare slot may hold typed metadata.
        var typedIsBare = lowerProbe(
                """
                        class_name TypeTestTypedArrayIsBare
                        extends RefCounted
                        
                        func probe(value: Array[int]) -> bool:
                            return value is Array
                        """
        );
        assertTrue(requireOnly(typedIsBare.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(typedIsBare.function(), IsInstanceOfInsn.class));

        var bareIsTyped = lowerProbe(
                """
                        class_name TypeTestBareArrayIsTyped
                        extends RefCounted
                        
                        func probe(value: Array) -> bool:
                            return value is Array[int]
                        """
        );
        assertEquals("Array[int]", requireOnly(bareIsTyped.function(), IsInstanceOfInsn.class).typeName());
        assertEquals(0, count(bareIsTyped.function(), LiteralBoolInsn.class));

        var typedDictIsBare = lowerProbe(
                """
                        class_name TypeTestTypedDictIsBare
                        extends RefCounted
                        
                        func probe(value: Dictionary[String, int]) -> bool:
                            return value is Dictionary
                        """
        );
        assertTrue(requireOnly(typedDictIsBare.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(typedDictIsBare.function(), IsInstanceOfInsn.class));

        var bareDictIsTyped = lowerProbe(
                """
                        class_name TypeTestBareDictIsTyped
                        extends RefCounted
                        
                        func probe(value: Dictionary) -> bool:
                            return value is Dictionary[String, int]
                        """
        );
        assertEquals(
                "Dictionary[String, int]",
                requireOnly(bareDictIsTyped.function(), IsInstanceOfInsn.class).typeName()
        );
        assertEquals(0, count(bareDictIsTyped.function(), LiteralBoolInsn.class));
    }

    @Test
    void foldsExactPackedMatchMismatchAndNegated() throws Exception {
        var same = lowerProbe(
                """
                        class_name TypeTestFoldSamePacked
                        extends RefCounted
                        
                        func probe(value: PackedInt32Array) -> bool:
                            return value is PackedInt32Array
                        """
        );
        assertTrue(requireOnly(same.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(same.function(), IsInstanceOfInsn.class));

        var mismatch = lowerProbe(
                """
                        class_name TypeTestFoldMismatchPacked
                        extends RefCounted
                        
                        func probe(value: PackedInt32Array) -> bool:
                            return value is PackedFloat32Array
                        """
        );
        assertFalse(requireOnly(mismatch.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(mismatch.function(), IsInstanceOfInsn.class));

        var packedIsBareArray = lowerProbe(
                """
                        class_name TypeTestPackedIsBareArray
                        extends RefCounted
                        
                        func probe(value: PackedInt32Array) -> bool:
                            return value is Array
                        """
        );
        assertFalse(requireOnly(packedIsBareArray.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(packedIsBareArray.function(), IsInstanceOfInsn.class));

        var isNotMismatch = lowerProbe(
                """
                        class_name TypeTestFoldIsNotMismatchPacked
                        extends RefCounted
                        
                        func probe(value: PackedInt32Array) -> bool:
                            return value is not PackedInt64Array
                        """
        );
        assertTrue(requireOnly(isNotMismatch.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(isNotMismatch.function(), IsInstanceOfInsn.class));
        assertEquals(0, count(isNotMismatch.function(), UnaryOpInsn.class));
    }

    @Test
    void lowersVariantIsPackedToSingleIsInstanceOf() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name TypeTestVariantIsPacked
                        extends RefCounted
                        
                        func probe(value) -> bool:
                            return value is PackedInt32Array
                        """
        );

        var insn = requireOnly(lowered.function(), IsInstanceOfInsn.class);
        assertAll(
                () -> assertEquals("PackedInt32Array", insn.typeName()),
                () -> assertEquals(0, count(lowered.function(), UnaryOpInsn.class)),
                () -> assertEquals(0, count(lowered.function(), GetVariantTypeInsn.class)),
                () -> assertEquals(0, count(lowered.function(), LiteralBoolInsn.class))
        );
    }

    @Test
    void foldsVariantTargetToTrueForAnyOperandIncludingNegated() throws Exception {
        // Variant is the top type: any operand, including null, folds true; is not -> false.
        // Must not emit is_instance_of "Variant" (backend would fail-closed / NIL-enum trap).
        assertFoldsVariantTargetTrue(
                """
                        class_name TypeTestVariantTargetVariantOperand
                        extends RefCounted
                        
                        func probe(value: Variant) -> bool:
                            return value is Variant
                        """
        );
        assertFoldsVariantTargetTrue(
                """
                        class_name TypeTestVariantTargetIntOperand
                        extends RefCounted
                        
                        func probe(value: int) -> bool:
                            return value is Variant
                        """
        );
        assertFoldsVariantTargetTrue(
                """
                        class_name TypeTestVariantTargetNodeOperand
                        extends RefCounted
                        
                        func probe(value: Node) -> bool:
                            return value is Variant
                        """
        );
        assertFoldsVariantTargetTrue(
                """
                        class_name TypeTestVariantTargetNullOperand
                        extends RefCounted
                        
                        func probe() -> bool:
                            return null is Variant
                        """
        );
        assertFoldsVariantTargetTrue(
                """
                        class_name TypeTestVariantTargetTypedArrayOperand
                        extends RefCounted
                        
                        func probe(value: Array[int]) -> bool:
                            return value is Variant
                        """
        );
        assertFoldsVariantTargetTrue(
                """
                        class_name TypeTestVariantTargetBareArrayOperand
                        extends RefCounted
                        
                        func probe(value: Array) -> bool:
                            return value is Variant
                        """
        );
        assertFoldsVariantTargetTrue(
                """
                        class_name TypeTestVariantTargetPackedArrayOperand
                        extends RefCounted
                        
                        func probe(value: PackedByteArray) -> bool:
                            return value is Variant
                        """
        );

        assertFoldsVariantTargetIsNotFalse(
                """
                        class_name TypeTestIsNotVariantIntOperand
                        extends RefCounted
                        
                        func probe(value: int) -> bool:
                            return value is not Variant
                        """
        );
        assertFoldsVariantTargetIsNotFalse(
                """
                        class_name TypeTestIsNotVariantNullOperand
                        extends RefCounted
                        
                        func probe() -> bool:
                            return null is not Variant
                        """
        );
        assertFoldsVariantTargetIsNotFalse(
                """
                        class_name TypeTestIsNotVariantNodeOperand
                        extends RefCounted
                        
                        func probe(value: Node) -> bool:
                            return value is not Variant
                        """
        );
        assertFoldsVariantTargetIsNotFalse(
                """
                        class_name TypeTestIsNotVariantOperand
                        extends RefCounted
                        
                        func probe(value: Variant) -> bool:
                            return value is not Variant
                        """
        );
    }

    @Test
    void foldsNilLiteralOperandToFalseAndHonorsNegation() throws Exception {
        // Direct null literal keeps Nil static type; a `var x = null` local stabilizes to Variant
        // and must stay runtime-open for non-Variant targets (covered by the Variant no-fold case).
        var nullIsNode = lowerProbe(
                """
                        class_name TypeTestNullIsNode
                        extends RefCounted
                        
                        func probe() -> bool:
                            return null is Node
                        """
        );
        assertFalse(requireOnly(nullIsNode.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(nullIsNode.function(), IsInstanceOfInsn.class));

        var nullIsNotInt = lowerProbe(
                """
                        class_name TypeTestNullIsNotInt
                        extends RefCounted
                        
                        func probe() -> bool:
                            return null is not int
                        """
        );
        assertTrue(requireOnly(nullIsNotInt.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(nullIsNotInt.function(), IsInstanceOfInsn.class));
    }

    @Test
    void negatedPathsCoverFoldAndRuntimeOutcomes() throws Exception {
        var notExact = lowerProbe(
                """
                        class_name TypeTestIsNotExact
                        extends RefCounted
                        
                        func probe(value: int) -> bool:
                            return value is not int
                        """
        );
        assertFalse(requireOnly(notExact.function(), LiteralBoolInsn.class).value());

        var notUpcast = lowerProbe(
                """
                        class_name TypeTestIsNotUpcast
                        extends RefCounted
                        
                        func probe(value: Node2D) -> bool:
                            return value is not Node
                        """
        );
        var upcastIsInstanceOf = requireOnly(notUpcast.function(), IsInstanceOfInsn.class);
        var upcastNot = requireOnly(notUpcast.function(), UnaryOpInsn.class);
        assertAll(
                () -> assertEquals("Node", upcastIsInstanceOf.typeName()),
                () -> assertEquals(GodotOperator.NOT, upcastNot.op()),
                () -> assertEquals(upcastIsInstanceOf.resultId(), upcastNot.operandId())
        );

        var notParentToChild = lowerProbe(
                """
                        class_name TypeTestIsNotParentToChild
                        extends RefCounted
                        
                        func probe(value: Node) -> bool:
                            return value is not Node2D
                        """
        );
        var parentIsInstanceOf = requireOnly(notParentToChild.function(), IsInstanceOfInsn.class);
        var parentNot = requireOnly(notParentToChild.function(), UnaryOpInsn.class);
        assertAll(
                () -> assertEquals("Node2D", parentIsInstanceOf.typeName()),
                () -> assertEquals(GodotOperator.NOT, parentNot.op()),
                () -> assertEquals(parentIsInstanceOf.resultId(), parentNot.operandId())
        );

        var notUnresolved = lowerProbe(
                """
                        class_name TypeTestIsNotUnresolved
                        extends RefCounted
                        
                        func probe(value) -> bool:
                            return value is not FutureEnemy
                        """
        );
        var unresolvedIsInstanceOf = requireOnly(notUnresolved.function(), IsInstanceOfInsn.class);
        var unresolvedNot = requireOnly(notUnresolved.function(), UnaryOpInsn.class);
        assertAll(
                () -> assertEquals("FutureEnemy", unresolvedIsInstanceOf.typeName()),
                () -> assertEquals(GodotOperator.NOT, unresolvedNot.op()),
                () -> assertEquals(unresolvedIsInstanceOf.resultId(), unresolvedNot.operandId())
        );
    }

    @Test
    void cfgStillPublishesTypeTestItemAndBodyConsumesPublishedTarget() throws Exception {
        var lowered = lowerProbe(
                """
                        class_name TypeTestCfgItem
                        extends RefCounted
                        
                        func probe(value: Variant) -> bool:
                            return value is String
                        """
        );
        var graph = lowered.functionContext().requireFrontendCfgGraph();
        var typeTestItems = new ArrayList<TypeTestItem>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph.SequenceNode(
                    _, var items, _
            ))) {
                continue;
            }
            for (var item : items) {
                if (item instanceof TypeTestItem typeTestItem) {
                    typeTestItems.add(typeTestItem);
                }
            }
        }
        assertEquals(1, typeTestItems.size());
        assertEquals("String", requireOnly(lowered.function(), IsInstanceOfInsn.class).typeName());
    }

    @Test
    void analyzeForCompilePassesTypeTestThroughCompileGate() throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "type_test_gate.gd"),
                """
                        class_name TypeTestGate
                        extends RefCounted
                        
                        func probe(value) -> bool:
                            return value is Node
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
                .filter(diagnostic -> diagnostic.message().toLowerCase().contains("type-test"))
                .toList();
        assertTrue(compileBlocks.isEmpty(), () -> "TypeTest should pass compile gate, got: " + analysisData.diagnostics());
    }

    private static void assertFoldsVariantTargetTrue(@NotNull String source) throws Exception {
        var lowered = lowerProbe(source);
        assertTrue(requireOnly(lowered.function(), LiteralBoolInsn.class).value(), source);
        assertEquals(0, count(lowered.function(), IsInstanceOfInsn.class), source);
        assertEquals(0, count(lowered.function(), UnaryOpInsn.class), source);
    }

    private static void assertFoldsVariantTargetIsNotFalse(@NotNull String source) throws Exception {
        var lowered = lowerProbe(source);
        assertFalse(requireOnly(lowered.function(), LiteralBoolInsn.class).value(), source);
        assertEquals(0, count(lowered.function(), IsInstanceOfInsn.class), source);
        assertEquals(0, count(lowered.function(), UnaryOpInsn.class), source);
    }

    private static @NotNull LoweredProbe lowerProbe(@NotNull String source) throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "type_test_body_lowering.gd"),
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

        var functionContext = context.requireFunctionLoweringContexts().stream()
                .filter(candidate -> candidate.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(candidate -> candidate.targetFunction().getName().equals("probe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing executable body context for probe"));
        return new LoweredProbe(functionContext, functionContext.targetFunction());
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

    private record LoweredProbe(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull LirFunctionDef function
    ) {
    }
}
