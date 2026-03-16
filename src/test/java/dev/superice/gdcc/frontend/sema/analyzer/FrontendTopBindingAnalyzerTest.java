package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
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
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
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
    void analyzeBindsTopLevelTypeMetaChainHeadsForEngineAndLexicalInnerClasses() throws Exception {
        var preparedInput = prepareBindingInput("type_meta_chain_head.gd", """
                class_name TypeMetaChainHead
                extends Node
                
                class Inner:
                    static func build():
                        return null
                
                func ping():
                    Node.new()
                    Inner.build()
                """);
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
                findIdentifierExpression(pingFunction.body(), "Inner"),
                FrontendBindingKind.TYPE_META
        );
        assertTrue(preparedInput.diagnosticManager().snapshot().isEmpty());
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
    void analyzeRejectsUnsupportedTypeMetaSourcesAndDoesNotMispublishThemAsTypeMeta() throws Exception {
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
        assertNull(preparedInput.analysisData().symbolBindings().get(findIdentifierExpression(pingFunction.body(), "String")));
        assertNull(preparedInput.analysisData().symbolBindings().get(findIdentifierExpression(pingFunction.body(), "Alias")));

        var bindingDiagnostics = bindingDiagnostics(preparedInput.diagnosticManager());
        assertEquals(2, bindingDiagnostics.size());
        assertTrue(bindingDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("String")));
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
        assertTrue(unsupportedDiagnostics.getFirst().message().contains("value"));
    }

    private static @NotNull List<FrontendDiagnostic> bindingDiagnostics(@NotNull DiagnosticManager diagnosticManager) {
        return diagnosticManager.snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.binding"))
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

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return matches.stream()
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
