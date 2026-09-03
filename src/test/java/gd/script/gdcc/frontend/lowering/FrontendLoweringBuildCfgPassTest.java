package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendCfgRegion;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendIfRegion;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendLoweringBuildCfgPassTest {
    @Test
    void runPublishesFrontendCfgGraphForExecutableBodiesAndKeepsLirShellOnly() throws Exception {
        var prepared = prepareContext(
                "build_cfg_linear_value_ops.gd",
                """
                        class_name BuildCfgLinearValueOps
                        extends RefCounted
                        
                        var ready_value: float = Vector3.ZERO.length()
                        var payloads: Dictionary[int, BuildCfgLinearValueOps]
                        var value: int
                        
                        func helper(value: int) -> int:
                            return value + 1
                        
                        func build(seed: int) -> BuildCfgLinearValueOps:
                            return self
                        
                        func fetch(index: int) -> BuildCfgLinearValueOps:
                            return self
                        
                        func ping(seed: int) -> int:
                            return build(helper(seed)).payloads[helper(seed)].fetch(helper(seed)).value
                        
                        func branchy(flag: bool) -> void:
                            if flag:
                                pass
                        """,
                Map.of("BuildCfgLinearValueOps", "RuntimeBuildCfgLinearValueOps")
        );

        new FrontendLoweringBuildCfgPass().run(prepared.context());

        var contexts = prepared.context().requireFunctionLoweringContexts();
        var linearContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBuildCfgLinearValueOps",
                "ping"
        );
        var structuredContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBuildCfgLinearValueOps",
                "branchy"
        );
        var propertyContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBuildCfgLinearValueOps",
                "_field_init_ready_value"
        );

        var pingFunction = requireFunctionDeclaration(prepared.module().units().getFirst().ast(), "ping");
        var pingBlock = pingFunction.body();
        var returnStatement = assertInstanceOf(ReturnStatement.class, pingBlock.statements().getFirst());
        var returnExpression = assertInstanceOf(AttributeExpression.class, returnStatement.value());
        var fetchStep = assertInstanceOf(AttributeCallStep.class, returnExpression.steps().get(1));
        var valueStep = assertInstanceOf(AttributePropertyStep.class, returnExpression.steps().get(2));
        var branchyFunction = requireFunctionDeclaration(prepared.module().units().getFirst().ast(), "branchy");
        var branchyBlock = branchyFunction.body();
        var branchyIf = assertInstanceOf(IfStatement.class, branchyBlock.statements().getFirst());
        var readyValueProperty = requirePropertyDeclaration(prepared.module().units().getFirst().ast(), "ready_value");
        var propertyInitializer = assertInstanceOf(dev.superice.gdparser.frontend.ast.AttributeExpression.class, readyValueProperty.value());
        var zeroStep = assertInstanceOf(AttributePropertyStep.class, propertyInitializer.steps().getFirst());
        var lengthStep = assertInstanceOf(AttributeCallStep.class, propertyInitializer.steps().get(1));

        var graph = linearContext.requireFrontendCfgGraph();
        var blockRegion = assertInstanceOf(
                FrontendCfgRegion.BlockRegion.class,
                linearContext.requireFrontendCfgRegion(pingBlock)
        );
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode("stop_1"));
        var items = entryNode.items();
        var firstSeed = assertInstanceOf(OpaqueExprValueItem.class, items.get(0));
        var firstHelper = assertInstanceOf(CallItem.class, items.get(1));
        var buildCall = assertInstanceOf(CallItem.class, items.get(2));
        var payloadsSubscript = assertInstanceOf(SubscriptLoadItem.class, items.get(5));
        var fetchCall = assertInstanceOf(CallItem.class, items.get(8));
        var valueRead = assertInstanceOf(MemberLoadItem.class, items.get(9));

        var structuredGraph = structuredContext.requireFrontendCfgGraph();
        var structuredRootRegion = assertInstanceOf(
                FrontendCfgRegion.BlockRegion.class,
                structuredContext.requireFrontendCfgRegion(branchyBlock)
        );
        var structuredIfRegion = assertInstanceOf(
                FrontendIfRegion.class,
                structuredContext.requireFrontendCfgRegion(branchyIf)
        );
        structuredGraph.requireNode(structuredRootRegion.entryId());
        var structuredBranch = requireReachableBranch(
                structuredGraph,
                structuredIfRegion.conditionEntryId(),
                structuredIfRegion.thenEntryId(),
                structuredIfRegion.elseOrNextClauseEntryId()
        );
        var structuredMerge = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                structuredGraph.requireNode(structuredIfRegion.mergeId())
        );
        var propertyGraph = propertyContext.requireFrontendCfgGraph();
        var propertyEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                propertyGraph.requireNode(propertyGraph.entryNodeId())
        );
        var propertyStop = assertInstanceOf(
                FrontendCfgGraph.StopNode.class,
                propertyGraph.requireNode(propertyEntry.nextId())
        );
        var propertyZeroLoad = assertInstanceOf(MemberLoadItem.class, propertyEntry.items().get(0));
        var propertyLengthCall = assertInstanceOf(CallItem.class, propertyEntry.items().get(1));

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(List.of("seq_0", "stop_1"), graph.nodeIds()),
                () -> assertEquals("seq_0", graph.entryNodeId()),
                () -> assertEquals("seq_0", blockRegion.entryId()),
                () -> assertEquals("stop_1", entryNode.nextId()),
                () -> assertEquals(10, items.size()),
                () -> assertEquals(List.of(), firstSeed.operandValueIds()),
                () -> assertEquals("helper", firstHelper.callableName()),
                () -> assertEquals(List.of("v0"), firstHelper.operandValueIds()),
                () -> assertEquals("build", buildCall.callableName()),
                () -> assertEquals(List.of("v1"), buildCall.operandValueIds()),
                () -> assertEquals("payloads", payloadsSubscript.memberNameOrNull()),
                () -> assertEquals(List.of("v2", "v4"), payloadsSubscript.operandValueIds()),
                () -> assertEquals(fetchStep, fetchCall.anchor()),
                () -> assertEquals(List.of("v5", "v7"), fetchCall.operandValueIds()),
                () -> assertEquals(valueStep, valueRead.anchor()),
                () -> assertEquals(List.of("v8"), valueRead.operandValueIds()),
                () -> assertEquals("v9", stopNode.returnValueIdOrNull()),
                () -> assertEquals(0, linearContext.targetFunction().getBasicBlockCount()),
                () -> assertTrue(linearContext.targetFunction().getEntryBlockId().isEmpty()),
                () -> assertEquals(structuredRootRegion.entryId(), structuredGraph.entryNodeId()),
                () -> assertEquals(structuredIfRegion.conditionEntryId(), structuredRootRegion.entryId()),
                () -> assertEquals(branchyIf.condition(), structuredBranch.conditionRoot()),
                () -> assertEquals(structuredIfRegion.thenEntryId(), structuredBranch.trueTargetId()),
                () -> assertEquals(structuredIfRegion.elseOrNextClauseEntryId(), structuredBranch.falseTargetId()),
                () -> assertEquals(structuredIfRegion.mergeId(), structuredMerge.id()),
                () -> assertEquals("stop_0", structuredMerge.nextId()),
                () -> assertEquals(List.of("seq_0", "stop_0"), propertyGraph.nodeIds()),
                () -> assertEquals(2, propertyEntry.items().size()),
                () -> assertEquals(zeroStep, propertyZeroLoad.anchor()),
                () -> assertEquals("ZERO", propertyZeroLoad.memberName()),
                () -> assertEquals(List.of(), propertyZeroLoad.operandValueIds()),
                () -> assertEquals(lengthStep, propertyLengthCall.anchor()),
                () -> assertEquals("length", propertyLengthCall.callableName()),
                () -> assertEquals(List.of(propertyZeroLoad.resultValueId()), propertyLengthCall.operandValueIds()),
                () -> assertEquals(propertyLengthCall.resultValueId(), propertyStop.returnValueIdOrNull()),
                () -> assertNull(propertyContext.frontendCfgRegionOrNull(propertyContext.loweringRoot())),
                () -> assertEquals(0, propertyContext.targetFunction().getBasicBlockCount()),
                () -> assertTrue(propertyContext.targetFunction().getEntryBlockId().isEmpty())
        );
    }

    @Test
    void runPublishesSingletonBackedPropertyInitCfgGraph() throws Exception {
        var prepared = prepareContext(
                "build_cfg_property_init_singleton_receiver.gd",
                        """
                        class_name BuildCfgPropertyInitSingletonReceiver
                        extends RefCounted

                        var frames: int = Engine.get_frames_drawn()
                        """,
                Map.of(
                        "BuildCfgPropertyInitSingletonReceiver",
                        "RuntimeBuildCfgPropertyInitSingletonReceiver"
                )
        );
        var propertyContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBuildCfgPropertyInitSingletonReceiver",
                "_field_init_frames"
        );

        new FrontendLoweringBuildCfgPass().run(prepared.context());

        var propertyGraph = propertyContext.requireFrontendCfgGraph();
        var propertyEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                propertyGraph.requireNode(propertyGraph.entryNodeId())
        );
        var propertyStop = assertInstanceOf(
                FrontendCfgGraph.StopNode.class,
                propertyGraph.requireNode(propertyEntry.nextId())
        );
        var singletonReceiver = assertInstanceOf(OpaqueExprValueItem.class, propertyEntry.items().getFirst());
        var methodCall = assertInstanceOf(CallItem.class, propertyEntry.items().getLast());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(List.of("seq_0", "stop_0"), propertyGraph.nodeIds()),
                () -> assertEquals(2, propertyEntry.items().size()),
                () -> assertEquals(List.of(), singletonReceiver.operandValueIds()),
                () -> assertEquals("get_frames_drawn", methodCall.callableName()),
                () -> assertEquals(singletonReceiver.resultValueId(), methodCall.receiverValueIdOrNull()),
                () -> assertEquals(List.of(singletonReceiver.resultValueId()), methodCall.operandValueIds()),
                () -> assertEquals(methodCall.resultValueId(), propertyStop.returnValueIdOrNull()),
                () -> assertEquals(0, propertyContext.targetFunction().getBasicBlockCount()),
                () -> assertTrue(propertyContext.targetFunction().getEntryBlockId().isEmpty())
        );
    }

    /// A static var initializer builds the same expression-rooted CFG shape as an instance
    /// initializer (sequence node -> RETURN stop carrying the initializer value id); the target
    /// shell stays block-free until body lowering.
    @Test
    void runPublishesStaticPropertyInitCfgGraph() throws Exception {
        var prepared = prepareSharedContext(
                "build_cfg_static_property_init.gd",
                """
                        class_name BuildCfgStaticPropertyInit
                        extends RefCounted

                        static var base: int = 1
                        static var derived: int = base + 41
                        """,
                Map.of("BuildCfgStaticPropertyInit", "RuntimeBuildCfgStaticPropertyInit")
        );
        var baseContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBuildCfgStaticPropertyInit",
                "_field_init_base"
        );
        var derivedContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBuildCfgStaticPropertyInit",
                "_field_init_derived"
        );

        new FrontendLoweringBuildCfgPass().run(prepared.context());

        var baseGraph = baseContext.requireFrontendCfgGraph();
        var baseEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                baseGraph.requireNode(baseGraph.entryNodeId())
        );
        var baseStop = assertInstanceOf(
                FrontendCfgGraph.StopNode.class,
                baseGraph.requireNode(baseEntry.nextId())
        );
        var baseLiteral = assertInstanceOf(OpaqueExprValueItem.class, baseEntry.items().getFirst());

        var derivedGraph = derivedContext.requireFrontendCfgGraph();
        var derivedEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                derivedGraph.requireNode(derivedGraph.entryNodeId())
        );
        var derivedStop = assertInstanceOf(
                FrontendCfgGraph.StopNode.class,
                derivedGraph.requireNode(derivedEntry.nextId())
        );

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(List.of("seq_0", "stop_0"), baseGraph.nodeIds()),
                () -> assertEquals(baseLiteral.resultValueId(), baseStop.returnValueIdOrNull()),
                () -> assertEquals("seq_0", derivedGraph.entryNodeId()),
                // `base + 41` lowers through a binary item chain terminated by the RETURN stop;
                // the exact operator items are asserted at body-insn level.
                () -> assertFalse(derivedEntry.items().isEmpty()),
                () -> assertNotNull(derivedStop.returnValueIdOrNull()),
                () -> assertEquals(0, baseContext.targetFunction().getBasicBlockCount()),
                () -> assertEquals(0, derivedContext.targetFunction().getBasicBlockCount()),
                () -> assertTrue(baseContext.targetFunction().isStatic()),
                () -> assertEquals(0, baseContext.targetFunction().getParameterCount())
        );
    }

    @Test
    void runFailsFastWhenPropertyInitializerExpressionFactIsMissing() throws Exception {
        var prepared = prepareContext(
                "build_cfg_property_init_missing_fact.gd",
                """
                        class_name BuildCfgPropertyInitMissingFact
                        extends RefCounted
                        
                        var ready_value: int = 1
                        """,
                Map.of("BuildCfgPropertyInitMissingFact", "RuntimeBuildCfgPropertyInitMissingFact")
        );
        var propertyContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBuildCfgPropertyInitMissingFact",
                "_field_init_ready_value"
        );
        var propertyDeclaration = requirePropertyDeclaration(prepared.module().units().getFirst().ast(), "ready_value");
        var initializerExpression = java.util.Objects.requireNonNull(
                propertyDeclaration.value(),
                "property initializer must not be null"
        );
        prepared.context().requireAnalysisData().expressionTypes().remove(initializerExpression);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBuildCfgPass().run(prepared.context())
        );

        assertAll(
                () -> assertTrue(
                        exception.getMessage().contains("expressionTypes() is missing a lowering-ready fact"),
                        exception.getMessage()
                ),
                () -> assertTrue(exception.getMessage().contains("LiteralExpression"), exception.getMessage()),
                () -> assertNull(propertyContext.frontendCfgGraphOrNull()),
                () -> assertNull(propertyContext.frontendCfgRegionOrNull(initializerExpression))
        );
    }

    @Test
    void runPublishesParameterDefaultCfgGraphForInstanceShell() throws Exception {
        var prepared = prepareContext(
                "build_cfg_parameter_default.gd",
                """
                        class_name BuildCfgParameterDefault
                        extends RefCounted
                        
                        var hp: int = 10
                        
                        func max_hp() -> int:
                            return 100
                        
                        func restore(amount: int = self.hp, cap: int = max_hp()):
                            pass
                        """,
                Map.of("BuildCfgParameterDefault", "RuntimeBuildCfgParameterDefault")
        );
        var contexts = prepared.context().requireFunctionLoweringContexts();

        new FrontendLoweringBuildCfgPass().run(prepared.context());

        assertFalse(prepared.diagnostics().hasErrors());
        // `self.hp` lowers into a self read feeding a member load; the bare instance call
        // `max_hp()` lowers into a receiver-less call item with no self producer at all. Both
        // graphs close with a RETURN stop carrying the default expression's producer result.
        var amountContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT,
                "RuntimeBuildCfgParameterDefault",
                "_default_restore$amount"
        );
        var amountGraph = amountContext.requireFrontendCfgGraph();
        var amountEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                amountGraph.requireNode(amountGraph.entryNodeId())
        );
        var amountStop = assertInstanceOf(
                FrontendCfgGraph.StopNode.class,
                amountGraph.requireNode(amountEntry.nextId())
        );
        var selfRead = assertInstanceOf(OpaqueExprValueItem.class, amountEntry.items().get(0));
        var hpLoad = assertInstanceOf(MemberLoadItem.class, amountEntry.items().get(1));
        assertAll(
                () -> assertEquals(2, amountEntry.items().size()),
                () -> assertInstanceOf(SelfExpression.class, selfRead.anchor()),
                () -> assertEquals(List.of(), selfRead.operandValueIds()),
                () -> assertEquals("hp", hpLoad.memberName()),
                () -> assertEquals(List.of(selfRead.resultValueId()), hpLoad.operandValueIds()),
                () -> assertEquals(hpLoad.resultValueId(), amountStop.returnValueIdOrNull()),
                () -> assertNull(amountContext.frontendCfgRegionOrNull(amountContext.loweringRoot())),
                () -> assertEquals(0, amountContext.targetFunction().getBasicBlockCount()),
                () -> assertTrue(amountContext.targetFunction().getEntryBlockId().isEmpty())
        );

        var capContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT,
                "RuntimeBuildCfgParameterDefault",
                "_default_restore$cap"
        );
        var capGraph = capContext.requireFrontendCfgGraph();
        var capEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                capGraph.requireNode(capGraph.entryNodeId())
        );
        var capStop = assertInstanceOf(
                FrontendCfgGraph.StopNode.class,
                capGraph.requireNode(capEntry.nextId())
        );
        var maxHpCall = assertInstanceOf(CallItem.class, capEntry.items().getFirst());
        assertAll(
                () -> assertEquals(1, capEntry.items().size()),
                () -> assertEquals("max_hp", maxHpCall.callableName()),
                () -> assertEquals(List.of(), maxHpCall.operandValueIds()),
                () -> assertNull(maxHpCall.receiverValueIdOrNull()),
                () -> assertEquals(maxHpCall.resultValueId(), capStop.returnValueIdOrNull()),
                () -> assertTrue(capEntry.items().stream()
                        .noneMatch(item -> item instanceof OpaqueExprValueItem))
        );
    }

    @Test
    void runPublishesParameterDefaultCfgGraphForStaticShell() throws Exception {
        var prepared = prepareContext(
                "build_cfg_static_parameter_default.gd",
                """
                        class_name BuildCfgStaticParameterDefault
                        extends RefCounted
                        
                        static func build(code: int = 7):
                            pass
                        """,
                Map.of("BuildCfgStaticParameterDefault", "RuntimeBuildCfgStaticParameterDefault")
        );
        var contexts = prepared.context().requireFunctionLoweringContexts();

        new FrontendLoweringBuildCfgPass().run(prepared.context());

        assertFalse(prepared.diagnostics().hasErrors());
        // The static shell graph is a single literal producer closed by a RETURN stop; a static
        // default island can never observe `self`, so no self-anchored producer may appear.
        var codeContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT,
                "RuntimeBuildCfgStaticParameterDefault",
                "_default_s_build$code"
        );
        var codeGraph = codeContext.requireFrontendCfgGraph();
        var codeEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                codeGraph.requireNode(codeGraph.entryNodeId())
        );
        var codeStop = assertInstanceOf(
                FrontendCfgGraph.StopNode.class,
                codeGraph.requireNode(codeEntry.nextId())
        );
        var literalRead = assertInstanceOf(OpaqueExprValueItem.class, codeEntry.items().getFirst());
        assertAll(
                () -> assertEquals(1, codeEntry.items().size()),
                () -> assertFalse(literalRead.anchor() instanceof SelfExpression),
                () -> assertEquals(List.of(), literalRead.operandValueIds()),
                () -> assertEquals(literalRead.resultValueId(), codeStop.returnValueIdOrNull()),
                () -> assertEquals(0, codeContext.targetFunction().getBasicBlockCount()),
                () -> assertTrue(codeContext.targetFunction().getEntryBlockId().isEmpty())
        );
    }

    @Test
    void runRejectsParameterDefaultContextWithNonParameterSourceOwner() throws Exception {
        var prepared = prepareContext(
                "build_cfg_parameter_default_shape.gd",
                """
                        class_name BuildCfgParameterDefaultShape
                        extends RefCounted
                        
                        func ping(value: int) -> int:
                            return value
                        """,
                Map.of("BuildCfgParameterDefaultShape", "RuntimeBuildCfgParameterDefaultShape")
        );
        var executableContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBuildCfgParameterDefaultShape",
                "ping"
        );
        // Hand-corrupt the context shape: a PARAMETER_DEFAULT_INIT unit must keep the `Parameter`
        // as sourceOwner and the default expression as loweringRoot, otherwise the expression
        // build cannot find the island's published bindings/types.
        var malformedContext = new FunctionLoweringContext(
                FunctionLoweringContext.Kind.PARAMETER_DEFAULT_INIT,
                executableContext.sourcePath(),
                executableContext.sourceClassRelation(),
                executableContext.owningClass(),
                executableContext.targetFunction(),
                executableContext.sourceOwner(),
                executableContext.loweringRoot(),
                executableContext.analysisData()
        );
        prepared.context().publishFunctionLoweringContexts(List.of(executableContext, malformedContext));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> new FrontendLoweringBuildCfgPass().run(prepared.context())
        );

        assertTrue(
                exception.getMessage().contains("must keep a parameter declaration as sourceOwner"),
                exception.getMessage()
        );
    }

    @Test
    void runBuildsTypeMetaStaticHeadMemberLoadsWithoutMaterializingReceiverValues() throws Exception {
        var prepared = prepareContext(
                "build_cfg_type_meta_static_head.gd",
                """
                        class_name BuildCfgTypeMetaStaticHead
                        extends RefCounted
                        
                        func zero_length() -> float:
                            return Vector3.ZERO.length()
                        
                        func red() -> Color:
                            return Color.RED
                        """,
                Map.of("BuildCfgTypeMetaStaticHead", "RuntimeBuildCfgTypeMetaStaticHead")
        );

        new FrontendLoweringBuildCfgPass().run(prepared.context());

        var contexts = prepared.context().requireFunctionLoweringContexts();
        var zeroLengthContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBuildCfgTypeMetaStaticHead",
                "zero_length"
        );
        var redContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBuildCfgTypeMetaStaticHead",
                "red"
        );

        var sourceFile = prepared.module().units().getFirst().ast();
        var zeroLengthFunction = requireFunctionDeclaration(sourceFile, "zero_length");
        var redFunction = requireFunctionDeclaration(sourceFile, "red");
        var zeroLengthExpression = assertInstanceOf(
                AttributeExpression.class,
                assertInstanceOf(ReturnStatement.class, zeroLengthFunction.body().statements().getFirst()).value()
        );
        var redExpression = assertInstanceOf(
                AttributeExpression.class,
                assertInstanceOf(ReturnStatement.class, redFunction.body().statements().getFirst()).value()
        );
        var zeroStep = assertInstanceOf(AttributePropertyStep.class, zeroLengthExpression.steps().getFirst());
        var lengthStep = assertInstanceOf(AttributeCallStep.class, zeroLengthExpression.steps().get(1));
        var redStep = assertInstanceOf(AttributePropertyStep.class, redExpression.steps().getFirst());

        var zeroGraph = zeroLengthContext.requireFrontendCfgGraph();
        var redGraph = redContext.requireFrontendCfgGraph();
        var zeroEntry = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, zeroGraph.requireNode(zeroGraph.entryNodeId()));
        var zeroStop = assertInstanceOf(FrontendCfgGraph.StopNode.class, zeroGraph.requireNode(zeroEntry.nextId()));
        var redEntry = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, redGraph.requireNode(redGraph.entryNodeId()));
        var redStop = assertInstanceOf(FrontendCfgGraph.StopNode.class, redGraph.requireNode(redEntry.nextId()));
        var zeroStaticLoad = assertInstanceOf(MemberLoadItem.class, zeroEntry.items().get(0));
        var zeroLengthCall = assertInstanceOf(CallItem.class, zeroEntry.items().get(1));
        var redStaticLoad = assertInstanceOf(MemberLoadItem.class, redEntry.items().getFirst());

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertEquals(2, zeroEntry.items().size()),
                () -> assertEquals(zeroStep, zeroStaticLoad.anchor()),
                () -> assertEquals("ZERO", zeroStaticLoad.memberName()),
                () -> assertEquals(List.of(), zeroStaticLoad.operandValueIds()),
                () -> assertEquals(lengthStep, zeroLengthCall.anchor()),
                () -> assertEquals("length", zeroLengthCall.callableName()),
                () -> assertEquals(List.of(zeroStaticLoad.resultValueId()), zeroLengthCall.operandValueIds()),
                () -> assertEquals(zeroLengthCall.resultValueId(), zeroStop.returnValueIdOrNull()),
                () -> assertEquals(1, redEntry.items().size()),
                () -> assertEquals(redStep, redStaticLoad.anchor()),
                () -> assertEquals("RED", redStaticLoad.memberName()),
                () -> assertEquals(List.of(), redStaticLoad.operandValueIds()),
                () -> assertEquals(redStaticLoad.resultValueId(), redStop.returnValueIdOrNull())
        );
    }

    @Test
    void runBuildsDiscardedGlobalVoidCallWithoutLeakingStopReturnValue() throws Exception {
        var prepared = prepareContext(
                "build_cfg_discarded_global_void_call.gd",
                """
                        class_name BuildCfgDiscardedGlobalVoidCall
                        extends RefCounted
                        
                        func ping(seed: int) -> int:
                            print(seed)
                            return seed
                        """,
                Map.of(
                        "BuildCfgDiscardedGlobalVoidCall",
                        "RuntimeBuildCfgDiscardedGlobalVoidCall"
                )
        );

        new FrontendLoweringBuildCfgPass().run(prepared.context());

        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBuildCfgDiscardedGlobalVoidCall",
                "ping"
        );
        var graph = pingContext.requireFrontendCfgGraph();
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(graph.entryNodeId()));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode(entryNode.nextId()));
        var entryItems = entryNode.items();
        var voidCall = entryItems.stream()
                .filter(CallItem.class::isInstance)
                .map(CallItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing discarded void CallItem"));
        var returnValue = entryItems.stream()
                .filter(OpaqueExprValueItem.class::isInstance)
                .map(OpaqueExprValueItem.class::cast)
                .reduce((_, second) -> second)
                .orElseThrow(() -> new AssertionError("Missing return value item"));

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                () -> assertNull(voidCall.resultValueIdOrNull()),
                () -> assertFalse(voidCall.hasStandaloneMaterializationSlot()),
                () -> assertEquals(returnValue.resultValueId(), stopNode.returnValueIdOrNull())
        );
    }

    @Test
    void runPublishesConditionalExpressionCfgGraphsAfterGateRelease() throws Exception {
        var prepared = prepareContext(
                "build_cfg_conditional_value.gd",
                """
                        class_name BuildCfgConditionalValue
                        extends RefCounted
                        
                        var ready_choice: int = 3 if true else 4
                        
                        func ping(flag: bool, yes: int, no: int) -> int:
                            return yes if flag else no
                        """,
                Map.of("BuildCfgConditionalValue", "RuntimeBuildCfgConditionalValue")
        );

        new FrontendLoweringBuildCfgPass().run(prepared.context());

        var contexts = prepared.context().requireFunctionLoweringContexts();
        var pingContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBuildCfgConditionalValue",
                "ping"
        );
        var propertyContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeBuildCfgConditionalValue",
                "_field_init_ready_choice"
        );
        var pingFunction = requireFunctionDeclaration(prepared.module().units().getFirst().ast(), "ping");
        var returnStatement = assertInstanceOf(ReturnStatement.class, pingFunction.body().statements().getFirst());
        var conditional = assertInstanceOf(ConditionalExpression.class, returnStatement.value());
        var readyChoiceProperty = requirePropertyDeclaration(prepared.module().units().getFirst().ast(), "ready_choice");
        var propertyConditional = assertInstanceOf(ConditionalExpression.class, readyChoiceProperty.value());

        var graph = pingContext.requireFrontendCfgGraph();
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(graph.entryNodeId()));
        var conditionBranch = assertInstanceOf(FrontendCfgGraph.BranchNode.class, graph.requireNode(entryNode.nextId()));
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
        var mergeSequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                graph.requireNode(trueArmSequence.nextId())
        );
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode(mergeSequence.nextId()));

        var propertyGraph = propertyContext.requireFrontendCfgGraph();
        var propertyEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                propertyGraph.requireNode(propertyGraph.entryNodeId())
        );
        var propertyBranch = assertInstanceOf(FrontendCfgGraph.BranchNode.class, propertyGraph.requireNode(propertyEntry.nextId()));
        var propertyTrueArm = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                propertyGraph.requireNode(propertyBranch.trueTargetId())
        );
        var propertyFalseArm = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                propertyGraph.requireNode(propertyBranch.falseTargetId())
        );
        var propertyTrueMerge = assertInstanceOf(MergeValueItem.class, propertyTrueArm.items().getLast());
        var propertyFalseMerge = assertInstanceOf(MergeValueItem.class, propertyFalseArm.items().getLast());
        var propertyMergeSequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                propertyGraph.requireNode(propertyTrueArm.nextId())
        );
        var propertyStop = assertInstanceOf(FrontendCfgGraph.StopNode.class, propertyGraph.requireNode(propertyMergeSequence.nextId()));

        assertAll(
                () -> assertFalse(prepared.diagnostics().hasErrors()),
                // Executable body: the ternary condition publishes one branch; both arms end in a
                // merge write anchored at the whole conditional; the shared merge continuation feeds
                // the return stop.
                () -> assertEquals(conditional.condition(), conditionBranch.conditionRoot()),
                () -> assertEquals(conditional, trueMerge.mergeAnchor()),
                () -> assertEquals(conditional, falseMerge.mergeAnchor()),
                () -> assertEquals(trueMerge.resultValueId(), falseMerge.resultValueId()),
                () -> assertNotEquals(trueMerge.sourceValueId(), falseMerge.sourceValueId()),
                () -> assertEquals(trueArmSequence.nextId(), falseArmSequence.nextId()),
                () -> assertEquals(trueMerge.resultValueId(), stopNode.returnValueIdOrNull()),
                () -> assertEquals(0, pingContext.targetFunction().getBasicBlockCount()),
                // Property initializer: the same branch-result merge shape flows through the
                // buildPropertyInitializer route after the gate release.
                () -> assertEquals(propertyConditional.condition(), propertyBranch.conditionRoot()),
                () -> assertEquals(propertyConditional, propertyTrueMerge.mergeAnchor()),
                () -> assertEquals(propertyConditional, propertyFalseMerge.mergeAnchor()),
                () -> assertEquals(propertyTrueMerge.resultValueId(), propertyFalseMerge.resultValueId()),
                () -> assertEquals(propertyTrueArm.nextId(), propertyFalseArm.nextId()),
                () -> assertEquals(propertyTrueMerge.resultValueId(), propertyStop.returnValueIdOrNull()),
                () -> assertEquals(0, propertyContext.targetFunction().getBasicBlockCount())
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

    private static @NotNull PreparedContext prepareContext(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
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
        return new PreparedContext(context, diagnostics, module);
    }

    /// Shared-semantic variant of `prepareContext`: publishes shared analysis data directly so the
    /// CFG pass can be verified without the compile-only entry.
    private static @NotNull PreparedContext prepareSharedContext(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(List.of(new SourceFixture(fileName, source)), topLevelCanonicalNameMap);
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer()
                .analyze(module, classRegistry, diagnostics);
        var context = new FrontendLoweringContext(
                module,
                classRegistry,
                diagnostics
        );
        context.publishAnalysisData(analysisData);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
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

    private static @NotNull VariableDeclaration requirePropertyDeclaration(
            @NotNull dev.superice.gdparser.frontend.ast.SourceFile sourceFile,
            @NotNull String propertyName
    ) {
        return sourceFile.statements().stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(property -> property.name().equals(propertyName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing property declaration " + propertyName));
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
