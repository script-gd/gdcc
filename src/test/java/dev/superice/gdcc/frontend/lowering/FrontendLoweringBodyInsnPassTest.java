package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
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
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.insn.AssignInsn;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.CallGlobalInsn;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.ConstructBuiltinInsn;
import dev.superice.gdcc.lir.insn.ConstructObjectInsn;
import dev.superice.gdcc.lir.insn.GoIfInsn;
import dev.superice.gdcc.lir.insn.GotoInsn;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.LiteralNullInsn;
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
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    void runMaterializesVariantBoundariesForLocalInitializersAndOrdinaryPropertyAssignments() throws Exception {
        var prepared = prepareContext(
                "body_insn_assignment_variant_boundary.gd",
                """
                        class_name BodyInsnAssignmentVariantBoundary
                        extends RefCounted
                        
                        var payload_int: int
                        var payload_variant: Variant
                        
                        func ping(seed: int, box: Variant) -> void:
                            var any = seed
                            var typed: int = any
                            payload_variant = seed
                            payload_int = box
                        """,
                Map.of("BodyInsnAssignmentVariantBoundary", "RuntimeBodyInsnAssignmentVariantBoundary"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnAssignmentVariantBoundary",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var packedResultIds = packResultIds(instructions);
        var unpackedResultIds = unpackResultIds(instructions);
        var assignSourcesByTarget = assignSourcesByTarget(instructions);
        var payloadVariantStoreIds = storeValueIdsForProperty(instructions, "payload_variant");
        var payloadIntStoreIds = storeValueIdsForProperty(instructions, "payload_int");

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(2, packedResultIds.size()),
                () -> assertEquals(2, unpackedResultIds.size()),
                () -> assertTrue(packedResultIds.contains(assignSourcesByTarget.get("any"))),
                () -> assertTrue(unpackedResultIds.contains(assignSourcesByTarget.get("typed"))),
                () -> assertEquals(1, payloadVariantStoreIds.size()),
                () -> assertEquals(1, payloadIntStoreIds.size()),
                () -> assertTrue(packedResultIds.contains(payloadVariantStoreIds.getFirst())),
                () -> assertTrue(unpackedResultIds.contains(payloadIntStoreIds.getFirst()))
        );
    }

    @Test
    void runKeepsDirectLocalPropertyAndReturnRoutesInstructionFreeWhenNoVariantBoundaryExists() throws Exception {
        var prepared = prepareContext(
                "body_insn_direct_routes.gd",
                """
                        class_name BodyInsnDirectRoutes
                        extends RefCounted
                        
                        var payload_int: int
                        
                        func ping(seed: int) -> int:
                            var copy: int = seed
                            payload_int = copy
                            return copy
                        """,
                Map.of("BodyInsnDirectRoutes", "RuntimeBodyInsnDirectRoutes"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDirectRoutes",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var returnInsn = requireOnlyReturnInsn(pingContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class)),
                () -> assertNotNull(assignSourcesByTarget(instructions).get("copy")),
                () -> assertEquals(1, storeValueIdsForProperty(instructions, "payload_int").size()),
                () -> assertNotNull(returnInsn.returnValueId())
        );
    }

    @Test
    void runMaterializesVariantBoundariesForFixedCallsAndVarargTailArguments() throws Exception {
        var prepared = prepareContext(
                "body_insn_call_variant_boundary.gd",
                """
                        class_name BodyInsnCallVariantBoundary
                        extends RefCounted
                        
                        func take_i(value: int) -> int:
                            return value
                        
                        func take_any(value: Variant) -> Variant:
                            return value
                        
                        func call_concrete(box: Variant) -> int:
                            return take_i(box)
                        
                        func call_variant(seed: int) -> Variant:
                            return take_any(seed)
                        
                        func call_vararg(seed: int) -> void:
                            print(seed)
                        
                        func call_vararg_variant(box: Variant) -> void:
                            print(box)
                        """,
                Map.of("BodyInsnCallVariantBoundary", "RuntimeBodyInsnCallVariantBoundary"),
                true
        );
        var callConcreteContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnCallVariantBoundary",
                "call_concrete"
        );
        var callVariantContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnCallVariantBoundary",
                "call_variant"
        );
        var callVarargContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnCallVariantBoundary",
                "call_vararg"
        );
        var callVarargVariantContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnCallVariantBoundary",
                "call_vararg_variant"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var concreteInstructions = allInstructions(callConcreteContext.targetFunction());
        var variantInstructions = allInstructions(callVariantContext.targetFunction());
        var varargInstructions = allInstructions(callVarargContext.targetFunction());
        var varargVariantInstructions = allInstructions(callVarargVariantContext.targetFunction());

        var callConcreteInsn = requireOnlyInstruction(callConcreteContext.targetFunction(), CallMethodInsn.class);
        var callVariantInsn = requireOnlyInstruction(callVariantContext.targetFunction(), CallMethodInsn.class);
        var callVarargInsn = requireOnlyInstruction(callVarargContext.targetFunction(), CallGlobalInsn.class);
        var callVarargVariantInsn = requireOnlyInstruction(callVarargVariantContext.targetFunction(), CallGlobalInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(0, countInstructions(concreteInstructions, PackVariantInsn.class)),
                () -> assertEquals(1, countInstructions(concreteInstructions, UnpackVariantInsn.class)),
                () -> assertTrue(unpackResultIds(concreteInstructions).contains(onlyVariableOperandId(callConcreteInsn.args()))),
                () -> assertEquals(1, countInstructions(variantInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(variantInstructions, UnpackVariantInsn.class)),
                () -> assertTrue(packResultIds(variantInstructions).contains(onlyVariableOperandId(callVariantInsn.args()))),
                () -> assertEquals(1, countInstructions(varargInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(varargInstructions, UnpackVariantInsn.class)),
                () -> assertTrue(packResultIds(varargInstructions).contains(onlyVariableOperandId(callVarargInsn.args()))),
                () -> assertEquals(0, countInstructions(varargVariantInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(varargVariantInstructions, UnpackVariantInsn.class)),
                () -> assertNotNull(onlyVariableOperandId(callVarargVariantInsn.args()))
        );
    }

    @Test
    void runMaterializesVariantBoundariesAtReturnSlots() throws Exception {
        var prepared = prepareContext(
                "body_insn_return_variant_boundary.gd",
                """
                        class_name BodyInsnReturnVariantBoundary
                        extends RefCounted
                        
                        func ret_any(seed: int) -> Variant:
                            return seed
                        
                        func ret_i(value) -> int:
                            return value
                        
                        func ret_i_explicit(value: Variant) -> int:
                            return value
                        
                        func ret_direct(seed: int) -> int:
                            return seed
                        """,
                Map.of("BodyInsnReturnVariantBoundary", "RuntimeBodyInsnReturnVariantBoundary"),
                true
        );
        var retAnyContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnReturnVariantBoundary",
                "ret_any"
        );
        var retImplicitVariantContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnReturnVariantBoundary",
                "ret_i"
        );
        var retExplicitVariantContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnReturnVariantBoundary",
                "ret_i_explicit"
        );
        var retDirectContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnReturnVariantBoundary",
                "ret_direct"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var retAnyInstructions = allInstructions(retAnyContext.targetFunction());
        var retImplicitVariantInstructions = allInstructions(retImplicitVariantContext.targetFunction());
        var retExplicitVariantInstructions = allInstructions(retExplicitVariantContext.targetFunction());
        var retDirectInstructions = allInstructions(retDirectContext.targetFunction());

        var retAnyInsn = requireOnlyReturnInsn(retAnyContext.targetFunction());
        var retImplicitVariantInsn = requireOnlyReturnInsn(retImplicitVariantContext.targetFunction());
        var retExplicitVariantInsn = requireOnlyReturnInsn(retExplicitVariantContext.targetFunction());
        var retDirectInsn = requireOnlyReturnInsn(retDirectContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, countInstructions(retAnyInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(retAnyInstructions, UnpackVariantInsn.class)),
                () -> assertTrue(packResultIds(retAnyInstructions).contains(retAnyInsn.returnValueId())),
                () -> assertEquals(0, countInstructions(retImplicitVariantInstructions, PackVariantInsn.class)),
                () -> assertEquals(1, countInstructions(retImplicitVariantInstructions, UnpackVariantInsn.class)),
                () -> assertTrue(unpackResultIds(retImplicitVariantInstructions).contains(retImplicitVariantInsn.returnValueId())),
                () -> assertEquals(0, countInstructions(retExplicitVariantInstructions, PackVariantInsn.class)),
                () -> assertEquals(1, countInstructions(retExplicitVariantInstructions, UnpackVariantInsn.class)),
                () -> assertTrue(unpackResultIds(retExplicitVariantInstructions).contains(retExplicitVariantInsn.returnValueId())),
                () -> assertEquals(0, countInstructions(retDirectInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(retDirectInstructions, UnpackVariantInsn.class)),
                () -> assertNotNull(retDirectInsn.returnValueId())
        );
    }

    @Test
    void runMaterializesObjectNullBoundariesForLocalPropertyCallAndReturnRoutes() throws Exception {
        var prepared = prepareContext(
                "body_insn_null_object_boundary.gd",
                """
                        class_name BodyInsnNullObjectBoundary
                        extends RefCounted
                        
                        var payload_obj: Object
                        
                        func take_obj(value: Object) -> Object:
                            return value
                        
                        func ping() -> Object:
                            var local_obj: Object = null
                            payload_obj = null
                            take_obj(null)
                            return local_obj
                        
                        func ret_obj() -> Object:
                            return null
                        """,
                Map.of("BodyInsnNullObjectBoundary", "RuntimeBodyInsnNullObjectBoundary"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnNullObjectBoundary",
                "ping"
        );
        var retObjContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnNullObjectBoundary",
                "ret_obj"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var pingInstructions = allInstructions(pingContext.targetFunction());
        var retObjInstructions = allInstructions(retObjContext.targetFunction());
        var objectNullIds = literalNullResultIds(pingInstructions);
        var callInsn = requireOnlyInstruction(pingContext.targetFunction(), CallMethodInsn.class);
        var retObjInsn = requireOnlyReturnInsn(retObjContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(3, objectNullIds.size()),
                () -> assertTrue(objectNullIds.contains(assignSourcesByTarget(pingInstructions).get("local_obj"))),
                () -> assertTrue(objectNullIds.contains(storeValueIdsForProperty(pingInstructions, "payload_obj").getFirst())),
                () -> assertTrue(objectNullIds.contains(onlyVariableOperandId(callInsn.args()))),
                () -> assertEquals(0, countInstructions(pingInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(pingInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(1, countInstructions(retObjInstructions, LiteralNullInsn.class)),
                () -> assertTrue(literalNullResultIds(retObjInstructions).contains(retObjInsn.returnValueId()))
        );
    }

    @Test
    void runSkipsSyntheticTerminalMergeStopsWhenLoweringFullyTerminatingIfChains() throws Exception {
        var prepared = prepareContext(
                "body_insn_terminal_merge_stop.gd",
                """
                        class_name BodyInsnTerminalMergeStop
                        extends RefCounted
                        
                        func ping(flag: bool, seed: int) -> int:
                            if flag:
                                return seed
                            else:
                                return seed + 1
                        """,
                Map.of("BodyInsnTerminalMergeStop", "RuntimeBodyInsnTerminalMergeStop"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnTerminalMergeStop",
                "ping"
        );
        var graph = pingContext.requireFrontendCfgGraph();
        var terminalMergeStopIds = graph.nodes().values().stream()
                .filter(FrontendCfgGraph.StopNode.class::isInstance)
                .map(FrontendCfgGraph.StopNode.class::cast)
                .filter(stopNode -> stopNode.kind() == FrontendCfgGraph.StopKind.TERMINAL_MERGE)
                .map(FrontendCfgGraph.StopNode::id)
                .toList();

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = pingContext.targetFunction();
        var returnTerminators = new ArrayList<ReturnInsn>();
        for (var block : function) {
            if (block.getTerminator() instanceof ReturnInsn returnInsn) {
                returnTerminators.add(returnInsn);
            }
        }

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, terminalMergeStopIds.size()),
                () -> assertTrue(function.getBasicBlock(terminalMergeStopIds.getFirst()) == null),
                () -> assertEquals(2, returnTerminators.size()),
                () -> assertTrue(returnTerminators.stream().allMatch(returnInsn -> returnInsn.returnValueId() != null))
        );
    }

    @Test
    void runMaterializesBuiltinAndObjectConstructorsFromPublishedConstructorRoutes() throws Exception {
        var prepared = prepareContext(
                "body_insn_constructor_routes.gd",
                """
                        class_name BodyInsnConstructorRoutes
                        extends RefCounted
                        
                        class Worker:
                            func _init():
                                pass
                        
                        func build_vector() -> Vector3i:
                            return Vector3i(1, 2, 3)
                        
                        func build_array(source: Array) -> Array:
                            return Array(source)
                        
                        func build_node() -> Node:
                            return Node.new()
                        
                        func build_worker() -> Worker:
                            return Worker.new()
                        """,
                Map.of("BodyInsnConstructorRoutes", "RuntimeBodyInsnConstructorRoutes"),
                true
        );
        var buildVectorContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnConstructorRoutes",
                "build_vector"
        );
        var buildArrayContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnConstructorRoutes",
                "build_array"
        );
        var buildNodeContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnConstructorRoutes",
                "build_node"
        );
        var buildWorkerContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnConstructorRoutes",
                "build_worker"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var vectorInstructions = allInstructions(buildVectorContext.targetFunction());
        var arrayInstructions = allInstructions(buildArrayContext.targetFunction());
        var nodeInstructions = allInstructions(buildNodeContext.targetFunction());
        var workerInstructions = allInstructions(buildWorkerContext.targetFunction());

        var vectorConstructInsn = requireOnlyInstruction(buildVectorContext.targetFunction(), ConstructBuiltinInsn.class);
        var arrayConstructInsn = requireOnlyInstruction(buildArrayContext.targetFunction(), ConstructBuiltinInsn.class);
        var nodeConstructInsn = requireOnlyInstruction(buildNodeContext.targetFunction(), ConstructObjectInsn.class);
        var workerConstructInsn = requireOnlyInstruction(buildWorkerContext.targetFunction(), ConstructObjectInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, countInstructions(vectorInstructions, ConstructBuiltinInsn.class)),
                () -> assertEquals(3, vectorConstructInsn.args().size()),
                () -> assertEquals(1, countInstructions(arrayInstructions, ConstructBuiltinInsn.class)),
                () -> assertEquals(1, arrayConstructInsn.args().size()),
                () -> assertEquals(1, countInstructions(nodeInstructions, ConstructObjectInsn.class)),
                () -> assertEquals("Node", nodeConstructInsn.className()),
                () -> assertEquals(0, countInstructions(nodeInstructions, CallMethodInsn.class)),
                () -> assertEquals(1, countInstructions(workerInstructions, ConstructObjectInsn.class)),
                () -> assertTrue(workerConstructInsn.className().contains("Worker")),
                () -> assertEquals(0, countInstructions(workerInstructions, CallMethodInsn.class)),
                () -> assertEquals(0, countInstructions(workerInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(workerInstructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runFailsFastWhenParameterizedBuiltinConstructorLosesCallableSignatureMetadata() throws Exception {
        var prepared = prepareContext(
                "body_insn_constructor_missing_signature.gd",
                """
                        class_name BodyInsnConstructorMissingSignature
                        extends RefCounted
                        
                        func build_vector(x: int, y: int, z: int) -> Vector3i:
                            return Vector3i(x, y, z)
                        """,
                Map.of("BodyInsnConstructorMissingSignature", "RuntimeBodyInsnConstructorMissingSignature"),
                true
        );
        var buildVectorContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnConstructorMissingSignature",
                "build_vector"
        );
        var callAnchor = requireSingleCallAnchor(buildVectorContext.requireFrontendCfgGraph());
        var originalResolvedCall = prepared.context().requireAnalysisData().resolvedCalls().get(callAnchor);
        assertNotNull(originalResolvedCall);

        prepared.context().requireAnalysisData().resolvedCalls().put(
                callAnchor,
                FrontendResolvedCall.resolved(
                        originalResolvedCall.callableName(),
                        originalResolvedCall.callKind(),
                        originalResolvedCall.receiverKind(),
                        originalResolvedCall.ownerKind(),
                        originalResolvedCall.receiverType(),
                        originalResolvedCall.returnType(),
                        originalResolvedCall.argumentTypes(),
                        new Object()
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("callable signature metadata"), exception.getMessage()),
                () -> assertTrue(
                        exception.getMessage().contains("required for argument materialization"),
                        exception.getMessage()
                ),
                () -> assertFalse(exception.getMessage().contains("call route is not lowering-ready"), exception.getMessage())
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

    private static @NotNull List<String> packResultIds(@NotNull List<LirInstruction> instructions) {
        return instructions.stream()
                .filter(PackVariantInsn.class::isInstance)
                .map(PackVariantInsn.class::cast)
                .map(PackVariantInsn::resultId)
                .toList();
    }

    private static @NotNull List<String> unpackResultIds(@NotNull List<LirInstruction> instructions) {
        return instructions.stream()
                .filter(UnpackVariantInsn.class::isInstance)
                .map(UnpackVariantInsn.class::cast)
                .map(UnpackVariantInsn::resultId)
                .toList();
    }

    private static @NotNull List<String> literalNullResultIds(@NotNull List<LirInstruction> instructions) {
        return instructions.stream()
                .filter(LiteralNullInsn.class::isInstance)
                .map(LiteralNullInsn.class::cast)
                .map(LiteralNullInsn::resultId)
                .toList();
    }

    private static @NotNull Map<String, String> assignSourcesByTarget(@NotNull List<LirInstruction> instructions) {
        var assignSources = new LinkedHashMap<String, String>();
        for (var instruction : instructions) {
            if (instruction instanceof AssignInsn(var resultId, var sourceId)) {
                assignSources.put(resultId, sourceId);
            }
        }
        return Map.copyOf(assignSources);
    }

    private static @NotNull List<String> storeValueIdsForProperty(
            @NotNull List<LirInstruction> instructions,
            @NotNull String propertyName
    ) {
        return instructions.stream()
                .filter(StorePropertyInsn.class::isInstance)
                .map(StorePropertyInsn.class::cast)
                .filter(instruction -> instruction.propertyName().equals(propertyName))
                .map(StorePropertyInsn::valueId)
                .toList();
    }

    private static <T extends LirInstruction> @NotNull T requireOnlyInstruction(
            @NotNull dev.superice.gdcc.lir.LirFunctionDef function,
            @NotNull Class<T> instructionType
    ) {
        var matches = allInstructions(function).stream()
                .filter(instructionType::isInstance)
                .map(instructionType::cast)
                .toList();
        assertEquals(1, matches.size(), () -> "Expected exactly one " + instructionType.getSimpleName());
        return matches.getFirst();
    }

    private static @NotNull ReturnInsn requireOnlyReturnInsn(
            @NotNull dev.superice.gdcc.lir.LirFunctionDef function
    ) {
        var matches = new ArrayList<ReturnInsn>();
        for (var block : function) {
            if (block.getTerminator() instanceof ReturnInsn returnInsn) {
                matches.add(returnInsn);
            }
        }
        assertEquals(1, matches.size(), "Expected exactly one ReturnInsn terminator");
        return matches.getFirst();
    }

    private static @NotNull String onlyVariableOperandId(@NotNull List<LirInstruction.Operand> operands) {
        assertEquals(1, operands.size(), "Expected exactly one variable argument");
        return assertInstanceOf(LirInstruction.VariableOperand.class, operands.getFirst()).id();
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

    private static @NotNull Node requireSingleCallAnchor(@NotNull FrontendCfgGraph graph) {
        var anchors = new ArrayList<Node>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (item instanceof CallItem callItem) {
                    anchors.add(callItem.anchor());
                }
            }
        }
        assertEquals(1, anchors.size());
        return anchors.getFirst();
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
