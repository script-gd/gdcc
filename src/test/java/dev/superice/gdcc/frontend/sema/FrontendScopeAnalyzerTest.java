package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.BlockScopeKind;
import dev.superice.gdcc.frontend.scope.CallableScope;
import dev.superice.gdcc.frontend.scope.CallableScopeKind;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdSignalType;
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
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics
        );
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
    void analyzePublishesImmediateInnerTypeMetaWithoutLeakingOuterValueOrFunctionBindings() throws Exception {
        var analyzed = analyze("""
                class_name ScopeTypePublish
                extends RefCounted
                
                var outer_prop: int
                
                func outer_call():
                    pass
                
                class Middle:
                    func ping() -> Deep:
                        var local_lambda := func() -> Sibling:
                            return null
                        return null
                
                    class Deep:
                        pass
                
                class Sibling:
                    pass
                """);
        var sourceFile = analyzed.unit().ast();
        var scopesByAst = analyzed.analysisData().scopesByAst();
        var sourceScope = assertInstanceOf(ClassScope.class, scopesByAst.get(sourceFile));

        assertEquals("ScopeTypePublish$Middle", sourceScope.resolveTypeMeta("Middle").canonicalName());
        assertEquals("ScopeTypePublish$Sibling", sourceScope.resolveTypeMeta("Sibling").canonicalName());
        assertNull(sourceScope.resolveTypeMeta("Deep"));

        var middleDeclaration = findStatement(
                sourceFile.statements(),
                ClassDeclaration.class,
                classDeclaration -> classDeclaration.name().equals("Middle")
        );
        var middleScope = assertInstanceOf(ClassScope.class, scopesByAst.get(middleDeclaration));
        assertEquals("ScopeTypePublish", middleScope.resolveTypeMeta("ScopeTypePublish").canonicalName());
        assertEquals("ScopeTypePublish$Middle$Deep", middleScope.resolveTypeMeta("Deep").canonicalName());
        assertNull(middleScope.resolveValue("outer_prop"));
        assertTrue(middleScope.resolveFunctions("outer_call").isEmpty());

        var pingFunction = findStatement(
                middleDeclaration.body().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var pingScope = assertInstanceOf(CallableScope.class, scopesByAst.get(pingFunction));
        assertEquals("ScopeTypePublish$Middle$Deep", pingScope.resolveTypeMeta("Deep").canonicalName());

        var localLambdaDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("local_lambda")
        );
        var localLambda = assertInstanceOf(LambdaExpression.class, localLambdaDeclaration.value());
        var lambdaScope = assertInstanceOf(CallableScope.class, scopesByAst.get(localLambda));
        assertEquals("ScopeTypePublish$Sibling", lambdaScope.resolveTypeMeta("Sibling").canonicalName());

        var deepDeclaration = findStatement(
                middleDeclaration.body().statements(),
                ClassDeclaration.class,
                classDeclaration -> classDeclaration.name().equals("Deep")
        );
        var deepScope = assertInstanceOf(ClassScope.class, scopesByAst.get(deepDeclaration));
        assertEquals("ScopeTypePublish$Middle", deepScope.resolveTypeMeta("Middle").canonicalName());
        assertEquals("ScopeTypePublish$Sibling", deepScope.resolveTypeMeta("Sibling").canonicalName());
        assertNull(deepScope.resolveValue("outer_prop"));
        assertTrue(deepScope.resolveFunctions("outer_call").isEmpty());
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
                                "SyntheticBlockScope",
                                "SyntheticBlockScope",
                                new FrontendSuperClassRef("Node", "Node"),
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
        assertEquals("MaterializedInnerClassBoundary$Inner", innerScope.getCurrentClass().getName());
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
        assertEquals("MaterializedInnerClassBoundary$Inner$Deep", deepScope.getCurrentClass().getName());
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
    void analyzeAutoIndexesSkeletonPropertiesAndSignalsButKeepsClassBoundaryIsolation() throws Exception {
        var analyzed = analyze("""
                class_name MaterializedMemberBindings
                extends Node
                
                signal outer_changed(amount: int)
                var outer_prop: int = 1
                
                class Inner:
                    signal inner_changed(flag: bool)
                    var inner_prop: int = 2
                
                    func ping() -> int:
                        return inner_prop
                """);
        var sourceFile = analyzed.unit().ast();
        var scopesByAst = analyzed.analysisData().scopesByAst();
        var sourceScope = assertInstanceOf(ClassScope.class, scopesByAst.get(sourceFile));

        var outerProperty = sourceScope.resolveValue("outer_prop", ResolveRestriction.instanceContext());
        assertTrue(outerProperty.isAllowed());
        assertEquals(ScopeValueKind.PROPERTY, outerProperty.requireValue().kind());
        assertEquals(GdIntType.INT, outerProperty.requireValue().type());

        var outerSignal = sourceScope.resolveValue("outer_changed", ResolveRestriction.instanceContext());
        assertTrue(outerSignal.isAllowed());
        assertEquals(ScopeValueKind.SIGNAL, outerSignal.requireValue().kind());
        assertInstanceOf(GdSignalType.class, outerSignal.requireValue().type());

        var innerClass = findStatement(
                sourceFile.statements(),
                ClassDeclaration.class,
                declaration -> declaration.name().equals("Inner")
        );
        var innerScope = assertInstanceOf(ClassScope.class, scopesByAst.get(innerClass));

        var innerProperty = innerScope.resolveValue("inner_prop", ResolveRestriction.instanceContext());
        assertTrue(innerProperty.isAllowed());
        assertEquals(ScopeValueKind.PROPERTY, innerProperty.requireValue().kind());
        assertEquals(GdIntType.INT, innerProperty.requireValue().type());

        var innerSignal = innerScope.resolveValue("inner_changed", ResolveRestriction.instanceContext());
        assertTrue(innerSignal.isAllowed());
        assertEquals(ScopeValueKind.SIGNAL, innerSignal.requireValue().kind());
        assertInstanceOf(GdSignalType.class, innerSignal.requireValue().type());

        assertNull(sourceScope.resolveValue("inner_prop"));
        assertNull(sourceScope.resolveValue("inner_changed"));
        assertNull(innerScope.resolveValue("outer_prop"));
        assertNull(innerScope.resolveValue("outer_changed"));
    }

    @Test
    void analyzeBuildsMixedNestedInnerClassAndLambdaScopesAcrossMultipleLevels() throws Exception {
        var analyzed = analyze("""
                class_name MixedNestedBoundaries
                extends Node
                
                class Inner:
                    var class_lambda := func(seed: int):
                        var nested_lambda := func(offset: int):
                            return offset
                        return nested_lambda
                
                    func host(value: int) -> Callable:
                        var local_lambda := func(multiplier: int):
                            var deeper_lambda := func(extra: int):
                                return extra
                            return multiplier
                        return local_lambda
                
                    class Deep:
                        var deep_lambda := func(flag: bool):
                            return flag
                
                        class Bottom:
                            func bottom_host() -> Callable:
                                var bottom_lambda := func(code: int):
                                    return code
                                return bottom_lambda
                """);
        var sourceFile = analyzed.unit().ast();
        var scopesByAst = analyzed.analysisData().scopesByAst();
        var sourceScope = assertInstanceOf(ClassScope.class, scopesByAst.get(sourceFile));

        var innerClass = findStatement(sourceFile.statements(), ClassDeclaration.class, declaration -> declaration.name().equals("Inner"));
        var innerScope = assertInstanceOf(ClassScope.class, scopesByAst.get(innerClass));
        assertSame(sourceScope, innerScope.getParentScope());
        assertSame(innerScope, scopesByAst.get(innerClass.body()));

        var classLambdaDeclaration = findStatement(
                innerClass.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("class_lambda")
        );
        assertSame(innerScope, scopesByAst.get(classLambdaDeclaration));
        var classLambda = assertInstanceOf(LambdaExpression.class, classLambdaDeclaration.value());
        var classLambdaScope = assertInstanceOf(CallableScope.class, scopesByAst.get(classLambda));
        assertEquals(CallableScopeKind.LAMBDA_EXPRESSION, classLambdaScope.kind());
        assertSame(innerScope, classLambdaScope.getParentScope());
        var classLambdaBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(classLambda.body()));
        assertEquals(BlockScopeKind.LAMBDA_BODY, classLambdaBodyScope.kind());
        assertSame(classLambdaScope, classLambdaBodyScope.getParentScope());

        var nestedLambdaDeclaration = findStatement(
                classLambda.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("nested_lambda")
        );
        assertSame(classLambdaBodyScope, scopesByAst.get(nestedLambdaDeclaration));
        var nestedLambda = assertInstanceOf(LambdaExpression.class, nestedLambdaDeclaration.value());
        var nestedLambdaScope = assertInstanceOf(CallableScope.class, scopesByAst.get(nestedLambda));
        assertSame(classLambdaBodyScope, nestedLambdaScope.getParentScope());
        var nestedLambdaBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(nestedLambda.body()));
        assertSame(nestedLambdaScope, nestedLambdaBodyScope.getParentScope());

        var hostFunction = findStatement(innerClass.body().statements(), FunctionDeclaration.class, function -> function.name().equals("host"));
        var hostScope = assertInstanceOf(CallableScope.class, scopesByAst.get(hostFunction));
        assertSame(innerScope, hostScope.getParentScope());
        var hostBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(hostFunction.body()));
        assertSame(hostScope, hostBodyScope.getParentScope());

        var localLambdaDeclaration = findStatement(
                hostFunction.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("local_lambda")
        );
        assertSame(hostBodyScope, scopesByAst.get(localLambdaDeclaration));
        var localLambda = assertInstanceOf(LambdaExpression.class, localLambdaDeclaration.value());
        var localLambdaScope = assertInstanceOf(CallableScope.class, scopesByAst.get(localLambda));
        assertSame(hostBodyScope, localLambdaScope.getParentScope());
        var localLambdaBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(localLambda.body()));
        assertSame(localLambdaScope, localLambdaBodyScope.getParentScope());

        var deeperLambdaDeclaration = findStatement(
                localLambda.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("deeper_lambda")
        );
        assertSame(localLambdaBodyScope, scopesByAst.get(deeperLambdaDeclaration));
        var deeperLambda = assertInstanceOf(LambdaExpression.class, deeperLambdaDeclaration.value());
        var deeperLambdaScope = assertInstanceOf(CallableScope.class, scopesByAst.get(deeperLambda));
        assertSame(localLambdaBodyScope, deeperLambdaScope.getParentScope());
        var deeperLambdaBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(deeperLambda.body()));
        assertSame(deeperLambdaScope, deeperLambdaBodyScope.getParentScope());

        var deepClass = findStatement(innerClass.body().statements(), ClassDeclaration.class, declaration -> declaration.name().equals("Deep"));
        var deepScope = assertInstanceOf(ClassScope.class, scopesByAst.get(deepClass));
        assertSame(innerScope, deepScope.getParentScope());
        assertSame(deepScope, scopesByAst.get(deepClass.body()));

        var deepLambdaDeclaration = findStatement(
                deepClass.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("deep_lambda")
        );
        assertSame(deepScope, scopesByAst.get(deepLambdaDeclaration));
        var deepLambda = assertInstanceOf(LambdaExpression.class, deepLambdaDeclaration.value());
        var deepLambdaScope = assertInstanceOf(CallableScope.class, scopesByAst.get(deepLambda));
        assertSame(deepScope, deepLambdaScope.getParentScope());
        var deepLambdaBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(deepLambda.body()));
        assertSame(deepLambdaScope, deepLambdaBodyScope.getParentScope());

        var bottomClass = findStatement(deepClass.body().statements(), ClassDeclaration.class, declaration -> declaration.name().equals("Bottom"));
        var bottomScope = assertInstanceOf(ClassScope.class, scopesByAst.get(bottomClass));
        assertSame(deepScope, bottomScope.getParentScope());
        assertSame(bottomScope, scopesByAst.get(bottomClass.body()));

        var bottomHost = findStatement(bottomClass.body().statements(), FunctionDeclaration.class, function -> function.name().equals("bottom_host"));
        var bottomHostScope = assertInstanceOf(CallableScope.class, scopesByAst.get(bottomHost));
        assertSame(bottomScope, bottomHostScope.getParentScope());
        var bottomHostBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(bottomHost.body()));
        assertSame(bottomHostScope, bottomHostBodyScope.getParentScope());

        var bottomLambdaDeclaration = findStatement(
                bottomHost.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("bottom_lambda")
        );
        assertSame(bottomHostBodyScope, scopesByAst.get(bottomLambdaDeclaration));
        var bottomLambda = assertInstanceOf(LambdaExpression.class, bottomLambdaDeclaration.value());
        var bottomLambdaScope = assertInstanceOf(CallableScope.class, scopesByAst.get(bottomLambda));
        assertSame(bottomHostBodyScope, bottomLambdaScope.getParentScope());
        var bottomLambdaBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(bottomLambda.body()));
        assertSame(bottomLambdaScope, bottomLambdaBodyScope.getParentScope());
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
                                "SyntheticMissingInner",
                                "SyntheticMissingInner",
                                new FrontendSuperClassRef("Node", "Node"),
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
    void semanticAnalysisBuildsIndependentScopeGraphsForMultipleSourceUnitsWithSameInnerClassNames() throws Exception {
        var analyzedModule = analyzeModule(List.of(
                new ModuleSource("alpha_scope_unit.gd", """
                        class_name AlphaScript
                        extends Node
                        
                        class Inner:
                            var alpha_builder := func(seed: int):
                                return seed
                        """),
                new ModuleSource("beta_scope_unit.gd", """
                        class_name BetaScript
                        extends Node
                        
                        class Inner:
                            func beta() -> int:
                                var beta_builder := func(code: int):
                                    return code
                                return 2
                        """)
        ));
        var unitA = analyzedModule.units().getFirst();
        var unitB = analyzedModule.units().getLast();
        var scopesByAst = analyzedModule.analysisData().scopesByAst();

        assertEquals(2, analyzedModule.analysisData().moduleSkeleton().sourceClassRelations().size());

        var sourceAScope = assertInstanceOf(ClassScope.class, scopesByAst.get(unitA.ast()));
        var sourceBScope = assertInstanceOf(ClassScope.class, scopesByAst.get(unitB.ast()));
        assertNotSame(sourceAScope, sourceBScope);
        assertEquals("AlphaScript", sourceAScope.getCurrentClass().getName());
        assertEquals("BetaScript", sourceBScope.getCurrentClass().getName());

        var innerA = findStatement(unitA.ast().statements(), ClassDeclaration.class, declaration -> declaration.name().equals("Inner"));
        var innerB = findStatement(unitB.ast().statements(), ClassDeclaration.class, declaration -> declaration.name().equals("Inner"));
        var innerAScope = assertInstanceOf(ClassScope.class, scopesByAst.get(innerA));
        var innerBScope = assertInstanceOf(ClassScope.class, scopesByAst.get(innerB));
        assertSame(sourceAScope, innerAScope.getParentScope());
        assertSame(sourceBScope, innerBScope.getParentScope());
        assertNotSame(innerAScope, innerBScope);

        var alphaBuilderDeclaration = findStatement(
                innerA.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("alpha_builder")
        );
        var alphaBuilder = assertInstanceOf(LambdaExpression.class, alphaBuilderDeclaration.value());
        var alphaBuilderScope = assertInstanceOf(CallableScope.class, scopesByAst.get(alphaBuilder));
        assertSame(innerAScope, alphaBuilderScope.getParentScope());

        var betaFunction = findStatement(innerB.body().statements(), FunctionDeclaration.class, function -> function.name().equals("beta"));
        var betaFunctionScope = assertInstanceOf(CallableScope.class, scopesByAst.get(betaFunction));
        assertSame(innerBScope, betaFunctionScope.getParentScope());
        var betaBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(betaFunction.body()));
        assertSame(betaFunctionScope, betaBodyScope.getParentScope());
        var betaBuilderDeclaration = findStatement(
                betaFunction.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("beta_builder")
        );
        var betaBuilder = assertInstanceOf(LambdaExpression.class, betaBuilderDeclaration.value());
        var betaBuilderScope = assertInstanceOf(CallableScope.class, scopesByAst.get(betaBuilder));
        assertSame(betaBodyScope, betaBuilderScope.getParentScope());
    }

    @Test
    void analyzeSkipsMissingDeepInnerClassRelationWithoutBreakingSiblingUnitsOrNestedLambdas() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var firstUnit = parserService.parseUnit(
                java.nio.file.Path.of("tmp", "multi_unit_missing_deep_a.gd"),
                """
                        class_name MultiUnitNestedA
                        extends Node
                        
                        class OuterInner:
                            var outer_lambda := func(seed: int):
                                return seed
                        
                            class DeepMissing:
                                func lost() -> int:
                                    return 0
                        
                            func host() -> Callable:
                                var survivor := func(value: int):
                                    return value
                                return survivor
                        """,
                diagnostics
        );
        var secondUnit = parserService.parseUnit(
                java.nio.file.Path.of("tmp", "multi_unit_missing_deep_b.gd"),
                """
                        class_name MultiUnitNestedB
                        extends Node
                        
                        class Inner:
                            func keep() -> Callable:
                                var factory := func(code: int):
                                    return code
                                return factory
                        """,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());

        var firstSourceFile = firstUnit.ast();
        var outerInner = findStatement(firstSourceFile.statements(), ClassDeclaration.class, declaration -> declaration.name().equals("OuterInner"));
        var deepMissing = findStatement(outerInner.body().statements(), ClassDeclaration.class, declaration -> declaration.name().equals("DeepMissing"));
        var lostFunction = findStatement(deepMissing.body().statements(), FunctionDeclaration.class, function -> function.name().equals("lost"));

        var secondSourceFile = secondUnit.ast();
        var secondInner = findStatement(secondSourceFile.statements(), ClassDeclaration.class, declaration -> declaration.name().equals("Inner"));
        var keepFunction = findStatement(secondInner.body().statements(), FunctionDeclaration.class, function -> function.name().equals("keep"));

        var boundaryDiagnostics = diagnostics.snapshot();
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.updateModuleSkeleton(
                new FrontendModuleSkeleton(
                        "test_module",
                        List.of(
                                new FrontendSourceClassRelation(
                                        firstUnit,
                                        "MultiUnitNestedA",
                                        "MultiUnitNestedA",
                                        new FrontendSuperClassRef("Node", "Node"),
                                        new dev.superice.gdcc.lir.LirClassDef("MultiUnitNestedA", "Node"),
                                        List.of(new FrontendInnerClassRelation(
                                                firstSourceFile,
                                                outerInner,
                                                "OuterInner",
                                                "MultiUnitNestedA$OuterInner",
                                                new FrontendSuperClassRef("RefCounted", "RefCounted"),
                                                new dev.superice.gdcc.lir.LirClassDef("MultiUnitNestedA$OuterInner", "RefCounted")
                                        ))
                                ),
                                new FrontendSourceClassRelation(
                                        secondUnit,
                                        "MultiUnitNestedB",
                                        "MultiUnitNestedB",
                                        new FrontendSuperClassRef("Node", "Node"),
                                        new dev.superice.gdcc.lir.LirClassDef("MultiUnitNestedB", "Node"),
                                        List.of(new FrontendInnerClassRelation(
                                                secondSourceFile,
                                                secondInner,
                                                "Inner",
                                                "MultiUnitNestedB$Inner",
                                                new FrontendSuperClassRef("RefCounted", "RefCounted"),
                                                new dev.superice.gdcc.lir.LirClassDef("MultiUnitNestedB$Inner", "RefCounted")
                                        ))
                                )
                        ),
                        boundaryDiagnostics
                )
        );
        analysisData.updateDiagnostics(boundaryDiagnostics);

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        new FrontendScopeAnalyzer().analyze(registry, analysisData, diagnostics);

        var scopesByAst = analysisData.scopesByAst();
        var firstSourceScope = assertInstanceOf(ClassScope.class, scopesByAst.get(firstSourceFile));
        var secondSourceScope = assertInstanceOf(ClassScope.class, scopesByAst.get(secondSourceFile));
        assertNotSame(firstSourceScope, secondSourceScope);

        var outerInnerScope = assertInstanceOf(ClassScope.class, scopesByAst.get(outerInner));
        assertSame(firstSourceScope, outerInnerScope.getParentScope());
        var outerLambdaDeclaration = findStatement(
                outerInner.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("outer_lambda")
        );
        var outerLambda = assertInstanceOf(LambdaExpression.class, outerLambdaDeclaration.value());
        var outerLambdaScope = assertInstanceOf(CallableScope.class, scopesByAst.get(outerLambda));
        assertSame(outerInnerScope, outerLambdaScope.getParentScope());

        var hostFunction = findStatement(outerInner.body().statements(), FunctionDeclaration.class, function -> function.name().equals("host"));
        var hostFunctionScope = assertInstanceOf(CallableScope.class, scopesByAst.get(hostFunction));
        assertSame(outerInnerScope, hostFunctionScope.getParentScope());
        var hostBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(hostFunction.body()));
        assertSame(hostFunctionScope, hostBodyScope.getParentScope());
        var survivorDeclaration = findStatement(
                hostFunction.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("survivor")
        );
        var survivorLambda = assertInstanceOf(LambdaExpression.class, survivorDeclaration.value());
        var survivorScope = assertInstanceOf(CallableScope.class, scopesByAst.get(survivorLambda));
        assertSame(hostBodyScope, survivorScope.getParentScope());

        assertFalse(scopesByAst.containsKey(deepMissing));
        assertFalse(scopesByAst.containsKey(deepMissing.body()));
        assertFalse(scopesByAst.containsKey(lostFunction));
        assertFalse(scopesByAst.containsKey(lostFunction.body()));

        var secondInnerScope = assertInstanceOf(ClassScope.class, scopesByAst.get(secondInner));
        assertSame(secondSourceScope, secondInnerScope.getParentScope());
        var keepFunctionScope = assertInstanceOf(CallableScope.class, scopesByAst.get(keepFunction));
        assertSame(secondInnerScope, keepFunctionScope.getParentScope());
        var keepBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(keepFunction.body()));
        assertSame(keepFunctionScope, keepBodyScope.getParentScope());
        var factoryDeclaration = findStatement(
                keepFunction.body().statements(),
                VariableDeclaration.class,
                variable -> variable.name().equals("factory")
        );
        var factoryLambda = assertInstanceOf(LambdaExpression.class, factoryDeclaration.value());
        var factoryScope = assertInstanceOf(CallableScope.class, scopesByAst.get(factoryLambda));
        assertSame(keepBodyScope, factoryScope.getParentScope());
    }

    @Test
    void scopeAnalysisInIsolationRecordsParameterScopeFactsBeforeVariableBindingPrefill() throws Exception {
        var analyzed = analyzeScopeOnly("""
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

        var result = analyzer.analyze(new FrontendModule("test_module", List.of(unit)), registry, diagnostics);

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
        var analysisData = analyzer.analyze(new FrontendModule("test_module", List.of(unit)), registry, diagnostics);
        return new AnalyzedUnit(unit, analysisData);
    }

    private AnalyzedUnit analyzeScopeOnly(@NotNull String source) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(
                java.nio.file.Path.of("tmp", "frontend_scope_analyzer_isolation_test.gd"),
                source,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics,
                analysisData
        );
        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnostics.snapshot());
        new FrontendScopeAnalyzer().analyze(registry, analysisData, diagnostics);
        analysisData.updateDiagnostics(diagnostics.snapshot());
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

    private List<LirClassDef> topLevelClassDefs(@NotNull FrontendModuleSkeleton result) {
        return result.sourceClassRelations().stream()
                .map(FrontendSourceClassRelation::topLevelClassDef)
                .toList();
    }

    private AnalyzedModule analyzeModule(@NotNull List<ModuleSource> sources) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var units = new java.util.ArrayList<FrontendSourceUnit>(sources.size());
        for (var source : sources) {
            units.add(parserService.parseUnit(
                    java.nio.file.Path.of("tmp", source.fileName()),
                    source.source(),
                    diagnostics
            ));
        }
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analyzer = new FrontendSemanticAnalyzer();
        var analysisData = analyzer.analyze(new FrontendModule("test_module", units), registry, diagnostics);
        return new AnalyzedModule(List.copyOf(units), analysisData);
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

    private record AnalyzedUnit(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData
    ) {
    }

    private record AnalyzedModule(
            @NotNull List<FrontendSourceUnit> units,
            @NotNull FrontendAnalysisData analysisData
    ) {
    }

    private record ModuleSource(
            @NotNull String fileName,
            @NotNull String source
    ) {
    }
}
