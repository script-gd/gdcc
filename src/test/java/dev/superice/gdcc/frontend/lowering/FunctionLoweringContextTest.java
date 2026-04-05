package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SourceAnchorItem;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendCfgRegion;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendElifRegion;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendIfRegion;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendWhileRegion;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringAnalysisPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import dev.superice.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionLoweringContextTest {
    private static final Range SYNTHETIC_RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void frontendCfgCarrierPublishesIndependentGraphsAndAstIdentityRegionsPerFunctionContext() throws Exception {
        var prepared = prepareContext();
        var contexts = prepared.context().requireFunctionLoweringContexts();
        var pingContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeCfgCarrierOuter",
                "ping"
        );
        var pongContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeCfgCarrierOuter",
                "pong"
        );
        var propertyContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeCfgCarrierOuter",
                "_field_init_ready_value"
        );
        var pingFunction = requireFunctionDeclaration(prepared.module().units().getFirst().ast(), "ping");
        var ifStatement = (IfStatement) pingFunction.body().statements().getFirst();
        var elifClause = ifStatement.elifClauses().getFirst();
        var whileStatement = (WhileStatement) pingFunction.body().statements().getLast();

        var pingGraph = frontendCfgGraph("ping");
        var pongGraph = frontendCfgGraph("pong");
        var blockRegion = new FrontendCfgRegion.BlockRegion("ping_entry");
        var ifRegion = new FrontendIfRegion("ping_if_cond", "ping_if_then", "ping_if_false", "ping_if_merge");
        var elifRegion = new FrontendElifRegion("ping_elif_cond", "ping_elif_body", "ping_if_merge");
        var whileRegion = new FrontendWhileRegion("ping_while_cond", "ping_while_body", "ping_while_exit");

        pingContext.publishFrontendCfgGraph(pingGraph);
        pongContext.publishFrontendCfgGraph(pongGraph);
        pingContext.publishFrontendCfgRegion(pingFunction.body(), blockRegion);
        pingContext.publishFrontendCfgRegion(ifStatement, ifRegion);
        pingContext.publishFrontendCfgRegion(elifClause, elifRegion);
        pingContext.publishFrontendCfgRegion(whileStatement, whileRegion);

        var equivalentModule = parseModule(
                List.of(new SourceFixture("cfg_function_context.gd", CFG_FUNCTION_CONTEXT_SOURCE)),
                Map.of("CfgCarrierOuter", "RuntimeCfgCarrierOuter")
        );
        var equivalentPing = requireFunctionDeclaration(equivalentModule.units().getFirst().ast(), "ping");
        var equivalentIf = (IfStatement) equivalentPing.body().statements().getFirst();

        assertAll(
                () -> assertSame(pingGraph, pingContext.requireFrontendCfgGraph()),
                () -> assertSame(pongGraph, pongContext.requireFrontendCfgGraph()),
                () -> assertTrue(pingContext.hasFrontendCfgGraph()),
                () -> assertTrue(pingContext.hasFrontendCfgRegion(ifStatement)),
                () -> assertSame(blockRegion, pingContext.requireFrontendCfgRegion(pingFunction.body())),
                () -> assertSame(ifRegion, pingContext.requireFrontendCfgRegion(ifStatement)),
                () -> assertSame(elifRegion, pingContext.requireFrontendCfgRegion(elifClause)),
                () -> assertSame(whileRegion, pingContext.requireFrontendCfgRegion(whileStatement)),
                () -> assertEquals("ping_if_cond", ifRegion.entryId()),
                () -> assertEquals("ping_elif_cond", elifRegion.entryId()),
                () -> assertEquals("ping_while_cond", whileRegion.entryId()),
                () -> assertNull(propertyContext.frontendCfgGraphOrNull()),
                () -> assertNull(propertyContext.frontendCfgRegionOrNull(propertyContext.loweringRoot())),
                () -> assertNull(pongContext.frontendCfgRegionOrNull(ifStatement)),
                () -> assertNull(pingContext.frontendCfgRegionOrNull(equivalentIf)),
                () -> assertThrows(IllegalStateException.class, propertyContext::requireFrontendCfgGraph),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> propertyContext.requireFrontendCfgRegion(propertyContext.loweringRoot())
                )
        );
    }

    @Test
    void frontendCfgPublicationRejectsDuplicateGraphAndDuplicateRegion() throws Exception {
        var prepared = prepareContext();
        var pingContext = requireContext(
                prepared.context().requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeCfgCarrierOuter",
                "ping"
        );
        var pingFunction = requireFunctionDeclaration(prepared.module().units().getFirst().ast(), "ping");
        var ifStatement = (IfStatement) pingFunction.body().statements().getFirst();

        pingContext.publishFrontendCfgGraph(frontendCfgGraph("ping"));
        pingContext.publishFrontendCfgRegion(ifStatement, new FrontendIfRegion(
                "ping_if_cond",
                "ping_if_then",
                "ping_if_false",
                "ping_if_merge"
        ));

        var duplicateGraph = assertThrows(
                IllegalStateException.class,
                () -> pingContext.publishFrontendCfgGraph(frontendCfgGraph("duplicate"))
        );
        var duplicateRegion = assertThrows(
                IllegalStateException.class,
                () -> pingContext.publishFrontendCfgRegion(ifStatement, new FrontendCfgRegion.BlockRegion("duplicate_entry"))
        );

        assertAll(
                () -> assertTrue(duplicateGraph.getMessage().contains("already been published")),
                () -> assertTrue(duplicateRegion.getMessage().contains("already been published")),
                () -> assertFalse(prepared.diagnostics().hasErrors())
        );
    }

    private static @NotNull FrontendCfgGraph frontendCfgGraph(@NotNull String prefix) {
        var entryId = prefix + "_entry";
        var branchId = prefix + "_branch";
        var stopId = prefix + "_stop";
        var conditionExpression = new IdentifierExpression(prefix + "_flag", SYNTHETIC_RANGE);
        var nodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        nodes.put(entryId, new FrontendCfgGraph.SequenceNode(
                entryId,
                List.of(
                        new SourceAnchorItem(new PassStatement(SYNTHETIC_RANGE)),
                        new OpaqueExprValueItem(conditionExpression, prefix + "_v0")
                ),
                branchId
        ));
        nodes.put(branchId, new FrontendCfgGraph.BranchNode(
                branchId,
                conditionExpression,
                prefix + "_v0",
                stopId,
                stopId
        ));
        nodes.put(stopId, new FrontendCfgGraph.StopNode(stopId, FrontendCfgGraph.StopKind.RETURN, null));
        return new FrontendCfgGraph(entryId, nodes);
    }

    private static @NotNull PreparedContext prepareContext() throws Exception {
        var diagnostics = new DiagnosticManager();
        var module = parseModule(
                List.of(new SourceFixture("cfg_function_context.gd", CFG_FUNCTION_CONTEXT_SOURCE)),
                Map.of("CfgCarrierOuter", "RuntimeCfgCarrierOuter")
        );
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

    private static final @NotNull String CFG_FUNCTION_CONTEXT_SOURCE = """
            class_name CfgCarrierOuter
            extends RefCounted
            
            var ready_value: int = 1
            
            func ping(flag: bool, other: bool) -> void:
                if flag:
                    pass
                elif other:
                    pass
                else:
                    pass
                while other:
                    pass
            
            func pong() -> void:
                pass
            """;

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
