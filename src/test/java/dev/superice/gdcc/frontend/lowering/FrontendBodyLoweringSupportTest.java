package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraphBuilder;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.DirectSlotAliasValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.ValueOpItem;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdcc.type.GdVoidType;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        var valueTypes = FrontendBodyLoweringSupport.collectCfgValueSlotTypes(
                graph,
                analyzed.analysisData(),
                new ClassRegistry(ExtensionApiLoader.loadDefault())
        );

        assertAll(
                () -> assertEquals(GdBoolType.BOOL, valueTypes.get(mergedResultValueId)),
                () -> assertEquals("cfg_merge_" + mergedResultValueId, FrontendBodyLoweringSupport.mergeSlotId(mergedResultValueId))
        );
    }

    @Test
    void collectCfgValueSlotTypesPublishesRealCompoundBinaryResultTypeInsteadOfFinalStoreType() throws Exception {
        var analyzed = analyzeFunction(
                "body_lowering_support_compound_variant_result.gd",
                """
                        class_name BodyLoweringSupportCompoundVariantResult
                        extends RefCounted
                        
                        func ping(seed: Variant) -> Variant:
                            var count: int = 1
                            count += seed
                            return count
                        """,
                "ping",
                Map.of("BodyLoweringSupportCompoundVariantResult", "RuntimeBodyLoweringSupportCompoundVariantResult")
        );

        var graph = new FrontendCfgGraphBuilder()
                .buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
                .graph();
        var compoundItem = collectReachableValueItems(graph).stream()
                .filter(CompoundAssignmentBinaryOpItem.class::isInstance)
                .map(CompoundAssignmentBinaryOpItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing CompoundAssignmentBinaryOpItem"));
        var valueTypes = FrontendBodyLoweringSupport.collectCfgValueSlotTypes(
                graph,
                analyzed.analysisData(),
                new ClassRegistry(ExtensionApiLoader.loadDefault())
        );

        assertEquals(GdVariantType.VARIANT, valueTypes.get(compoundItem.resultValueId()));
    }

    @Test
    void collectCfgValueMaterializationsMarksDirectSlotMutatingReceiverAsSourceSlotAlias() throws Exception {
        var analyzed = analyzeFunction(
                "body_lowering_support_direct_slot_alias.gd",
                """
                        class_name BodyLoweringSupportDirectSlotAlias
                        extends RefCounted
                        
                        func ping(values: PackedInt32Array, seed: int) -> void:
                            values.push_back(seed)
                        """,
                "ping",
                Map.of("BodyLoweringSupportDirectSlotAlias", "RuntimeBodyLoweringSupportDirectSlotAlias")
        );

        var graph = new FrontendCfgGraphBuilder()
                .buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
                .graph();
        var aliasItem = collectReachableValueItems(graph).stream()
                .filter(DirectSlotAliasValueItem.class::isInstance)
                .map(DirectSlotAliasValueItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing DirectSlotAliasValueItem"));
        var materializations = FrontendBodyLoweringSupport.collectCfgValueMaterializations(
                graph,
                analyzed.analysisData(),
                new ClassRegistry(ExtensionApiLoader.loadDefault())
        );
        var materialization = materializations.get(aliasItem.resultValueId());

        assertAll(
                () -> assertEquals(
                        FrontendBodyLoweringSupport.CfgValueMaterializationKind.SOURCE_SLOT_ALIAS,
                        materialization.kind()
                ),
                () -> assertSame(aliasItem.expression(), materialization.aliasSourceAnchorOrNull())
        );
    }

    @Test
    void collectCfgValueMaterializationsKeepsExactVoidCallTempBackedUntilPhaseC() throws Exception {
        var analyzed = analyzeFunction(
                "body_lowering_support_exact_void_call_materialization.gd",
                """
                        class_name BodyLoweringSupportExactVoidCallMaterialization
                        extends RefCounted
                        
                        func ping(values: Array[int], seed: int) -> void:
                            values.push_back(seed)
                        """,
                "ping",
                Map.of(
                        "BodyLoweringSupportExactVoidCallMaterialization",
                        "RuntimeBodyLoweringSupportExactVoidCallMaterialization"
                )
        );

        var graph = new FrontendCfgGraphBuilder()
                .buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
                .graph();
        var callItem = collectReachableValueItems(graph).stream()
                .filter(CallItem.class::isInstance)
                .map(CallItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing CallItem"));
        var materialization = java.util.Objects.requireNonNull(
                FrontendBodyLoweringSupport.collectCfgValueMaterializations(
                        graph,
                        analyzed.analysisData(),
                        new ClassRegistry(ExtensionApiLoader.loadDefault())
                ).get(callItem.resultValueId()),
                "Missing materialization for exact void call result"
        );

        assertAll(
                () -> assertEquals(GdVoidType.VOID, materialization.type()),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.CfgValueMaterializationKind.TEMP_SLOT,
                        materialization.kind()
                ),
                () -> assertNull(materialization.aliasSourceAnchorOrNull())
        );
    }

    @Test
    void collectCfgValueMaterializationsKeepsOrdinaryIdentifierAndSelfReadsTempBacked() throws Exception {
        var identifierAnalyzed = analyzeFunction(
                "body_lowering_support_identifier_read.gd",
                """
                        class_name BodyLoweringSupportIdentifierRead
                        extends RefCounted
                        
                        func ping(values: PackedInt32Array) -> PackedInt32Array:
                            return values
                        """,
                "ping",
                Map.of(
                        "BodyLoweringSupportIdentifierRead",
                        "RuntimeBodyLoweringSupportIdentifierRead"
                )
        );
        var identifierGraph = new FrontendCfgGraphBuilder()
                .buildExecutableBody(identifierAnalyzed.function().body(), identifierAnalyzed.analysisData())
                .graph();
        var identifierValue = assertInstanceOf(
                OpaqueExprValueItem.class,
                collectReachableValueItems(identifierGraph).getFirst()
        );
        var identifierMaterialization = FrontendBodyLoweringSupport.collectCfgValueMaterializations(
                identifierGraph,
                identifierAnalyzed.analysisData(),
                new ClassRegistry(ExtensionApiLoader.loadDefault())
        ).get(identifierValue.resultValueId());

        var selfAnalyzed = analyzeFunction(
                "body_lowering_support_self_read.gd",
                """
                        class_name BodyLoweringSupportSelfRead
                        extends RefCounted
                        
                        func ping() -> BodyLoweringSupportSelfRead:
                            return self
                        """,
                "ping",
                Map.of(
                        "BodyLoweringSupportSelfRead",
                        "RuntimeBodyLoweringSupportSelfRead"
                )
        );
        var selfGraph = new FrontendCfgGraphBuilder()
                .buildExecutableBody(selfAnalyzed.function().body(), selfAnalyzed.analysisData())
                .graph();
        var selfValue = assertInstanceOf(
                OpaqueExprValueItem.class,
                collectReachableValueItems(selfGraph).getFirst()
        );
        var selfMaterialization = FrontendBodyLoweringSupport.collectCfgValueMaterializations(
                selfGraph,
                selfAnalyzed.analysisData(),
                new ClassRegistry(ExtensionApiLoader.loadDefault())
        ).get(selfValue.resultValueId());

        assertAll(
                () -> assertEquals(
                        FrontendBodyLoweringSupport.CfgValueMaterializationKind.TEMP_SLOT,
                        identifierMaterialization.kind()
                ),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.CfgValueMaterializationKind.TEMP_SLOT,
                        selfMaterialization.kind()
                ),
                () -> assertNull(identifierMaterialization.aliasSourceAnchorOrNull()),
                () -> assertNull(selfMaterialization.aliasSourceAnchorOrNull())
        );
    }

    @Test
    void collectCfgValueSlotTypesFailsWithCompoundSpecificMessageWhenOperandTypesAreMissing() throws Exception {
        var analyzed = analyzeFunction(
                "body_lowering_support_compound_missing_operand_type.gd",
                """
                        class_name BodyLoweringSupportCompoundMissingOperandType
                        extends RefCounted
                        
                        func ping(seed: int) -> int:
                            var count := seed
                            count += 1
                            return count
                        """,
                "ping",
                Map.of("BodyLoweringSupportCompoundMissingOperandType", "RuntimeBodyLoweringSupportCompoundMissingOperandType")
        );

        var originalGraph = new FrontendCfgGraphBuilder()
                .buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
                .graph();
        var compoundItem = collectReachableValueItems(originalGraph).stream()
                .filter(CompoundAssignmentBinaryOpItem.class::isInstance)
                .map(CompoundAssignmentBinaryOpItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing CompoundAssignmentBinaryOpItem"));
        var syntheticGraph = new FrontendCfgGraph(
                "seq_0",
                Map.of(
                        "seq_0",
                        new FrontendCfgGraph.SequenceNode("seq_0", List.of(compoundItem), "stop_1"),
                        "stop_1",
                        new FrontendCfgGraph.StopNode("stop_1", FrontendCfgGraph.StopKind.RETURN, null)
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> FrontendBodyLoweringSupport.collectCfgValueSlotTypes(
                        syntheticGraph,
                        analyzed.analysisData(),
                        new ClassRegistry(ExtensionApiLoader.loadDefault())
                )
        );

        assertTrue(exception.getMessage().contains("Compound assignment body-lowering contract"), exception.getMessage());
        assertTrue(exception.getMessage().contains("current target"), exception.getMessage());
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
