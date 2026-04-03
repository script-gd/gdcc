package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraphBuilder;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.ValueOpItem;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendBodyLoweringSupportTest {
    @Test
    void slotNamingAndSourceLocalSlotTypeStayStable() throws Exception {
        var analyzed = analyzeFunction(
                "body_lowering_support_slots.gd",
                """
                        class_name BodyLoweringSupportSlots
                        extends RefCounted
                        
                        func ping(seed: int) -> void:
                            var local := seed
                        """,
                "ping",
                Map.of("BodyLoweringSupportSlots", "RuntimeBodyLoweringSupportSlots")
        );

        var local = findVariable(analyzed.function().body().statements(), "local");

        assertAll(
                () -> assertEquals("cfg_tmp_v7", FrontendBodyLoweringSupport.cfgTempSlotId("v7")),
                () -> assertEquals("cfg_merge_v7", FrontendBodyLoweringSupport.mergeSlotId("v7")),
                () -> assertEquals("local", FrontendBodyLoweringSupport.sourceLocalSlotId(local)),
                () -> assertEquals("cfg_cond_variant_v7", FrontendBodyLoweringSupport.conditionVariantSlotId("v7")),
                () -> assertEquals("cfg_cond_bool_v7", FrontendBodyLoweringSupport.conditionBoolSlotId("v7")),
                () -> assertEquals(
                        GdIntType.INT,
                        FrontendBodyLoweringSupport.requireSourceLocalSlotType(analyzed.analysisData(), local)
                )
        );
    }

    @Test
    void collectCfgValueSlotTypesPublishesBoolTypeForShortCircuitMergeValues() throws Exception {
        var analyzed = analyzeFunction(
                "body_lowering_support_merge_types.gd",
                """
                        class_name BodyLoweringSupportMergeTypes
                        extends RefCounted
                        
                        func consume(value: bool) -> bool:
                            return value
                        
                        func helper(seed: int) -> bool:
                            return seed > 0
                        
                        func ping(flag: bool, seed: int) -> bool:
                            return consume(flag or helper(seed))
                        """,
                "ping",
                Map.of("BodyLoweringSupportMergeTypes", "RuntimeBodyLoweringSupportMergeTypes")
        );

        var graph = new FrontendCfgGraphBuilder()
                .buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
                .graph();
        var returnStatement = assertInstanceOf(ReturnStatement.class, analyzed.function().body().statements().getFirst());
        var consumeCall = assertInstanceOf(CallExpression.class, returnStatement.value());
        var reachableValueItems = collectReachableValueItems(graph);
        var consumeItem = reachableValueItems.stream()
                .filter(CallItem.class::isInstance)
                .map(CallItem.class::cast)
                .filter(item -> item.anchor() == consumeCall)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing consume CallItem"));
        var mergedResultValueId = consumeItem.argumentValueIds().getFirst();
        var valueTypes = FrontendBodyLoweringSupport.collectCfgValueSlotTypes(graph, analyzed.analysisData());

        assertAll(
                () -> assertEquals(GdBoolType.BOOL, valueTypes.get(mergedResultValueId)),
                () -> assertEquals("cfg_merge_" + mergedResultValueId, FrontendBodyLoweringSupport.mergeSlotId(mergedResultValueId))
        );
    }

    private static @NotNull List<ValueOpItem> collectReachableValueItems(@NotNull FrontendCfgGraph graph) {
        var items = new ArrayList<ValueOpItem>();
        var visited = new LinkedHashSet<String>();
        var worklist = new ArrayDeque<String>();
        worklist.add(graph.entryNodeId());
        while (!worklist.isEmpty()) {
            var nodeId = worklist.removeFirst();
            if (!visited.add(nodeId)) {
                continue;
            }
            switch (graph.requireNode(nodeId)) {
                case FrontendCfgGraph.SequenceNode(_, var nodeItems, var nextId) -> {
                    nodeItems.stream()
                            .filter(ValueOpItem.class::isInstance)
                            .map(ValueOpItem.class::cast)
                            .forEach(items::add);
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
        return new AnalyzedFunction(module, diagnostics, analysisData, requireFunctionDeclaration(
                module.units().getFirst().ast(),
                functionName
        ));
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

    private static @NotNull VariableDeclaration findVariable(@NotNull List<?> statements, @NotNull String name) {
        return statements.stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(declaration -> declaration.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing variable declaration " + name));
    }

    private record AnalyzedFunction(
            @NotNull FrontendModule module,
            @NotNull DiagnosticManager diagnostics,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull FunctionDeclaration function
    ) {
    }
}
