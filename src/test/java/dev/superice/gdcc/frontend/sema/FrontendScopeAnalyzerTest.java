package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.BlockScopeKind;
import dev.superice.gdcc.frontend.scope.CallableScope;
import dev.superice.gdcc.frontend.scope.CallableScopeKind;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdparser.frontend.ast.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendScopeAnalyzerTest {
    private static final Range SYNTHETIC_RANGE = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void analyzeRejectsAnalysisDataWithoutPublishedModuleSkeletonBoundary() throws Exception {
        var analyzer = new FrontendScopeAnalyzer();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var diagnostics = new DiagnosticManager();

        assertThrows(IllegalStateException.class, () -> analyzer.analyze(registry, analysisData, diagnostics));
    }

    @Test
    void analyzeRejectsAnalysisDataWithoutPublishedPreScopeDiagnosticsBoundary() throws Exception {
        var analyzer = new FrontendScopeAnalyzer();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var diagnostics = new DiagnosticManager();
        analysisData.updateModuleSkeleton(new FrontendModuleSkeleton("test_module", List.of(), List.of(), diagnostics.snapshot()));

        assertThrows(IllegalStateException.class, () -> analyzer.analyze(registry, analysisData, diagnostics));
    }

    @Test
    void analyzeClearsStaleScopeSideTableWhenPublishedSkeletonHasNoUnits() throws Exception {
        var analyzer = new FrontendScopeAnalyzer();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = publishedAnalysisData();
        var originalSideTable = analysisData.scopesByAst();
        var staleAstNode = new PassStatement(SYNTHETIC_RANGE);
        analysisData.scopesByAst().put(staleAstNode, registry);

        analyzer.analyze(registry, analysisData, new DiagnosticManager());

        assertSame(originalSideTable, analysisData.scopesByAst());
        assertFalse(analysisData.scopesByAst().containsKey(staleAstNode));
        assertTrue(analysisData.scopesByAst().isEmpty());
    }

    @Test
    void analyzeOnlyExposesTheManagerAwarePublicEntryPoint() {
        var analyzeMethods = Arrays.stream(FrontendScopeAnalyzer.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().equals("analyze"))
                .toList();

        assertEquals(1, analyzeMethods.size());
        assertArrayEquals(
                new Class<?>[]{ClassRegistry.class, FrontendAnalysisData.class, DiagnosticManager.class},
                analyzeMethods.getFirst().getParameterTypes()
        );
    }

    @Test
    void analyzeBuildsTopLevelAndCallableScopeGraphWithDistinctKinds() throws Exception {
        var analyzed = analyze("""
                class_name ScopePhaseExample
                extends Node
                
                var builder := func(item: int, fallback = item):
                    return fallback
                
                func ping(value: int, alias = value) -> int:
                    var typed_local: int = value
                    var local_lambda := func(offset: int):
                        return offset
                    return alias
                
                func _init(seed: int, mirror = seed):
                    pass
                """);
        var sourceFile = analyzed.unit().ast();
        var scopesByAst = analyzed.analysisData().scopesByAst();

        assertFalse(scopesByAst.isEmpty());

        var sourceScope = assertInstanceOf(ClassScope.class, scopesByAst.get(sourceFile));
        var builderDeclaration = findStatement(sourceFile.statements(), VariableDeclaration.class, variable -> variable.name().equals("builder"));
        assertSame(sourceScope, scopesByAst.get(builderDeclaration));

        var builderLambda = assertInstanceOf(LambdaExpression.class, builderDeclaration.value());
        var builderLambdaScope = assertInstanceOf(CallableScope.class, scopesByAst.get(builderLambda));
        assertEquals(CallableScopeKind.LAMBDA_EXPRESSION, builderLambdaScope.kind());
        assertSame(sourceScope, builderLambdaScope.getParentScope());

        var builderLambdaBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(builderLambda.body()));
        assertEquals(BlockScopeKind.LAMBDA_BODY, builderLambdaBodyScope.kind());
        assertSame(builderLambdaScope, builderLambdaBodyScope.getParentScope());
        assertSame(builderLambdaScope, scopesByAst.get(builderLambda.parameters().getFirst()));
        assertSame(builderLambdaScope, scopesByAst.get(builderLambda.parameters().getLast().defaultValue()));
        var builderLambdaReturn = assertInstanceOf(ReturnStatement.class, builderLambda.body().statements().getFirst());
        assertSame(builderLambdaBodyScope, scopesByAst.get(builderLambdaReturn.value()));

        var pingFunction = findStatement(sourceFile.statements(), FunctionDeclaration.class, function -> function.name().equals("ping"));
        var pingScope = assertInstanceOf(CallableScope.class, scopesByAst.get(pingFunction));
        assertEquals(CallableScopeKind.FUNCTION_DECLARATION, pingScope.kind());
        assertSame(sourceScope, pingScope.getParentScope());
        assertSame(pingScope, scopesByAst.get(pingFunction.parameters().getFirst()));
        assertSame(pingScope, scopesByAst.get(pingFunction.parameters().getLast().defaultValue()));
        assertSame(pingScope, scopesByAst.get(pingFunction.returnType()));

        var pingBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(pingFunction.body()));
        assertEquals(BlockScopeKind.FUNCTION_BODY, pingBodyScope.kind());
        assertSame(pingScope, pingBodyScope.getParentScope());
        var typedLocalDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("typed_local")
        );
        assertSame(pingBodyScope, scopesByAst.get(typedLocalDeclaration.type()));

        var localLambdaDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("local_lambda")
        );
        var localLambda = assertInstanceOf(LambdaExpression.class, localLambdaDeclaration.value());
        var localLambdaScope = assertInstanceOf(CallableScope.class, scopesByAst.get(localLambda));
        assertEquals(CallableScopeKind.LAMBDA_EXPRESSION, localLambdaScope.kind());
        assertSame(pingBodyScope, localLambdaScope.getParentScope());
        var localLambdaBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(localLambda.body()));
        assertEquals(BlockScopeKind.LAMBDA_BODY, localLambdaBodyScope.kind());
        assertSame(localLambdaScope, localLambdaBodyScope.getParentScope());
        assertSame(localLambdaScope, scopesByAst.get(localLambda.parameters().getFirst()));

        var pingReturn = assertInstanceOf(ReturnStatement.class, pingFunction.body().statements().getLast());
        assertSame(pingBodyScope, scopesByAst.get(pingReturn.value()));

        var constructor = findStatement(sourceFile.statements(), ConstructorDeclaration.class, _ -> true);
        var constructorScope = assertInstanceOf(CallableScope.class, scopesByAst.get(constructor));
        assertEquals(CallableScopeKind.CONSTRUCTOR_DECLARATION, constructorScope.kind());
        assertSame(sourceScope, constructorScope.getParentScope());
        assertSame(constructorScope, scopesByAst.get(constructor.parameters().getFirst()));
        assertSame(constructorScope, scopesByAst.get(constructor.parameters().getLast().defaultValue()));

        var constructorBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(constructor.body()));
        assertEquals(BlockScopeKind.CONSTRUCTOR_BODY, constructorBodyScope.kind());
        assertSame(constructorScope, constructorBodyScope.getParentScope());
    }

    @Test
    void semanticAnalysisBuildsConstructorScopeFromValidParsedGdScript() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(
                java.nio.file.Path.of("tmp", "constructor_scope_probe.gd"),
                """
                        class_name ConstructorScopeProbe
                        extends Node
                        
                        func _init(seed: int, mirror = seed):
                            var typed_local: int = mirror
                            pass
                        """,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());

        var constructor = findStatement(unit.ast().statements(), ConstructorDeclaration.class, _ -> true);
        assertEquals(2, constructor.parameters().size());

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze("test_module", List.of(unit), registry, diagnostics);
        var scopesByAst = analysisData.scopesByAst();

        var sourceScope = assertInstanceOf(ClassScope.class, scopesByAst.get(unit.ast()));
        var constructorScope = assertInstanceOf(CallableScope.class, scopesByAst.get(constructor));
        assertEquals(CallableScopeKind.CONSTRUCTOR_DECLARATION, constructorScope.kind());
        assertSame(sourceScope, constructorScope.getParentScope());
        assertSame(constructorScope, scopesByAst.get(constructor.parameters().getFirst()));
        assertSame(constructorScope, scopesByAst.get(constructor.parameters().getLast().defaultValue()));

        var constructorBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(constructor.body()));
        assertEquals(BlockScopeKind.CONSTRUCTOR_BODY, constructorBodyScope.kind());
        assertSame(constructorScope, constructorBodyScope.getParentScope());

        var typedLocal = findStatement(
                constructor.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("typed_local")
        );
        assertSame(constructorBodyScope, scopesByAst.get(typedLocal.type()));
    }

    @Test
    void analyzeKeepsPhase3AndPhase4BoundariesDeferredWhileStillCoveringSafeOuterExpressions() throws Exception {
        var analyzed = analyze("""
                class_name DeferredBoundaries
                extends Node
                
                class Inner:
                    func nested():
                        pass
                
                func ping(value: int) -> int:
                    if value > 0:
                        var from_if := value
                    while value > 1:
                        pass
                    for item: int in [1, 2]:
                        pass
                    match value:
                        var bound when bound > 0:
                            pass
                    return value
                """);
        var sourceFile = analyzed.unit().ast();
        var scopesByAst = analyzed.analysisData().scopesByAst();
        var pingFunction = findStatement(sourceFile.statements(), FunctionDeclaration.class, function -> function.name().equals("ping"));
        var pingBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(pingFunction.body()));

        var innerClass = findStatement(sourceFile.statements(), ClassDeclaration.class, declaration -> declaration.name().equals("Inner"));
        assertFalse(scopesByAst.containsKey(innerClass));

        var ifStatement = findStatement(pingFunction.body().statements(), IfStatement.class, _ -> true);
        assertSame(pingBodyScope, scopesByAst.get(ifStatement));
        assertSame(pingBodyScope, scopesByAst.get(ifStatement.condition()));
        assertFalse(scopesByAst.containsKey(ifStatement.body()));
        var fromIf = findStatement(ifStatement.body().statements(), VariableDeclaration.class, variable -> variable.name().equals("from_if"));
        assertFalse(scopesByAst.containsKey(fromIf));

        var whileStatement = findStatement(pingFunction.body().statements(), WhileStatement.class, _ -> true);
        assertSame(pingBodyScope, scopesByAst.get(whileStatement));
        assertSame(pingBodyScope, scopesByAst.get(whileStatement.condition()));
        assertFalse(scopesByAst.containsKey(whileStatement.body()));

        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        assertSame(pingBodyScope, scopesByAst.get(forStatement));
        assertSame(pingBodyScope, scopesByAst.get(forStatement.iteratorType()));
        assertSame(pingBodyScope, scopesByAst.get(forStatement.iterable()));
        assertFalse(scopesByAst.containsKey(forStatement.body()));

        var matchStatement = findStatement(pingFunction.body().statements(), MatchStatement.class, _ -> true);
        assertSame(pingBodyScope, scopesByAst.get(matchStatement));
        assertSame(pingBodyScope, scopesByAst.get(matchStatement.value()));
        var firstSection = matchStatement.sections().getFirst();
        assertFalse(scopesByAst.containsKey(firstSection));
        assertFalse(scopesByAst.containsKey(firstSection.body()));
    }

    @Test
    void analyzeRecordsParameterScopeFactsBeforePhase5BindingPrefill() throws Exception {
        var analyzed = analyze("""
                class_name ParameterBoundary
                extends Node
                
                func ping(value: int, alias = value) -> int:
                    return alias
                """);
        var sourceFile = analyzed.unit().ast();
        var scopesByAst = analyzed.analysisData().scopesByAst();
        var pingFunction = findStatement(sourceFile.statements(), FunctionDeclaration.class, function -> function.name().equals("ping"));
        var pingScope = assertInstanceOf(CallableScope.class, scopesByAst.get(pingFunction));

        assertSame(pingScope, scopesByAst.get(pingFunction.parameters().getFirst()));
        assertSame(pingScope, scopesByAst.get(pingFunction.parameters().getLast()));
        assertSame(pingScope, scopesByAst.get(pingFunction.parameters().getLast().defaultValue()));
        assertNull(pingScope.resolveValue("value"));
        assertNull(pingScope.resolveValue("alias"));
    }

    @Test
    void semanticAnalyzerPublishesSkeletonBoundaryBeforeScopePhaseAndRefreshesDiagnosticsAfterIt() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(java.nio.file.Path.of("tmp", "scope_phase_probe.gd"), """
                class_name ScopePhaseProbe
                extends Node
                
                func ping(value):
                    pass
                """, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var probeScopeAnalyzer = new RecordingScopeAnalyzer();
        var analyzer = new FrontendSemanticAnalyzer(new FrontendClassSkeletonBuilder(), probeScopeAnalyzer);

        var result = analyzer.analyze("test_module", List.of(unit), registry, diagnostics);

        assertTrue(probeScopeAnalyzer.invoked);
        assertTrue(probeScopeAnalyzer.moduleSkeletonPublished);
        assertTrue(probeScopeAnalyzer.preScopeDiagnosticsMatchedManager);
        assertFalse(result.scopesByAst().isEmpty());
        assertTrue(result.scopesByAst().containsKey(unit.ast()));
        var pingFunction = findStatement(unit.ast().statements(), FunctionDeclaration.class, function -> function.name().equals("ping"));
        assertTrue(result.scopesByAst().containsKey(pingFunction));
        assertEquals(probeScopeAnalyzer.preScopeDiagnostics, result.moduleSkeleton().diagnostics());
        assertEquals(probeScopeAnalyzer.preScopeDiagnostics.size() + 1, result.diagnostics().size());
        assertEquals("sema.scope_phase_probe", result.diagnostics().getLast().category());
        assertEquals(result.diagnostics(), diagnostics.snapshot());
    }

    private FrontendAnalysisData publishedAnalysisData() {
        var analysisData = FrontendAnalysisData.bootstrap();
        var boundaryDiagnostics = new DiagnosticSnapshot(List.of());
        analysisData.updateModuleSkeleton(new FrontendModuleSkeleton("test_module", List.of(), List.of(), boundaryDiagnostics));
        analysisData.updateDiagnostics(boundaryDiagnostics);
        return analysisData;
    }

    private AnalyzedUnit analyze(@NotNull String source) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(java.nio.file.Path.of("tmp", "frontend_scope_analyzer_test.gd"), source, diagnostics);
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();
        var analysisData = analyzer.analyze("test_module", List.of(unit), registry, diagnostics);
        return new AnalyzedUnit(unit, analysisData);
    }

    private <T extends Statement> T findStatement(
            @NotNull List<Statement> statements,
            @NotNull Class<T> statementType,
            @NotNull Predicate<T> predicate
    ) {
        return statements.stream()
                .filter(statementType::isInstance)
                .map(statementType::cast)
                .filter(predicate)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Statement not found: " + statementType.getSimpleName()));
    }

    /// Scope-phase probe used by the integration test to anchor phase ordering.
    ///
    /// It observes what `FrontendSemanticAnalyzer` has already published when the scope phase
    /// starts, then appends a synthetic diagnostic to prove that the outer analyzer refreshes the
    /// final diagnostics snapshot after the scope phase returns.
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
            moduleSkeletonPublished = analysisData.moduleSkeleton().classDefs().size() == 1;
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

    private record AnalyzedUnit(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData
    ) {
    }
}
