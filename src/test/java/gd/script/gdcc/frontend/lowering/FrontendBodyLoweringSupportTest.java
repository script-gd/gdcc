package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraphBuilder;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
import gd.script.gdcc.frontend.lowering.cfg.item.DirectSlotAliasValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ValueOpItem;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
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
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void collectCfgValueMaterializationsUsesExpressionTypesForDynamicMemberLoadItems() throws Exception {
        var analyzed = analyzeFunction(
                "body_lowering_support_dynamic_member_type.gd",
                """
                        class_name BodyLoweringSupportDynamicMemberType
                        extends RefCounted
                        
                        func ping(dynamic_host) -> Variant:
                            return dynamic_host.marker
                        """,
                "ping",
                Map.of(
                        "BodyLoweringSupportDynamicMemberType",
                        "RuntimeBodyLoweringSupportDynamicMemberType"
                )
        );

        var graph = new FrontendCfgGraphBuilder()
                .buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
                .graph();
        var memberLoad = requireMemberLoadItem(graph, "marker");
        var publishedMember = Objects.requireNonNull(analyzed.analysisData().resolvedMembers().get(memberLoad.anchor()));
        var publishedExpressionType = Objects.requireNonNull(
                analyzed.analysisData().expressionTypes().get(memberLoad.anchor())
        );
        var materialization = FrontendBodyLoweringSupport.collectCfgValueMaterializations(
                graph,
                analyzed.analysisData(),
                new ClassRegistry(ExtensionApiLoader.loadDefault())
        ).get(memberLoad.resultValueId());

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertEquals(FrontendMemberResolutionStatus.DYNAMIC, publishedMember.status()),
                () -> assertNull(publishedMember.resultType()),
                () -> assertEquals(FrontendExpressionTypeStatus.DYNAMIC, publishedExpressionType.status()),
                () -> assertEquals(GdVariantType.VARIANT, publishedExpressionType.publishedType()),
                () -> assertEquals(GdVariantType.VARIANT, materialization.type()),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.CfgValueMaterializationKind.TEMP_SLOT,
                        materialization.kind()
                )
        );
    }

    @Test
    void collectCfgValueMaterializationsKeepsResolvedMemberExactResultTypeFallback() throws Exception {
        var analyzed = analyzeFunction(
                "body_lowering_support_resolved_member_fallback.gd",
                """
                        class_name BodyLoweringSupportResolvedMemberFallback
                        extends RefCounted
                        
                        var payload: int
                        
                        func ping(box: BodyLoweringSupportResolvedMemberFallback) -> int:
                            return box.payload
                        """,
                "ping",
                Map.of(
                        "BodyLoweringSupportResolvedMemberFallback",
                        "RuntimeBodyLoweringSupportResolvedMemberFallback"
                )
        );

        var graph = new FrontendCfgGraphBuilder()
                .buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
                .graph();
        var memberLoad = requireMemberLoadItem(graph, "payload");
        var publishedMember = Objects.requireNonNull(analyzed.analysisData().resolvedMembers().get(memberLoad.anchor()));
        var removedExpressionFact = Objects.requireNonNull(
                analyzed.analysisData().expressionTypes().remove(memberLoad.anchor())
        );
        var materialization = FrontendBodyLoweringSupport.collectCfgValueMaterializations(
                graph,
                analyzed.analysisData(),
                new ClassRegistry(ExtensionApiLoader.loadDefault())
        ).get(memberLoad.resultValueId());

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, publishedMember.status()),
                () -> assertEquals(GdIntType.INT, publishedMember.resultType()),
                () -> assertEquals(GdIntType.INT, removedExpressionFact.publishedType()),
                () -> assertEquals(GdIntType.INT, materialization.type())
        );
    }

    @Test
    void collectCfgValueMaterializationsFailsFastWhenDynamicMemberExpressionFactIsMissing() throws Exception {
        var analyzed = analyzeFunction(
                "body_lowering_support_dynamic_member_missing_expression_fact.gd",
                """
                        class_name BodyLoweringSupportDynamicMemberMissingExpressionFact
                        extends RefCounted
                        
                        func ping(dynamic_host) -> Variant:
                            return dynamic_host.marker
                        """,
                "ping",
                Map.of(
                        "BodyLoweringSupportDynamicMemberMissingExpressionFact",
                        "RuntimeBodyLoweringSupportDynamicMemberMissingExpressionFact"
                )
        );

        var graph = new FrontendCfgGraphBuilder()
                .buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
                .graph();
        var memberLoad = requireMemberLoadItem(graph, "marker");
        var publishedMember = Objects.requireNonNull(analyzed.analysisData().resolvedMembers().get(memberLoad.anchor()));
        var removedExpressionFact = Objects.requireNonNull(
                analyzed.analysisData().expressionTypes().remove(memberLoad.anchor())
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> FrontendBodyLoweringSupport.collectCfgValueMaterializations(
                        graph,
                        analyzed.analysisData(),
                        new ClassRegistry(ExtensionApiLoader.loadDefault())
                )
        );

        assertAll(
                () -> assertFalse(analyzed.diagnostics().hasErrors(), analyzed.diagnostics().snapshot()::toString),
                () -> assertEquals(FrontendMemberResolutionStatus.DYNAMIC, publishedMember.status()),
                () -> assertNull(publishedMember.resultType()),
                () -> assertEquals(GdVariantType.VARIANT, removedExpressionFact.publishedType()),
                () -> assertTrue(exception.getMessage().contains("DYNAMIC member"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("marker"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("expressionTypes()"), exception.getMessage())
        );
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
    void collectCfgValueMaterializationsSkipsDiscardedExactVoidCallResultSlot() throws Exception {
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
        var materializations = FrontendBodyLoweringSupport.collectCfgValueMaterializations(
                graph,
                analyzed.analysisData(),
                new ClassRegistry(ExtensionApiLoader.loadDefault())
        );

        assertAll(
                () -> assertNull(callItem.resultValueIdOrNull()),
                () -> assertFalse(callItem.hasStandaloneMaterializationSlot()),
                () -> assertNull(materializations.get(callItem.resultValueIdOrNull())),
                () -> assertTrue(materializations.values().stream().noneMatch(materialization -> materialization.type().equals(GdVoidType.VOID)))
        );
    }

    @Test
    void collectCfgValueSlotTypesFailsFastWhenVoidCallPublishesStandaloneResultValue() throws Exception {
        var analyzed = analyzeFunction(
                "body_lowering_support_leaked_void_call_value.gd",
                """
                        class_name BodyLoweringSupportLeakedVoidCallValue
                        extends RefCounted
                        
                        func ping(values: Array[int], seed: int) -> void:
                            values.push_back(seed)
                        """,
                "ping",
                Map.of(
                        "BodyLoweringSupportLeakedVoidCallValue",
                        "RuntimeBodyLoweringSupportLeakedVoidCallValue"
                )
        );

        var originalGraph = new FrontendCfgGraphBuilder()
                .buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
                .graph();
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, originalGraph.requireNode(originalGraph.entryNodeId()));
        var originalCallItem = entryNode.items().stream()
                .filter(CallItem.class::isInstance)
                .map(CallItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing CallItem"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, originalGraph.requireNode(entryNode.nextId()));
        var mutatedItems = entryNode.items().stream()
                .map(item -> item == originalCallItem
                        ? new CallItem(
                        originalCallItem.anchor(),
                        originalCallItem.callableName(),
                        originalCallItem.receiverValueIdOrNull(),
                        originalCallItem.argumentValueIds(),
                        "v_void_leak",
                        originalCallItem.writableRoutePayloadOrNull()
                )
                        : item)
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

        var exception = assertThrows(
                IllegalStateException.class,
                () -> FrontendBodyLoweringSupport.collectCfgValueSlotTypes(
                        mutatedGraph,
                        analyzed.analysisData(),
                        new ClassRegistry(ExtensionApiLoader.loadDefault())
                )
        );

        assertAll(
                () -> assertTrue(exception.getMessage().contains("v_void_leak"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("push_back"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("void"), exception.getMessage()),
                () -> assertTrue(exception.getMessage().contains("resultValueIdOrNull"), exception.getMessage())
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

    private static @NotNull MemberLoadItem requireMemberLoadItem(
            @NotNull FrontendCfgGraph graph,
            @NotNull String memberName
    ) {
        return collectReachableValueItems(graph).stream()
                .filter(MemberLoadItem.class::isInstance)
                .map(MemberLoadItem.class::cast)
                .filter(item -> item.memberName().equals(memberName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing MemberLoadItem " + memberName));
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
