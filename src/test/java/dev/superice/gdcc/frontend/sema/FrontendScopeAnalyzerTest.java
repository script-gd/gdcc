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
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
        analysisData.updateModuleSkeleton(new FrontendModuleSkeleton("test_module", List.of(), diagnostics.snapshot()));

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
    void analyzeBuildsDistinctIfElifElseScopesAndKeepsConditionsInOuterScope() throws Exception {
        var analyzed = analyze("""
                class_name BranchScopeCoverage
                extends Node
                
                func ping(value: int) -> int:
                    if value > 0:
                        var positive := value
                    elif value == 0:
                        var zero := value
                    else:
                        var negative := value
                    return value
                """);
        var sourceFile = analyzed.unit().ast();
        var scopesByAst = analyzed.analysisData().scopesByAst();
        var pingFunction = findStatement(sourceFile.statements(), FunctionDeclaration.class, function -> function.name().equals("ping"));
        var pingBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(pingFunction.body()));

        var ifStatement = findStatement(pingFunction.body().statements(), IfStatement.class, _ -> true);
        assertSame(pingBodyScope, scopesByAst.get(ifStatement));
        assertSame(pingBodyScope, scopesByAst.get(ifStatement.condition()));
        var ifBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(ifStatement.body()));
        assertEquals(BlockScopeKind.IF_BODY, ifBodyScope.kind());
        assertSame(pingBodyScope, ifBodyScope.getParentScope());
        var positive = findStatement(ifStatement.body().statements(), VariableDeclaration.class, variable -> variable.name().equals("positive"));
        assertSame(ifBodyScope, scopesByAst.get(positive));
        assertSame(ifBodyScope, scopesByAst.get(positive.value()));

        var elifClause = ifStatement.elifClauses().getFirst();
        assertSame(pingBodyScope, scopesByAst.get(elifClause));
        assertSame(pingBodyScope, scopesByAst.get(elifClause.condition()));
        var elifBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(elifClause.body()));
        assertEquals(BlockScopeKind.ELIF_BODY, elifBodyScope.kind());
        assertSame(pingBodyScope, elifBodyScope.getParentScope());
        var zero = findStatement(elifClause.body().statements(), VariableDeclaration.class, variable -> variable.name().equals("zero"));
        assertSame(elifBodyScope, scopesByAst.get(zero));
        assertSame(elifBodyScope, scopesByAst.get(zero.value()));

        var elseBody = assertInstanceOf(Block.class, ifStatement.elseBody());
        var elseBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(elseBody));
        assertEquals(BlockScopeKind.ELSE_BODY, elseBodyScope.kind());
        assertSame(pingBodyScope, elseBodyScope.getParentScope());
        var negative = findStatement(elseBody.statements(), VariableDeclaration.class, variable -> variable.name().equals("negative"));
        assertSame(elseBodyScope, scopesByAst.get(negative));
        assertSame(elseBodyScope, scopesByAst.get(negative.value()));

        assertNotSame(ifBodyScope, elifBodyScope);
        assertNotSame(ifBodyScope, elseBodyScope);
        assertNotSame(elifBodyScope, elseBodyScope);
    }

    @Test
    void analyzeBuildsLoopAndMatchBranchScopesWhileLeavingDeferredBindingsUnfilled() throws Exception {
        var analyzed = analyze("""
                class_name LoopAndMatchScopes
                extends Node
                
                func ping(value: int) -> int:
                    while value > 1:
                        var from_while := value
                    for item: int in [value, value + 1]:
                        var from_for := item
                    match value:
                        var bound when bound > 0:
                            var from_match := bound
                        0:
                            pass
                    return value
                """);
        var sourceFile = analyzed.unit().ast();
        var scopesByAst = analyzed.analysisData().scopesByAst();
        var pingFunction = findStatement(sourceFile.statements(), FunctionDeclaration.class, function -> function.name().equals("ping"));
        var pingBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(pingFunction.body()));

        var whileStatement = findStatement(pingFunction.body().statements(), WhileStatement.class, _ -> true);
        assertSame(pingBodyScope, scopesByAst.get(whileStatement));
        assertSame(pingBodyScope, scopesByAst.get(whileStatement.condition()));
        var whileBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(whileStatement.body()));
        assertEquals(BlockScopeKind.WHILE_BODY, whileBodyScope.kind());
        assertSame(pingBodyScope, whileBodyScope.getParentScope());
        var fromWhile = findStatement(whileStatement.body().statements(), VariableDeclaration.class, variable -> variable.name().equals("from_while"));
        assertSame(whileBodyScope, scopesByAst.get(fromWhile));
        assertSame(whileBodyScope, scopesByAst.get(fromWhile.value()));

        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        assertSame(pingBodyScope, scopesByAst.get(forStatement));
        assertSame(pingBodyScope, scopesByAst.get(forStatement.iteratorType()));
        assertSame(pingBodyScope, scopesByAst.get(forStatement.iterable()));
        var forBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(forStatement.body()));
        assertEquals(BlockScopeKind.FOR_BODY, forBodyScope.kind());
        assertSame(pingBodyScope, forBodyScope.getParentScope());
        var fromFor = findStatement(forStatement.body().statements(), VariableDeclaration.class, variable -> variable.name().equals("from_for"));
        assertSame(forBodyScope, scopesByAst.get(fromFor));
        assertSame(forBodyScope, scopesByAst.get(fromFor.value()));
        assertNull(forBodyScope.resolveValue("item"));

        var matchStatement = findStatement(pingFunction.body().statements(), MatchStatement.class, _ -> true);
        assertSame(pingBodyScope, scopesByAst.get(matchStatement));
        assertSame(pingBodyScope, scopesByAst.get(matchStatement.value()));

        var firstSection = matchStatement.sections().getFirst();
        var firstSectionScope = assertInstanceOf(BlockScope.class, scopesByAst.get(firstSection));
        assertEquals(BlockScopeKind.MATCH_SECTION_BODY, firstSectionScope.kind());
        assertSame(pingBodyScope, firstSectionScope.getParentScope());
        assertSame(firstSectionScope, scopesByAst.get(firstSection.body()));
        var firstPattern = assertInstanceOf(PatternBindingExpression.class, firstSection.patterns().getFirst());
        assertSame(firstSectionScope, scopesByAst.get(firstPattern));
        assertSame(firstSectionScope, scopesByAst.get(firstSection.guard()));
        var fromMatch = findStatement(firstSection.body().statements(), VariableDeclaration.class, variable -> variable.name().equals("from_match"));
        assertSame(firstSectionScope, scopesByAst.get(fromMatch));
        assertSame(firstSectionScope, scopesByAst.get(fromMatch.value()));
        assertNull(firstSectionScope.resolveValue("bound"));

        var secondSection = matchStatement.sections().get(1);
        var secondSectionScope = assertInstanceOf(BlockScope.class, scopesByAst.get(secondSection));
        assertEquals(BlockScopeKind.MATCH_SECTION_BODY, secondSectionScope.kind());
        assertSame(pingBodyScope, secondSectionScope.getParentScope());
        assertSame(secondSectionScope, scopesByAst.get(secondSection.body()));

        assertNotSame(whileBodyScope, forBodyScope);
        assertNotSame(firstSectionScope, secondSectionScope);
    }

    @Test
    void analyzeCreatesBlockStatementScopeForStandaloneBlockNodes() throws Exception {
        var nestedBlock = new Block(List.of(new PassStatement(SYNTHETIC_RANGE)), SYNTHETIC_RANGE);
        var functionBody = new Block(List.of(nestedBlock), SYNTHETIC_RANGE);
        var function = new FunctionDeclaration("ping", List.of(), null, false, functionBody, SYNTHETIC_RANGE);
        var sourceFile = new SourceFile(List.of(function), SYNTHETIC_RANGE);
        var unit = new FrontendSourceUnit(java.nio.file.Path.of("tmp", "synthetic_block_scope.gd"), "", sourceFile);

        var boundaryDiagnostics = new DiagnosticSnapshot(List.of());
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.updateModuleSkeleton(
                new FrontendModuleSkeleton(
                        "test_module",
                        List.of(new FrontendSourceClassRelation(
                                unit,
                                new dev.superice.gdcc.lir.LirClassDef("SyntheticBlockScope", "Node"),
                                List.of()
                        )),
                        boundaryDiagnostics
                )
        );
        analysisData.updateDiagnostics(boundaryDiagnostics);

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        new FrontendScopeAnalyzer().analyze(registry, analysisData, new DiagnosticManager());

        var scopesByAst = analysisData.scopesByAst();
        var sourceScope = assertInstanceOf(ClassScope.class, scopesByAst.get(sourceFile));
        var functionScope = assertInstanceOf(CallableScope.class, scopesByAst.get(function));
        assertSame(sourceScope, functionScope.getParentScope());

        var functionBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(functionBody));
        assertEquals(BlockScopeKind.FUNCTION_BODY, functionBodyScope.kind());
        assertSame(functionScope, functionBodyScope.getParentScope());

        var nestedBlockScope = assertInstanceOf(BlockScope.class, scopesByAst.get(nestedBlock));
        assertEquals(BlockScopeKind.BLOCK_STATEMENT, nestedBlockScope.kind());
        assertSame(functionBodyScope, nestedBlockScope.getParentScope());
        var nestedPass = assertInstanceOf(PassStatement.class, nestedBlock.statements().getFirst());
        assertSame(nestedBlockScope, scopesByAst.get(nestedPass));
    }

    @Test
    void analyzeBuildsInnerClassScopesFromExplicitSourceRelations() throws Exception {
        var analyzed = analyze("""
                class_name MaterializedInnerClassBoundary
                extends Node
                
                var outer_prop: int = 1
                
                class Inner:
                    var inner_prop: int = 2
                
                    func nested(value: int) -> int:
                        if value > 0:
                            return value
                        return inner_prop
                
                    class Deep:
                        func deeper() -> int:
                            return 1
                
                func ping(value: int) -> int:
                    if value > 0:
                        pass
                    return value
                """);
        var sourceFile = analyzed.unit().ast();
        var scopesByAst = analyzed.analysisData().scopesByAst();
        var sourceScope = assertInstanceOf(ClassScope.class, scopesByAst.get(sourceFile));

        var innerClass = findStatement(sourceFile.statements(), ClassDeclaration.class, declaration -> declaration.name().equals("Inner"));
        var innerScope = assertInstanceOf(ClassScope.class, scopesByAst.get(innerClass));
        assertSame(sourceScope, innerScope.getParentScope());
        assertEquals("Inner", innerScope.getCurrentClass().getName());
        assertSame(innerScope, scopesByAst.get(innerClass.body()));
        assertNull(innerScope.resolveValue("outer_prop"));

        var nestedFunction = findStatement(innerClass.body().statements(), FunctionDeclaration.class, function -> function.name().equals("nested"));
        var nestedFunctionScope = assertInstanceOf(CallableScope.class, scopesByAst.get(nestedFunction));
        assertEquals(CallableScopeKind.FUNCTION_DECLARATION, nestedFunctionScope.kind());
        assertSame(innerScope, nestedFunctionScope.getParentScope());
        assertSame(nestedFunctionScope, scopesByAst.get(nestedFunction.parameters().getFirst()));
        assertSame(nestedFunctionScope, scopesByAst.get(nestedFunction.returnType()));

        var nestedFunctionBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(nestedFunction.body()));
        assertEquals(BlockScopeKind.FUNCTION_BODY, nestedFunctionBodyScope.kind());
        assertSame(nestedFunctionScope, nestedFunctionBodyScope.getParentScope());

        var nestedIf = findStatement(nestedFunction.body().statements(), IfStatement.class, _ -> true);
        var nestedIfBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(nestedIf.body()));
        assertEquals(BlockScopeKind.IF_BODY, nestedIfBodyScope.kind());
        assertSame(nestedFunctionBodyScope, nestedIfBodyScope.getParentScope());
        var fallbackReturn = assertInstanceOf(ReturnStatement.class, nestedFunction.body().statements().getLast());
        assertSame(nestedFunctionBodyScope, scopesByAst.get(fallbackReturn.value()));

        var deepClass = findStatement(innerClass.body().statements(), ClassDeclaration.class, declaration -> declaration.name().equals("Deep"));
        var deepScope = assertInstanceOf(ClassScope.class, scopesByAst.get(deepClass));
        assertSame(innerScope, deepScope.getParentScope());
        assertEquals("Deep", deepScope.getCurrentClass().getName());
        assertSame(deepScope, scopesByAst.get(deepClass.body()));

        var deeperFunction = findStatement(deepClass.body().statements(), FunctionDeclaration.class, function -> function.name().equals("deeper"));
        var deeperFunctionScope = assertInstanceOf(CallableScope.class, scopesByAst.get(deeperFunction));
        assertSame(deepScope, deeperFunctionScope.getParentScope());
        var deeperBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(deeperFunction.body()));
        assertSame(deeperFunctionScope, deeperBodyScope.getParentScope());

        var pingFunction = findStatement(sourceFile.statements(), FunctionDeclaration.class, function -> function.name().equals("ping"));
        var pingBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(pingFunction.body()));
        var ifStatement = findStatement(pingFunction.body().statements(), IfStatement.class, _ -> true);
        var ifBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(ifStatement.body()));
        assertEquals(BlockScopeKind.IF_BODY, ifBodyScope.kind());
        assertSame(pingBodyScope, ifBodyScope.getParentScope());
    }

    @Test
    void analyzeSkipsOnlyInnerClassSubtreeWhenSourceRelationDoesNotPublishItsSkeleton() throws Exception {
        var nestedFunctionBody = new Block(List.of(new PassStatement(SYNTHETIC_RANGE)), SYNTHETIC_RANGE);
        var nestedFunction = new FunctionDeclaration("nested", List.of(), null, false, nestedFunctionBody, SYNTHETIC_RANGE);
        var innerClassBody = new Block(List.of(nestedFunction), SYNTHETIC_RANGE);
        var innerClass = new ClassDeclaration("Inner", null, innerClassBody, SYNTHETIC_RANGE);
        var pingBody = new Block(List.of(new PassStatement(SYNTHETIC_RANGE)), SYNTHETIC_RANGE);
        var pingFunction = new FunctionDeclaration("ping", List.of(), null, false, pingBody, SYNTHETIC_RANGE);
        var sourceFile = new SourceFile(List.of(innerClass, pingFunction), SYNTHETIC_RANGE);
        var unit = new FrontendSourceUnit(java.nio.file.Path.of("tmp", "synthetic_missing_inner_relation.gd"), "", sourceFile);

        var boundaryDiagnostics = new DiagnosticSnapshot(List.of());
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.updateModuleSkeleton(
                new FrontendModuleSkeleton(
                        "test_module",
                        List.of(new FrontendSourceClassRelation(
                                unit,
                                new dev.superice.gdcc.lir.LirClassDef("SyntheticMissingInner", "Node"),
                                List.of()
                        )),
                        boundaryDiagnostics
                )
        );
        analysisData.updateDiagnostics(boundaryDiagnostics);

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        new FrontendScopeAnalyzer().analyze(registry, analysisData, new DiagnosticManager());

        var scopesByAst = analysisData.scopesByAst();
        var sourceScope = assertInstanceOf(ClassScope.class, scopesByAst.get(sourceFile));
        var pingScope = assertInstanceOf(CallableScope.class, scopesByAst.get(pingFunction));
        assertSame(sourceScope, pingScope.getParentScope());
        var pingBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(pingBody));
        assertSame(pingScope, pingBodyScope.getParentScope());

        assertFalse(scopesByAst.containsKey(innerClass));
        assertFalse(scopesByAst.containsKey(innerClassBody));
        assertFalse(scopesByAst.containsKey(nestedFunction));
        assertFalse(scopesByAst.containsKey(nestedFunctionBody));
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
        analysisData.updateModuleSkeleton(new FrontendModuleSkeleton("test_module", List.of(), boundaryDiagnostics));
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
            moduleSkeletonPublished = analysisData.moduleSkeleton().sourceClassRelations().size() == 1
                    && analysisData.moduleSkeleton().classDefs().size() == 1;
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
