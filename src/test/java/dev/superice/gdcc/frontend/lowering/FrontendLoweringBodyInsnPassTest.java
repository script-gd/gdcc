package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringBodyInsnPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.insn.AssignInsn;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.GoIfInsn;
import dev.superice.gdcc.lir.insn.GotoInsn;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.LoadPropertyInsn;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.ReturnInsn;
import dev.superice.gdcc.lir.insn.StorePropertyInsn;
import dev.superice.gdcc.lir.insn.UnpackVariantInsn;
import dev.superice.gdcc.lir.insn.VariantGetInsn;
import dev.superice.gdcc.lir.insn.VariantGetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantGetKeyedInsn;
import dev.superice.gdcc.lir.insn.VariantGetNamedInsn;
import dev.superice.gdcc.lir.insn.VariantSetInsn;
import dev.superice.gdcc.lir.insn.VariantSetIndexedInsn;
import dev.superice.gdcc.lir.insn.VariantSetKeyedInsn;
import dev.superice.gdcc.lir.insn.VariantSetNamedInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendLoweringBodyInsnPassTest {
    @Test
    void runMaterializesStraightLineExecutableBodyIntoRealBlocksAndInstructions() throws Exception {
        var prepared = prepareContext(
                "body_insn_linear.gd",
                """
                        class_name BodyInsnLinear
                        extends RefCounted
                        
                        var payloads: Dictionary[int, BodyInsnLinear]
                        var value: int
                        
                        func helper(seed: int) -> int:
                            return seed + 1
                        
                        func build(seed: int) -> BodyInsnLinear:
                            return self
                        
                        func ping(seed: int) -> int:
                            var next := helper(seed)
                            value = next
                            return build(seed).value + payloads[seed].value + next
                        """,
                Map.of("BodyInsnLinear", "RuntimeBodyInsnLinear"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnLinear",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = pingContext.targetFunction();
        var entryBlock = requireBlock(function, function.getEntryBlockId());
        var stopBlock = requireBlock(function, "stop_1");
        var nonTerminatorInstructions = entryBlock.getNonTerminatorInstructions();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("seq_0", function.getEntryBlockId()),
                () -> assertEquals(2, function.getBasicBlockCount()),
                () -> assertTrue(function.hasVariable("next")),
                () -> assertTrue(function.getVariables().keySet().stream().anyMatch(id -> id.startsWith("cfg_tmp_"))),
                () -> assertTrue(nonTerminatorInstructions.stream().anyMatch(CallMethodInsn.class::isInstance)),
                () -> assertTrue(nonTerminatorInstructions.stream().anyMatch(StorePropertyInsn.class::isInstance)),
                () -> assertTrue(nonTerminatorInstructions.stream().anyMatch(insn ->
                        insn instanceof VariantGetInsn
                                || insn instanceof VariantGetIndexedInsn
                                || insn instanceof VariantGetKeyedInsn
                                || insn instanceof VariantGetNamedInsn
                )),
                () -> assertTrue(nonTerminatorInstructions.stream().anyMatch(LoadPropertyInsn.class::isInstance)),
                () -> assertTrue(nonTerminatorInstructions.stream().anyMatch(insn ->
                        insn instanceof BinaryOpInsn(_, var op, _, _) && op == GodotOperator.ADD
                )),
                () -> assertInstanceOf(GotoInsn.class, entryBlock.getTerminator()),
                () -> assertInstanceOf(ReturnInsn.class, stopBlock.getTerminator())
        );
    }

    @Test
    void runMaterializesAttributeSubscriptChainsFromPublishedStepExpressionTypes() throws Exception {
        var prepared = prepareContext(
                "body_insn_attribute_subscript.gd",
                """
                        class_name BodyInsnAttributeSubscript
                        extends RefCounted
                        
                        var payloads: Dictionary[int, BodyInsnAttributeSubscript]
                        var value: int
                        
                        func build(seed: int) -> BodyInsnAttributeSubscript:
                            return self
                        
                        func ping(seed: int) -> int:
                            return build(seed).payloads[seed].value
                        """,
                Map.of("BodyInsnAttributeSubscript", "RuntimeBodyInsnAttributeSubscript"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnAttributeSubscript",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = pingContext.targetFunction();
        var entryBlock = requireBlock(function, function.getEntryBlockId());
        var stopBlock = requireBlock(function, "stop_1");
        var nonTerminatorInstructions = entryBlock.getNonTerminatorInstructions();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("seq_0", function.getEntryBlockId()),
                () -> assertTrue(nonTerminatorInstructions.stream().anyMatch(CallMethodInsn.class::isInstance)),
                () -> assertTrue(nonTerminatorInstructions.stream().anyMatch(VariantGetIndexedInsn.class::isInstance)),
                () -> assertFalse(nonTerminatorInstructions.stream().anyMatch(VariantGetInsn.class::isInstance)),
                () -> assertTrue(nonTerminatorInstructions.stream().anyMatch(LoadPropertyInsn.class::isInstance)),
                () -> assertInstanceOf(GotoInsn.class, entryBlock.getTerminator()),
                () -> assertInstanceOf(ReturnInsn.class, stopBlock.getTerminator())
        );
    }

    @Test
    void runReportsPublishedAttributeSubscriptFailureWhenCompileGateIsBypassed() throws Exception {
        var prepared = prepareContext(
                "body_insn_attribute_subscript_failure.gd",
                """
                        class_name BodyInsnAttributeSubscriptFailure
                        extends RefCounted
                        
                        var payloads: Dictionary[int, BodyInsnAttributeSubscriptFailure]
                        var value: int
                        
                        func build(seed: int) -> BodyInsnAttributeSubscriptFailure:
                            return self
                        
                        func ping(seed: int) -> int:
                            return build(seed).payloads[seed].value
                        """,
                Map.of("BodyInsnAttributeSubscriptFailure", "RuntimeBodyInsnAttributeSubscriptFailure"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnAttributeSubscriptFailure",
                "ping"
        );
        var subscriptStep = requireSingleAttributeSubscriptStep(pingContext.requireFrontendCfgGraph());
        prepared.context().requireAnalysisData().expressionTypes().put(
                subscriptStep,
                FrontendExpressionType.failed("synthetic failed attribute subscript step")
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertAll(
                () -> assertTrue(
                        exception.getMessage().contains("AttributeSubscriptStep 'payloads[...]'"),
                        exception.getMessage()
                ),
                () -> assertTrue(exception.getMessage().contains("FAILED"), exception.getMessage()),
                () -> assertTrue(
                        exception.getMessage().contains("synthetic failed attribute subscript step"),
                        exception.getMessage()
                ),
                () -> assertTrue(
                        exception.getMessage().contains("FrontendCompileCheckAnalyzer should have blocked this"),
                        exception.getMessage()
                )
        );
    }

    @Test
    void runMaterializesValueContextShortCircuitAsBranchConstantWritesAndMergeSlotWrites() throws Exception {
        var prepared = prepareContext(
                "body_insn_short_circuit.gd",
                """
                        class_name BodyInsnShortCircuit
                        extends RefCounted
                        
                        func helper(seed: int) -> bool:
                            return seed > 0
                        
                        func consume(value: bool) -> bool:
                            return value
                        
                        func ping(flag: bool, seed: int) -> bool:
                            return consume(flag and helper(seed))
                        """,
                Map.of("BodyInsnShortCircuit", "RuntimeBodyInsnShortCircuit"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnShortCircuit",
                "ping"
        );
        var mergeValueId = requireSingleMergeValueId(pingContext.requireFrontendCfgGraph());

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = pingContext.targetFunction();
        var allInstructions = allInstructions(function);
        var mergeSlotId = "cfg_merge_" + mergeValueId;

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertTrue(allInstructions.stream().anyMatch(GoIfInsn.class::isInstance)),
                () -> assertFalse(allInstructions.stream().anyMatch(insn ->
                        insn instanceof BinaryOpInsn(_, var op, _, _)
                                && (op == GodotOperator.AND || op == GodotOperator.OR)
                )),
                () -> assertTrue(allInstructions.stream().anyMatch(insn -> insn instanceof LiteralBoolInsn)),
                () -> assertTrue(allInstructions.stream().anyMatch(insn ->
                        insn instanceof AssignInsn(var resultId, _)
                                && resultId.equals(mergeSlotId)
                )),
                () -> assertTrue(function.hasVariable(mergeSlotId))
        );
    }

    @Test
    void runNormalizesVariantAndStableNonBoolConditionsAtBranchProcessors() throws Exception {
        var prepared = prepareContext(
                "body_insn_condition_normalization.gd",
                """
                        class_name BodyInsnConditionNormalization
                        extends RefCounted
                        
                        func branch_on_variant(box: Variant) -> int:
                            if box:
                                return 1
                            return 2
                        
                        func branch_on_int(count: int) -> int:
                            if count:
                                return 1
                            return 2
                        """,
                Map.of("BodyInsnConditionNormalization", "RuntimeBodyInsnConditionNormalization"),
                true
        );
        var variantContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnConditionNormalization",
                "branch_on_variant"
        );
        var intContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnConditionNormalization",
                "branch_on_int"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var variantInstructions = allInstructions(variantContext.targetFunction());
        var intInstructions = allInstructions(intContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(0, countInstructions(variantInstructions, PackVariantInsn.class)),
                () -> assertEquals(1, countInstructions(variantInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(1, countInstructions(variantInstructions, GoIfInsn.class)),
                () -> assertEquals(1, countInstructions(intInstructions, PackVariantInsn.class)),
                () -> assertEquals(1, countInstructions(intInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(1, countInstructions(intInstructions, GoIfInsn.class))
        );
    }

    @Test
    void runChoosesIndexedNamedAndKeyedSubscriptInstructionsFromPublishedKeyTypes() throws Exception {
        var prepared = prepareContext(
                "body_insn_subscript_modes.gd",
                """
                        class_name BodyInsnSubscriptModes
                        extends RefCounted
                        
                        func ping(
                            values: Array[int],
                            dict_by_name: Dictionary[StringName, int],
                            dict_by_text: Dictionary[String, int],
                            idx: int,
                            name_key: StringName,
                            text_key: String
                        ) -> int:
                            values[idx] = 11
                            dict_by_name[name_key] = 22
                            dict_by_text[text_key] = 33
                            return values[idx] + dict_by_name[name_key] + dict_by_text[text_key]
                        """,
                Map.of("BodyInsnSubscriptModes", "RuntimeBodyInsnSubscriptModes"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSubscriptModes",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var allInstructions = allInstructions(pingContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, countInstructions(allInstructions, VariantSetIndexedInsn.class)),
                () -> assertEquals(1, countInstructions(allInstructions, VariantGetIndexedInsn.class)),
                () -> assertEquals(1, countInstructions(allInstructions, VariantSetNamedInsn.class)),
                () -> assertEquals(1, countInstructions(allInstructions, VariantGetNamedInsn.class)),
                () -> assertEquals(1, countInstructions(allInstructions, VariantSetKeyedInsn.class)),
                () -> assertEquals(1, countInstructions(allInstructions, VariantGetKeyedInsn.class)),
                () -> assertFalse(allInstructions.stream().anyMatch(VariantSetInsn.class::isInstance)),
                () -> assertFalse(allInstructions.stream().anyMatch(VariantGetInsn.class::isInstance))
        );
    }

    @Test
    void runKeepsGenericSubscriptInstructionsWhenOnlyVariantKeyKindIsKnown() throws Exception {
        var prepared = prepareContext(
                "body_insn_subscript_variant_key.gd",
                """
                        class_name BodyInsnSubscriptVariantKey
                        extends RefCounted
                        
                        func ping(box: Variant, key: Variant, value: Variant) -> Variant:
                            box[key] = value
                            return box[key]
                        """,
                Map.of("BodyInsnSubscriptVariantKey", "RuntimeBodyInsnSubscriptVariantKey"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSubscriptVariantKey",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var allInstructions = allInstructions(pingContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, countInstructions(allInstructions, VariantSetInsn.class)),
                () -> assertEquals(1, countInstructions(allInstructions, VariantGetInsn.class)),
                () -> assertFalse(allInstructions.stream().anyMatch(VariantSetIndexedInsn.class::isInstance)),
                () -> assertFalse(allInstructions.stream().anyMatch(VariantSetKeyedInsn.class::isInstance)),
                () -> assertFalse(allInstructions.stream().anyMatch(VariantSetNamedInsn.class::isInstance)),
                () -> assertFalse(allInstructions.stream().anyMatch(VariantGetIndexedInsn.class::isInstance)),
                () -> assertFalse(allInstructions.stream().anyMatch(VariantGetKeyedInsn.class::isInstance)),
                () -> assertFalse(allInstructions.stream().anyMatch(VariantGetNamedInsn.class::isInstance))
        );
    }

    @Test
    void runRejectsExecutableBodyWithoutPublishedFrontendCfgGraph() throws Exception {
        var prepared = prepareContext(
                "body_insn_missing_graph.gd",
                """
                        class_name BodyInsnMissingGraph
                        extends RefCounted
                        
                        func ping(seed: int) -> int:
                            return seed
                        """,
                Map.of("BodyInsnMissingGraph", "RuntimeBodyInsnMissingGraph"),
                false
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("Frontend CFG graph has not been published"), exception.getMessage());
    }

    @Test
    void runRejectsParameterDefaultContextsUntilTheirBodySurfaceExists() throws Exception {
        var prepared = prepareContext(
                "body_insn_parameter_default.gd",
                """
                        class_name BodyInsnParameterDefault
                        extends RefCounted
                        
                        func ping(seed: int) -> int:
                            return seed
                        """,
                Map.of("BodyInsnParameterDefault", "RuntimeBodyInsnParameterDefault"),
                true
        );
        var executableContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnParameterDefault",
                "ping"
        );
        var parameterDefaultContext = new FunctionLoweringContext(
                FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT,
                executableContext.sourcePath(),
                executableContext.sourceClassRelation(),
                executableContext.owningClass(),
                executableContext.targetFunction(),
                executableContext.sourceOwner(),
                executableContext.loweringRoot(),
                executableContext.analysisData()
        );
        prepared.context().publishFunctionLoweringContexts(List.of(executableContext, parameterDefaultContext));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("parameter default"), exception.getMessage());
    }

    private static @NotNull PreparedContext prepareContext(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap,
            boolean buildCfg
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(List.of(new SourceFixture(fileName, source)), topLevelCanonicalNameMap);
        var context = new FrontendLoweringContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        new FrontendLoweringAnalysisPass().run(context);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
        if (buildCfg) {
            new FrontendLoweringBuildCfgPass().run(context);
        }
        return new PreparedContext(context, diagnostics, module);
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull List<SourceFixture> fixtures,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var units = fixtures.stream()
                .map(fixture -> parserService.parseUnit(Path.of("tmp", fixture.fileName()), fixture.source(), parseDiagnostics))
                .toList();
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule("test_module", units, topLevelCanonicalNameMap);
    }

    private static @NotNull FunctionLoweringContext requireContext(
            @NotNull List<FunctionLoweringContext> contexts,
            @NotNull FunctionLoweringContext.Kind kind,
            @NotNull String owningClassName,
            @NotNull String functionName
    ) {
        return contexts.stream()
                .filter(context -> context.kind() == kind)
                .filter(context -> context.owningClass().getName().equals(owningClassName))
                .filter(context -> context.targetFunction().getName().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing context " + kind + " " + owningClassName + "." + functionName
                ));
    }

    private static @NotNull LirBasicBlock requireBlock(
            @NotNull dev.superice.gdcc.lir.LirFunctionDef function,
            @NotNull String blockId
    ) {
        var block = function.getBasicBlock(blockId);
        assertNotNull(block, () -> "Missing basic block " + blockId);
        return block;
    }

    private static @NotNull List<LirInstruction> allInstructions(
            @NotNull dev.superice.gdcc.lir.LirFunctionDef function
    ) {
        var instructions = new ArrayList<LirInstruction>();
        for (var block : function) {
            instructions.addAll(block.getInstructions());
        }
        return List.copyOf(instructions);
    }

    private static int countInstructions(
            @NotNull List<LirInstruction> instructions,
            @NotNull Class<? extends LirInstruction> instructionType
    ) {
        return (int) instructions.stream().filter(instructionType::isInstance).count();
    }

    private static @NotNull String requireSingleMergeValueId(@NotNull FrontendCfgGraph graph) {
        var mergeValueIds = new LinkedHashSet<String>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (item instanceof MergeValueItem mergeValueItem) {
                    mergeValueIds.add(mergeValueItem.resultValueId());
                }
            }
        }
        assertEquals(1, mergeValueIds.size());
        return mergeValueIds.stream().toList().getFirst();
    }

    private static @NotNull AttributeSubscriptStep requireSingleAttributeSubscriptStep(@NotNull FrontendCfgGraph graph) {
        var subscriptSteps = new ArrayList<AttributeSubscriptStep>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (item instanceof SubscriptLoadItem subscriptLoadItem
                        && subscriptLoadItem.anchor() instanceof AttributeSubscriptStep attributeSubscriptStep) {
                    subscriptSteps.add(attributeSubscriptStep);
                }
            }
        }
        assertEquals(1, subscriptSteps.size());
        return subscriptSteps.getFirst();
    }

    private record PreparedContext(
            @NotNull FrontendLoweringContext context,
            @NotNull DiagnosticManager diagnostics,
            @NotNull FrontendModule module
    ) {
    }

    private record SourceFixture(
            @NotNull String fileName,
            @NotNull String source
    ) {
    }
}
