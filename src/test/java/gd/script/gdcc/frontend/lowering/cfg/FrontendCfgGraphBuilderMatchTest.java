package gd.script.gdcc.frontend.lowering.cfg;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.GetVariantTypeItem;
import gd.script.gdcc.frontend.lowering.cfg.item.IntConstantItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MatchBindItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MatchEqualItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SequenceItem;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendMatchRegion;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Focused tests for first-batch `match` frontend CFG construction.
class FrontendCfgGraphBuilderMatchTest {
    @Test
    void buildsLiteralAndWildcardRegionWithSharedSubject() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_match_literal_wildcard.gd",
                """
                        class_name CfgMatchLiteralWildcard
                        extends RefCounted
                        
                        func ping(value: int) -> int:
                            match value:
                                1:
                                    return 10
                                _:
                                    return 0
                        """
        );

        var matchStatement = findMatch(analyzed);
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData());
        var graph = build.graph();
        var region = assertInstanceOf(FrontendMatchRegion.class, Objects.requireNonNull(build.regions().get(matchStatement)));

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertEquals(region.headerEntryId(), region.entryId()),
                () -> assertEquals(2, region.sections().size()),
                () -> assertNotEquals(region.sections().getFirst().testEntryId(), region.sections().getLast().testEntryId()),
                () -> assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(region.headerEntryId())),
                () -> assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode(region.mergeId()))
        );
        var merge = (FrontendCfgGraph.StopNode) graph.requireNode(region.mergeId());
        assertEquals(FrontendCfgGraph.StopKind.TERMINAL_MERGE, merge.kind());
    }

    @Test
    void publishesTopLevelBindSlotAndBodyHeadItem() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_match_bind.gd",
                """
                        class_name CfgMatchBind
                        extends RefCounted
                        
                        func ping(value: int) -> int:
                            match value:
                                var bound:
                                    return bound
                        """
        );

        var matchStatement = findMatch(analyzed);
        var bind = findBind(analyzed);
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData());
        var region = assertInstanceOf(FrontendMatchRegion.class, Objects.requireNonNull(build.regions().get(matchStatement)));
        var slot = Objects.requireNonNull(build.matchBindSlots().get(bind));
        var bodySequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                build.graph().requireNode(region.sections().getFirst().bodyEntryId())
        );
        var bindItem = firstMatchBind(bodySequence.items());

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors()),
                () -> assertSame(bind, slot.declaration()),
                () -> assertEquals("bound", slot.bindSlotId()),
                () -> assertEquals(GdIntType.INT.getTypeName(), slot.exposedType().getTypeName()),
                () -> assertSame(bind, bindItem.declaration()),
                () -> assertEquals("bound", bindItem.bindSlotId())
        );
    }

    @Test
    void foldsIncompatibleLiteralWithoutEvaluatingPatternOperand() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_match_fold_false.gd",
                """
                        class_name CfgMatchFoldFalse
                        extends RefCounted
                        
                        func ping(value: int) -> int:
                            match value:
                                "a":
                                    return 1
                                _:
                                    return 0
                        """
        );

        var matchStatement = findMatch(analyzed);
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData());
        var region = assertInstanceOf(FrontendMatchRegion.class, Objects.requireNonNull(build.regions().get(matchStatement)));
        var firstTest = region.sections().getFirst().testEntryId();
        var firstBody = region.sections().getFirst().bodyEntryId();
        // Incompatible constant literal folds to the next miss; the body is still published.
        assertEquals(region.sections().getLast().testEntryId(), firstTest);
        assertNotEquals(firstBody, firstTest);
        assertEquals(0, countItems(build.graph(), MatchEqualItem.class));
    }

    @Test
    void evaluatesRuntimeExpressionEvenWhenTypesAreIncompatible() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_match_runtime_side_effect.gd",
                """
                        class_name CfgMatchRuntimeSideEffect
                        extends RefCounted
                        
                        func side() -> String:
                            return "x"
                        
                        func ping(value: int) -> int:
                            match value:
                                side():
                                    return 1
                                _:
                                    return 0
                        """
        );

        var build = new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData());
        assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot().asList()::toString);
        assertTrue(countItems(build.graph(), CallItem.class) >= 1);
        assertTrue(countItems(build.graph(), gd.script.gdcc.frontend.lowering.cfg.item.BoolConstantItem.class) >= 1);
        var headerItems = sequenceItems(build.graph(), regionHeader(build, findMatch(analyzed)));
        assertEquals(0, headerItems.stream().filter(CallItem.class::isInstance).count());
    }

    @Test
    void evaluatesSecondOrPatternOnlyOnFirstMiss() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_match_or_runtime.gd",
                """
                        class_name CfgMatchOrRuntime
                        extends RefCounted
                        
                        func side() -> int:
                            return 7
                        
                        func ping(value: int) -> int:
                            match value:
                                1, side():
                                    return 1
                                _:
                                    return 0
                        """
        );

        var matchStatement = findMatch(analyzed);
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData());
        var region = assertInstanceOf(FrontendMatchRegion.class, Objects.requireNonNull(build.regions().get(matchStatement)));
        var graph = build.graph();
        var firstSequence = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(region.sections().getFirst().testEntryId()));
        var firstBranch = assertInstanceOf(FrontendCfgGraph.BranchNode.class, graph.requireNode(firstSequence.nextId()));
        assertEquals(0, firstSequence.items().stream().filter(CallItem.class::isInstance).count());
        assertEquals(0, sequenceItems(graph, region.headerEntryId()).stream().filter(CallItem.class::isInstance).count());
        assertTrue(reachableContains(graph, firstBranch.falseTargetId(), CallItem.class));
        assertFalse(reachableContains(graph, firstBranch.trueTargetId(), CallItem.class));
    }

    @Test
    void splitsStringFamilyTypeGateWithoutBinaryOr() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_match_string_name.gd",
                """
                        class_name CfgMatchStringName
                        extends RefCounted
                        
                        func ping(value) -> int:
                            match value:
                                "hello":
                                    return 1
                                _:
                                    return 0
                        """
        );

        var matchStatement = findMatch(analyzed);
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData());
        var region = assertInstanceOf(FrontendMatchRegion.class, Objects.requireNonNull(build.regions().get(matchStatement)));
        var graph = build.graph();
        assertTrue(countItems(graph, GetVariantTypeItem.class) >= 1);
        assertTrue(countItems(graph, IntConstantItem.class) >= 2);
        var firstSequence = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(region.sections().getFirst().testEntryId()));
        var firstBranch = assertInstanceOf(FrontendCfgGraph.BranchNode.class, graph.requireNode(firstSequence.nextId()));
        assertNotEquals(region.sections().getFirst().bodyEntryId(), firstBranch.falseTargetId());
        assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode(firstBranch.falseTargetId()));
    }

    @Test
    void chainsMultiPatternOrLazily() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_match_or.gd",
                """
                        class_name CfgMatchOr
                        extends RefCounted
                        
                        func ping(value: int) -> int:
                            match value:
                                1, 2:
                                    return 1
                                _:
                                    return 0
                        """
        );

        var matchStatement = findMatch(analyzed);
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData());
        var region = assertInstanceOf(FrontendMatchRegion.class, Objects.requireNonNull(build.regions().get(matchStatement)));
        var firstTest = region.sections().getFirst().testEntryId();
        var firstSequence = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, build.graph().requireNode(firstTest));
        var firstBranch = assertInstanceOf(FrontendCfgGraph.BranchNode.class, build.graph().requireNode(firstSequence.nextId()));
        assertEquals(region.sections().getFirst().bodyEntryId(), firstBranch.trueTargetId());
        assertNotEquals(region.sections().getLast().testEntryId(), firstBranch.falseTargetId());
    }

    @Test
    void keepsCatchAllWithGuardOnTheMissChain() throws Exception {
        var analyzed = analyzeFunction(
                "cfg_match_guard.gd",
                """
                        class_name CfgMatchGuard
                        extends RefCounted
                        
                        func ping(value: int) -> int:
                            match value:
                                var bound when bound > 0:
                                    return bound
                                _:
                                    return 0
                        """
        );

        var matchStatement = findMatch(analyzed);
        var build = new FrontendCfgGraphBuilder().buildExecutableBody(analyzed.function().body(), analyzed.analysisData());
        var region = assertInstanceOf(FrontendMatchRegion.class, Objects.requireNonNull(build.regions().get(matchStatement)));
        var bindSequence = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                build.graph().requireNode(region.sections().getFirst().bodyEntryId())
        );
        assertInstanceOf(MatchBindItem.class, bindSequence.items().getFirst());
        assertNotEquals(region.sections().getLast().testEntryId(), bindSequence.nextId());
    }

    private static @NotNull String regionHeader(
            @NotNull FrontendCfgGraphBuilder.ExecutableBodyBuild build,
            @NotNull MatchStatement matchStatement
    ) {
        var region = assertInstanceOf(FrontendMatchRegion.class, Objects.requireNonNull(build.regions().get(matchStatement)));
        return region.headerEntryId();
    }

    private static @NotNull List<SequenceItem> sequenceItems(
            @NotNull FrontendCfgGraph graph,
            @NotNull String nodeId
    ) {
        if (graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _)) {
            return items;
        }
        return List.of();
    }

    private static boolean reachableContains(
            @NotNull FrontendCfgGraph graph,
            @NotNull String startId,
            @NotNull Class<?> itemType
    ) {
        var seen = new java.util.HashSet<String>();
        var queue = new java.util.ArrayDeque<String>();
        queue.add(startId);
        while (!queue.isEmpty()) {
            var nodeId = queue.removeFirst();
            if (!seen.add(nodeId)) {
                continue;
            }
            switch (graph.requireNode(nodeId)) {
                case FrontendCfgGraph.SequenceNode(_, var items, var nextId) -> {
                    for (var item : items) {
                        if (itemType.isInstance(item)) {
                            return true;
                        }
                    }
                    queue.add(nextId);
                }
                case FrontendCfgGraph.BranchNode(_, _, _, var trueTargetId, var falseTargetId) -> {
                    queue.add(trueTargetId);
                    queue.add(falseTargetId);
                }
                case FrontendCfgGraph.StopNode _ -> {
                }
            }
        }
        return false;
    }

    private static @NotNull MatchStatement findMatch(@NotNull AnalyzedFunction analyzed) {
        return analyzed.function().body().statements().stream()
                .filter(MatchStatement.class::isInstance)
                .map(MatchStatement.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing match statement"));
    }

    private static @NotNull PatternBindingExpression findBind(@NotNull AnalyzedFunction analyzed) {
        var match = findMatch(analyzed);
        return (PatternBindingExpression) match.sections().getFirst().patterns().getFirst();
    }

    private static @NotNull MatchBindItem firstMatchBind(@NotNull List<?> items) {
        for (var item : items) {
            if (item instanceof MatchBindItem bindItem) {
                return bindItem;
            }
        }
        throw new AssertionError("Missing MatchBindItem");
    }

    private static int countItems(@NotNull FrontendCfgGraph graph, @NotNull Class<?> itemType) {
        var count = 0;
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (itemType.isInstance(item)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static @NotNull AnalyzedFunction analyzeFunction(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        var module = new FrontendModule(
                "test_module",
                List.of(unit),
                Map.of(classNameOf(source), "Runtime" + classNameOf(source))
        );
        var diagnostics = new DiagnosticManager();
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        var function = module.units().getFirst().ast().statements().stream()
                .filter(FunctionDeclaration.class::isInstance)
                .map(FunctionDeclaration.class::cast)
                .filter(candidate -> candidate.name().equals("ping"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing function declaration ping"));
        return new AnalyzedFunction(analysisData, diagnostics, function);
    }

    private static @NotNull String classNameOf(@NotNull String source) {
        for (var line : source.split("\n")) {
            var trimmed = line.trim();
            if (trimmed.startsWith("class_name ")) {
                return trimmed.substring("class_name ".length()).trim();
            }
        }
        throw new AssertionError("Missing class_name in source");
    }

    private record AnalyzedFunction(
            @NotNull gd.script.gdcc.frontend.sema.FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics,
            @NotNull FunctionDeclaration function
    ) {
    }
}
