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

/// Phase 2 body-lowering contract for GDScript `is` / `is not`.
///
/// Uses shared `analyze(...)` (not `analyzeForCompile`) so TypeTest can reach CFG/body lowering
/// while the compile gate remains intentionally closed for the full pipeline (Phase 4).
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
        assertTrue(requireOnly(node2dIsNode.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(node2dIsNode.function(), IsInstanceOfInsn.class));
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

        // Typed container is bare Array → true (variant family); reverse bare→typed is false.
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
        assertFalse(requireOnly(bareIsTyped.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(bareIsTyped.function(), IsInstanceOfInsn.class));

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
        assertFalse(requireOnly(bareDictIsTyped.function(), LiteralBoolInsn.class).value());
        assertEquals(0, count(bareDictIsTyped.function(), IsInstanceOfInsn.class));
    }

    @Test
    void foldsNilLiteralOperandToFalseAndHonorsNegation() throws Exception {
        // Direct null literal keeps Nil static type; a `var x = null` local stabilizes to Variant
        // and must stay runtime-open (covered by the Variant no-fold case).
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
        assertFalse(requireOnly(notUpcast.function(), LiteralBoolInsn.class).value());

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
    void analyzeForCompileStillBlocksTypeTestAtCompileGate() throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "type_test_gate_phase2.gd"),
                """
                        class_name TypeTestGatePhase2
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
        assertFalse(compileBlocks.isEmpty(), () -> "expected compile-gate block, got: " + analysisData.diagnostics());
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
        // Shared semantic only: keep TypeTest out of compile gate so body lowering can be exercised.
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
