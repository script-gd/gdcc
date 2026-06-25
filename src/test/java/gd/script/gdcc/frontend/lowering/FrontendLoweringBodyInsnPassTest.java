package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SequenceItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ValueOpItem;
import gd.script.gdcc.frontend.lowering.pass.body.FrontendBodyLoweringSession;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBodyInsnPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionGlobalConstant;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.BinaryOpInsn;
import gd.script.gdcc.lir.insn.CallGlobalInsn;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.ConstructBuiltinInsn;
import gd.script.gdcc.lir.insn.ConstructObjectInsn;
import gd.script.gdcc.lir.insn.GoIfInsn;
import gd.script.gdcc.lir.insn.GotoInsn;
import gd.script.gdcc.lir.insn.LineNumberInsn;
import gd.script.gdcc.lir.insn.LiteralBoolInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralNilInsn;
import gd.script.gdcc.lir.insn.LiteralNullInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.LiteralStringNameInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.LoadPropertyInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.StorePropertyInsn;
import gd.script.gdcc.lir.insn.UnpackVariantInsn;
import gd.script.gdcc.lir.insn.VariantGetInsn;
import gd.script.gdcc.lir.insn.VariantGetIndexedInsn;
import gd.script.gdcc.lir.insn.VariantGetKeyedInsn;
import gd.script.gdcc.lir.insn.VariantGetNamedInsn;
import gd.script.gdcc.lir.insn.VariantSetInsn;
import gd.script.gdcc.lir.insn.VariantSetIndexedInsn;
import gd.script.gdcc.lir.insn.VariantSetKeyedInsn;
import gd.script.gdcc.lir.insn.VariantSetNamedInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FrontendLoweringBodyInsnPassTest {
    private static final @NotNull Range SYNTHETIC_RANGE = new Range(0, 0, new Point(0, 0), new Point(0, 0));

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
    void runKeepsTypedObjectNilEqualityOnBinaryOpRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_typed_object_nil_equality.gd",
                """
                        class_name BodyInsnTypedObjectNilEquality
                        extends RefCounted
                        
                        class Point extends RefCounted:
                            var next: Point = null
                        
                        func has_next(point: Point) -> bool:
                            var current: Point = point
                            return current != null
                        """,
                Map.of("BodyInsnTypedObjectNilEquality", "RuntimeBodyInsnTypedObjectNilEquality"),
                true
        );
        var hasNextContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnTypedObjectNilEquality",
                "has_next"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(hasNextContext.targetFunction());
        var comparisonInsn = requireOnlyInstruction(hasNextContext.targetFunction(), BinaryOpInsn.class);
        var nilIds = literalNilResultIds(instructions);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors(), () -> "Unexpected diagnostics: "
                        + prepared.diagnostics().snapshot().asList()),
                () -> assertEquals(GodotOperator.NOT_EQUAL, comparisonInsn.op()),
                () -> assertTrue(comparisonInsn.leftId().startsWith("cfg_tmp_"), comparisonInsn.leftId()),
                () -> assertEquals(nilIds.getFirst(), comparisonInsn.rightId()),
                () -> assertEquals(1, nilIds.size()),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
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
    void runFailsFastWhenPublishedSingletonBindingLosesRegistryMetadata() throws Exception {
        var prepared = prepareContext(
                "body_insn_singleton_missing_registry_metadata.gd",
                """
                        class_name BodyInsnSingletonMissingRegistryMetadata
                        extends RefCounted
                        
                        func ping(value: int) -> int:
                            return value
                        """,
                Map.of(
                        "BodyInsnSingletonMissingRegistryMetadata",
                        "RuntimeBodyInsnSingletonMissingRegistryMetadata"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSingletonMissingRegistryMetadata",
                "ping"
        );
        var rootBlock = assertInstanceOf(dev.superice.gdparser.frontend.ast.Block.class, pingContext.loweringRoot());
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var value = assertInstanceOf(IdentifierExpression.class, returnStatement.value());
        var originalBinding = pingContext.analysisData().symbolBindings().get(value);
        assertNotNull(originalBinding);
        // Simulate a broken upstream publication so this test stays focused on the body-lowering guard.
        pingContext.analysisData().symbolBindings().put(
                value,
                new FrontendBinding(
                        "MissingRegistrySingleton",
                        FrontendBindingKind.SINGLETON,
                        originalBinding.declarationSite()
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertTrue(
                        exception.getMessage().contains(
                                "Published singleton binding 'MissingRegistrySingleton'"
                        ),
                        exception.getMessage()
                ),
                () -> assertTrue(
                        exception.getMessage().contains("missing registry-validated object metadata"),
                        exception.getMessage()
                )
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
    void runMaterializesPlainDictionaryStringKeysThroughSharedVariantBoundary() throws Exception {
        var prepared = prepareContext(
                "body_insn_plain_dictionary_string_key.gd",
                """
                        class_name BodyInsnPlainDictionaryStringKey
                        extends RefCounted
                        
                        func ping(box: Dictionary, key: String) -> int:
                            box[key] = 7
                            return int(box[key])
                        """,
                Map.of("BodyInsnPlainDictionaryStringKey", "RuntimeBodyInsnPlainDictionaryStringKey"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnPlainDictionaryStringKey",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var setInsn = requireOnlyInstruction(pingContext.targetFunction(), VariantSetInsn.class);
        var getInsn = requireOnlyInstruction(pingContext.targetFunction(), VariantGetInsn.class);
        var keyPackResultIds = packResultIds(instructions);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, countInstructions(instructions, VariantSetInsn.class)),
                () -> assertEquals(1, countInstructions(instructions, VariantGetInsn.class)),
                () -> assertTrue(keyPackResultIds.contains(setInsn.keyId())),
                () -> assertTrue(keyPackResultIds.contains(getInsn.keyId())),
                () -> assertEquals(0, countInstructions(instructions, VariantSetIndexedInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, VariantGetIndexedInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, VariantSetNamedInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, VariantGetNamedInsn.class)),
                () -> assertFalse(instructions.stream().anyMatch(VariantSetKeyedInsn.class::isInstance)),
                () -> assertFalse(instructions.stream().anyMatch(VariantGetKeyedInsn.class::isInstance))
        );
    }

    @Test
    void runMaterializesSubscriptKeysBeforeSelectingAccessInstructions() throws Exception {
        var prepared = prepareContext(
                "body_insn_subscript_key_materialization.gd",
                """
                        class_name BodyInsnSubscriptKeyMaterialization
                        extends RefCounted
                        
                        func dictionary_ops(box: Dictionary[float, int], key: int) -> int:
                            box[key] = 7
                            return box[key]
                        
                        func array_ops(values: Array[int], key: Variant) -> int:
                            values[key] = 11
                            return values[key]
                        
                        func packed_ops(values: PackedInt32Array, key: Variant) -> int:
                            values[key] = 13
                            return values[key]
                        """,
                Map.of(
                        "BodyInsnSubscriptKeyMaterialization",
                        "RuntimeBodyInsnSubscriptKeyMaterialization"
                ),
                true
        );
        var dictionaryContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSubscriptKeyMaterialization",
                "dictionary_ops"
        );
        var arrayContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSubscriptKeyMaterialization",
                "array_ops"
        );
        var packedContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSubscriptKeyMaterialization",
                "packed_ops"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var dictionaryInstructions = allInstructions(dictionaryContext.targetFunction());
        var arrayInstructions = allInstructions(arrayContext.targetFunction());
        var packedInstructions = allInstructions(packedContext.targetFunction());
        var dictionaryCasts = dictionaryInstructions.stream()
                .filter(CallIntrinsicInsn.class::isInstance)
                .map(CallIntrinsicInsn.class::cast)
                .toList();
        var setKeyedInsn = requireOnlyInstruction(dictionaryContext.targetFunction(), VariantSetKeyedInsn.class);
        var getKeyedInsn = requireOnlyInstruction(dictionaryContext.targetFunction(), VariantGetKeyedInsn.class);
        var arrayUnpacks = arrayInstructions.stream()
                .filter(UnpackVariantInsn.class::isInstance)
                .map(UnpackVariantInsn.class::cast)
                .toList();
        var setIndexedInsn = requireOnlyInstruction(arrayContext.targetFunction(), VariantSetIndexedInsn.class);
        var getIndexedInsn = requireOnlyInstruction(arrayContext.targetFunction(), VariantGetIndexedInsn.class);
        var packedUnpacks = packedInstructions.stream()
                .filter(UnpackVariantInsn.class::isInstance)
                .map(UnpackVariantInsn.class::cast)
                .toList();
        var packedSetIndexedInsn = requireOnlyInstruction(packedContext.targetFunction(), VariantSetIndexedInsn.class);
        var packedGetIndexedInsn = requireOnlyInstruction(packedContext.targetFunction(), VariantGetIndexedInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(2, dictionaryCasts.size()),
                () -> assertTrue(dictionaryCasts.stream().allMatch(insn -> insn.intrinsicName().equals("c_int_to_float"))),
                () -> assertTrue(dictionaryCasts.stream().map(CallIntrinsicInsn::resultId).anyMatch(setKeyedInsn.keyId()::equals)),
                () -> assertTrue(dictionaryCasts.stream().map(CallIntrinsicInsn::resultId).anyMatch(getKeyedInsn.keyId()::equals)),
                () -> assertTrue(dictionaryCasts.stream().allMatch(insn -> requireIntrinsicResultType(
                        dictionaryContext.targetFunction(),
                        insn
                ).equals(GdFloatType.FLOAT))),
                () -> assertEquals(
                        GdIntType.INT,
                        requireVariableType(
                                dictionaryContext.targetFunction(),
                                onlyVariableOperandId(dictionaryCasts.getFirst().args())
                        )
                ),
                () -> assertFalse(dictionaryInstructions.stream().anyMatch(VariantSetInsn.class::isInstance)),
                () -> assertFalse(dictionaryInstructions.stream().anyMatch(VariantGetInsn.class::isInstance)),
                () -> assertEquals(2, arrayUnpacks.size()),
                () -> assertTrue(arrayUnpacks.stream().map(UnpackVariantInsn::resultId).anyMatch(setIndexedInsn.indexId()::equals)),
                () -> assertTrue(arrayUnpacks.stream().map(UnpackVariantInsn::resultId).anyMatch(getIndexedInsn.indexId()::equals)),
                () -> assertTrue(arrayUnpacks.stream().allMatch(insn -> requireVariableType(
                        arrayContext.targetFunction(),
                        insn.resultId()
                ).equals(GdIntType.INT))),
                () -> assertTrue(arrayUnpacks.stream().allMatch(insn -> requireVariableType(
                        arrayContext.targetFunction(),
                        insn.variantId()
                ).equals(GdVariantType.VARIANT))),
                () -> assertFalse(arrayInstructions.stream().anyMatch(VariantSetInsn.class::isInstance)),
                () -> assertFalse(arrayInstructions.stream().anyMatch(VariantGetInsn.class::isInstance)),
                () -> assertFalse(arrayInstructions.stream().anyMatch(VariantSetKeyedInsn.class::isInstance)),
                () -> assertFalse(arrayInstructions.stream().anyMatch(VariantGetKeyedInsn.class::isInstance)),
                () -> assertEquals(2, packedUnpacks.size()),
                () -> assertTrue(packedUnpacks.stream()
                        .map(UnpackVariantInsn::resultId)
                        .anyMatch(packedSetIndexedInsn.indexId()::equals)),
                () -> assertTrue(packedUnpacks.stream()
                        .map(UnpackVariantInsn::resultId)
                        .anyMatch(packedGetIndexedInsn.indexId()::equals)),
                () -> assertTrue(packedUnpacks.stream().allMatch(insn -> requireVariableType(
                        packedContext.targetFunction(),
                        insn.resultId()
                ).equals(GdIntType.INT))),
                () -> assertTrue(packedUnpacks.stream().allMatch(insn -> requireVariableType(
                        packedContext.targetFunction(),
                        insn.variantId()
                ).equals(GdVariantType.VARIANT))),
                () -> assertFalse(packedInstructions.stream().anyMatch(VariantSetInsn.class::isInstance)),
                () -> assertFalse(packedInstructions.stream().anyMatch(VariantGetInsn.class::isInstance)),
                () -> assertFalse(packedInstructions.stream().anyMatch(VariantSetKeyedInsn.class::isInstance)),
                () -> assertFalse(packedInstructions.stream().anyMatch(VariantGetKeyedInsn.class::isInstance))
        );
    }

    @Test
    void runMaterializesVectorIntrinsicCastsForSubscriptKeyAndValueBoundaries() throws Exception {
        var prepared = prepareContext(
                "body_insn_vector_subscript_boundary.gd",
                """
                        class_name BodyInsnVectorSubscriptBoundary
                        extends RefCounted
                        
                        func dictionary_ops(box: Dictionary[Vector3, Vector3], key: Vector3i, value: Vector3i) -> Vector3:
                            box[key] = value
                            return box[key]
                        """,
                Map.of("BodyInsnVectorSubscriptBoundary", "RuntimeBodyInsnVectorSubscriptBoundary"),
                true
        );
        var dictionaryContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnVectorSubscriptBoundary",
                "dictionary_ops"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(dictionaryContext.targetFunction());
        var vectorCasts = instructions.stream()
                .filter(CallIntrinsicInsn.class::isInstance)
                .map(CallIntrinsicInsn.class::cast)
                .toList();
        var setKeyedInsn = requireOnlyInstruction(dictionaryContext.targetFunction(), VariantSetKeyedInsn.class);
        var getKeyedInsn = requireOnlyInstruction(dictionaryContext.targetFunction(), VariantGetKeyedInsn.class);
        var castResultIds = vectorCasts.stream()
                .map(CallIntrinsicInsn::resultId)
                .toList();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(3, vectorCasts.size()),
                () -> assertTrue(vectorCasts.stream().allMatch(insn -> insn.intrinsicName().equals("c_vector3i_to_vector3"))),
                () -> assertTrue(castResultIds.contains(setKeyedInsn.keyId())),
                () -> assertTrue(castResultIds.contains(setKeyedInsn.valueId())),
                () -> assertTrue(castResultIds.contains(getKeyedInsn.keyId())),
                () -> assertTrue(vectorCasts.stream().allMatch(insn -> requireIntrinsicResultType(
                        dictionaryContext.targetFunction(),
                        insn
                ).equals(GdFloatVectorType.VECTOR3))),
                () -> assertTrue(vectorCasts.stream().allMatch(insn -> requireVariableType(
                        dictionaryContext.targetFunction(),
                        onlyVariableOperandId(insn.args())
                ).equals(GdIntVectorType.VECTOR3I))),
                () -> assertFalse(instructions.stream().anyMatch(VariantSetInsn.class::isInstance)),
                () -> assertFalse(instructions.stream().anyMatch(VariantGetInsn.class::isInstance)),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runMaterializesStringFamilySubscriptKeysBeforeSelectingAccessInstructions() throws Exception {
        var prepared = prepareContext(
                "body_insn_string_family_subscript_key_boundary.gd",
                """
                        class_name BodyInsnStringFamilySubscriptKeyBoundary
                        extends RefCounted
                        
                        func name_key_ops(box: Dictionary[StringName, int], key: String) -> int:
                            box[key] = 7
                            return box[key]
                        
                        func text_key_ops(box: Dictionary[String, int], key: StringName) -> int:
                            box[key] = 11
                            return box[key]
                        """,
                Map.of(
                        "BodyInsnStringFamilySubscriptKeyBoundary",
                        "RuntimeBodyInsnStringFamilySubscriptKeyBoundary"
                ),
                true
        );
        var nameContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilySubscriptKeyBoundary",
                "name_key_ops"
        );
        var textContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilySubscriptKeyBoundary",
                "text_key_ops"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var nameInstructions = allInstructions(nameContext.targetFunction());
        var textInstructions = allInstructions(textContext.targetFunction());
        var nameConstructors = constructBuiltinInsns(nameInstructions);
        var textConstructors = constructBuiltinInsns(textInstructions);
        var nameConstructorResultIds = nameConstructors.stream()
                .map(ConstructBuiltinInsn::resultId)
                .toList();
        var textConstructorResultIds = textConstructors.stream()
                .map(ConstructBuiltinInsn::resultId)
                .toList();
        var setNamedInsn = requireOnlyInstruction(nameContext.targetFunction(), VariantSetNamedInsn.class);
        var getNamedInsn = requireOnlyInstruction(nameContext.targetFunction(), VariantGetNamedInsn.class);
        var setKeyedInsn = requireOnlyInstruction(textContext.targetFunction(), VariantSetKeyedInsn.class);
        var getKeyedInsn = requireOnlyInstruction(textContext.targetFunction(), VariantGetKeyedInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(2, nameConstructors.size()),
                () -> assertEquals(2, textConstructors.size()),
                () -> assertTrue(nameConstructorResultIds.contains(setNamedInsn.nameId())),
                () -> assertTrue(nameConstructorResultIds.contains(getNamedInsn.nameId())),
                () -> assertTrue(textConstructorResultIds.contains(setKeyedInsn.keyId())),
                () -> assertTrue(textConstructorResultIds.contains(getKeyedInsn.keyId())),
                () -> assertTrue(nameConstructors.stream().allMatch(insn -> requireVariableType(
                        nameContext.targetFunction(),
                        insn.resultId()
                ).equals(GdStringNameType.STRING_NAME))),
                () -> assertTrue(nameConstructors.stream().allMatch(insn -> requireVariableType(
                        nameContext.targetFunction(),
                        onlyVariableOperandId(insn.args())
                ).equals(GdStringType.STRING))),
                () -> assertTrue(textConstructors.stream().allMatch(insn -> requireVariableType(
                        textContext.targetFunction(),
                        insn.resultId()
                ).equals(GdStringType.STRING))),
                () -> assertTrue(textConstructors.stream().allMatch(insn -> requireVariableType(
                        textContext.targetFunction(),
                        onlyVariableOperandId(insn.args())
                ).equals(GdStringNameType.STRING_NAME))),
                () -> assertFalse(nameInstructions.stream().anyMatch(VariantSetKeyedInsn.class::isInstance)),
                () -> assertFalse(nameInstructions.stream().anyMatch(VariantGetKeyedInsn.class::isInstance)),
                () -> assertFalse(textInstructions.stream().anyMatch(VariantSetNamedInsn.class::isInstance)),
                () -> assertFalse(textInstructions.stream().anyMatch(VariantGetNamedInsn.class::isInstance)),
                () -> assertFalse(nameInstructions.stream().anyMatch(VariantSetInsn.class::isInstance)),
                () -> assertFalse(nameInstructions.stream().anyMatch(VariantGetInsn.class::isInstance)),
                () -> assertFalse(textInstructions.stream().anyMatch(VariantSetInsn.class::isInstance)),
                () -> assertFalse(textInstructions.stream().anyMatch(VariantGetInsn.class::isInstance)),
                () -> assertEquals(0, countInstructions(nameInstructions, CallIntrinsicInsn.class)),
                () -> assertEquals(0, countInstructions(textInstructions, CallIntrinsicInsn.class)),
                () -> assertEquals(0, countInstructions(nameInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(textInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(nameInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(textInstructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runMaterializesStringFamilySubscriptValuesBeforeStoreInstructions() throws Exception {
        var prepared = prepareContext(
                "body_insn_string_family_subscript_value_boundary.gd",
                """
                        class_name BodyInsnStringFamilySubscriptValueBoundary
                        extends RefCounted
                        
                        func name_value_ops(box: Dictionary[int, StringName], value: String) -> void:
                            box[1] = value
                        
                        func text_value_ops(box: Dictionary[int, String], value: StringName) -> void:
                            box[2] = value
                        """,
                Map.of(
                        "BodyInsnStringFamilySubscriptValueBoundary",
                        "RuntimeBodyInsnStringFamilySubscriptValueBoundary"
                ),
                true
        );
        var nameContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilySubscriptValueBoundary",
                "name_value_ops"
        );
        var textContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilySubscriptValueBoundary",
                "text_value_ops"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var nameInstructions = allInstructions(nameContext.targetFunction());
        var textInstructions = allInstructions(textContext.targetFunction());
        var nameConstructor = requireOnlyInstruction(nameContext.targetFunction(), ConstructBuiltinInsn.class);
        var textConstructor = requireOnlyInstruction(textContext.targetFunction(), ConstructBuiltinInsn.class);
        var nameSetInsn = requireOnlyInstruction(nameContext.targetFunction(), VariantSetIndexedInsn.class);
        var textSetInsn = requireOnlyInstruction(textContext.targetFunction(), VariantSetIndexedInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(nameConstructor.resultId(), nameSetInsn.valueId()),
                () -> assertEquals(textConstructor.resultId(), textSetInsn.valueId()),
                () -> assertEquals(
                        GdStringNameType.STRING_NAME,
                        requireVariableType(nameContext.targetFunction(), nameConstructor.resultId())
                ),
                () -> assertEquals(
                        GdStringType.STRING,
                        requireVariableType(textContext.targetFunction(), textConstructor.resultId())
                ),
                () -> assertEquals(
                        GdStringType.STRING,
                        requireVariableType(nameContext.targetFunction(), onlyVariableOperandId(nameConstructor.args()))
                ),
                () -> assertEquals(
                        GdStringNameType.STRING_NAME,
                        requireVariableType(textContext.targetFunction(), onlyVariableOperandId(textConstructor.args()))
                ),
                () -> assertFalse(nameInstructions.stream().anyMatch(VariantSetInsn.class::isInstance)),
                () -> assertFalse(textInstructions.stream().anyMatch(VariantSetInsn.class::isInstance)),
                () -> assertFalse(nameInstructions.stream().anyMatch(VariantSetKeyedInsn.class::isInstance)),
                () -> assertFalse(textInstructions.stream().anyMatch(VariantSetKeyedInsn.class::isInstance)),
                () -> assertFalse(nameInstructions.stream().anyMatch(VariantSetNamedInsn.class::isInstance)),
                () -> assertFalse(textInstructions.stream().anyMatch(VariantSetNamedInsn.class::isInstance)),
                () -> assertEquals(0, countInstructions(nameInstructions, CallIntrinsicInsn.class)),
                () -> assertEquals(0, countInstructions(textInstructions, CallIntrinsicInsn.class)),
                () -> assertEquals(0, countInstructions(nameInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(textInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(nameInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(textInstructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runLowersExplicitSelfDirectPropertyAssignmentTargetWithoutMissingPrefixType() throws Exception {
        var prepared = prepareContext(
                "body_insn_explicit_self_property_assignment.gd",
                """
                        class_name BodyInsnExplicitSelfPropertyAssignment
                        extends RefCounted
                        
                        var hp: int = 0
                        
                        func ping(seed: int) -> void:
                            self.hp = seed
                        """,
                Map.of(
                        "BodyInsnExplicitSelfPropertyAssignment",
                        "RuntimeBodyInsnExplicitSelfPropertyAssignment"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnExplicitSelfPropertyAssignment",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var propertyStores = instructions.stream()
                .filter(StorePropertyInsn.class::isInstance)
                .map(StorePropertyInsn.class::cast)
                .filter(instruction -> instruction.propertyName().equals("hp"))
                .toList();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, propertyStores.size()),
                () -> assertEquals("self", propertyStores.getFirst().objectId()),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runLowersRotatingCameraExplicitSelfPositionAssignmentSmoke() throws Exception {
        var prepared = prepareContext(
                "body_insn_rotating_camera_self_position.gd",
                """
                        class_name BodyInsnRotatingCameraSelfPosition
                        extends Camera3D
                        
                        func _process(delta: float) -> void:
                            var vec = Vector3(1.0, 0.0, 0.0)
                            self.position = vec
                        """,
                Map.of(
                        "BodyInsnRotatingCameraSelfPosition",
                        "RuntimeBodyInsnRotatingCameraSelfPosition"
                ),
                true
        );
        var processContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnRotatingCameraSelfPosition",
                "_process"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(processContext.targetFunction());
        var propertyStores = instructions.stream()
                .filter(StorePropertyInsn.class::isInstance)
                .map(StorePropertyInsn.class::cast)
                .filter(instruction -> instruction.propertyName().equals("position"))
                .toList();
        var vectorConstruct = requireOnlyInstruction(processContext.targetFunction(), ConstructBuiltinInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, propertyStores.size()),
                () -> assertEquals("self", propertyStores.getFirst().objectId()),
                () -> assertEquals(3, vectorConstruct.args().size()),
                () -> assertEquals(1, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(1, countInstructions(instructions, UnpackVariantInsn.class))
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
    void runLowersStringFamilyLocalInitializersThroughConstructBuiltinInsn() throws Exception {
        var prepared = prepareContext(
                "body_insn_string_family_local_init.gd",
                """
                        class_name BodyInsnStringFamilyLocalInit
                        extends RefCounted
                        
                        func ping(text: String) -> StringName:
                            var from_text: StringName = text
                            var from_literal: StringName = "line\\nbreak"
                            var direct_name: StringName = &"ready"
                            return from_text
                        """,
                Map.of("BodyInsnStringFamilyLocalInit", "RuntimeBodyInsnStringFamilyLocalInit"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilyLocalInit",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = pingContext.targetFunction();
        var instructions = allInstructions(function);
        var constructors = constructBuiltinInsns(instructions);
        var literalString = requireOnlyInstruction(function, LiteralStringInsn.class);
        var literalStringName = requireOnlyInstruction(function, LiteralStringNameInsn.class);
        var assignSources = assignSourcesByTarget(instructions);
        var firstConstructorArg = onlyVariableOperandId(constructors.getFirst().args());
        var secondConstructorArg = onlyVariableOperandId(constructors.getLast().args());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(2, constructors.size()),
                () -> assertEquals(GdStringType.STRING, requireVariableType(function, firstConstructorArg)),
                () -> assertEquals(literalString.resultId(), secondConstructorArg),
                () -> assertEquals("line\nbreak", literalString.value()),
                () -> assertEquals("ready", literalStringName.value()),
                () -> assertEquals(constructors.getFirst().resultId(), assignSources.get("from_text")),
                () -> assertEquals(constructors.getLast().resultId(), assignSources.get("from_literal")),
                () -> assertEquals(literalStringName.resultId(), assignSources.get("direct_name")),
                () -> assertEquals(GdStringNameType.STRING_NAME, requireVariableType(function, constructors.getFirst().resultId())),
                () -> assertEquals(GdStringNameType.STRING_NAME, requireVariableType(function, constructors.getLast().resultId())),
                () -> assertEquals(0, countInstructions(instructions, CallIntrinsicInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runLowersStringFamilyAssignmentsAndPropertyStoresThroughConstructBuiltinInsn() throws Exception {
        var prepared = prepareContext(
                "body_insn_string_family_assignment.gd",
                """
                        class_name BodyInsnStringFamilyAssignment
                        extends RefCounted
                        
                        var prop_name: StringName = &""
                        var prop_text: String = ""
                        
                        func ping(text: String, name: StringName) -> void:
                            var local_name: StringName = &""
                            var local_text: String = ""
                            local_name = text
                            local_text = name
                            prop_name = text
                            prop_text = name
                        """,
                Map.of("BodyInsnStringFamilyAssignment", "RuntimeBodyInsnStringFamilyAssignment"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilyAssignment",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = pingContext.targetFunction();
        var instructions = allInstructions(function);
        var constructors = constructBuiltinInsns(instructions);
        var assignSources = assignSourcesByTarget(instructions);
        var propNameStores = storeValueIdsForProperty(instructions, "prop_name");
        var propTextStores = storeValueIdsForProperty(instructions, "prop_text");
        var constructorResultIds = constructors.stream()
                .map(ConstructBuiltinInsn::resultId)
                .toList();
        var constructorArgs = constructors.stream()
                .map(insn -> onlyVariableOperandId(insn.args()))
                .toList();
        var constructorArgTypes = constructorArgs.stream()
                .map(arg -> requireVariableType(function, arg))
                .toList();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(4, constructors.size()),
                () -> assertEquals(
                        List.of(
                                GdStringType.STRING,
                                GdStringNameType.STRING_NAME,
                                GdStringType.STRING,
                                GdStringNameType.STRING_NAME
                        ),
                        constructorArgTypes
                ),
                () -> assertTrue(constructorResultIds.contains(assignSources.get("local_name"))),
                () -> assertTrue(constructorResultIds.contains(assignSources.get("local_text"))),
                () -> assertEquals(1, propNameStores.size()),
                () -> assertEquals(1, propTextStores.size()),
                () -> assertTrue(constructorResultIds.contains(propNameStores.getFirst())),
                () -> assertTrue(constructorResultIds.contains(propTextStores.getFirst())),
                () -> assertEquals(GdStringNameType.STRING_NAME, requireVariableType(function, propNameStores.getFirst())),
                () -> assertEquals(GdStringType.STRING, requireVariableType(function, propTextStores.getFirst())),
                () -> assertEquals(0, countInstructions(instructions, CallIntrinsicInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runLowersStringFamilyCallArgumentsThroughConstructBuiltinInsn() throws Exception {
        var prepared = prepareContext(
                "body_insn_string_family_call_args.gd",
                """
                        class_name BodyInsnStringFamilyCallArgs
                        extends RefCounted
                        
                        func take_name(value: StringName) -> StringName:
                            return value
                        
                        func take_text(value: String) -> String:
                            return value
                        
                        func from_text(text: String) -> StringName:
                            return take_name(text)
                        
                        func from_name(name: StringName) -> String:
                            return take_text(name)
                        
                        func from_literal() -> StringName:
                            return take_name("call\\nname")
                        
                        func from_direct_literal() -> StringName:
                            return take_name(&"call_name")
                        """,
                Map.of("BodyInsnStringFamilyCallArgs", "RuntimeBodyInsnStringFamilyCallArgs"),
                true
        );
        var fromTextContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilyCallArgs",
                "from_text"
        );
        var fromNameContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilyCallArgs",
                "from_name"
        );
        var fromLiteralContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilyCallArgs",
                "from_literal"
        );
        var fromDirectLiteralContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilyCallArgs",
                "from_direct_literal"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var fromTextInstructions = allInstructions(fromTextContext.targetFunction());
        var fromNameInstructions = allInstructions(fromNameContext.targetFunction());
        var fromLiteralInstructions = allInstructions(fromLiteralContext.targetFunction());
        var fromDirectLiteralInstructions = allInstructions(fromDirectLiteralContext.targetFunction());

        var textConstructor = requireOnlyInstruction(fromTextContext.targetFunction(), ConstructBuiltinInsn.class);
        var nameConstructor = requireOnlyInstruction(fromNameContext.targetFunction(), ConstructBuiltinInsn.class);
        var literalConstructor = requireOnlyInstruction(fromLiteralContext.targetFunction(), ConstructBuiltinInsn.class);
        var textCall = requireOnlyInstruction(fromTextContext.targetFunction(), CallMethodInsn.class);
        var nameCall = requireOnlyInstruction(fromNameContext.targetFunction(), CallMethodInsn.class);
        var literalCall = requireOnlyInstruction(fromLiteralContext.targetFunction(), CallMethodInsn.class);
        var directLiteralCall = requireOnlyInstruction(fromDirectLiteralContext.targetFunction(), CallMethodInsn.class);
        var literalString = requireOnlyInstruction(fromLiteralContext.targetFunction(), LiteralStringInsn.class);
        var literalStringName = requireOnlyInstruction(fromDirectLiteralContext.targetFunction(), LiteralStringNameInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(
                        GdStringType.STRING,
                        requireVariableType(fromTextContext.targetFunction(), onlyVariableOperandId(textConstructor.args()))
                ),
                () -> assertEquals(
                        GdStringNameType.STRING_NAME,
                        requireVariableType(fromNameContext.targetFunction(), onlyVariableOperandId(nameConstructor.args()))
                ),
                () -> assertEquals(literalString.resultId(), onlyVariableOperandId(literalConstructor.args())),
                () -> assertEquals(textConstructor.resultId(), onlyVariableOperandId(textCall.args())),
                () -> assertEquals(nameConstructor.resultId(), onlyVariableOperandId(nameCall.args())),
                () -> assertEquals(literalConstructor.resultId(), onlyVariableOperandId(literalCall.args())),
                () -> assertEquals(literalStringName.resultId(), onlyVariableOperandId(directLiteralCall.args())),
                () -> assertEquals("call\nname", literalString.value()),
                () -> assertEquals("call_name", literalStringName.value()),
                () -> assertEquals(
                        GdStringNameType.STRING_NAME,
                        requireVariableType(fromTextContext.targetFunction(), textConstructor.resultId())
                ),
                () -> assertEquals(GdStringType.STRING, requireVariableType(fromNameContext.targetFunction(), nameConstructor.resultId())),
                () -> assertEquals(
                        GdStringNameType.STRING_NAME,
                        requireVariableType(fromLiteralContext.targetFunction(), literalConstructor.resultId())
                ),
                () -> assertEquals(0, countInstructions(fromDirectLiteralInstructions, ConstructBuiltinInsn.class)),
                () -> assertEquals(0, countInstructions(fromTextInstructions, CallIntrinsicInsn.class)),
                () -> assertEquals(0, countInstructions(fromNameInstructions, CallIntrinsicInsn.class)),
                () -> assertEquals(0, countInstructions(fromLiteralInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(fromDirectLiteralInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(fromLiteralInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(fromDirectLiteralInstructions, UnpackVariantInsn.class))
        );
    }

    @Test
    void runLowersStringFamilyReturnSlotsThroughConstructBuiltinInsn() throws Exception {
        var prepared = prepareContext(
                "body_insn_string_family_return.gd",
                """
                        class_name BodyInsnStringFamilyReturn
                        extends RefCounted
                        
                        func ret_name(text: String) -> StringName:
                            return text
                        
                        func ret_text(name: StringName) -> String:
                            return name
                        
                        func ret_name_literal() -> StringName:
                            return "return\\nvalue"
                        
                        func ret_name_direct_literal() -> StringName:
                            return &"return_name"
                        """,
                Map.of("BodyInsnStringFamilyReturn", "RuntimeBodyInsnStringFamilyReturn"),
                true
        );
        var retNameContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilyReturn",
                "ret_name"
        );
        var retTextContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilyReturn",
                "ret_text"
        );
        var retNameLiteralContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilyReturn",
                "ret_name_literal"
        );
        var retNameDirectLiteralContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringFamilyReturn",
                "ret_name_direct_literal"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var retNameConstructor = requireOnlyInstruction(retNameContext.targetFunction(), ConstructBuiltinInsn.class);
        var retTextConstructor = requireOnlyInstruction(retTextContext.targetFunction(), ConstructBuiltinInsn.class);
        var retNameLiteralConstructor = requireOnlyInstruction(retNameLiteralContext.targetFunction(), ConstructBuiltinInsn.class);
        var retNameReturn = requireOnlyReturnInsn(retNameContext.targetFunction());
        var retTextReturn = requireOnlyReturnInsn(retTextContext.targetFunction());
        var retNameLiteralReturn = requireOnlyReturnInsn(retNameLiteralContext.targetFunction());
        var retNameDirectLiteralReturn = requireOnlyReturnInsn(retNameDirectLiteralContext.targetFunction());
        var literalString = requireOnlyInstruction(retNameLiteralContext.targetFunction(), LiteralStringInsn.class);
        var literalStringName = requireOnlyInstruction(retNameDirectLiteralContext.targetFunction(), LiteralStringNameInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(
                        GdStringType.STRING,
                        requireVariableType(retNameContext.targetFunction(), onlyVariableOperandId(retNameConstructor.args()))
                ),
                () -> assertEquals(
                        GdStringNameType.STRING_NAME,
                        requireVariableType(retTextContext.targetFunction(), onlyVariableOperandId(retTextConstructor.args()))
                ),
                () -> assertEquals(literalString.resultId(), onlyVariableOperandId(retNameLiteralConstructor.args())),
                () -> assertEquals(retNameConstructor.resultId(), retNameReturn.returnValueId()),
                () -> assertEquals(retTextConstructor.resultId(), retTextReturn.returnValueId()),
                () -> assertEquals(retNameLiteralConstructor.resultId(), retNameLiteralReturn.returnValueId()),
                () -> assertEquals(literalStringName.resultId(), retNameDirectLiteralReturn.returnValueId()),
                () -> assertEquals("return\nvalue", literalString.value()),
                () -> assertEquals("return_name", literalStringName.value()),
                () -> assertEquals(
                        0,
                        countInstructions(allInstructions(retNameDirectLiteralContext.targetFunction()), ConstructBuiltinInsn.class)
                ),
                () -> assertEquals(0, countInstructions(allInstructions(retNameContext.targetFunction()), PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(allInstructions(retTextContext.targetFunction()), UnpackVariantInsn.class))
        );
    }

    @Test
    void runLowersStringFamilyPropertyInitializersThroughConstructBuiltinInsn() throws Exception {
        var prepared = prepareContext(
                "body_insn_string_family_property_init.gd",
                """
                        class_name BodyInsnStringFamilyPropertyInit
                        extends RefCounted
                        
                        var name_from_text: StringName = "field\\nname"
                        var name_direct: StringName = &"field_name"
                        var text_from_name: String = &"field_text"
                        
                        func ping() -> int:
                            return 1
                        """,
                Map.of("BodyInsnStringFamilyPropertyInit", "RuntimeBodyInsnStringFamilyPropertyInit"),
                true
        );
        var nameFromTextContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBodyInsnStringFamilyPropertyInit",
                "_field_init_name_from_text"
        );
        var nameDirectContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBodyInsnStringFamilyPropertyInit",
                "_field_init_name_direct"
        );
        var textFromNameContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBodyInsnStringFamilyPropertyInit",
                "_field_init_text_from_name"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var nameFromTextConstructor = requireOnlyInstruction(
                nameFromTextContext.targetFunction(),
                ConstructBuiltinInsn.class
        );
        var textFromNameConstructor = requireOnlyInstruction(
                textFromNameContext.targetFunction(),
                ConstructBuiltinInsn.class
        );
        var nameFromTextLiteral = requireOnlyInstruction(nameFromTextContext.targetFunction(), LiteralStringInsn.class);
        var nameDirectLiteral = requireOnlyInstruction(nameDirectContext.targetFunction(), LiteralStringNameInsn.class);
        var textFromNameLiteral = requireOnlyInstruction(textFromNameContext.targetFunction(), LiteralStringNameInsn.class);
        var nameFromTextReturn = requireOnlyReturnInsn(nameFromTextContext.targetFunction());
        var nameDirectReturn = requireOnlyReturnInsn(nameDirectContext.targetFunction());
        var textFromNameReturn = requireOnlyReturnInsn(textFromNameContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(nameFromTextLiteral.resultId(), onlyVariableOperandId(nameFromTextConstructor.args())),
                () -> assertEquals(textFromNameLiteral.resultId(), onlyVariableOperandId(textFromNameConstructor.args())),
                () -> assertEquals(nameFromTextConstructor.resultId(), nameFromTextReturn.returnValueId()),
                () -> assertEquals(nameDirectLiteral.resultId(), nameDirectReturn.returnValueId()),
                () -> assertEquals(textFromNameConstructor.resultId(), textFromNameReturn.returnValueId()),
                () -> assertEquals("field\nname", nameFromTextLiteral.value()),
                () -> assertEquals("field_name", nameDirectLiteral.value()),
                () -> assertEquals("field_text", textFromNameLiteral.value()),
                () -> assertEquals(
                        GdStringNameType.STRING_NAME,
                        requireVariableType(nameFromTextContext.targetFunction(), nameFromTextConstructor.resultId())
                ),
                () -> assertEquals(
                        GdStringType.STRING,
                        requireVariableType(textFromNameContext.targetFunction(), textFromNameConstructor.resultId())
                ),
                () -> assertEquals(0, countInstructions(allInstructions(nameDirectContext.targetFunction()), ConstructBuiltinInsn.class)),
                () -> assertEquals(0, countInstructions(allInstructions(nameFromTextContext.targetFunction()), PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(allInstructions(textFromNameContext.targetFunction()), UnpackVariantInsn.class))
        );
    }

    @Test
    void runMaterializesPrimitiveCastBoundariesForAssignmentsCallsAndReturns() throws Exception {
        var prepared = prepareContext(
                "body_insn_primitive_cast_boundary.gd",
                """
                        class_name BodyInsnPrimitiveCastBoundary
                        extends RefCounted
                        
                        var ratio: float
                        
                        func take_float(value: float) -> float:
                            return value
                        
                        func assign(seed: int) -> void:
                            ratio = seed
                        
                        func call(seed: int) -> float:
                            return take_float(seed)
                        
                        func ret(seed: int) -> float:
                            return seed
                        """,
                Map.of("BodyInsnPrimitiveCastBoundary", "RuntimeBodyInsnPrimitiveCastBoundary"),
                true
        );
        var assignContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnPrimitiveCastBoundary",
                "assign"
        );
        var callContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnPrimitiveCastBoundary",
                "call"
        );
        var retContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnPrimitiveCastBoundary",
                "ret"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var assignInstructions = allInstructions(assignContext.targetFunction());
        var callInstructions = allInstructions(callContext.targetFunction());
        var retInstructions = allInstructions(retContext.targetFunction());
        var assignmentCast = requireOnlyInstruction(assignContext.targetFunction(), CallIntrinsicInsn.class);
        var callCast = requireOnlyInstruction(callContext.targetFunction(), CallIntrinsicInsn.class);
        var callInsn = requireOnlyInstruction(callContext.targetFunction(), CallMethodInsn.class);
        var returnCast = requireOnlyInstruction(retContext.targetFunction(), CallIntrinsicInsn.class);
        var returnInsn = requireOnlyReturnInsn(retContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("c_int_to_float", assignmentCast.intrinsicName()),
                () -> assertTrue(storeValueIdsForProperty(assignInstructions, "ratio").contains(assignmentCast.resultId())),
                () -> assertEquals(GdFloatType.FLOAT, requireIntrinsicResultType(assignContext.targetFunction(), assignmentCast)),
                () -> assertEquals(
                        GdIntType.INT,
                        requireVariableType(
                                assignContext.targetFunction(),
                                onlyVariableOperandId(assignmentCast.args())
                        )
                ),
                () -> assertEquals("c_int_to_float", callCast.intrinsicName()),
                () -> assertEquals(callCast.resultId(), onlyVariableOperandId(callInsn.args())),
                () -> assertEquals(GdFloatType.FLOAT, requireIntrinsicResultType(callContext.targetFunction(), callCast)),
                () -> assertEquals(
                        GdIntType.INT,
                        requireVariableType(
                                callContext.targetFunction(),
                                onlyVariableOperandId(callCast.args())
                        )
                ),
                () -> assertEquals("c_int_to_float", returnCast.intrinsicName()),
                () -> assertEquals(returnCast.resultId(), returnInsn.returnValueId()),
                () -> assertEquals(GdFloatType.FLOAT, requireIntrinsicResultType(retContext.targetFunction(), returnCast)),
                () -> assertEquals(
                        GdIntType.INT,
                        requireVariableType(
                                retContext.targetFunction(),
                                onlyVariableOperandId(returnCast.args())
                        )
                ),
                () -> assertEquals(0, countInstructions(assignInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(callInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(retInstructions, PackVariantInsn.class))
        );
    }

    @Test
    void runMaterializesVectorIntrinsicCastBoundariesForLocalAssignmentsCallsAndReturns() throws Exception {
        var prepared = prepareContext(
                "body_insn_vector_intrinsic_cast_boundary.gd",
                """
                        class_name BodyInsnVectorIntrinsicCastBoundary
                        extends RefCounted
                        
                        var position: Vector3
                        
                        func take_vector(value: Vector3) -> Vector3:
                            return value
                        
                        func local(seed: Vector3i) -> Vector3:
                            var value: Vector3 = seed
                            return value
                        
                        func assign(seed: Vector3i) -> void:
                            position = seed
                        
                        func call(seed: Vector3i) -> Vector3:
                            return take_vector(seed)
                        
                        func ret(seed: Vector3i) -> Vector3:
                            return seed
                        """,
                Map.of("BodyInsnVectorIntrinsicCastBoundary", "RuntimeBodyInsnVectorIntrinsicCastBoundary"),
                true
        );
        var localContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnVectorIntrinsicCastBoundary",
                "local"
        );
        var assignContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnVectorIntrinsicCastBoundary",
                "assign"
        );
        var callContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnVectorIntrinsicCastBoundary",
                "call"
        );
        var retContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnVectorIntrinsicCastBoundary",
                "ret"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var localInstructions = allInstructions(localContext.targetFunction());
        var assignInstructions = allInstructions(assignContext.targetFunction());
        var callInstructions = allInstructions(callContext.targetFunction());
        var retInstructions = allInstructions(retContext.targetFunction());
        var localCast = requireOnlyInstruction(localContext.targetFunction(), CallIntrinsicInsn.class);
        var assignmentCast = requireOnlyInstruction(assignContext.targetFunction(), CallIntrinsicInsn.class);
        var callCast = requireOnlyInstruction(callContext.targetFunction(), CallIntrinsicInsn.class);
        var callInsn = requireOnlyInstruction(callContext.targetFunction(), CallMethodInsn.class);
        var returnCast = requireOnlyInstruction(retContext.targetFunction(), CallIntrinsicInsn.class);
        var returnInsn = requireOnlyReturnInsn(retContext.targetFunction());
        var localAssignSources = assignSourcesByTarget(localInstructions);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("c_vector3i_to_vector3", localCast.intrinsicName()),
                () -> assertEquals(localCast.resultId(), localAssignSources.get("value")),
                () -> assertEquals(GdFloatVectorType.VECTOR3, requireIntrinsicResultType(localContext.targetFunction(), localCast)),
                () -> assertEquals(
                        GdIntVectorType.VECTOR3I,
                        requireVariableType(
                                localContext.targetFunction(),
                                onlyVariableOperandId(localCast.args())
                        )
                ),
                () -> assertEquals("c_vector3i_to_vector3", assignmentCast.intrinsicName()),
                () -> assertTrue(storeValueIdsForProperty(assignInstructions, "position").contains(assignmentCast.resultId())),
                () -> assertEquals(GdFloatVectorType.VECTOR3, requireIntrinsicResultType(assignContext.targetFunction(), assignmentCast)),
                () -> assertEquals(
                        GdIntVectorType.VECTOR3I,
                        requireVariableType(
                                assignContext.targetFunction(),
                                onlyVariableOperandId(assignmentCast.args())
                        )
                ),
                () -> assertEquals("c_vector3i_to_vector3", callCast.intrinsicName()),
                () -> assertEquals(callCast.resultId(), onlyVariableOperandId(callInsn.args())),
                () -> assertEquals(GdFloatVectorType.VECTOR3, requireIntrinsicResultType(callContext.targetFunction(), callCast)),
                () -> assertEquals(
                        GdIntVectorType.VECTOR3I,
                        requireVariableType(
                                callContext.targetFunction(),
                                onlyVariableOperandId(callCast.args())
                        )
                ),
                () -> assertEquals("c_vector3i_to_vector3", returnCast.intrinsicName()),
                () -> assertEquals(returnCast.resultId(), returnInsn.returnValueId()),
                () -> assertEquals(GdFloatVectorType.VECTOR3, requireIntrinsicResultType(retContext.targetFunction(), returnCast)),
                () -> assertEquals(
                        GdIntVectorType.VECTOR3I,
                        requireVariableType(
                                retContext.targetFunction(),
                                onlyVariableOperandId(returnCast.args())
                        )
                ),
                () -> assertEquals(0, countInstructions(localInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(assignInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(callInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(retInstructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(localInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(assignInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(callInstructions, UnpackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(retInstructions, UnpackVariantInsn.class))
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
    void runReusesMaterializedSubscriptKeyForWritableRouteWriteback() throws Exception {
        var prepared = prepareContext(
                "body_insn_materialized_subscript_writeback.gd",
                """
                        class_name BodyInsnMaterializedSubscriptWriteback
                        extends RefCounted
                        
                        var payloads: Dictionary[float, PackedInt32Array]
                        
                        func ping(index: int, seed: int) -> void:
                            payloads[index].push_back(seed)
                        """,
                Map.of(
                        "BodyInsnMaterializedSubscriptWriteback",
                        "RuntimeBodyInsnMaterializedSubscriptWriteback"
                ),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnMaterializedSubscriptWriteback",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var propertyLoad = requireOnlyInstruction(pingContext.targetFunction(), LoadPropertyInsn.class);
        var castInsns = instructions.stream()
                .filter(CallIntrinsicInsn.class::isInstance)
                .map(CallIntrinsicInsn.class::cast)
                .toList();
        var keyedLoads = instructions.stream()
                .filter(VariantGetKeyedInsn.class::isInstance)
                .map(VariantGetKeyedInsn.class::cast)
                .toList();
        var callInsn = requireOnlyInstruction(pingContext.targetFunction(), CallMethodInsn.class);
        var keyedStore = requireOnlyInstruction(pingContext.targetFunction(), VariantSetKeyedInsn.class);
        var writebackCastInsn = castInsns.stream()
                .filter(insn -> insn.resultId() != null && insn.resultId().equals(keyedStore.keyId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected writeback key to come from c_int_to_float"));
        var receiverKeyedLoad = keyedLoads.stream()
                .filter(insn -> insn.resultId() != null && insn.resultId().equals(callInsn.objectId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected mutating receiver to come from keyed load"));

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("payloads", propertyLoad.propertyName()),
                () -> assertEquals(2, castInsns.size()),
                () -> assertTrue(castInsns.stream().allMatch(insn -> insn.intrinsicName().equals("c_int_to_float"))),
                () -> assertEquals(1, keyedLoads.size()),
                () -> assertEquals("c_int_to_float", writebackCastInsn.intrinsicName()),
                () -> assertEquals(GdFloatType.FLOAT, requireIntrinsicResultType(pingContext.targetFunction(), writebackCastInsn)),
                () -> assertEquals(GdIntType.INT, requireVariableType(
                        pingContext.targetFunction(),
                        onlyVariableOperandId(writebackCastInsn.args())
                )),
                () -> assertEquals(propertyLoad.resultId(), receiverKeyedLoad.keyedVariantId()),
                () -> assertEquals(keyedLoads.getFirst().keyId(), receiverKeyedLoad.keyId()),
                () -> assertEquals(receiverKeyedLoad.resultId(), callInsn.objectId()),
                () -> assertEquals("push_back", callInsn.methodName()),
                () -> assertEquals(propertyLoad.resultId(), keyedStore.keyedVariantId()),
                () -> assertEquals(writebackCastInsn.resultId(), keyedStore.keyId()),
                () -> assertEquals(callInsn.objectId(), keyedStore.valueId()),
                () -> assertEquals(0, storeValueIdsForProperty(instructions, "payloads").size()),
                () -> assertEquals(0, countInstructions(instructions, VariantGetInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, VariantSetInsn.class)),
                () -> assertTrue(instructionIndex(instructions, propertyLoad) < instructionIndex(instructions, receiverKeyedLoad)),
                () -> assertTrue(instructionIndex(instructions, receiverKeyedLoad) < instructionIndex(instructions, callInsn)),
                () -> assertTrue(instructionIndex(instructions, writebackCastInsn) < instructionIndex(instructions, keyedStore)),
                () -> assertTrue(instructionIndex(instructions, callInsn) < instructionIndex(instructions, keyedStore))
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
    void runLowersSingletonValueReceiverAsInstanceReceiverInExecutableBody() throws Exception {
        var prepared = prepareContext(
                "body_insn_singleton_receiver_call.gd",
                """
                        class_name BodyInsnSingletonReceiverCall
                        extends RefCounted
                        
                        func frames() -> int:
                            return Engine.get_frames_drawn()
                        
                        func pressed() -> bool:
                            return Input.is_action_pressed(&"ui_accept", true)
                        
                        func scale() -> void:
                            Engine.set_time_scale(1.0)
                        """,
                Map.of("BodyInsnSingletonReceiverCall", "RuntimeBodyInsnSingletonReceiverCall"),
                true
        );
        var framesContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSingletonReceiverCall",
                "frames"
        );
        var pressedContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSingletonReceiverCall",
                "pressed"
        );
        var scaleContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSingletonReceiverCall",
                "scale"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var framesInstructions = allInstructions(framesContext.targetFunction());
        var framesReceiver = requireOnlyInstruction(framesContext.targetFunction(), LoadStaticInsn.class);
        var framesCall = requireOnlyInstruction(framesContext.targetFunction(), CallMethodInsn.class);
        var framesReturn = requireOnlyReturnInsn(framesContext.targetFunction());
        var pressedReceiver = requireOnlyInstruction(pressedContext.targetFunction(), LoadStaticInsn.class);
        var pressedCall = requireOnlyInstruction(pressedContext.targetFunction(), CallMethodInsn.class);
        var pressedName = requireOnlyInstruction(pressedContext.targetFunction(), LiteralStringNameInsn.class);
        var pressedExactMatch = requireOnlyInstruction(pressedContext.targetFunction(), LiteralBoolInsn.class);
        var pressedArgIds = pressedCall.args().stream()
                .map(operand -> assertInstanceOf(LirInstruction.VariableOperand.class, operand).id())
                .toList();
        var scaleInstructions = allInstructions(scaleContext.targetFunction());
        var scaleReceiver = requireOnlyInstruction(scaleContext.targetFunction(), LoadStaticInsn.class);
        var scaleCall = requireOnlyInstruction(scaleContext.targetFunction(), CallMethodInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("@GlobalScope", framesReceiver.className()),
                () -> assertEquals("Engine", framesReceiver.staticName()),
                () -> assertEquals("get_frames_drawn", framesCall.methodName()),
                () -> assertEquals(framesReceiver.resultId(), framesCall.objectId()),
                () -> assertEquals(framesCall.resultId(), framesReturn.returnValueId()),
                () -> assertEquals(0, countInstructions(framesInstructions, CallGlobalInsn.class)),
                () -> assertEquals("@GlobalScope", pressedReceiver.className()),
                () -> assertEquals("Input", pressedReceiver.staticName()),
                () -> assertEquals("is_action_pressed", pressedCall.methodName()),
                () -> assertEquals(pressedReceiver.resultId(), pressedCall.objectId()),
                () -> assertEquals(List.of(pressedName.resultId(), pressedExactMatch.resultId()), pressedArgIds),
                () -> assertEquals("@GlobalScope", scaleReceiver.className()),
                () -> assertEquals("Engine", scaleReceiver.staticName()),
                () -> assertEquals("set_time_scale", scaleCall.methodName()),
                () -> assertEquals(scaleReceiver.resultId(), scaleCall.objectId()),
                () -> assertNull(scaleCall.resultId()),
                () -> assertEquals(0, countInstructions(scaleInstructions, CallGlobalInsn.class))
        );
    }

    @Test
    void runLowersSingletonReceiverBeforeLaterLocalShadowAsGlobalScopeLoad() throws Exception {
        var prepared = prepareContext(
                "body_insn_singleton_receiver_later_local.gd",
                """
                        class_name BodyInsnSingletonReceiverLaterLocal
                        extends RefCounted

                        func frames() -> int:
                            var frames := Engine.get_frames_drawn()
                            var Engine: String = ""
                            return frames
                        """,
                Map.of("BodyInsnSingletonReceiverLaterLocal", "RuntimeBodyInsnSingletonReceiverLaterLocal"),
                true
        );
        var framesContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnSingletonReceiverLaterLocal",
                "frames"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(framesContext.targetFunction());
        var receiver = requireOnlyInstruction(framesContext.targetFunction(), LoadStaticInsn.class);
        var call = requireOnlyInstruction(framesContext.targetFunction(), CallMethodInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("@GlobalScope", receiver.className()),
                () -> assertEquals("Engine", receiver.staticName()),
                () -> assertEquals("get_frames_drawn", call.methodName()),
                () -> assertEquals(receiver.resultId(), call.objectId()),
                () -> assertEquals(0, countInstructions(instructions, CallGlobalInsn.class))
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
    void runSkipsCommentStatementsInsideExecutableBodies() throws Exception {
        var prepared = prepareContext(
                "body_insn_comment_statement.gd",
                """
                        class_name BodyInsnCommentStatement
                        extends RefCounted
                        
                        func ping() -> void:
                            # leading
                            pass
                            # trailing
                        """,
                Map.of("BodyInsnCommentStatement", "RuntimeBodyInsnCommentStatement"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnCommentStatement",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(pingContext.targetFunction());
        var lineNumberInsn = requireOnlyInstruction(pingContext.targetFunction(), LineNumberInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, countInstructions(instructions, LineNumberInsn.class)),
                () -> assertTrue(lineNumberInsn.lineNumber() > 0),
                () -> assertNotNull(requireOnlyReturnInsn(pingContext.targetFunction()))
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
                        
                        func build_float_vector() -> Vector3:
                            return Vector3(11, -2.5, 7)
                        
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
        var buildFloatVectorContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnConstructorRoutes",
                "build_float_vector"
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
        var floatVectorInstructions = allInstructions(buildFloatVectorContext.targetFunction());
        var arrayInstructions = allInstructions(buildArrayContext.targetFunction());
        var nodeInstructions = allInstructions(buildNodeContext.targetFunction());
        var workerInstructions = allInstructions(buildWorkerContext.targetFunction());

        var vectorConstructInsn = requireOnlyInstruction(buildVectorContext.targetFunction(), ConstructBuiltinInsn.class);
        var floatVectorConstructInsn = requireOnlyInstruction(
                buildFloatVectorContext.targetFunction(),
                ConstructBuiltinInsn.class
        );
        var floatVectorCasts = floatVectorInstructions.stream()
                .filter(CallIntrinsicInsn.class::isInstance)
                .map(CallIntrinsicInsn.class::cast)
                .toList();
        var floatVectorCtorArgIds = floatVectorConstructInsn.args().stream()
                .map(operand -> assertInstanceOf(LirInstruction.VariableOperand.class, operand).id())
                .toList();
        var arrayConstructInsn = requireOnlyInstruction(buildArrayContext.targetFunction(), ConstructBuiltinInsn.class);
        var nodeConstructInsn = requireOnlyInstruction(buildNodeContext.targetFunction(), ConstructObjectInsn.class);
        var workerConstructInsn = requireOnlyInstruction(buildWorkerContext.targetFunction(), ConstructObjectInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, countInstructions(vectorInstructions, ConstructBuiltinInsn.class)),
                () -> assertEquals(3, vectorConstructInsn.args().size()),
                () -> assertEquals(1, countInstructions(floatVectorInstructions, ConstructBuiltinInsn.class)),
                () -> assertEquals(3, floatVectorConstructInsn.args().size()),
                () -> assertEquals(2, floatVectorCasts.size()),
                () -> assertTrue(floatVectorCasts.stream().allMatch(insn -> insn.intrinsicName().equals("c_int_to_float"))),
                () -> assertTrue(floatVectorCasts.stream().map(CallIntrinsicInsn::resultId).allMatch(floatVectorCtorArgIds::contains)),
                () -> assertTrue(floatVectorCasts.stream().allMatch(insn -> requireIntrinsicResultType(
                        buildFloatVectorContext.targetFunction(),
                        insn
                ).equals(GdFloatType.FLOAT))),
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
    void runLowersVariantDynamicMemberReadIntoVariantNamedGet() throws Exception {
        var prepared = prepareContext(
                "body_insn_variant_dynamic_member_read.gd",
                """
                        class_name BodyInsnVariantDynamicMemberRead
                        extends RefCounted
                        
                        func marker(host: Variant) -> Variant:
                            return host.marker
                        """,
                Map.of("BodyInsnVariantDynamicMemberRead", "RuntimeBodyInsnVariantDynamicMemberRead"),
                true
        );
        var markerContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnVariantDynamicMemberRead",
                "marker"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(markerContext.targetFunction());
        var memberLoad = requireSingleMemberLoadItem(markerContext.requireFrontendCfgGraph(), "marker");
        var receiverValueId = memberLoad.baseValueIdOrNull();
        assertNotNull(receiverValueId);
        var getNamedInsn = requireOnlyInstruction(markerContext.targetFunction(), VariantGetNamedInsn.class);
        var getNamedResultId = getNamedInsn.resultId();
        assertNotNull(getNamedResultId);
        var nameLiteral = requireOnlyInstruction(markerContext.targetFunction(), LiteralStringNameInsn.class);
        var returnInsn = requireOnlyReturnInsn(markerContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, LoadPropertyInsn.class)),
                () -> assertEquals("marker", nameLiteral.value()),
                () -> assertEquals(nameLiteral.resultId(), getNamedInsn.nameId()),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.cfgTempSlotId(receiverValueId),
                        getNamedInsn.namedVariantId()
                ),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.cfgTempSlotId(memberLoad.resultValueId()),
                        getNamedResultId
                ),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(
                        markerContext.targetFunction(),
                        getNamedResultId
                )),
                () -> assertEquals(getNamedResultId, returnInsn.returnValueId())
        );
    }

    @Test
    void runLetsDynamicMemberReadCrossTypedReturnBoundary() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_member_typed_return_boundary.gd",
                """
                        class_name BodyInsnDynamicMemberTypedReturnBoundary
                        extends RefCounted
                        
                        func marker(host: Variant) -> int:
                            return host.marker
                        """,
                Map.of(
                        "BodyInsnDynamicMemberTypedReturnBoundary",
                        "RuntimeBodyInsnDynamicMemberTypedReturnBoundary"
                ),
                true
        );
        var markerContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicMemberTypedReturnBoundary",
                "marker"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(markerContext.targetFunction());
        var getNamedInsn = requireOnlyInstruction(markerContext.targetFunction(), VariantGetNamedInsn.class);
        var getNamedResultId = getNamedInsn.resultId();
        assertNotNull(getNamedResultId);
        var unpackInsn = requireOnlyInstruction(markerContext.targetFunction(), UnpackVariantInsn.class);
        var nameLiteral = requireOnlyInstruction(markerContext.targetFunction(), LiteralStringNameInsn.class);
        var returnInsn = requireOnlyReturnInsn(markerContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("marker", nameLiteral.value()),
                () -> assertEquals(nameLiteral.resultId(), getNamedInsn.nameId()),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(
                        markerContext.targetFunction(),
                        getNamedResultId
                )),
                () -> assertEquals(getNamedResultId, unpackInsn.variantId()),
                () -> assertEquals(GdIntType.INT, requireVariableType(
                        markerContext.targetFunction(),
                        unpackInsn.resultId()
                )),
                () -> assertEquals(unpackInsn.resultId(), returnInsn.returnValueId()),
                () -> assertEquals(0, countInstructions(instructions, LoadPropertyInsn.class)),
                () -> assertTrue(instructionIndex(instructions, getNamedInsn) < instructionIndex(instructions, unpackInsn))
        );
    }

    @Test
    void runPacksObjectDynamicMemberReadBeforeVariantNamedGet() throws Exception {
        var prepared = prepareContext(
                "body_insn_object_dynamic_member_read.gd",
                """
                        class_name BodyInsnObjectDynamicMemberRead
                        extends RefCounted
                        
                        func marker(host: Variant) -> Variant:
                            return host.marker
                        """,
                Map.of("BodyInsnObjectDynamicMemberRead", "RuntimeBodyInsnObjectDynamicMemberRead"),
                true
        );
        var markerContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnObjectDynamicMemberRead",
                "marker"
        );
        var memberLoad = requireSingleMemberLoadItem(markerContext.requireFrontendCfgGraph(), "marker");
        var receiverValueId = memberLoad.baseValueIdOrNull();
        assertNotNull(receiverValueId);
        var hostProducer = requireValueProducerByResultId(
                markerContext.requireFrontendCfgGraph(),
                receiverValueId
        );
        prepared.context().requireAnalysisData().expressionTypes().put(
                hostProducer.anchor(),
                FrontendExpressionType.resolved(new GdObjectType("MissingWorker"))
        );
        prepared.context().requireAnalysisData().resolvedMembers().put(
                memberLoad.anchor(),
                FrontendResolvedMember.dynamic(
                        "marker",
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.GDCC,
                        new GdObjectType("MissingWorker"),
                        "MissingWorker.marker",
                        "synthetic metadata-unknown object dynamic member"
                )
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(markerContext.targetFunction());
        var packInsn = requireOnlyInstruction(markerContext.targetFunction(), PackVariantInsn.class);
        var getNamedInsn = requireOnlyInstruction(markerContext.targetFunction(), VariantGetNamedInsn.class);
        var getNamedResultId = getNamedInsn.resultId();
        assertNotNull(getNamedResultId);
        var nameLiteral = requireOnlyInstruction(markerContext.targetFunction(), LiteralStringNameInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(FrontendBodyLoweringSupport.cfgTempSlotId(receiverValueId), packInsn.valueId()),
                () -> assertEquals(packInsn.resultId(), getNamedInsn.namedVariantId()),
                () -> assertEquals("marker", nameLiteral.value()),
                () -> assertEquals(nameLiteral.resultId(), getNamedInsn.nameId()),
                () -> assertEquals(0, countInstructions(instructions, LoadPropertyInsn.class)),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(
                        markerContext.targetFunction(),
                        packInsn.resultId()
                )),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(
                        markerContext.targetFunction(),
                        getNamedResultId
                ))
        );
    }

    @Test
    void runLowersVariantDynamicMemberAssignmentIntoVariantNamedSet() throws Exception {
        var prepared = prepareContext(
                "body_insn_variant_dynamic_member_write.gd",
                """
                        class_name BodyInsnVariantDynamicMemberWrite
                        extends RefCounted
                        
                        func marker(host: Variant, value: Variant) -> void:
                            host.marker = value
                        """,
                Map.of("BodyInsnVariantDynamicMemberWrite", "RuntimeBodyInsnVariantDynamicMemberWrite"),
                true
        );
        var markerContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnVariantDynamicMemberWrite",
                "marker"
        );
        var assignmentItem = requireSingleSequenceItem(markerContext.requireFrontendCfgGraph(), AssignmentItem.class);

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(markerContext.targetFunction());
        var setNamedInsn = requireOnlyInstruction(markerContext.targetFunction(), VariantSetNamedInsn.class);
        var nameLiteral = requireOnlyInstruction(markerContext.targetFunction(), LiteralStringNameInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("marker", nameLiteral.value()),
                () -> assertEquals(nameLiteral.resultId(), setNamedInsn.nameId()),
                () -> assertEquals("host", setNamedInsn.namedVariantId()),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.cfgTempSlotId(assignmentItem.rhsValueId()),
                        setNamedInsn.valueId()
                ),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, LoadPropertyInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, StorePropertyInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, VariantGetNamedInsn.class))
        );
    }

    @Test
    void runPacksObjectDynamicMemberAssignmentBeforeVariantNamedSet() throws Exception {
        var prepared = prepareContext(
                "body_insn_object_dynamic_member_write.gd",
                """
                        class_name BodyInsnObjectDynamicMemberWrite
                        extends RefCounted
                        
                        func marker(host: Variant, value: Variant) -> void:
                            host.marker = value
                        """,
                Map.of("BodyInsnObjectDynamicMemberWrite", "RuntimeBodyInsnObjectDynamicMemberWrite"),
                true
        );
        var markerContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnObjectDynamicMemberWrite",
                "marker"
        );
        var assignmentItem = requireSingleSequenceItem(markerContext.requireFrontendCfgGraph(), AssignmentItem.class);
        var receiverValueId = assignmentItem.targetOperandValueIds().getFirst();
        var hostProducer = requireValueProducerByResultId(markerContext.requireFrontendCfgGraph(), receiverValueId);
        var objectType = new GdObjectType("MissingWorker");
        replaceParameterType(markerContext, "host", objectType);
        var leafAnchor = assignmentItem.writableRoutePayload().leaf().anchor();
        prepared.context().requireAnalysisData().expressionTypes().put(
                hostProducer.anchor(),
                FrontendExpressionType.resolved(objectType)
        );
        prepared.context().requireAnalysisData().expressionTypes().put(
                leafAnchor,
                FrontendExpressionType.dynamic("synthetic metadata-unknown object dynamic member write")
        );
        prepared.context().requireAnalysisData().resolvedMembers().put(
                leafAnchor,
                FrontendResolvedMember.dynamic(
                        "marker",
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.GDCC,
                        objectType,
                        "MissingWorker.marker",
                        "synthetic metadata-unknown object dynamic member write"
                )
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(markerContext.targetFunction());
        var packInsn = requireOnlyInstruction(markerContext.targetFunction(), PackVariantInsn.class);
        var setNamedInsn = requireOnlyInstruction(markerContext.targetFunction(), VariantSetNamedInsn.class);
        var nameLiteral = requireOnlyInstruction(markerContext.targetFunction(), LiteralStringNameInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("host", packInsn.valueId()),
                () -> assertEquals(packInsn.resultId(), setNamedInsn.namedVariantId()),
                () -> assertEquals("marker", nameLiteral.value()),
                () -> assertEquals(nameLiteral.resultId(), setNamedInsn.nameId()),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.cfgTempSlotId(assignmentItem.rhsValueId()),
                        setNamedInsn.valueId()
                ),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(
                        markerContext.targetFunction(),
                        packInsn.resultId()
                )),
                () -> assertEquals(0, countInstructions(instructions, LoadPropertyInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, StorePropertyInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, VariantGetNamedInsn.class))
        );
    }

    @Test
    void runPacksObjectDynamicMemberAssignmentWithConcreteValueBeforeVariantNamedSet() throws Exception {
        var prepared = prepareContext(
                "body_insn_object_dynamic_member_concrete_write.gd",
                """
                        class_name BodyInsnObjectDynamicMemberConcreteWrite
                        extends RefCounted
                        
                        func marker(host: Variant, value: int) -> void:
                            host.marker = value
                        """,
                Map.of(
                        "BodyInsnObjectDynamicMemberConcreteWrite",
                        "RuntimeBodyInsnObjectDynamicMemberConcreteWrite"
                ),
                true
        );
        var markerContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnObjectDynamicMemberConcreteWrite",
                "marker"
        );
        var assignmentItem = requireSingleSequenceItem(markerContext.requireFrontendCfgGraph(), AssignmentItem.class);
        var receiverValueId = assignmentItem.targetOperandValueIds().getFirst();
        var hostProducer = requireValueProducerByResultId(markerContext.requireFrontendCfgGraph(), receiverValueId);
        var objectType = new GdObjectType("MissingWorker");
        replaceParameterType(markerContext, "host", objectType);
        var leafAnchor = assignmentItem.writableRoutePayload().leaf().anchor();
        prepared.context().requireAnalysisData().expressionTypes().put(
                hostProducer.anchor(),
                FrontendExpressionType.resolved(objectType)
        );
        prepared.context().requireAnalysisData().expressionTypes().put(
                leafAnchor,
                FrontendExpressionType.dynamic("synthetic metadata-unknown object dynamic member concrete write")
        );
        prepared.context().requireAnalysisData().resolvedMembers().put(
                leafAnchor,
                FrontendResolvedMember.dynamic(
                        "marker",
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.GDCC,
                        objectType,
                        "MissingWorker.marker",
                        "synthetic metadata-unknown object dynamic member concrete write"
                )
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(markerContext.targetFunction());
        var packInsns = instructions.stream()
                .filter(PackVariantInsn.class::isInstance)
                .map(PackVariantInsn.class::cast)
                .toList();
        var setNamedInsn = requireOnlyInstruction(markerContext.targetFunction(), VariantSetNamedInsn.class);
        var nameLiteral = requireOnlyInstruction(markerContext.targetFunction(), LiteralStringNameInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(2, packInsns.size()),
                () -> assertTrue(packInsns.stream().map(PackVariantInsn::valueId).toList().contains("host")),
                () -> assertTrue(packInsns.stream()
                        .map(PackVariantInsn::valueId)
                        .toList()
                        .contains(FrontendBodyLoweringSupport.cfgTempSlotId(assignmentItem.rhsValueId()))),
                () -> assertTrue(packInsns.stream().map(PackVariantInsn::resultId).toList().contains(
                        setNamedInsn.namedVariantId()
                )),
                () -> assertTrue(packInsns.stream().map(PackVariantInsn::resultId).toList().contains(
                        setNamedInsn.valueId()
                )),
                () -> assertEquals("marker", nameLiteral.value()),
                () -> assertEquals(nameLiteral.resultId(), setNamedInsn.nameId()),
                () -> assertEquals(GdVariantType.VARIANT, requireVariableType(
                        markerContext.targetFunction(),
                        setNamedInsn.valueId()
                )),
                () -> assertEquals(0, countInstructions(instructions, LoadPropertyInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, StorePropertyInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, VariantGetNamedInsn.class))
        );
    }

    @Test
    void runLowersVariantDynamicMemberCompoundAssignmentThroughVariantNamedRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_variant_dynamic_member_compound_write.gd",
                """
                        class_name BodyInsnVariantDynamicMemberCompoundWrite
                        extends RefCounted
                        
                        func bump(host: Variant) -> void:
                            host.count += 1
                        """,
                Map.of(
                        "BodyInsnVariantDynamicMemberCompoundWrite",
                        "RuntimeBodyInsnVariantDynamicMemberCompoundWrite"
                ),
                true
        );
        var bumpContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnVariantDynamicMemberCompoundWrite",
                "bump"
        );
        var graph = bumpContext.requireFrontendCfgGraph();
        var memberLoad = requireSingleMemberLoadItem(graph, "count");
        var assignmentItem = requireSingleSequenceItem(graph, AssignmentItem.class);
        var receiverValueId = assignmentItem.targetOperandValueIds().getFirst();

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(bumpContext.targetFunction());
        var getNamedInsn = requireOnlyInstruction(bumpContext.targetFunction(), VariantGetNamedInsn.class);
        var setNamedInsn = requireOnlyInstruction(bumpContext.targetFunction(), VariantSetNamedInsn.class);
        var compoundInsn = requireOnlyInstruction(bumpContext.targetFunction(), BinaryOpInsn.class);
        var stringNames = stringNameValuesByResultId(instructions);
        var frozenReceiverSlot = FrontendBodyLoweringSupport.cfgTempSlotId(receiverValueId);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(GodotOperator.ADD, compoundInsn.op()),
                () -> assertEquals(receiverValueId, memberLoad.baseValueIdOrNull()),
                () -> assertEquals("count", stringNames.get(getNamedInsn.nameId())),
                () -> assertEquals("count", stringNames.get(setNamedInsn.nameId())),
                () -> assertEquals(frozenReceiverSlot, getNamedInsn.namedVariantId()),
                () -> assertEquals("host", setNamedInsn.namedVariantId()),
                () -> assertEquals(getNamedInsn.resultId(), compoundInsn.leftId()),
                () -> assertEquals(compoundInsn.resultId(), setNamedInsn.valueId()),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, StorePropertyInsn.class)),
                () -> assertTrue(instructionIndex(instructions, getNamedInsn) < instructionIndex(instructions, compoundInsn)),
                () -> assertTrue(instructionIndex(instructions, compoundInsn) < instructionIndex(instructions, setNamedInsn))
        );
    }

    @Test
    void runLowersObjectDynamicMemberCompoundAssignmentThroughPackedVariantNamedRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_object_dynamic_member_compound_write.gd",
                """
                        class_name BodyInsnObjectDynamicMemberCompoundWrite
                        extends RefCounted
                        
                        func bump(host: Variant) -> void:
                            host.count += 1
                        """,
                Map.of(
                        "BodyInsnObjectDynamicMemberCompoundWrite",
                        "RuntimeBodyInsnObjectDynamicMemberCompoundWrite"
                ),
                true
        );
        var bumpContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnObjectDynamicMemberCompoundWrite",
                "bump"
        );
        var graph = bumpContext.requireFrontendCfgGraph();
        var memberLoad = requireSingleMemberLoadItem(graph, "count");
        var assignmentItem = requireSingleSequenceItem(graph, AssignmentItem.class);
        var receiverValueId = assignmentItem.targetOperandValueIds().getFirst();
        var hostProducer = requireValueProducerByResultId(graph, receiverValueId);
        var objectType = new GdObjectType("MissingWorker");
        replaceParameterType(bumpContext, "host", objectType);
        prepared.context().requireAnalysisData().expressionTypes().put(
                hostProducer.anchor(),
                FrontendExpressionType.resolved(objectType)
        );
        prepared.context().requireAnalysisData().expressionTypes().put(
                memberLoad.anchor(),
                FrontendExpressionType.dynamic("synthetic metadata-unknown object dynamic compound member")
        );
        prepared.context().requireAnalysisData().resolvedMembers().put(
                memberLoad.anchor(),
                FrontendResolvedMember.dynamic(
                        "count",
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.GDCC,
                        objectType,
                        "MissingWorker.count",
                        "synthetic metadata-unknown object dynamic compound member"
                )
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(bumpContext.targetFunction());
        var packInsns = instructions.stream()
                .filter(PackVariantInsn.class::isInstance)
                .map(PackVariantInsn.class::cast)
                .toList();
        var getNamedInsn = requireOnlyInstruction(bumpContext.targetFunction(), VariantGetNamedInsn.class);
        var setNamedInsn = requireOnlyInstruction(bumpContext.targetFunction(), VariantSetNamedInsn.class);
        var compoundInsn = requireOnlyInstruction(bumpContext.targetFunction(), BinaryOpInsn.class);
        var stringNames = stringNameValuesByResultId(instructions);
        var packValueIds = packInsns.stream().map(PackVariantInsn::valueId).toList();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(GodotOperator.ADD, compoundInsn.op()),
                () -> assertEquals(2, packInsns.size()),
                () -> assertTrue(packValueIds.contains(FrontendBodyLoweringSupport.cfgTempSlotId(receiverValueId))),
                () -> assertTrue(packValueIds.contains("host")),
                () -> assertEquals("count", stringNames.get(getNamedInsn.nameId())),
                () -> assertEquals("count", stringNames.get(setNamedInsn.nameId())),
                () -> assertTrue(packInsns.stream().map(PackVariantInsn::resultId).toList().contains(getNamedInsn.namedVariantId())),
                () -> assertTrue(packInsns.stream().map(PackVariantInsn::resultId).toList().contains(setNamedInsn.namedVariantId())),
                () -> assertEquals(getNamedInsn.resultId(), compoundInsn.leftId()),
                () -> assertEquals(compoundInsn.resultId(), setNamedInsn.valueId()),
                () -> assertEquals(0, countInstructions(instructions, LoadPropertyInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, StorePropertyInsn.class)),
                () -> assertTrue(instructionIndex(instructions, getNamedInsn) < instructionIndex(instructions, compoundInsn)),
                () -> assertTrue(instructionIndex(instructions, compoundInsn) < instructionIndex(instructions, setNamedInsn))
        );
    }

    @Test
    void runKeepsResolvedObjectPropertyCompoundAssignmentOnOrdinaryRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_resolved_object_property_write_guard.gd",
                """
                        class_name BodyInsnResolvedObjectPropertyWriteGuard
                        extends RefCounted
                        
                        var hp: int = 0
                        
                        func bump(box: BodyInsnResolvedObjectPropertyWriteGuard, delta: int) -> int:
                            box.hp += delta
                            return box.hp
                        """,
                Map.of(
                        "BodyInsnResolvedObjectPropertyWriteGuard",
                        "RuntimeBodyInsnResolvedObjectPropertyWriteGuard"
                ),
                true
        );
        var bumpContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnResolvedObjectPropertyWriteGuard",
                "bump"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(bumpContext.targetFunction());
        var stores = instructions.stream()
                .filter(StorePropertyInsn.class::isInstance)
                .map(StorePropertyInsn.class::cast)
                .filter(instruction -> instruction.propertyName().equals("hp"))
                .toList();
        var loads = instructions.stream()
                .filter(LoadPropertyInsn.class::isInstance)
                .map(LoadPropertyInsn.class::cast)
                .filter(instruction -> instruction.propertyName().equals("hp"))
                .toList();
        var compoundInsn = requireOnlyInstruction(bumpContext.targetFunction(), BinaryOpInsn.class);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(GodotOperator.ADD, compoundInsn.op()),
                () -> assertEquals(1, stores.size()),
                () -> assertEquals(2, loads.size()),
                () -> assertEquals(compoundInsn.resultId(), stores.getFirst().valueId()),
                () -> assertEquals(0, countInstructions(instructions, VariantGetNamedInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, VariantSetNamedInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, LiteralStringNameInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class))
        );
    }

    @Test
    void runThreadsContinuationBlockAfterRuntimeGatedDynamicOwnerWriteback() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_member_owner_writeback.gd",
                """
                        class_name BodyInsnDynamicMemberOwnerWriteback
                        extends RefCounted
                        
                        func write_path(host: Variant, value: Variant, next: Variant) -> void:
                            host.box.value = value
                            host.after = next
                        """,
                Map.of("BodyInsnDynamicMemberOwnerWriteback", "RuntimeBodyInsnDynamicMemberOwnerWriteback"),
                true
        );
        var writePathContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicMemberOwnerWriteback",
                "write_path"
        );
        var afterAssignmentItem = requireAssignmentItemForLeafMember(
                writePathContext.requireFrontendCfgGraph(),
                "after"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = writePathContext.targetFunction();
        var instructions = allInstructions(function);
        var stringNames = stringNameValuesByResultId(instructions);
        var boxGets = variantGetNamedInsnsForName(instructions, "box");
        var boxSets = variantSetNamedInsnsForName(instructions, "box");
        var valueSets = variantSetNamedInsnsForName(instructions, "value");
        var afterSets = variantSetNamedInsnsForName(instructions, "after");
        var entryBlock = requireBlock(function, "seq_0");
        var gateCalls = entryBlock.getNonTerminatorInstructions().stream()
                .filter(CallGlobalInsn.class::isInstance)
                .map(CallGlobalInsn.class::cast)
                .toList();
        var gateBranch = assertInstanceOf(GoIfInsn.class, entryBlock.getTerminator());
        var applyBlock = requireBlock(function, gateBranch.trueBbId());
        var skipBlock = requireBlock(function, gateBranch.falseBbId());
        var applyGoto = assertInstanceOf(GotoInsn.class, applyBlock.getTerminator());
        var skipGoto = assertInstanceOf(GotoInsn.class, skipBlock.getTerminator());
        var continuationBlock = requireBlock(function, applyGoto.targetBbId());
        var continuationNameIds = continuationBlock.getNonTerminatorInstructions().stream()
                .filter(LiteralStringNameInsn.class::isInstance)
                .map(LiteralStringNameInsn.class::cast)
                .map(LiteralStringNameInsn::resultId)
                .toList();
        var entryNameIds = entryBlock.getNonTerminatorInstructions().stream()
                .filter(LiteralStringNameInsn.class::isInstance)
                .map(LiteralStringNameInsn.class::cast)
                .map(LiteralStringNameInsn::resultId)
                .toList();
        var gateCallInsn = gateCalls.getFirst();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(1, boxGets.size()),
                () -> assertEquals(1, boxSets.size()),
                () -> assertEquals(1, valueSets.size()),
                () -> assertEquals(1, afterSets.size()),
                () -> assertEquals("box", stringNames.get(boxGets.getFirst().nameId())),
                () -> assertEquals("box", stringNames.get(boxSets.getFirst().nameId())),
                () -> assertEquals("value", stringNames.get(valueSets.getFirst().nameId())),
                () -> assertEquals("after", stringNames.get(afterSets.getFirst().nameId())),
                () -> assertEquals(valueSets.getFirst().namedVariantId(), boxSets.getFirst().valueId()),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.cfgTempSlotId(afterAssignmentItem.rhsValueId()),
                        afterSets.getFirst().valueId()
                ),
                () -> assertEquals(1, gateCalls.size()),
                () -> assertEquals("gdcc_variant_requires_writeback", gateCallInsn.functionName()),
                () -> assertEquals(valueSets.getFirst().namedVariantId(), onlyVariableOperandId(gateCallInsn.args())),
                () -> assertEquals(gateCallInsn.resultId(), gateBranch.conditionVarId()),
                () -> assertEquals(applyGoto.targetBbId(), skipGoto.targetBbId()),
                () -> assertTrue(applyBlock.getNonTerminatorInstructions().stream().anyMatch(boxSets.getFirst()::equals)),
                () -> assertTrue(continuationBlock.getNonTerminatorInstructions().stream().anyMatch(afterSets.getFirst()::equals)),
                () -> assertTrue(continuationNameIds.stream().anyMatch(id -> stringNames.get(id).equals("after"))),
                () -> assertTrue(entryNameIds.stream().noneMatch(id -> stringNames.get(id).equals("after"))),
                () -> assertEquals(0, countInstructions(instructions, StorePropertyInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertTrue(instructionIndex(instructions, boxGets.getFirst()) < instructionIndex(instructions, valueSets.getFirst())),
                () -> assertTrue(instructionIndex(instructions, valueSets.getFirst()) < instructionIndex(instructions, gateCallInsn)),
                () -> assertTrue(instructionIndex(instructions, gateCallInsn) < instructionIndex(instructions, boxSets.getFirst())),
                () -> assertTrue(instructionIndex(instructions, boxSets.getFirst()) < instructionIndex(instructions, afterSets.getFirst()))
        );
    }

    @Test
    void runAppendsDynamicMemberAssignmentValueAfterRuntimeGatedOwnerWriteback() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_member_assignment_value_writeback.gd",
                """
                        class_name BodyInsnDynamicMemberAssignmentValueWriteback
                        extends RefCounted
                        
                        func write_path(host: Variant, value: Variant) -> void:
                            host.box.value = value
                        """,
                Map.of(
                        "BodyInsnDynamicMemberAssignmentValueWriteback",
                        "RuntimeBodyInsnDynamicMemberAssignmentValueWriteback"
                ),
                true
        );
        var originalContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicMemberAssignmentValueWriteback",
                "write_path"
        );
        var originalGraph = originalContext.requireFrontendCfgGraph();
        var entryNode = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                originalGraph.requireNode(originalGraph.entryNodeId())
        );
        var originalAssignment = requireAssignmentItemForLeafMember(originalGraph, "value");
        var assignmentResultValueId = "v_assignment_value";
        var mutatedAssignment = new AssignmentItem(
                originalAssignment.assignment(),
                originalAssignment.targetOperandValueIds(),
                originalAssignment.rhsValueId(),
                assignmentResultValueId,
                originalAssignment.writableRoutePayload()
        );
        // Source assignment statements are void today; this mutation models a value-producing
        // assignment while preserving the same frozen dynamic writable route payload.
        originalContext.analysisData().expressionTypes().put(
                originalAssignment.assignment(),
                FrontendExpressionType.dynamic("synthetic assignment-as-value dynamic writable route")
        );
        var mutatedItems = entryNode.items().stream()
                .map(item -> item == originalAssignment ? mutatedAssignment : item)
                .toList();
        var mutatedNodes = new LinkedHashMap<>(originalGraph.nodes());
        mutatedNodes.put(
                entryNode.id(),
                new FrontendCfgGraph.SequenceNode(entryNode.id(), mutatedItems, entryNode.nextId())
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
        mutatedContext.publishFrontendCfgGraph(new FrontendCfgGraph(originalGraph.entryNodeId(), mutatedNodes));

        new FrontendBodyLoweringSession(
                mutatedContext,
                new ClassRegistry(ExtensionApiLoader.loadDefault())
        ).run();

        var function = mutatedContext.targetFunction();
        var entryBlock = requireBlock(function, "seq_0");
        var gateBranch = assertInstanceOf(GoIfInsn.class, entryBlock.getTerminator());
        var applyBlock = requireBlock(function, gateBranch.trueBbId());
        var skipBlock = requireBlock(function, gateBranch.falseBbId());
        var applyGoto = assertInstanceOf(GotoInsn.class, applyBlock.getTerminator());
        var skipGoto = assertInstanceOf(GotoInsn.class, skipBlock.getTerminator());
        var continuationBlock = requireBlock(function, applyGoto.targetBbId());
        var assignmentResultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(assignmentResultValueId);
        var continuationAssigns = continuationBlock.getNonTerminatorInstructions().stream()
                .filter(AssignInsn.class::isInstance)
                .map(AssignInsn.class::cast)
                .toList();
        var allAssignSources = assignSourcesByTarget(allInstructions(function));

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(applyGoto.targetBbId(), skipGoto.targetBbId()),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.cfgTempSlotId(originalAssignment.rhsValueId()),
                        allAssignSources.get(assignmentResultSlotId)
                ),
                () -> assertTrue(continuationAssigns.stream()
                        .anyMatch(assign -> assign.resultId().equals(assignmentResultSlotId))),
                () -> assertTrue(entryBlock.getNonTerminatorInstructions().stream()
                        .filter(AssignInsn.class::isInstance)
                        .map(AssignInsn.class::cast)
                        .noneMatch(assign -> assign.resultId().equals(assignmentResultSlotId))),
                () -> assertEquals(1, variantSetNamedInsnsForName(allInstructions(function), "box").size()),
                () -> assertEquals(1, variantSetNamedInsnsForName(allInstructions(function), "value").size())
        );
    }

    @Test
    void runPacksObjectDynamicOwnerBeforeRuntimeGatedReverseCommit() throws Exception {
        var prepared = prepareContext(
                "body_insn_object_dynamic_member_owner_writeback.gd",
                """
                        class_name BodyInsnObjectDynamicMemberOwnerWriteback
                        extends RefCounted
                        
                        func write_path(host: Variant, value: Variant) -> void:
                            host.box.value = value
                        """,
                Map.of(
                        "BodyInsnObjectDynamicMemberOwnerWriteback",
                        "RuntimeBodyInsnObjectDynamicMemberOwnerWriteback"
                ),
                true
        );
        var writePathContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnObjectDynamicMemberOwnerWriteback",
                "write_path"
        );
        var graph = writePathContext.requireFrontendCfgGraph();
        var boxLoad = requireSingleMemberLoadItem(graph, "box");
        var assignmentItem = requireAssignmentItemForLeafMember(graph, "value");
        var hostValueId = boxLoad.baseValueIdOrNull();
        assertNotNull(hostValueId);
        var hostProducer = requireValueProducerByResultId(graph, hostValueId);
        var objectType = new GdObjectType("MissingWorker");
        replaceParameterType(writePathContext, "host", objectType);
        prepared.context().requireAnalysisData().expressionTypes().put(
                hostProducer.anchor(),
                FrontendExpressionType.resolved(objectType)
        );
        prepared.context().requireAnalysisData().expressionTypes().put(
                boxLoad.anchor(),
                FrontendExpressionType.dynamic("synthetic metadata-unknown object dynamic owner")
        );
        prepared.context().requireAnalysisData().expressionTypes().put(
                assignmentItem.writableRoutePayload().leaf().anchor(),
                FrontendExpressionType.dynamic("synthetic metadata-unknown object dynamic leaf")
        );
        prepared.context().requireAnalysisData().resolvedMembers().put(
                boxLoad.anchor(),
                FrontendResolvedMember.dynamic(
                        "box",
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.GDCC,
                        objectType,
                        "MissingWorker.box",
                        "synthetic metadata-unknown object dynamic owner"
                )
        );
        prepared.context().requireAnalysisData().resolvedMembers().put(
                assignmentItem.writableRoutePayload().leaf().anchor(),
                FrontendResolvedMember.dynamic(
                        "value",
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.GDCC,
                        GdVariantType.VARIANT,
                        "Variant.value",
                        "synthetic metadata-unknown object dynamic leaf"
                )
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = writePathContext.targetFunction();
        var instructions = allInstructions(function);
        var packInsns = instructions.stream()
                .filter(PackVariantInsn.class::isInstance)
                .map(PackVariantInsn.class::cast)
                .toList();
        var packValueIds = packInsns.stream().map(PackVariantInsn::valueId).toList();
        var packedResultIds = packInsns.stream().map(PackVariantInsn::resultId).toList();
        var boxGets = variantGetNamedInsnsForName(instructions, "box");
        var boxSets = variantSetNamedInsnsForName(instructions, "box");
        var valueSets = variantSetNamedInsnsForName(instructions, "value");
        var entryBlock = requireBlock(function, "seq_0");
        var gateBranch = assertInstanceOf(GoIfInsn.class, entryBlock.getTerminator());
        var applyBlock = requireBlock(function, gateBranch.trueBbId());
        var applyGoto = assertInstanceOf(GotoInsn.class, applyBlock.getTerminator());
        var continuationBlock = requireBlock(function, applyGoto.targetBbId());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(2, packInsns.size()),
                () -> assertTrue(packValueIds.contains(FrontendBodyLoweringSupport.cfgTempSlotId(hostValueId))),
                () -> assertTrue(packValueIds.contains("host")),
                () -> assertEquals(1, boxGets.size()),
                () -> assertEquals(1, boxSets.size()),
                () -> assertEquals(1, valueSets.size()),
                () -> assertTrue(packedResultIds.contains(boxGets.getFirst().namedVariantId())),
                () -> assertTrue(packedResultIds.contains(boxSets.getFirst().namedVariantId())),
                () -> assertEquals(valueSets.getFirst().namedVariantId(), boxSets.getFirst().valueId()),
                () -> assertTrue(applyBlock.getNonTerminatorInstructions().stream().anyMatch(boxSets.getFirst()::equals)),
                () -> assertTrue(continuationBlock.getNonTerminatorInstructions().stream()
                        .noneMatch(boxSets.getFirst()::equals)),
                () -> assertEquals(0, countInstructions(instructions, StorePropertyInsn.class))
        );
    }

    @Test
    void runPacksObjectDynamicAttributeSubscriptReceiverBeforeNamedBaseWriteback() throws Exception {
        var prepared = prepareContext(
                "body_insn_object_dynamic_attribute_subscript_writeback.gd",
                """
                        class_name BodyInsnObjectDynamicAttributeSubscriptWriteback
                        extends RefCounted
                        
                        func write_path(host: Variant, index: int, value: Variant) -> void:
                            host.payloads[index].value = value
                        """,
                Map.of(
                        "BodyInsnObjectDynamicAttributeSubscriptWriteback",
                        "RuntimeBodyInsnObjectDynamicAttributeSubscriptWriteback"
                ),
                true
        );
        var writePathContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnObjectDynamicAttributeSubscriptWriteback",
                "write_path"
        );
        var graph = writePathContext.requireFrontendCfgGraph();
        var payloadsLoad = requireSingleSequenceItem(graph, SubscriptLoadItem.class);
        assertEquals("payloads", payloadsLoad.memberNameOrNull());
        var assignmentItem = requireAssignmentItemForLeafMember(graph, "value");
        var hostValueId = payloadsLoad.baseValueId();
        var hostProducer = requireValueProducerByResultId(graph, hostValueId);
        var objectType = new GdObjectType("MissingWorker");
        replaceParameterType(writePathContext, "host", objectType);
        prepared.context().requireAnalysisData().expressionTypes().put(
                hostProducer.anchor(),
                FrontendExpressionType.resolved(objectType)
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var function = writePathContext.targetFunction();
        var instructions = allInstructions(function);
        var packInsns = instructions.stream()
                .filter(PackVariantInsn.class::isInstance)
                .map(PackVariantInsn.class::cast)
                .toList();
        var payloadsGets = variantGetNamedInsnsForName(instructions, "payloads");
        var payloadsSets = variantSetNamedInsnsForName(instructions, "payloads");
        var valueSets = variantSetNamedInsnsForName(instructions, "value");
        var indexedStores = instructions.stream()
                .filter(VariantSetIndexedInsn.class::isInstance)
                .map(VariantSetIndexedInsn.class::cast)
                .toList();
        var hostPrefixPackResults = packInsns.stream()
                .filter(pack -> pack.valueId().equals(FrontendBodyLoweringSupport.cfgTempSlotId(hostValueId)))
                .map(PackVariantInsn::resultId)
                .toList();
        var hostSlotPackResults = packInsns.stream()
                .filter(pack -> pack.valueId().equals("host"))
                .map(PackVariantInsn::resultId)
                .toList();
        var entryBlock = requireBlock(function, "seq_0");
        var gateCalls = entryBlock.getNonTerminatorInstructions().stream()
                .filter(CallGlobalInsn.class::isInstance)
                .map(CallGlobalInsn.class::cast)
                .toList();
        var gateBranch = assertInstanceOf(GoIfInsn.class, entryBlock.getTerminator());
        var applyBlock = requireBlock(function, gateBranch.trueBbId());
        var applyGoto = assertInstanceOf(GotoInsn.class, applyBlock.getTerminator());
        var continuationBlock = requireBlock(function, applyGoto.targetBbId());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("value", assignmentItem.writableRoutePayload().leaf().memberNameOrNull()),
                () -> assertEquals(2, packInsns.size()),
                () -> assertEquals(1, hostPrefixPackResults.size()),
                () -> assertEquals(1, hostSlotPackResults.size()),
                () -> assertEquals(2, payloadsGets.size()),
                () -> assertEquals(1, payloadsSets.size()),
                () -> assertEquals(1, valueSets.size()),
                () -> assertEquals(1, indexedStores.size()),
                () -> assertTrue(payloadsGets.stream()
                        .anyMatch(get -> get.namedVariantId().equals(hostPrefixPackResults.getFirst()))),
                () -> assertTrue(payloadsGets.stream()
                        .anyMatch(get -> get.namedVariantId().equals(hostSlotPackResults.getFirst()))),
                () -> assertEquals(hostSlotPackResults.getFirst(), payloadsSets.getFirst().namedVariantId()),
                () -> assertEquals(payloadsSets.getFirst().valueId(), indexedStores.getFirst().variantId()),
                () -> assertEquals(valueSets.getFirst().namedVariantId(), indexedStores.getFirst().valueId()),
                () -> assertEquals(1, gateCalls.size()),
                () -> assertEquals("gdcc_variant_requires_writeback", gateCalls.getFirst().functionName()),
                () -> assertEquals(valueSets.getFirst().namedVariantId(), onlyVariableOperandId(gateCalls.getFirst().args())),
                () -> assertTrue(applyBlock.getNonTerminatorInstructions().stream()
                        .anyMatch(payloadsSets.getFirst()::equals)),
                () -> assertTrue(continuationBlock.getNonTerminatorInstructions().stream()
                        .noneMatch(payloadsSets.getFirst()::equals)),
                () -> assertEquals(0, countInstructions(instructions, LoadPropertyInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, StorePropertyInsn.class))
        );
    }

    @Test
    void runFailsFastWhenDynamicMemberWriteHasNonVariantNonObjectReceiver() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_member_write_invalid_receiver.gd",
                """
                        class_name BodyInsnDynamicMemberWriteInvalidReceiver
                        extends RefCounted
                        
                        func axis_x(vector: Vector3, value: float) -> void:
                            vector.x = value
                        """,
                Map.of(
                        "BodyInsnDynamicMemberWriteInvalidReceiver",
                        "RuntimeBodyInsnDynamicMemberWriteInvalidReceiver"
                ),
                true
        );
        var axisContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicMemberWriteInvalidReceiver",
                "axis_x"
        );
        var assignmentItem = requireSingleSequenceItem(axisContext.requireFrontendCfgGraph(), AssignmentItem.class);
        var leafAnchor = assignmentItem.writableRoutePayload().leaf().anchor();
        prepared.context().requireAnalysisData().expressionTypes().put(
                leafAnchor,
                FrontendExpressionType.dynamic("synthetic illegal dynamic writable member receiver")
        );
        prepared.context().requireAnalysisData().resolvedMembers().put(
                leafAnchor,
                FrontendResolvedMember.dynamic(
                        "x",
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.BUILTIN,
                        GdFloatVectorType.VECTOR3,
                        "Vector3.x",
                        "synthetic illegal dynamic writable member receiver"
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("Variant named member route"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("Variant or Object-family receiver"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("Vector3"), exception.getMessage())
        );
    }

    @Test
    void runFailsFastWhenDynamicMemberWriteUsesTypeMetaRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_member_write_type_meta_route.gd",
                """
                        class_name BodyInsnDynamicMemberWriteTypeMetaRoute
                        extends RefCounted
                        
                        func marker(host: Variant, value: Variant) -> void:
                            host.marker = value
                        """,
                Map.of(
                        "BodyInsnDynamicMemberWriteTypeMetaRoute",
                        "RuntimeBodyInsnDynamicMemberWriteTypeMetaRoute"
                ),
                true
        );
        var markerContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicMemberWriteTypeMetaRoute",
                "marker"
        );
        var assignmentItem = requireSingleSequenceItem(markerContext.requireFrontendCfgGraph(), AssignmentItem.class);
        var leafAnchor = assignmentItem.writableRoutePayload().leaf().anchor();
        prepared.context().requireAnalysisData().resolvedMembers().put(
                leafAnchor,
                FrontendResolvedMember.dynamic(
                        "marker",
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.TYPE_META,
                        ScopeOwnerKind.GDCC,
                        GdVariantType.VARIANT,
                        "Variant.marker",
                        "synthetic type-meta dynamic writable member route"
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("Dynamic writable"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("instance receiver route"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("TYPE_META"), exception.getMessage())
        );
    }

    @Test
    void runKeepsResolvedBuiltinPropertyReadOnOrdinaryPropertyRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_resolved_property_route_guard.gd",
                """
                        class_name BodyInsnResolvedPropertyRouteGuard
                        extends RefCounted
                        
                        func axis_x(vector: Vector3) -> float:
                            return vector.x
                        """,
                Map.of("BodyInsnResolvedPropertyRouteGuard", "RuntimeBodyInsnResolvedPropertyRouteGuard"),
                true
        );
        var axisContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnResolvedPropertyRouteGuard",
                "axis_x"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var instructions = allInstructions(axisContext.targetFunction());
        var loadInsn = requireOnlyInstruction(axisContext.targetFunction(), LoadPropertyInsn.class);
        var memberLoad = requireSingleMemberLoadItem(axisContext.requireFrontendCfgGraph(), "x");
        var receiverValueId = memberLoad.baseValueIdOrNull();
        assertNotNull(receiverValueId);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("x", loadInsn.propertyName()),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.cfgTempSlotId(receiverValueId),
                        loadInsn.objectId()
                ),
                () -> assertEquals(0, countInstructions(instructions, VariantGetNamedInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, LiteralStringNameInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class))
        );
    }

    @Test
    void runFailsFastWhenDynamicMemberReadHasNonVariantNonObjectReceiver() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_member_invalid_receiver.gd",
                """
                        class_name BodyInsnDynamicMemberInvalidReceiver
                        extends RefCounted
                        
                        func axis_x(vector: Vector3) -> float:
                            return vector.x
                        """,
                Map.of("BodyInsnDynamicMemberInvalidReceiver", "RuntimeBodyInsnDynamicMemberInvalidReceiver"),
                true
        );
        var axisContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicMemberInvalidReceiver",
                "axis_x"
        );
        var memberLoad = requireSingleMemberLoadItem(axisContext.requireFrontendCfgGraph(), "x");
        prepared.context().requireAnalysisData().expressionTypes().put(
                memberLoad.anchor(),
                FrontendExpressionType.dynamic("synthetic illegal dynamic member receiver")
        );
        prepared.context().requireAnalysisData().resolvedMembers().put(
                memberLoad.anchor(),
                FrontendResolvedMember.dynamic(
                        "x",
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.BUILTIN,
                        GdFloatVectorType.VECTOR3,
                        "Vector3.x",
                        "synthetic illegal dynamic member receiver"
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("dynamic member load"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("Variant or Object-family receiver"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("Vector3"), exception.getMessage())
        );
    }

    @Test
    void runFailsFastWhenDynamicMemberReadUsesTypeMetaRoute() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_member_type_meta_route.gd",
                """
                        class_name BodyInsnDynamicMemberTypeMetaRoute
                        extends RefCounted
                        
                        func zero() -> Vector3:
                            return Vector3.ZERO
                        """,
                Map.of("BodyInsnDynamicMemberTypeMetaRoute", "RuntimeBodyInsnDynamicMemberTypeMetaRoute"),
                true
        );
        var zeroContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicMemberTypeMetaRoute",
                "zero"
        );
        var memberLoad = requireSingleMemberLoadItem(zeroContext.requireFrontendCfgGraph(), "ZERO");
        var originalMember = prepared.context().requireAnalysisData().resolvedMembers().get(memberLoad.anchor());
        assertNotNull(originalMember);
        prepared.context().requireAnalysisData().resolvedMembers().put(
                memberLoad.anchor(),
                FrontendResolvedMember.dynamic(
                        originalMember.memberName(),
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.TYPE_META,
                        originalMember.ownerKind(),
                        originalMember.receiverType(),
                        originalMember.declarationSite(),
                        "synthetic type-meta dynamic member route"
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBodyInsnPass().run(prepared.context())
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("dynamic member load"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("instance receiver route"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("TYPE_META"), exception.getMessage())
        );
    }

    @Test
    void runFailsFastWhenDynamicMemberReadLosesReceiverValueId() throws Exception {
        var prepared = prepareContext(
                "body_insn_dynamic_member_missing_receiver_id.gd",
                """
                        class_name BodyInsnDynamicMemberMissingReceiverId
                        extends RefCounted
                        
                        func marker(host: Variant) -> Variant:
                            return host.marker
                        """,
                Map.of("BodyInsnDynamicMemberMissingReceiverId", "RuntimeBodyInsnDynamicMemberMissingReceiverId"),
                true
        );
        var originalContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnDynamicMemberMissingReceiverId",
                "marker"
        );
        var originalGraph = originalContext.requireFrontendCfgGraph();
        var originalMemberLoad = requireSingleMemberLoadItem(originalGraph, "marker");
        var entryNode = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                originalGraph.requireNode(originalGraph.entryNodeId())
        );
        var mutatedMemberLoad = new MemberLoadItem(
                originalMemberLoad.anchor(),
                originalMemberLoad.memberName(),
                null,
                originalMemberLoad.resultValueId()
        );
        var mutatedItems = entryNode.items().stream()
                .map(item -> item == originalMemberLoad ? mutatedMemberLoad : item)
                .toList();
        var mutatedNodes = new LinkedHashMap<>(originalGraph.nodes());
        mutatedNodes.put(
                entryNode.id(),
                new FrontendCfgGraph.SequenceNode(entryNode.id(), mutatedItems, entryNode.nextId())
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
        mutatedContext.publishFrontendCfgGraph(new FrontendCfgGraph(originalGraph.entryNodeId(), mutatedNodes));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendBodyLoweringSession(
                        mutatedContext,
                        new ClassRegistry(ExtensionApiLoader.loadDefault())
                ).run()
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("dynamic member load"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("missing a receiver value id"), exception.getMessage())
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
    void runLowersGlobalConstantIdentifierIntoInt64Literal() throws Exception {
        var prepared = prepareContext(
                "body_insn_global_constant.gd",
                """
                        class_name BodyInsnGlobalConstant
                        extends RefCounted
                        
                        func ping() -> int:
                            return GDCC_TEST_BIG_FLAG
                        """,
                Map.of("BodyInsnGlobalConstant", "RuntimeBodyInsnGlobalConstant"),
                true,
                new ClassRegistry(createGlobalConstantFixtureApi())
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnGlobalConstant",
                "ping"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var literalInsn = requireOnlyInstruction(pingContext.targetFunction(), LiteralIntInsn.class);
        var returnInsn = requireOnlyReturnInsn(pingContext.targetFunction());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(4_294_967_296L, literalInsn.value()),
                () -> assertEquals(literalInsn.resultId(), returnInsn.returnValueId())
        );
    }

    @Test
    void runLowersStringLiteralPropertyInitializerIntoNormalizedPayloadWhileAstKeepsRawLexeme() throws Exception {
        var prepared = prepareContext(
                "body_insn_property_string_literal.gd",
                """
                        class_name BodyInsnPropertyStringLiteral
                        extends RefCounted
                        
                        var ready_value: String = "line\\nbreak"
                        
                        func ping() -> String:
                            return ready_value
                        """,
                Map.of("BodyInsnPropertyStringLiteral", "RuntimeBodyInsnPropertyStringLiteral"),
                true
        );
        var initContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBodyInsnPropertyStringLiteral",
                "_field_init_ready_value"
        );
        var sourceLiteral = findLiteralExpression(prepared.module().units().getFirst().ast(), "\"line\\nbreak\"");

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var initFunction = initContext.targetFunction();
        var instructions = allInstructions(initFunction);
        var literalInsn = requireOnlyInstruction(initFunction, LiteralStringInsn.class);
        var returnInsn = requireOnlyReturnInsn(initFunction);
        var selfParameter = initFunction.getParameter(0);
        var selfParameterName = selfParameter == null ? null : selfParameter.name();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("\"line\\nbreak\"", sourceLiteral.sourceText()),
                () -> assertEquals("line\nbreak", literalInsn.value()),
                () -> assertNotEquals(sourceLiteral.sourceText(), literalInsn.value()),
                () -> assertEquals("seq_0", initFunction.getEntryBlockId()),
                () -> assertEquals(2, initFunction.getBasicBlockCount()),
                () -> assertEquals(1, initFunction.getParameters().size()),
                () -> assertInstanceOf(LirParameterDef.class, selfParameter),
                () -> assertEquals("self", selfParameterName),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class)),
                () -> assertEquals(literalInsn.resultId(), returnInsn.returnValueId())
        );
    }

    @Test
    void runLowersStringNameLiteralReturnIntoNormalizedPayloadWhileAstKeepsRawLexeme() throws Exception {
        var prepared = prepareContext(
                "body_insn_string_name_literal.gd",
                """
                        class_name BodyInsnStringNameLiteral
                        extends RefCounted
                        
                        func ping() -> StringName:
                            return &"Hero_Node"
                        """,
                Map.of("BodyInsnStringNameLiteral", "RuntimeBodyInsnStringNameLiteral"),
                true
        );
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnStringNameLiteral",
                "ping"
        );
        var sourceLiteral = findLiteralExpression(prepared.module().units().getFirst().ast(), "&\"Hero_Node\"");

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var pingFunction = pingContext.targetFunction();
        var instructions = allInstructions(pingFunction);
        var literalInsn = requireOnlyInstruction(pingFunction, LiteralStringNameInsn.class);
        var returnInsn = requireOnlyReturnInsn(pingFunction);
        var selfParameter = pingFunction.getParameter(0);
        var selfParameterName = selfParameter == null ? null : selfParameter.name();

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("&\"Hero_Node\"", sourceLiteral.sourceText()),
                () -> assertEquals("Hero_Node", literalInsn.value()),
                () -> assertNotEquals(sourceLiteral.sourceText(), literalInsn.value()),
                () -> assertEquals("seq_0", pingFunction.getEntryBlockId()),
                () -> assertEquals(2, pingFunction.getBasicBlockCount()),
                () -> assertEquals(1, pingFunction.getParameters().size()),
                () -> assertInstanceOf(LirParameterDef.class, selfParameter),
                () -> assertEquals("self", selfParameterName),
                () -> assertEquals(0, countInstructions(instructions, PackVariantInsn.class)),
                () -> assertEquals(0, countInstructions(instructions, UnpackVariantInsn.class)),
                () -> assertEquals(literalInsn.resultId(), returnInsn.returnValueId())
        );
    }

    @Test
    void runFailsFastWhenPublishedStringLiteralLexemeIsMalformed() throws Exception {
        var prepared = prepareContext(
                "body_insn_bad_string_lexeme.gd",
                """
                        class_name BodyInsnBadStringLexeme
                        extends RefCounted
                        
                        func ping() -> String:
                            return "ok"
                        """,
                Map.of("BodyInsnBadStringLexeme", "RuntimeBodyInsnBadStringLexeme"),
                true
        );
        var originalContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBodyInsnBadStringLexeme",
                "ping"
        );
        var originalGraph = originalContext.requireFrontendCfgGraph();
        var originalLiteralItem = requireSingleValueProducerItem(originalGraph, OpaqueExprValueItem.class);
        var entryNode = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                originalGraph.requireNode(originalGraph.entryNodeId())
        );
        var stopNode = assertInstanceOf(
                FrontendCfgGraph.StopNode.class,
                originalGraph.requireNode(entryNode.nextId())
        );
        var mutatedLiteralItem = new OpaqueExprValueItem(
                new LiteralExpression("string", "\"unterminated", SYNTHETIC_RANGE),
                originalLiteralItem.operandValueIds(),
                originalLiteralItem.resultValueId()
        );
        var mutatedItems = entryNode.items().stream()
                .map(item -> item == originalLiteralItem ? mutatedLiteralItem : item)
                .toList();
        var mutatedGraph = new FrontendCfgGraph(
                originalGraph.entryNodeId(),
                Map.of(
                        entryNode.id(),
                        new FrontendCfgGraph.SequenceNode(entryNode.id(), mutatedItems, entryNode.nextId()),
                        stopNode.id(),
                        stopNode
                )
        );
        var publishedType = originalContext.analysisData().expressionTypes().get(originalLiteralItem.expression());
        assertNotNull(publishedType);
        originalContext.analysisData().expressionTypes().put(mutatedLiteralItem.expression(), publishedType);
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
                IllegalArgumentException.class,
                () -> new FrontendBodyLoweringSession(
                        mutatedContext,
                        new ClassRegistry(ExtensionApiLoader.loadDefault())
                ).run()
        );

        assertEquals("Invalid GDScript string lexeme: \"unterminated", exception.getMessage());
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
    void runLowersSingletonReceiverPropertyInitializerIntoExecutableInitFunction() throws Exception {
        var prepared = prepareContext(
                "body_insn_property_singleton_receiver_call.gd",
                """
                        class_name BodyInsnPropertySingletonReceiverCall
                        extends RefCounted
                        
                        var frames: int = Engine.get_frames_drawn()
                        
                        func ping() -> int:
                            return frames
                        """,
                Map.of(
                        "BodyInsnPropertySingletonReceiverCall",
                        "RuntimeBodyInsnPropertySingletonReceiverCall"
                ),
                true
        );
        var initContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBodyInsnPropertySingletonReceiverCall",
                "_field_init_frames"
        );

        new FrontendLoweringBodyInsnPass().run(prepared.context());

        var initFunction = initContext.targetFunction();
        var instructions = allInstructions(initFunction);
        var receiverLoad = requireOnlyInstruction(initFunction, LoadStaticInsn.class);
        var methodCall = requireOnlyInstruction(initFunction, CallMethodInsn.class);
        var returnInsn = requireOnlyReturnInsn(initFunction);

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals("seq_0", initFunction.getEntryBlockId()),
                () -> assertTrue(initFunction.getBasicBlockCount() > 0),
                () -> assertEquals("@GlobalScope", receiverLoad.className()),
                () -> assertEquals("Engine", receiverLoad.staticName()),
                () -> assertEquals("get_frames_drawn", methodCall.methodName()),
                () -> assertEquals(receiverLoad.resultId(), methodCall.objectId()),
                () -> assertEquals(methodCall.resultId(), returnInsn.returnValueId()),
                () -> assertEquals(0, countInstructions(instructions, CallGlobalInsn.class))
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
        return prepareContext(
                fileName,
                source,
                topLevelCanonicalNameMap,
                buildCfg,
                new ClassRegistry(ExtensionApiLoader.loadDefault())
        );
    }

    private static @NotNull PreparedContext prepareContext(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap,
            boolean buildCfg,
            @NotNull ClassRegistry classRegistry
    ) {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(List.of(new SourceFixture(fileName, source)), topLevelCanonicalNameMap);
        var context = new FrontendLoweringContext(
                module,
                classRegistry,
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

    private static @NotNull ExtensionAPI createGlobalConstantFixtureApi() throws IOException {
        var defaultApi = ExtensionApiLoader.loadDefault();
        return new ExtensionAPI(
                defaultApi.header(),
                defaultApi.builtinClassSizes(),
                defaultApi.builtinClassMemberOffsets(),
                List.of(new ExtensionGlobalConstant("GDCC_TEST_BIG_FLAG", 4_294_967_296L, true)),
                defaultApi.globalEnums(),
                defaultApi.utilityFunctions(),
                defaultApi.builtinClasses(),
                defaultApi.classes(),
                defaultApi.singletons(),
                defaultApi.nativeStructures()
        );
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
            @NotNull LirFunctionDef function,
            @NotNull String blockId
    ) {
        var block = function.getBasicBlock(blockId);
        assertNotNull(block, () -> "Missing basic block " + blockId);
        return block;
    }

    private static @NotNull List<LirInstruction> allInstructions(
            @NotNull LirFunctionDef function
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

    private static @NotNull List<ConstructBuiltinInsn> constructBuiltinInsns(
            @NotNull List<LirInstruction> instructions
    ) {
        return instructions.stream()
                .filter(ConstructBuiltinInsn.class::isInstance)
                .map(ConstructBuiltinInsn.class::cast)
                .toList();
    }

    private static @NotNull List<String> literalNullResultIds(@NotNull List<LirInstruction> instructions) {
        return instructions.stream()
                .filter(LiteralNullInsn.class::isInstance)
                .map(LiteralNullInsn.class::cast)
                .map(LiteralNullInsn::resultId)
                .toList();
    }

    private static @NotNull List<String> literalNilResultIds(@NotNull List<LirInstruction> instructions) {
        return instructions.stream()
                .filter(LiteralNilInsn.class::isInstance)
                .map(LiteralNilInsn.class::cast)
                .map(LiteralNilInsn::resultId)
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

    private static void replaceParameterType(
            @NotNull FunctionLoweringContext context,
            @NotNull String parameterName,
            @NotNull GdType newType
    ) {
        var function = context.targetFunction();
        var parameters = List.copyOf(function.getParameters());
        function.clearParameters();
        var replaced = false;
        for (var parameterDef : parameters) {
            var parameter = assertInstanceOf(LirParameterDef.class, parameterDef);
            var type = parameter.name().equals(parameterName) ? newType : parameter.type();
            function.addParameter(new LirParameterDef(
                    parameter.name(),
                    type,
                    parameter.defaultValueFunc(),
                    function
            ));
            replaced = replaced || parameter.name().equals(parameterName);
        }
        assertTrue(replaced, () -> "Expected parameter to exist: " + parameterName);
    }

    private static @NotNull Map<String, String> stringNameValuesByResultId(@NotNull List<LirInstruction> instructions) {
        var values = new LinkedHashMap<String, String>();
        for (var instruction : instructions) {
            if (instruction instanceof LiteralStringNameInsn(var resultId, var value)) {
                values.put(resultId, value);
            }
        }
        return Map.copyOf(values);
    }

    private static @NotNull List<VariantGetNamedInsn> variantGetNamedInsnsForName(
            @NotNull List<LirInstruction> instructions,
            @NotNull String memberName
    ) {
        var stringNames = stringNameValuesByResultId(instructions);
        return instructions.stream()
                .filter(VariantGetNamedInsn.class::isInstance)
                .map(VariantGetNamedInsn.class::cast)
                .filter(instruction -> memberName.equals(stringNames.get(instruction.nameId())))
                .toList();
    }

    private static @NotNull List<VariantSetNamedInsn> variantSetNamedInsnsForName(
            @NotNull List<LirInstruction> instructions,
            @NotNull String memberName
    ) {
        var stringNames = stringNameValuesByResultId(instructions);
        return instructions.stream()
                .filter(VariantSetNamedInsn.class::isInstance)
                .map(VariantSetNamedInsn.class::cast)
                .filter(instruction -> memberName.equals(stringNames.get(instruction.nameId())))
                .toList();
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
            @NotNull LirFunctionDef function,
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
            @NotNull LirFunctionDef function
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

    private static @NotNull LiteralExpression findLiteralExpression(
            @NotNull Node root,
            @NotNull String sourceText
    ) {
        var matches = new ArrayList<LiteralExpression>();
        collectMatchingLiteralExpressions(root, sourceText, matches);
        return matches.stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("LiteralExpression not found: " + sourceText));
    }

    private static void collectMatchingLiteralExpressions(
            @NotNull Node node,
            @NotNull String sourceText,
            @NotNull List<LiteralExpression> matches
    ) {
        if (node instanceof LiteralExpression literalExpression
                && literalExpression.sourceText().equals(sourceText)) {
            matches.add(literalExpression);
        }
        for (var child : node.getChildren()) {
            collectMatchingLiteralExpressions(child, sourceText, matches);
        }
    }

    private static @NotNull String onlyVariableOperandId(@NotNull List<LirInstruction.Operand> operands) {
        assertEquals(1, operands.size(), "Expected exactly one variable argument");
        return assertInstanceOf(LirInstruction.VariableOperand.class, operands.getFirst()).id();
    }

    private static @NotNull GdType requireVariableType(@NotNull LirFunctionDef function, @NotNull String variableId) {
        var variable = function.getVariableById(variableId);
        assertNotNull(variable, () -> "Expected lowered variable to exist: " + variableId);
        return variable.type();
    }

    private static @NotNull GdType requireIntrinsicResultType(
            @NotNull LirFunctionDef function,
            @NotNull CallIntrinsicInsn insn
    ) {
        var resultId = insn.resultId();
        assertNotNull(resultId, "Expected intrinsic result slot");
        return requireVariableType(function, resultId);
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

    private static @NotNull AssignmentItem requireAssignmentItemForLeafMember(
            @NotNull FrontendCfgGraph graph,
            @NotNull String memberName
    ) {
        var matches = new ArrayList<AssignmentItem>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (item instanceof AssignmentItem assignmentItem
                        && memberName.equals(assignmentItem.writableRoutePayload().leaf().memberNameOrNull())) {
                    matches.add(assignmentItem);
                }
            }
        }
        assertEquals(1, matches.size(), () -> "Expected exactly one assignment leaf for " + memberName);
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

    private static @NotNull MemberLoadItem requireSingleMemberLoadItem(
            @NotNull FrontendCfgGraph graph,
            @NotNull String memberName
    ) {
        var matches = new ArrayList<MemberLoadItem>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (item instanceof MemberLoadItem memberLoadItem
                        && memberLoadItem.memberName().equals(memberName)) {
                    matches.add(memberLoadItem);
                }
            }
        }
        assertEquals(1, matches.size(), () -> "Expected exactly one MemberLoadItem for " + memberName);
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
