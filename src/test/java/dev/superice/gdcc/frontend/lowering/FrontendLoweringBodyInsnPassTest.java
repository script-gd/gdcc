package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SequenceItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.ValueOpItem;
import dev.superice.gdcc.frontend.lowering.pass.body.FrontendBodyLoweringSession;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringBodyInsnPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendReceiverKind;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirParameterDef;
import dev.superice.gdcc.lir.insn.AssignInsn;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.CallGlobalInsn;
import dev.superice.gdcc.lir.insn.CallMethodInsn;
import dev.superice.gdcc.lir.insn.ConstructBuiltinInsn;
import dev.superice.gdcc.lir.insn.ConstructObjectInsn;
import dev.superice.gdcc.lir.insn.GoIfInsn;
import dev.superice.gdcc.lir.insn.GotoInsn;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.LiteralIntInsn;
import dev.superice.gdcc.lir.insn.LiteralNullInsn;
import dev.superice.gdcc.lir.insn.LoadStaticInsn;
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
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FrontendLoweringBodyInsnPassTest {
    @Test
    void runLowersCompoundAssignmentOnLocalIntoBinaryOpAndFinalAssign() throws Exception {
        var prepared = prepareContext(
                "body_insn_compound_local.gd",
                """
                        class_name BodyInsnCompoundLocal
                        extends RefCounted
                        
                        func ping(seed: int) -> int:
                            var count := seed
                            count += 1
                            return count
                        """,
                Map.of("BodyInsnCompoundLocal", "RuntimeBodyInsnCompoundLocal"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnCompoundLocal",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var compoundInsn = requireOnlyInstruction(pingContext.targetFunction(), BinaryOpInsn.class);
        var assignSources = assignSourcesByTarget(instructions);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(GodotOperator.ADD, compoundInsn.op()),
                () -> assertEquals(compoundInsn.resultId(), assignSources.get("count")),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runLowersCompoundAssignmentOnPropertyThroughLoadBinaryOpAndStore() throws Exception {
        var prepared = prepareContext(
                "body_insn_compound_property.gd",
                """
                        class_name BodyInsnCompoundProperty
                        extends RefCounted
                        
                        var hp: int = 10
                        
                        func ping(seed: int) -> int:
                            hp -= seed
                            return hp
                        """,
                Map.of("BodyInsnCompoundProperty", "RuntimeBodyInsnCompoundProperty"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnCompoundProperty",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var compoundInsn = requireOnlyInstruction(pingContext.targetFunction(), BinaryOpInsn.class);
        var propertyLoads = instructions.stream()
                .filter(LoadPropertyInsn.class::isInstance)
                .map(LoadPropertyInsn.class::cast)
                .filter(instruction -> instruction.propertyName().equals("hp"))
                .toList();
        var propertyStores = instructions.stream()
                .filter(StorePropertyInsn.class::isInstance)
                .map(StorePropertyInsn.class::cast)
                .filter(instruction -> instruction.propertyName().equals("hp"))
                .toList();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(GodotOperator.SUBTRACT, compoundInsn.op()),
                () -> assertEquals(2, propertyLoads.size()),
                () -> assertEquals(1, propertyStores.size()),
                () -> assertEquals(compoundInsn.resultId(), propertyStores.getFirst().valueId())
        );
    }

    @Test
    void runLowersCompoundAssignmentOnIndexedSubscriptThroughSingleReadModifyWriteRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_compound_subscript.gd",
                """
                        class_name BodyInsnCompoundSubscript
                        extends RefCounted
                        
                        func ping(values: PackedInt32Array, slot: int) -> int:
                            values[slot] <<= 1
                            return values[slot]
                        """,
                Map.of("BodyInsnCompoundSubscript", "RuntimeBodyInsnCompoundSubscript"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnCompoundSubscript",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var compoundInsn = requireOnlyInstruction(pingContext.targetFunction(), BinaryOpInsn.class);
        var indexedStores = instructions.stream()
                .filter(VariantSetIndexedInsn.class::isInstance)
                .map(VariantSetIndexedInsn.class::cast)
                .toList();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(GodotOperator.SHIFT_LEFT, compoundInsn.op()),
                () -> assertEquals(1, indexedStores.size()),
                () -> assertEquals(compoundInsn.resultId(), indexedStores.getFirst().valueId()),
                () -> assertEquals(2, countInstructions(instructions, VariantGetIndexedInsn.class))
        );
    }

    @Test
    void runKeepsVariantUnpackAtFinalCompoundAssignmentStoreBoundary() throws Exception {
        var prepared = prepareContext(
                "body_insn_compound_boundary.gd",
                """
                        class_name BodyInsnCompoundBoundary
                        extends RefCounted
                        
                        func ping(seed: Variant) -> int:
                            var count: int = 1
                            count += seed
                            return count
                        """,
                Map.of("BodyInsnCompoundBoundary", "RuntimeBodyInsnCompoundBoundary"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnCompoundBoundary",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var compoundInsn = requireOnlyInstruction(pingContext.targetFunction(), BinaryOpInsn.class);
        var unpackInsn = requireOnlyInstruction(pingContext.targetFunction(), UnpackVariantInsn.class);
        var assignSources = assignSourcesByTarget(instructions);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(GodotOperator.ADD, compoundInsn.op()),
                () -> assertEquals(compoundInsn.resultId(), unpackInsn.variantId()),
                () -> assertEquals(unpackInsn.resultId(), assignSources.get("count")),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class))
        );
    }

    @Test
    void runFailsFastWithCompoundSpecificMessageWhenPublishedOperatorLexemeIsInvalid() throws Exception {
        var prepared = prepareContext(
                "body_insn_compound_invalid_operator.gd",
                """
                        class_name BodyInsnCompoundInvalidOperator
                        extends RefCounted
                        
                        func ping(seed: int) -> int:
                            var count := seed
                            count += 1
                            return count
                        """,
                Map.of("BodyInsnCompoundInvalidOperator", "RuntimeBodyInsnCompoundInvalidOperator"),
                true
        );
        var originalContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnCompoundInvalidOperator",
                "ping"
        );
        var originalGraph = originalContext.requireFrontendCfgGraph();
        var originalCompoundItem = requireSingleValueProducerItem(originalGraph, CompoundAssignmentBinaryOpItem.class);
        var currentProducer = requireValueProducerByResultId(originalGraph, originalCompoundItem.currentTargetValueId());
        var rhsProducer = requireValueProducerByResultId(originalGraph, originalCompoundItem.rhsValueId());
        var originalAssignmentItem = requireSingleSequenceItem(originalGraph, AssignmentItem.class);
        var mutatedGraph = new FrontendCfgGraph(
                "seq_0",
                Map.of(
                        "seq_0",
                        new FrontendCfgGraph.SequenceNode(
                                "seq_0",
                                List.of(
                                        currentProducer,
                                        rhsProducer,
                                        new CompoundAssignmentBinaryOpItem(
                                                originalCompoundItem.assignment(),
                                                "??",
                                                originalCompoundItem.currentTargetValueId(),
                                                originalCompoundItem.rhsValueId(),
                                                originalCompoundItem.resultValueId()
                                        ),
                                        originalAssignmentItem
                                ),
                                "stop_1"
                        ),
                        "stop_1",
                        new FrontendCfgGraph.StopNode("stop_1", FrontendCfgGraph.StopKind.RETURN, null)
                )
        );
        var mutatedContext = new FunctionLoweringContext(
                originalContext.kind(),
                originalContext.sourcePath(),
                originalContext.sourceClassRelation(),
                originalContext.owningClass(),
                originalContext.targetFunction(),
                originalContext.sourceOwner(),
                originalContext.loweringRoot(),
                originalContext.analysisData()
        );
        mutatedContext.publishFrontendCfgGraph(mutatedGraph);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendBodyLoweringSession(
                        mutatedContext,
                        new ClassRegistry(ExtensionApiLoader.loadDefault())
                ).run()
        );

        assertTrue(exception.getMessage().contains("Compound assignment body-lowering contract"), exception.getMessage());
        assertTrue(exception.getMessage().contains("unsupported binary operator"), exception.getMessage());
    }

    @Test
    void runFailsFastWhenNonVoidExactCallLosesPublishedResultSlot() throws Exception {
        var prepared = prepareContext(
                "body_insn_missing_exact_call_result.gd",
                """
                        class_name BodyInsnMissingExactCallResult
                        extends RefCounted
                        
                        func helper(seed: int) -> int:
                            return seed + 1
                        
                        func ping(seed: int) -> void:
                            helper(seed)
                        """,
                Map.of(
                        "BodyInsnMissingExactCallResult",
                        "RuntimeBodyInsnMissingExactCallResult"
                ),
                true
        );
        var originalContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnMissingExactCallResult",
                "ping"
        );
        var originalGraph = originalContext.requireFrontendCfgGraph();
        var originalCallItem = requireSingleValueProducerItem(originalGraph, CallItem.class);
        var argumentProducer = requireValueProducerByResultId(
                originalGraph,
                originalCallItem.argumentValueIds().getFirst()
        );
        var mutatedGraph = new FrontendCfgGraph(
                "seq_0",
                Map.of(
                        "seq_0",
                        new FrontendCfgGraph.SequenceNode(
                                "seq_0",
                                List.of(
                                        argumentProducer,
                                        new CallItem(
                                                originalCallItem.anchor(),
                                                originalCallItem.callableName(),
                                                originalCallItem.receiverValueIdOrNull(),
                                                originalCallItem.argumentValueIds(),
                                                null,
                                                originalCallItem.writableRoutePayloadOrNull()
                                        )
                                ),
                                "stop_1"
                        ),
                        "stop_1",
                        new FrontendCfgGraph.StopNode("stop_1", FrontendCfgGraph.StopKind.RETURN, null)
                )
        );
        var mutatedContext = new FunctionLoweringContext(
                originalContext.kind(),
                originalContext.sourcePath(),
                originalContext.sourceClassRelation(),
                originalContext.owningClass(),
                originalContext.targetFunction(),
                originalContext.sourceOwner(),
                originalContext.loweringRoot(),
                originalContext.analysisData()
        );
        mutatedContext.publishFrontendCfgGraph(mutatedGraph);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendBodyLoweringSession(
                        mutatedContext,
                        new ClassRegistry(ExtensionApiLoader.loadDefault())
                ).run()
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("non-void exact call 'helper'"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("must publish resultValueIdOrNull"), exception.getMessage())
        );
    }

    @Test
    void runFailsFastWhenIdentifierValueBindingPretendsToBeSelf() throws Exception {
        var prepared = prepareContext(
                "body_insn_identifier_self_binding_value.gd",
                """
                        class_name BodyInsnIdentifierSelfBindingValue
                        extends RefCounted
                        
                        func ping(value: int) -> int:
                            return value
                        """,
                Map.of(
                        "BodyInsnIdentifierSelfBindingValue",
                        "RuntimeBodyInsnIdentifierSelfBindingValue"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnIdentifierSelfBindingValue",
                "ping"
        );
        var rootBlock = assertInstanceOf(dev.superice.gdparser.frontend.ast.Block.class, pingContext.loweringRoot());
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var value = assertInstanceOf(IdentifierExpression.class, returnStatement.value());
        rewriteBindingKindToSelf(pingContext.analysisData().symbolBindings(), value);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertTrue(exception.getMessage().contains("Identifier value lowering"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("explicit SelfExpression"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("binding kind SELF"), exception.getMessage())
        );
    }

    @Test
    void runFailsFastWhenDirectSlotReceiverIdentifierPretendsToBeSelf() throws Exception {
        var prepared = prepareContext(
                "body_insn_identifier_self_binding_receiver.gd",
                """
                        class_name BodyInsnIdentifierSelfBindingReceiver
                        extends RefCounted
                        
                        func ping(values: PackedInt32Array, seed: int) -> void:
                            values.push_back(seed)
                        """,
                Map.of(
                        "BodyInsnIdentifierSelfBindingReceiver",
                        "RuntimeBodyInsnIdentifierSelfBindingReceiver"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnIdentifierSelfBindingReceiver",
                "ping"
        );
        var rootBlock = assertInstanceOf(dev.superice.gdparser.frontend.ast.Block.class, pingContext.loweringRoot());
        var statement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, statement.expression());
        var receiver = assertInstanceOf(IdentifierExpression.class, expression.base());
        rewriteBindingKindToSelf(pingContext.analysisData().symbolBindings(), receiver);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertTrue(exception.getMessage().contains("DIRECT_SLOT writable root"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("explicit SelfExpression"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("binding kind SELF"), exception.getMessage())
        );
    }

    @Test
    void runSkipsOuterPropertyWritebackForSharedPropertyBackedAssignmentRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_shared_property_assignment.gd",
                """
                        class_name BodyInsnSharedPropertyAssignment
                        extends RefCounted
                        
                        var payloads: Dictionary[int, int]
                        
                        func ping(seed: int) -> int:
                            payloads[seed] = seed + 1
                            return payloads[seed]
                        """,
                Map.of("BodyInsnSharedPropertyAssignment", "RuntimeBodyInsnSharedPropertyAssignment"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSharedPropertyAssignment",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var indexedStores = instructions.stream()
                .filter(VariantSetIndexedInsn.class::isInstance)
                .map(VariantSetIndexedInsn.class::cast)
                .toList();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, indexedStores.size()),
                () -> assertEquals(0, storeValueIdsForProperty(instructions, "payloads").size()),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runSkipsOuterPropertyWritebackForSharedPropertyBackedCompoundAssignmentRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_shared_property_compound.gd",
                """
                        class_name BodyInsnSharedPropertyCompound
                        extends RefCounted
                        
                        var payloads: Dictionary[int, int]
                        
                        func delta(seed: int) -> int:
                            return seed + 1
                        
                        func ping(seed: int) -> int:
                            payloads[seed] += delta(seed)
                            return payloads[seed]
                        """,
                Map.of("BodyInsnSharedPropertyCompound", "RuntimeBodyInsnSharedPropertyCompound"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSharedPropertyCompound",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var compoundInsn = requireOnlyInstruction(pingContext.targetFunction(), BinaryOpInsn.class);
        var indexedStores = instructions.stream()
                .filter(VariantSetIndexedInsn.class::isInstance)
                .map(VariantSetIndexedInsn.class::cast)
                .toList();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(GodotOperator.ADD, compoundInsn.op()),
                () -> assertEquals(1, indexedStores.size()),
                () -> assertEquals(compoundInsn.resultId(), indexedStores.getFirst().valueId()),
                () -> assertEquals(0, storeValueIdsForProperty(instructions, "payloads").size()),
                () -> assertEquals(2, countInstructions(instructions, VariantGetIndexedInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

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
                () -> assertNull(callVarargInsn.resultId()),
                () -> assertEquals(1, countInstructions(varargInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(varargInstructions, UnpackVariantInsn.class)),
                () -> assertTrue(packResultIds(varargInstructions).contains(onlyVariableOperandId(callVarargInsn.args()))),
                () -> assertFalse(callVarargContext.targetFunction().getVariables().containsKey("cfg_tmp_v1")),
                () -> assertNull(callVarargVariantInsn.resultId()),
                () -> assertEquals(0, countInstructions(varargVariantInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(varargVariantInstructions, UnpackVariantInsn.class)),
                () -> assertNotNull(onlyVariableOperandId(callVarargVariantInsn.args())),
                () -> assertFalse(callVarargVariantContext.targetFunction().getVariables().containsKey("cfg_tmp_v1"))
        );
    }

    @Test
    void runConsumesDirectSlotReceiverPayloadWithoutCallingThroughCfgTempReceiver() throws Exception {
        var prepared = prepareContext(
                "body_insn_direct_slot_receiver_payload.gd",
                """
                        class_name BodyInsnDirectSlotReceiverPayload
                        extends RefCounted
                        
                        func ping(values: Array[int], seed: int) -> void:
                            values.push_back(seed)
                        """,
                Map.of(
                        "BodyInsnDirectSlotReceiverPayload",
                        "RuntimeBodyInsnDirectSlotReceiverPayload"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDirectSlotReceiverPayload",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var callInsn = requireOnlyInstruction(pingContext.targetFunction(), CallMethodInsn.class);
        var packInsn = requireOnlyInstruction(pingContext.targetFunction(), PackVariantInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("push_back", callInsn.methodName()),
                () -> assertEquals("values", callInsn.objectId()),
                () -> assertFalse(callInsn.objectId().startsWith("cfg_tmp_"), callInsn.objectId()),
                () -> assertNull(callInsn.resultId()),
                () -> assertEquals("cfg_tmp_v1", packInsn.valueId()),
                () -> assertEquals(packInsn.resultId(), onlyVariableOperandId(callInsn.args())),
                () -> assertFalse(pingContext.targetFunction().getVariables().containsKey("cfg_tmp_v0")),
                () -> assertFalse(pingContext.targetFunction().getVariables().containsKey("cfg_tmp_v2")),
                () -> assertTrue(assignSourcesByTarget(instructions).values().stream().noneMatch("values"::equals)),
                () -> assertEquals(1, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runKeepsDynamicConstLikeDirectSlotReceiverOnAliasSurface() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_const_like_direct_slot_receiver.gd",
                """
                        class_name BodyInsnDynamicConstLikeDirectSlotReceiver
                        extends RefCounted
                        
                        func ping(values) -> int:
                            return values.size()
                        """,
                Map.of(
                        "BodyInsnDynamicConstLikeDirectSlotReceiver",
                        "RuntimeBodyInsnDynamicConstLikeDirectSlotReceiver"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicConstLikeDirectSlotReceiver",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var callInsn = requireOnlyInstruction(pingContext.targetFunction(), CallMethodInsn.class);
        var unpackInsn = requireOnlyInstruction(pingContext.targetFunction(), UnpackVariantInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("size", callInsn.methodName()),
                () -> assertEquals("values", callInsn.objectId()),
                () -> assertFalse(callInsn.objectId().startsWith("cfg_tmp_"), callInsn.objectId()),
                () -> assertFalse(pingContext.targetFunction().getVariables().containsKey("cfg_tmp_v0")),
                () -> assertTrue(assignSourcesByTarget(instructions).values().stream().noneMatch("values"::equals)),
                () -> assertEquals(callInsn.resultId(), unpackInsn.variantId()),
                () -> assertEquals(0, countInstructions(instructions, CallGlobalInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, GoIfInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, StorePropertyInsn.class))
        );
    }

    @Test
    void runFallsBackToSnapshotReceiverWhenArgumentContainsNestedCall() throws Exception {
        var prepared = prepareContext(
                "body_insn_receiver_snapshot_fallback.gd",
                """
                        class_name BodyInsnReceiverSnapshotFallback
                        extends RefCounted
                        
                        func helper(value: int) -> int:
                            return value + 1
                        
                        func ping(values: Array[int], seed: int) -> void:
                            values.push_back(helper(seed))
                        """,
                Map.of(
                        "BodyInsnReceiverSnapshotFallback",
                        "RuntimeBodyInsnReceiverSnapshotFallback"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnReceiverSnapshotFallback",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var callMethodInstructions = instructions.stream()
                .filter(CallMethodInsn.class::isInstance)
                .map(CallMethodInsn.class::cast)
                .toList();
        var helperInsn = callMethodInstructions.stream()
                .filter(insn -> insn.methodName().equals("helper"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing helper call"));
        var pushBackInsn = callMethodInstructions.stream()
                .filter(insn -> insn.methodName().equals("push_back"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing push_back call"));
        var receiverSnapshotSource = assignSourcesByTarget(instructions).get(pushBackInsn.objectId());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(2, callMethodInstructions.size()),
                () -> assertNotNull(helperInsn.resultId()),
                () -> assertEquals("self", helperInsn.objectId()),
                () -> assertNull(pushBackInsn.resultId()),
                () -> assertTrue(pushBackInsn.objectId().startsWith("cfg_tmp_"), pushBackInsn.objectId()),
                () -> assertEquals("values", receiverSnapshotSource),
                () -> assertTrue(pingContext.targetFunction().getVariables().containsKey(pushBackInsn.objectId())),
                () -> assertNotEquals("values", pushBackInsn.objectId())
        );
    }

    @Test
    void runKeepsNonMutatingDirectSlotReceiverOnOrdinaryTempSurface() throws Exception {
        var prepared = prepareContext(
                "body_insn_non_mutating_direct_slot_receiver.gd",
                """
                        class_name BodyInsnNonMutatingDirectSlotReceiver
                        extends RefCounted
                        
                        func ping(values: PackedInt32Array) -> int:
                            return values.size()
                        """,
                Map.of(
                        "BodyInsnNonMutatingDirectSlotReceiver",
                        "RuntimeBodyInsnNonMutatingDirectSlotReceiver"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnNonMutatingDirectSlotReceiver",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var callInsn = requireOnlyInstruction(pingContext.targetFunction(), CallMethodInsn.class);
        var assignSources = assignSourcesByTarget(instructions);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("size", callInsn.methodName()),
                () -> assertEquals("cfg_tmp_v0", callInsn.objectId()),
                () -> assertEquals("values", assignSources.get("cfg_tmp_v0")),
                () -> assertTrue(pingContext.targetFunction().getVariables().containsKey("cfg_tmp_v0"))
        );
    }

    @Test
    void runWritesBackPropertyBackedValueSemanticReceiverAfterResolvedMutatingCall() throws Exception {
        var prepared = prepareContext(
                "body_insn_property_mutating_call.gd",
                """
                        class_name BodyInsnPropertyMutatingCall
                        extends RefCounted
                        
                        var payloads: PackedInt32Array
                        
                        func ping(seed: Variant) -> void:
                            payloads.push_back(seed)
                        """,
                Map.of(
                        "BodyInsnPropertyMutatingCall",
                        "RuntimeBodyInsnPropertyMutatingCall"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnPropertyMutatingCall",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var propertyLoad = requireOnlyInstruction(pingContext.targetFunction(), LoadPropertyInsn.class);
        var unpackInsn = requireOnlyInstruction(pingContext.targetFunction(), UnpackVariantInsn.class);
        var callInsn = requireOnlyInstruction(pingContext.targetFunction(), CallMethodInsn.class);
        var propertyStore = requireOnlyInstruction(pingContext.targetFunction(), StorePropertyInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("payloads", propertyLoad.propertyName()),
                () -> assertEquals("push_back", callInsn.methodName()),
                () -> assertEquals(propertyLoad.resultId(), callInsn.objectId()),
                () -> assertNotNull(callInsn.resultId()),
                () -> assertEquals(unpackInsn.resultId(), onlyVariableOperandId(callInsn.args())),
                () -> assertEquals("payloads", propertyStore.propertyName()),
                () -> assertEquals("self", propertyStore.objectId()),
                () -> assertEquals(callInsn.objectId(), propertyStore.valueId()),
                () -> assertTrue(instructionIndex(instructions, propertyLoad) < instructionIndex(instructions, unpackInsn)),
                () -> assertTrue(instructionIndex(instructions, unpackInsn) < instructionIndex(instructions, callInsn)),
                () -> assertTrue(instructionIndex(instructions, callInsn) < instructionIndex(instructions, propertyStore))
        );
    }

    @Test
    void runWritesBackNestedMutatingCallIntoSharedDictionaryElementWithoutOuterPropertyStore() throws Exception {
        var prepared = prepareContext(
                "body_insn_nested_mutating_call.gd",
                """
                        class_name BodyInsnNestedMutatingCall
                        extends RefCounted
                        
                        var payloads: Dictionary[int, PackedInt32Array]
                        
                        func ping(index: int, seed: int) -> void:
                            payloads[index].push_back(seed)
                        """,
                Map.of(
                        "BodyInsnNestedMutatingCall",
                        "RuntimeBodyInsnNestedMutatingCall"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnNestedMutatingCall",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var propertyLoad = requireOnlyInstruction(pingContext.targetFunction(), LoadPropertyInsn.class);
        var indexedLoad = requireOnlyInstruction(pingContext.targetFunction(), VariantGetIndexedInsn.class);
        var callInsn = requireOnlyInstruction(pingContext.targetFunction(), CallMethodInsn.class);
        var indexedStore = requireOnlyInstruction(pingContext.targetFunction(), VariantSetIndexedInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("payloads", propertyLoad.propertyName()),
                () -> assertEquals(propertyLoad.resultId(), indexedLoad.variantId()),
                () -> assertEquals(indexedLoad.resultId(), callInsn.objectId()),
                () -> assertEquals("push_back", callInsn.methodName()),
                () -> assertEquals(propertyLoad.resultId(), indexedStore.variantId()),
                () -> assertEquals(callInsn.objectId(), indexedStore.valueId()),
                () -> assertEquals(0, storeValueIdsForProperty(instructions, "payloads").size()),
                () -> assertTrue(instructionIndex(instructions, propertyLoad) < instructionIndex(instructions, indexedLoad)),
                () -> assertTrue(instructionIndex(instructions, indexedLoad) < instructionIndex(instructions, callInsn)),
                () -> assertTrue(instructionIndex(instructions, callInsn) < instructionIndex(instructions, indexedStore))
        );
    }

    @Test
    void runSkipsPropertyWritebackForConstValueSemanticMethodCall() throws Exception {
        var prepared = prepareContext(
                "body_insn_const_value_semantic_call.gd",
                """
                        class_name BodyInsnConstValueSemanticCall
                        extends RefCounted
                        
                        var payloads: PackedInt32Array
                        
                        func ping() -> int:
                            return payloads.size()
                        """,
                Map.of(
                        "BodyInsnConstValueSemanticCall",
                        "RuntimeBodyInsnConstValueSemanticCall"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnConstValueSemanticCall",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var propertyLoad = requireOnlyInstruction(pingContext.targetFunction(), LoadPropertyInsn.class);
        var callInsn = requireOnlyInstruction(pingContext.targetFunction(), CallMethodInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("payloads", propertyLoad.propertyName()),
                () -> assertEquals(propertyLoad.resultId(), callInsn.objectId()),
                () -> assertEquals("size", callInsn.methodName()),
                () -> assertEquals(0, storeValueIdsForProperty(instructions, "payloads").size())
        );
    }

    @Test
    void runSkipsPropertyWritebackForSharedArrayReceiverCall() throws Exception {
        var prepared = prepareContext(
                "body_insn_shared_array_receiver_call.gd",
                """
                        class_name BodyInsnSharedArrayReceiverCall
                        extends RefCounted
                        
                        var payloads: Array[int]
                        
                        func ping(seed: int) -> void:
                            payloads.append(seed)
                        """,
                Map.of(
                        "BodyInsnSharedArrayReceiverCall",
                        "RuntimeBodyInsnSharedArrayReceiverCall"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSharedArrayReceiverCall",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var propertyLoad = requireOnlyInstruction(pingContext.targetFunction(), LoadPropertyInsn.class);
        var callInsn = requireOnlyInstruction(pingContext.targetFunction(), CallMethodInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("payloads", propertyLoad.propertyName()),
                () -> assertEquals(propertyLoad.resultId(), callInsn.objectId()),
                () -> assertEquals("append", callInsn.methodName()),
                () -> assertEquals(0, storeValueIdsForProperty(instructions, "payloads").size())
        );
    }

    @Test
    void runSkipsPropertyWritebackForObjectReceiverCall() throws Exception {
        var prepared = prepareContext(
                "body_insn_object_receiver_call.gd",
                """
                        class_name BodyInsnObjectReceiverCall
                        extends RefCounted
                        
                        var host: Node
                        
                        func ping() -> void:
                            host.queue_free()
                        """,
                Map.of(
                        "BodyInsnObjectReceiverCall",
                        "RuntimeBodyInsnObjectReceiverCall"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnObjectReceiverCall",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var propertyLoad = requireOnlyInstruction(pingContext.targetFunction(), LoadPropertyInsn.class);
        var callInsn = requireOnlyInstruction(pingContext.targetFunction(), CallMethodInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("host", propertyLoad.propertyName()),
                () -> assertEquals(propertyLoad.resultId(), callInsn.objectId()),
                () -> assertEquals("queue_free", callInsn.methodName()),
                () -> assertEquals(0, storeValueIdsForProperty(instructions, "host").size())
        );
    }

    @Test
    void runLowersDynamicInstanceCallsIntoCallMethodInsnWithVariantResultSlot() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_call.gd",
                """
                        class_name BodyInsnDynamicCall
                        extends RefCounted
                        
                        func ping(worker):
                            return worker.ping()
                        """,
                Map.of("BodyInsnDynamicCall", "RuntimeBodyInsnDynamicCall"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicCall",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var callInsn = requireOnlyInstruction(pingContext.targetFunction(), CallMethodInsn.class);
        var returnInsn = requireOnlyReturnInsn(pingContext.targetFunction());
        var callResultId = java.util.Objects.requireNonNull(callInsn.resultId());
        var resultVariable = pingContext.targetFunction().getVariableById(callResultId);

        assertNotNull(resultVariable, () -> "Missing lowered variable for " + callResultId);
        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("worker", callInsn.objectId()),
                () -> assertEquals("ping", callInsn.methodName()),
                () -> assertEquals(GdVariantType.VARIANT, resultVariable.type()),
                () -> assertEquals(callResultId, returnInsn.returnValueId()),
                () -> assertFalse(pingContext.targetFunction().getVariables().containsKey("cfg_tmp_v0")),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runLetsDynamicCallResultsCrossTypedCallBoundariesThroughOrdinaryUnpack() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_call_boundary.gd",
                """
                        class_name BodyInsnDynamicCallBoundary
                        extends RefCounted
                        
                        func take_i(value: int) -> int:
                            return value
                        
                        func ping(worker) -> int:
                            return take_i(worker.size())
                        """,
                Map.of("BodyInsnDynamicCallBoundary", "RuntimeBodyInsnDynamicCallBoundary"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicCallBoundary",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var methodCalls = instructions.stream()
                .filter(CallMethodInsn.class::isInstance)
                .map(CallMethodInsn.class::cast)
                .toList();
        var dynamicSizeCall = methodCalls.stream()
                .filter(instruction -> instruction.methodName().equals("size"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing dynamic size() call"));
        var exactTakeCall = methodCalls.stream()
                .filter(instruction -> instruction.methodName().equals("take_i"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing exact take_i() call"));
        var dynamicResultId = java.util.Objects.requireNonNull(dynamicSizeCall.resultId());
        var dynamicResultVariable = pingContext.targetFunction().getVariableById(dynamicResultId);
        var unpackInsn = requireOnlyInstruction(pingContext.targetFunction(), UnpackVariantInsn.class);

        assertNotNull(dynamicResultVariable, () -> "Missing lowered variable for " + dynamicResultId);
        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("worker", dynamicSizeCall.objectId()),
                () -> assertEquals(GdVariantType.VARIANT, dynamicResultVariable.type()),
                () -> assertEquals(dynamicResultId, unpackInsn.variantId()),
                () -> assertEquals(unpackInsn.resultId(), onlyVariableOperandId(exactTakeCall.args())),
                () -> assertFalse(pingContext.targetFunction().getVariables().containsKey("cfg_tmp_v0")),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class))
        );
    }

    @Test
    void runEmitsRuntimeGatedPropertyWritebackForDynamicReceiverAndThreadsContinuationBlock() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_property_mutating_call.gd",
                """
                        class_name BodyInsnDynamicPropertyMutatingCall
                        extends RefCounted
                        
                        var payloads: Variant
                        
                        func ping(seed: int) -> Variant:
                            payloads.push_back(seed)
                            return payloads
                        """,
                Map.of(
                        "BodyInsnDynamicPropertyMutatingCall",
                        "RuntimeBodyInsnDynamicPropertyMutatingCall"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicPropertyMutatingCall",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = pingContext.targetFunction();
        var instructions = allInstructions(function);
        var entryBlock = requireBlock(function, "seq_0");
        var entryLoads = entryBlock.getNonTerminatorInstructions().stream()
                .filter(LoadPropertyInsn.class::isInstance)
                .map(LoadPropertyInsn.class::cast)
                .toList();
        var entryCalls = entryBlock.getNonTerminatorInstructions().stream()
                .filter(CallMethodInsn.class::isInstance)
                .map(CallMethodInsn.class::cast)
                .toList();
        var gateCalls = entryBlock.getNonTerminatorInstructions().stream()
                .filter(CallGlobalInsn.class::isInstance)
                .map(CallGlobalInsn.class::cast)
                .toList();
        var gateBranch = assertInstanceOf(GoIfInsn.class, entryBlock.getTerminator());
        var applyBlock = requireBlock(function, gateBranch.trueBbId());
        var skipBlock = requireBlock(function, gateBranch.falseBbId());
        var applyStore = assertInstanceOf(StorePropertyInsn.class, applyBlock.getNonTerminatorInstructions().getFirst());
        var applyGoto = assertInstanceOf(GotoInsn.class, applyBlock.getTerminator());
        var skipGoto = assertInstanceOf(GotoInsn.class, skipBlock.getTerminator());
        var continuationBlock = requireBlock(function, applyGoto.targetBbId());
        var continuationLoads = continuationBlock.getNonTerminatorInstructions().stream()
                .filter(LoadPropertyInsn.class::isInstance)
                .map(LoadPropertyInsn.class::cast)
                .toList();
        var sequenceGoto = assertInstanceOf(GotoInsn.class, continuationBlock.getTerminator());
        var stopBlock = requireBlock(function, sequenceGoto.targetBbId());
        var returnInsn = assertInstanceOf(ReturnInsn.class, stopBlock.getTerminator());
        var entryLoad = entryLoads.getFirst();
        var callInsn = entryCalls.getFirst();
        var gateCallInsn = gateCalls.getFirst();
        var continuationLoad = continuationLoads.getFirst();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, entryLoads.size()),
                () -> assertEquals(1, entryCalls.size()),
                () -> assertEquals(1, gateCalls.size()),
                () -> assertEquals("payloads", entryLoad.propertyName()),
                () -> assertEquals("self", entryLoad.objectId()),
                () -> assertEquals("push_back", callInsn.methodName()),
                () -> assertEquals(entryLoad.resultId(), callInsn.objectId()),
                () -> assertEquals("gdcc_variant_requires_writeback", gateCallInsn.functionName()),
                () -> assertEquals(callInsn.objectId(), onlyVariableOperandId(gateCallInsn.args())),
                () -> assertEquals(gateCallInsn.resultId(), gateBranch.conditionVarId()),
                () -> assertEquals("payloads", applyStore.propertyName()),
                () -> assertEquals("self", applyStore.objectId()),
                () -> assertEquals(callInsn.objectId(), applyStore.valueId()),
                () -> assertEquals(applyGoto.targetBbId(), skipGoto.targetBbId()),
                () -> assertEquals(1, continuationLoads.size()),
                () -> assertEquals("payloads", continuationLoad.propertyName()),
                () -> assertEquals("self", continuationLoad.objectId()),
                () -> assertEquals(continuationLoad.resultId(), returnInsn.returnValueId()),
                () -> assertEquals(1, countInstructions(instructions, CallGlobalInsn.class)),
                () -> assertEquals(1, countInstructions(instructions, GoIfInsn.class)),
                () -> assertEquals(1, countInstructions(instructions, StorePropertyInsn.class)),
                () -> assertTrue(instructionIndex(instructions, callInsn) < instructionIndex(instructions, gateCallInsn)),
                () -> assertTrue(instructionIndex(instructions, gateCallInsn) < instructionIndex(instructions, applyStore)),
                () -> assertTrue(instructionIndex(instructions, applyStore) < instructionIndex(instructions, continuationLoad))
        );
    }

    @Test
    void runStillEmitsRuntimeGatedWritebackForDynamicConstLikePropertyReceiver() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_const_like_property_call.gd",
                """
                        class_name BodyInsnDynamicConstLikePropertyCall
                        extends RefCounted
                        
                        var payloads: Variant
                        
                        func ping() -> int:
                            return payloads.size()
                        """,
                Map.of(
                        "BodyInsnDynamicConstLikePropertyCall",
                        "RuntimeBodyInsnDynamicConstLikePropertyCall"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicConstLikePropertyCall",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = pingContext.targetFunction();
        var instructions = allInstructions(function);
        var entryBlock = requireBlock(function, "seq_0");
        var entryLoads = entryBlock.getNonTerminatorInstructions().stream()
                .filter(LoadPropertyInsn.class::isInstance)
                .map(LoadPropertyInsn.class::cast)
                .toList();
        var entryCalls = entryBlock.getNonTerminatorInstructions().stream()
                .filter(CallMethodInsn.class::isInstance)
                .map(CallMethodInsn.class::cast)
                .toList();
        var gateCalls = entryBlock.getNonTerminatorInstructions().stream()
                .filter(CallGlobalInsn.class::isInstance)
                .map(CallGlobalInsn.class::cast)
                .toList();
        var gateBranch = assertInstanceOf(GoIfInsn.class, entryBlock.getTerminator());
        var applyBlock = requireBlock(function, gateBranch.trueBbId());
        var skipBlock = requireBlock(function, gateBranch.falseBbId());
        var applyStore = assertInstanceOf(StorePropertyInsn.class, applyBlock.getNonTerminatorInstructions().getFirst());
        var applyGoto = assertInstanceOf(GotoInsn.class, applyBlock.getTerminator());
        var skipGoto = assertInstanceOf(GotoInsn.class, skipBlock.getTerminator());
        var continuationBlock = requireBlock(function, applyGoto.targetBbId());
        var continuationGoto = assertInstanceOf(GotoInsn.class, continuationBlock.getTerminator());
        var stopBlock = requireBlock(function, continuationGoto.targetBbId());
        var stopUnpacks = stopBlock.getNonTerminatorInstructions().stream()
                .filter(UnpackVariantInsn.class::isInstance)
                .map(UnpackVariantInsn.class::cast)
                .toList();
        var returnInsn = assertInstanceOf(ReturnInsn.class, stopBlock.getTerminator());
        var entryLoad = entryLoads.getFirst();
        var callInsn = entryCalls.getFirst();
        var gateCallInsn = gateCalls.getFirst();
        var unpackInsn = stopUnpacks.getFirst();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, entryLoads.size()),
                () -> assertEquals(1, entryCalls.size()),
                () -> assertEquals(1, gateCalls.size()),
                () -> assertEquals("payloads", entryLoad.propertyName()),
                () -> assertEquals("self", entryLoad.objectId()),
                () -> assertEquals("size", callInsn.methodName()),
                () -> assertEquals(entryLoad.resultId(), callInsn.objectId()),
                () -> assertEquals("gdcc_variant_requires_writeback", gateCallInsn.functionName()),
                () -> assertEquals(callInsn.objectId(), onlyVariableOperandId(gateCallInsn.args())),
                () -> assertEquals(gateCallInsn.resultId(), gateBranch.conditionVarId()),
                () -> assertEquals("payloads", applyStore.propertyName()),
                () -> assertEquals("self", applyStore.objectId()),
                () -> assertEquals(callInsn.objectId(), applyStore.valueId()),
                () -> assertEquals(applyGoto.targetBbId(), skipGoto.targetBbId()),
                () -> assertEquals(1, stopUnpacks.size()),
                () -> assertEquals(callInsn.resultId(), unpackInsn.variantId()),
                () -> assertEquals(unpackInsn.resultId(), returnInsn.returnValueId()),
                () -> assertEquals(1, countInstructions(instructions, CallGlobalInsn.class)),
                () -> assertEquals(1, countInstructions(instructions, GoIfInsn.class)),
                () -> assertEquals(1, countInstructions(instructions, StorePropertyInsn.class)),
                () -> assertTrue(instructionIndex(instructions, callInsn) < instructionIndex(instructions, gateCallInsn)),
                () -> assertTrue(instructionIndex(instructions, gateCallInsn) < instructionIndex(instructions, applyStore))
        );
    }

    @Test
    void runEmitsRuntimeGatedWritebackForExplicitVariantPropertyOnObjectReceiver() throws Exception {
        var prepared = prepareContext(
                "body_insn_object_variant_property_mutating_call.gd",
                """
                        class_name BodyInsnObjectVariantPropertyMutatingCall
                        extends RefCounted
                        
                        var payloads: Variant
                        
                        func ping(box: BodyInsnObjectVariantPropertyMutatingCall, seed: int) -> void:
                            box.payloads.push_back(seed)
                        """,
                Map.of(
                        "BodyInsnObjectVariantPropertyMutatingCall",
                        "RuntimeBodyInsnObjectVariantPropertyMutatingCall"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnObjectVariantPropertyMutatingCall",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = pingContext.targetFunction();
        var instructions = allInstructions(function);
        var assignSources = assignSourcesByTarget(instructions);
        var entryBlock = requireBlock(function, "seq_0");
        var entryLoads = entryBlock.getNonTerminatorInstructions().stream()
                .filter(LoadPropertyInsn.class::isInstance)
                .map(LoadPropertyInsn.class::cast)
                .toList();
        var entryCalls = entryBlock.getNonTerminatorInstructions().stream()
                .filter(CallMethodInsn.class::isInstance)
                .map(CallMethodInsn.class::cast)
                .toList();
        var gateCalls = entryBlock.getNonTerminatorInstructions().stream()
                .filter(CallGlobalInsn.class::isInstance)
                .map(CallGlobalInsn.class::cast)
                .toList();
        var gateBranch = assertInstanceOf(GoIfInsn.class, entryBlock.getTerminator());
        var applyBlock = requireBlock(function, gateBranch.trueBbId());
        var skipBlock = requireBlock(function, gateBranch.falseBbId());
        var applyStore = assertInstanceOf(StorePropertyInsn.class, applyBlock.getNonTerminatorInstructions().getFirst());
        var applyGoto = assertInstanceOf(GotoInsn.class, applyBlock.getTerminator());
        var skipGoto = assertInstanceOf(GotoInsn.class, skipBlock.getTerminator());
        var entryLoad = entryLoads.getFirst();
        var callInsn = entryCalls.getFirst();
        var gateCallInsn = gateCalls.getFirst();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, entryLoads.size()),
                () -> assertEquals(1, entryCalls.size()),
                () -> assertEquals(1, gateCalls.size()),
                () -> assertEquals("payloads", entryLoad.propertyName()),
                () -> assertTrue(entryLoad.objectId().startsWith("cfg_tmp_"), entryLoad.objectId()),
                () -> assertEquals("box", assignSources.get(entryLoad.objectId())),
                () -> assertTrue(function.getVariables().containsKey(entryLoad.objectId())),
                () -> assertNotEquals("box", entryLoad.objectId()),
                () -> assertEquals("push_back", callInsn.methodName()),
                () -> assertEquals(entryLoad.resultId(), callInsn.objectId()),
                () -> assertEquals("gdcc_variant_requires_writeback", gateCallInsn.functionName()),
                () -> assertEquals(callInsn.objectId(), onlyVariableOperandId(gateCallInsn.args())),
                () -> assertEquals(gateCallInsn.resultId(), gateBranch.conditionVarId()),
                () -> assertEquals("payloads", applyStore.propertyName()),
                () -> assertEquals("box", applyStore.objectId()),
                () -> assertEquals(callInsn.objectId(), applyStore.valueId()),
                () -> assertEquals(applyGoto.targetBbId(), skipGoto.targetBbId())
        );
    }

    @Test
    void runThreadsDynamicKeyMutatingCallContinuationIntoOuterSubscriptRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_key_mutating_call.gd",
                """
                        class_name BodyInsnDynamicKeyMutatingCall
                        extends RefCounted
                        
                        var payloads: Dictionary[Variant, PackedInt32Array]
                        var keys: Variant
                        
                        func ping(seed: int) -> void:
                            payloads[keys.push_back(seed)].push_back(seed)
                        """,
                Map.of(
                        "BodyInsnDynamicKeyMutatingCall",
                        "RuntimeBodyInsnDynamicKeyMutatingCall"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicKeyMutatingCall",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = pingContext.targetFunction();
        var instructions = allInstructions(function);
        var entryBlock = requireBlock(function, "seq_0");
        var entryLoads = entryBlock.getNonTerminatorInstructions().stream()
                .filter(LoadPropertyInsn.class::isInstance)
                .map(LoadPropertyInsn.class::cast)
                .toList();
        var entryCalls = entryBlock.getNonTerminatorInstructions().stream()
                .filter(CallMethodInsn.class::isInstance)
                .map(CallMethodInsn.class::cast)
                .toList();
        var gateCalls = entryBlock.getNonTerminatorInstructions().stream()
                .filter(CallGlobalInsn.class::isInstance)
                .map(CallGlobalInsn.class::cast)
                .toList();
        var gateBranch = assertInstanceOf(GoIfInsn.class, entryBlock.getTerminator());
        var applyBlock = requireBlock(function, gateBranch.trueBbId());
        var skipBlock = requireBlock(function, gateBranch.falseBbId());
        var applyStore = assertInstanceOf(StorePropertyInsn.class, applyBlock.getNonTerminatorInstructions().getFirst());
        var applyGoto = assertInstanceOf(GotoInsn.class, applyBlock.getTerminator());
        var skipGoto = assertInstanceOf(GotoInsn.class, skipBlock.getTerminator());
        var continuationBlock = requireBlock(function, applyGoto.targetBbId());
        var continuationGets = continuationBlock.getNonTerminatorInstructions().stream()
                .filter(VariantGetInsn.class::isInstance)
                .map(VariantGetInsn.class::cast)
                .toList();
        var continuationCalls = continuationBlock.getNonTerminatorInstructions().stream()
                .filter(CallMethodInsn.class::isInstance)
                .map(CallMethodInsn.class::cast)
                .toList();
        var continuationStores = continuationBlock.getNonTerminatorInstructions().stream()
                .filter(VariantSetInsn.class::isInstance)
                .map(VariantSetInsn.class::cast)
                .toList();
        var continuationGoto = assertInstanceOf(GotoInsn.class, continuationBlock.getTerminator());
        var stopBlock = requireBlock(function, continuationGoto.targetBbId());
        var returnInsn = assertInstanceOf(ReturnInsn.class, stopBlock.getTerminator());
        var payloadsLoad = entryLoads.stream()
                .filter(instruction -> instruction.propertyName().equals("payloads"))
                .findFirst()
                .orElseThrow();
        var keysLoad = entryLoads.stream()
                .filter(instruction -> instruction.propertyName().equals("keys"))
                .findFirst()
                .orElseThrow();
        var innerCallInsn = entryCalls.getFirst();
        var gateCallInsn = gateCalls.getFirst();
        var outerGetInsn = continuationGets.getFirst();
        var outerCallInsn = continuationCalls.getFirst();
        var outerStoreInsn = continuationStores.getFirst();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(2, entryLoads.size()),
                () -> assertEquals(1, entryCalls.size()),
                () -> assertEquals(1, gateCalls.size()),
                () -> assertEquals("payloads", payloadsLoad.propertyName()),
                () -> assertEquals("self", payloadsLoad.objectId()),
                () -> assertEquals("keys", keysLoad.propertyName()),
                () -> assertEquals("self", keysLoad.objectId()),
                () -> assertEquals("push_back", innerCallInsn.methodName()),
                () -> assertEquals(keysLoad.resultId(), innerCallInsn.objectId()),
                () -> assertEquals("gdcc_variant_requires_writeback", gateCallInsn.functionName()),
                () -> assertEquals(innerCallInsn.objectId(), onlyVariableOperandId(gateCallInsn.args())),
                () -> assertEquals(gateCallInsn.resultId(), gateBranch.conditionVarId()),
                () -> assertEquals("keys", applyStore.propertyName()),
                () -> assertEquals("self", applyStore.objectId()),
                () -> assertEquals(innerCallInsn.objectId(), applyStore.valueId()),
                () -> assertEquals(applyGoto.targetBbId(), skipGoto.targetBbId()),
                () -> assertEquals(1, continuationGets.size()),
                () -> assertEquals(payloadsLoad.resultId(), outerGetInsn.variantId()),
                () -> assertEquals(innerCallInsn.resultId(), outerGetInsn.keyId()),
                () -> assertEquals(1, continuationCalls.size()),
                () -> assertEquals("push_back", outerCallInsn.methodName()),
                () -> assertEquals(outerGetInsn.resultId(), outerCallInsn.objectId()),
                () -> assertEquals(1, continuationStores.size()),
                () -> assertEquals(payloadsLoad.resultId(), outerStoreInsn.variantId()),
                () -> assertEquals(innerCallInsn.resultId(), outerStoreInsn.keyId()),
                () -> assertEquals(outerCallInsn.objectId(), outerStoreInsn.valueId()),
                () -> assertNull(returnInsn.returnValueId()),
                () -> assertEquals(1, countInstructions(instructions, CallGlobalInsn.class)),
                () -> assertEquals(1, countInstructions(instructions, GoIfInsn.class)),
                () -> assertEquals(1, countInstructions(instructions, StorePropertyInsn.class)),
                () -> assertEquals(1, countInstructions(instructions, VariantGetInsn.class)),
                () -> assertEquals(1, countInstructions(instructions, VariantSetInsn.class)),
                () -> assertEquals(0, storeValueIdsForProperty(instructions, "payloads").size()),
                () -> assertTrue(instructionIndex(instructions, payloadsLoad) < instructionIndex(instructions, keysLoad)),
                () -> assertTrue(instructionIndex(instructions, keysLoad) < instructionIndex(instructions, innerCallInsn)),
                () -> assertTrue(instructionIndex(instructions, innerCallInsn) < instructionIndex(instructions, gateCallInsn)),
                () -> assertTrue(instructionIndex(instructions, gateCallInsn) < instructionIndex(instructions, applyStore)),
                () -> assertTrue(instructionIndex(instructions, applyStore) < instructionIndex(instructions, outerGetInsn)),
                () -> assertTrue(instructionIndex(instructions, outerGetInsn) < instructionIndex(instructions, outerCallInsn)),
                () -> assertTrue(instructionIndex(instructions, outerCallInsn) < instructionIndex(instructions, outerStoreInsn))
        );
    }

    @Test
    void runLowersExplicitSelfMutatingReceiverWithoutReceiverDeadTemp() throws Exception {
        var prepared = prepareContext(
                "body_insn_self_receiver_alias.gd",
                """
                        class_name BodyInsnSelfReceiverAlias
                        extends RefCounted
                        
                        func touch(seed: int) -> void:
                            pass
                        
                        func ping(seed: int) -> void:
                            self.touch(seed)
                        """,
                Map.of("BodyInsnSelfReceiverAlias", "RuntimeBodyInsnSelfReceiverAlias"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSelfReceiverAlias",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var callInsn = requireOnlyInstruction(pingContext.targetFunction(), CallMethodInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("self", callInsn.objectId()),
                () -> assertEquals("touch", callInsn.methodName()),
                () -> assertFalse(pingContext.targetFunction().getVariables().containsKey("cfg_tmp_v0")),
                () -> assertTrue(assignSourcesByTarget(instructions).values().stream().noneMatch("self"::equals))
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
                () -> assertNull(function.getBasicBlock(terminalMergeStopIds.getFirst())),
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
    void runLowersUnaryVariantBuiltinConstructorsIntoUnpackVariantInsn() throws Exception {
        var prepared = prepareContext(
                "body_insn_variant_constructor_unpack.gd",
                """
                        class_name BodyInsnVariantConstructorUnpack
                        extends RefCounted
                        
                        func build_int(seed: Variant) -> int:
                            return int(seed)
                        
                        func build_string(seed: Variant) -> String:
                            return String(seed)
                        
                        func build_array(seed: Variant) -> Array:
                            return Array(seed)
                        
                        func build_dictionary(seed: Variant) -> Dictionary:
                            return Dictionary(seed)
                        """,
                Map.of(
                        "BodyInsnVariantConstructorUnpack",
                        "RuntimeBodyInsnVariantConstructorUnpack"
                ),
                true
        );
        var buildIntContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnVariantConstructorUnpack",
                "build_int"
        );
        var buildStringContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnVariantConstructorUnpack",
                "build_string"
        );
        var buildArrayContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnVariantConstructorUnpack",
                "build_array"
        );
        var buildDictionaryContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnVariantConstructorUnpack",
                "build_dictionary"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var intInstructions = allInstructions(buildIntContext.targetFunction());
        var stringInstructions = allInstructions(buildStringContext.targetFunction());
        var arrayInstructions = allInstructions(buildArrayContext.targetFunction());
        var dictionaryInstructions = allInstructions(buildDictionaryContext.targetFunction());

        var intUnpackInsn = requireOnlyInstruction(buildIntContext.targetFunction(), UnpackVariantInsn.class);
        var stringUnpackInsn = requireOnlyInstruction(buildStringContext.targetFunction(), UnpackVariantInsn.class);
        var arrayUnpackInsn = requireOnlyInstruction(buildArrayContext.targetFunction(), UnpackVariantInsn.class);
        var dictionaryUnpackInsn = requireOnlyInstruction(buildDictionaryContext.targetFunction(), UnpackVariantInsn.class);

        var intReturnInsn = requireOnlyReturnInsn(buildIntContext.targetFunction());
        var stringReturnInsn = requireOnlyReturnInsn(buildStringContext.targetFunction());
        var arrayReturnInsn = requireOnlyReturnInsn(buildArrayContext.targetFunction());
        var dictionaryReturnInsn = requireOnlyReturnInsn(buildDictionaryContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, countInstructions(intInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(intInstructions, ConstructBuiltinInsn.class)),
                () -> assertEquals(intUnpackInsn.resultId(), intReturnInsn.returnValueId()),
                () -> assertEquals(1, countInstructions(stringInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(stringInstructions, ConstructBuiltinInsn.class)),
                () -> assertEquals(stringUnpackInsn.resultId(), stringReturnInsn.returnValueId()),
                () -> assertEquals(1, countInstructions(arrayInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(arrayInstructions, ConstructBuiltinInsn.class)),
                () -> assertEquals(arrayUnpackInsn.resultId(), arrayReturnInsn.returnValueId()),
                () -> assertEquals(1, countInstructions(dictionaryInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(dictionaryInstructions, ConstructBuiltinInsn.class)),
                () -> assertEquals(dictionaryUnpackInsn.resultId(), dictionaryReturnInsn.returnValueId())
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
        var originalReturnType = java.util.Objects.requireNonNull(originalResolvedCall.returnType());

        prepared.context().requireAnalysisData().resolvedCalls().put(
                callAnchor,
                FrontendResolvedCall.resolved(
                        originalResolvedCall.callableName(),
                        originalResolvedCall.callKind(),
                        originalResolvedCall.receiverKind(),
                        originalResolvedCall.ownerKind(),
                        originalResolvedCall.receiverType(),
                        originalReturnType,
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
    void runUsesPublishedExactBoundaryForExactEngineMethodWithoutReReadingCallableMetadata() throws Exception {
        var prepared = prepareContext(
                "body_insn_exact_engine_metadata_regression.gd",
                """
                        class_name BodyInsnExactEngineMetadataRegression
                        extends Node
                        
                        func attach(child: Node):
                            self.add_child(child)
                        """,
                Map.of("BodyInsnExactEngineMetadataRegression", "RuntimeBodyInsnExactEngineMetadataRegression"),
                true
        );
        var attachContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnExactEngineMetadataRegression",
                "attach"
        );
        var callAnchor = requireSingleCallAnchor(attachContext.requireFrontendCfgGraph());
        var publishedCall = prepared.context().requireAnalysisData().resolvedCalls().get(callAnchor);
        assertNotNull(publishedCall);
        var publishedReturnType = java.util.Objects.requireNonNull(publishedCall.returnType());
        var exactBoundary = java.util.Objects.requireNonNull(publishedCall.exactCallableBoundary());

        prepared.context().requireAnalysisData().resolvedCalls().put(
                callAnchor,
                FrontendResolvedCall.resolved(
                        publishedCall.callableName(),
                        publishedCall.callKind(),
                        publishedCall.receiverKind(),
                        publishedCall.ownerKind(),
                        publishedCall.receiverType(),
                        publishedReturnType,
                        publishedCall.argumentTypes(),
                        new Object(),
                        exactBoundary
                )
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var callInsn = requireOnlyInstruction(attachContext.targetFunction(), CallMethodInsn.class);
        var instructions = allInstructions(attachContext.targetFunction());
        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("add_child", callInsn.methodName()),
                () -> assertEquals("self", callInsn.objectId()),
                () -> assertEquals(1, callInsn.args().size()),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runFailsFastWhenExactInstanceCallLosesPublishedCallableBoundary() throws Exception {
        var prepared = prepareContext(
                "body_insn_exact_engine_metadata_missing_boundary.gd",
                """
                        class_name BodyInsnExactEngineMetadataMissingBoundary
                        extends Node
                        
                        func attach(child: Node):
                            self.add_child(child)
                        """,
                Map.of("BodyInsnExactEngineMetadataMissingBoundary", "RuntimeBodyInsnExactEngineMetadataMissingBoundary"),
                true
        );
        var attachContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnExactEngineMetadataMissingBoundary",
                "attach"
        );
        var callAnchor = requireSingleCallAnchor(attachContext.requireFrontendCfgGraph());
        var publishedCall = prepared.context().requireAnalysisData().resolvedCalls().get(callAnchor);
        assertNotNull(publishedCall);
        assertNotNull(publishedCall.exactCallableBoundary());
        var publishedReturnType = java.util.Objects.requireNonNull(publishedCall.returnType());

        prepared.context().requireAnalysisData().resolvedCalls().put(
                callAnchor,
                FrontendResolvedCall.resolved(
                        publishedCall.callableName(),
                        publishedCall.callKind(),
                        publishedCall.receiverKind(),
                        publishedCall.ownerKind(),
                        publishedCall.receiverType(),
                        publishedReturnType,
                        publishedCall.argumentTypes(),
                        publishedCall.declarationSite()
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertAll(
                () -> assertTrue(
                        exception.getMessage().contains("missing published callable boundary metadata"),
                        exception.getMessage()
                ),
                () -> assertTrue(
                        exception.getMessage().contains("required for argument materialization"),
                        exception.getMessage()
                ),
                () -> assertFalse(
                        exception.getMessage().contains("callable signature metadata"),
                        exception.getMessage()
                )
        );
    }

    @Test
    void runUsesPublishedExactBoundaryAcrossExtensionMetadataFamiliesWithoutReReadingRawMetadata() throws Exception {
        var prepared = prepareContext(
                "body_insn_exact_engine_metadata_families.gd",
                """
                        class_name BodyInsnExactEngineMetadataFamilies
                        extends RefCounted
                        
                        func enum_case(holder: Node, child: Node):
                            holder.add_child(child)
                        
                        func bitfield_case(holder: Node):
                            holder.set_process_thread_messages(0)
                        
                        func typedarray_case(mesh: ArrayMesh, arrays: Array):
                            mesh.add_surface_from_arrays(0, arrays)
                        """,
                Map.of("BodyInsnExactEngineMetadataFamilies", "RuntimeBodyInsnExactEngineMetadataFamilies"),
                true
        );
        var enumContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnExactEngineMetadataFamilies",
                "enum_case"
        );
        var bitfieldContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnExactEngineMetadataFamilies",
                "bitfield_case"
        );
        var typedarrayContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnExactEngineMetadataFamilies",
                "typedarray_case"
        );
        var enumAnchor = requireSingleCallAnchor(enumContext.requireFrontendCfgGraph());
        var bitfieldAnchor = requireSingleCallAnchor(bitfieldContext.requireFrontendCfgGraph());
        var typedarrayAnchor = requireSingleCallAnchor(typedarrayContext.requireFrontendCfgGraph());
        var enumCall = java.util.Objects.requireNonNull(
                prepared.context().requireAnalysisData().resolvedCalls().get(enumAnchor),
                "Missing resolved call for enum metadata anchor"
        );
        var bitfieldCall = java.util.Objects.requireNonNull(
                prepared.context().requireAnalysisData().resolvedCalls().get(bitfieldAnchor),
                "Missing resolved call for bitfield metadata anchor"
        );
        var typedarrayCall = java.util.Objects.requireNonNull(
                prepared.context().requireAnalysisData().resolvedCalls().get(typedarrayAnchor),
                "Missing resolved call for typedarray metadata anchor"
        );

        // Replace raw declaration metadata with opaque markers. Successful lowering after this point
        // proves the exact routes are consuming the published boundary instead of reparsing metadata.
        prepared.context().requireAnalysisData().resolvedCalls().put(
                enumAnchor,
                FrontendResolvedCall.resolved(
                        enumCall.callableName(),
                        enumCall.callKind(),
                        enumCall.receiverKind(),
                        enumCall.ownerKind(),
                        enumCall.receiverType(),
                        java.util.Objects.requireNonNull(enumCall.returnType()),
                        enumCall.argumentTypes(),
                        new Object(),
                        java.util.Objects.requireNonNull(enumCall.exactCallableBoundary())
                )
        );
        prepared.context().requireAnalysisData().resolvedCalls().put(
                bitfieldAnchor,
                FrontendResolvedCall.resolved(
                        bitfieldCall.callableName(),
                        bitfieldCall.callKind(),
                        bitfieldCall.receiverKind(),
                        bitfieldCall.ownerKind(),
                        bitfieldCall.receiverType(),
                        java.util.Objects.requireNonNull(bitfieldCall.returnType()),
                        bitfieldCall.argumentTypes(),
                        new Object(),
                        java.util.Objects.requireNonNull(bitfieldCall.exactCallableBoundary())
                )
        );
        prepared.context().requireAnalysisData().resolvedCalls().put(
                typedarrayAnchor,
                FrontendResolvedCall.resolved(
                        typedarrayCall.callableName(),
                        typedarrayCall.callKind(),
                        typedarrayCall.receiverKind(),
                        typedarrayCall.ownerKind(),
                        typedarrayCall.receiverType(),
                        java.util.Objects.requireNonNull(typedarrayCall.returnType()),
                        typedarrayCall.argumentTypes(),
                        new Object(),
                        java.util.Objects.requireNonNull(typedarrayCall.exactCallableBoundary())
                )
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var enumCallInsn = requireOnlyInstruction(enumContext.targetFunction(), CallMethodInsn.class);
        var bitfieldCallInsn = requireOnlyInstruction(bitfieldContext.targetFunction(), CallMethodInsn.class);
        var typedarrayCallInsn = requireOnlyInstruction(typedarrayContext.targetFunction(), CallMethodInsn.class);
        var enumInstructions = allInstructions(enumContext.targetFunction());
        var bitfieldInstructions = allInstructions(bitfieldContext.targetFunction());
        var typedarrayInstructions = allInstructions(typedarrayContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("add_child", enumCallInsn.methodName()),
                () -> assertEquals(1, enumCallInsn.args().size()),
                () -> assertEquals(0, countInstructions(enumInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(enumInstructions, UnpackVariantInsn.class)),
                () -> assertEquals("set_process_thread_messages", bitfieldCallInsn.methodName()),
                () -> assertEquals(1, bitfieldCallInsn.args().size()),
                () -> assertEquals(0, countInstructions(bitfieldInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(bitfieldInstructions, UnpackVariantInsn.class)),
                () -> assertEquals("add_surface_from_arrays", typedarrayCallInsn.methodName()),
                () -> assertEquals(2, typedarrayCallInsn.args().size()),
                () -> assertEquals(0, countInstructions(typedarrayInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(typedarrayInstructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runFailsFastWhenSyntheticDynamicFallbackDoesNotUseInstanceReceiverRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_call_invalid_route.gd",
                """
                        class_name BodyInsnDynamicCallInvalidRoute
                        extends RefCounted
                        
                        func build_vector(x: int, y: int, z: int) -> Vector3i:
                            return Vector3i(x, y, z)
                        """,
                Map.of("BodyInsnDynamicCallInvalidRoute", "RuntimeBodyInsnDynamicCallInvalidRoute"),
                true
        );
        var buildVectorContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicCallInvalidRoute",
                "build_vector"
        );
        var callAnchor = requireSingleCallAnchor(buildVectorContext.requireFrontendCfgGraph());
        var originalResolvedCall = prepared.context().requireAnalysisData().resolvedCalls().get(callAnchor);
        assertNotNull(originalResolvedCall);

        prepared.context().requireAnalysisData().resolvedCalls().put(
                callAnchor,
                FrontendResolvedCall.dynamic(
                        originalResolvedCall.callableName(),
                        FrontendReceiverKind.TYPE_META,
                        originalResolvedCall.ownerKind(),
                        originalResolvedCall.receiverType(),
                        originalResolvedCall.argumentTypes(),
                        originalResolvedCall.declarationSite(),
                        "synthetic non-instance dynamic route"
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("instance receiver route"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("TYPE_META"), exception.getMessage()),
                () -> assertFalse(exception.getMessage().contains("call route is not lowering-ready"), exception.getMessage())
        );
    }

    @Test
    void runLowersTypeMetaStaticHeadMemberLoadsIntoLoadStaticInsn() throws Exception {
        var prepared = prepareContext(
                "body_insn_type_meta_static_head.gd",
                """
                        class_name BodyInsnTypeMetaStaticHead
                        extends RefCounted
                        
                        func zero_length() -> float:
                            return Vector3.ZERO.length()
                        
                        func red() -> Color:
                            return Color.RED
                        """,
                Map.of("BodyInsnTypeMetaStaticHead", "RuntimeBodyInsnTypeMetaStaticHead"),
                true
        );
        var zeroLengthContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnTypeMetaStaticHead",
                "zero_length"
        );
        var redContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnTypeMetaStaticHead",
                "red"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var zeroLengthInstructions = allInstructions(zeroLengthContext.targetFunction());
        var redInstructions = allInstructions(redContext.targetFunction());
        var zeroLengthStaticLoad = requireOnlyInstruction(zeroLengthContext.targetFunction(), LoadStaticInsn.class);
        var zeroLengthCall = requireOnlyInstruction(zeroLengthContext.targetFunction(), CallMethodInsn.class);
        var redStaticLoad = requireOnlyInstruction(redContext.targetFunction(), LoadStaticInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, countInstructions(zeroLengthInstructions, LoadStaticInsn.class)),
                () -> assertEquals("Vector3", zeroLengthStaticLoad.className()),
                () -> assertEquals("ZERO", zeroLengthStaticLoad.staticName()),
                () -> assertEquals(1, countInstructions(zeroLengthInstructions, CallMethodInsn.class)),
                () -> assertEquals("length", zeroLengthCall.methodName()),
                () -> assertEquals(0, countInstructions(zeroLengthInstructions, LoadPropertyInsn.class)),
                () -> assertEquals(1, countInstructions(redInstructions, LoadStaticInsn.class)),
                () -> assertEquals("Color", redStaticLoad.className()),
                () -> assertEquals("RED", redStaticLoad.staticName()),
                () -> assertEquals(0, countInstructions(redInstructions, LoadPropertyInsn.class)),
                () -> assertEquals(0, countInstructions(redInstructions, CallMethodInsn.class))
        );
    }

    @Test
    void runLowersBuiltinInstancePropertyReadsIntoLoadPropertyInsn() throws Exception {
        var prepared = prepareContext(
                "body_insn_builtin_property_read.gd",
                """
                        class_name BodyInsnBuiltinPropertyRead
                        extends RefCounted
                        
                        func axis_x(vector: Vector3) -> float:
                            return vector.x
                        
                        func constructed_y() -> float:
                            return Vector3(1.0, 2.0, 3.0).y
                        """,
                Map.of("BodyInsnBuiltinPropertyRead", "RuntimeBodyInsnBuiltinPropertyRead"),
                true
        );
        var axisContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnBuiltinPropertyRead",
                "axis_x"
        );
        var constructedContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnBuiltinPropertyRead",
                "constructed_y"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var axisInstructions = allInstructions(axisContext.targetFunction());
        var axisLoad = requireOnlyInstruction(axisContext.targetFunction(), LoadPropertyInsn.class);
        var axisReturn = requireOnlyReturnInsn(axisContext.targetFunction());

        var constructedInstructions = allInstructions(constructedContext.targetFunction());
        var constructedLoad = requireOnlyInstruction(constructedContext.targetFunction(), LoadPropertyInsn.class);
        var constructedConstruct = requireOnlyInstruction(constructedContext.targetFunction(), ConstructBuiltinInsn.class);
        var constructedReturn = requireOnlyReturnInsn(constructedContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, countInstructions(axisInstructions, LoadPropertyInsn.class)),
                () -> assertEquals("x", axisLoad.propertyName()),
                () -> assertEquals(axisLoad.resultId(), axisReturn.returnValueId()),
                () -> assertEquals(0, countInstructions(axisInstructions, ConstructBuiltinInsn.class)),
                () -> assertEquals(0, countInstructions(axisInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(axisInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(1, countInstructions(constructedInstructions, ConstructBuiltinInsn.class)),
                () -> assertEquals(1, countInstructions(constructedInstructions, LoadPropertyInsn.class)),
                () -> assertEquals(3, constructedConstruct.args().size()),
                () -> assertEquals("y", constructedLoad.propertyName()),
                () -> assertEquals(constructedConstruct.resultId(), constructedLoad.objectId()),
                () -> assertEquals(constructedLoad.resultId(), constructedReturn.returnValueId()),
                () -> assertEquals(0, countInstructions(constructedInstructions, CallMethodInsn.class)),
                () -> assertEquals(0, countInstructions(constructedInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(constructedInstructions, UnpackVariantInsn.class))
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
    void runSkipsOuterWritebackForSharedArrayPropertyBackedSubscriptAssignments() throws Exception {
        var prepared = prepareContext(
                "body_insn_property_subscript_writeback.gd",
                """
                        class_name BodyInsnPropertySubscriptWriteback
                        extends RefCounted
                        
                        var payloads: Array[int]
                        
                        func ping(idx: int, value: int) -> void:
                            payloads[idx] = value
                        """,
                Map.of("BodyInsnPropertySubscriptWriteback", "RuntimeBodyInsnPropertySubscriptWriteback"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnPropertySubscriptWriteback",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var propertyStoreValueIds = storeValueIdsForProperty(instructions, "payloads");

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, countInstructions(instructions, VariantSetIndexedInsn.class)),
                () -> assertEquals(0, propertyStoreValueIds.size())
        );
    }

    @Test
    void runWritesBackValueSemanticBuiltinPropertyChainAssignmentRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_builtin_property_writeback.gd",
                """
                        class_name BodyInsnBuiltinPropertyWriteback
                        extends Node2D
                        
                        func ping(seed: float) -> float:
                            position.x = seed
                            return position.x
                        """,
                Map.of("BodyInsnBuiltinPropertyWriteback", "RuntimeBodyInsnBuiltinPropertyWriteback"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnBuiltinPropertyWriteback",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var propertyStores = instructions.stream()
                .filter(StorePropertyInsn.class::isInstance)
                .map(StorePropertyInsn.class::cast)
                .toList();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(List.of("x", "position"), propertyStores.stream().map(StorePropertyInsn::propertyName).toList()),
                () -> assertEquals(propertyStores.getFirst().objectId(), propertyStores.getLast().valueId()),
                () -> assertEquals("self", propertyStores.getLast().objectId()),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runLowersLiteralPropertyInitializerIntoExecutableInitFunction() throws Exception {
        var prepared = prepareContext(
                "body_insn_property_literal.gd",
                """
                        class_name BodyInsnPropertyLiteral
                        extends RefCounted
                        
                        var ready_value: int = 7
                        
                        func ping() -> int:
                            return ready_value
                        """,
                Map.of("BodyInsnPropertyLiteral", "RuntimeBodyInsnPropertyLiteral"),
                true
        );
        var initContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBodyInsnPropertyLiteral",
                "_field_init_ready_value"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var initFunction = initContext.targetFunction();
        var literalInsn = requireOnlyInstruction(initFunction, LiteralIntInsn.class);
        var returnInsn = requireOnlyReturnInsn(initFunction);
        var selfParameter = initFunction.getParameter(0);
        var selfParameterName = selfParameter == null ? null : selfParameter.name();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("seq_0", initFunction.getEntryBlockId()),
                () -> assertEquals(2, initFunction.getBasicBlockCount()),
                () -> assertEquals(1, initFunction.getParameters().size()),
                () -> assertInstanceOf(LirParameterDef.class, selfParameter),
                () -> assertEquals("self", selfParameterName),
                () -> assertEquals(literalInsn.resultId(), returnInsn.returnValueId())
        );
    }

    @Test
    void runLowersCallPropertyInitializerIntoExecutableInitFunction() throws Exception {
        var prepared = prepareContext(
                "body_insn_property_call.gd",
                """
                        class_name BodyInsnPropertyCall
                        extends RefCounted
                        
                        var ready_value: float = abs(1.0)
                        
                        func ping() -> float:
                            return ready_value
                        """,
                Map.of("BodyInsnPropertyCall", "RuntimeBodyInsnPropertyCall"),
                true
        );
        var initContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBodyInsnPropertyCall",
                "_field_init_ready_value"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var initFunction = initContext.targetFunction();
        var globalInsn = requireOnlyInstruction(initFunction, CallGlobalInsn.class);
        var packInsn = requireOnlyInstruction(initFunction, PackVariantInsn.class);
        var unpackInsn = requireOnlyInstruction(initFunction, UnpackVariantInsn.class);
        var returnInsn = requireOnlyReturnInsn(initFunction);
        var instructions = allInstructions(initFunction);
        var selfParameter = initFunction.getParameter(0);
        var selfParameterName = selfParameter == null ? null : selfParameter.name();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("seq_0", initFunction.getEntryBlockId()),
                () -> assertEquals(2, initFunction.getBasicBlockCount()),
                () -> assertEquals(1, initFunction.getParameters().size()),
                () -> assertInstanceOf(LirParameterDef.class, selfParameter),
                () -> assertEquals("self", selfParameterName),
                () -> assertEquals("abs", globalInsn.functionName()),
                () -> assertEquals(0, countInstructions(instructions, CallMethodInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, ConstructBuiltinInsn.class)),
                () -> assertEquals(packInsn.resultId(), onlyVariableOperandId(globalInsn.args())),
                () -> assertEquals(globalInsn.resultId(), unpackInsn.variantId()),
                () -> assertEquals(unpackInsn.resultId(), returnInsn.returnValueId())
        );
    }

    @Test
    void runLowersPropertyInitializerThroughMemberAndGlobalHelperRoutes() throws Exception {
        var prepared = prepareContext(
                "body_insn_property_helper_call.gd",
                """
                        class_name BodyInsnPropertyHelperCall
                        extends RefCounted
                        
                        var ready_value: float = abs(Vector3.ZERO.length())
                        
                        func ping() -> float:
                            return ready_value
                        """,
                Map.of("BodyInsnPropertyHelperCall", "RuntimeBodyInsnPropertyHelperCall"),
                true
        );
        var initContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBodyInsnPropertyHelperCall",
                "_field_init_ready_value"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var initFunction = initContext.targetFunction();
        var instructions = allInstructions(initFunction);
        var loadStaticInsn = requireOnlyInstruction(initFunction, LoadStaticInsn.class);
        var methodInsn = requireOnlyInstruction(initFunction, CallMethodInsn.class);
        var globalInsn = requireOnlyInstruction(initFunction, CallGlobalInsn.class);
        var packInsn = requireOnlyInstruction(initFunction, PackVariantInsn.class);
        var unpackInsn = requireOnlyInstruction(initFunction, UnpackVariantInsn.class);
        var returnInsn = requireOnlyReturnInsn(initFunction);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("seq_0", initFunction.getEntryBlockId()),
                () -> assertEquals(2, initFunction.getBasicBlockCount()),
                () -> assertEquals("ZERO", loadStaticInsn.staticName()),
                () -> assertEquals("length", methodInsn.methodName()),
                () -> assertEquals(loadStaticInsn.resultId(), methodInsn.objectId()),
                () -> assertEquals("abs", globalInsn.functionName()),
                () -> assertEquals(1, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(1, countInstructions(instructions, UnpackVariantInsn.class)),
                () -> assertEquals(methodInsn.resultId(), packInsn.valueId()),
                () -> assertEquals(packInsn.resultId(), onlyVariableOperandId(globalInsn.args())),
                () -> assertEquals(globalInsn.resultId(), unpackInsn.variantId()),
                () -> assertEquals(unpackInsn.resultId(), returnInsn.returnValueId())
        );
    }

    @Test
    void runFailsFastWhenPropertyInitializerCallFactIsMissingDuringBodyLowering() throws Exception {
        var prepared = prepareContext(
                "body_insn_property_missing_call_fact.gd",
                """
                        class_name BodyInsnPropertyMissingCallFact
                        extends RefCounted
                        
                        var ready_value: float = abs(1.0)
                        
                        func ping() -> float:
                            return ready_value
                        """,
                Map.of("BodyInsnPropertyMissingCallFact", "RuntimeBodyInsnPropertyMissingCallFact"),
                true
        );
        var initContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBodyInsnPropertyMissingCallFact",
                "_field_init_ready_value"
        );
        var callAnchor = requireSingleCallAnchor(initContext.requireFrontendCfgGraph());
        prepared.context().requireAnalysisData().resolvedCalls().remove(callAnchor);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("Missing published resolved call"), exception.getMessage());
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

    private static void rewriteBindingKindToSelf(
            @NotNull Map<Node, FrontendBinding> bindings,
            @NotNull IdentifierExpression identifierExpression
    ) {
        bindings.compute(
                identifierExpression,
                (k, originalBinding) -> new FrontendBinding(
                        "self",
                        FrontendBindingKind.SELF,
                        originalBinding == null ? identifierExpression : originalBinding.declarationSite()
                )
        );
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

    private static int instructionIndex(
            @NotNull List<LirInstruction> instructions,
            @NotNull LirInstruction targetInstruction
    ) {
        for (var index = 0; index < instructions.size(); index++) {
            if (instructions.get(index) == targetInstruction) {
                return index;
            }
        }
        fail("Instruction not found in emitted instruction list: " + targetInstruction);
        return -1;
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

    private static <T extends SequenceItem> @NotNull T requireSingleSequenceItem(
            @NotNull FrontendCfgGraph graph,
            @NotNull Class<T> itemType
    ) {
        var matches = new ArrayList<T>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (itemType.isInstance(item)) {
                    matches.add(itemType.cast(item));
                }
            }
        }
        assertEquals(1, matches.size(), () -> "Expected exactly one " + itemType.getSimpleName());
        return matches.getFirst();
    }

    private static <T extends ValueOpItem> @NotNull T requireSingleValueProducerItem(
            @NotNull FrontendCfgGraph graph,
            @NotNull Class<T> itemType
    ) {
        var matches = new ArrayList<T>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (itemType.isInstance(item)) {
                    matches.add(itemType.cast(item));
                }
            }
        }
        assertEquals(1, matches.size(), () -> "Expected exactly one " + itemType.getSimpleName());
        return matches.getFirst();
    }

    private static @NotNull ValueOpItem requireValueProducerByResultId(
            @NotNull FrontendCfgGraph graph,
            @NotNull String valueId
    ) {
        var matches = new ArrayList<ValueOpItem>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (item instanceof ValueOpItem valueOpItem
                        && valueId.equals(valueOpItem.resultValueIdOrNull())) {
                    matches.add(valueOpItem);
                }
            }
        }
        assertEquals(1, matches.size(), () -> "Expected exactly one producer for value id " + valueId);
        return matches.getFirst();
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
