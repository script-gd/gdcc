package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.sema.analyzer.FrontendCompileCheckAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendAnnotationUsageAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendLoopControlFlowAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendTypeCheckAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendVirtualOverrideAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendVariableAnalyzer;
import gd.script.gdcc.frontend.sema.patch.FrontendOwnerPatch;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionHeader;
import gd.script.gdcc.gdextension.ExtensionSingleton;
import gd.script.gdcc.lir.LirClassDef;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassNameStatement;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.scope.resolver.ScopeResolvedMethod;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.scope.resolver.ScopeTypeResolver;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendSemanticAnalyzerFrameworkTest {
    private static final Range SYNTHETIC_RANGE = new Range(
            0,
            1,
            new Point(0, 0),
            new Point(0, 1)
    );

    @Test
    void analyzeBootstrapsSideTablesAndCollectsSemanticallyRelevantAnnotations() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "annotated_player.gd"), """
                @tool
                class_name AnnotatedPlayer
                extends Node
                
                @export var hp: int = 1
                
                @rpc("authority")
                func ping(value):
                    var local := value
                
                @warning_ignore_start("unused_variable")
                var tmp := 1
                
                @warning_ignore_restore("unused_variable")
                var keep := 2
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();

        var result = analyzeModule(analyzer, "test_module", List.of(unit), registry, diagnostics);
        var topLevelStatements = unit.ast().statements();
        var hpProperty = findVariable(topLevelStatements, "hp");
        var tmpProperty = findVariable(topLevelStatements, "tmp");
        var keepProperty = findVariable(topLevelStatements, "keep");
        var pingFunction = findFunction(topLevelStatements, "ping");

        assertEquals(1, topLevelClassDefs(result.moduleSkeleton()).size());
        assertEquals("AnnotatedPlayer", topLevelClassDefs(result.moduleSkeleton()).getFirst().getName());
        assertEquals(List.of("tool"), annotationNames(result.annotationsByAst().get(unit.ast())));
        assertEquals(List.of("export"), annotationNames(result.annotationsByAst().get(hpProperty)));
        assertEquals(List.of("rpc"), annotationNames(result.annotationsByAst().get(pingFunction)));
        assertNull(result.annotationsByAst().get(tmpProperty));
        assertNull(result.annotationsByAst().get(keepProperty));

        assertFalse(result.scopesByAst().isEmpty());
        assertTrue(result.scopesByAst().containsKey(unit.ast()));
        assertTrue(result.scopesByAst().containsKey(pingFunction));
        assertTrue(result.scopesByAst().containsKey(pingFunction.body()));
        assertTrue(result.scopesByAst().containsKey(pingFunction.parameters().getFirst()));
        var pingScope = assertInstanceOf(CallableScope.class, result.scopesByAst().get(pingFunction));
        var pingBodyScope = assertInstanceOf(BlockScope.class, result.scopesByAst().get(pingFunction.body()));
        var parameterBinding = pingScope.resolveValue("value");
        assertNotNull(parameterBinding);
        assertEquals(GdVariantType.VARIANT, parameterBinding.type());
        assertEquals(ScopeValueKind.PARAMETER, parameterBinding.kind());
        assertSame(pingFunction.parameters().getFirst(), parameterBinding.declaration());
        var localBinding = pingBodyScope.resolveValue("local");
        assertNotNull(localBinding);
        assertEquals(GdVariantType.VARIANT, localBinding.type());
        assertEquals(ScopeValueKind.LOCAL, localBinding.kind());
        assertEquals(GdVariantType.VARIANT, result.slotTypes().get(pingFunction.parameters().getFirst()));
        assertEquals(
                GdVariantType.VARIANT,
                result.slotTypes().get(findVariable(pingFunction.body().statements(), "local"))
        );
        var localInitializerUseSite = assertInstanceOf(
                IdentifierExpression.class,
                findVariable(pingFunction.body().statements(), "local").value()
        );
        var localInitializerBinding = result.symbolBindings().get(localInitializerUseSite);
        assertNotNull(localInitializerBinding);
        assertEquals(FrontendBindingKind.PARAMETER, localInitializerBinding.kind());
        assertSame(pingFunction.parameters().getFirst(), localInitializerBinding.declarationSite());
        assertFalse(result.symbolBindings().isEmpty());
        var localInitializerType = result.expressionTypes().get(localInitializerUseSite);
        assertNotNull(localInitializerType);
        assertEquals("Variant", localInitializerType.publishedType().getTypeName());
        assertTrue(result.resolvedMembers().isEmpty());
        assertTrue(result.resolvedCalls().isEmpty());
        assertNotNull(result.diagnostics());
        var typeHintDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.type_hint"))
                .toList();
        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertTrue(result.moduleSkeleton().diagnostics().isEmpty());
        assertEquals(2, typeHintDiagnostics.size());
        assertTrue(typeHintDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("tmp")
                        && diagnostic.message().contains(": int")
        ));
        assertTrue(typeHintDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("keep")
                        && diagnostic.message().contains(": int")
        ));
    }

    @Test
    void analyzeAndAnalyzeForCompileAcceptFrontendModuleWhileKeepingCompileGateSplitStable() throws Exception {
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", "module_compile_split.gd"), """
                class_name ModuleCompileSplit
                extends Node
                
                static var shared := 1
                
                func ping():
                    pass
                """, new DiagnosticManager());
        var module = new FrontendModule(
                "test_module",
                List.of(unit),
                java.util.Map.of("ModuleCompileSplit", "RuntimeModuleCompileSplit")
        );

        var sharedDiagnostics = new DiagnosticManager();
        var sharedResult = new FrontendSemanticAnalyzer().analyze(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                sharedDiagnostics
        );

        assertEquals("test_module", sharedResult.moduleSkeleton().moduleName());
        assertEquals(List.of(unit), sourceUnits(sharedResult.moduleSkeleton()));
        assertFalse(sharedResult.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.compile_check")
        ));

        var compileDiagnostics = new DiagnosticManager();
        var compileResult = new FrontendSemanticAnalyzer().analyzeForCompile(
                module,
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                compileDiagnostics
        );

        // Static var declarations are compile-ready: the compile pass adds no gate diagnostic on
        // top of the shared-semantic result for this module.
        assertFalse(compileResult.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.compile_check")
        ));
        assertFalse(compileResult.diagnostics().hasErrors());
    }

    @Test
    void analyzeCollectsNestedBlockAnnotationsAndStillIgnoresRegionAnnotations() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "nested_annotations.gd"), """
                class_name NestedAnnotations
                extends Node
                
                func ping(value):
                    @warning_ignore("unused_variable")
                    var inner := 1
                    @warning_ignore_start("unused_variable")
                    var region_ignored := 2
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();

        var result = analyzeModule(analyzer, "test_module", List.of(unit), registry, diagnostics);
        var pingFunction = findFunction(unit.ast().statements(), "ping");
        var bodyStatements = pingFunction.body().statements();
        var innerVariable = findVariable(bodyStatements, "inner");
        var regionIgnoredVariable = findVariable(bodyStatements, "region_ignored");

        assertEquals(diagnostics.snapshot(), result.diagnostics());
        assertEquals(List.of("warning_ignore"), annotationNames(result.annotationsByAst().get(innerVariable)));
        assertNull(result.annotationsByAst().get(regionIgnoredVariable));
    }

    @Test
    void analyzeKeepsPlainVarDynamicFallbackSeparateFromTypedNodeExactRoute() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "exact_call_route_split.gd"), """
                class_name ExactCallRouteSplit
                extends Node
                
                func dynamic_route():
                    var holder = Node.new()
                    holder.add_child(Node.new())
                
                func exact_route():
                    var holder: Node = Node.new()
                    holder.add_child(Node.new())
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = analyzeModule("test_module", List.of(unit), registry, diagnostics);
        var dynamicFunction = findFunction(unit.ast().statements(), "dynamic_route");
        var exactFunction = findFunction(unit.ast().statements(), "exact_route");
        var dynamicStep = findNode(dynamicFunction, AttributeCallStep.class, step -> step.name().equals("add_child"));
        var exactStep = findNode(exactFunction, AttributeCallStep.class, step -> step.name().equals("add_child"));
        var dynamicCall = Objects.requireNonNull(result.resolvedCalls().get(dynamicStep));
        var exactCall = Objects.requireNonNull(result.resolvedCalls().get(exactStep));
        var exactBoundary = exactCall.exactCallableBoundary();

        assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.DYNAMIC, dynamicCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.DYNAMIC_FALLBACK, dynamicCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, dynamicCall.receiverKind()),
                () -> assertNull(dynamicCall.exactCallableBoundary()),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, exactCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, exactCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, exactCall.receiverKind()),
                () -> assertEquals(new GdObjectType("Node"), exactCall.receiverType()),
                // `argumentTypes()` stays a call-site snapshot, not the selected callable signature.
                () -> assertEquals(List.of(new GdObjectType("Node")), exactCall.argumentTypes()),
                () -> assertNotNull(exactBoundary),
                () -> assertEquals(
                        List.of(new GdObjectType("Node"), GdBoolType.BOOL, GdIntType.INT),
                        exactBoundary.fixedParameterTypes()
                ),
                () -> assertFalse(exactBoundary.isVararg()),
                () -> assertFalse(exactCall.declarationSite() instanceof ScopeResolvedMethod),
                () -> assertTrue(diagnosticsByCategory(result.diagnostics(), "sema.call_resolution").isEmpty())
        );
    }

    @Test
    void analyzePublishesExactCallableBoundaryForExtensionMetadataFamilies() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "exact_call_metadata_families.gd"), """
                class_name ExactCallMetadataFamilies
                extends RefCounted
                
                func enum_case(holder: Node, child: Node):
                    holder.add_child(child)
                
                func bitfield_case(holder: Node):
                    holder.set_process_thread_messages(0)
                
                func typedarray_case(mesh: ArrayMesh, arrays: Array):
                    mesh.add_surface_from_arrays(0, arrays)
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = analyzeModule("test_module", List.of(unit), registry, diagnostics);
        var enumFunction = findFunction(unit.ast().statements(), "enum_case");
        var bitfieldFunction = findFunction(unit.ast().statements(), "bitfield_case");
        var typedarrayFunction = findFunction(unit.ast().statements(), "typedarray_case");
        var enumStep = findNode(enumFunction, AttributeCallStep.class, step -> step.name().equals("add_child"));
        var bitfieldStep = findNode(
                bitfieldFunction,
                AttributeCallStep.class,
                step -> step.name().equals("set_process_thread_messages")
        );
        var typedarrayStep = findNode(
                typedarrayFunction,
                AttributeCallStep.class,
                step -> step.name().equals("add_surface_from_arrays")
        );
        var enumCall = Objects.requireNonNull(result.resolvedCalls().get(enumStep));
        var bitfieldCall = Objects.requireNonNull(result.resolvedCalls().get(bitfieldStep));
        var typedarrayCall = Objects.requireNonNull(result.resolvedCalls().get(typedarrayStep));
        var variantArray = new GdArrayType(GdVariantType.VARIANT);
        var variantDictionary = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);

        assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, enumCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, enumCall.callKind()),
                () -> assertEquals(new GdObjectType("Node"), enumCall.receiverType()),
                () -> assertEquals(List.of(new GdObjectType("Node")), enumCall.argumentTypes()),
                () -> assertEquals(
                        List.of(new GdObjectType("Node"), GdBoolType.BOOL, GdIntType.INT),
                        Objects.requireNonNull(enumCall.exactCallableBoundary()).fixedParameterTypes()
                ),
                () -> assertFalse(Objects.requireNonNull(enumCall.exactCallableBoundary()).isVararg()),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, bitfieldCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, bitfieldCall.callKind()),
                () -> assertEquals(new GdObjectType("Node"), bitfieldCall.receiverType()),
                () -> assertEquals(List.of(GdIntType.INT), bitfieldCall.argumentTypes()),
                () -> assertEquals(
                        List.of(GdIntType.INT),
                        Objects.requireNonNull(bitfieldCall.exactCallableBoundary()).fixedParameterTypes()
                ),
                () -> assertFalse(Objects.requireNonNull(bitfieldCall.exactCallableBoundary()).isVararg()),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, typedarrayCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, typedarrayCall.callKind()),
                () -> assertEquals(new GdObjectType("ArrayMesh"), typedarrayCall.receiverType()),
                // The call site only passes two arguments, but the published exact boundary still
                // carries the full fixed signature selected from extension metadata.
                () -> assertEquals(List.of(GdIntType.INT, variantArray), typedarrayCall.argumentTypes()),
                () -> assertEquals(
                        List.of(
                                GdIntType.INT,
                                variantArray,
                                new GdArrayType(variantArray),
                                variantDictionary,
                                GdIntType.INT
                        ),
                        Objects.requireNonNull(typedarrayCall.exactCallableBoundary()).fixedParameterTypes()
                ),
                () -> assertFalse(Objects.requireNonNull(typedarrayCall.exactCallableBoundary()).isVararg()),
                () -> assertTrue(diagnosticsByCategory(result.diagnostics(), "sema.call_resolution").isEmpty())
        );
    }

    @Test
    void analyzePublishesTopBindingsForChainHeadsAndParameterDefaultIslandFacts() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "framework_top_binding.gd"), """
                class_name FrameworkTopBinding
                extends Node
                
                func helper():
                    pass
                
                func get_player():
                    pass
                
                func ping(player, i, seed = helper()):
                    player.hp
                    player.move(i + 1)
                    var f = func():
                        return player
                    return get_player().hp
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var result = analyzeModule("test_module", List.of(unit), registry, diagnostics);

        var pingFunction = findFunction(unit.ast().statements(), "ping");
        var headRead = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst());
        var stepCall = assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1));
        var lambdaHolder = findVariable(pingFunction.body().statements(), "f");
        var outerReturn = assertInstanceOf(ReturnStatement.class, pingFunction.body().statements().getLast());
        var moveStep = findNode(stepCall, AttributeCallStep.class, step -> step.name().equals("move"));
        var getPlayerCall = findNode(
                outerReturn,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifierExpression
                        && identifierExpression.name().equals("get_player")
        );

        assertEquals(
                FrontendBindingKind.PARAMETER,
                Objects.requireNonNull(
                        result.symbolBindings().get(findNode(headRead, IdentifierExpression.class, id -> id.name().equals("player")))
                ).kind()
        );
        assertEquals(
                FrontendBindingKind.PARAMETER,
                Objects.requireNonNull(
                        result.symbolBindings().get(findNode(stepCall, IdentifierExpression.class, id -> id.name().equals("player")))
                ).kind()
        );
        assertEquals(
                FrontendBindingKind.PARAMETER,
                Objects.requireNonNull(
                        result.symbolBindings().get(findNode(stepCall, IdentifierExpression.class, id -> id.name().equals("i")))
                ).kind()
        );
        assertEquals(
                FrontendBindingKind.LITERAL,
                Objects.requireNonNull(result.symbolBindings().get(findLiteral(stepCall, "1"))).kind()
        );
        assertEquals(
                FrontendBindingKind.METHOD,
                Objects.requireNonNull(
                        result.symbolBindings().get(findNode(outerReturn, IdentifierExpression.class, id -> id.name().equals("get_player")))
                ).kind()
        );

        // The parameter-default island analyzes `helper()` through the ordinary owner pipeline:
        // the callee identifier binds as METHOD and the call resolves, publishing into the same
        // AST-identity side tables as body facts.
        var helperUseSite = findNode(
                pingFunction.parameters().getLast().defaultValue(),
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("helper")
        );
        var helperDefaultCall = findNode(
                pingFunction.parameters().getLast().defaultValue(),
                CallExpression.class,
                candidate -> true
        );
        assertEquals(
                FrontendBindingKind.METHOD,
                Objects.requireNonNull(result.symbolBindings().get(helperUseSite)).kind()
        );
        assertEquals(
                FrontendCallResolutionStatus.RESOLVED,
                Objects.requireNonNull(result.resolvedCalls().get(helperDefaultCall)).status()
        );
        assertEquals("helper", Objects.requireNonNull(result.resolvedCalls().get(helperDefaultCall)).callableName());

        var lambdaPlayerUseSite = findNode(
                lambdaHolder.value(),
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("player")
        );
        // Lambda bodies are resolved through their own nested suite: the captured use site binds
        // to the lambda's CAPTURE slot instead of staying unpublished.
        assertEquals(
                FrontendBindingKind.CAPTURE,
                Objects.requireNonNull(result.symbolBindings().get(lambdaPlayerUseSite)).kind()
        );
        assertEquals("move", Objects.requireNonNull(result.resolvedCalls().get(moveStep)).callableName());
        assertEquals(
                FrontendCallResolutionStatus.RESOLVED,
                Objects.requireNonNull(result.resolvedCalls().get(getPlayerCall)).status()
        );
        assertEquals("get_player", Objects.requireNonNull(result.resolvedCalls().get(getPlayerCall)).callableName());

        // The accepted default carries its deterministic synthetic name on the parameter metadata.
        var pingFunctionDef = result.moduleSkeleton().allClassDefs().stream()
                .filter(classDef -> classDef.getName().equals("FrameworkTopBinding"))
                .flatMap(classDef -> classDef.getFunctions().stream())
                .filter(function -> function.getName().equals("ping"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ping function skeleton not found"));
        assertEquals(
                "_default_ping$seed",
                pingFunctionDef.getParameter("seed").getDefaultValueFunc()
        );

        assertEquals(7, result.symbolBindings().size());
        assertEquals(2, result.resolvedMembers().size());
        assertEquals(3, result.resolvedCalls().size());
        assertEquals(
                0,
                result.diagnostics().asList().stream()
                        .filter(diagnostic -> diagnostic.category().equals("sema.unsupported_binding_subtree"))
                        .count()
        );
        assertEquals(
                0,
                result.diagnostics().asList().stream()
                        .filter(diagnostic -> diagnostic.category().equals("sema.deferred_chain_resolution"))
                        .count()
        );
        assertEquals(
                0,
                result.diagnostics().asList().stream()
                        .filter(diagnostic -> diagnostic.category().equals("sema.unsupported_chain_route"))
                        .count()
        );
        assertEquals(
                0,
                result.diagnostics().asList().stream()
                        .filter(diagnostic -> diagnostic.category().equals("sema.unsupported_expression_route"))
                        .count()
        );
    }

    @Test
    void analyzePublishesStablePropertyInitializerFactsAcrossBodyPhases() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "framework_property_initializer_facts.gd"), """
                class_name FrameworkPropertyInitializerFacts
                extends RefCounted
                
                class Handle:
                    func read() -> int:
                        return 1
                
                class Worker:
                    var handle: Handle = Handle.new()
                
                    static func build() -> Worker:
                        return Worker.new()
                
                var ready_value := Worker.build().handle.read()
                const Alias = Worker.build()
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var result = analyzeModule("test_module", List.of(unit), registry, diagnostics);

        var readyValue = findVariable(unit.ast().statements(), "ready_value");
        var readyInitializer = assertInstanceOf(AttributeExpression.class, readyValue.value());
        var readyWorkerHead = findNode(
                readyInitializer,
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("Worker")
        );
        var handleStep = findNode(
                readyInitializer,
                dev.superice.gdparser.frontend.ast.AttributePropertyStep.class,
                step -> step.name().equals("handle")
        );
        var readStep = findNode(
                readyInitializer,
                dev.superice.gdparser.frontend.ast.AttributeCallStep.class,
                step -> step.name().equals("read")
        );
        var aliasDeclaration = findVariable(unit.ast().statements(), "Alias");
        var aliasInitializer = assertInstanceOf(AttributeExpression.class, aliasDeclaration.value());
        var aliasWorkerHead = findNode(
                aliasInitializer,
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("Worker")
        );
        var aliasBuildStep = findNode(
                aliasInitializer,
                dev.superice.gdparser.frontend.ast.AttributeCallStep.class,
                step -> step.name().equals("build")
        );

        assertEquals(FrontendBindingKind.TYPE_META, Objects.requireNonNull(result.symbolBindings().get(readyWorkerHead)).kind());
        assertEquals(FrontendMemberResolutionStatus.RESOLVED, Objects.requireNonNull(result.resolvedMembers().get(handleStep)).status());
        assertEquals(FrontendCallResolutionStatus.RESOLVED, Objects.requireNonNull(result.resolvedCalls().get(readStep)).status());
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(result.expressionTypes().get(readyInitializer)).status()
        );
        assertEquals("int", result.expressionTypes().get(readyInitializer).publishedType().getTypeName());

        assertNull(result.symbolBindings().get(aliasWorkerHead));
        assertNull(result.resolvedCalls().get(aliasBuildStep));
        assertNull(result.expressionTypes().get(aliasInitializer));
        var typeHintDiagnostics = result.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.type_hint"))
                .toList();
        assertEquals(1, typeHintDiagnostics.size());
        assertTrue(typeHintDiagnostics.getFirst().message().contains("ready_value"));
        assertTrue(typeHintDiagnostics.getFirst().message().contains(": int"));
        assertTrue(result.diagnostics().asList().stream().noneMatch(diagnostic ->
                diagnostic.category().equals("sema.type_check")
        ));
    }

    @Test
    void analyzeDoesNotInventParseDiagnosticsForManualUnitsOutsideSharedManagerPipeline() throws Exception {
        var parserService = new GdScriptParserService();
        var parsed = parserService.parseUnit(Path.of("tmp", "manual_unit.gd"), """
                class_name ManualUnit
                extends Node
                
                func ping():
                    pass
                """, new DiagnosticManager());
        var unit = new FrontendSourceUnit(
                parsed.path(),
                parsed.source(),
                parsed.ast()
        );
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();
        var diagnostics = new DiagnosticManager();

        var result = analyzeModule(analyzer, "test_module", List.of(unit), registry, diagnostics);

        assertEquals(1, topLevelClassDefs(result.moduleSkeleton()).size());
        assertTrue(diagnostics.isEmpty());
        assertTrue(result.moduleSkeleton().diagnostics().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    /// Anchors the phase-boundary snapshot rule: the analysis data captures the shared
    /// manager state once, and later manager mutations must not retroactively rewrite either
    /// `FrontendAnalysisData` or its nested `FrontendModuleSkeleton`.
    @Test
    void analyzePublishesStableDiagnosticsSnapshotEvenIfManagerChangesLater() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "stable_snapshot.gd"), """
                class_name StableSnapshot
                extends Node
                
                func _ready(
                    pass
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();

        var result = analyzeModule(analyzer, "test_module", List.of(unit), registry, diagnostics);
        var parseSnapshot = diagnostics.snapshot();
        var beforeMutation = result.diagnostics();
        diagnostics.error("sema.synthetic", "late diagnostic", unit.path(), null);

        assertFalse(beforeMutation.isEmpty());
        assertEquals(parseSnapshot.asList(), beforeMutation.asList());
        assertEquals(beforeMutation, result.diagnostics());
        assertEquals(beforeMutation, result.moduleSkeleton().diagnostics());
        assertEquals(beforeMutation.size() + 1, diagnostics.snapshot().size());
    }

    @Test
    void activeDependencyConstructorUsesDefaultSuiteResolverAndPreservesPhaseBoundaries() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "variable_phase_probe.gd"), """
                class_name VariablePhaseProbe
                extends Node
                
                func ping(value):
                    pass
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var probeScopeAnalyzer = new RecordingScopeAnalyzer();
        var probeVariableAnalyzer = new RecordingVariableAnalyzer();
        var probeAnnotationUsageAnalyzer = new RecordingAnnotationUsageAnalyzer();
        var probeVirtualOverrideAnalyzer = new RecordingVirtualOverrideAnalyzer();
        var probeTypeCheckAnalyzer = new RecordingTypeCheckAnalyzer();
        var probeLoopControlFlowAnalyzer = new RecordingLoopControlFlowAnalyzer();
        var analyzer = new FrontendSemanticAnalyzer(
                new FrontendClassSkeletonBuilder(),
                probeScopeAnalyzer,
                probeVariableAnalyzer,
                probeAnnotationUsageAnalyzer,
                probeVirtualOverrideAnalyzer,
                probeTypeCheckAnalyzer,
                probeLoopControlFlowAnalyzer,
                new FrontendCompileCheckAnalyzer()
        );

        var result = analyzeModule(
                analyzer,
                "test_module",
                List.of(unit),
                registry,
                diagnostics
        );

        assertTrue(probeScopeAnalyzer.invoked);
        assertTrue(probeScopeAnalyzer.moduleSkeletonPublished);
        assertTrue(probeScopeAnalyzer.preScopeDiagnosticsMatchedManager);
        assertTrue(probeVariableAnalyzer.invoked);
        assertTrue(probeVariableAnalyzer.scopeBoundaryPublished);
        assertTrue(probeVariableAnalyzer.preVariableDiagnosticsMatchedManager);
        assertTrue(probeAnnotationUsageAnalyzer.invoked);
        assertTrue(probeAnnotationUsageAnalyzer.varTypeBoundaryPublished);
        assertTrue(probeAnnotationUsageAnalyzer.preAnnotationUsageDiagnosticsMatchedManager);
        assertTrue(probeAnnotationUsageAnalyzer.stableAnnotationsReferencePreserved);
        assertTrue(probeVirtualOverrideAnalyzer.invoked);
        assertTrue(probeVirtualOverrideAnalyzer.annotationUsageBoundaryPublished);
        assertTrue(probeVirtualOverrideAnalyzer.preVirtualOverrideDiagnosticsMatchedManager);
        assertTrue(probeTypeCheckAnalyzer.invoked);
        assertTrue(probeTypeCheckAnalyzer.virtualOverrideBoundaryPublished);
        assertTrue(probeTypeCheckAnalyzer.preTypeCheckDiagnosticsMatchedManager);
        assertTrue(probeTypeCheckAnalyzer.stableExpressionTypesReferencePreserved);
        assertTrue(probeTypeCheckAnalyzer.expressionTypesRemainPublishedAfterTypeCheck);
        assertTrue(probeLoopControlFlowAnalyzer.invoked);
        assertTrue(probeLoopControlFlowAnalyzer.typeCheckBoundaryPublished);
        assertTrue(probeLoopControlFlowAnalyzer.preLoopControlFlowDiagnosticsMatchedManager);
        assertEquals(probeScopeAnalyzer.preScopeDiagnostics.size() + 1, probeVariableAnalyzer.preVariableDiagnostics.size());
        assertTrue(probeVariableAnalyzer.preVariableDiagnostics.asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.scope_phase_probe")
        ));
        assertEquals(
                probeVariableAnalyzer.preVariableDiagnostics.size() + 1,
                probeAnnotationUsageAnalyzer.preAnnotationUsageDiagnostics.size()
        );
        assertTrue(probeAnnotationUsageAnalyzer.preAnnotationUsageDiagnostics.asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.variable_phase_probe")
        ));
        assertEquals(
                probeAnnotationUsageAnalyzer.preAnnotationUsageDiagnostics.size() + 1,
                probeVirtualOverrideAnalyzer.preVirtualOverrideDiagnostics.size()
        );
        assertTrue(probeVirtualOverrideAnalyzer.preVirtualOverrideDiagnostics.asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.annotation_usage_phase_probe")
        ));
        assertEquals(
                probeVirtualOverrideAnalyzer.preVirtualOverrideDiagnostics.size() + 1,
                probeTypeCheckAnalyzer.preTypeCheckDiagnostics.size()
        );
        assertTrue(probeTypeCheckAnalyzer.preTypeCheckDiagnostics.asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.virtual_override_phase_probe")
        ));
        assertEquals(
                probeTypeCheckAnalyzer.preTypeCheckDiagnostics.size() + 1,
                probeLoopControlFlowAnalyzer.preLoopControlFlowDiagnostics.size()
        );
        assertTrue(probeLoopControlFlowAnalyzer.preLoopControlFlowDiagnostics.asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.type_check_phase_probe")
        ));
        assertEquals(probeLoopControlFlowAnalyzer.preLoopControlFlowDiagnostics.size() + 1, result.diagnostics().size());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.scope_phase_probe")
        ));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.variable_phase_probe")
        ));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.annotation_usage_phase_probe")
        ));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.virtual_override_phase_probe")
        ));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.type_check_phase_probe")
        ));
        assertEquals("sema.loop_control_flow_phase_probe", result.diagnostics().getLast().category());
        assertEquals(probeScopeAnalyzer.preScopeDiagnostics, result.moduleSkeleton().diagnostics());
        assertEquals(result.diagnostics(), diagnostics.snapshot());
        assertTrue(result.symbolBindings().isEmpty());
        assertTrue(result.resolvedMembers().isEmpty());
        assertTrue(result.resolvedCalls().isEmpty());
        assertTrue(result.expressionTypes().isEmpty());
        // The callable-entry var type post procedure is the only body publication for this empty body.
        var pingFunction = findFunction(unit.ast().statements(), "ping");
        assertEquals(GdVariantType.VARIANT, result.slotTypes().get(pingFunction.parameters().getFirst()));
        assertFalse(result.slotTypes().isEmpty());
    }

    /// Locks the constructor boundary: active phase injection remains available while body-owner
    /// classes are absent from both constructor signatures and the runtime classpath.
    @Test
    void activeDependencyConstructorAndClasspathExcludeLegacyBodyOwners() throws Exception {
        var activeConstructor = FrontendSemanticAnalyzer.class.getConstructor(
                FrontendClassSkeletonBuilder.class,
                FrontendScopeAnalyzer.class,
                FrontendVariableAnalyzer.class,
                FrontendAnnotationUsageAnalyzer.class,
                FrontendVirtualOverrideAnalyzer.class,
                FrontendTypeCheckAnalyzer.class,
                FrontendLoopControlFlowAnalyzer.class,
                FrontendCompileCheckAnalyzer.class
        );
        var legacyOwnerParameterNames = Set.of(
                "FrontendTopBindingAnalyzer",
                "FrontendLocalTypeStabilizationAnalyzer",
                "FrontendChainBindingAnalyzer",
                "FrontendExprTypeAnalyzer",
                "FrontendVarTypePostAnalyzer"
        );

        assertNotNull(activeConstructor);
        assertTrue(Arrays.stream(FrontendSemanticAnalyzer.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .noneMatch(parameterType -> legacyOwnerParameterNames.contains(parameterType.getSimpleName())));
        for (var legacyOwnerName : legacyOwnerParameterNames) {
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName("gd.script.gdcc.frontend.sema.analyzer." + legacyOwnerName)
            );
        }
    }

    @Test
    void removedWindowAndMultiOwnerPatchShimsStayAbsent() throws Exception {
        var removedClassNames = List.of(
                "gd.script.gdcc.frontend.sema.FrontendWindowAnalysisContext",
                "gd.script.gdcc.frontend.sema.FrontendWindowPublicationSurface",
                "gd.script.gdcc.frontend.sema.patch.FrontendAnalysisPatch"
        );

        for (var removedClassName : removedClassNames) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(removedClassName));
        }
        assertNotNull(FrontendAnalysisData.class.getMethod("applyPatch", FrontendOwnerPatch.class));
        assertTrue(Arrays.stream(FrontendAnalysisData.class.getMethods())
                .filter(method -> method.getName().equals("applyPatch"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .noneMatch(parameterType -> parameterType.getSimpleName().equals("FrontendAnalysisPatch")));
    }

    @Test
    void analyzePublishesStableLocalTypeFactsAcrossBodyPhases() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "stable_local_type_body_facts.gd"), """
                class_name StableLocalTypeBodyFacts
                extends RefCounted
                
                class Point:
                    var next: Point = null
                    var marker: int = -1
                
                func make_point() -> Point:
                    return Point.new()
                
                func write_path(point: Point) -> void:
                    var tail := make_point()
                    tail.next = point
                
                func read_path() -> bool:
                    var point := make_point()
                    return point.marker != -1
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = analyzeModule("test_module", List.of(unit), registry, diagnostics);

        var writePath = findFunction(unit.ast().statements(), "write_path");
        var tailDeclaration = findVariable(writePath.body().statements(), "tail");
        var tailAssignment = assertInstanceOf(
                dev.superice.gdparser.frontend.ast.AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, writePath.body().statements().get(1)).expression()
        );
        var nextStep = findNode(tailAssignment, dev.superice.gdparser.frontend.ast.AttributePropertyStep.class, step ->
                step.name().equals("next")
        );
        var readPath = findFunction(unit.ast().statements(), "read_path");
        var pointDeclaration = findVariable(readPath.body().statements(), "point");
        var markerStep = findNode(readPath.body(), dev.superice.gdparser.frontend.ast.AttributePropertyStep.class, step ->
                step.name().equals("marker")
        );

        var tailSlotType = result.slotTypes().get(tailDeclaration);
        var pointSlotType = result.slotTypes().get(pointDeclaration);
        var nextMember = result.resolvedMembers().get(nextStep);
        var markerMember = result.resolvedMembers().get(markerStep);
        var comparison = assertInstanceOf(ReturnStatement.class, readPath.body().statements().get(1)).value();
        assertNotNull(comparison);
        var comparisonType = result.expressionTypes().get(comparison);

        assertAll(
                () -> assertNotNull(tailSlotType),
                () -> assertTrue(tailSlotType.getTypeName().endsWith("Point")),
                () -> assertNotNull(pointSlotType),
                () -> assertTrue(pointSlotType.getTypeName().endsWith("Point")),
                () -> assertNotNull(nextMember),
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, nextMember.status()),
                () -> assertTrue(nextMember.receiverType().getTypeName().endsWith("Point")),
                () -> assertNotNull(markerMember),
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, markerMember.status()),
                () -> assertEquals("int", markerMember.resultType().getTypeName()),
                () -> assertNotNull(comparisonType),
                () -> assertEquals("bool", comparisonType.publishedType().getTypeName()),
                () -> assertTrue(result.diagnostics().asList().stream()
                        .noneMatch(diagnostic -> diagnostic.category().equals("sema.member_resolution")))
        );
    }

    @Test
    void analyzePublishesParameterAliasFactsAcrossBodyPhases() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "parameter_alias_body_facts.gd"), """
                class_name ParameterAliasBodyFacts
                extends RefCounted
                
                class Point:
                    var marker: int = -1
                
                func ping(value: Point) -> int:
                    var a := value
                    return a.marker
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = analyzeModule("test_module", List.of(unit), registry, diagnostics);

        var pingFunction = findFunction(unit.ast().statements(), "ping");
        var valueParameter = pingFunction.parameters().getFirst();
        var aliasDeclaration = findVariable(pingFunction.body().statements(), "a");
        var markerStep = findNode(pingFunction.body(), dev.superice.gdparser.frontend.ast.AttributePropertyStep.class, step ->
                step.name().equals("marker")
        );
        var returnValue = assertInstanceOf(ReturnStatement.class, pingFunction.body().statements().get(1)).value();
        assertNotNull(returnValue);

        var parameterSlotType = result.slotTypes().get(valueParameter);
        var aliasSlotType = result.slotTypes().get(aliasDeclaration);
        var aliasInitializerType = result.expressionTypes().get(aliasDeclaration.value());
        var markerMember = result.resolvedMembers().get(markerStep);
        var returnType = result.expressionTypes().get(returnValue);

        assertAll(
                () -> assertNotNull(parameterSlotType),
                () -> assertTrue(parameterSlotType.getTypeName().endsWith("Point")),
                () -> assertNotNull(aliasSlotType),
                () -> assertTrue(aliasSlotType.getTypeName().endsWith("Point")),
                () -> assertNotNull(aliasInitializerType),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, aliasInitializerType.status()),
                () -> assertTrue(aliasInitializerType.publishedType().getTypeName().endsWith("Point")),
                () -> assertNotNull(markerMember),
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, markerMember.status()),
                () -> assertTrue(markerMember.receiverType().getTypeName().endsWith("Point")),
                () -> assertEquals("int", markerMember.resultType().getTypeName()),
                () -> assertNotNull(returnType),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, returnType.status()),
                () -> assertEquals("int", returnType.publishedType().getTypeName()),
                () -> assertTrue(diagnosticsByCategory(result.diagnostics(), "sema.member_resolution").isEmpty()),
                () -> assertTrue(diagnosticsByCategory(result.diagnostics(), "sema.expression_resolution").isEmpty()),
                () -> assertTrue(diagnosticsByCategory(result.diagnostics(), "sema.type_check").isEmpty())
        );
    }

    @Test
    void defaultInterfaceBodyPipelinePublishesBodyFactsThroughSuiteExport() throws Exception {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "real_body_owner_framework_path.gd"), """
                class_name RealBodyOwnerFrameworkPath
                extends RefCounted
                class Point:
                    var marker: int = 1
                
                func ping(value: Point) -> int:
                    var alias := value
                    var number := alias.marker
                    return number
                """, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());
        var pingFunction = findFunction(unit.ast().statements(), "ping");
        var aliasDeclaration = findVariable(pingFunction.body().statements(), "alias");
        var numberDeclaration = findVariable(pingFunction.body().statements(), "number");
        var markerStep = findNode(pingFunction.body(), AttributePropertyStep.class, step -> step.name().equals("marker"));
        var numberInitializer = numberDeclaration.value();
        assertNotNull(numberInitializer);
        var interfaceBodyDiagnostics = new DiagnosticManager();

        var interfaceBody = analyzeModule(
                new FrontendSemanticAnalyzer(),
                "test_module",
                List.of(unit),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                interfaceBodyDiagnostics
        );

        var aliasSlotType = interfaceBody.slotTypes().get(aliasDeclaration);
        var numberSlotType = interfaceBody.slotTypes().get(numberDeclaration);
        var markerMember = interfaceBody.resolvedMembers().get(markerStep);
        var initializerType = interfaceBody.expressionTypes().get(numberInitializer);
        assertAll(
                () -> assertNotNull(aliasSlotType),
                () -> assertTrue(aliasSlotType.getTypeName().endsWith("Point")),
                () -> assertNotNull(numberSlotType),
                () -> assertEquals("int", numberSlotType.getTypeName()),
                () -> assertNotNull(markerMember),
                () -> assertEquals(FrontendMemberResolutionStatus.RESOLVED, markerMember.status()),
                () -> assertTrue(markerMember.receiverType().getTypeName().endsWith("Point")),
                () -> assertEquals("int", markerMember.resultType().getTypeName()),
                () -> assertNotNull(initializerType),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, initializerType.status()),
                () -> assertEquals("int", initializerType.publishedType().getTypeName())
        );
        assertEquals(interfaceBodyDiagnostics.snapshot(), interfaceBody.diagnostics());
    }

    @Test
    void defaultInterfaceBodyPipelinePublishesNestedHeaderAndBodyFactsInSourceOrder() throws Exception {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "segmented_equivalence.gd"), """
                class_name SegmentedEquivalence
                extends Node
                class Point:
                    var marker: int = 1
                func ping(value: Point) -> int:
                    var alias := value
                    var number := 1
                    if number > 0:
                        var nested := alias
                        number = nested.marker
                    while number < 3:
                        number += 1
                    return alias.marker
                """, parseDiagnostics);
        var interfaceBodyDiagnostics = new DiagnosticManager();
        var interfaceBody = analyzeModule(
                new FrontendSemanticAnalyzer(),
                "test_module",
                List.of(unit),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                interfaceBodyDiagnostics
        );
        var pingFunction = findFunction(unit.ast().statements(), "ping");
        var aliasDeclaration = findVariable(pingFunction.body().statements(), "alias");
        var numberDeclaration = findVariable(pingFunction.body().statements(), "number");
        var nestedDeclaration = findNode(pingFunction.body(), VariableDeclaration.class, variableDeclaration ->
                variableDeclaration.name().equals("nested")
        );
        var markerSteps = findNodes(pingFunction.body(), AttributePropertyStep.class, step -> step.name().equals("marker"));
        assertEquals(2, markerSteps.size());
        var numberInitializer = numberDeclaration.value();
        var nestedInitializer = nestedDeclaration.value();
        assertNotNull(numberInitializer);
        assertNotNull(nestedInitializer);

        assertAll(
                () -> assertTypeNameEndsWith(interfaceBody.slotTypes().get(aliasDeclaration), "Point"),
                () -> assertEquals("int", interfaceBody.slotTypes().get(numberDeclaration).getTypeName()),
                () -> assertTypeNameEndsWith(interfaceBody.slotTypes().get(nestedDeclaration), "Point"),
                () -> assertEquals("int", interfaceBody.resolvedMembers().get(markerSteps.getFirst()).resultType().getTypeName()),
                () -> assertEquals("int", interfaceBody.resolvedMembers().get(markerSteps.getLast()).resultType().getTypeName()),
                () -> assertEquals("int", interfaceBody.expressionTypes().get(numberInitializer).publishedType().getTypeName()),
                () -> assertTypeNameEndsWith(interfaceBody.expressionTypes().get(nestedInitializer).publishedType(), "Point")
        );
        assertEquals(interfaceBodyDiagnostics.snapshot(), interfaceBody.diagnostics());
    }

    @Test
    void defaultInterfaceBodyPipelineSupportsForAndMatchWhileConstStayFailClosed() throws Exception {
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", "segmented_unsupported_equivalence.gd"), """
                class_name SegmentedUnsupportedEquivalence
                extends Node
                func ping(values):
                    const blocked := 1
                    for value in values:
                        var hidden := value
                    match values:
                        _:
                            pass
                    return values
                """, new DiagnosticManager());
        var interfaceBodyDiagnostics = new DiagnosticManager();

        var interfaceBody = analyzeModule(
                new FrontendSemanticAnalyzer(),
                "test_module",
                List.of(unit),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                interfaceBodyDiagnostics
        );
        var pingFunction = findFunction(unit.ast().statements(), "ping");
        var blockedConst = findNode(pingFunction.body(), VariableDeclaration.class, variableDeclaration ->
                variableDeclaration.name().equals("blocked")
        );
        var hiddenLocal = findNode(pingFunction.body(), VariableDeclaration.class, variableDeclaration ->
                variableDeclaration.name().equals("hidden")
        );
        var hiddenUseSite = assertInstanceOf(IdentifierExpression.class, hiddenLocal.value());

        assertNull(interfaceBody.slotTypes().get(blockedConst));
        assertEquals(GdVariantType.VARIANT, interfaceBody.slotTypes().get(hiddenLocal));
        assertEquals(FrontendBindingKind.LOCAL_VAR, interfaceBody.symbolBindings().get(hiddenUseSite).kind());
        var matchStatement = findNode(pingFunction.body(), MatchStatement.class, _ -> true);
        assertNotNull(interfaceBody.matchPlans().get(matchStatement));
        assertFalse(diagnosticsByCategory(interfaceBody.diagnostics(), "sema.unsupported_binding_subtree").isEmpty());
        assertTrue(interfaceBody.diagnostics().asList().stream().noneMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_variable_inventory_subtree")
                        && diagnostic.range().equals(FrontendRange.fromAstRange(
                        findNode(pingFunction.body(), ForStatement.class, _ -> true).range()
                ))
        ));
        assertTrue(interfaceBody.diagnostics().asList().stream().noneMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_variable_inventory_subtree")
                        && diagnostic.range().equals(FrontendRange.fromAstRange(matchStatement.range()))
        ));
        assertEquals(interfaceBodyDiagnostics.snapshot(), interfaceBody.diagnostics());
    }

    @Test
    void defaultInterfaceBodyPipelinePublishesAssignmentTargetLoweringFacts() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "assignment_target_lowering_facts.gd"), """
                class_name AssignmentTargetLoweringFacts
                extends RefCounted
                
                var hp: int = 0
                
                func ping(host, index: int, value: int):
                    self.hp = value
                    host.box.count += value
                    host.payloads[index].value = value
                """, diagnostics);

        var result = analyzeModule(
                "test_module",
                List.of(unit),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        var assignments = findNodes(findFunction(unit.ast().statements(), "ping"), AssignmentExpression.class, _ -> true);
        assertEquals(3, assignments.size());
        var selfTarget = assertInstanceOf(AttributeExpression.class, assignments.get(0).left());
        var dynamicTarget = assertInstanceOf(AttributeExpression.class, assignments.get(1).left());
        var subscriptTarget = assertInstanceOf(AttributeExpression.class, assignments.get(2).left());
        var explicitSelf = assertInstanceOf(SelfExpression.class, selfTarget.base());
        var boxStep = assertInstanceOf(AttributePropertyStep.class, dynamicTarget.steps().getFirst());
        var countStep = assertInstanceOf(AttributePropertyStep.class, dynamicTarget.steps().getLast());
        var indexUse = findNode(subscriptTarget, IdentifierExpression.class, identifier -> identifier.name().equals("index"));

        assertAll(
                () -> assertEquals(
                        FrontendExpressionTypeStatus.RESOLVED,
                        result.expressionTypes().get(explicitSelf).status()
                ),
                () -> assertEquals(
                        FrontendExpressionTypeStatus.DYNAMIC,
                        result.expressionTypes().get(boxStep).status()
                ),
                () -> assertEquals(
                        FrontendExpressionTypeStatus.DYNAMIC,
                        result.expressionTypes().get(countStep).status()
                ),
                () -> assertEquals(
                        FrontendExpressionTypeStatus.RESOLVED,
                        result.expressionTypes().get(indexUse).status()
                )
        );
        assertEquals(diagnostics.snapshot(), result.diagnostics());
    }

    @Test
    void analyzeForCompileRunsCompileGateAfterLoopControlWhileAnalyzeStaysCompileCheckFree() throws Exception {
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", "compile_check_phase_probe.gd"), """
                class_name CompileCheckPhaseProbe
                extends Node
                
                func ping():
                    pass
                """, new DiagnosticManager());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var sharedDiagnostics = new DiagnosticManager();
        var sharedLoopControlProbe = new RecordingLoopControlFlowAnalyzer();
        var sharedProbe = new RecordingCompileCheckAnalyzer();
        var sharedAnalyzer = new FrontendSemanticAnalyzer(
                new FrontendClassSkeletonBuilder(),
                new FrontendScopeAnalyzer(),
                new FrontendVariableAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendVirtualOverrideAnalyzer(),
                new FrontendTypeCheckAnalyzer(),
                sharedLoopControlProbe,
                sharedProbe
        );
        var sharedResult = analyzeModule(sharedAnalyzer, "test_module", List.of(unit), registry, sharedDiagnostics);

        assertTrue(sharedLoopControlProbe.invoked);
        assertTrue(sharedLoopControlProbe.preLoopControlFlowDiagnosticsMatchedManager);
        assertFalse(sharedProbe.invoked);
        assertEquals(1, diagnosticsByCategory(sharedResult.diagnostics(), "sema.loop_control_flow_phase_probe").size());
        assertTrue(diagnosticsByCategory(sharedResult.diagnostics(), "sema.compile_check_phase_probe").isEmpty());
        assertEquals(sharedDiagnostics.snapshot(), sharedResult.diagnostics());

        var compileDiagnostics = new DiagnosticManager();
        var compileLoopControlProbe = new RecordingLoopControlFlowAnalyzer();
        var compileProbe = new RecordingCompileCheckAnalyzer();
        var compileAnalyzer = new FrontendSemanticAnalyzer(
                new FrontendClassSkeletonBuilder(),
                new FrontendScopeAnalyzer(),
                new FrontendVariableAnalyzer(),
                new FrontendAnnotationUsageAnalyzer(),
                new FrontendVirtualOverrideAnalyzer(),
                new FrontendTypeCheckAnalyzer(),
                compileLoopControlProbe,
                compileProbe
        );
        var compileResult = analyzeModuleForCompile(
                compileAnalyzer,
                "test_module",
                List.of(unit),
                registry,
                compileDiagnostics
        );

        assertTrue(compileLoopControlProbe.invoked);
        assertTrue(compileLoopControlProbe.preLoopControlFlowDiagnosticsMatchedManager);
        assertTrue(compileProbe.invoked);
        assertTrue(compileProbe.preCompileCheckDiagnosticsMatchedManager);
        assertTrue(compileProbe.loopControlBoundaryPublished);
        assertEquals(1, diagnosticsByCategory(compileResult.diagnostics(), "sema.loop_control_flow_phase_probe").size());
        assertEquals("sema.compile_check_phase_probe", compileResult.diagnostics().getLast().category());
        assertEquals(1, diagnosticsByCategory(compileResult.diagnostics(), "sema.compile_check_phase_probe").size());
        assertEquals(compileDiagnostics.snapshot(), compileResult.diagnostics());
    }

    @Test
    void analyzeForCompileDefinesTheLoweringReadinessBoundary() throws Exception {
        // Shared analyze stays diagnostic-free; compile gate still blocks forms that are not compile-ready.
        // Container literals, assert, preload and function-body get-node are compile-ready; the
        // property-initializer get-node stays DEFERRED on the shared path and serves as the
        // compile-blocking anchor via the generic published-fact scan.
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", "compile_check_lowering_boundary.gd"), """
                class_name CompileCheckLoweringBoundary
                extends Node
                
                var camera = $Camera3D
                
                func ping():
                    [1]
                    assert(true)
                    preload("res://icon.svg")
                    $Camera3D
                """, new DiagnosticManager());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var sharedDiagnostics = new DiagnosticManager();
        var sharedResult = analyzeModule("test_module", List.of(unit), registry, sharedDiagnostics);
        assertFalse(sharedResult.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedResult.diagnostics(), "sema.compile_check").isEmpty());

        var compileDiagnostics = new DiagnosticManager();
        var compileResult = analyzeModuleForCompile("test_module", List.of(unit), registry, compileDiagnostics);
        assertTrue(compileResult.diagnostics().hasErrors());
        var compileBlocks = diagnosticsByCategory(compileResult.diagnostics(), "sema.compile_check");
        assertEquals(1, compileBlocks.size());
        assertTrue(compileBlocks.getFirst().message().contains("Get-node expression"));
        assertTrue(compileBlocks.stream().noneMatch(diagnostic -> diagnostic.message().contains("assert statement")));
        assertTrue(compileBlocks.stream().noneMatch(diagnostic -> diagnostic.message().contains("Preload expression")));
        assertTrue(compileBlocks.stream().noneMatch(diagnostic -> diagnostic.message().contains("Array literal")));
        assertEquals(compileDiagnostics.snapshot(), compileResult.diagnostics());
    }

    @Test
    void analyzeKeepsPipelineAliveWhenSkeletonReportsRecoverableDiagnostics() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var duplicateA = parserService.parseUnit(Path.of("tmp", "duplicate_a.gd"), """
                class_name SharedName
                extends Node
                
                func from_a():
                    pass
                """, diagnostics);
        var duplicateB = parserService.parseUnit(Path.of("tmp", "duplicate_b.gd"), """
                class_name SharedName
                extends Node
                
                func from_b():
                    pass
                """, diagnostics);
        var stable = parserService.parseUnit(Path.of("tmp", "stable.gd"), """
                class_name StableAfterError
                extends Node
                
                func ok():
                    pass
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();

        var result = analyzeModule(analyzer, "test_module", List.of(duplicateA, duplicateB, stable), registry, diagnostics);

        assertEquals(
                List.of("SharedName", "StableAfterError"),
                topLevelClassDefs(result.moduleSkeleton()).stream().map(LirClassDef::getName).toList()
        );
        assertFalse(result.scopesByAst().isEmpty());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("Duplicate top-level class source name 'SharedName'")
        ));
        assertNotNull(registry.findGdccClass("SharedName"));
        assertNotNull(registry.findGdccClass("StableAfterError"));
    }

    @Test
    void semanticAnalysisKeepsSharedTypeResolverAlignedWithSkeletonDeclaredTypes() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "scope_type_resolver_parity.gd"), """
                class_name ScopeTypeResolverParity
                extends RefCounted
                
                var inner_ref: Inner
                
                class Inner:
                    var helpers: Array[Helper]
                
                class Helper:
                    pass
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = analyzeModule("test_module", List.of(unit), registry, diagnostics);

        var topLevel = findClassByName(topLevelClassDefs(result.moduleSkeleton()), "ScopeTypeResolverParity");
        var inner = findClassByName(result.moduleSkeleton().allClassDefs(), "ScopeTypeResolverParity__sub__Inner");
        var sourceScope = assertInstanceOf(ClassScope.class, result.scopesByAst().get(unit.ast()));

        assertEquals(
                findPropertyByName(topLevel, "inner_ref").getType(),
                ScopeTypeResolver.tryResolveDeclaredType(sourceScope, "Inner")
        );

        var innerDeclaration = findClass(unit.ast().statements(), "Inner");
        var innerScope = assertInstanceOf(ClassScope.class, result.scopesByAst().get(innerDeclaration));
        assertEquals(
                findPropertyByName(inner, "helpers").getType(),
                ScopeTypeResolver.tryResolveDeclaredType(innerScope, "Array[Helper]")
        );
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void semanticAnalysisCurrentlyPrefersOuterTypeMetaOverBaseTypeMetaWhenNamesCollide() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var baseUnit = parserService.parseUnit(Path.of("tmp", "base_leaf_precedence.gd"), """
                class_name BaseLeaf
                extends RefCounted
                
                class Shared:
                    pass
                """, diagnostics);
        var outerUnit = parserService.parseUnit(Path.of("tmp", "outer_precedence.gd"), """
                class_name OuterPrecedence
                extends RefCounted
                
                class Shared:
                    pass
                
                class Leaf extends BaseLeaf:
                    var picked: Shared
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = analyzeModule("test_module", List.of(baseUnit, outerUnit), registry, diagnostics);

        var leaf = findClassByName(result.moduleSkeleton().allClassDefs(), "OuterPrecedence__sub__Leaf");
        var pickedType = assertInstanceOf(GdObjectType.class, findPropertyByName(leaf, "picked").getType());
        assertEquals("OuterPrecedence__sub__Shared", pickedType.getTypeName());

        var leafDeclaration = findClass(outerUnit.ast().statements(), "Leaf");
        var leafScope = assertInstanceOf(ClassScope.class, result.scopesByAst().get(leafDeclaration));
        var resolvedShared = assertInstanceOf(
                GdObjectType.class,
                ScopeTypeResolver.tryResolveDeclaredType(leafScope, "Shared")
        );
        assertEquals("OuterPrecedence__sub__Shared", resolvedShared.getTypeName());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void semanticAnalysisCanonicalizesHeaderExtendsWhileKeepingFrontendSuperclassFacts() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "header_super_shared_resolver_gap.gd"), """
                class_name HeaderResolverGap
                extends RefCounted
                
                class Shared:
                    pass
                
                class Leaf extends Shared:
                    var picked: Shared
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = analyzeModule("test_module", List.of(unit), registry, diagnostics);

        var leaf = findClassByName(result.moduleSkeleton().allClassDefs(), "HeaderResolverGap__sub__Leaf");
        var leafDeclaration = findClass(unit.ast().statements(), "Leaf");
        var sourceRelation = result.moduleSkeleton().sourceClassRelations().getFirst();
        var leafRelation = assertInstanceOf(
                FrontendInnerClassRelation.class,
                sourceRelation.findRelation(leafDeclaration)
        );
        assertEquals(
                new FrontendSuperClassRef("Shared", "HeaderResolverGap__sub__Shared"),
                leafRelation.superClassRef()
        );
        assertEquals("HeaderResolverGap__sub__Shared", leaf.getSuperName());
        assertNotNull(registry.findGdccClass(leaf.getSuperName()));

        var pickedType = assertInstanceOf(GdObjectType.class, findPropertyByName(leaf, "picked").getType());
        assertEquals("HeaderResolverGap__sub__Shared", pickedType.getTypeName());

        var leafScope = assertInstanceOf(ClassScope.class, result.scopesByAst().get(leafDeclaration));
        var resolvedShared = assertInstanceOf(
                GdObjectType.class,
                ScopeTypeResolver.tryResolveDeclaredType(leafScope, "Shared")
        );
        assertEquals("HeaderResolverGap__sub__Shared", resolvedShared.getTypeName());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void semanticAnalysisRejectsCanonicalSuperclassSpellingAtFrontendBoundary() throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new FrontendSourceUnit(
                Path.of("tmp", "canonical_super_boundary.gd"),
                "",
                new SourceFile(
                        List.of(
                                new ClassNameStatement("CanonicalBoundary", "RefCounted", SYNTHETIC_RANGE),
                                new ClassDeclaration(
                                        "Shared",
                                        null,
                                        new Block(List.of(new PassStatement(SYNTHETIC_RANGE)), SYNTHETIC_RANGE),
                                        SYNTHETIC_RANGE
                                ),
                                new ClassDeclaration(
                                        "Leaf",
                                        "CanonicalBoundary__sub__Shared",
                                        new Block(List.of(new PassStatement(SYNTHETIC_RANGE)), SYNTHETIC_RANGE),
                                        SYNTHETIC_RANGE
                                )
                        ),
                        SYNTHETIC_RANGE
                )
        );
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var result = analyzeModule("test_module", List.of(unit), registry, diagnostics);

        var leafDeclaration = findClass(unit.ast().statements(), "Leaf");
        var sourceRelation = result.moduleSkeleton().sourceClassRelations().getFirst();
        assertEquals(
                List.of("CanonicalBoundary", "CanonicalBoundary__sub__Shared"),
                result.moduleSkeleton().allClassDefs().stream().map(LirClassDef::getName).toList()
        );
        assertEquals(
                List.of("Shared"),
                sourceRelation.innerClassRelations().stream().map(FrontendInnerClassRelation::sourceName).toList()
        );
        assertNull(sourceRelation.findRelation(leafDeclaration));
        assertNull(result.scopesByAst().get(leafDeclaration));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("CanonicalBoundary__sub__Leaf")
                        && diagnostic.message().contains("canonical '__sub__' spelling")
        ));
        assertNull(registry.findGdccClass("CanonicalBoundary__sub__Leaf"));
        assertNotNull(registry.findGdccClass("CanonicalBoundary__sub__Shared"));
    }

    @Test
    void semanticAnalysisRejectsSingletonSuperclassSourceAtFrontendBoundary() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "singleton_super.gd"), """
                class_name SingletonBoundary
                extends GameSingleton
                
                func ping():
                    pass
                """, diagnostics);
        var registry = createRegistryWithSingleton("GameSingleton");
        var result = analyzeModule("test_module", List.of(unit), registry, diagnostics);

        assertTrue(topLevelClassDefs(result.moduleSkeleton()).isEmpty());
        assertTrue(result.moduleSkeleton().sourceClassRelations().isEmpty());
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("SingletonBoundary")
                        && diagnostic.message().contains("autoload/singleton superclasses")
        ));
        assertNull(registry.findGdccClass("SingletonBoundary"));
        assertNull(result.scopesByAst().get(unit.ast()));
    }

    @Test
    void semanticAnalysisSkipsReservedSyntheticPropertyHelperMemberSubtrees() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "reserved_helper_subtree_skip.gd"), """
                class_name ReservedHelperSubtreeSkip
                extends RefCounted
                
                var _field_getter_value := 1
                
                func _field_setter_value() -> int:
                    return 1
                
                func ok() -> int:
                    return 2
                """, diagnostics);
        var reservedProperty = findVariable(unit.ast().statements(), "_field_getter_value");
        var reservedFunction = findFunction(unit.ast().statements(), "_field_setter_value");
        var okFunction = findFunction(unit.ast().statements(), "ok");
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var result = analyzeModule("test_module", List.of(unit), registry, diagnostics);
        var classDef = findClassByName(topLevelClassDefs(result.moduleSkeleton()), "ReservedHelperSubtreeSkip");

        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("_field_getter_value")
        ));
        assertTrue(result.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.class_skeleton")
                        && diagnostic.message().contains("_field_setter_value")
        ));
        assertNull(result.scopesByAst().get(reservedProperty));
        assertNull(result.scopesByAst().get(reservedFunction));
        assertNotNull(result.scopesByAst().get(okFunction));
        assertTrue(classDef.getProperties().stream().noneMatch(property -> property.getName().equals("_field_getter_value")));
        assertTrue(classDef.getFunctions().stream().noneMatch(function -> function.getName().equals("_field_setter_value")));
        assertTrue(classDef.getFunctions().stream().anyMatch(function -> function.getName().equals("ok")));
    }

    private VariableDeclaration findVariable(List<?> statements, String name) {
        return statements.stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(variableDeclaration -> variableDeclaration.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Variable not found: " + name));
    }

    private List<FrontendSourceUnit> sourceUnits(FrontendModuleSkeleton result) {
        return result.sourceClassRelations().stream()
                .map(FrontendSourceClassRelation::unit)
                .toList();
    }

    private List<LirClassDef> topLevelClassDefs(FrontendModuleSkeleton result) {
        return result.sourceClassRelations().stream()
                .map(FrontendSourceClassRelation::topLevelClassDef)
                .toList();
    }

    private FrontendAnalysisData analyzeModule(
            @NotNull String moduleName,
            @NotNull List<FrontendSourceUnit> units,
            @NotNull ClassRegistry registry,
            @NotNull DiagnosticManager diagnostics
    ) {
        return analyzeModule(new FrontendSemanticAnalyzer(), moduleName, units, registry, diagnostics);
    }

    private FrontendAnalysisData analyzeModule(
            @NotNull FrontendSemanticAnalyzer analyzer,
            @NotNull String moduleName,
            @NotNull List<FrontendSourceUnit> units,
            @NotNull ClassRegistry registry,
            @NotNull DiagnosticManager diagnostics
    ) {
        return analyzer.analyze(new FrontendModule(moduleName, units), registry, diagnostics);
    }

    private FrontendAnalysisData analyzeModuleForCompile(
            @NotNull String moduleName,
            @NotNull List<FrontendSourceUnit> units,
            @NotNull ClassRegistry registry,
            @NotNull DiagnosticManager diagnostics
    ) {
        return analyzeModuleForCompile(new FrontendSemanticAnalyzer(), moduleName, units, registry, diagnostics);
    }

    private FrontendAnalysisData analyzeModuleForCompile(
            @NotNull FrontendSemanticAnalyzer analyzer,
            @NotNull String moduleName,
            @NotNull List<FrontendSourceUnit> units,
            @NotNull ClassRegistry registry,
            @NotNull DiagnosticManager diagnostics
    ) {
        return analyzer.analyzeForCompile(new FrontendModule(moduleName, units), registry, diagnostics);
    }

    private FunctionDeclaration findFunction(List<?> statements, String name) {
        return statements.stream()
                .filter(FunctionDeclaration.class::isInstance)
                .map(FunctionDeclaration.class::cast)
                .filter(functionDeclaration -> functionDeclaration.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Function not found: " + name));
    }

    private ClassDeclaration findClass(List<?> statements, String name) {
        return statements.stream()
                .filter(ClassDeclaration.class::isInstance)
                .map(ClassDeclaration.class::cast)
                .filter(classDeclaration -> classDeclaration.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + name));
    }

    private ClassDef findClassByName(List<? extends ClassDef> classDefs, String name) {
        return classDefs.stream()
                .filter(classDef -> classDef.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ClassDef not found: " + name));
    }

    private PropertyDef findPropertyByName(ClassDef classDef, String name) {
        return classDef.getProperties().stream()
                .filter(propertyDef -> propertyDef.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property not found: " + name));
    }

    private LiteralExpression findLiteral(Node root, String sourceText) {
        return findNode(
                root,
                LiteralExpression.class,
                literalExpression -> literalExpression.sourceText().equals(sourceText)
        );
    }

    private <T extends Node> T findNode(
            Node root,
            Class<T> nodeType,
            Predicate<T> predicate
    ) {
        return findNodes(root, nodeType, predicate).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node not found: " + nodeType.getSimpleName()));
    }

    private <T extends Node> List<T> findNodes(
            Node root,
            Class<T> nodeType,
            Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return List.copyOf(matches);
    }

    private <T extends Node> void collectMatchingNodes(
            Node node,
            Class<T> nodeType,
            Predicate<T> predicate,
            List<T> matches
    ) {
        if (nodeType.isInstance(node)) {
            var candidate = nodeType.cast(node);
            if (predicate.test(candidate)) {
                matches.add(candidate);
            }
        }
        for (var child : node.getChildren()) {
            collectMatchingNodes(child, nodeType, predicate, matches);
        }
    }

    private List<String> annotationNames(List<FrontendGdAnnotation> annotations) {
        assertNotNull(annotations);
        return annotations.stream().map(FrontendGdAnnotation::name).toList();
    }

    private List<FrontendDiagnostic> diagnosticsByCategory(
            DiagnosticSnapshot diagnostics,
            String category
    ) {
        return diagnostics.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private void assertTypeNameEndsWith(@NotNull GdType type, @NotNull String suffix) {
        assertTrue(type.getTypeName().endsWith(suffix), () -> "Expected type ending with " + suffix + ", got " + type);
    }

    private void assertDiagnosticsEquivalentIgnoringOrder(
            @NotNull DiagnosticSnapshot expected,
            @NotNull DiagnosticSnapshot actual
    ) {
        assertEquals(
                expected.asList().stream().map(Objects::toString).sorted().toList(),
                actual.asList().stream().map(Objects::toString).sorted().toList()
        );
    }

    private ClassRegistry createRegistryWithSingleton(String singletonName) {
        return new ClassRegistry(new ExtensionAPI(
                new ExtensionHeader(4, 4, 0, "stable", "test", "test", "single"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ExtensionGdClass("Object", false, true, "", "core", List.of(), List.of(), List.of(), List.of(), List.of()),
                        new ExtensionGdClass("Node", false, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of()),
                        new ExtensionGdClass("RefCounted", true, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of())
                ),
                List.of(new ExtensionSingleton(singletonName, "Node")),
                List.of()
        ));
    }

    /// Test double that records the diagnostics snapshot and published skeleton boundary visible
    /// to scope analysis.
    private static final class RecordingScopeAnalyzer extends FrontendScopeAnalyzer {
        private boolean invoked;
        private boolean moduleSkeletonPublished;
        private boolean preScopeDiagnosticsMatchedManager;
        private DiagnosticSnapshot preScopeDiagnostics;

        @Override
        public void analyze(
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            invoked = true;
            moduleSkeletonPublished = analysisData.moduleSkeleton().sourceClassRelations().size() == 1;
            preScopeDiagnostics = analysisData.diagnostics();
            preScopeDiagnosticsMatchedManager = preScopeDiagnostics.equals(diagnosticManager.snapshot());
            diagnosticManager.warning(
                    "sema.scope_phase_probe",
                    "scope phase probe diagnostic",
                    null,
                    null
            );
            super.analyze(classRegistry, analysisData, diagnosticManager);
        }
    }

    /// Test double that records the diagnostics snapshot and published scope boundary visible to
    /// variable inventory analysis.
    private static final class RecordingVariableAnalyzer extends FrontendVariableAnalyzer {
        private boolean invoked;
        private boolean scopeBoundaryPublished;
        private boolean preVariableDiagnosticsMatchedManager;
        private DiagnosticSnapshot preVariableDiagnostics;

        @Override
        public void analyze(
                @NotNull FrontendAnalysisData analysisData,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            invoked = true;
            preVariableDiagnostics = analysisData.diagnostics();
            preVariableDiagnosticsMatchedManager = preVariableDiagnostics.equals(diagnosticManager.snapshot());
            scopeBoundaryPublished = analysisData.moduleSkeleton().sourceClassRelations().stream()
                    .allMatch(sourceClassRelation -> analysisData.scopesByAst().containsKey(sourceClassRelation.unit().ast()));
            super.analyze(analysisData, diagnosticManager);
            diagnosticManager.warning(
                    "sema.variable_phase_probe",
                    "variable phase probe diagnostic",
                    null,
                    null
            );
        }
    }

    private static final class RecordingAnnotationUsageAnalyzer extends FrontendAnnotationUsageAnalyzer {
        private boolean invoked;
        private boolean varTypeBoundaryPublished;
        private boolean preAnnotationUsageDiagnosticsMatchedManager;
        private boolean stableAnnotationsReferencePreserved;
        private DiagnosticSnapshot preAnnotationUsageDiagnostics;

        @Override
        public void analyze(
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            invoked = true;
            preAnnotationUsageDiagnostics = analysisData.diagnostics();
            preAnnotationUsageDiagnosticsMatchedManager =
                    preAnnotationUsageDiagnostics.equals(diagnosticManager.snapshot());
            varTypeBoundaryPublished = analysisData.moduleSkeleton().sourceClassRelations().stream()
                    .allMatch(sourceClassRelation -> analysisData.scopesByAst().containsKey(sourceClassRelation.unit().ast()))
                    && analysisData.symbolBindings().isEmpty()
                    && analysisData.resolvedMembers().isEmpty()
                    && analysisData.resolvedCalls().isEmpty()
                    && analysisData.expressionTypes().isEmpty()
                    && !analysisData.slotTypes().isEmpty();
            var publishedAnnotations = analysisData.annotationsByAst();

            super.analyze(classRegistry, analysisData, diagnosticManager);

            stableAnnotationsReferencePreserved = publishedAnnotations == analysisData.annotationsByAst();
            diagnosticManager.warning(
                    "sema.annotation_usage_phase_probe",
                    "annotation-usage phase probe diagnostic",
                    null,
                    null
            );
        }
    }

    private static final class RecordingVirtualOverrideAnalyzer extends FrontendVirtualOverrideAnalyzer {
        private boolean invoked;
        private boolean annotationUsageBoundaryPublished;
        private boolean preVirtualOverrideDiagnosticsMatchedManager;
        private DiagnosticSnapshot preVirtualOverrideDiagnostics;

        @Override
        public void analyze(
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            invoked = true;
            preVirtualOverrideDiagnostics = analysisData.diagnostics();
            preVirtualOverrideDiagnosticsMatchedManager =
                    preVirtualOverrideDiagnostics.equals(diagnosticManager.snapshot());
            annotationUsageBoundaryPublished = analysisData.moduleSkeleton().sourceClassRelations().stream()
                    .allMatch(sourceClassRelation -> analysisData.scopesByAst().containsKey(sourceClassRelation.unit().ast()))
                    && analysisData.symbolBindings().isEmpty()
                    && analysisData.resolvedMembers().isEmpty()
                    && analysisData.resolvedCalls().isEmpty()
                    && analysisData.expressionTypes().isEmpty()
                    && !analysisData.slotTypes().isEmpty();
            super.analyze(classRegistry, analysisData, diagnosticManager);
            diagnosticManager.warning(
                    "sema.virtual_override_phase_probe",
                    "virtual-override phase probe diagnostic",
                    null,
                    null
            );
        }
    }

    private static final class RecordingTypeCheckAnalyzer extends FrontendTypeCheckAnalyzer {
        private boolean invoked;
        private boolean virtualOverrideBoundaryPublished;
        private boolean preTypeCheckDiagnosticsMatchedManager;
        private boolean stableExpressionTypesReferencePreserved;
        private boolean expressionTypesRemainPublishedAfterTypeCheck;
        private DiagnosticSnapshot preTypeCheckDiagnostics;

        @Override
        public void analyze(
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            invoked = true;
            preTypeCheckDiagnostics = analysisData.diagnostics();
            preTypeCheckDiagnosticsMatchedManager = preTypeCheckDiagnostics.equals(diagnosticManager.snapshot());
            virtualOverrideBoundaryPublished = analysisData.moduleSkeleton().sourceClassRelations().stream()
                    .allMatch(sourceClassRelation -> analysisData.scopesByAst().containsKey(sourceClassRelation.unit().ast()))
                    && analysisData.symbolBindings().isEmpty()
                    && analysisData.resolvedMembers().isEmpty()
                    && analysisData.resolvedCalls().isEmpty()
                    && analysisData.expressionTypes().isEmpty()
                    && !analysisData.slotTypes().isEmpty();
            var publishedExpressionTypes = analysisData.expressionTypes();

            super.analyze(classRegistry, analysisData, diagnosticManager);

            stableExpressionTypesReferencePreserved = publishedExpressionTypes == analysisData.expressionTypes();
            expressionTypesRemainPublishedAfterTypeCheck = analysisData.expressionTypes().isEmpty();
            diagnosticManager.warning(
                    "sema.type_check_phase_probe",
                    "type-check phase probe diagnostic",
                    null,
                    null
            );
        }
    }

    private static final class RecordingLoopControlFlowAnalyzer extends FrontendLoopControlFlowAnalyzer {
        private boolean invoked;
        private boolean typeCheckBoundaryPublished;
        private boolean preLoopControlFlowDiagnosticsMatchedManager;
        private DiagnosticSnapshot preLoopControlFlowDiagnostics;

        @Override
        public void analyze(
                @NotNull FrontendAnalysisData analysisData,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            invoked = true;
            preLoopControlFlowDiagnostics = analysisData.diagnostics();
            preLoopControlFlowDiagnosticsMatchedManager =
                    preLoopControlFlowDiagnostics.equals(diagnosticManager.snapshot());
            typeCheckBoundaryPublished = analysisData.moduleSkeleton().sourceClassRelations().stream()
                    .allMatch(sourceClassRelation -> analysisData.scopesByAst().containsKey(sourceClassRelation.unit().ast()))
                    && analysisData.symbolBindings().isEmpty()
                    && analysisData.resolvedMembers().isEmpty()
                    && analysisData.resolvedCalls().isEmpty()
                    && analysisData.expressionTypes().isEmpty()
                    && !analysisData.slotTypes().isEmpty();
            super.analyze(analysisData, diagnosticManager);
            diagnosticManager.warning(
                    "sema.loop_control_flow_phase_probe",
                    "loop-control phase probe diagnostic",
                    null,
                    null
            );
        }
    }

    private static final class RecordingCompileCheckAnalyzer extends FrontendCompileCheckAnalyzer {
        private boolean invoked;
        private boolean loopControlBoundaryPublished;
        private boolean preCompileCheckDiagnosticsMatchedManager;

        @Override
        public void analyze(
                @NotNull FrontendAnalysisData analysisData,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            invoked = true;
            var preCompileCheckDiagnostics = analysisData.diagnostics();
            preCompileCheckDiagnosticsMatchedManager = preCompileCheckDiagnostics.equals(diagnosticManager.snapshot());
            loopControlBoundaryPublished = analysisData.moduleSkeleton().sourceClassRelations().stream()
                    .allMatch(sourceClassRelation -> analysisData.scopesByAst().containsKey(sourceClassRelation.unit().ast()))
                    && analysisData.symbolBindings().isEmpty()
                    && analysisData.resolvedMembers().isEmpty()
                    && analysisData.resolvedCalls().isEmpty()
                    && analysisData.expressionTypes().isEmpty();
            super.analyze(analysisData, diagnosticManager);
            diagnosticManager.warning(
                    "sema.compile_check_phase_probe",
                    "compile-check phase probe diagnostic",
                    null,
                    null
            );
        }
    }
}
