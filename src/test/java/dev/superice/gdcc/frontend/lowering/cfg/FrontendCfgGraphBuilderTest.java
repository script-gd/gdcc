package dev.superice.gdcc.frontend.lowering.cfg;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.BoolConstantItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SequenceItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.ValueOpItem;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendCfgRegion;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendElifRegion;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendIfRegion;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendWhileRegion;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendCfgGraphBuilderTest {
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
                () -> assertEquals(valueStep, valueRead.anchor()),
                () -> assertEquals("value", valueRead.memberName()),
                () -> assertEquals(List.of("v8"), valueRead.operandValueIds()),
                () -> assertEquals("v9", valueRead.resultValueId())
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
                () -> assertEquals(compoundValue.resultValueId(), assignmentCommit.rhsValueId())
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
                () -> assertEquals(compoundValue.resultValueId(), assignmentCommit.rhsValueId())
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
                () -> assertEquals(compoundValue.resultValueId(), assignmentCommit.rhsValueId())
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

    private static @NotNull AnalyzedFunction analyzeFunction(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull String functionName,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        var module = parseModule(fileName, source, topLevelCanonicalNameMap);
        var diagnostics = new DiagnosticManager();
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(
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
