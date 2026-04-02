package dev.superice.gdcc.frontend.lowering;

import dev.superice.gdcc.enums.GodotOperator;
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
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.insn.AssignInsn;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.GoIfInsn;
import dev.superice.gdcc.lir.insn.GotoInsn;
import dev.superice.gdcc.lir.insn.LiteralBoolInsn;
import dev.superice.gdcc.lir.insn.PackVariantInsn;
import dev.superice.gdcc.lir.insn.UnpackVariantInsn;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdBoolType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
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
                () -> assertEquals(GdIntType.INT, FrontendBodyLoweringSupport.requireSourceLocalSlotType(
                        analyzed.analysisData(),
                        local
                ))
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

    @Test
    void emitConditionBranchUsesDirectBoolValueWithoutNormalization() {
        var function = new LirFunctionDef("bool_condition");
        var entry = new LirBasicBlock("entry");

        var materialization = FrontendBodyLoweringSupport.emitConditionBranch(
                function,
                entry,
                "v0",
                GdBoolType.BOOL,
                "bb_true",
                "bb_false"
        );

        var tempVar = Objects.requireNonNull(function.getVariableById("cfg_tmp_v0"));
        var terminator = assertInstanceOf(GoIfInsn.class, entry.getTerminator());
        assertAll(
                () -> assertEquals("cfg_tmp_v0", materialization.branchInputSlotId()),
                () -> assertNull(materialization.variantSlotId()),
                () -> assertNull(materialization.boolSlotId()),
                () -> assertTrue(entry.getNonTerminatorInstructions().isEmpty()),
                () -> assertEquals("cfg_tmp_v0", terminator.conditionVarId()),
                () -> assertEquals(GdBoolType.BOOL, tempVar.type())
        );
    }

    @Test
    void emitConditionBranchUnpacksVariantAndPackThenUnpackForNonBool() {
        var variantFunction = new LirFunctionDef("variant_condition");
        var variantEntry = new LirBasicBlock("entry");
        var variantMaterialization = FrontendBodyLoweringSupport.emitConditionBranch(
                variantFunction,
                variantEntry,
                "v1",
                GdVariantType.VARIANT,
                "bb_true",
                "bb_false"
        );
        var variantUnpack = assertInstanceOf(UnpackVariantInsn.class, variantEntry.getNonTerminatorInstructions().getFirst());
        var variantTerminator = assertInstanceOf(GoIfInsn.class, variantEntry.getTerminator());

        var intFunction = new LirFunctionDef("int_condition");
        var intEntry = new LirBasicBlock("entry");
        var intMaterialization = FrontendBodyLoweringSupport.emitConditionBranch(
                intFunction,
                intEntry,
                "v2",
                GdIntType.INT,
                "bb_true",
                "bb_false"
        );
        var packInsn = assertInstanceOf(PackVariantInsn.class, intEntry.getNonTerminatorInstructions().getFirst());
        var unpackInsn = assertInstanceOf(UnpackVariantInsn.class, intEntry.getNonTerminatorInstructions().get(1));
        var intTerminator = assertInstanceOf(GoIfInsn.class, intEntry.getTerminator());

        assertAll(
                () -> assertNull(variantMaterialization.variantSlotId()),
                () -> assertEquals("cfg_cond_bool_v1", variantMaterialization.boolSlotId()),
                () -> assertEquals("cfg_cond_bool_v1", variantUnpack.resultId()),
                () -> assertEquals("cfg_tmp_v1", variantUnpack.variantId()),
                () -> assertEquals("cfg_cond_bool_v1", variantTerminator.conditionVarId()),
                () -> assertEquals("cfg_cond_variant_v2", intMaterialization.variantSlotId()),
                () -> assertEquals("cfg_cond_bool_v2", intMaterialization.boolSlotId()),
                () -> assertEquals("cfg_cond_variant_v2", packInsn.resultId()),
                () -> assertEquals("cfg_tmp_v2", packInsn.valueId()),
                () -> assertEquals("cfg_cond_bool_v2", unpackInsn.resultId()),
                () -> assertEquals("cfg_cond_variant_v2", unpackInsn.variantId()),
                () -> assertEquals("cfg_cond_bool_v2", intTerminator.conditionVarId())
        );
    }

    @Test
    void classifyOpaqueExpressionSeparatesHandleNowDeferredAndRejectedRoots() {
        var function = parseFunction(
                "body_lowering_support_opaque_policy.gd",
                """
                        class_name BodyLoweringSupportOpaquePolicy
                        extends RefCounted
                        
                        func helper(seed: int) -> int:
                            return seed
                        
                        func ping(flag: bool, left: bool, right: bool, seed: int, value):
                            var leaf = seed
                            var unary_value = -seed
                            var eager = seed + 1
                            var shorted = left and right
                            var conditional_value = seed if flag else 1
                            var call_value = helper(seed)
                            var cast_value = value as int
                            var type_test_value = value is int
                        """,
                "ping",
                Map.of("BodyLoweringSupportOpaquePolicy", "RuntimeBodyLoweringSupportOpaquePolicy")
        );

        var leaf = Objects.requireNonNull(findVariable(function.body().statements(), "leaf").value());
        var unaryValue = Objects.requireNonNull(findVariable(function.body().statements(), "unary_value").value());
        var eager = Objects.requireNonNull(findVariable(function.body().statements(), "eager").value());
        var shorted = Objects.requireNonNull(findVariable(function.body().statements(), "shorted").value());
        var conditionalValue = Objects.requireNonNull(findVariable(function.body().statements(), "conditional_value").value());
        var callValue = Objects.requireNonNull(findVariable(function.body().statements(), "call_value").value());
        var castValue = Objects.requireNonNull(findVariable(function.body().statements(), "cast_value").value());
        var typeTestValue = Objects.requireNonNull(findVariable(function.body().statements(), "type_test_value").value());

        assertAll(
                () -> assertEquals(
                        FrontendBodyLoweringSupport.OpaqueExprHandling.HANDLE_NOW,
                        FrontendBodyLoweringSupport.classifyOpaqueExpression(leaf).handling()
                ),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.OpaqueExprHandling.HANDLE_NOW,
                        FrontendBodyLoweringSupport.classifyOpaqueExpression(unaryValue).handling()
                ),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.OpaqueExprHandling.HANDLE_NOW,
                        FrontendBodyLoweringSupport.classifyOpaqueExpression(eager).handling()
                ),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.OpaqueExprHandling.REJECT,
                        FrontendBodyLoweringSupport.classifyOpaqueExpression(shorted).handling()
                ),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.OpaqueExprHandling.DEFER,
                        FrontendBodyLoweringSupport.classifyOpaqueExpression(conditionalValue).handling()
                ),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.OpaqueExprHandling.REJECT,
                        FrontendBodyLoweringSupport.classifyOpaqueExpression(callValue).handling()
                ),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.OpaqueExprHandling.DEFER,
                        FrontendBodyLoweringSupport.classifyOpaqueExpression(castValue).handling()
                ),
                () -> assertEquals(
                        FrontendBodyLoweringSupport.OpaqueExprHandling.DEFER,
                        FrontendBodyLoweringSupport.classifyOpaqueExpression(typeTestValue).handling()
                )
        );
    }

    @Test
    void shortCircuitValueMaterializationUsesBranchConstantWritesAndMergeSlotForAndOr() {
        assertAll(
                () -> assertShortCircuitValueMaterialization("and"),
                () -> assertShortCircuitValueMaterialization("or")
        );
    }

    private void assertShortCircuitValueMaterialization(@NotNull String operator) throws Exception {
        var className = "and".equals(operator)
                ? "BodyLoweringSupportShortCircuitAnd"
                : "BodyLoweringSupportShortCircuitOr";
        var analyzed = analyzeFunction(
                "body_lowering_support_short_circuit_" + operator + ".gd",
                """
                        class_name %s
                        extends RefCounted
                        
                        func consume(value: bool) -> bool:
                            return value
                        
                        func helper(seed: int) -> bool:
                            return seed > 0
                        
                        func ping(flag: bool, seed: int) -> bool:
                            return consume(flag %s helper(seed))
                        """.formatted(className, operator),
                "ping",
                Map.of(className, "Runtime" + className)
        );

        var graph = new FrontendCfgGraphBuilder()
                .buildExecutableBody(analyzed.function().body(), analyzed.analysisData())
                .graph();
        var returnStatement = assertInstanceOf(ReturnStatement.class, analyzed.function().body().statements().getFirst());
        var consumeCall = assertInstanceOf(CallExpression.class, returnStatement.value());
        assertInstanceOf(BinaryExpression.class, consumeCall.arguments().getFirst());
        var reachableValueItems = collectReachableValueItems(graph);
        var consumeItem = reachableValueItems.stream()
                .filter(CallItem.class::isInstance)
                .map(CallItem.class::cast)
                .filter(item -> item.anchor() == consumeCall)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing consume CallItem"));
        var mergedResultValueId = consumeItem.argumentValueIds().getFirst();

        var function = new LirFunctionDef("ping_" + operator);
        var entry = new LirBasicBlock("entry");
        var trueBlock = new LirBasicBlock("bb_true");
        var falseBlock = new LirBasicBlock("bb_false");
        var mergeBlock = new LirBasicBlock("bb_merge");
        function.addBasicBlock(entry);
        function.addBasicBlock(trueBlock);
        function.addBasicBlock(falseBlock);
        function.addBasicBlock(mergeBlock);
        function.setEntryBlockId("entry");
        function.createAndAddVariable("cond", GdBoolType.BOOL);
        entry.setTerminator(new GoIfInsn("cond", "bb_true", "bb_false"));

        var materialization = FrontendBodyLoweringSupport.emitShortCircuitBooleanMaterialization(
                function,
                trueBlock,
                falseBlock,
                mergedResultValueId,
                "bb_merge"
        );
        var trueLiteral = assertInstanceOf(LiteralBoolInsn.class, trueBlock.getNonTerminatorInstructions().getFirst());
        var trueAssign = assertInstanceOf(AssignInsn.class, trueBlock.getNonTerminatorInstructions().get(1));
        var falseLiteral = assertInstanceOf(LiteralBoolInsn.class, falseBlock.getNonTerminatorInstructions().getFirst());
        var falseAssign = assertInstanceOf(AssignInsn.class, falseBlock.getNonTerminatorInstructions().get(1));

        var mergeVar = Objects.requireNonNull(function.getVariableById(materialization.mergeSlotId()));
        assertAll(
                () -> assertEquals(2, trueBlock.getNonTerminatorInstructions().size()),
                () -> assertEquals(2, falseBlock.getNonTerminatorInstructions().size()),
                () -> assertEquals(materialization.trueConstantSlotId(), trueLiteral.resultId()),
                () -> assertTrue(trueLiteral.value()),
                () -> assertEquals(materialization.mergeSlotId(), trueAssign.resultId()),
                () -> assertEquals(materialization.trueConstantSlotId(), trueAssign.sourceId()),
                () -> assertEquals(materialization.falseConstantSlotId(), falseLiteral.resultId()),
                () -> assertFalse(falseLiteral.value()),
                () -> assertEquals(materialization.mergeSlotId(), falseAssign.resultId()),
                () -> assertEquals(materialization.falseConstantSlotId(), falseAssign.sourceId()),
                () -> assertEquals("bb_merge", assertInstanceOf(GotoInsn.class, trueBlock.getTerminator()).targetBbId()),
                () -> assertEquals("bb_merge", assertInstanceOf(GotoInsn.class, falseBlock.getTerminator()).targetBbId()),
                () -> assertEquals(GdBoolType.BOOL, mergeVar.type()),
                () -> assertTrue(function.getVariables().containsKey(materialization.trueConstantSlotId())),
                () -> assertTrue(function.getVariables().containsKey(materialization.falseConstantSlotId())),
                () -> assertFalse(Objects.requireNonNull(function.getBasicBlock("entry")).getInstructions().stream().anyMatch(this::isAndOrBinaryInsn)),
                () -> assertFalse(trueBlock.getInstructions().stream().anyMatch(this::isAndOrBinaryInsn)),
                () -> assertFalse(falseBlock.getInstructions().stream().anyMatch(this::isAndOrBinaryInsn)),
                () -> assertFalse(mergeBlock.getInstructions().stream().anyMatch(this::isAndOrBinaryInsn))
        );
    }

    private boolean isAndOrBinaryInsn(@NotNull Object instruction) {
        return instruction instanceof BinaryOpInsn binaryOpInsn
                && (binaryOpInsn.op() == GodotOperator.AND || binaryOpInsn.op() == GodotOperator.OR);
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

    private static @NotNull FunctionDeclaration parseFunction(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull String functionName,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        return requireFunctionDeclaration(parseModule(fileName, source, topLevelCanonicalNameMap).units().getFirst().ast(), functionName);
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
