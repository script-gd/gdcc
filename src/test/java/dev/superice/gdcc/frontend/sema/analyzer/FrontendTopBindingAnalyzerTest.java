package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.frontend.scope.AbstractFrontendScope;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendTopBindingAnalyzerTest {
    @Test
    void analyzeRejectsMissingModuleSkeletonBoundary() {
        var analyzer = new FrontendTopBindingAnalyzer();
        var analysisData = FrontendAnalysisData.bootstrap();

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(analysisData, new DiagnosticManager())
        );

        assertTrue(thrown.getMessage().contains("moduleSkeleton"));
    }

    @Test
    void analyzeRejectsMissingDiagnosticsBoundary() throws Exception {
        var preparedInput = prepareBindingInput("missing_binding_diagnostics.gd", """
                class_name MissingBindingDiagnostics
                extends Node
                
                func ping():
                    pass
                """);
        var analyzer = new FrontendTopBindingAnalyzer();
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.updateModuleSkeleton(preparedInput.analysisData().moduleSkeleton());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(analysisData, preparedInput.diagnosticManager())
        );

        assertTrue(thrown.getMessage().contains("diagnostics"));
    }

    @Test
    void analyzeRejectsMissingPublishedSourceScope() throws Exception {
        var preparedInput = prepareBindingInput("missing_source_scope.gd", """
                class_name MissingSourceScope
                extends Node
                
                func ping():
                    pass
                """);
        var analyzer = new FrontendTopBindingAnalyzer();
        preparedInput.analysisData().scopesByAst().remove(preparedInput.unit().ast());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager())
        );

        assertTrue(thrown.getMessage().contains(preparedInput.unit().path().toString()));
    }

    @Test
    void analyzeClearsStaleEntriesAndPublishesNoBindingsWhenBodyHasNoUseSites() throws Exception {
        var preparedInput = prepareBindingInput("publish_empty_symbol_bindings.gd", """
                class_name PublishEmptySymbolBindings
                extends Node
                
                func ping():
                    pass
                """);
        var analyzer = new FrontendTopBindingAnalyzer();
        var publishedSymbolBindings = preparedInput.analysisData().symbolBindings();
        var staleNode = preparedInput.unit().ast();
        publishedSymbolBindings.put(staleNode, new FrontendBinding("__stale__", FrontendBindingKind.UNKNOWN, null));

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        assertSame(publishedSymbolBindings, preparedInput.analysisData().symbolBindings());
        assertTrue(preparedInput.analysisData().symbolBindings().isEmpty());
        assertNull(preparedInput.analysisData().symbolBindings().get(staleNode));
        assertTrue(preparedInput.analysisData().resolvedMembers().isEmpty());
        assertTrue(preparedInput.analysisData().resolvedCalls().isEmpty());
        assertTrue(preparedInput.analysisData().expressionTypes().isEmpty());
    }

    @Test
    void analyzeBindsSupportedValueCategoriesAndLiteralsInExecutableBodies() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        assertFalse(api.singletons().isEmpty());
        assertFalse(api.globalEnums().isEmpty());
        var singletonName = api.singletons().getFirst().name();
        var globalEnumName = api.globalEnums().getFirst().name();
        var preparedInput = prepareBindingInput(
                "value_categories.gd",
                """
                        class_name ValueCategories
                        extends Node
                        
                        signal changed(value: int)
                        var hp = 1
                        
                        func ping(value, arr, i):
                            var local = value
                            print(local)
                            print(hp)
                            print(changed)
                            print(%s)
                            print(%s)
                            print(arr[i + 1])
                            print(self)
                        """.formatted(singletonName, globalEnumName),
                new ClassRegistry(api)
        );
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var localDeclaration = findVariable(pingFunction.body().statements(), "local");
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(localDeclaration.value(), "value"),
                FrontendBindingKind.PARAMETER
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "local"),
                FrontendBindingKind.LOCAL_VAR
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "hp"),
                FrontendBindingKind.PROPERTY
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "changed"),
                FrontendBindingKind.SIGNAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), singletonName),
                FrontendBindingKind.SINGLETON
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), globalEnumName),
                FrontendBindingKind.GLOBAL_ENUM
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "arr"),
                FrontendBindingKind.PARAMETER
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "i"),
                FrontendBindingKind.PARAMETER
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "1"),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findSelfExpression(pingFunction.body()),
                FrontendBindingKind.SELF
        );
        assertTrue(preparedInput.diagnosticManager().snapshot().isEmpty());
    }

    @Test
    void analyzeBindsTopLevelTypeMetaChainHeadsForEngineBuiltinGlobalEnumAndLexicalInnerClasses() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        assertFalse(api.globalEnums().isEmpty());
        assertFalse(api.globalEnums().getFirst().values().isEmpty());
        var globalEnumName = api.globalEnums().getFirst().name();
        var globalEnumValueName = api.globalEnums().getFirst().values().getFirst().name();
        var preparedInput = prepareBindingInput(
                "type_meta_chain_head.gd",
                """
                        class_name TypeMetaChainHead
                        extends Node
                        
                        class Inner:
                            static func build():
                                return null
                        
                        func ping():
                            Node.new()
                            String.num_int64(1)
                            %s.%s
                            Inner.build()
                        """.formatted(globalEnumName, globalEnumValueName),
                new ClassRegistry(api)
        );
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "Node"),
                FrontendBindingKind.TYPE_META
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "String"),
                FrontendBindingKind.TYPE_META
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), globalEnumName),
                FrontendBindingKind.TYPE_META
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "Inner"),
                FrontendBindingKind.TYPE_META
        );
        assertTrue(preparedInput.diagnosticManager().snapshot().isEmpty());
    }

    @Test
    void analyzePrefersLocalValueOverVisibleTypeMetaChainHeadAndReportsShadowingDiagnostic() throws Exception {
        var preparedInput = prepareBindingInput("shadowed_type_meta_chain_head.gd", """
                class_name ShadowedTypeMetaChainHead
                extends Node
                
                class Inner:
                    static func build():
                        return null
                
                func ping(seed):
                    var Inner = seed
                    Inner.build()
                """);
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var initializer = findVariable(pingFunction.body().statements(), "Inner");
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(initializer.value(), "seed"),
                FrontendBindingKind.PARAMETER
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "Inner"),
                FrontendBindingKind.LOCAL_VAR
        );

        var publishedSymbolNames = preparedInput.analysisData().symbolBindings().values().stream()
                .map(FrontendBinding::symbolName)
                .toList();
        assertFalse(publishedSymbolNames.contains("build"));

        var bindingDiagnostics = bindingDiagnostics(preparedInput.diagnosticManager());
        assertEquals(1, bindingDiagnostics.size());
        assertTrue(bindingDiagnostics.getFirst().message().contains("Inner"));
        assertTrue(bindingDiagnostics.getFirst().message().contains("shadows a visible type-meta"));
    }

    @Test
    void analyzeBindsBareMethodsStaticMethodsAndUtilityFunctionsWithoutPublishingResolvedCalls() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        assertFalse(api.utilityFunctions().isEmpty());
        var utilityName = api.utilityFunctions().getFirst().name();
        var preparedInput = prepareBindingInput(
                "bare_callee_bindings.gd",
                """
                        class_name BareCalleeBindings
                        extends Node
                        
                        func move():
                            pass
                        
                        static func build():
                            return null
                        
                        func ping():
                            move()
                            build()
                            %s()
                        """.formatted(utilityName),
                new ClassRegistry(api)
        );
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "move"),
                FrontendBindingKind.METHOD
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "build"),
                FrontendBindingKind.STATIC_METHOD
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), utilityName),
                FrontendBindingKind.UTILITY_FUNCTION
        );
        assertTrue(preparedInput.analysisData().resolvedCalls().isEmpty());
        assertTrue(preparedInput.diagnosticManager().snapshot().isEmpty());
    }

    @Test
    void analyzeBindsBareFunctionLikeSymbolsInValuePositionAfterValueNamespaceMiss() throws Exception {
        var preparedInput = prepareBindingInput("bare_callable_values.gd", """
                class_name BareCallableValues
                extends Node
                
                static func build():
                    return null
                
                func helper():
                    pass
                
                func ping():
                    helper
                    build
                    print
                    var cb = helper
                """);
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "helper",
                FrontendBindingKind.METHOD,
                2
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "build"),
                FrontendBindingKind.STATIC_METHOD
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "print"),
                FrontendBindingKind.UTILITY_FUNCTION
        );
        assertTrue(bindingDiagnostics(preparedInput.diagnosticManager()).isEmpty());
    }

    @Test
    void analyzePublishesSupportedTypeMetaValueBindingsAndReportsOrdinaryValueMisuse() throws Exception {
        var preparedInput = prepareBindingInput("bare_type_meta_values.gd", """
                class_name BareTypeMetaValues
                extends RefCounted
                
                class Worker:
                    static func build():
                        return null
                
                func consume(value):
                    pass
                
                func ping():
                    Worker
                    consume(Worker)
                    var bad = Worker
                """);
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "Worker",
                FrontendBindingKind.TYPE_META,
                3
        );

        var bindingDiagnostics = bindingDiagnostics(preparedInput.diagnosticManager());
        assertEquals(3, bindingDiagnostics.size());
        assertTrue(bindingDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.message().contains("static-route head")
        ));
        assertTrue(bindingDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.message().contains("Worker.build")
        ));
    }

    @Test
    void analyzePublishesBlockedBareInstanceMethodInStaticContextWithoutFallingBackToUtilityFunction()
            throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        assertFalse(api.utilityFunctions().isEmpty());
        var utilityName = api.utilityFunctions().getFirst().name();
        var preparedInput = prepareBindingInput(
                "static_context_bare_callee_shadowing.gd",
                """
                        class_name StaticContextBareCalleeShadowing
                        extends Node
                        
                        func %s():
                            pass
                        
                        static func ping():
                            %s()
                        """.formatted(utilityName, utilityName),
                new ClassRegistry(api)
        );
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), utilityName),
                FrontendBindingKind.METHOD
        );
        var bindingDiagnostics = bindingDiagnostics(preparedInput.diagnosticManager());
        assertEquals(1, bindingDiagnostics.size());
        assertTrue(bindingDiagnostics.getFirst().message().contains(utilityName));
    }

    @Test
    void analyzePublishesUnknownForMissingBareCalleeAndReportsBindingError() throws Exception {
        var preparedInput = prepareBindingInput("unknown_bare_callee.gd", """
                class_name UnknownBareCallee
                extends Node
                
                func ping():
                    missing_call()
                """);
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "missing_call"),
                FrontendBindingKind.UNKNOWN
        );
        var bindingDiagnostics = bindingDiagnostics(preparedInput.diagnosticManager());
        assertEquals(1, bindingDiagnostics.size());
        assertTrue(bindingDiagnostics.getFirst().message().contains("missing_call"));
    }

    @Test
    void analyzeFailsClosedWhenBareCalleeOverloadSetMixesStaticAndInstanceMethods() throws Exception {
        var preparedInput = prepareBindingInput("mixed_bare_callee_overloads.gd", """
                class_name MixedBareCalleeOverloads
                extends Node
                
                func ping():
                    mix()
                """);
        var analyzer = new FrontendTopBindingAnalyzer();
        var classScope = assertInstanceOf(
                ClassScope.class,
                preparedInput.analysisData().scopesByAst().get(preparedInput.unit().ast())
        );
        classScope.defineFunction(createFunctionDef("mix", false));
        classScope.defineFunction(createFunctionDef("mix", true));

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertNull(preparedInput.analysisData().symbolBindings().get(findIdentifierExpression(pingFunction.body(), "mix")));
        var bindingDiagnostics = bindingDiagnostics(preparedInput.diagnosticManager());
        assertEquals(1, bindingDiagnostics.size());
        assertTrue(bindingDiagnostics.getFirst().message().contains("mixed static/non-static"));
        assertTrue(bindingDiagnostics.getFirst().message().contains("mix"));
    }

    @Test
    void analyzeAllowsBuiltinTypeMetaHeadsButRejectsScopeLocalPseudoTypeMetaSources() throws Exception {
        var preparedInput = prepareBindingInput("unsupported_type_meta_sources.gd", """
                class_name UnsupportedTypeMetaSources
                extends Node
                
                func ping():
                    String.num_int64(1)
                    Alias.build()
                """);
        var analyzer = new FrontendTopBindingAnalyzer();
        var sourceScope = assertInstanceOf(
                AbstractFrontendScope.class,
                preparedInput.analysisData().scopesByAst().get(preparedInput.unit().ast())
        );
        sourceScope.defineTypeMeta(new ScopeTypeMeta(
                "EnemyAlias",
                "Alias",
                new GdObjectType("EnemyAlias"),
                ScopeTypeMetaKind.GDCC_CLASS,
                "preload alias",
                true
        ));

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "String"),
                FrontendBindingKind.TYPE_META
        );
        assertNull(preparedInput.analysisData().symbolBindings().get(findIdentifierExpression(pingFunction.body(), "Alias")));

        var bindingDiagnostics = bindingDiagnostics(preparedInput.diagnosticManager());
        assertEquals(1, bindingDiagnostics.size());
        assertTrue(bindingDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Alias")));
    }

    @Test
    void analyzePreservesInitializerVisibilityFallbackAndUnknownContracts() throws Exception {
        var preparedInput = prepareBindingInput("initializer_visibility.gd", """
                class_name InitializerVisibility
                extends Node
                
                var node = 7
                
                func bind_property():
                    var node = node
                
                func bind_unknown():
                    var answer = answer
                """);
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var propertyFunction = findFunction(preparedInput.unit().ast(), "bind_property");
        var propertyInitializer = findVariable(propertyFunction.body().statements(), "node");
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(propertyInitializer.value(), "node"),
                FrontendBindingKind.PROPERTY
        );

        var unknownFunction = findFunction(preparedInput.unit().ast(), "bind_unknown");
        var unknownInitializer = findVariable(unknownFunction.body().statements(), "answer");
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(unknownInitializer.value(), "answer"),
                FrontendBindingKind.UNKNOWN
        );

        var bindingDiagnostics = bindingDiagnostics(preparedInput.diagnosticManager());
        assertEquals(1, bindingDiagnostics.size());
        assertTrue(bindingDiagnostics.getFirst().message().contains("answer"));
    }

    @Test
    void analyzeBindsOnlyChainHeadsAndStepArgumentsForExplicitReceiverChains() throws Exception {
        var preparedInput = prepareBindingInput("explicit_receiver_chain_heads.gd", """
                class_name ExplicitReceiverChainHeads
                extends Node
                
                func get_player():
                    pass
                
                func ping(player, i):
                    player.hp
                    self.hp
                    get_player().hp
                    player.move(i + 1)
                    player.list[i + 2]
                """);
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var playerUseSites = findNodes(
                pingFunction.body(),
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("player")
        );
        assertEquals(3, playerUseSites.size());
        for (var playerUseSite : playerUseSites) {
            assertBinding(preparedInput.analysisData(), playerUseSite, FrontendBindingKind.PARAMETER);
        }

        var iteratorUseSites = findNodes(
                pingFunction.body(),
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("i")
        );
        assertEquals(2, iteratorUseSites.size());
        for (var iteratorUseSite : iteratorUseSites) {
            assertBinding(preparedInput.analysisData(), iteratorUseSite, FrontendBindingKind.PARAMETER);
        }

        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "get_player"),
                FrontendBindingKind.METHOD
        );
        assertBinding(
                preparedInput.analysisData(),
                findSelfExpression(pingFunction.body()),
                FrontendBindingKind.SELF
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "1"),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "2"),
                FrontendBindingKind.LITERAL
        );
        assertEquals(9, preparedInput.analysisData().symbolBindings().size());
        assertTrue(preparedInput.analysisData().resolvedMembers().isEmpty());
        assertTrue(preparedInput.analysisData().resolvedCalls().isEmpty());
        assertTrue(preparedInput.diagnosticManager().snapshot().isEmpty());
    }

    @Test
    void analyzeBindsSupportedPartsAcrossComplexExpressionsExceptChainTails() throws Exception {
        var preparedInput = prepareBindingInput("complex_expression_bindings.gd", """
                class_name ComplexExpressionBindings
                extends Node
                
                func helper(value):
                    return value
                
                func get_player():
                    return null
                
                func ping(player, arr, key, cond, value, obj, matrix, row, col):
                    print([value + 1, -(arr[key + 2]), helper(value + 3), player.move(value + 4)])
                    print({value: arr[key + 5], "fallback": helper(value + 6)})
                    print("yes" if cond and not false else get_player().hp)
                    var awaited = await helper(value + 7)
                    print(obj is Node)
                    print(obj as Node)
                    value = matrix[row][col + 8]
                """);
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var helperFunction = findFunction(preparedInput.unit().ast(), "helper");
        var getPlayerFunction = findFunction(preparedInput.unit().ast(), "get_player");
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "value",
                FrontendBindingKind.PARAMETER,
                7
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "helper",
                FrontendBindingKind.METHOD,
                3
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "arr",
                FrontendBindingKind.PARAMETER,
                2
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "key",
                FrontendBindingKind.PARAMETER,
                2
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "player",
                FrontendBindingKind.PARAMETER,
                1
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "cond",
                FrontendBindingKind.PARAMETER,
                1
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "get_player",
                FrontendBindingKind.METHOD,
                1
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "obj",
                FrontendBindingKind.PARAMETER,
                2
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "matrix",
                FrontendBindingKind.PARAMETER,
                1
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "row",
                FrontendBindingKind.PARAMETER,
                1
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "col",
                FrontendBindingKind.PARAMETER,
                1
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "print",
                FrontendBindingKind.UTILITY_FUNCTION,
                5
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                helperFunction.body(),
                "value",
                FrontendBindingKind.PARAMETER,
                1
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "1"),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "2"),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "3"),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "4"),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "5"),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "6"),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "7"),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "8"),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "\"fallback\""),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "\"yes\""),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "false"),
                FrontendBindingKind.LITERAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(getPlayerFunction.body(), "null"),
                FrontendBindingKind.LITERAL
        );

        var publishedSymbolNames = preparedInput.analysisData().symbolBindings().values().stream()
                .map(FrontendBinding::symbolName)
                .toList();
        assertFalse(publishedSymbolNames.contains("move"));
        assertFalse(publishedSymbolNames.contains("hp"));
        assertFalse(publishedSymbolNames.contains("Node"));
        assertEquals(40, preparedInput.analysisData().symbolBindings().size());
        assertTrue(preparedInput.analysisData().resolvedMembers().isEmpty());
        assertTrue(preparedInput.analysisData().resolvedCalls().isEmpty());
        assertTrue(bindingDiagnostics(preparedInput.diagnosticManager()).isEmpty());
        assertTrue(unsupportedBindingDiagnostics(preparedInput.diagnosticManager()).isEmpty());
    }

    @Test
    void analyzeTreatsAssignmentSitesAsUsageAgnosticBindingsForNow() throws Exception {
        var preparedInput = prepareBindingInput("assignment_usage_agnostic.gd", """
                class_name AssignmentUsageAgnostic
                extends Node
                
                func ping(value, matrix, row, col):
                    value = matrix[row][col + 1]
                """);
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "value",
                FrontendBindingKind.PARAMETER,
                1
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "matrix",
                FrontendBindingKind.PARAMETER,
                1
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "row",
                FrontendBindingKind.PARAMETER,
                1
        );
        assertBindingsForName(
                preparedInput.analysisData(),
                pingFunction.body(),
                "col",
                FrontendBindingKind.PARAMETER,
                1
        );
        assertBinding(
                preparedInput.analysisData(),
                findLiteralExpression(pingFunction.body(), "1"),
                FrontendBindingKind.LITERAL
        );
        assertTrue(preparedInput.diagnosticManager().snapshot().isEmpty());
    }

    @Test
    void analyzeReportsDeferredBindingSubtreesAtRootsWithoutPublishingInnerBindings() throws Exception {
        var preparedInput = prepareBindingInput("deferred_binding_subtrees.gd", """
                class_name DeferredBindingSubtrees
                extends Node
                
                func helper():
                    pass
                
                func ping(seed = helper()):
                    var body_local = 0
                    var f = func():
                        return body_local
                    const answer = body_local
                    for item in [body_local]:
                        assert(item)
                    match body_local:
                        var bound when bound > 0:
                            assert(bound)
                    return body_local
                """);
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var defaultHelperUseSite = findIdentifierExpression(
                pingFunction.parameters().getFirst().defaultValue(),
                "helper"
        );
        assertNull(preparedInput.analysisData().symbolBindings().get(defaultHelperUseSite));

        var lambdaExpression = findNode(pingFunction.body(), LambdaExpression.class, _ -> true);
        var lambdaUseSite = findIdentifierExpression(lambdaExpression, "body_local");
        assertNull(preparedInput.analysisData().symbolBindings().get(lambdaUseSite));

        var constDeclaration = findVariable(pingFunction.body().statements(), "answer");
        var constInitializerUseSite = findIdentifierExpression(constDeclaration.value(), "body_local");
        assertNull(preparedInput.analysisData().symbolBindings().get(constInitializerUseSite));

        var forStatement = findNode(pingFunction.body(), ForStatement.class, _ -> true);
        var forIterableUseSite = findIdentifierExpression(forStatement.iterable(), "body_local");
        var forBodyUseSite = findIdentifierExpression(forStatement.body(), "item");
        assertNull(preparedInput.analysisData().symbolBindings().get(forIterableUseSite));
        assertNull(preparedInput.analysisData().symbolBindings().get(forBodyUseSite));

        var matchStatement = findNode(pingFunction.body(), MatchStatement.class, _ -> true);
        var matchValueUseSite = findIdentifierExpression(matchStatement.value(), "body_local");
        assertBinding(
                preparedInput.analysisData(),
                matchValueUseSite,
                FrontendBindingKind.LOCAL_VAR
        );
        var boundUseSites = findNodes(
                matchStatement,
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("bound")
        );
        for (var boundUseSite : boundUseSites) {
            assertNull(preparedInput.analysisData().symbolBindings().get(boundUseSite));
        }

        var outerReturn = assertInstanceOf(ReturnStatement.class, pingFunction.body().statements().getLast());
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(outerReturn, "body_local"),
                FrontendBindingKind.LOCAL_VAR
        );

        var unsupportedDiagnostics = unsupportedBindingDiagnostics(preparedInput.diagnosticManager());
        assertEquals(5, unsupportedDiagnostics.size());
        assertTrue(unsupportedDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
        ));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("parameter default")));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("lambda subtree")));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("block-local const initializer")));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("for subtree")));
        assertTrue(unsupportedDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("match subtree")));
        assertTrue(bindingDiagnostics(preparedInput.diagnosticManager()).isEmpty());
    }

    @Test
    void analyzeReportsSingleSkippedSubtreeWarningWhenCallableBodyScopeIsMissing() throws Exception {
        var preparedInput = prepareBindingInput("missing_callable_body_scope.gd", """
                class_name MissingCallableBodyScope
                extends Node
                
                func skipped(value):
                    print(value)
                
                func ok(value):
                    print(value)
                """);
        var analyzer = new FrontendTopBindingAnalyzer();
        var skippedFunction = findFunction(preparedInput.unit().ast(), "skipped");
        var okFunction = findFunction(preparedInput.unit().ast(), "ok");
        preparedInput.analysisData().scopesByAst().remove(skippedFunction.body());

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        assertNull(preparedInput.analysisData().symbolBindings().get(findIdentifierExpression(skippedFunction.body(), "value")));
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(okFunction.body(), "value"),
                FrontendBindingKind.PARAMETER
        );
        var unsupportedDiagnostics = unsupportedBindingDiagnostics(preparedInput.diagnosticManager());
        assertEquals(1, unsupportedDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.WARNING, unsupportedDiagnostics.getFirst().severity());
        assertTrue(unsupportedDiagnostics.getFirst().message().contains("skipped subtree"));
        assertTrue(bindingDiagnostics(preparedInput.diagnosticManager()).isEmpty());
    }

    @Test
    void analyzeReportsSingleSkippedSubtreeWarningWhenNestedBlockScopeIsMissing() throws Exception {
        var preparedInput = prepareBindingInput("missing_nested_block_scope.gd", """
                class_name MissingNestedBlockScope
                extends Node
                
                func ping(value):
                    if value > 0:
                        print(value)
                    print(value)
                """);
        var analyzer = new FrontendTopBindingAnalyzer();
        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var ifStatement = findNode(pingFunction.body(), dev.superice.gdparser.frontend.ast.IfStatement.class, _ -> true);
        preparedInput.analysisData().scopesByAst().remove(ifStatement.body());

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var printedValues = findNodes(
                pingFunction.body(),
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("value")
        );
        assertEquals(3, printedValues.size());
        assertBinding(preparedInput.analysisData(), printedValues.getFirst(), FrontendBindingKind.PARAMETER);
        assertNull(preparedInput.analysisData().symbolBindings().get(printedValues.get(1)));
        assertBinding(preparedInput.analysisData(), printedValues.get(2), FrontendBindingKind.PARAMETER);
        var unsupportedDiagnostics = unsupportedBindingDiagnostics(preparedInput.diagnosticManager());
        assertEquals(1, unsupportedDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.WARNING, unsupportedDiagnostics.getFirst().severity());
        assertTrue(unsupportedDiagnostics.getFirst().message().contains("skipped subtree"));
        assertTrue(bindingDiagnostics(preparedInput.diagnosticManager()).isEmpty());
    }

    @Test
    void analyzePublishesBlockedMembersAndSelfInStaticContextWithoutFallingBack() throws Exception {
        var preparedInput = prepareBindingInput("static_context_bindings.gd", """
                class_name StaticContextBindings
                extends Node
                
                signal changed(value: int)
                var hp = 1
                
                static func ping():
                    print(hp)
                    print(changed)
                    print(self)
                """);
        var analyzer = new FrontendTopBindingAnalyzer();

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "hp"),
                FrontendBindingKind.PROPERTY
        );
        assertBinding(
                preparedInput.analysisData(),
                findIdentifierExpression(pingFunction.body(), "changed"),
                FrontendBindingKind.SIGNAL
        );
        assertBinding(
                preparedInput.analysisData(),
                findSelfExpression(pingFunction.body()),
                FrontendBindingKind.SELF
        );

        var bindingDiagnostics = bindingDiagnostics(preparedInput.diagnosticManager());
        assertEquals(3, bindingDiagnostics.size());
        assertTrue(bindingDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("hp")));
        assertTrue(bindingDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("changed")));
        assertTrue(bindingDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("self")));
    }

    @Test
    void analyzeReportsUnsupportedBindingSubtreeWhenVisitedUseSiteScopeIsMissing() throws Exception {
        var preparedInput = prepareBindingInput("missing_use_site_scope.gd", """
                class_name MissingUseSiteScope
                extends Node
                
                func ping(value):
                    print(value)
                """);
        var analyzer = new FrontendTopBindingAnalyzer();
        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var useSite = findIdentifierExpression(pingFunction.body(), "value");
        preparedInput.analysisData().scopesByAst().remove(useSite);

        analyzer.analyze(preparedInput.analysisData(), preparedInput.diagnosticManager());

        assertNull(preparedInput.analysisData().symbolBindings().get(useSite));
        var unsupportedDiagnostics = preparedInput.diagnosticManager().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.unsupported_binding_subtree"))
                .toList();
        assertEquals(1, unsupportedDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.WARNING, unsupportedDiagnostics.getFirst().severity());
        assertTrue(unsupportedDiagnostics.getFirst().message().contains("value"));
    }

    private static @NotNull List<FrontendDiagnostic> bindingDiagnostics(@NotNull DiagnosticManager diagnosticManager) {
        return diagnosticManager.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.binding"))
                .toList();
    }

    private static @NotNull List<FrontendDiagnostic> unsupportedBindingDiagnostics(
            @NotNull DiagnosticManager diagnosticManager
    ) {
        return diagnosticManager.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.unsupported_binding_subtree"))
                .toList();
    }

    private static @NotNull LirFunctionDef createFunctionDef(@NotNull String name, boolean isStatic) {
        var function = new LirFunctionDef(name);
        function.setReturnType(GdVariantType.VARIANT);
        function.setStatic(isStatic);
        return function;
    }

    private static void assertBinding(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Node useSite,
            @NotNull FrontendBindingKind expectedKind
    ) {
        var binding = analysisData.symbolBindings().get(useSite);
        assertEquals(expectedKind, Objects.requireNonNull(binding, "binding must not be null").kind());
    }

    private static void assertBindingsForName(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Node root,
            @NotNull String symbolName,
            @NotNull FrontendBindingKind expectedKind,
            int expectedCount
    ) {
        var useSites = findNodes(
                root,
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals(symbolName)
        );
        assertEquals(expectedCount, useSites.size(), "Unexpected use-site count for " + symbolName);
        for (var useSite : useSites) {
            assertBinding(analysisData, useSite, expectedKind);
        }
    }

    private static @NotNull PreparedBindingInput prepareBindingInput(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return prepareBindingInput(fileName, source, new ClassRegistry(ExtensionApiLoader.loadDefault()));
    }

    private static @NotNull PreparedBindingInput prepareBindingInput(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull ClassRegistry classRegistry
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnosticManager = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnosticManager);
        assertTrue(diagnosticManager.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnosticManager.snapshot());

        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(
                "test_module",
                List.of(unit),
                Objects.requireNonNull(classRegistry, "classRegistry must not be null"),
                diagnosticManager,
                analysisData
        );
        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendScopeAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendVariableAnalyzer().analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        return new PreparedBindingInput(unit, analysisData, diagnosticManager, classRegistry);
    }

    private static @NotNull FunctionDeclaration findFunction(
            @NotNull SourceFile sourceFile,
            @NotNull String name
    ) {
        return findNode(
                sourceFile,
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals(name)
        );
    }

    private static @NotNull VariableDeclaration findVariable(
            @NotNull List<?> statements,
            @NotNull String name
    ) {
        return statements.stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(variableDeclaration -> variableDeclaration.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Variable not found: " + name));
    }

    private static @NotNull IdentifierExpression findIdentifierExpression(
            @NotNull Node root,
            @NotNull String name
    ) {
        return findNode(
                root,
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals(name)
        );
    }

    private static @NotNull LiteralExpression findLiteralExpression(
            @NotNull Node root,
            @NotNull String sourceText
    ) {
        return findNode(
                root,
                LiteralExpression.class,
                literalExpression -> literalExpression.sourceText().equals(sourceText)
        );
    }

    private static @NotNull SelfExpression findSelfExpression(@NotNull Node root) {
        return findNode(root, SelfExpression.class, _ -> true);
    }

    private static <T extends Node> @NotNull List<T> findNodes(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return List.copyOf(matches);
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        return findNodes(root, nodeType, predicate).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node not found: " + nodeType.getSimpleName()));
    }

    private static <T extends Node> void collectMatchingNodes(
            @NotNull Node node,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
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

    private record PreparedBindingInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull ClassRegistry classRegistry
    ) {
    }
}
