package gd.script.gdcc.frontend.lowering.cfg;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendSubscriptAccessSupport;
import gd.script.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import gd.script.gdcc.frontend.lowering.cfg.item.AwaitItem;
import gd.script.gdcc.frontend.lowering.cfg.item.BoolConstantItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CastItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
import gd.script.gdcc.frontend.lowering.cfg.item.DirectSlotAliasValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.FrontendWritableRoutePayload;
import gd.script.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CallableLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SignalLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.StandaloneCallableLoadItem;
import gd.script.gdcc.lir.insn.StandaloneCallableKind;
import gd.script.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SequenceItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SourceAnchorItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ValueOpItem;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendCfgRegion;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendElifRegion;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendForRegion;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendIfRegion;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendWhileRegion;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionGlobalConstant;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendCfgGraphBuilderTest {
    private static final Range SYNTHETIC_RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void buildExecutableBodyPublishesCastItemWithOperandFirst() throws Exception {
        // Shared semantic path: CFG item shape for CastExpression is stable and compile-ready.
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_cast_item.gd",
                """
                        class_name CfgBuilderCastItem
                        extends RefCounted
                        
                        func probe(value: int) -> float:
                            return value as float
                        """,
                "probe",
                Map.of("CfgBuilderCastItem", "RuntimeCfgBuilderCastItem")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var castItem = requireSingleSequenceItem(graph, CastItem.class);
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode("seq_0"));
        var castIndex = entryNode.items().indexOf(castItem);
        assertTrue(castIndex >= 0, "CastItem must appear in entry sequence");
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var castExpression = assertInstanceOf(CastExpression.class, returnStatement.value());

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertSame(castExpression, castItem.expression()),
                () -> assertEquals(List.of(castItem.operandValueId()), castItem.operandValueIds()),
                () -> assertEquals(castItem.resultValueId(), castItem.resultValueIdOrNull()),
                () -> assertNotEquals(castItem.operandValueId(), castItem.resultValueId()),
                // Operand value is produced before the cast item (source order).
                () -> assertTrue(
                        entryNode.items().subList(0, castIndex).stream().anyMatch(item ->
                                item instanceof ValueOpItem valueOp
                                        && castItem.operandValueId().equals(valueOp.resultValueIdOrNull())
                        ),
                        () -> "Expected operand producer before CastItem in " + entryNode.items()
                )
        );
    }

    @Test
    void buildExecutableBodyFailsFastForBreakWithoutLoopFrame() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_break_without_loop.gd",
                """
                        class_name CfgBuilderBreakWithoutLoop
                        extends RefCounted
                        
                        func ping() -> void:
                            break
                        """,
                "ping",
                Map.of("CfgBuilderBreakWithoutLoop", "RuntimeCfgBuilderBreakWithoutLoop")
        );

        var rootBlock = analyzed.function().body();
        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData())
        );

        assertAll(
                () -> assertTrue(analyzed.diagnostics().hasErrors()),
                () -> assertTrue(exception.getMessage().contains("loop frame"), exception.getMessage())
        );
    }

    @Test
    void buildExecutableBodyStopsScanningAfterReachableReturn() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_terminal_return.gd",
                """
                        class_name CfgBuilderTerminalReturn
                        extends RefCounted
                        
                        func ping(seed: int) -> int:
                            return seed
                            if seed:
                                pass
                        """,
                "ping",
                Map.of("CfgBuilderTerminalReturn", "RuntimeCfgBuilderTerminalReturn")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode("stop_1"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var returnValue = assertInstanceOf(OpaqueExprValueItem.class, entryNode.items().getFirst());
        var rootRegion = assertInstanceOf(FrontendCfgRegion.BlockRegion.class, build.regions().get(rootBlock));

        assertAll(
                () -> assertEquals(List.of("seq_0", "stop_1"), graph.nodeIds()),
                () -> assertEquals(1, entryNode.items().size()),
                () -> assertEquals("stop_1", entryNode.nextId()),
                () -> assertEquals("v0", stopNode.returnValueIdOrNull()),
                () -> assertEquals("seq_0", rootRegion.entryId()),
                () -> assertEquals(List.of(), returnValue.operandValueIds()),
                () -> assertEquals("v0", returnValue.resultValueId()),
                () -> assertEquals(returnStatement.value(), returnValue.expression())
        );
    }

    @Test
    void buildExecutableBodySkipsCommentStatementsInsideFunctionBodies() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_comment_statement.gd",
                """
                        class_name CfgBuilderCommentStatement
                        extends RefCounted
                        
                        func ping() -> void:
                            # leading
                            pass
                            # trailing
                        """,
                "ping",
                Map.of("CfgBuilderCommentStatement", "RuntimeCfgBuilderCommentStatement")
        );

        var rootBlock = analyzed.function().body();
        var passStatement = assertInstanceOf(PassStatement.class, rootBlock.statements().get(1));
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode("seq_0"));
        var sourceAnchor = assertInstanceOf(SourceAnchorItem.class, entryNode.items().getFirst());

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(3, rootBlock.statements().size()),
                () -> assertInstanceOf(CommentStatement.class, rootBlock.statements().getFirst()),
                () -> assertInstanceOf(CommentStatement.class, rootBlock.statements().getLast()),
                () -> assertEquals(List.of("seq_0", "stop_0"), graph.nodeIds()),
                () -> assertEquals(1, entryNode.items().size()),
                () -> assertSame(passStatement, sourceAnchor.statement())
        );
    }

    @Test
    void buildExecutableBodyRecursivelyExpandsNestedCallsSubscriptsAndMemberReads() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_nested_chain.gd",
                """
                        class_name CfgBuilderNested
                        extends RefCounted
                        
                        var payloads: Dictionary[int, CfgBuilderNested]
                        var value: int
                        
                        func helper(value: int) -> int:
                            return value + 1
                        
                        func build(seed: int) -> CfgBuilderNested:
                            return self
                        
                        func fetch(index: int) -> CfgBuilderNested:
                            return self
                        
                        func ping(seed: int) -> int:
                            return build(helper(seed)).payloads[helper(seed)].fetch(helper(seed)).value
                        """,
                "ping",
                Map.of("CfgBuilderNested", "RuntimeCfgBuilderNested")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode("stop_1"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var returnExpression = assertInstanceOf(AttributeExpression.class, returnStatement.value());
        var buildCall = assertInstanceOf(CallExpression.class, returnExpression.base());
        var buildHelperCall = assertInstanceOf(CallExpression.class, buildCall.arguments().getFirst());
        var buildSeed = assertInstanceOf(IdentifierExpression.class, buildHelperCall.arguments().getFirst());
        var payloadsStep = assertInstanceOf(AttributeSubscriptStep.class, returnExpression.steps().getFirst());
        var payloadsHelperCall = assertInstanceOf(CallExpression.class, payloadsStep.arguments().getFirst());
        var payloadsSeed = assertInstanceOf(IdentifierExpression.class, payloadsHelperCall.arguments().getFirst());
        var fetchStep = assertInstanceOf(AttributeCallStep.class, returnExpression.steps().get(1));
        var fetchHelperCall = assertInstanceOf(CallExpression.class, fetchStep.arguments().getFirst());
        var fetchSeed = assertInstanceOf(IdentifierExpression.class, fetchHelperCall.arguments().getFirst());
        var valueStep = assertInstanceOf(AttributePropertyStep.class, returnExpression.steps().get(2));

        var items = entryNode.items();
        var buildSeedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(0));
        var buildHelperValue = assertInstanceOf(CallItem.class, items.get(1));
        var buildCallValue = assertInstanceOf(CallItem.class, items.get(2));
        var payloadsSeedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(3));
        var payloadsHelperValue = assertInstanceOf(CallItem.class, items.get(4));
        var payloadsSubscriptValue = assertInstanceOf(SubscriptLoadItem.class, items.get(5));
        var fetchSeedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(6));
        var fetchHelperValue = assertInstanceOf(CallItem.class, items.get(7));
        var fetchCallValue = assertInstanceOf(CallItem.class, items.get(8));
        var valueRead = assertInstanceOf(MemberLoadItem.class, items.get(9));
        var fetchCallPayload = requireNotNull(
                fetchCallValue.writableRoutePayloadOrNull(),
                "attribute call should publish a writable receiver payload"
        );
        var fetchCommitStep = fetchCallPayload.reverseCommitSteps().getFirst();

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(10, items.size()),
                () -> assertEquals("v9", stopNode.returnValueIdOrNull()),
                () -> assertEquals(buildSeed, buildSeedValue.expression()),
                () -> assertEquals(List.of(), buildSeedValue.operandValueIds()),
                () -> assertEquals("v0", buildSeedValue.resultValueId()),
                () -> assertEquals(buildHelperCall, buildHelperValue.anchor()),
                () -> assertEquals("helper", buildHelperValue.callableName()),
                () -> assertEquals(List.of("v0"), buildHelperValue.operandValueIds()),
                () -> assertEquals("v1", buildHelperValue.resultValueId()),
                () -> assertEquals(buildCall, buildCallValue.anchor()),
                () -> assertEquals("build", buildCallValue.callableName()),
                () -> assertEquals(List.of("v1"), buildCallValue.operandValueIds()),
                () -> assertEquals("v2", buildCallValue.resultValueId()),
                () -> assertEquals(payloadsSeed, payloadsSeedValue.expression()),
                () -> assertEquals("v3", payloadsSeedValue.resultValueId()),
                () -> assertEquals(payloadsHelperCall, payloadsHelperValue.anchor()),
                () -> assertEquals("helper", payloadsHelperValue.callableName()),
                () -> assertEquals(List.of("v3"), payloadsHelperValue.operandValueIds()),
                () -> assertEquals("v4", payloadsHelperValue.resultValueId()),
                () -> assertEquals(payloadsStep, payloadsSubscriptValue.anchor()),
                () -> assertEquals("payloads", payloadsSubscriptValue.memberNameOrNull()),
                () -> assertEquals(List.of("v2", "v4"), payloadsSubscriptValue.operandValueIds()),
                () -> assertEquals("v5", payloadsSubscriptValue.resultValueId()),
                () -> assertEquals(fetchSeed, fetchSeedValue.expression()),
                () -> assertEquals("v6", fetchSeedValue.resultValueId()),
                () -> assertEquals(fetchHelperCall, fetchHelperValue.anchor()),
                () -> assertEquals(List.of("v6"), fetchHelperValue.operandValueIds()),
                () -> assertEquals("v7", fetchHelperValue.resultValueId()),
                () -> assertEquals(fetchStep, fetchCallValue.anchor()),
                () -> assertEquals("fetch", fetchCallValue.callableName()),
                () -> assertEquals(List.of("v5", "v7"), fetchCallValue.operandValueIds()),
                () -> assertEquals("v8", fetchCallValue.resultValueId()),
                () -> assertSame(fetchStep, fetchCallPayload.routeAnchor()),
                () -> assertEquals(FrontendWritableRoutePayload.RootKind.VALUE_ID, fetchCallPayload.root().kind()),
                () -> assertSame(buildCall, fetchCallPayload.root().anchor()),
                () -> assertEquals(buildCallValue.resultValueId(), fetchCallPayload.root().valueIdOrNull()),
                () -> assertEquals(FrontendWritableRoutePayload.LeafKind.SUBSCRIPT, fetchCallPayload.leaf().kind()),
                () -> assertSame(payloadsStep, fetchCallPayload.leaf().anchor()),
                () -> assertEquals(buildCallValue.resultValueId(), fetchCallPayload.leaf().containerValueIdOrNull()),
                () -> assertEquals(List.of(payloadsHelperValue.resultValueId()), fetchCallPayload.leaf().operandValueIds()),
                () -> assertEquals("payloads", fetchCallPayload.leaf().memberNameOrNull()),
                () -> assertEquals(
                        FrontendSubscriptAccessSupport.AccessKind.INDEXED,
                        fetchCallPayload.leaf().subscriptAccessKindOrNull()
                ),
                () -> assertEquals(1, fetchCallPayload.reverseCommitSteps().size()),
                () -> assertEquals(FrontendWritableRoutePayload.StepKind.SUBSCRIPT, fetchCommitStep.kind()),
                () -> assertSame(payloadsStep, fetchCommitStep.anchor()),
                () -> assertEquals(buildCallValue.resultValueId(), fetchCommitStep.containerValueIdOrNull()),
                () -> assertEquals(List.of(payloadsHelperValue.resultValueId()), fetchCommitStep.operandValueIds()),
                () -> assertEquals("payloads", fetchCommitStep.memberNameOrNull()),
                () -> assertEquals(
                        FrontendSubscriptAccessSupport.AccessKind.INDEXED,
                        fetchCommitStep.subscriptAccessKindOrNull()
                ),
                () -> assertEquals(valueStep, valueRead.anchor()),
                () -> assertEquals("value", valueRead.memberName()),
                () -> assertEquals(List.of("v8"), valueRead.operandValueIds()),
                () -> assertEquals("v9", valueRead.resultValueId())
        );
    }

    @Test
    void buildExecutableBodyPublishesDirectSlotWritableReceiverPayloadForInstanceCalls() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_direct_slot_receiver_call.gd",
                """
                        class_name CfgBuilderDirectSlotReceiverCall
                        extends RefCounted
                        
                        func ping(values: PackedInt32Array, seed: int) -> void:
                            values.push_back(seed)
                        """,
                "ping",
                Map.of(
                        "CfgBuilderDirectSlotReceiverCall",
                        "RuntimeCfgBuilderDirectSlotReceiverCall"
                )
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var statement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, statement.expression());
        var receiver = assertInstanceOf(IdentifierExpression.class, expression.base());
        var callStep = assertInstanceOf(AttributeCallStep.class, expression.steps().getFirst());
        var seed = assertInstanceOf(IdentifierExpression.class, callStep.arguments().getFirst());

        var items = entryNode.items();
        var receiverValue = assertInstanceOf(DirectSlotAliasValueItem.class, items.get(0));
        var seedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(1));
        var callValue = assertInstanceOf(CallItem.class, items.get(2));
        var callPayload = requireNotNull(
                callValue.writableRoutePayloadOrNull(),
                "instance call receiver should publish a writable payload"
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(3, items.size()),
                () -> assertSame(receiver, receiverValue.expression()),
                () -> assertSame(seed, seedValue.expression()),
                () -> assertSame(callStep, callValue.anchor()),
                () -> assertEquals("push_back", callValue.callableName()),
                () -> assertEquals(receiverValue.resultValueId(), callValue.receiverValueIdOrNull()),
                () -> assertEquals(List.of(seedValue.resultValueId()), callValue.argumentValueIds()),
                () -> assertNotNull(callValue.resultValueIdOrNull()),
                () -> assertTrue(callValue.hasStandaloneMaterializationSlot()),
                () -> assertSame(callStep, callPayload.routeAnchor()),
                () -> assertEquals(FrontendWritableRoutePayload.RootKind.DIRECT_SLOT, callPayload.root().kind()),
                () -> assertSame(receiver, callPayload.root().anchor()),
                () -> assertNull(callPayload.root().valueIdOrNull()),
                () -> assertEquals(FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT, callPayload.leaf().kind()),
                () -> assertSame(receiver, callPayload.leaf().anchor()),
                () -> assertNull(callPayload.leaf().containerValueIdOrNull()),
                () -> assertEquals(List.of(), callPayload.leaf().operandValueIds()),
                () -> assertNull(callPayload.leaf().memberNameOrNull()),
                () -> assertNull(callPayload.leaf().subscriptAccessKindOrNull()),
                () -> assertTrue(callPayload.reverseCommitSteps().isEmpty())
        );
    }

    @Test
    void buildExecutableBodyPublishesDiscardedExactVoidReceiverCallWithoutResultSlot() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_exact_void_receiver_call.gd",
                """
                        class_name CfgBuilderExactVoidReceiverCall
                        extends RefCounted
                        
                        func ping(values: Array[int], seed: int) -> void:
                            values.push_back(seed)
                        """,
                "ping",
                Map.of(
                        "CfgBuilderExactVoidReceiverCall",
                        "RuntimeCfgBuilderExactVoidReceiverCall"
                )
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var statement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, statement.expression());
        var receiver = assertInstanceOf(IdentifierExpression.class, expression.base());
        var callStep = assertInstanceOf(AttributeCallStep.class, expression.steps().getFirst());
        var seed = assertInstanceOf(IdentifierExpression.class, callStep.arguments().getFirst());

        var items = entryNode.items();
        var receiverValue = assertInstanceOf(DirectSlotAliasValueItem.class, items.get(0));
        var seedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(1));
        var callValue = assertInstanceOf(CallItem.class, items.get(2));
        var callPayload = requireNotNull(
                callValue.writableRoutePayloadOrNull(),
                "exact void receiver call should still publish a writable payload"
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(3, items.size()),
                () -> assertSame(receiver, receiverValue.expression()),
                () -> assertSame(seed, seedValue.expression()),
                () -> assertSame(callStep, callValue.anchor()),
                () -> assertEquals("push_back", callValue.callableName()),
                () -> assertEquals(receiverValue.resultValueId(), callValue.receiverValueIdOrNull()),
                () -> assertEquals(List.of(seedValue.resultValueId()), callValue.argumentValueIds()),
                () -> assertNull(callValue.resultValueIdOrNull()),
                () -> assertFalse(callValue.hasStandaloneMaterializationSlot()),
                () -> assertEquals(FrontendWritableRoutePayload.RootKind.DIRECT_SLOT, callPayload.root().kind()),
                () -> assertEquals(FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT, callPayload.leaf().kind()),
                () -> assertTrue(callPayload.reverseCommitSteps().isEmpty())
        );
    }

    @Test
    void buildExecutableBodyPublishesDirectSlotAliasValueForDynamicReceiverCalls() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_dynamic_direct_slot_receiver_call.gd",
                """
                        class_name CfgBuilderDynamicDirectSlotReceiverCall
                        extends RefCounted
                        
                        func ping(values, seed: int) -> void:
                            values.push_back(seed)
                        """,
                "ping",
                Map.of(
                        "CfgBuilderDynamicDirectSlotReceiverCall",
                        "RuntimeCfgBuilderDynamicDirectSlotReceiverCall"
                )
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var statement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, statement.expression());
        var receiver = assertInstanceOf(IdentifierExpression.class, expression.base());
        var callStep = assertInstanceOf(AttributeCallStep.class, expression.steps().getFirst());
        var seed = assertInstanceOf(IdentifierExpression.class, callStep.arguments().getFirst());

        var items = entryNode.items();
        var receiverValue = assertInstanceOf(DirectSlotAliasValueItem.class, items.get(0));
        var seedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(1));
        var callValue = assertInstanceOf(CallItem.class, items.get(2));
        var callPayload = requireNotNull(
                callValue.writableRoutePayloadOrNull(),
                "dynamic receiver call should still publish a writable payload"
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(3, items.size()),
                () -> assertSame(receiver, receiverValue.expression()),
                () -> assertSame(seed, seedValue.expression()),
                () -> assertSame(callStep, callValue.anchor()),
                () -> assertEquals("push_back", callValue.callableName()),
                () -> assertEquals(receiverValue.resultValueId(), callValue.receiverValueIdOrNull()),
                () -> assertEquals(List.of(seedValue.resultValueId()), callValue.argumentValueIds()),
                () -> assertNotNull(callValue.resultValueIdOrNull()),
                () -> assertTrue(callValue.hasStandaloneMaterializationSlot()),
                () -> assertEquals(FrontendWritableRoutePayload.RootKind.DIRECT_SLOT, callPayload.root().kind()),
                () -> assertEquals(FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT, callPayload.leaf().kind()),
                () -> assertTrue(callPayload.reverseCommitSteps().isEmpty())
        );
    }

    @Test
    void buildExecutableBodyAllowsDynamicInstanceMemberReads() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_dynamic_instance_member_read.gd",
                """
                        class_name CfgBuilderDynamicInstanceMemberRead
                        extends RefCounted
                        
                        func marker(host):
                            return host.marker
                        """,
                "marker",
                Map.of("CfgBuilderDynamicInstanceMemberRead", "RuntimeCfgBuilderDynamicInstanceMemberRead")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, returnStatement.value());
        var receiver = assertInstanceOf(IdentifierExpression.class, expression.base());
        var markerStep = assertInstanceOf(AttributePropertyStep.class, expression.steps().getFirst());

        var items = entryNode.items();
        var receiverValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(0));
        var markerLoad = assertInstanceOf(MemberLoadItem.class, items.get(1));
        var publishedMember = Objects.requireNonNull(analyzed.analysisData().resolvedMembers().get(markerStep));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, publishedMember.receiverKind()),
                () -> assertEquals(GdVariantType.VARIANT, publishedMember.receiverType()),
                () -> assertEquals(2, items.size()),
                () -> assertSame(receiver, receiverValue.expression()),
                () -> assertSame(markerStep, markerLoad.anchor()),
                () -> assertEquals("marker", markerLoad.memberName()),
                () -> assertEquals(List.of(receiverValue.resultValueId()), markerLoad.operandValueIds())
        );
    }

    @Test
    void buildExecutableBodyFailsFastWhenDynamicMemberUsesTypeMetaRoute() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_dynamic_member_type_meta_route.gd",
                """
                        class_name CfgBuilderDynamicMemberTypeMetaRoute
                        extends RefCounted
                        
                        func zero() -> Vector3:
                            return Vector3.ZERO
                        """,
                "zero",
                Map.of("CfgBuilderDynamicMemberTypeMetaRoute", "RuntimeCfgBuilderDynamicMemberTypeMetaRoute")
        );
        var returnStatement = assertInstanceOf(ReturnStatement.class, analyzed.function().body().statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, returnStatement.value());
        var zeroStep = assertInstanceOf(AttributePropertyStep.class, expression.steps().getFirst());
        var originalMember = analyzed.analysisData().resolvedMembers().get(zeroStep);
        assertNotNull(originalMember);
        analyzed.analysisData().resolvedMembers().put(
                zeroStep,
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
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("publication contract drift"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("DYNAMIC member"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("TYPE_META"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("CFG member lowering"), exception.getMessage())
        );
    }

    @Test
    void buildExecutableBodyPublishesDiscardedGlobalVoidCallWithoutResultSlot() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_discarded_global_void_call.gd",
                """
                        class_name CfgBuilderDiscardedGlobalVoidCall
                        extends RefCounted
                        
                        func ping(seed: int) -> void:
                            print(seed)
                        """,
                "ping",
                Map.of(
                        "CfgBuilderDiscardedGlobalVoidCall",
                        "RuntimeCfgBuilderDiscardedGlobalVoidCall"
                )
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var statement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var callExpression = assertInstanceOf(CallExpression.class, statement.expression());
        var seed = assertInstanceOf(IdentifierExpression.class, callExpression.arguments().getFirst());

        var items = entryNode.items();
        var seedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(0));
        var callValue = assertInstanceOf(CallItem.class, items.get(1));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(2, items.size()),
                () -> assertSame(seed, seedValue.expression()),
                () -> assertSame(callExpression, callValue.anchor()),
                () -> assertEquals("print", callValue.callableName()),
                () -> assertNull(callValue.receiverValueIdOrNull()),
                () -> assertEquals(List.of(seedValue.resultValueId()), callValue.argumentValueIds()),
                () -> assertNull(callValue.resultValueIdOrNull()),
                () -> assertFalse(callValue.hasStandaloneMaterializationSlot())
        );
    }

    @Test
    void buildExecutableBodyFailsFastWhenGlobalVoidCallLeaksIntoValueRequiredInitializer() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_value_required_global_void_call.gd",
                """
                        class_name CfgBuilderValueRequiredGlobalVoidCall
                        extends RefCounted
                        
                        func ping(seed: int) -> int:
                            var leaked = print(seed)
                            return seed
                        """,
                "ping",
                Map.of(
                        "CfgBuilderValueRequiredGlobalVoidCall",
                        "RuntimeCfgBuilderValueRequiredGlobalVoidCall"
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("Value-required"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("print"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("void"), exception.getMessage())
        );
    }

    @Test
    void buildExecutableBodyFailsFastWhenExactVoidCallLeaksIntoReturnValue() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_value_required_exact_void_call.gd",
                """
                        class_name CfgBuilderValueRequiredExactVoidCall
                        extends RefCounted
                        
                        func ping(values: Array[int], seed: int) -> int:
                            return values.push_back(seed)
                        """,
                "ping",
                Map.of(
                        "CfgBuilderValueRequiredExactVoidCall",
                        "RuntimeCfgBuilderValueRequiredExactVoidCall"
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("Value-required"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("push_back"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("void"), exception.getMessage())
        );
    }

    @Test
    void buildExecutableBodyKeepsObjectPropertyDynamicReceiverReadOnSnapshotButPublishesDirectOwnerWriteback() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_object_variant_property_call.gd",
                """
                        class_name CfgBuilderObjectVariantPropertyCall
                        extends RefCounted
                        
                        var payloads: Variant
                        
                        func ping(box: CfgBuilderObjectVariantPropertyCall, seed: int) -> void:
                            box.payloads.push_back(seed)
                        """,
                "ping",
                Map.of(
                        "CfgBuilderObjectVariantPropertyCall",
                        "RuntimeCfgBuilderObjectVariantPropertyCall"
                )
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var statement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, statement.expression());
        var box = assertInstanceOf(IdentifierExpression.class, expression.base());
        var propertyStep = assertInstanceOf(AttributePropertyStep.class, expression.steps().get(0));
        var callStep = assertInstanceOf(AttributeCallStep.class, expression.steps().get(1));
        var seed = assertInstanceOf(IdentifierExpression.class, callStep.arguments().getFirst());

        var items = entryNode.items();
        var receiverValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(0));
        var propertyValue = assertInstanceOf(MemberLoadItem.class, items.get(1));
        var seedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(2));
        var callValue = assertInstanceOf(CallItem.class, items.get(3));
        var callPayload = requireNotNull(
                callValue.writableRoutePayloadOrNull(),
                "object property dynamic call should publish a writable payload"
        );
        var promotedPropertyStep = callPayload.reverseCommitSteps().getFirst();

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(4, items.size()),
                () -> assertSame(box, receiverValue.expression()),
                () -> assertSame(propertyStep, propertyValue.anchor()),
                () -> assertEquals("payloads", propertyValue.memberName()),
                () -> assertEquals(List.of(receiverValue.resultValueId()), propertyValue.operandValueIds()),
                () -> assertSame(seed, seedValue.expression()),
                () -> assertSame(callStep, callValue.anchor()),
                () -> assertEquals("push_back", callValue.callableName()),
                () -> assertEquals(propertyValue.resultValueId(), callValue.receiverValueIdOrNull()),
                () -> assertEquals(List.of(seedValue.resultValueId()), callValue.argumentValueIds()),
                () -> assertEquals(FrontendWritableRoutePayload.RootKind.DIRECT_SLOT, callPayload.root().kind()),
                () -> assertSame(box, callPayload.root().anchor()),
                () -> assertNull(callPayload.root().valueIdOrNull()),
                () -> assertEquals(FrontendWritableRoutePayload.LeafKind.PROPERTY, callPayload.leaf().kind()),
                () -> assertSame(propertyStep, callPayload.leaf().anchor()),
                () -> assertNull(callPayload.leaf().containerValueIdOrNull()),
                () -> assertEquals(List.of(), callPayload.leaf().operandValueIds()),
                () -> assertEquals("payloads", callPayload.leaf().memberNameOrNull()),
                () -> assertNull(callPayload.leaf().subscriptAccessKindOrNull()),
                () -> assertEquals(1, callPayload.reverseCommitSteps().size()),
                () -> assertEquals(FrontendWritableRoutePayload.StepKind.PROPERTY, promotedPropertyStep.kind()),
                () -> assertSame(propertyStep, promotedPropertyStep.anchor()),
                () -> assertNull(promotedPropertyStep.containerValueIdOrNull()),
                () -> assertEquals(List.of(), promotedPropertyStep.operandValueIds()),
                () -> assertEquals("payloads", promotedPropertyStep.memberNameOrNull()),
                () -> assertNull(promotedPropertyStep.subscriptAccessKindOrNull())
        );
    }

    @Test
    void buildExecutableBodyPublishesDirectSlotAliasValueForExplicitSelfMutatingReceiverCalls() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_self_receiver_call.gd",
                """
                        class_name CfgBuilderSelfReceiverCall
                        extends RefCounted
                        
                        func touch(seed: int) -> void:
                            pass
                        
                        func ping(seed: int) -> void:
                            self.touch(seed)
                        """,
                "ping",
                Map.of("CfgBuilderSelfReceiverCall", "RuntimeCfgBuilderSelfReceiverCall")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var statement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, statement.expression());
        var receiver = assertInstanceOf(SelfExpression.class, expression.base());
        var callStep = assertInstanceOf(AttributeCallStep.class, expression.steps().getFirst());
        var seed = assertInstanceOf(IdentifierExpression.class, callStep.arguments().getFirst());
        var items = entryNode.items();
        var receiverValue = assertInstanceOf(DirectSlotAliasValueItem.class, items.get(0));
        var seedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(1));
        var callValue = assertInstanceOf(CallItem.class, items.get(2));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(3, items.size()),
                () -> assertSame(receiver, receiverValue.expression()),
                () -> assertSame(seed, seedValue.expression()),
                () -> assertSame(callStep, callValue.anchor()),
                () -> assertEquals(receiverValue.resultValueId(), callValue.receiverValueIdOrNull())
        );
    }

    @Test
    void buildExecutableBodyPublishesWritableRouteForRotatingCameraExplicitSelfPositionAssignment() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_rotating_camera_self_position.gd",
                """
                        class_name CfgBuilderRotatingCameraSelfPosition
                        extends Camera3D
                        
                        func _process(delta: float) -> void:
                            var vec = Vector3(1.0, 0.0, 0.0)
                            self.position = vec
                        """,
                "_process",
                Map.of("CfgBuilderRotatingCameraSelfPosition", "RuntimeCfgBuilderRotatingCameraSelfPosition")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var assignmentStatement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().get(1));
        var assignmentExpression = assertInstanceOf(AssignmentExpression.class, assignmentStatement.expression());
        var targetAttribute = assertInstanceOf(AttributeExpression.class, assignmentExpression.left());
        var explicitSelf = assertInstanceOf(SelfExpression.class, targetAttribute.base());
        var positionStep = assertInstanceOf(AttributePropertyStep.class, targetAttribute.steps().getFirst());
        var assignmentCommit = requireSingleSequenceItem(graph, AssignmentItem.class);
        var assignmentPayload = requireNotNull(
                assignmentCommit.writableRoutePayload(),
                "explicit self.position assignment should publish a frozen writable route"
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertSame(assignmentExpression, assignmentCommit.assignment()),
                () -> assertEquals(1, assignmentCommit.targetOperandValueIds().size()),
                () -> assertSame(assignmentExpression, assignmentPayload.routeAnchor()),
                () -> assertEquals(FrontendWritableRoutePayload.RootKind.DIRECT_SLOT, assignmentPayload.root().kind()),
                () -> assertSame(explicitSelf, assignmentPayload.root().anchor()),
                () -> assertNull(assignmentPayload.root().valueIdOrNull()),
                () -> assertEquals(FrontendWritableRoutePayload.LeafKind.PROPERTY, assignmentPayload.leaf().kind()),
                () -> assertSame(positionStep, assignmentPayload.leaf().anchor()),
                () -> assertNull(assignmentPayload.leaf().containerValueIdOrNull()),
                () -> assertEquals(List.of(), assignmentPayload.leaf().operandValueIds()),
                () -> assertEquals("position", assignmentPayload.leaf().memberNameOrNull()),
                () -> assertNull(assignmentPayload.leaf().subscriptAccessKindOrNull()),
                () -> assertEquals(List.of(), assignmentPayload.reverseCommitSteps())
        );
    }

    @Test
    void buildExecutableBodyKeepsIdentifierAliasForCompositeNoRebindingArguments() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_identifier_receiver_binary_argument.gd",
                """
                        class_name CfgBuilderIdentifierReceiverBinaryArgument
                        extends RefCounted
                        
                        func ping(values: PackedInt32Array, seed: int, offset: int) -> void:
                            values.push_back(seed + offset)
                        """,
                "ping",
                Map.of(
                        "CfgBuilderIdentifierReceiverBinaryArgument",
                        "RuntimeCfgBuilderIdentifierReceiverBinaryArgument"
                )
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var statement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, statement.expression());
        var receiver = assertInstanceOf(IdentifierExpression.class, expression.base());
        var callStep = assertInstanceOf(AttributeCallStep.class, expression.steps().getFirst());
        var binaryArgument = assertInstanceOf(BinaryExpression.class, callStep.arguments().getFirst());
        var seed = assertInstanceOf(IdentifierExpression.class, binaryArgument.left());
        var offset = assertInstanceOf(IdentifierExpression.class, binaryArgument.right());
        var items = entryNode.items();
        var receiverValue = assertInstanceOf(DirectSlotAliasValueItem.class, items.get(0));
        var seedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(1));
        var offsetValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(2));
        var binaryValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(3));
        var callValue = assertInstanceOf(CallItem.class, items.get(4));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(5, items.size()),
                () -> assertSame(receiver, receiverValue.expression()),
                () -> assertSame(seed, seedValue.expression()),
                () -> assertSame(offset, offsetValue.expression()),
                () -> assertSame(binaryArgument, binaryValue.expression()),
                () -> assertEquals(List.of(seedValue.resultValueId(), offsetValue.resultValueId()), binaryValue.operandValueIds()),
                () -> assertSame(callStep, callValue.anchor()),
                () -> assertEquals(receiverValue.resultValueId(), callValue.receiverValueIdOrNull()),
                () -> assertEquals(List.of(binaryValue.resultValueId()), callValue.argumentValueIds())
        );
    }

    @Test
    void buildExecutableBodyKeepsIdentifierReceiverOnSnapshotSurfaceWhenArgumentContainsNestedCall() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_identifier_receiver_nested_call.gd",
                """
                        class_name CfgBuilderIdentifierReceiverNestedCall
                        extends RefCounted
                        
                        func helper(value: int) -> int:
                            return value + 1
                        
                        func ping(values: PackedInt32Array, seed: int) -> void:
                            values.push_back(helper(seed))
                        """,
                "ping",
                Map.of(
                        "CfgBuilderIdentifierReceiverNestedCall",
                        "RuntimeCfgBuilderIdentifierReceiverNestedCall"
                )
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var statement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, statement.expression());
        var receiver = assertInstanceOf(IdentifierExpression.class, expression.base());
        var callStep = assertInstanceOf(AttributeCallStep.class, expression.steps().getFirst());
        var helperCallExpression = assertInstanceOf(CallExpression.class, callStep.arguments().getFirst());
        var seed = assertInstanceOf(IdentifierExpression.class, helperCallExpression.arguments().getFirst());
        var items = entryNode.items();
        var receiverValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(0));
        var seedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(1));
        var helperCallValue = assertInstanceOf(CallItem.class, items.get(2));
        var outerCallValue = assertInstanceOf(CallItem.class, items.get(3));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(4, items.size()),
                () -> assertSame(receiver, receiverValue.expression()),
                () -> assertSame(seed, seedValue.expression()),
                () -> assertSame(helperCallExpression, helperCallValue.anchor()),
                () -> assertEquals(List.of(seedValue.resultValueId()), helperCallValue.argumentValueIds()),
                () -> assertSame(callStep, outerCallValue.anchor()),
                () -> assertEquals("push_back", outerCallValue.callableName()),
                () -> assertEquals(receiverValue.resultValueId(), outerCallValue.receiverValueIdOrNull()),
                () -> assertEquals(List.of(helperCallValue.resultValueId()), outerCallValue.argumentValueIds())
        );
    }

    @Test
    void buildExecutableBodyStillPublishesSelfAliasWhenArgumentContainsNestedCall() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_self_receiver_nested_call.gd",
                """
                        class_name CfgBuilderSelfReceiverNestedCall
                        extends RefCounted
                        
                        func helper(value: int) -> int:
                            return value + 1
                        
                        func touch(value: int) -> void:
                            pass
                        
                        func ping(seed: int) -> void:
                            self.touch(helper(seed))
                        """,
                "ping",
                Map.of("CfgBuilderSelfReceiverNestedCall", "RuntimeCfgBuilderSelfReceiverNestedCall")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var statement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, statement.expression());
        var receiver = assertInstanceOf(SelfExpression.class, expression.base());
        var callStep = assertInstanceOf(AttributeCallStep.class, expression.steps().getFirst());
        var helperCallExpression = assertInstanceOf(CallExpression.class, callStep.arguments().getFirst());
        var seed = assertInstanceOf(IdentifierExpression.class, helperCallExpression.arguments().getFirst());
        var items = entryNode.items();
        var receiverValue = assertInstanceOf(DirectSlotAliasValueItem.class, items.get(0));
        var seedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(1));
        var helperCallValue = assertInstanceOf(CallItem.class, items.get(2));
        var outerCallValue = assertInstanceOf(CallItem.class, items.get(3));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(4, items.size()),
                () -> assertSame(receiver, receiverValue.expression()),
                () -> assertSame(seed, seedValue.expression()),
                () -> assertSame(helperCallExpression, helperCallValue.anchor()),
                () -> assertSame(callStep, outerCallValue.anchor()),
                () -> assertEquals(receiverValue.resultValueId(), outerCallValue.receiverValueIdOrNull()),
                () -> assertEquals(List.of(helperCallValue.resultValueId()), outerCallValue.argumentValueIds())
        );
    }

    @Test
    void buildExecutableBodyKeepsOrdinaryIdentifierReadOnOpaqueValueSurface() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_identifier_read_surface.gd",
                """
                        class_name CfgBuilderIdentifierReadSurface
                        extends RefCounted
                        
                        func ping(values: PackedInt32Array) -> PackedInt32Array:
                            return values
                        """,
                "ping",
                Map.of(
                        "CfgBuilderIdentifierReadSurface",
                        "RuntimeCfgBuilderIdentifierReadSurface"
                )
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, build.graph().requireNode("stop_1"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var receiver = assertInstanceOf(IdentifierExpression.class, returnStatement.value());
        var valueItem = assertInstanceOf(OpaqueExprValueItem.class, entryNode.items().getFirst());

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(1, entryNode.items().size()),
                () -> assertSame(receiver, valueItem.expression()),
                () -> assertEquals(List.of(), valueItem.operandValueIds()),
                () -> assertEquals(valueItem.resultValueId(), stopNode.returnValueIdOrNull()),
                () -> assertTrue(entryNode.items().stream().noneMatch(DirectSlotAliasValueItem.class::isInstance))
        );
    }

    @Test
    void buildExecutableBodyKeepsBareSignalIdentifierOnOpaqueValueSurfaceWithoutWritableRoute() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_bare_signal_read.gd",
                """
                        class_name CfgBuilderBareSignalRead
                        extends Node
                        
                        signal pinged
                        
                        func copy_signal() -> Signal:
                            return pinged
                        """,
                "copy_signal",
                Map.of("CfgBuilderBareSignalRead", "RuntimeCfgBuilderBareSignalRead")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, build.graph().requireNode("stop_1"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var receiver = assertInstanceOf(IdentifierExpression.class, returnStatement.value());
        var valueItem = assertInstanceOf(OpaqueExprValueItem.class, entryNode.items().getFirst());

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertEquals(1, entryNode.items().size()),
                () -> assertSame(receiver, valueItem.expression()),
                () -> assertEquals(valueItem.resultValueId(), stopNode.returnValueIdOrNull()),
                () -> assertTrue(entryNode.items().stream().noneMatch(MemberLoadItem.class::isInstance)),
                () -> assertTrue(entryNode.items().stream().noneMatch(SignalLoadItem.class::isInstance))
        );
    }

    @Test
    void buildExecutableBodyBuildsReceiverSignalReadsAsSignalLoadsWithoutWritableRoute() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_receiver_signal_read.gd",
                """
                        class_name CfgBuilderReceiverSignalRead
                        extends Node
                        
                        signal pinged
                        
                        func copy_from(other: CfgBuilderReceiverSignalRead) -> Signal:
                            return other.pinged
                        """,
                "copy_from",
                Map.of("CfgBuilderReceiverSignalRead", "RuntimeCfgBuilderReceiverSignalRead")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, build.graph().requireNode("stop_1"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var attribute = assertInstanceOf(AttributeExpression.class, returnStatement.value());
        var pingedStep = assertInstanceOf(AttributePropertyStep.class, attribute.steps().getFirst());
        var receiver = assertInstanceOf(OpaqueExprValueItem.class, entryNode.items().get(0));
        var signalLoad = assertInstanceOf(SignalLoadItem.class, entryNode.items().get(1));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertEquals(2, entryNode.items().size()),
                () -> assertEquals(attribute.base(), receiver.expression()),
                () -> assertEquals(pingedStep, signalLoad.anchor()),
                () -> assertEquals("pinged", signalLoad.signalName()),
                () -> assertEquals(List.of(receiver.resultValueId()), signalLoad.operandValueIds()),
                () -> assertEquals(receiver.resultValueId(), signalLoad.receiverValueId()),
                () -> assertEquals(signalLoad.resultValueId(), stopNode.returnValueIdOrNull()),
                () -> assertTrue(entryNode.items().stream().noneMatch(MemberLoadItem.class::isInstance))
        );
    }

    @Test
    void buildExecutableBodyBuildsInheritedEngineSignalReadsAsSignalLoadsWithoutWritableRoute() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_engine_signal_read.gd",
                """
                        class_name CfgBuilderEngineSignalRead
                        extends Node
                        
                        func copy_ready(n: Node) -> Signal:
                            return n.ready
                        """,
                "copy_ready",
                Map.of("CfgBuilderEngineSignalRead", "RuntimeCfgBuilderEngineSignalRead")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, build.graph().requireNode("stop_1"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var attribute = assertInstanceOf(AttributeExpression.class, returnStatement.value());
        var readyStep = assertInstanceOf(AttributePropertyStep.class, attribute.steps().getFirst());
        var receiver = assertInstanceOf(OpaqueExprValueItem.class, entryNode.items().get(0));
        var signalLoad = assertInstanceOf(SignalLoadItem.class, entryNode.items().get(1));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertEquals(2, entryNode.items().size()),
                () -> assertEquals(attribute.base(), receiver.expression()),
                () -> assertEquals(readyStep, signalLoad.anchor()),
                () -> assertEquals("ready", signalLoad.signalName()),
                () -> assertEquals(List.of(receiver.resultValueId()), signalLoad.operandValueIds()),
                () -> assertEquals(receiver.resultValueId(), signalLoad.receiverValueId()),
                () -> assertEquals(signalLoad.resultValueId(), stopNode.returnValueIdOrNull()),
                () -> assertTrue(entryNode.items().stream().noneMatch(MemberLoadItem.class::isInstance))
        );
    }

    @Test
    void buildExecutableBodyPublishesAwaitItemForSignalAwait() throws Exception {
        // Shared sema path: the await operand
        // builds as an ordinary signal load, then one AwaitItem marks the suspension point.
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_await_signal.gd",
                """
                        class_name CfgBuilderAwaitSignal
                        extends Node
                        
                        signal pinged
                        
                        func run():
                            var result = await pinged
                        """,
                "run",
                Map.of("CfgBuilderAwaitSignal", "RuntimeCfgBuilderAwaitSignal")
        );

        var build = new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        // A bare signal-identifier read stays an opaque value at CFG level; body lowering
        // materializes the Signal from the published binding (the existing bare-signal route).
        var operand = entryNode.items().stream()
                .filter(OpaqueExprValueItem.class::isInstance)
                .map(OpaqueExprValueItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing operand OpaqueExprValueItem in " + entryNode.items()));
        var awaitItem = entryNode.items().stream()
                .filter(AwaitItem.class::isInstance)
                .map(AwaitItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing AwaitItem in " + entryNode.items()));
        var declaration = entryNode.items().stream()
                .filter(LocalDeclarationItem.class::isInstance)
                .map(LocalDeclarationItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing LocalDeclarationItem in " + entryNode.items()));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertEquals(operand.resultValueId(), awaitItem.operandValueIdOrNull()),
                () -> assertEquals(List.of(operand.resultValueId()), awaitItem.operandValueIds()),
                () -> assertEquals(
                        declaration.initializerValueIdOrNull(),
                        awaitItem.resultValueId(),
                        "the variable-initializer preferred id flows through to the await result"
                ),
                () -> assertTrue(awaitItem.resultValueId().startsWith("result_"), awaitItem.resultValueId())
        );
    }

    @Test
    void buildExecutableBodyPublishesAwaitItemConsumingCoroutineCallResult() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_await_call.gd",
                """
                        class_name CfgBuilderAwaitCall
                        extends Node
                        
                        signal pinged
                        
                        func inner():
                            await pinged
                        
                        func run():
                            var result = await inner()
                        """,
                "run",
                Map.of("CfgBuilderAwaitCall", "RuntimeCfgBuilderAwaitCall")
        );

        var build = new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var callItem = entryNode.items().stream()
                .filter(CallItem.class::isInstance)
                .map(CallItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing CallItem in " + entryNode.items()));
        var awaitItem = entryNode.items().stream()
                .filter(AwaitItem.class::isInstance)
                .map(AwaitItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing AwaitItem in " + entryNode.items()));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertNotNull(callItem.resultValueIdOrNull(), "coroutine call must publish a result value"),
                () -> assertEquals(callItem.resultValueIdOrNull(), awaitItem.operandValueIdOrNull())
        );
    }

    @Test
    void buildExecutableBodyPublishesOperandlessAwaitItemForVoidRedundantCall() throws Exception {
        // `-> void` non-coroutine callee: the call runs on the no-result statement path and the
        // await carries no operand id; body lowering materializes nil as the resume value.
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_await_void_redundant.gd",
                """
                        class_name CfgBuilderAwaitVoidRedundant
                        extends Node
                        
                        func inner() -> void:
                            pass
                        
                        func run():
                            var result = await inner()
                        """,
                "run",
                Map.of("CfgBuilderAwaitVoidRedundant", "RuntimeCfgBuilderAwaitVoidRedundant")
        );

        var build = new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var callItem = entryNode.items().stream()
                .filter(CallItem.class::isInstance)
                .map(CallItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing CallItem in " + entryNode.items()));
        var awaitItem = entryNode.items().stream()
                .filter(AwaitItem.class::isInstance)
                .map(AwaitItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing AwaitItem in " + entryNode.items()));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertNull(callItem.resultValueIdOrNull(), "void call keeps the no-result shape"),
                () -> assertNull(awaitItem.operandValueIdOrNull()),
                () -> assertTrue(awaitItem.operandValueIds().isEmpty()),
                () -> assertNotNull(awaitItem.resultValueIdOrNull())
        );
    }

    @Test
    void buildExecutableBodyKeepsVoidCoroutineStatementCallOnValuePath() throws Exception {
        // Regression anchor: a statement-position resolved-void call normally takes the no-result
        // path, but a void coroutine call must still publish its state result value (the backend
        // coroutine ABI requires it; fire-and-forget detaches it through an INTERNAL destruct).
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_void_coroutine_statement.gd",
                """
                        class_name CfgBuilderVoidCoroutineStatement
                        extends Node
                        
                        signal pinged
                        
                        func fire():
                            await pinged
                        
                        func run():
                            fire()
                        """,
                "run",
                Map.of("CfgBuilderVoidCoroutineStatement", "RuntimeCfgBuilderVoidCoroutineStatement")
        );

        var build = new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var callItem = entryNode.items().stream()
                .filter(CallItem.class::isInstance)
                .map(CallItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing CallItem in " + entryNode.items()));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertNotNull(
                        callItem.resultValueIdOrNull(),
                        "void coroutine statement call must keep its coroutine-state result value"
                )
        );
    }

    @Test
    void buildExecutableBodyKeepsBareMethodIdentifierOnOpaqueValueSurfaceWithoutWritableRoute() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_bare_method_ref.gd",
                """
                        class_name CfgBuilderBareMethodRef
                        extends Node
                        
                        func _handler():
                            pass
                        
                        func copy_handler() -> Callable:
                            return _handler
                        """,
                "copy_handler",
                Map.of("CfgBuilderBareMethodRef", "RuntimeCfgBuilderBareMethodRef")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, build.graph().requireNode("stop_1"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var receiver = assertInstanceOf(IdentifierExpression.class, returnStatement.value());
        var valueItem = assertInstanceOf(OpaqueExprValueItem.class, entryNode.items().getFirst());

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertEquals(1, entryNode.items().size()),
                () -> assertSame(receiver, valueItem.expression()),
                () -> assertEquals(valueItem.resultValueId(), stopNode.returnValueIdOrNull()),
                () -> assertTrue(entryNode.items().stream().noneMatch(MemberLoadItem.class::isInstance)),
                () -> assertTrue(entryNode.items().stream().noneMatch(CallableLoadItem.class::isInstance))
        );
    }

    @Test
    void buildExecutableBodyBuildsReceiverMethodReferencesAsCallableLoadsWithoutWritableRoute() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_receiver_method_ref.gd",
                """
                        class_name CfgBuilderReceiverMethodRef
                        extends Node
                        
                        func _handler():
                            pass
                        
                        func copy_from(other: CfgBuilderReceiverMethodRef) -> Callable:
                            return other._handler
                        """,
                "copy_from",
                Map.of("CfgBuilderReceiverMethodRef", "RuntimeCfgBuilderReceiverMethodRef")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, build.graph().requireNode("stop_1"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var attribute = assertInstanceOf(AttributeExpression.class, returnStatement.value());
        var handlerStep = assertInstanceOf(AttributePropertyStep.class, attribute.steps().getFirst());
        var receiver = assertInstanceOf(OpaqueExprValueItem.class, entryNode.items().get(0));
        var callableLoad = assertInstanceOf(CallableLoadItem.class, entryNode.items().get(1));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertEquals(2, entryNode.items().size()),
                () -> assertEquals(attribute.base(), receiver.expression()),
                () -> assertEquals(handlerStep, callableLoad.anchor()),
                () -> assertEquals("_handler", callableLoad.methodName()),
                () -> assertEquals(List.of(receiver.resultValueId()), callableLoad.operandValueIds()),
                () -> assertEquals(receiver.resultValueId(), callableLoad.receiverValueId()),
                () -> assertEquals(callableLoad.resultValueId(), stopNode.returnValueIdOrNull()),
                () -> assertTrue(entryNode.items().stream().noneMatch(MemberLoadItem.class::isInstance))
        );
    }

    @Test
    void buildExecutableBodyBuildsBuiltinInstanceMethodReferencesAsCallableLoads() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_builtin_method_ref.gd",
                """
                        class_name CfgBuilderBuiltinMethodRef
                        extends Node
                        
                        func copy_abs(vec: Vector2) -> Callable:
                            return vec.abs
                        """,
                "copy_abs",
                Map.of("CfgBuilderBuiltinMethodRef", "RuntimeCfgBuilderBuiltinMethodRef")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, build.graph().requireNode("stop_1"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var attribute = assertInstanceOf(AttributeExpression.class, returnStatement.value());
        var absStep = assertInstanceOf(AttributePropertyStep.class, attribute.steps().getFirst());
        var receiver = assertInstanceOf(OpaqueExprValueItem.class, entryNode.items().get(0));
        var callableLoad = assertInstanceOf(CallableLoadItem.class, entryNode.items().get(1));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertEquals(2, entryNode.items().size()),
                () -> assertEquals(attribute.base(), receiver.expression()),
                () -> assertEquals(absStep, callableLoad.anchor()),
                () -> assertEquals("abs", callableLoad.methodName()),
                () -> assertEquals(List.of(receiver.resultValueId()), callableLoad.operandValueIds()),
                () -> assertEquals(callableLoad.resultValueId(), stopNode.returnValueIdOrNull()),
                () -> assertTrue(entryNode.items().stream().noneMatch(MemberLoadItem.class::isInstance))
        );
    }

    @Test
    void buildExecutableBodyBuildsQualifiedStaticMethodReferencesAsStandaloneCallableLoads() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_static_method_ref.gd",
                """
                        class_name CfgBuilderStaticMethodRef
                        extends Node
                        
                        static func make_static():
                            return 1
                        
                        func copy_static() -> Callable:
                            return CfgBuilderStaticMethodRef.make_static
                        """,
                "copy_static",
                Map.of("CfgBuilderStaticMethodRef", "RuntimeCfgBuilderStaticMethodRef")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, build.graph().requireNode("stop_1"));
        var standaloneLoad = assertInstanceOf(StandaloneCallableLoadItem.class, entryNode.items().getFirst());

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertEquals(1, entryNode.items().size()),
                () -> assertEquals("make_static", standaloneLoad.callableName()),
                () -> assertEquals("RuntimeCfgBuilderStaticMethodRef", standaloneLoad.ownerName()),
                () -> assertEquals(StandaloneCallableKind.STATIC_GDCC, standaloneLoad.kind()),
                () -> assertEquals(standaloneLoad.resultValueId(), stopNode.returnValueIdOrNull()),
                () -> assertTrue(entryNode.items().stream().noneMatch(MemberLoadItem.class::isInstance))
        );
    }

    @Test
    void buildExecutableBodyRejectsDictionaryClearAsMethodReference() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_dictionary_clear_ref.gd",
                """
                        class_name CfgBuilderDictionaryClearRef
                        extends Node
                        
                        func copy_clear(dict: Dictionary) -> Callable:
                            return dict.clear
                        """,
                "copy_clear",
                Map.of("CfgBuilderDictionaryClearRef", "RuntimeCfgBuilderDictionaryClearRef")
        );

        var ex = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(
                        analyzed.function().body(),
                        analyzed.analysisData()
                )
        );
        assertTrue(ex.getMessage().contains("clear"), ex.getMessage());
        assertTrue(ex.getMessage().contains("cannot lower as a property"), ex.getMessage());
    }

    @Test
    void buildExecutableBodyKeepsGlobalConstantIdentifierOnOpaqueValueSurface() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_global_constant_read_surface.gd",
                """
                        class_name CfgBuilderGlobalConstantReadSurface
                        extends RefCounted
                        
                        func ping() -> int:
                            return GDCC_TEST_BIG_FLAG
                        """,
                "ping",
                Map.of(
                        "CfgBuilderGlobalConstantReadSurface",
                        "RuntimeCfgBuilderGlobalConstantReadSurface"
                ),
                new ClassRegistry(createGlobalConstantFixtureApi())
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, build.graph().requireNode("stop_1"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var constant = assertInstanceOf(IdentifierExpression.class, returnStatement.value());
        var valueItem = assertInstanceOf(OpaqueExprValueItem.class, entryNode.items().getFirst());

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(1, entryNode.items().size()),
                () -> assertSame(constant, valueItem.expression()),
                () -> assertEquals(List.of(), valueItem.operandValueIds()),
                () -> assertEquals(valueItem.resultValueId(), stopNode.returnValueIdOrNull()),
                () -> assertTrue(entryNode.items().stream().noneMatch(DirectSlotAliasValueItem.class::isInstance))
        );
    }

    @Test
    void buildExecutableBodyKeepsOrdinarySelfReadOnOpaqueValueSurface() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_self_read_surface.gd",
                """
                        class_name CfgBuilderSelfReadSurface
                        extends RefCounted
                        
                        func ping() -> CfgBuilderSelfReadSurface:
                            return self
                        """,
                "ping",
                Map.of(
                        "CfgBuilderSelfReadSurface",
                        "RuntimeCfgBuilderSelfReadSurface"
                )
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, build.graph().requireNode("stop_1"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var selfExpression = assertInstanceOf(SelfExpression.class, returnStatement.value());
        var valueItem = assertInstanceOf(OpaqueExprValueItem.class, entryNode.items().getFirst());

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(1, entryNode.items().size()),
                () -> assertSame(selfExpression, valueItem.expression()),
                () -> assertEquals(List.of(), valueItem.operandValueIds()),
                () -> assertEquals(valueItem.resultValueId(), stopNode.returnValueIdOrNull()),
                () -> assertTrue(entryNode.items().stream().noneMatch(DirectSlotAliasValueItem.class::isInstance))
        );
    }

    @Test
    void buildExecutableBodyFailsFastWhenReceiverBindingIsCaptureAliasRoot() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_capture_receiver_alias.gd",
                """
                        class_name CfgBuilderCaptureReceiverAlias
                        extends RefCounted
                        
                        func ping(values: PackedInt32Array, seed: int) -> void:
                            values.push_back(seed)
                        """,
                "ping",
                Map.of(
                        "CfgBuilderCaptureReceiverAlias",
                        "RuntimeCfgBuilderCaptureReceiverAlias"
                )
        );

        var rootBlock = analyzed.function().body();
        var statement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, statement.expression());
        var receiver = assertInstanceOf(IdentifierExpression.class, expression.base());
        analyzed.analysisData().symbolBindings().compute(
                receiver,
                (k, originalBinding) -> new FrontendBinding(
                        "values",
                        FrontendBindingKind.CAPTURE,
                        originalBinding == null ? receiver : originalBinding.declarationSite()
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertTrue(exception.getMessage().contains("does not support CAPTURE binding"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("lambda/capture semantics"), exception.getMessage())
        );
    }

    @Test
    void buildExecutableBodyFailsFastWhenWritableIdentifierBindingPretendsToBeSelf() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_identifier_self_writable_route.gd",
                """
                        class_name CfgBuilderIdentifierSelfWritableRoute
                        extends RefCounted
                        
                        func ping(seed: int) -> int:
                            var value := seed
                            value = seed + 1
                            return value
                        """,
                "ping",
                Map.of(
                        "CfgBuilderIdentifierSelfWritableRoute",
                        "RuntimeCfgBuilderIdentifierSelfWritableRoute"
                )
        );

        var rootBlock = analyzed.function().body();
        var assignmentStatement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().get(1));
        var assignmentExpression = assertInstanceOf(AssignmentExpression.class, assignmentStatement.expression());
        var target = assertInstanceOf(IdentifierExpression.class, assignmentExpression.left());
        analyzed.analysisData().symbolBindings().compute(
                target,
                (k, originalBinding) -> new FrontendBinding(
                        "self",
                        FrontendBindingKind.SELF,
                        originalBinding == null ? target : originalBinding.declarationSite()
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertTrue(
                        exception.getMessage().contains(
                                "Identifier writable-route publication must use explicit SelfExpression"
                        ),
                        exception.getMessage()
                ),
                () -> assertTrue(exception.getMessage().contains("binding kind SELF"), exception.getMessage())
        );
    }

    @Test
    void buildExecutableBodyFailsFastWhenConstantIdentifierIsUsedAsAssignmentTarget() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_identifier_constant_writable_route.gd",
                """
                        class_name CfgBuilderIdentifierConstantWritableRoute
                        extends RefCounted
                        
                        func ping(seed: int) -> int:
                            var value := seed
                            value = seed + 1
                            return value
                        """,
                "ping",
                Map.of(
                        "CfgBuilderIdentifierConstantWritableRoute",
                        "RuntimeCfgBuilderIdentifierConstantWritableRoute"
                )
        );

        var rootBlock = analyzed.function().body();
        var assignmentStatement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().get(1));
        var assignmentExpression = assertInstanceOf(AssignmentExpression.class, assignmentStatement.expression());
        var target = assertInstanceOf(IdentifierExpression.class, assignmentExpression.left());
        analyzed.analysisData().symbolBindings().compute(
                target,
                (k, originalBinding) -> new FrontendBinding(
                        "value",
                        FrontendBindingKind.CONSTANT,
                        originalBinding == null ? target : originalBinding.declarationSite()
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertTrue(
                        exception.getMessage().contains("cannot publish a writable route"),
                        exception.getMessage()
                ),
                () -> assertTrue(exception.getMessage().contains("binding kind CONSTANT"), exception.getMessage())
        );
    }

    @Test
    void buildExecutableBodyFailsFastWhenReceiverIdentifierPretendsToBeSelfBinding() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_identifier_self_receiver_alias.gd",
                """
                        class_name CfgBuilderIdentifierSelfReceiverAlias
                        extends RefCounted
                        
                        func ping(values: PackedInt32Array, seed: int) -> void:
                            values.push_back(seed)
                        """,
                "ping",
                Map.of(
                        "CfgBuilderIdentifierSelfReceiverAlias",
                        "RuntimeCfgBuilderIdentifierSelfReceiverAlias"
                )
        );

        var rootBlock = analyzed.function().body();
        var statement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, statement.expression());
        var receiver = assertInstanceOf(IdentifierExpression.class, expression.base());
        analyzed.analysisData().symbolBindings().compute(
                receiver,
                (k, originalBinding) -> new FrontendBinding(
                        "self",
                        FrontendBindingKind.SELF,
                        originalBinding == null ? receiver : originalBinding.declarationSite()
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertTrue(exception.getMessage().contains("explicit SelfExpression"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("binding kind SELF"), exception.getMessage())
        );
    }

    @Test
    void classifyDirectSlotAliasArgumentsRequiresSnapshotForAssignmentExpression() throws Exception {
        var assignmentExpression = new AssignmentExpression("=", identifier("values"), identifier("other"), SYNTHETIC_RANGE);

        assertEquals(
                "REQUIRES_SNAPSHOT",
                invokeAliasArgumentSafety(List.of(assignmentExpression))
        );
    }

    @Test
    void buildExecutableBodyKeepsNonMutatingDirectSlotReceiverOnOpaqueValueSurface() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_non_mutating_receiver_call.gd",
                """
                        class_name CfgBuilderNonMutatingReceiverCall
                        extends RefCounted
                        
                        func ping(values: PackedInt32Array) -> int:
                            return values.size()
                        """,
                "ping",
                Map.of("CfgBuilderNonMutatingReceiverCall", "RuntimeCfgBuilderNonMutatingReceiverCall")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, returnStatement.value());
        var receiver = assertInstanceOf(IdentifierExpression.class, expression.base());
        var callStep = assertInstanceOf(AttributeCallStep.class, expression.steps().getFirst());
        var items = entryNode.items();
        var receiverValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(0));
        var callValue = assertInstanceOf(CallItem.class, items.get(1));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(2, items.size()),
                () -> assertSame(receiver, receiverValue.expression()),
                () -> assertSame(callStep, callValue.anchor()),
                () -> assertEquals(receiverValue.resultValueId(), callValue.receiverValueIdOrNull())
        );
    }

    @Test
    void buildExecutableBodyBuildsBuiltinInstancePropertyReadsAsLoweringReadyMemberLoads() throws Exception {
        var source = """
                class_name CfgBuilderBuiltinPropertyRead
                extends RefCounted
                
                func axis_x(vector: Vector3) -> float:
                    return vector.x
                
                func constructed_y() -> float:
                    return Vector3(1.0, 2.0, 3.0).y
                """;
        var axisAnalyzed = analyzeFunction(
                "cfg_builder_builtin_property_read.gd",
                source,
                "axis_x",
                Map.of("CfgBuilderBuiltinPropertyRead", "RuntimeCfgBuilderBuiltinPropertyRead")
        );
        var constructedAnalyzed = analyzeFunction(
                "cfg_builder_builtin_property_read.gd",
                source,
                "constructed_y",
                Map.of("CfgBuilderBuiltinPropertyRead", "RuntimeCfgBuilderBuiltinPropertyRead")
        );

        var axisBuild = new FrontendCfgGraphBuilder().buildExecutableBody(axisAnalyzed.function().body(), axisAnalyzed.analysisData());
        var constructedBuild = new FrontendCfgGraphBuilder().buildExecutableBody(
                constructedAnalyzed.function().body(),
                constructedAnalyzed.analysisData()
        );

        var axisGraph = axisBuild.graph();
        var constructedGraph = constructedBuild.graph();
        var axisEntry = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, axisGraph.requireNode("seq_0"));
        var axisStop = assertInstanceOf(FrontendCfgGraph.StopNode.class, axisGraph.requireNode("stop_1"));
        var constructedEntry = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, constructedGraph.requireNode("seq_0"));
        var constructedStop = assertInstanceOf(FrontendCfgGraph.StopNode.class, constructedGraph.requireNode("stop_1"));

        var axisReturn = assertInstanceOf(ReturnStatement.class, axisAnalyzed.function().body().statements().getFirst());
        var axisExpression = assertInstanceOf(AttributeExpression.class, axisReturn.value());
        var axisStep = assertInstanceOf(AttributePropertyStep.class, axisExpression.steps().getFirst());
        var axisReceiver = assertInstanceOf(OpaqueExprValueItem.class, axisEntry.items().get(0));
        var axisLoad = assertInstanceOf(MemberLoadItem.class, axisEntry.items().get(1));

        var constructedReturn = assertInstanceOf(ReturnStatement.class, constructedAnalyzed.function().body().statements().getFirst());
        var constructedExpression = assertInstanceOf(AttributeExpression.class, constructedReturn.value());
        var constructorCall = assertInstanceOf(CallExpression.class, constructedExpression.base());
        var yStep = assertInstanceOf(AttributePropertyStep.class, constructedExpression.steps().getFirst());
        var constructedItems = constructedEntry.items();
        var constructorCallItem = assertInstanceOf(CallItem.class, constructedItems.get(3));
        var constructedLoad = assertInstanceOf(MemberLoadItem.class, constructedItems.get(4));

        assertAll(
                () -> assertFalse(axisAnalyzed.diagnostics().hasErrors()),
                () -> assertFalse(constructedAnalyzed.diagnostics().hasErrors()),
                () -> assertEquals(2, axisEntry.items().size()),
                () -> assertEquals(axisExpression.base(), axisReceiver.expression()),
                () -> assertEquals(axisStep, axisLoad.anchor()),
                () -> assertEquals("x", axisLoad.memberName()),
                () -> assertEquals(List.of(axisReceiver.resultValueId()), axisLoad.operandValueIds()),
                () -> assertEquals(axisLoad.resultValueId(), axisStop.returnValueIdOrNull()),
                () -> assertEquals(5, constructedItems.size()),
                () -> assertEquals(constructorCall, constructorCallItem.anchor()),
                () -> assertEquals("Vector3", constructorCallItem.callableName()),
                () -> assertEquals(3, constructorCallItem.operandValueIds().size()),
                () -> assertEquals(yStep, constructedLoad.anchor()),
                () -> assertEquals("y", constructedLoad.memberName()),
                () -> assertEquals(List.of(constructorCallItem.resultValueId()), constructedLoad.operandValueIds()),
                () -> assertEquals(constructedLoad.resultValueId(), constructedStop.returnValueIdOrNull())
        );
    }

    @Test
    void buildExecutableBodyKeepsDeclarationAndAssignmentCommitsExplicit() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_assignment_commit.gd",
                """
                        class_name CfgBuilderAssignment
                        extends RefCounted
                        
                        var items: Dictionary[int, int]
                        
                        func helper(value: int) -> int:
                            return value + 1
                        
                        func produce(value: int) -> int:
                            return value * 2
                        
                        func ping(seed: int) -> int:
                            var local: int = helper(seed) + 1
                            items[helper(seed)] = produce(seed)
                            return local
                        """,
                "ping",
                Map.of("CfgBuilderAssignment", "RuntimeCfgBuilderAssignment")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode("stop_1"));

        var localDeclaration = assertInstanceOf(VariableDeclaration.class, rootBlock.statements().getFirst());
        var localInitializer = assertInstanceOf(BinaryExpression.class, localDeclaration.value());
        var localHelperCall = assertInstanceOf(CallExpression.class, localInitializer.left());
        var localSeed = assertInstanceOf(IdentifierExpression.class, localHelperCall.arguments().getFirst());
        var localLiteral = assertInstanceOf(LiteralExpression.class, localInitializer.right());

        var assignmentStatement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().get(1));
        var assignmentExpression = assertInstanceOf(AssignmentExpression.class, assignmentStatement.expression());
        var targetSubscript = assertInstanceOf(dev.superice.gdparser.frontend.ast.SubscriptExpression.class, assignmentExpression.left());
        var targetBase = assertInstanceOf(IdentifierExpression.class, targetSubscript.base());
        var indexHelperCall = assertInstanceOf(CallExpression.class, targetSubscript.arguments().getFirst());
        var indexSeed = assertInstanceOf(IdentifierExpression.class, indexHelperCall.arguments().getFirst());
        var rhsProduceCall = assertInstanceOf(CallExpression.class, assignmentExpression.right());
        var rhsSeed = assertInstanceOf(IdentifierExpression.class, rhsProduceCall.arguments().getFirst());

        var items = entryNode.items();
        var localSeedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(0));
        var localHelperValue = assertInstanceOf(CallItem.class, items.get(1));
        var localLiteralValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(2));
        var localInitValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(3));
        var localCommit = assertInstanceOf(LocalDeclarationItem.class, items.get(4));
        var targetBaseValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(5));
        var indexSeedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(6));
        var indexHelperValue = assertInstanceOf(CallItem.class, items.get(7));
        var rhsSeedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(8));
        var rhsProduceValue = assertInstanceOf(CallItem.class, items.get(9));
        var assignmentCommit = assertInstanceOf(AssignmentItem.class, items.get(10));
        var returnValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(11));
        var assignmentPayload = requireNotNull(
                assignmentCommit.writableRoutePayload(),
                "plain subscript assignment should publish a writable target payload"
        );
        var assignmentCommitStep = assignmentPayload.reverseCommitSteps().getFirst();

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(12, items.size()),
                () -> assertEquals(localSeed, localSeedValue.expression()),
                () -> assertEquals("v1", localSeedValue.resultValueId()),
                () -> assertEquals(localHelperCall, localHelperValue.anchor()),
                () -> assertEquals(List.of("v1"), localHelperValue.operandValueIds()),
                () -> assertEquals("v2", localHelperValue.resultValueId()),
                () -> assertEquals(localLiteral, localLiteralValue.expression()),
                () -> assertEquals("v3", localLiteralValue.resultValueId()),
                () -> assertEquals(localInitializer, localInitValue.expression()),
                () -> assertEquals(List.of("v2", "v3"), localInitValue.operandValueIds()),
                () -> assertEquals("local_0", localInitValue.resultValueId()),
                () -> assertEquals(localDeclaration, localCommit.declaration()),
                () -> assertEquals("local_0", localCommit.initializerValueIdOrNull()),
                () -> assertEquals(targetBase, targetBaseValue.expression()),
                () -> assertEquals("v4", targetBaseValue.resultValueId()),
                () -> assertEquals(indexSeed, indexSeedValue.expression()),
                () -> assertEquals("v5", indexSeedValue.resultValueId()),
                () -> assertEquals(indexHelperCall, indexHelperValue.anchor()),
                () -> assertEquals(List.of("v5"), indexHelperValue.operandValueIds()),
                () -> assertEquals("v6", indexHelperValue.resultValueId()),
                () -> assertEquals(rhsSeed, rhsSeedValue.expression()),
                () -> assertEquals("v7", rhsSeedValue.resultValueId()),
                () -> assertEquals(rhsProduceCall, rhsProduceValue.anchor()),
                () -> assertEquals("produce", rhsProduceValue.callableName()),
                () -> assertEquals(List.of("v7"), rhsProduceValue.operandValueIds()),
                () -> assertEquals("v8", rhsProduceValue.resultValueId()),
                () -> assertEquals(assignmentExpression, assignmentCommit.assignment()),
                () -> assertEquals(List.of("v4", "v6"), assignmentCommit.targetOperandValueIds()),
                () -> assertEquals("v8", assignmentCommit.rhsValueId()),
                () -> assertEquals(List.of("v4", "v6", "v8"), assignmentCommit.operandValueIds()),
                () -> assertSame(assignmentExpression, assignmentPayload.routeAnchor()),
                () -> assertEquals(FrontendWritableRoutePayload.RootKind.SELF_CONTEXT, assignmentPayload.root().kind()),
                () -> assertSame(targetBase, assignmentPayload.root().anchor()),
                () -> assertNull(assignmentPayload.root().valueIdOrNull()),
                () -> assertEquals(FrontendWritableRoutePayload.LeafKind.SUBSCRIPT, assignmentPayload.leaf().kind()),
                () -> assertSame(targetSubscript, assignmentPayload.leaf().anchor()),
                () -> assertEquals(targetBaseValue.resultValueId(), assignmentPayload.leaf().containerValueIdOrNull()),
                () -> assertEquals(List.of(indexHelperValue.resultValueId()), assignmentPayload.leaf().operandValueIds()),
                () -> assertNull(assignmentPayload.leaf().memberNameOrNull()),
                () -> assertEquals(
                        FrontendSubscriptAccessSupport.AccessKind.INDEXED,
                        assignmentPayload.leaf().subscriptAccessKindOrNull()
                ),
                () -> assertEquals(1, assignmentPayload.reverseCommitSteps().size()),
                () -> assertEquals(FrontendWritableRoutePayload.StepKind.PROPERTY, assignmentCommitStep.kind()),
                () -> assertSame(targetBase, assignmentCommitStep.anchor()),
                () -> assertNull(assignmentCommitStep.containerValueIdOrNull()),
                () -> assertEquals(List.of(), assignmentCommitStep.operandValueIds()),
                () -> assertEquals("items", assignmentCommitStep.memberNameOrNull()),
                () -> assertNull(assignmentCommitStep.subscriptAccessKindOrNull()),
                () -> assertEquals("v9", returnValue.resultValueId()),
                () -> assertEquals("v9", stopNode.returnValueIdOrNull())
        );
    }

    @Test
    void buildExecutableBodyPublishesCompoundIdentifierReadModifyWriteSequence() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_compound_identifier.gd",
                """
                        class_name CfgBuilderCompoundIdentifier
                        extends RefCounted
                        
                        func step(value: int) -> int:
                            return value + 1
                        
                        func ping(seed: int) -> int:
                            var count: int = seed
                            count += step(seed)
                            return count
                        """,
                "ping",
                Map.of("CfgBuilderCompoundIdentifier", "RuntimeCfgBuilderCompoundIdentifier")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode("stop_1"));
        var localDeclaration = assertInstanceOf(VariableDeclaration.class, rootBlock.statements().getFirst());
        var assignmentStatement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().get(1));
        var assignmentExpression = assertInstanceOf(AssignmentExpression.class, assignmentStatement.expression());
        var targetIdentifier = assertInstanceOf(IdentifierExpression.class, assignmentExpression.left());
        var rhsCall = assertInstanceOf(CallExpression.class, assignmentExpression.right());
        var rhsSeed = assertInstanceOf(IdentifierExpression.class, rhsCall.arguments().getFirst());

        var items = entryNode.items();
        var localInitializer = assertInstanceOf(OpaqueExprValueItem.class, items.get(0));
        var localCommit = assertInstanceOf(LocalDeclarationItem.class, items.get(1));
        var currentTargetValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(2));
        var rhsSeedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(3));
        var rhsCallValue = assertInstanceOf(CallItem.class, items.get(4));
        var compoundValue = assertInstanceOf(CompoundAssignmentBinaryOpItem.class, items.get(5));
        var assignmentCommit = assertInstanceOf(AssignmentItem.class, items.get(6));
        var returnValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(7));
        var assignmentPayload = requireNotNull(
                assignmentCommit.writableRoutePayload(),
                "compound identifier assignment should publish a direct-slot payload"
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(8, items.size()),
                () -> assertEquals(localDeclaration.value(), localInitializer.expression()),
                () -> assertEquals("count_0", localInitializer.resultValueId()),
                () -> assertEquals(localDeclaration, localCommit.declaration()),
                () -> assertEquals("count_0", localCommit.initializerValueIdOrNull()),
                () -> assertSame(targetIdentifier, currentTargetValue.expression()),
                () -> assertEquals(List.of(), currentTargetValue.operandValueIds()),
                () -> assertEquals("v1", currentTargetValue.resultValueId()),
                () -> assertSame(rhsSeed, rhsSeedValue.expression()),
                () -> assertEquals("v2", rhsSeedValue.resultValueId()),
                () -> assertSame(rhsCall, rhsCallValue.anchor()),
                () -> assertEquals(List.of("v2"), rhsCallValue.operandValueIds()),
                () -> assertEquals("v3", rhsCallValue.resultValueId()),
                () -> assertSame(assignmentExpression, compoundValue.anchor()),
                () -> assertEquals("+", compoundValue.binaryOperatorLexeme()),
                () -> assertEquals(List.of("v1", "v3"), compoundValue.operandValueIds()),
                () -> assertEquals("v4", compoundValue.resultValueId()),
                () -> assertSame(assignmentExpression, assignmentCommit.anchor()),
                () -> assertEquals(List.of(), assignmentCommit.targetOperandValueIds()),
                () -> assertEquals("v4", assignmentCommit.rhsValueId()),
                () -> assertEquals(List.of("v4"), assignmentCommit.operandValueIds()),
                () -> assertSame(assignmentExpression, assignmentPayload.routeAnchor()),
                () -> assertEquals(FrontendWritableRoutePayload.RootKind.DIRECT_SLOT, assignmentPayload.root().kind()),
                () -> assertSame(targetIdentifier, assignmentPayload.root().anchor()),
                () -> assertNull(assignmentPayload.root().valueIdOrNull()),
                () -> assertEquals(FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT, assignmentPayload.leaf().kind()),
                () -> assertSame(targetIdentifier, assignmentPayload.leaf().anchor()),
                () -> assertNull(assignmentPayload.leaf().containerValueIdOrNull()),
                () -> assertEquals(List.of(), assignmentPayload.leaf().operandValueIds()),
                () -> assertTrue(assignmentPayload.reverseCommitSteps().isEmpty()),
                () -> assertEquals("v5", returnValue.resultValueId()),
                () -> assertEquals("v5", stopNode.returnValueIdOrNull())
        );
    }

    @Test
    void buildExecutableBodyPublishesCompoundPropertyReadModifyWriteSequence() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_compound_property.gd",
                """
                        class_name CfgBuilderCompoundProperty
                        extends RefCounted
                        
                        var hp: int
                        
                        func heal(value: int) -> int:
                            return value + 1
                        
                        func ping(seed: int) -> void:
                            self.hp += heal(seed)
                        """,
                "ping",
                Map.of("CfgBuilderCompoundProperty", "RuntimeCfgBuilderCompoundProperty")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var assignmentStatement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var assignmentExpression = assertInstanceOf(AssignmentExpression.class, assignmentStatement.expression());
        var targetAttribute = assertInstanceOf(AttributeExpression.class, assignmentExpression.left());
        var propertyStep = assertInstanceOf(AttributePropertyStep.class, targetAttribute.steps().getLast());
        var rhsCall = assertInstanceOf(CallExpression.class, assignmentExpression.right());

        var items = entryNode.items();
        var receiverValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(0));
        var currentPropertyValue = assertInstanceOf(MemberLoadItem.class, items.get(1));
        var rhsSeedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(2));
        var rhsCallValue = assertInstanceOf(CallItem.class, items.get(3));
        var compoundValue = assertInstanceOf(CompoundAssignmentBinaryOpItem.class, items.get(4));
        var assignmentCommit = assertInstanceOf(AssignmentItem.class, items.get(5));
        var assignmentPayload = requireNotNull(
                assignmentCommit.writableRoutePayload(),
                "compound property assignment should publish a property payload"
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(6, items.size()),
                () -> assertSame(targetAttribute.base(), receiverValue.expression()),
                () -> assertEquals(1, items.stream()
                        .filter(OpaqueExprValueItem.class::isInstance)
                        .map(OpaqueExprValueItem.class::cast)
                        .filter(item -> item.expression() == targetAttribute.base())
                        .count()),
                () -> assertSame(propertyStep, currentPropertyValue.anchor()),
                () -> assertEquals("hp", currentPropertyValue.memberName()),
                () -> assertEquals(List.of(receiverValue.resultValueId()), currentPropertyValue.operandValueIds()),
                () -> assertSame(rhsCall, rhsCallValue.anchor()),
                () -> assertEquals(List.of(rhsSeedValue.resultValueId()), rhsCallValue.operandValueIds()),
                () -> assertEquals("+", compoundValue.binaryOperatorLexeme()),
                () -> assertEquals(
                        List.of(currentPropertyValue.resultValueId(), rhsCallValue.resultValueId()),
                        compoundValue.operandValueIds()
                ),
                () -> assertEquals(List.of(receiverValue.resultValueId()), assignmentCommit.targetOperandValueIds()),
                () -> assertEquals(compoundValue.resultValueId(), assignmentCommit.rhsValueId()),
                () -> assertSame(assignmentExpression, assignmentPayload.routeAnchor()),
                () -> assertEquals(FrontendWritableRoutePayload.RootKind.DIRECT_SLOT, assignmentPayload.root().kind()),
                () -> assertSame(targetAttribute.base(), assignmentPayload.root().anchor()),
                () -> assertEquals(FrontendWritableRoutePayload.LeafKind.PROPERTY, assignmentPayload.leaf().kind()),
                () -> assertSame(propertyStep, assignmentPayload.leaf().anchor()),
                () -> assertNull(assignmentPayload.leaf().containerValueIdOrNull()),
                () -> assertEquals(List.of(), assignmentPayload.leaf().operandValueIds()),
                () -> assertEquals("hp", assignmentPayload.leaf().memberNameOrNull()),
                () -> assertNull(assignmentPayload.leaf().subscriptAccessKindOrNull()),
                () -> assertTrue(assignmentPayload.reverseCommitSteps().isEmpty())
        );
    }

    @Test
    void buildExecutableBodyPublishesCompoundSubscriptReadModifyWriteSequence() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_compound_subscript.gd",
                """
                        class_name CfgBuilderCompoundSubscript
                        extends RefCounted
                        
                        var items: Array[int]
                        
                        func next_index() -> int:
                            return 0
                        
                        func produce(value: int) -> int:
                            return value + 1
                        
                        func ping(seed: int) -> void:
                            items[next_index()] += produce(seed)
                        """,
                "ping",
                Map.of("CfgBuilderCompoundSubscript", "RuntimeCfgBuilderCompoundSubscript")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var assignmentStatement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var assignmentExpression = assertInstanceOf(AssignmentExpression.class, assignmentStatement.expression());
        var targetSubscript = assertInstanceOf(dev.superice.gdparser.frontend.ast.SubscriptExpression.class, assignmentExpression.left());
        var indexCall = assertInstanceOf(CallExpression.class, targetSubscript.arguments().getFirst());
        var rhsCall = assertInstanceOf(CallExpression.class, assignmentExpression.right());

        var items = entryNode.items();
        var baseValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(0));
        var indexCallValue = assertInstanceOf(CallItem.class, items.get(1));
        var currentSubscriptValue = assertInstanceOf(SubscriptLoadItem.class, items.get(2));
        var rhsSeedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(3));
        var rhsCallValue = assertInstanceOf(CallItem.class, items.get(4));
        var compoundValue = assertInstanceOf(CompoundAssignmentBinaryOpItem.class, items.get(5));
        var assignmentCommit = assertInstanceOf(AssignmentItem.class, items.get(6));
        var assignmentPayload = requireNotNull(
                assignmentCommit.writableRoutePayload(),
                "compound subscript assignment should publish a subscript payload"
        );
        var assignmentCommitStep = assignmentPayload.reverseCommitSteps().getFirst();

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(7, items.size()),
                () -> assertSame(targetSubscript.base(), baseValue.expression()),
                () -> assertSame(indexCall, indexCallValue.anchor()),
                () -> assertEquals(1, items.stream()
                        .filter(CallItem.class::isInstance)
                        .map(CallItem.class::cast)
                        .filter(item -> item.anchor() == indexCall)
                        .count()),
                () -> assertEquals(List.of(baseValue.resultValueId(), indexCallValue.resultValueId()),
                        currentSubscriptValue.operandValueIds()),
                () -> assertSame(rhsCall, rhsCallValue.anchor()),
                () -> assertEquals(List.of(rhsSeedValue.resultValueId()), rhsCallValue.operandValueIds()),
                () -> assertEquals("+", compoundValue.binaryOperatorLexeme()),
                () -> assertEquals(
                        List.of(currentSubscriptValue.resultValueId(), rhsCallValue.resultValueId()),
                        compoundValue.operandValueIds()
                ),
                () -> assertEquals(
                        List.of(baseValue.resultValueId(), indexCallValue.resultValueId()),
                        assignmentCommit.targetOperandValueIds()
                ),
                () -> assertEquals(compoundValue.resultValueId(), assignmentCommit.rhsValueId()),
                () -> assertSame(assignmentExpression, assignmentPayload.routeAnchor()),
                () -> assertEquals(FrontendWritableRoutePayload.RootKind.SELF_CONTEXT, assignmentPayload.root().kind()),
                () -> assertSame(targetSubscript.base(), assignmentPayload.root().anchor()),
                () -> assertEquals(FrontendWritableRoutePayload.LeafKind.SUBSCRIPT, assignmentPayload.leaf().kind()),
                () -> assertSame(targetSubscript, assignmentPayload.leaf().anchor()),
                () -> assertEquals(baseValue.resultValueId(), assignmentPayload.leaf().containerValueIdOrNull()),
                () -> assertEquals(List.of(indexCallValue.resultValueId()), assignmentPayload.leaf().operandValueIds()),
                () -> assertNull(assignmentPayload.leaf().memberNameOrNull()),
                () -> assertEquals(
                        FrontendSubscriptAccessSupport.AccessKind.INDEXED,
                        assignmentPayload.leaf().subscriptAccessKindOrNull()
                ),
                () -> assertEquals(1, assignmentPayload.reverseCommitSteps().size()),
                () -> assertEquals(FrontendWritableRoutePayload.StepKind.PROPERTY, assignmentCommitStep.kind()),
                () -> assertSame(targetSubscript.base(), assignmentCommitStep.anchor()),
                () -> assertEquals("items", assignmentCommitStep.memberNameOrNull())
        );
    }

    @Test
    void buildExecutableBodyPublishesCompoundAttributeSubscriptReadModifyWriteSequence() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_compound_attribute_subscript.gd",
                """
                        class_name CfgBuilderCompoundAttributeSubscript
                        extends RefCounted
                        
                        var payloads: Array[int]
                        var holder: CfgBuilderCompoundAttributeSubscript
                        
                        func delta(value: int) -> int:
                            return value + 1
                        
                        func ping(seed: int) -> void:
                            holder.payloads[seed] += delta(seed)
                        """,
                "ping",
                Map.of(
                        "CfgBuilderCompoundAttributeSubscript",
                        "RuntimeCfgBuilderCompoundAttributeSubscript"
                )
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode("seq_0"));
        var assignmentStatement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var assignmentExpression = assertInstanceOf(AssignmentExpression.class, assignmentStatement.expression());
        var targetAttribute = assertInstanceOf(AttributeExpression.class, assignmentExpression.left());
        var targetStep = assertInstanceOf(AttributeSubscriptStep.class, targetAttribute.steps().getLast());
        var rhsCall = assertInstanceOf(CallExpression.class, assignmentExpression.right());

        var items = entryNode.items();
        var baseValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(0));
        var indexValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(1));
        var currentAttributeSubscriptValue = assertInstanceOf(SubscriptLoadItem.class, items.get(2));
        var rhsSeedValue = assertInstanceOf(OpaqueExprValueItem.class, items.get(3));
        var rhsCallValue = assertInstanceOf(CallItem.class, items.get(4));
        var compoundValue = assertInstanceOf(CompoundAssignmentBinaryOpItem.class, items.get(5));
        var assignmentCommit = assertInstanceOf(AssignmentItem.class, items.get(6));
        var assignmentPayload = requireNotNull(
                assignmentCommit.writableRoutePayload(),
                "compound attribute-subscript assignment should publish a nested writable payload"
        );
        var assignmentCommitStep = assignmentPayload.reverseCommitSteps().getFirst();

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(7, items.size()),
                () -> assertSame(targetAttribute.base(), baseValue.expression()),
                () -> assertSame(targetStep.arguments().getFirst(), indexValue.expression()),
                () -> assertSame(targetStep, currentAttributeSubscriptValue.anchor()),
                () -> assertEquals("payloads", currentAttributeSubscriptValue.memberNameOrNull()),
                () -> assertEquals(
                        List.of(baseValue.resultValueId(), indexValue.resultValueId()),
                        currentAttributeSubscriptValue.operandValueIds()
                ),
                () -> assertSame(rhsCall, rhsCallValue.anchor()),
                () -> assertEquals(List.of(rhsSeedValue.resultValueId()), rhsCallValue.operandValueIds()),
                () -> assertEquals("+", compoundValue.binaryOperatorLexeme()),
                () -> assertEquals(
                        List.of(currentAttributeSubscriptValue.resultValueId(), rhsCallValue.resultValueId()),
                        compoundValue.operandValueIds()
                ),
                () -> assertEquals(
                        List.of(baseValue.resultValueId(), indexValue.resultValueId()),
                        assignmentCommit.targetOperandValueIds()
                ),
                () -> assertEquals(compoundValue.resultValueId(), assignmentCommit.rhsValueId()),
                () -> assertSame(assignmentExpression, assignmentPayload.routeAnchor()),
                () -> assertEquals(FrontendWritableRoutePayload.RootKind.SELF_CONTEXT, assignmentPayload.root().kind()),
                () -> assertSame(targetAttribute.base(), assignmentPayload.root().anchor()),
                () -> assertEquals(FrontendWritableRoutePayload.LeafKind.SUBSCRIPT, assignmentPayload.leaf().kind()),
                () -> assertSame(targetStep, assignmentPayload.leaf().anchor()),
                () -> assertEquals(baseValue.resultValueId(), assignmentPayload.leaf().containerValueIdOrNull()),
                () -> assertEquals(List.of(indexValue.resultValueId()), assignmentPayload.leaf().operandValueIds()),
                () -> assertEquals("payloads", assignmentPayload.leaf().memberNameOrNull()),
                () -> assertEquals(
                        FrontendSubscriptAccessSupport.AccessKind.INDEXED,
                        assignmentPayload.leaf().subscriptAccessKindOrNull()
                ),
                () -> assertEquals(1, assignmentPayload.reverseCommitSteps().size()),
                () -> assertEquals(FrontendWritableRoutePayload.StepKind.PROPERTY, assignmentCommitStep.kind()),
                () -> assertSame(targetAttribute.base(), assignmentCommitStep.anchor()),
                () -> assertEquals("holder", assignmentCommitStep.memberNameOrNull())
        );
    }

    @Test
    void buildExecutableBodyFailsFastWhenCompoundPropertyReadFactIsMissing() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_compound_missing_property_fact.gd",
                """
                        class_name CfgBuilderCompoundMissingPropertyFact
                        extends RefCounted
                        
                        var hp: int
                        
                        func heal(value: int) -> int:
                            return value + 1
                        
                        func ping(seed: int) -> void:
                            self.hp += heal(seed)
                        """,
                "ping",
                Map.of(
                        "CfgBuilderCompoundMissingPropertyFact",
                        "RuntimeCfgBuilderCompoundMissingPropertyFact"
                )
        );

        var rootBlock = analyzed.function().body();
        var assignmentStatement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().getFirst());
        var assignmentExpression = assertInstanceOf(AssignmentExpression.class, assignmentStatement.expression());
        var targetAttribute = assertInstanceOf(AttributeExpression.class, assignmentExpression.left());
        var propertyStep = assertInstanceOf(AttributePropertyStep.class, targetAttribute.steps().getLast());
        analyzed.analysisData().resolvedMembers().remove(propertyStep);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertTrue(exception.getMessage().contains("compound-assignment publication contract")),
                () -> assertTrue(exception.getMessage().contains("AttributePropertyStep 'hp'")),
                () -> assertFalse(exception.getMessage().contains("unsupported assignment target"))
        );
    }

    @Test
    void buildExecutableBodyKeepsDeclarationCommitExplicitWhenInitializerIsMissing() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_declaration_without_initializer.gd",
                """
                        class_name CfgBuilderDeclarationWithoutInitializer
                        extends RefCounted
                        
                        func ping() -> int:
                            var local: int
                            return 1
                        """,
                "ping",
                Map.of(
                        "CfgBuilderDeclarationWithoutInitializer",
                        "RuntimeCfgBuilderDeclarationWithoutInitializer"
                )
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode("seq_0"));
        var declaration = assertInstanceOf(LocalDeclarationItem.class, entryNode.items().getFirst());
        var returnValue = assertInstanceOf(OpaqueExprValueItem.class, entryNode.items().get(1));

        assertAll(
                () -> assertEquals(2, entryNode.items().size()),
                () -> assertEquals("local", declaration.declaration().name()),
                () -> assertTrue(declaration.operandValueIds().isEmpty()),
                () -> assertEquals(List.of(), declaration.operandValueIds()),
                () -> assertEquals("v0", returnValue.resultValueId())
        );
    }

    @Test
    void buildExecutableBodyFailsFastWhenPublishedCallFactIsMissing() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_missing_call_fact.gd",
                """
                        class_name CfgBuilderMissingCallFact
                        extends RefCounted
                        
                        func helper(value: int) -> int:
                            return value + 1
                        
                        func ping(seed: int) -> int:
                            return helper(seed)
                        """,
                "ping",
                Map.of("CfgBuilderMissingCallFact", "RuntimeCfgBuilderMissingCallFact")
        );

        var rootBlock = analyzed.function().body();
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var helperCall = assertInstanceOf(CallExpression.class, returnStatement.value());
        analyzed.analysisData().resolvedCalls().remove(helperCall);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData())
        );

        assertTrue(exception.getMessage().contains("resolvedCalls()"), exception.getMessage());
    }

    @Test
    void buildExecutableBodyPublishesShortCircuitValueGraphForNestedConsumer() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_short_circuit_binary.gd",
                """
                        class_name CfgBuilderShortCircuitBinary
                        extends RefCounted
                        
                        func helper(value: int) -> bool:
                            return value > 0
                        
                        func consume(value: bool) -> bool:
                            return value
                        
                        func ping(flag: bool, seed: int) -> bool:
                            return consume(flag or helper(seed))
                        """,
                "ping",
                Map.of("CfgBuilderShortCircuitBinary", "RuntimeCfgBuilderShortCircuitBinary")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var consumeCall = assertInstanceOf(CallExpression.class, returnStatement.value());
        var shortCircuit = assertInstanceOf(BinaryExpression.class, consumeCall.arguments().getFirst());
        var helperCall = assertInstanceOf(CallExpression.class, shortCircuit.right());

        var firstBranch = requireReachableBranchByConditionRoot(graph, graph.entryNodeId(), shortCircuit.left());
        var secondBranch = requireReachableBranchByConditionRoot(graph, firstBranch.falseTargetId(), shortCircuit.right());
        var firstProducer = requireSingleReachableValueProducerForBranch(graph, graph.entryNodeId(), firstBranch);
        var secondProducer = requireSingleReachableValueProducerForBranch(graph, firstBranch.falseTargetId(), secondBranch);
        var itemsBeforeLeftSplit = collectReachableItemsBeforeTargets(
                graph,
                graph.entryNodeId(),
                Set.of(firstBranch.trueTargetId(), firstBranch.falseTargetId())
        );
        var reachableValueItems = collectReachableItemsBeforeTargets(graph, graph.entryNodeId(), Set.of()).stream()
                .filter(ValueOpItem.class::isInstance)
                .map(ValueOpItem.class::cast)
                .toList();
        var consumeItem = reachableValueItems.stream()
                .filter(CallItem.class::isInstance)
                .map(CallItem.class::cast)
                .filter(item -> item.anchor() == consumeCall)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing consume CallItem"));
        var mergedResultValueId = consumeItem.argumentValueIds().getFirst();
        var mergeItems = reachableValueItems.stream()
                .filter(MergeValueItem.class::isInstance)
                .map(MergeValueItem.class::cast)
                .filter(item -> item.resultValueId().equals(mergedResultValueId))
                .toList();
        var boolConstantsById = reachableValueItems.stream()
                .filter(BoolConstantItem.class::isInstance)
                .map(BoolConstantItem.class::cast)
                .collect(java.util.stream.Collectors.toMap(BoolConstantItem::resultValueId, item -> item));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertSame(shortCircuit.left(), firstBranch.conditionRoot()),
                () -> assertSame(shortCircuit.right(), secondBranch.conditionRoot()),
                () -> assertSame(shortCircuit.left(), firstProducer.anchor()),
                () -> assertSame(shortCircuit.right(), secondProducer.anchor()),
                () -> assertEquals(List.of(mergedResultValueId), consumeItem.operandValueIds()),
                () -> assertNotEquals(mergedResultValueId, firstBranch.conditionValueId()),
                () -> assertNotEquals(mergedResultValueId, secondBranch.conditionValueId()),
                () -> assertEquals(2, mergeItems.size()),
                () -> assertEquals(
                        Set.of(true, false),
                        mergeItems.stream()
                                .map(MergeValueItem::sourceValueId)
                                .map(boolConstantsById::get)
                                .map(BoolConstantItem::value)
                                .collect(java.util.stream.Collectors.toSet())
                ),
                () -> assertFalse(itemsBeforeLeftSplit.stream().anyMatch(item ->
                        item instanceof CallItem callItem && callItem.anchor() == helperCall
                ))
        );
    }

    @Test
    void buildExecutableBodyPublishesConditionalValueGraphWithMergedArms() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_conditional_value.gd",
                """
                        class_name CfgBuilderConditionalValue
                        extends RefCounted
                        
                        func ping(flag: bool, yes: int, no: int) -> int:
                            return yes if flag else no
                        """,
                "ping",
                Map.of("CfgBuilderConditionalValue", "RuntimeCfgBuilderConditionalValue")
        );

        var rootBlock = analyzed.function().body();
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var conditional = assertInstanceOf(ConditionalExpression.class, returnStatement.value());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var conditionBranch = requireReachableBranchByConditionRoot(graph, graph.entryNodeId(), conditional.condition());
        var trueArmSequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(conditionBranch.trueTargetId())
        );
        var falseArmSequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(conditionBranch.falseTargetId())
        );
        var trueMerge = assertInstanceOf(MergeValueItem.class, trueArmSequence.items().getLast());
        var falseMerge = assertInstanceOf(MergeValueItem.class, falseArmSequence.items().getLast());
        var valueItems = collectAllValueOpItems(graph);
        var trueArmProducer = valueItems.stream()
                .filter(item -> item.anchor() == conditional.left())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing true arm producer"));
        var falseArmProducer = valueItems.stream()
                .filter(item -> item.anchor() == conditional.right())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing false arm producer"));
        var stopNode = requireSingleStopNode(graph);

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertSame(conditional.condition(), conditionBranch.conditionRoot()),
                // Merge slot writes are the only multi-producer form for the shared result id.
                () -> assertEquals(2, valueItems.stream().filter(MergeValueItem.class::isInstance).count()),
                () -> assertTrue(valueItems.stream().noneMatch(BoolConstantItem.class::isInstance)),
                () -> assertSame(conditional, trueMerge.mergeAnchor()),
                () -> assertSame(conditional, falseMerge.mergeAnchor()),
                () -> assertEquals(trueMerge.resultValueId(), falseMerge.resultValueId()),
                () -> assertEquals(trueArmProducer.resultValueIdOrNull(), trueMerge.sourceValueId()),
                () -> assertEquals(falseArmProducer.resultValueIdOrNull(), falseMerge.sourceValueId()),
                () -> assertNotEquals(trueMerge.sourceValueId(), falseMerge.sourceValueId()),
                // Both arms rejoin the same merge continuation, which feeds the return stop.
                () -> assertEquals(trueArmSequence.nextId(), falseArmSequence.nextId()),
                () -> assertEquals(trueMerge.resultValueId(), stopNode.returnValueIdOrNull())
        );
    }

    @Test
    void buildExecutableBodyReusesPreferredResultValueIdForConditionalInitializer() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_conditional_initializer.gd",
                """
                        class_name CfgBuilderConditionalInitializer
                        extends RefCounted
                        
                        func ping(flag: bool) -> int:
                            var x = 1 if flag else 2
                            return x
                        """,
                "ping",
                Map.of("CfgBuilderConditionalInitializer", "RuntimeCfgBuilderConditionalInitializer")
        );

        var rootBlock = analyzed.function().body();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var declarationItem = requireSingleSequenceItem(graph, LocalDeclarationItem.class);
        var initializerId = requireNotNull(
                declarationItem.initializerValueIdOrNull(),
                "Conditional initializer must publish a value id"
        );
        var mergeItems = collectAllValueOpItems(graph).stream()
                .filter(MergeValueItem.class::isInstance)
                .map(MergeValueItem.class::cast)
                .toList();

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                // The declaration-name preferred id becomes the shared merge slot id directly.
                () -> assertTrue(initializerId.startsWith("x_")),
                () -> assertEquals(2, mergeItems.size()),
                () -> assertEquals(initializerId, mergeItems.getFirst().resultValueId()),
                () -> assertEquals(initializerId, mergeItems.getLast().resultValueId())
        );
    }

    @Test
    void buildExecutableBodyPublishesConditionalValueGraphForShortCircuitCondition() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_conditional_short_circuit_condition.gd",
                """
                        class_name CfgBuilderConditionalShortCircuitCondition
                        extends RefCounted
                        
                        func ping(flag: bool, other: bool) -> int:
                            return 1 if not flag and other else 2
                        """,
                "ping",
                Map.of(
                        "CfgBuilderConditionalShortCircuitCondition",
                        "RuntimeCfgBuilderConditionalShortCircuitCondition"
                )
        );

        var rootBlock = analyzed.function().body();
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var conditional = assertInstanceOf(ConditionalExpression.class, returnStatement.value());
        var shortCircuit = assertInstanceOf(BinaryExpression.class, conditional.condition());
        var negated = assertInstanceOf(UnaryExpression.class, shortCircuit.left());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var valueItems = collectAllValueOpItems(graph);
        var mergeItems = valueItems.stream()
                .filter(MergeValueItem.class::isInstance)
                .map(MergeValueItem.class::cast)
                .toList();
        var flagBranch = requireReachableBranchByConditionRoot(graph, graph.entryNodeId(), negated.operand());
        var otherBranch = requireReachableBranchByConditionRoot(graph, graph.entryNodeId(), shortCircuit.right());
        var trueArmValueId = requireSingleProducerResultId(graph, conditional.left());
        var trueArmSequence = requireSequenceContainingMergeWrite(graph, conditional, trueArmValueId);

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                // `not` keeps the operand as condition root and only swaps targets; the false arm of
                // `flag` must skip the `other` test and land on the ternary's false arm sequence.
                () -> assertEquals(flagBranch.trueTargetId(), otherBranch.falseTargetId()),
                () -> assertEquals(trueArmSequence.id(), otherBranch.trueTargetId()),
                () -> assertEquals(2, mergeItems.size()),
                () -> assertEquals(mergeItems.getFirst().resultValueId(), mergeItems.getLast().resultValueId())
        );
    }

    @Test
    void buildExecutableBodyPublishesNestedConditionalValueGraphWithUniqueMergeIds() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_nested_conditional_value.gd",
                """
                        class_name CfgBuilderNestedConditionalValue
                        extends RefCounted
                        
                        func ping(c1: bool, c2: bool, a: int, b: int, d: int) -> int:
                            return a if c1 else b if c2 else d
                        """,
                "ping",
                Map.of("CfgBuilderNestedConditionalValue", "RuntimeCfgBuilderNestedConditionalValue")
        );

        var rootBlock = analyzed.function().body();
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var outer = assertInstanceOf(ConditionalExpression.class, returnStatement.value());
        var inner = assertInstanceOf(ConditionalExpression.class, outer.right());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var outerBranch = requireReachableBranchByConditionRoot(graph, graph.entryNodeId(), outer.condition());
        var innerBranch = requireReachableBranchByConditionRoot(graph, outerBranch.falseTargetId(), inner.condition());
        var mergeItems = collectAllValueOpItems(graph).stream()
                .filter(MergeValueItem.class::isInstance)
                .map(MergeValueItem.class::cast)
                .toList();
        var innerMerges = mergeItems.stream().filter(item -> item.mergeAnchor() == inner).toList();
        var outerMerges = mergeItems.stream().filter(item -> item.mergeAnchor() == outer).toList();
        var innerResultId = innerMerges.getFirst().resultValueId();
        var stopNode = requireSingleStopNode(graph);

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(4, mergeItems.size()),
                () -> assertEquals(2, innerMerges.size()),
                () -> assertEquals(2, outerMerges.size()),
                () -> assertEquals(innerResultId, innerMerges.getLast().resultValueId()),
                // The inner merge result feeds the outer merge slot (merge-of-merge), and each
                // nesting level keeps its own result id.
                () -> assertTrue(outerMerges.stream().anyMatch(item -> item.sourceValueId().equals(innerResultId))),
                () -> assertNotEquals(innerResultId, outerMerges.getFirst().resultValueId()),
                () -> assertEquals(outerMerges.getFirst().resultValueId(), stopNode.returnValueIdOrNull()),
                () -> assertNotEquals(innerBranch.conditionValueId(), outerBranch.conditionValueId())
        );
    }

    @Test
    void buildExecutableBodyPublishesParenthesizedLeftNestedConditionalValueGraph() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_left_nested_conditional_value.gd",
                """
                        class_name CfgBuilderLeftNestedConditionalValue
                        extends RefCounted
                        
                        func ping(c1: bool, c2: bool, a: int, b: int, d: int) -> int:
                            return (a if c1 else b) if c2 else d
                        """,
                "ping",
                Map.of("CfgBuilderLeftNestedConditionalValue", "RuntimeCfgBuilderLeftNestedConditionalValue")
        );

        var rootBlock = analyzed.function().body();
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var outer = assertInstanceOf(ConditionalExpression.class, returnStatement.value());
        var inner = assertInstanceOf(ConditionalExpression.class, outer.left());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var outerBranch = requireReachableBranchByConditionRoot(graph, graph.entryNodeId(), outer.condition());
        var innerBranch = requireReachableBranchByConditionRoot(graph, outerBranch.trueTargetId(), inner.condition());
        var mergeItems = collectAllValueOpItems(graph).stream()
                .filter(MergeValueItem.class::isInstance)
                .map(MergeValueItem.class::cast)
                .toList();
        var innerResultId = mergeItems.stream()
                .filter(item -> item.mergeAnchor() == inner)
                .findFirst()
                .orElseThrow()
                .resultValueId();
        var outerMerges = mergeItems.stream().filter(item -> item.mergeAnchor() == outer).toList();

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(4, mergeItems.size()),
                () -> assertTrue(outerMerges.stream().anyMatch(item -> item.sourceValueId().equals(innerResultId))),
                () -> assertNotEquals(innerBranch.conditionValueId(), outerBranch.conditionValueId())
        );
    }

    @Test
    void buildExecutableBodyPublishesConditionalValueGraphWithShortCircuitArm() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_conditional_short_circuit_arm.gd",
                """
                        class_name CfgBuilderConditionalShortCircuitArm
                        extends RefCounted
                        
                        func ping(a: bool, b: bool, c: bool, d: bool) -> bool:
                            return (a or b) if c else d
                        """,
                "ping",
                Map.of("CfgBuilderConditionalShortCircuitArm", "RuntimeCfgBuilderConditionalShortCircuitArm")
        );

        var rootBlock = analyzed.function().body();
        var returnStatement = assertInstanceOf(ReturnStatement.class, rootBlock.statements().getFirst());
        var conditional = assertInstanceOf(ConditionalExpression.class, returnStatement.value());
        var orExpression = assertInstanceOf(BinaryExpression.class, conditional.left());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var outerBranch = requireReachableBranchByConditionRoot(graph, graph.entryNodeId(), conditional.condition());
        var orLeftBranch = requireReachableBranchByConditionRoot(graph, outerBranch.trueTargetId(), orExpression.left());
        var orRightBranch = requireReachableBranchByConditionRoot(graph, orLeftBranch.falseTargetId(), orExpression.right());
        var valueItems = collectAllValueOpItems(graph);
        var mergeItems = valueItems.stream()
                .filter(MergeValueItem.class::isInstance)
                .map(MergeValueItem.class::cast)
                .toList();
        var orMerges = mergeItems.stream().filter(item -> item.mergeAnchor() == orExpression).toList();
        var conditionalMerges = mergeItems.stream().filter(item -> item.mergeAnchor() == conditional).toList();
        var orResultId = orMerges.getFirst().resultValueId();
        var conditionalResultId = conditionalMerges.getFirst().resultValueId();
        // The or result is produced in the or write sequences but consumed by the ternary merge write
        // in the or merge continuation, so its global producers must all be MergeValueItems for the
        // cross-sequence merge-of-merge contract to accept the graph.
        var orResultProducers = valueItems.stream()
                .filter(item -> orResultId.equals(item.resultValueIdOrNull()))
                .toList();
        var conditionalOrMerge = requireSequenceContainingMergeWrite(graph, conditional, orResultId);

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(2, valueItems.stream().filter(BoolConstantItem.class::isInstance).count()),
                () -> assertEquals(2, orMerges.size()),
                () -> assertEquals(2, conditionalMerges.size()),
                () -> assertEquals(orResultId, orMerges.getLast().resultValueId()),
                () -> assertFalse(orResultProducers.isEmpty()),
                () -> assertTrue(orResultProducers.stream().allMatch(MergeValueItem.class::isInstance)),
                () -> assertTrue(
                        conditionalMerges.stream().anyMatch(item -> item.sourceValueId().equals(orResultId))
                ),
                () -> assertNotEquals(orResultId, conditionalResultId),
                // The ternary merge write for the or arm lands in the or merge continuation, which is
                // the sequence both or write sequences flow into.
                () -> assertEquals(
                        conditionalOrMerge.id(),
                        assertInstanceOf(
                                FrontendCfgGraph.SequenceNode.class,
                                graph.requireNode(orLeftBranch.trueTargetId())
                        ).nextId()
                ),
                () -> assertNotEquals(orLeftBranch.conditionValueId(), orRightBranch.conditionValueId())
        );
    }

    @Test
    void buildExecutableBodyExpandsConditionalConditionAsPureControlFlow() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_conditional_condition.gd",
                """
                        class_name CfgBuilderConditionalCondition
                        extends RefCounted
                        
                        func ping(a: bool, b: bool, c: bool) -> int:
                            if a if c else b:
                                return 1
                            return 2
                        """,
                "ping",
                Map.of("CfgBuilderConditionalCondition", "RuntimeCfgBuilderConditionalCondition")
        );

        var rootBlock = analyzed.function().body();
        var ifStatement = assertInstanceOf(IfStatement.class, rootBlock.statements().getFirst());
        var conditional = assertInstanceOf(ConditionalExpression.class, ifStatement.condition());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var ifRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(ifStatement));

        var conditionBranch = requireReachableBranchByConditionRoot(
                graph,
                ifRegion.conditionEntryId(),
                conditional.condition()
        );
        var trueArmBranch = requireReachableBranchByConditionRoot(graph, conditionBranch.trueTargetId(), conditional.left());
        var falseArmBranch = requireReachableBranchByConditionRoot(
                graph,
                conditionBranch.falseTargetId(),
                conditional.right()
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                // Condition-context ternary never produces a merge value: every arm re-tests its
                // truthiness against the outer if targets instead.
                () -> assertTrue(collectAllValueOpItems(graph).stream().noneMatch(MergeValueItem.class::isInstance)),
                () -> assertEquals(ifRegion.thenEntryId(), trueArmBranch.trueTargetId()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), trueArmBranch.falseTargetId()),
                () -> assertEquals(ifRegion.thenEntryId(), falseArmBranch.trueTargetId()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), falseArmBranch.falseTargetId()),
                () -> assertNotEquals(conditionBranch.conditionValueId(), trueArmBranch.conditionValueId()),
                () -> assertNotEquals(conditionBranch.conditionValueId(), falseArmBranch.conditionValueId())
        );
    }

    @Test
    void buildExecutableBodyExpandsNestedConditionalConditionWithoutMergeValues() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_nested_conditional_condition.gd",
                """
                        class_name CfgBuilderNestedConditionalCondition
                        extends RefCounted
                        
                        func ping(a: bool, b: bool, c: bool, c2: bool, d: bool) -> int:
                            if a if c else b if c2 else d:
                                return 1
                            return 2
                        """,
                "ping",
                Map.of("CfgBuilderNestedConditionalCondition", "RuntimeCfgBuilderNestedConditionalCondition")
        );

        var rootBlock = analyzed.function().body();
        var ifStatement = assertInstanceOf(IfStatement.class, rootBlock.statements().getFirst());
        var outer = assertInstanceOf(ConditionalExpression.class, ifStatement.condition());
        var inner = assertInstanceOf(ConditionalExpression.class, outer.right());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var ifRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(ifStatement));

        var outerBranch = requireReachableBranchByConditionRoot(graph, ifRegion.conditionEntryId(), outer.condition());
        var innerBranch = requireReachableBranchByConditionRoot(graph, outerBranch.falseTargetId(), inner.condition());
        var innerTrueBranch = requireReachableBranchByConditionRoot(graph, innerBranch.trueTargetId(), inner.left());
        var innerFalseBranch = requireReachableBranchByConditionRoot(graph, innerBranch.falseTargetId(), inner.right());
        var branchCount = graph.nodeIds().stream()
                .filter(nodeId -> graph.requireNode(nodeId) instanceof FrontendCfgGraph.BranchNode)
                .count();

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(5, branchCount),
                () -> assertTrue(collectAllValueOpItems(graph).stream().noneMatch(MergeValueItem.class::isInstance)),
                () -> assertEquals(ifRegion.thenEntryId(), innerTrueBranch.trueTargetId()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), innerTrueBranch.falseTargetId()),
                () -> assertEquals(ifRegion.thenEntryId(), innerFalseBranch.trueTargetId()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), innerFalseBranch.falseTargetId())
        );
    }

    @Test
    void buildExecutableBodyExpandsShortCircuitConditionalArmWithoutMergeValues() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_conditional_condition_short_circuit_arm.gd",
                """
                        class_name CfgBuilderConditionalConditionShortCircuitArm
                        extends RefCounted
                        
                        func ping(a: bool, b: bool, c: bool, d: bool) -> int:
                            if (a or b) if c else d:
                                return 1
                            return 2
                        """,
                "ping",
                Map.of(
                        "CfgBuilderConditionalConditionShortCircuitArm",
                        "RuntimeCfgBuilderConditionalConditionShortCircuitArm"
                )
        );

        var rootBlock = analyzed.function().body();
        var ifStatement = assertInstanceOf(IfStatement.class, rootBlock.statements().getFirst());
        var conditional = assertInstanceOf(ConditionalExpression.class, ifStatement.condition());
        var orExpression = assertInstanceOf(BinaryExpression.class, conditional.left());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var ifRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(ifStatement));

        var conditionBranch = requireReachableBranchByConditionRoot(
                graph,
                ifRegion.conditionEntryId(),
                conditional.condition()
        );
        var orLeftBranch = requireReachableBranchByConditionRoot(graph, conditionBranch.trueTargetId(), orExpression.left());
        var orRightBranch = requireReachableBranchByConditionRoot(graph, orLeftBranch.falseTargetId(), orExpression.right());
        var fallbackBranch = requireReachableBranchByConditionRoot(
                graph,
                conditionBranch.falseTargetId(),
                conditional.right()
        );
        var valueItems = collectAllValueOpItems(graph);

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                // Condition-context `or` inside a condition-context ternary stays pure control flow:
                // no merge slots and no bool constants anywhere in the graph.
                () -> assertTrue(valueItems.stream().noneMatch(MergeValueItem.class::isInstance)),
                () -> assertTrue(valueItems.stream().noneMatch(BoolConstantItem.class::isInstance)),
                () -> assertEquals(ifRegion.thenEntryId(), orLeftBranch.trueTargetId()),
                () -> assertEquals(ifRegion.thenEntryId(), orRightBranch.trueTargetId()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), orRightBranch.falseTargetId()),
                () -> assertEquals(ifRegion.thenEntryId(), fallbackBranch.trueTargetId()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), fallbackBranch.falseTargetId())
        );
    }

    @Test
    void buildExecutableBodyFailsFastWhenConditionalTypeIsNotLoweringReady() throws Exception {
        var analyzed = analyzeSharedSemanticFunction(
                "cfg_builder_conditional_missing_fact.gd",
                """
                        class_name CfgBuilderConditionalMissingFact
                        extends RefCounted
                        
                        func ping(flag: bool) -> int:
                            return missing if flag else 1
                        """,
                "ping",
                Map.of("CfgBuilderConditionalMissingFact", "RuntimeCfgBuilderConditionalMissingFact")
        );

        var rootBlock = analyzed.function().body();
        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData())
        );

        assertAll(
                () -> assertTrue(analyzed.diagnostics().hasErrors()),
                () -> assertTrue(exception.getMessage().contains("not lowering-ready"), exception.getMessage())
        );
    }

    @Test
    void buildExecutableBodyPublishesShortCircuitConditionGraphWithoutEagerRightOperand() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_short_circuit_condition.gd",
                """
                        class_name CfgBuilderShortCircuitCondition
                        extends RefCounted
                        
                        func helper(value: int) -> bool:
                            return value > 0
                        
                        func ping(flag: bool, seed: int) -> int:
                            if flag and helper(seed):
                                return seed
                            return seed + 1
                        """,
                "ping",
                Map.of("CfgBuilderShortCircuitCondition", "RuntimeCfgBuilderShortCircuitCondition")
        );

        var rootBlock = analyzed.function().body();
        var ifStatement = assertInstanceOf(IfStatement.class, rootBlock.statements().getFirst());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var ifRegion = assertInstanceOf(
                FrontendIfRegion.class,
                build.regions().get(ifStatement)
        );
        var shortCircuit = assertInstanceOf(BinaryExpression.class, ifStatement.condition());
        var helperCall = assertInstanceOf(CallExpression.class, shortCircuit.right());
        var firstBranch = requireReachableBranchByConditionRoot(graph, ifRegion.conditionEntryId(), shortCircuit.left());
        var secondBranch = requireReachableBranchByConditionRoot(graph, firstBranch.trueTargetId(), shortCircuit.right());
        var firstProducer = requireSingleReachableValueProducerForBranch(graph, ifRegion.conditionEntryId(), firstBranch);
        var secondProducer = requireSingleReachableValueProducerForBranch(graph, firstBranch.trueTargetId(), secondBranch);
        var itemsBeforeLeftSplit = collectReachableItemsBeforeTargets(
                graph,
                ifRegion.conditionEntryId(),
                Set.of(firstBranch.trueTargetId(), firstBranch.falseTargetId())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertSame(shortCircuit.left(), firstBranch.conditionRoot()),
                () -> assertSame(shortCircuit.right(), secondBranch.conditionRoot()),
                () -> assertSame(shortCircuit.left(), firstProducer.anchor()),
                () -> assertSame(shortCircuit.right(), secondProducer.anchor()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), firstBranch.falseTargetId()),
                () -> assertEquals(ifRegion.thenEntryId(), secondBranch.trueTargetId()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), secondBranch.falseTargetId()),
                () -> assertNotEquals(firstBranch.conditionValueId(), secondBranch.conditionValueId()),
                () -> assertFalse(itemsBeforeLeftSplit.stream().anyMatch(item ->
                        item instanceof CallItem callItem && callItem.anchor() == helperCall
                ))
        );
    }

    @Test
    void buildExecutableBodyPublishesNestedAndConditionBranchesWithoutEagerLaterCalls() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_nested_and_condition.gd",
                """
                        class_name CfgBuilderNestedAndCondition
                        extends RefCounted
                        
                        func first(value: int) -> bool:
                            return value > 0
                        
                        func second(value: int) -> bool:
                            return value > 1
                        
                        func ping(flag: bool, seed: int) -> int:
                            if flag and first(seed) and second(seed):
                                return seed
                            return seed + 1
                        """,
                "ping",
                Map.of("CfgBuilderNestedAndCondition", "RuntimeCfgBuilderNestedAndCondition")
        );

        var rootBlock = analyzed.function().body();
        var ifStatement = assertInstanceOf(IfStatement.class, rootBlock.statements().getFirst());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var ifRegion = assertInstanceOf(
                FrontendIfRegion.class,
                build.regions().get(ifStatement)
        );
        var shortCircuit = assertInstanceOf(BinaryExpression.class, ifStatement.condition());
        var nestedLeft = assertInstanceOf(BinaryExpression.class, shortCircuit.left());
        var firstCall = assertInstanceOf(CallExpression.class, nestedLeft.right());
        var secondCall = assertInstanceOf(CallExpression.class, shortCircuit.right());
        var firstBranch = requireReachableBranchByConditionRoot(graph, ifRegion.conditionEntryId(), nestedLeft.left());
        var secondBranch = requireReachableBranchByConditionRoot(graph, firstBranch.trueTargetId(), nestedLeft.right());
        var thirdBranch = requireReachableBranchByConditionRoot(graph, secondBranch.trueTargetId(), shortCircuit.right());
        var firstProducer = requireSingleReachableValueProducerForBranch(graph, ifRegion.conditionEntryId(), firstBranch);
        var secondProducer = requireSingleReachableValueProducerForBranch(graph, firstBranch.trueTargetId(), secondBranch);
        var thirdProducer = requireSingleReachableValueProducerForBranch(graph, secondBranch.trueTargetId(), thirdBranch);
        var itemsBeforeFirstSplit = collectReachableItemsBeforeTargets(
                graph,
                ifRegion.conditionEntryId(),
                Set.of(firstBranch.trueTargetId(), firstBranch.falseTargetId())
        );
        var itemsBeforeSecondSplit = collectReachableItemsBeforeTargets(
                graph,
                firstBranch.trueTargetId(),
                Set.of(secondBranch.trueTargetId(), secondBranch.falseTargetId())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertSame(nestedLeft.left(), firstBranch.conditionRoot()),
                () -> assertSame(nestedLeft.right(), secondBranch.conditionRoot()),
                () -> assertSame(shortCircuit.right(), thirdBranch.conditionRoot()),
                () -> assertSame(nestedLeft.left(), firstProducer.anchor()),
                () -> assertSame(nestedLeft.right(), secondProducer.anchor()),
                () -> assertSame(shortCircuit.right(), thirdProducer.anchor()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), firstBranch.falseTargetId()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), secondBranch.falseTargetId()),
                () -> assertEquals(ifRegion.thenEntryId(), thirdBranch.trueTargetId()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), thirdBranch.falseTargetId()),
                () -> assertNotEquals(firstBranch.conditionValueId(), secondBranch.conditionValueId()),
                () -> assertNotEquals(secondBranch.conditionValueId(), thirdBranch.conditionValueId()),
                () -> assertNotEquals(firstBranch.conditionValueId(), thirdBranch.conditionValueId()),
                () -> assertFalse(itemsBeforeFirstSplit.stream().anyMatch(item ->
                        item instanceof CallItem callItem
                                && (callItem.anchor() == firstCall || callItem.anchor() == secondCall)
                )),
                () -> assertTrue(itemsBeforeSecondSplit.stream()
                        .filter(CallItem.class::isInstance)
                        .map(CallItem.class::cast)
                        .anyMatch(item -> item.anchor() == firstCall)),
                () -> assertFalse(itemsBeforeSecondSplit.stream().anyMatch(item ->
                        item instanceof CallItem callItem && callItem.anchor() == secondCall
                ))
        );
    }

    @Test
    void buildExecutableBodyPublishesNestedOrConditionBranchesWithoutEagerFallbackCalls() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_nested_or_condition.gd",
                """
                        class_name CfgBuilderNestedOrCondition
                        extends RefCounted
                        
                        func first(value: int) -> bool:
                            return value > 0
                        
                        func second(value: int) -> bool:
                            return value > 1
                        
                        func ping(flag: bool, seed: int) -> int:
                            if (flag and first(seed)) or second(seed):
                                return seed
                            return seed + 1
                        """,
                "ping",
                Map.of("CfgBuilderNestedOrCondition", "RuntimeCfgBuilderNestedOrCondition")
        );

        var rootBlock = analyzed.function().body();
        var ifStatement = assertInstanceOf(IfStatement.class, rootBlock.statements().getFirst());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var ifRegion = assertInstanceOf(
                FrontendIfRegion.class,
                build.regions().get(ifStatement)
        );
        var shortCircuit = assertInstanceOf(BinaryExpression.class, ifStatement.condition());
        var nestedLeft = assertInstanceOf(BinaryExpression.class, shortCircuit.left());
        var firstCall = assertInstanceOf(CallExpression.class, nestedLeft.right());
        var secondCall = assertInstanceOf(CallExpression.class, shortCircuit.right());
        var firstBranch = requireReachableBranchByConditionRoot(graph, ifRegion.conditionEntryId(), nestedLeft.left());
        var secondBranch = requireReachableBranchByConditionRoot(graph, firstBranch.trueTargetId(), nestedLeft.right());
        var fallbackBranch = requireReachableBranchByConditionRoot(graph, firstBranch.falseTargetId(), shortCircuit.right());
        var firstProducer = requireSingleReachableValueProducerForBranch(graph, ifRegion.conditionEntryId(), firstBranch);
        var secondProducer = requireSingleReachableValueProducerForBranch(graph, firstBranch.trueTargetId(), secondBranch);
        var fallbackProducer = requireSingleReachableValueProducerForBranch(graph, firstBranch.falseTargetId(), fallbackBranch);
        var itemsBeforeFirstSplit = collectReachableItemsBeforeTargets(
                graph,
                ifRegion.conditionEntryId(),
                Set.of(firstBranch.trueTargetId(), firstBranch.falseTargetId())
        );
        var itemsBeforeSecondSplit = collectReachableItemsBeforeTargets(
                graph,
                firstBranch.trueTargetId(),
                Set.of(secondBranch.trueTargetId(), secondBranch.falseTargetId())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertSame(nestedLeft.left(), firstBranch.conditionRoot()),
                () -> assertSame(nestedLeft.right(), secondBranch.conditionRoot()),
                () -> assertSame(shortCircuit.right(), fallbackBranch.conditionRoot()),
                () -> assertSame(nestedLeft.left(), firstProducer.anchor()),
                () -> assertSame(nestedLeft.right(), secondProducer.anchor()),
                () -> assertSame(shortCircuit.right(), fallbackProducer.anchor()),
                () -> assertEquals(ifRegion.thenEntryId(), secondBranch.trueTargetId()),
                () -> assertEquals(ifRegion.thenEntryId(), fallbackBranch.trueTargetId()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), fallbackBranch.falseTargetId()),
                () -> assertNotEquals(firstBranch.conditionValueId(), secondBranch.conditionValueId()),
                () -> assertNotEquals(secondBranch.conditionValueId(), fallbackBranch.conditionValueId()),
                () -> assertNotEquals(firstBranch.conditionValueId(), fallbackBranch.conditionValueId()),
                () -> assertFalse(itemsBeforeFirstSplit.stream().anyMatch(item ->
                        item instanceof CallItem callItem
                                && (callItem.anchor() == firstCall || callItem.anchor() == secondCall)
                )),
                () -> assertTrue(itemsBeforeSecondSplit.stream()
                        .filter(CallItem.class::isInstance)
                        .map(CallItem.class::cast)
                        .anyMatch(item -> item.anchor() == firstCall)),
                () -> assertFalse(itemsBeforeSecondSplit.stream().anyMatch(item ->
                        item instanceof CallItem callItem && callItem.anchor() == secondCall
                ))
        );
    }

    @Test
    void buildExecutableBodyFlipsLogicalNotConditionWithoutPublishingUnaryValueItem() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_not_condition.gd",
                """
                        class_name CfgBuilderNotCondition
                        extends RefCounted
                        
                        func helper(value: int) -> bool:
                            return value > 0
                        
                        func ping(seed: int) -> int:
                            if not helper(seed):
                                return seed
                            return seed + 1
                        """,
                "ping",
                Map.of("CfgBuilderNotCondition", "RuntimeCfgBuilderNotCondition")
        );

        var rootBlock = analyzed.function().body();
        var ifStatement = assertInstanceOf(IfStatement.class, rootBlock.statements().getFirst());
        var notCondition = assertInstanceOf(UnaryExpression.class, ifStatement.condition());
        var helperCall = assertInstanceOf(CallExpression.class, notCondition.operand());
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var rootRegion = assertInstanceOf(FrontendCfgRegion.BlockRegion.class, build.regions().get(rootBlock));
        var ifRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(ifStatement));
        var conditionBranch = requireReachableBranch(
                graph,
                ifRegion.conditionEntryId(),
                ifRegion.elseOrNextClauseEntryId(),
                ifRegion.thenEntryId()
        );
        var conditionItems = collectReachableItemsBeforeTargets(
                graph,
                ifRegion.conditionEntryId(),
                Set.of(conditionBranch.trueTargetId(), conditionBranch.falseTargetId())
        );
        var conditionProducers = requireReachableValueProducersForBranch(graph, ifRegion.conditionEntryId(), conditionBranch);

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(ifRegion.conditionEntryId(), rootRegion.entryId()),
                () -> assertSame(helperCall, conditionBranch.conditionRoot()),
                () -> assertEquals(1, conditionProducers.size()),
                () -> assertSame(conditionBranch.conditionRoot(), conditionProducers.getFirst().anchor()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), conditionBranch.trueTargetId()),
                () -> assertEquals(ifRegion.thenEntryId(), conditionBranch.falseTargetId()),
                () -> assertTrue(conditionItems.stream()
                        .filter(CallItem.class::isInstance)
                        .map(CallItem.class::cast)
                        .anyMatch(item -> item.anchor() == helperCall)),
                () -> assertFalse(conditionItems.stream()
                        .filter(OpaqueExprValueItem.class::isInstance)
                        .map(OpaqueExprValueItem.class::cast)
                        .anyMatch(item -> item.expression() == notCondition))
        );
    }

    @Test
    void buildExecutableBodyPublishesIfElifElseRegionsAndSharedMerge() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_if_elif_else.gd",
                """
                        class_name CfgBuilderIfElifElse
                        extends RefCounted
                        
                        func ping(flag: bool, other: bool, payload: int) -> int:
                            if flag:
                                payload + 1
                            elif other:
                                return payload
                            else:
                                pass
                            return payload + 2
                        """,
                "ping",
                Map.of("CfgBuilderIfElifElse", "RuntimeCfgBuilderIfElifElse")
        );

        var rootBlock = analyzed.function().body();
        var outerIf = assertInstanceOf(IfStatement.class, rootBlock.statements().getFirst());
        var elifClause = outerIf.elifClauses().getFirst();
        var elseBody = Objects.requireNonNull(outerIf.elseBody(), "elseBody must not be null");
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var rootRegion = assertInstanceOf(FrontendCfgRegion.BlockRegion.class, build.regions().get(rootBlock));
        var ifRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(outerIf));
        var elifRegion = assertInstanceOf(FrontendElifRegion.class, build.regions().get(elifClause));
        var thenBlockRegion = assertInstanceOf(FrontendCfgRegion.BlockRegion.class, build.regions().get(outerIf.body()));
        var elseBlockRegion = assertInstanceOf(FrontendCfgRegion.BlockRegion.class, build.regions().get(elseBody));
        var thenEntry = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(ifRegion.thenEntryId()));
        var mergeEntry = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(ifRegion.mergeId()));
        var conditionBranch = requireReachableBranch(
                graph,
                ifRegion.conditionEntryId(),
                ifRegion.thenEntryId(),
                ifRegion.elseOrNextClauseEntryId()
        );
        var elifConditionBranch = requireReachableBranch(
                graph,
                elifRegion.conditionEntryId(),
                elifRegion.bodyEntryId(),
                elifRegion.nextClauseOrMergeId()
        );
        var elifBodyEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(elifRegion.bodyEntryId())
        );
        var elseEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(elifRegion.nextClauseOrMergeId())
        );
        var conditionProducers = requireReachableValueProducersForBranch(graph, ifRegion.conditionEntryId(), conditionBranch);
        var elifConditionProducers = requireReachableValueProducersForBranch(
                graph,
                elifRegion.conditionEntryId(),
                elifConditionBranch
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(ifRegion.conditionEntryId(), rootRegion.entryId()),
                () -> assertSame(outerIf.condition(), conditionBranch.conditionRoot()),
                () -> assertEquals(1, conditionProducers.size()),
                () -> assertSame(conditionBranch.conditionRoot(), conditionProducers.getFirst().anchor()),
                () -> assertEquals(thenEntry.id(), conditionBranch.trueTargetId()),
                () -> assertEquals(ifRegion.elseOrNextClauseEntryId(), conditionBranch.falseTargetId()),
                () -> assertEquals(elifRegion.conditionEntryId(), ifRegion.elseOrNextClauseEntryId()),
                () -> assertSame(elifClause.condition(), elifConditionBranch.conditionRoot()),
                () -> assertEquals(1, elifConditionProducers.size()),
                () -> assertSame(elifConditionBranch.conditionRoot(), elifConditionProducers.getFirst().anchor()),
                () -> assertEquals(elifBodyEntry.id(), elifConditionBranch.trueTargetId()),
                () -> assertEquals(elseEntry.id(), elifConditionBranch.falseTargetId()),
                () -> assertEquals(elseEntry.id(), elifRegion.nextClauseOrMergeId()),
                () -> assertEquals(thenEntry.id(), thenBlockRegion.entryId()),
                () -> assertEquals(elseEntry.id(), elseBlockRegion.entryId()),
                () -> assertEquals(ifRegion.mergeId(), thenEntry.nextId()),
                () -> assertEquals(ifRegion.mergeId(), elseEntry.nextId()),
                () -> assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode(elifBodyEntry.nextId())),
                () -> assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode(mergeEntry.nextId()))
        );
    }

    @Test
    void buildExecutableBodyPublishesWhileRegionAndLoopControlEdges() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_while_loop_control.gd",
                """
                        class_name CfgBuilderWhileLoopControl
                        extends RefCounted
                        
                        func ping(flag: bool, stop_now: bool, skip_now: bool, payload: int) -> int:
                            while flag:
                                if stop_now:
                                    break
                                if skip_now:
                                    continue
                                payload + 1
                            return payload
                        """,
                "ping",
                Map.of("CfgBuilderWhileLoopControl", "RuntimeCfgBuilderWhileLoopControl")
        );

        var rootBlock = analyzed.function().body();
        var whileStatement = assertInstanceOf(WhileStatement.class, rootBlock.statements().getFirst());
        var breakIf = assertInstanceOf(IfStatement.class, whileStatement.body().statements().getFirst());
        var continueIf = assertInstanceOf(IfStatement.class, whileStatement.body().statements().get(1));
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var rootRegion = assertInstanceOf(FrontendCfgRegion.BlockRegion.class, build.regions().get(rootBlock));
        var whileRegion = assertInstanceOf(FrontendWhileRegion.class, build.regions().get(whileStatement));
        var breakIfRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(breakIf));
        var continueIfRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(continueIf));
        var conditionBranch = requireReachableBranch(
                graph,
                whileRegion.conditionEntryId(),
                whileRegion.bodyEntryId(),
                whileRegion.exitId()
        );
        var breakThenEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(breakIfRegion.thenEntryId())
        );
        var continueThenEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(continueIfRegion.thenEntryId())
        );
        var bodyTail = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(continueIfRegion.mergeId())
        );
        var exitEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(whileRegion.exitId())
        );
        var conditionProducers = requireReachableValueProducersForBranch(graph, whileRegion.conditionEntryId(), conditionBranch);

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(whileRegion.conditionEntryId(), rootRegion.entryId()),
                () -> assertSame(whileStatement.condition(), conditionBranch.conditionRoot()),
                () -> assertEquals(1, conditionProducers.size()),
                () -> assertSame(conditionBranch.conditionRoot(), conditionProducers.getFirst().anchor()),
                () -> assertEquals(whileRegion.bodyEntryId(), conditionBranch.trueTargetId()),
                () -> assertEquals(whileRegion.exitId(), conditionBranch.falseTargetId()),
                () -> assertEquals(whileRegion.exitId(), breakThenEntry.nextId()),
                () -> assertEquals(whileRegion.conditionEntryId(), continueThenEntry.nextId()),
                () -> assertEquals(whileRegion.conditionEntryId(), bodyTail.nextId()),
                () -> assertEquals(breakIfRegion.mergeId(), breakIfRegion.elseOrNextClauseEntryId()),
                () -> assertEquals(continueIfRegion.mergeId(), continueIfRegion.elseOrNextClauseEntryId()),
                () -> assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode(exitEntry.nextId()))
        );
    }

    @Test
    void buildExecutableBodyConnectsNestedWhileBreakAndContinueToInnermostRegionTargets() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_nested_while_loop_control.gd",
                """
                        class_name CfgBuilderNestedWhileLoopControl
                        extends RefCounted
                        
                        func ping(
                                outer_flag: bool,
                                inner_flag: bool,
                                stop_inner: bool,
                                skip_inner: bool,
                                stop_outer: bool
                        ) -> int:
                            while outer_flag:
                                while inner_flag:
                                    if stop_inner:
                                        break
                                    if skip_inner:
                                        continue
                                if stop_outer:
                                    break
                            return 0
                        """,
                "ping",
                Map.of("CfgBuilderNestedWhileLoopControl", "RuntimeCfgBuilderNestedWhileLoopControl")
        );

        var rootBlock = analyzed.function().body();
        var outerWhile = assertInstanceOf(WhileStatement.class, rootBlock.statements().getFirst());
        var innerWhile = assertInstanceOf(WhileStatement.class, outerWhile.body().statements().getFirst());
        var innerBreakIf = assertInstanceOf(IfStatement.class, innerWhile.body().statements().getFirst());
        var innerContinueIf = assertInstanceOf(IfStatement.class, innerWhile.body().statements().get(1));
        var outerBreakIf = assertInstanceOf(IfStatement.class, outerWhile.body().statements().get(1));
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var outerRegion = assertInstanceOf(FrontendWhileRegion.class, build.regions().get(outerWhile));
        var innerRegion = assertInstanceOf(FrontendWhileRegion.class, build.regions().get(innerWhile));
        var innerBreakIfRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(innerBreakIf));
        var innerContinueIfRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(innerContinueIf));
        var outerBreakIfRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(outerBreakIf));
        var innerBreakThen = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(innerBreakIfRegion.thenEntryId())
        );
        var innerContinueThen = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(innerContinueIfRegion.thenEntryId())
        );
        var outerBreakThen = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(outerBreakIfRegion.thenEntryId())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(innerRegion.exitId(), innerBreakThen.nextId()),
                () -> assertEquals(innerRegion.conditionEntryId(), innerContinueThen.nextId()),
                () -> assertNotEquals(outerRegion.exitId(), innerBreakThen.nextId()),
                () -> assertNotEquals(outerRegion.conditionEntryId(), innerContinueThen.nextId()),
                () -> assertEquals(outerRegion.exitId(), outerBreakThen.nextId()),
                () -> assertNotEquals(innerRegion.exitId(), outerBreakThen.nextId())
        );
    }

    @Test
    void buildExecutableBodyConnectsMixedForAndWhileLoopControlToActiveLoopFrame() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_mixed_for_while_loop_control.gd",
                """
                        class_name CfgBuilderMixedForWhileLoopControl
                        extends RefCounted
                        
                        func ping(outer_flag: bool, stop_for: bool, skip_for: bool, stop_while: bool) -> int:
                            var total := 0
                            while outer_flag:
                                for i in range(3):
                                    if stop_for:
                                        break
                                    if skip_for:
                                        continue
                                    total = total + i
                                if stop_while:
                                    break
                            return total
                        """,
                "ping",
                Map.of("CfgBuilderMixedForWhileLoopControl", "RuntimeCfgBuilderMixedForWhileLoopControl")
        );

        var rootBlock = analyzed.function().body();
        var outerWhile = assertInstanceOf(WhileStatement.class, rootBlock.statements().get(1));
        var innerFor = assertInstanceOf(ForStatement.class, outerWhile.body().statements().getFirst());
        var forBreakIf = assertInstanceOf(IfStatement.class, innerFor.body().statements().get(0));
        var forContinueIf = assertInstanceOf(IfStatement.class, innerFor.body().statements().get(1));
        var whileBreakIf = assertInstanceOf(IfStatement.class, outerWhile.body().statements().get(1));
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();

        var whileRegion = assertInstanceOf(FrontendWhileRegion.class, build.regions().get(outerWhile));
        var forRegion = assertInstanceOf(FrontendForRegion.class, build.regions().get(innerFor));
        var forBreakIfRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(forBreakIf));
        var forContinueIfRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(forContinueIf));
        var whileBreakIfRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(whileBreakIf));
        var forBreakThen = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(forBreakIfRegion.thenEntryId())
        );
        var forContinueThen = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(forContinueIfRegion.thenEntryId())
        );
        var whileBreakThen = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(whileBreakIfRegion.thenEntryId())
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(forRegion.exitId(), forBreakThen.nextId()),
                () -> assertEquals(forRegion.updateEntryId(), forContinueThen.nextId()),
                () -> assertNotEquals(forRegion.conditionEntryId(), forContinueThen.nextId()),
                () -> assertNotEquals(whileRegion.exitId(), forBreakThen.nextId()),
                () -> assertNotEquals(whileRegion.conditionEntryId(), forContinueThen.nextId()),
                () -> assertEquals(whileRegion.exitId(), whileBreakThen.nextId()),
                () -> assertNotEquals(forRegion.exitId(), whileBreakThen.nextId())
        );
    }

    @Test
    void buildExecutableBodyDoesNotPublishLexicalRemainderAfterFullyTerminatingIfChain() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_terminating_if_chain.gd",
                """
                        class_name CfgBuilderTerminatingIfChain
                        extends RefCounted
                        
                        func ping(flag: bool, payload: int) -> int:
                            if flag:
                                return payload
                            else:
                                return payload + 1
                            payload + 2
                        """,
                "ping",
                Map.of("CfgBuilderTerminatingIfChain", "RuntimeCfgBuilderTerminatingIfChain")
        );

        var rootBlock = analyzed.function().body();
        var outerIf = assertInstanceOf(IfStatement.class, rootBlock.statements().getFirst());
        var trailingExpression = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().get(1)).expression();
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(rootBlock, analyzed.analysisData());
        var graph = build.graph();
        var ifRegion = assertInstanceOf(FrontendIfRegion.class, build.regions().get(outerIf));

        var containsTrailingExpression = graph.nodes().values().stream()
                .filter(FrontendCfgGraph.SequenceNode.class::isInstance)
                .map(FrontendCfgGraph.SequenceNode.class::cast)
                .flatMap(node -> node.items().stream())
                .filter(OpaqueExprValueItem.class::isInstance)
                .map(OpaqueExprValueItem.class::cast)
                .anyMatch(item -> item.expression() == trailingExpression);

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(
                        FrontendCfgGraph.StopKind.TERMINAL_MERGE,
                        assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode(ifRegion.mergeId())).kind()
                ),
                () -> assertFalse(containsTrailingExpression)
        );
    }

    @Test
    void buildExecutableBodyFailsFastForContinueWithoutLoopFrame() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_builder_continue_without_loop.gd",
                """
                        class_name CfgBuilderContinueWithoutLoop
                        extends RefCounted
                        
                        func ping() -> void:
                            continue
                        """,
                "ping",
                Map.of("CfgBuilderContinueWithoutLoop", "RuntimeCfgBuilderContinueWithoutLoop")
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCfgGraphBuilder().buildExecutableBody(
                        analyzed.function().body(),
                        analyzed.analysisData()
                )
        );

        assertAll(
                () -> assertTrue(analyzed.diagnostics().hasErrors()),
                () -> assertTrue(exception.getMessage().contains("loop frame"), exception.getMessage())
        );
    }

    private static @NotNull FrontendCfgGraph.BranchNode requireReachableBranch(
            @NotNull FrontendCfgGraph graph,
            @NotNull String entryId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var visited = new LinkedHashSet<String>();
        var worklist = new ArrayDeque<String>();
        worklist.add(entryId);
        while (!worklist.isEmpty()) {
            var nodeId = worklist.removeFirst();
            if (!visited.add(nodeId)) {
                continue;
            }
            switch (graph.requireNode(nodeId)) {
                case FrontendCfgGraph.SequenceNode(_, _, var nextId) -> worklist.addLast(nextId);
                case FrontendCfgGraph.BranchNode branchNode -> {
                    if (branchNode.trueTargetId().equals(trueTargetId)
                            && branchNode.falseTargetId().equals(falseTargetId)) {
                        return branchNode;
                    }
                    worklist.addLast(branchNode.trueTargetId());
                    worklist.addLast(branchNode.falseTargetId());
                }
                case FrontendCfgGraph.StopNode _ -> {
                }
            }
        }
        throw new AssertionError(
                "Missing reachable branch from " + entryId + " to " + trueTargetId + " / " + falseTargetId
        );
    }

    private static @NotNull List<SequenceItem> collectReachableItemsBeforeTargets(
            @NotNull FrontendCfgGraph graph,
            @NotNull String entryId,
            @NotNull Set<String> boundaryIds
    ) {
        var items = new java.util.ArrayList<SequenceItem>();
        var visited = new LinkedHashSet<String>();
        var worklist = new ArrayDeque<String>();
        worklist.add(entryId);
        while (!worklist.isEmpty()) {
            var nodeId = worklist.removeFirst();
            if (boundaryIds.contains(nodeId) || !visited.add(nodeId)) {
                continue;
            }
            switch (graph.requireNode(nodeId)) {
                case FrontendCfgGraph.SequenceNode(_, var nodeItems, var nextId) -> {
                    items.addAll(nodeItems);
                    worklist.addLast(nextId);
                }
                case FrontendCfgGraph.BranchNode(_, _, _, var trueTargetId, var falseTargetId) -> {
                    worklist.addLast(trueTargetId);
                    worklist.addLast(falseTargetId);
                }
                case FrontendCfgGraph.StopNode _ -> {
                }
            }
        }
        return List.copyOf(items);
    }

    private static @NotNull List<ValueOpItem> requireReachableValueProducersForBranch(
            @NotNull FrontendCfgGraph graph,
            @NotNull String entryId,
            @NotNull FrontendCfgGraph.BranchNode branchNode
    ) {
        var producers = collectReachableItemsBeforeTargets(graph, entryId, Set.of(branchNode.id())).stream()
                .filter(ValueOpItem.class::isInstance)
                .map(ValueOpItem.class::cast)
                .filter(item -> branchNode.conditionValueId().equals(item.resultValueIdOrNull()))
                .toList();
        if (producers.isEmpty()) {
            throw new AssertionError("Missing producer for branch condition value " + branchNode.conditionValueId());
        }
        return producers;
    }

    private static @NotNull ValueOpItem requireSingleReachableValueProducerForBranch(
            @NotNull FrontendCfgGraph graph,
            @NotNull String entryId,
            @NotNull FrontendCfgGraph.BranchNode branchNode
    ) {
        var producers = requireReachableValueProducersForBranch(graph, entryId, branchNode);
        assertEquals(1, producers.size());
        return producers.getFirst();
    }

    private static @NotNull FrontendCfgGraph.BranchNode requireReachableBranchByConditionRoot(
            @NotNull FrontendCfgGraph graph,
            @NotNull String entryId,
            @NotNull dev.superice.gdparser.frontend.ast.Expression conditionRoot
    ) {
        var visited = new LinkedHashSet<String>();
        var worklist = new ArrayDeque<String>();
        worklist.add(entryId);
        while (!worklist.isEmpty()) {
            var nodeId = worklist.removeFirst();
            if (!visited.add(nodeId)) {
                continue;
            }
            switch (graph.requireNode(nodeId)) {
                case FrontendCfgGraph.SequenceNode(_, _, var nextId) -> worklist.addLast(nextId);
                case FrontendCfgGraph.BranchNode branchNode -> {
                    if (branchNode.conditionRoot() == conditionRoot) {
                        return branchNode;
                    }
                    worklist.addLast(branchNode.trueTargetId());
                    worklist.addLast(branchNode.falseTargetId());
                }
                case FrontendCfgGraph.StopNode _ -> {
                }
            }
        }
        throw new AssertionError("Missing reachable branch for condition root " + conditionRoot.getClass().getSimpleName());
    }

    private static @NotNull List<ValueOpItem> collectAllValueOpItems(@NotNull FrontendCfgGraph graph) {
        return graph.nodeIds().stream()
                .map(graph::requireNode)
                .filter(FrontendCfgGraph.SequenceNode.class::isInstance)
                .map(FrontendCfgGraph.SequenceNode.class::cast)
                .flatMap(node -> node.items().stream())
                .filter(ValueOpItem.class::isInstance)
                .map(ValueOpItem.class::cast)
                .toList();
    }

    private static FrontendCfgGraph.@NotNull StopNode requireSingleStopNode(@NotNull FrontendCfgGraph graph) {
        var stops = graph.nodeIds().stream()
                .map(graph::requireNode)
                .filter(FrontendCfgGraph.StopNode.class::isInstance)
                .map(FrontendCfgGraph.StopNode.class::cast)
                .toList();
        assertEquals(1, stops.size(), () -> "Expected exactly one StopNode in " + graph.nodeIds());
        return stops.getFirst();
    }

    /// Resolves the single value id published by one plain (non-branchy) arm expression.
    private static @NotNull String requireSingleProducerResultId(
            @NotNull FrontendCfgGraph graph,
            @NotNull Expression armExpression
    ) {
        var resultIds = collectAllValueOpItems(graph).stream()
                .filter(item -> item.anchor() == armExpression)
                .map(ValueOpItem::resultValueIdOrNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        assertEquals(1, resultIds.size(), () -> "Expected exactly one producer id for arm " + armExpression);
        return resultIds.getFirst();
    }

    /// Locates the sequence holding the ternary merge write for one source value id.
    private static FrontendCfgGraph.@NotNull SequenceNode requireSequenceContainingMergeWrite(
            @NotNull FrontendCfgGraph graph,
            @NotNull ConditionalExpression mergeAnchor,
            @NotNull String sourceValueId
    ) {
        return graph.nodeIds().stream()
                .map(graph::requireNode)
                .filter(FrontendCfgGraph.SequenceNode.class::isInstance)
                .map(FrontendCfgGraph.SequenceNode.class::cast)
                .filter(node -> node.items().stream().anyMatch(item ->
                        item instanceof MergeValueItem merge
                                && merge.mergeAnchor() == mergeAnchor
                                && merge.sourceValueId().equals(sourceValueId)
                ))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing merge sequence for source " + sourceValueId));
    }

    private static <T> @NotNull T requireNotNull(T value, @NotNull String detail) {
        assertNotNull(value, detail);
        return value;
    }

    private static <T extends SequenceItem> @NotNull T requireSingleSequenceItem(
            @NotNull FrontendCfgGraph graph,
            @NotNull Class<T> itemType
    ) {
        var matches = new java.util.ArrayList<T>();
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

    private static @NotNull String invokeAliasArgumentSafety(
            @NotNull List<? extends Expression> arguments
    ) throws Exception {
        var method = FrontendCfgGraphBuilder.class.getDeclaredMethod("classifyDirectSlotAliasArguments", List.class);
        method.setAccessible(true);
        return ((Enum<?>) method.invoke(new FrontendCfgGraphBuilder(), arguments)).name();
    }

    private static @NotNull IdentifierExpression identifier(@NotNull String name) {
        return new IdentifierExpression(name, SYNTHETIC_RANGE);
    }

    private static @NotNull AnalyzedFunction analyzeFunction(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull String functionName,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        return analyzeFunction(
                fileName,
                source,
                functionName,
                topLevelCanonicalNameMap,
                new ClassRegistry(ExtensionApiLoader.loadDefault())
        );
    }

    private static @NotNull AnalyzedFunction analyzeFunction(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull String functionName,
            @NotNull Map<String, String> topLevelCanonicalNameMap,
            @NotNull ClassRegistry classRegistry
    ) {
        var module = parseModule(fileName, source, topLevelCanonicalNameMap);
        var diagnostics = new DiagnosticManager();
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(
                module,
                classRegistry,
                diagnostics
        );
        return new AnalyzedFunction(
                module,
                diagnostics,
                analysisData,
                requireFunctionDeclaration(module.units().getFirst().ast(), functionName)
        );
    }

    private static @NotNull ExtensionAPI createGlobalConstantFixtureApi() throws Exception {
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

    private static @NotNull AnalyzedFunction analyzeSharedSemanticFunction(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull String functionName,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        var module = parseModule(fileName, source, topLevelCanonicalNameMap);
        var diagnostics = new DiagnosticManager();
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new AnalyzedFunction(
                module,
                diagnostics,
                analysisData,
                requireFunctionDeclaration(module.units().getFirst().ast(), functionName)
        );
    }

    private static @NotNull FrontendModule parseModule(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        return new FrontendModule("test_module", List.of(unit), topLevelCanonicalNameMap);
    }

    private static @NotNull FunctionDeclaration requireFunctionDeclaration(
            @NotNull dev.superice.gdparser.frontend.ast.SourceFile sourceFile,
            @NotNull String functionName
    ) {
        return sourceFile.statements().stream()
                .filter(FunctionDeclaration.class::isInstance)
                .map(FunctionDeclaration.class::cast)
                .filter(function -> function.name().equals(functionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing function declaration " + functionName));
    }

    private record AnalyzedFunction(
            @NotNull FrontendModule module,
            @NotNull DiagnosticManager diagnostics,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull FunctionDeclaration function
    ) {
    }
}
