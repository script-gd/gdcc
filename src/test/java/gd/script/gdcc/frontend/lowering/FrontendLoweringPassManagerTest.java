package gd.script.gdcc.frontend.lowering;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendCfgRegion;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.parser.DomLirSerializer;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import gd.script.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.CallGlobalInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;

import java.util.ArrayDeque;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendLoweringPassManagerTest {
    @Test
    void lowerIsTheOnlyDeclaredPublicMethod() {
        var publicMethodNames = Stream.of(FrontendLoweringPassManager.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();

        assertEquals(List.of("lower"), publicMethodNames);
    }

    @Test
    void lowerRunsPassesInOrderAndPreservesSharedContextState() throws Exception {
        var module = new FrontendModule("test_module", List.of());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var executionOrder = new ArrayList<String>();
        var publishedAnalysisData = new AtomicReference<FrontendAnalysisData>();
        var expectedLirModule = new LirModule("test_module", List.of());
        var manager = new FrontendLoweringPassManager(List.of(
                context -> {
                    executionOrder.add("analysis");
                    assertNull(context.analysisDataOrNull());
                    var analysisData = FrontendAnalysisData.bootstrap();
                    context.publishAnalysisData(analysisData);
                    publishedAnalysisData.set(analysisData);
                },
                context -> {
                    executionOrder.add("emit");
                    assertSame(publishedAnalysisData.get(), context.analysisDataOrNull());
                    assertFalse(context.isStopRequested());
                    context.publishLirModule(expectedLirModule);
                }
        ));

        var lowered = manager.lower(module, registry, diagnostics);

        assertSame(expectedLirModule, lowered);
        assertEquals(List.of("analysis", "emit"), executionOrder);
    }

    @Test
    void lowerStopsAfterPassRequestsStop() throws Exception {
        var module = new FrontendModule("test_module", List.of());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var diagnostics = new DiagnosticManager();
        var executionOrder = new ArrayList<String>();
        var manager = new FrontendLoweringPassManager(List.of(
                context -> {
                    executionOrder.add("first");
                    context.requestStop();
                },
                _ -> executionOrder.add("second")
        ));

        var lowered = manager.lower(module, registry, diagnostics);

        assertNull(lowered);
        assertEquals(List.of("first"), executionOrder);
    }

    @Test
    void lowerCompileReadyModuleReturnsSerializableLirModuleWithExecutableBodies() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = new FrontendLoweringPassManager().lower(
                parseModule(
                        List.of(new SourceFixture(
                                "lowering_manager_compile_ready.gd",
                                """
                                        class_name MappedOuter
                                        extends RefCounted
                                        
                                        signal changed(value: int)
                                        var count: int = 1
                                        
                                        func ping(value: int) -> int:
                                            return value
                                        
                                        class Inner:
                                            extends RefCounted
                                        
                                            func pong() -> void:
                                                pass
                                        """
                        )),
                        Map.of("MappedOuter", "RuntimeOuter")
                ),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        assertNotNull(lowered);
        assertEquals("test_module", lowered.getModuleName());
        assertFalse(diagnostics.hasErrors());
        assertEquals(List.of("RuntimeOuter", "RuntimeOuter__sub__Inner"), lowered.getClassDefs().stream().map(LirClassDef::getName).toList());

        var xml = new DomLirSerializer().serializeToString(lowered);
        assertTrue(xml.contains("name=\"RuntimeOuter\""), xml);
        assertTrue(xml.contains("name=\"RuntimeOuter__sub__Inner\""), xml);
        assertTrue(xml.contains("<signal name=\"changed\">"), xml);
        assertTrue(xml.contains("name=\"count\""), xml);
        assertTrue(xml.contains("type=\"int\""), xml);
        assertTrue(xml.contains("name=\"_field_init_count\""), xml);
        assertTrue(xml.contains("<basic_block id="), xml);
        assertTrue(xml.contains("<basic_blocks entry="), xml);
    }

    @Test
    void lowerToContextPublishesFrontendCfgGraphAndMaterializesExecutableBodies() throws Exception {
        var diagnostics = new DiagnosticManager();
        var manager = new FrontendLoweringPassManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "lowering_manager_cfg_metadata.gd",
                        """
                                class_name CfgMetadataOuter
                                extends RefCounted
                                
                                var count: int = 1
                                
                                func ping(flag: bool) -> void:
                                    if flag:
                                        pass
                                    else:
                                        return
                                """
                )),
                Map.of("CfgMetadataOuter", "RuntimeCfgMetadataOuter")
        );

        var context = manager.lowerToContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        var lowered = context.requireLirModule();
        var contexts = context.requireFunctionLoweringContexts();
        var executableContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeCfgMetadataOuter",
                "ping"
        );
        var propertyContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeCfgMetadataOuter",
                "_field_init_count"
        );
        var pingFunction = requireFunctionDeclaration(module.units().getFirst().ast(), "ping");
        var rootBlock = pingFunction.body();
        var outerIf = assertInstanceOf(IfStatement.class, rootBlock.statements().getFirst());

        assertAll(
                () -> assertFalse(diagnostics.hasErrors()),
                () -> assertTrue(executableContext.hasFrontendCfgGraph()),
                () -> assertNotNull(executableContext.frontendCfgGraphOrNull()),
                () -> assertInstanceOf(
                        FrontendCfgRegion.BlockRegion.class,
                        executableContext.requireFrontendCfgRegion(rootBlock)
                ),
                () -> assertNotNull(executableContext.frontendCfgRegionOrNull(outerIf)),
                () -> assertTrue(propertyContext.hasFrontendCfgGraph()),
                () -> assertNotNull(propertyContext.frontendCfgGraphOrNull()),
                () -> assertNull(propertyContext.frontendCfgRegionOrNull(propertyContext.loweringRoot())),
                () -> assertTrue(executableContext.targetFunction().getBasicBlockCount() > 0),
                () -> assertFalse(executableContext.targetFunction().getEntryBlockId().isEmpty()),
                () -> assertTrue(propertyContext.targetFunction().getBasicBlockCount() > 0),
                () -> assertFalse(propertyContext.targetFunction().getEntryBlockId().isEmpty())
        );

        var xml = new DomLirSerializer().serializeToString(lowered);
        assertTrue(xml.contains("<basic_block id="), xml);
        assertTrue(xml.contains("<basic_blocks entry="), xml);
    }

    @Test
    void lowerToContextHandlesSingletonBackedPropertyInitializerEndToEnd() throws Exception {
        var diagnostics = new DiagnosticManager();
        var manager = new FrontendLoweringPassManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "lowering_manager_singleton_property_init.gd",
                        """
                                class_name ManagerSingletonPropertyInit
                                extends RefCounted
                                
                                var frames: int = Engine.get_frames_drawn()
                                
                                func ping() -> int:
                                    return frames
                                """
                )),
                Map.of(
                        "ManagerSingletonPropertyInit",
                        "RuntimeManagerSingletonPropertyInit"
                )
        );

        var context = manager.lowerToContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        var propertyContext = requireContext(
                context.requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeManagerSingletonPropertyInit",
                "_field_init_frames"
        );
        var initFunction = propertyContext.targetFunction();
        var receiverLoad = requireOnlyInstruction(initFunction, LoadStaticInsn.class);
        var methodCall = requireOnlyInstruction(initFunction, CallMethodInsn.class);
        var returnInsn = requireOnlyInstruction(initFunction, ReturnInsn.class);

        assertAll(
                () -> assertFalse(diagnostics.hasErrors()),
                () -> assertTrue(propertyContext.hasFrontendCfgGraph()),
                () -> assertTrue(initFunction.getBasicBlockCount() > 0),
                () -> assertFalse(initFunction.getEntryBlockId().isEmpty()),
                () -> assertEquals("@GlobalScope", receiverLoad.className()),
                () -> assertEquals("Engine", receiverLoad.staticName()),
                () -> assertEquals("get_frames_drawn", methodCall.methodName()),
                () -> assertEquals(receiverLoad.resultId(), methodCall.objectId()),
                () -> assertEquals(methodCall.resultId(), returnInsn.returnValueId()),
                () -> assertEquals(0, countInstructions(initFunction, CallGlobalInsn.class))
        );
    }

    @Test
    void lowerToContextHandlesPreloadPropertyInitializerEndToEnd() throws Exception {
        // Class-level `var icon: Resource = preload("res://...")` rides the supported
        // property-initializer island and lowers to the ResourceLoader singleton call pair.
        var diagnostics = new DiagnosticManager();
        var manager = new FrontendLoweringPassManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "lowering_manager_preload_property_init.gd",
                        """
                                class_name ManagerPreloadPropertyInit
                                extends RefCounted
                                
                                var icon: Resource = preload("res://icon.svg")
                                
                                func ping() -> int:
                                    return 1
                                """
                )),
                Map.of(
                        "ManagerPreloadPropertyInit",
                        "RuntimeManagerPreloadPropertyInit"
                )
        );

        var context = manager.lowerToContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        var propertyContext = requireContext(
                context.requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeManagerPreloadPropertyInit",
                "_field_init_icon"
        );
        var initFunction = propertyContext.targetFunction();
        var pathLiteral = requireOnlyInstruction(initFunction, LiteralStringInsn.class);
        var receiverLoad = requireOnlyInstruction(initFunction, LoadStaticInsn.class);
        var methodCall = requireOnlyInstruction(initFunction, CallMethodInsn.class);
        var returnInsn = requireOnlyInstruction(initFunction, ReturnInsn.class);

        assertAll(
                () -> assertFalse(diagnostics.hasErrors()),
                () -> assertEquals("res://icon.svg", pathLiteral.value()),
                () -> assertEquals("@GlobalScope", receiverLoad.className()),
                () -> assertEquals("ResourceLoader", receiverLoad.staticName()),
                () -> assertEquals("load", methodCall.methodName()),
                () -> assertEquals(receiverLoad.resultId(), methodCall.objectId()),
                () -> assertEquals(
                        List.of(pathLiteral.resultId()),
                        methodCall.args().stream()
                                .map(operand -> assertInstanceOf(LirInstruction.VariableOperand.class, operand).id())
                                .toList()
                ),
                () -> assertEquals(methodCall.resultId(), returnInsn.returnValueId()),
                () -> assertEquals(0, countInstructions(initFunction, CallGlobalInsn.class))
        );
    }

    @Test
    void lowerToContextPublishesFrontendCfgGraphForExecutableBodies() throws Exception {
        var diagnostics = new DiagnosticManager();
        var manager = new FrontendLoweringPassManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "lowering_manager_frontend_cfg_graph.gd",
                        """
                                class_name StraightLineCfgOuter
                                extends RefCounted
                                
                                var ready_value: int = 1
                                
                                func ping(seed: int) -> int:
                                    pass
                                    seed + 1
                                    return seed
                                
                                func branchy(flag: bool) -> void:
                                    if flag:
                                        pass
                                """
                )),
                Map.of("StraightLineCfgOuter", "RuntimeStraightLineCfgOuter")
        );

        var context = manager.lowerToContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        var contexts = context.requireFunctionLoweringContexts();
        var straightLineContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeStraightLineCfgOuter",
                "ping"
        );
        var structuredContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeStraightLineCfgOuter",
                "branchy"
        );
        var propertyContext = requireContext(
                contexts,
                FunctionLoweringContext.Kind.PROPERTY_INIT,
                "RuntimeStraightLineCfgOuter",
                "_field_init_ready_value"
        );
        var pingFunction = requireFunctionDeclaration(module.units().getFirst().ast(), "ping");
        var rootBlock = pingFunction.body();
        var expressionStatement = assertInstanceOf(ExpressionStatement.class, rootBlock.statements().get(1));
        var graph = straightLineContext.requireFrontendCfgGraph();
        var rootRegion = assertInstanceOf(
                FrontendCfgRegion.BlockRegion.class,
                straightLineContext.requireFrontendCfgRegion(rootBlock)
        );
        var entryNode = assertInstanceOf(FrontendCfgGraph.SequenceNode.class, graph.requireNode("seq_0"));
        var stopNode = assertInstanceOf(FrontendCfgGraph.StopNode.class, graph.requireNode("stop_1"));
        var branchyFunction = requireFunctionDeclaration(module.units().getFirst().ast(), "branchy");
        var structuredBlock = branchyFunction.body();
        var structuredRootRegion = assertInstanceOf(
                FrontendCfgRegion.BlockRegion.class,
                structuredContext.requireFrontendCfgRegion(structuredBlock)
        );
        var structuredGraph = structuredContext.requireFrontendCfgGraph();
        var propertyGraph = propertyContext.requireFrontendCfgGraph();
        var propertyEntry = assertInstanceOf(
                FrontendCfgGraph.SequenceNode.class,
                propertyGraph.requireNode(propertyGraph.entryNodeId())
        );
        var propertyStop = assertInstanceOf(
                FrontendCfgGraph.StopNode.class,
                propertyGraph.requireNode(propertyEntry.nextId())
        );
        var binaryValue = assertInstanceOf(
                OpaqueExprValueItem.class,
                entryNode.items().get(3)
        );

        assertAll(
                () -> assertFalse(diagnostics.hasErrors()),
                () -> assertEquals(List.of("seq_0", "stop_1"), graph.nodeIds()),
                () -> assertEquals("seq_0", rootRegion.entryId()),
                () -> assertEquals(5, entryNode.items().size()),
                () -> assertSame(expressionStatement.expression(), binaryValue.expression()),
                () -> assertEquals(List.of("v0", "v1"), binaryValue.operandValueIds()),
                () -> assertEquals("v3", stopNode.returnValueIdOrNull()),
                () -> assertEquals(structuredGraph.entryNodeId(), structuredRootRegion.entryId()),
                () -> assertNotNull(structuredContext.frontendCfgGraphOrNull()),
                () -> assertEquals(List.of("seq_0", "stop_0"), propertyGraph.nodeIds()),
                () -> assertEquals(1, propertyEntry.items().size()),
                () -> assertEquals("v0", propertyStop.returnValueIdOrNull()),
                () -> assertNotNull(propertyContext.frontendCfgGraphOrNull()),
                () -> assertNull(propertyContext.frontendCfgRegionOrNull(propertyContext.loweringRoot())),
                () -> assertTrue(straightLineContext.targetFunction().getBasicBlockCount() > 0),
                () -> assertFalse(straightLineContext.targetFunction().getEntryBlockId().isEmpty()),
                () -> assertTrue(propertyContext.targetFunction().getBasicBlockCount() > 0),
                () -> assertFalse(propertyContext.targetFunction().getEntryBlockId().isEmpty())
        );
    }

    @Test
    void lowerToContextAllowsShortCircuitBinariesOnceCfgLoweringIsPresent() throws Exception {
        var diagnostics = new DiagnosticManager();
        var manager = new FrontendLoweringPassManager();
        var module = parseModule(
                List.of(new SourceFixture(
                        "lowering_manager_short_circuit_cfg.gd",
                        """
                                class_name ShortCircuitCfgOuter
                                extends RefCounted
                                
                                func helper(value: int) -> bool:
                                    return value > 0
                                
                                func consume(value: bool) -> bool:
                                    return value
                                
                                func ping(flag: bool, seed: int) -> bool:
                                    return consume(flag and helper(seed))
                                """
                )),
                Map.of("ShortCircuitCfgOuter", "RuntimeShortCircuitCfgOuter")
        );

        var context = manager.lowerToContext(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        var functionContext = requireContext(
                context.requireFunctionLoweringContexts(),
                FunctionLoweringContext.Kind.EXECUTABLE_BODY,
                "RuntimeShortCircuitCfgOuter",
                "ping"
        );
        var pingFunction = requireFunctionDeclaration(module.units().getFirst().ast(), "ping");
        var returnStatement = assertInstanceOf(ReturnStatement.class, pingFunction.body().statements().getFirst());
        var consumeCall = assertInstanceOf(CallExpression.class, returnStatement.value());
        var shortCircuit = assertInstanceOf(BinaryExpression.class, consumeCall.arguments().getFirst());
        var graph = functionContext.requireFrontendCfgGraph();
        var firstBranch = requireReachableBranchByConditionRoot(graph, graph.entryNodeId(), shortCircuit.left());
        var secondBranch = requireReachableBranchByConditionRoot(graph, firstBranch.trueTargetId(), shortCircuit.right());

        assertAll(
                () -> assertFalse(context.isStopRequested()),
                () -> assertFalse(diagnostics.hasErrors()),
                () -> assertSame(shortCircuit.left(), firstBranch.conditionRoot()),
                () -> assertSame(shortCircuit.right(), secondBranch.conditionRoot()),
                () -> assertTrue(graph.nodeIds().size() >= 6)
        );
    }

    @Test
    void lowerModuleWithPropertyWithoutInitializerDoesNotPublishFrontendInitShellIntoSerializedLir() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = new FrontendLoweringPassManager().lower(
                parseModule(
                        List.of(new SourceFixture(
                                "lowering_manager_no_property_initializer.gd",
                                """
                                        class_name NoInitializerOuter
                                        extends RefCounted
                                        
                                        var plain_count: int
                                        
                                        func ping() -> void:
                                            pass
                                        """
                        )),
                        Map.of("NoInitializerOuter", "RuntimeNoInitializerOuter")
                ),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        assertNotNull(lowered);
        assertFalse(diagnostics.hasErrors());

        var xml = new DomLirSerializer().serializeToString(lowered);
        assertTrue(xml.contains("name=\"plain_count\""), xml);
        assertFalse(xml.contains("init_func=\"_field_init_plain_count\""), xml);
        assertFalse(xml.contains("name=\"_field_init_plain_count\""), xml);
    }

    @Test
    void lowerToContextCompileBlockedModuleStopsBeforeCfgMetadataPublication() throws Exception {
        var diagnostics = new DiagnosticManager();
        var context = new FrontendLoweringPassManager().lowerToContext(
                parseModule(
                        List.of(new SourceFixture(
                                "lowering_manager_cfg_compile_blocked.gd",
                                """
                                        class_name LoweringManagerCfgCompileBlocked
                                        extends Node
                                        
                                        func ping(value):
                                            var camera = $Camera3D
                                        """
                        )),
                        Map.of()
                ),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        assertAll(
                () -> assertTrue(context.isStopRequested()),
                () -> assertNull(context.lirModuleOrNull()),
                () -> assertNull(context.functionLoweringContextsOrNull()),
                () -> assertTrue(diagnostics.hasErrors())
        );
    }

    @Test
    void lowerCompileBlockedModuleReturnsNullAndKeepsDiagnostics() throws Exception {
        var diagnostics = new DiagnosticManager();
        var lowered = new FrontendLoweringPassManager().lower(
                parseModule(
                        List.of(new SourceFixture(
                                "lowering_manager_compile_blocked.gd",
                                """
                                        class_name LoweringManagerCompileBlocked
                                        extends Node
                                        
                                        func ping(value):
                                            var camera = $Camera3D
                                        """
                        )),
                        Map.of()
                ),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        assertNull(lowered);
        assertTrue(diagnostics.hasErrors());
        var compileDiagnostics = diagnostics.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .toList();
        assertFalse(compileDiagnostics.isEmpty());
        assertTrue(
                compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Get-node expression")),
                () -> "Unexpected diagnostics: " + diagnostics.snapshot()
        );
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

    private static int countInstructions(
            @NotNull LirFunctionDef function,
            @NotNull Class<? extends LirInstruction> instructionType
    ) {
        return (int) allInstructions(function).stream()
                .filter(instructionType::isInstance)
                .count();
    }

    private static <T extends LirInstruction> @NotNull T requireOnlyInstruction(
            @NotNull LirFunctionDef function,
            @NotNull Class<T> instructionType
    ) {
        var matches = allInstructions(function).stream()
                .filter(instructionType::isInstance)
                .map(instructionType::cast)
                .toList();
        assertEquals(1, matches.size(), () -> "Expected exactly one " + instructionType.getSimpleName());
        return matches.getFirst();
    }

    private static @NotNull List<LirInstruction> allInstructions(@NotNull LirFunctionDef function) {
        var instructions = new ArrayList<LirInstruction>();
        for (var block : function) {
            instructions.addAll(block.getInstructions());
        }
        return instructions;
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

    private record SourceFixture(
            @NotNull String fileName,
            @NotNull String source
    ) {
    }
}
