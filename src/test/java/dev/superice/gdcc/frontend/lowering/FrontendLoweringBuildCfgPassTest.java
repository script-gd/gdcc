package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendCfgRegion;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendIfRegion;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
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
                        
                        var ready_value: int = 1
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
                () -> assertNull(propertyContext.frontendCfgGraphOrNull()),
                () -> assertNull(propertyContext.frontendCfgRegionOrNull(propertyContext.loweringRoot())),
                () -> assertEquals(0, propertyContext.targetFunction().getBasicBlockCount()),
                () -> assertTrue(propertyContext.targetFunction().getEntryBlockId().isEmpty())
        );
    }

    @Test
    void runRejectsParameterDefaultContextsUntilTheirCompileSurfaceExists() throws Exception {
        var prepared = prepareContext(
                "build_cfg_parameter_default.gd",
                """
                        class_name BuildCfgParameterDefault
                        extends RefCounted
                        
                        func ping(value: int) -> int:
                            return value
                        """,
                Map.of("BuildCfgParameterDefault", "RuntimeBuildCfgParameterDefault")
        );
        var executableContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeBuildCfgParameterDefault",
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
                () -> new FrontendLoweringBuildCfgPass().run(prepared.context())
        );

        assertTrue(exception.getMessage().contains("parameter default"), exception.getMessage());
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
