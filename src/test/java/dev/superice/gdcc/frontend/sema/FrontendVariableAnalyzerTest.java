package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.CallableScope;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import dev.superice.gdcc.frontend.sema.analyzer.FrontendVariableAnalyzer;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendVariableAnalyzerTest {
    @Test
    void analyzeRejectsAnalysisDataWithoutPublishedModuleSkeletonBoundary() {
        var analyzer = new FrontendVariableAnalyzer();
        var diagnostics = new DiagnosticManager();

        assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(FrontendAnalysisData.bootstrap(), diagnostics)
        );
    }

    @Test
    void analyzeRejectsAnalysisDataWithoutPublishedPreVariableDiagnosticsBoundary() {
        var analyzer = new FrontendVariableAnalyzer();
        var analysisData = FrontendAnalysisData.bootstrap();
        var diagnostics = new DiagnosticManager();
        analysisData.updateModuleSkeleton(new FrontendModuleSkeleton("test_module", List.of(), diagnostics.snapshot()));

        assertThrows(IllegalStateException.class, () -> analyzer.analyze(analysisData, diagnostics));
    }

    @Test
    void analyzeRejectsAcceptedSourcesBeforeScopePhasePublishesTopLevelScopes() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "missing_scope_boundary.gd"), """
                class_name MissingScopeBoundary
                extends Node
                
                func ping(value):
                    pass
                """, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());

        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                newRegistry(),
                diagnostics,
                analysisData
        );
        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnostics.snapshot());

        var error = assertThrows(
                IllegalStateException.class,
                () -> new FrontendVariableAnalyzer().analyze(analysisData, diagnostics)
        );
        assertTrue(error.getMessage().contains("Scope graph has not been published"));
        assertTrue(error.getMessage().contains(unit.path().toString()));
    }

    @Test
    void analyzeBindsParametersAndSupportedLocalsAcrossSupportedBlocks() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_supported_locals.gd", """
                class_name VariablePhaseBoundary
                extends Node
                
                func ping(value: int, alias):
                    var local := value
                    if value > 0:
                        var positive: int = value
                    elif value == 0:
                        var zero := value
                    else:
                        var negative := alias
                    while value > 1:
                        var loop_local := value
                        break
                    return alias
                
                func _init(seed: int, mirror):
                    var ctor_local := seed
                    pass
                """);
        var analysisData = phaseInput.analysisData();
        var sourceFile = phaseInput.unit().ast();
        var scopesByAst = analysisData.scopesByAst();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var constructor = findStatement(sourceFile.statements(), ConstructorDeclaration.class, _ -> true);
        var pingScope = assertInstanceOf(CallableScope.class, scopesByAst.get(pingFunction));
        var pingBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(pingFunction.body()));
        var constructorScope = assertInstanceOf(CallableScope.class, scopesByAst.get(constructor));
        var constructorBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(constructor.body()));
        var localDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("local")
        );
        var ifStatement = findStatement(pingFunction.body().statements(), IfStatement.class, _ -> true);
        var positiveDeclaration = findStatement(
                ifStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("positive")
        );
        var zeroDeclaration = findStatement(
                ifStatement.elifClauses().getFirst().body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("zero")
        );
        var negativeDeclaration = findStatement(
                assertInstanceOf(Block.class, ifStatement.elseBody()).statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("negative")
        );
        var whileStatement = findStatement(pingFunction.body().statements(), WhileStatement.class, _ -> true);
        var loopLocalDeclaration = findStatement(
                whileStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("loop_local")
        );
        var ctorLocalDeclaration = findStatement(
                constructor.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("ctor_local")
        );

        new FrontendVariableAnalyzer().analyze(analysisData, phaseInput.diagnostics());

        var valueBinding = pingScope.resolveValue("value");
        assertNotNull(valueBinding);
        assertEquals(GdIntType.INT, valueBinding.type());
        assertEquals(ScopeValueKind.PARAMETER, valueBinding.kind());
        assertSame(pingFunction.parameters().getFirst(), valueBinding.declaration());

        var aliasBinding = pingScope.resolveValue("alias");
        assertNotNull(aliasBinding);
        assertEquals(GdVariantType.VARIANT, aliasBinding.type());
        assertEquals(ScopeValueKind.PARAMETER, aliasBinding.kind());
        assertSame(pingFunction.parameters().getLast(), aliasBinding.declaration());

        var localBinding = pingBodyScope.resolveValue("local");
        assertNotNull(localBinding);
        assertEquals(GdVariantType.VARIANT, localBinding.type());
        assertEquals(ScopeValueKind.LOCAL, localBinding.kind());
        assertSame(localDeclaration, localBinding.declaration());

        var ifBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(ifStatement.body()));
        var positiveBinding = ifBodyScope.resolveValue("positive");
        assertNotNull(positiveBinding);
        assertEquals(GdIntType.INT, positiveBinding.type());
        assertSame(positiveDeclaration, positiveBinding.declaration());

        var elifBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(ifStatement.elifClauses().getFirst().body()));
        var zeroBinding = elifBodyScope.resolveValue("zero");
        assertNotNull(zeroBinding);
        assertEquals(GdVariantType.VARIANT, zeroBinding.type());
        assertSame(zeroDeclaration, zeroBinding.declaration());

        var elseBody = assertInstanceOf(Block.class, ifStatement.elseBody());
        var elseBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(elseBody));
        var negativeBinding = elseBodyScope.resolveValue("negative");
        assertNotNull(negativeBinding);
        assertEquals(GdVariantType.VARIANT, negativeBinding.type());
        assertSame(negativeDeclaration, negativeBinding.declaration());

        var whileBodyScope = assertInstanceOf(BlockScope.class, scopesByAst.get(whileStatement.body()));
        var loopLocalBinding = whileBodyScope.resolveValue("loop_local");
        assertNotNull(loopLocalBinding);
        assertEquals(GdVariantType.VARIANT, loopLocalBinding.type());
        assertSame(loopLocalDeclaration, loopLocalBinding.declaration());

        var seedBinding = constructorScope.resolveValue("seed");
        assertNotNull(seedBinding);
        assertEquals(GdIntType.INT, seedBinding.type());
        assertEquals(ScopeValueKind.PARAMETER, seedBinding.kind());

        var mirrorBinding = constructorScope.resolveValue("mirror");
        assertNotNull(mirrorBinding);
        assertEquals(GdVariantType.VARIANT, mirrorBinding.type());
        assertEquals(ScopeValueKind.PARAMETER, mirrorBinding.kind());

        var ctorLocalBinding = constructorBodyScope.resolveValue("ctor_local");
        assertNotNull(ctorLocalBinding);
        assertEquals(GdVariantType.VARIANT, ctorLocalBinding.type());
        assertEquals(ScopeValueKind.LOCAL, ctorLocalBinding.kind());
        assertSame(ctorLocalDeclaration, ctorLocalBinding.declaration());

        assertSame(scopesByAst, analysisData.scopesByAst());
        assertSame(pingScope, analysisData.scopesByAst().get(pingFunction));
        assertSame(pingBodyScope, analysisData.scopesByAst().get(pingFunction.body()));
        assertSame(pingBodyScope, analysisData.scopesByAst().get(localDeclaration));
        assertTrue(analysisData.symbolBindings().isEmpty());
        assertTrue(analysisData.expressionTypes().isEmpty());
        assertTrue(analysisData.resolvedMembers().isEmpty());
        assertTrue(analysisData.resolvedCalls().isEmpty());
        assertTrue(phaseInput.diagnostics().isEmpty());
        assertEquals(phaseInput.diagnostics().snapshot(), analysisData.diagnostics());
    }

    @Test
    void analyzeWarnsForDefaultValuesButStillBindsParameters() throws Exception {
        var phaseInput = publishedPhaseInput("phase3_parameter_default.gd", """
                class_name ParameterDefaultWarning
                extends Node
                
                func ping(value, alias = value):
                    pass
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var pingScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var error = newDiagnostics.getFirst();

        assertEquals(1, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("sema.unsupported_parameter_default_value", error.category());
        assertTrue(error.message().contains("not supported"));
        assertTrue(error.message().contains("ignores the default value expression"));
        assertEquals(phaseInput.unit().path(), error.sourcePath());
        assertEquals(
                FrontendRange.fromAstRange(pingFunction.parameters().getLast().defaultValue().range()),
                error.range()
        );

        var valueBinding = pingScope.resolveValue("value");
        assertNotNull(valueBinding);
        assertEquals(GdVariantType.VARIANT, valueBinding.type());
        var aliasBinding = pingScope.resolveValue("alias");
        assertNotNull(aliasBinding);
        assertEquals(GdVariantType.VARIANT, aliasBinding.type());
    }

    @Test
    void analyzeWarnsForDeferredLambdaSubtreesWhileBindingOuterLocal() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_deferred.gd", """
                class_name LambdaVariableDeferred
                extends Node
                
                func ping(seed: int):
                    var builder := func(item: int, fallback = item):
                        var lambda_local := fallback
                        return lambda_local
                    return seed
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var builderDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("builder")
        );
        var pingBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction.body()));
        var builderLambda = assertInstanceOf(LambdaExpression.class, builderDeclaration.value());
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(builderLambda));
        var lambdaBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(builderLambda.body()));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var error = newDiagnostics.getFirst();

        assertEquals(1, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("sema.unsupported_variable_inventory_subtree", error.category());
        assertTrue(error.message().contains("does not support lambda subtrees"));
        assertTrue(error.message().contains("parameters, default values, locals, and captures"));
        assertEquals(phaseInput.unit().path(), error.sourcePath());
        assertEquals(FrontendRange.fromAstRange(builderLambda.range()), error.range());
        assertNotNull(assertInstanceOf(
                CallableScope.class,
                phaseInput.analysisData().scopesByAst().get(pingFunction)
        ).resolveValue("seed"));
        assertNotNull(pingBodyScope.resolveValue("builder"));
        assertNull(lambdaScope.resolveValue("item"));
        assertNull(lambdaScope.resolveValue("fallback"));
        assertNull(lambdaBodyScope.resolveValue("lambda_local"));
    }

    @Test
    void analyzeWarnsForDeferredForMatchAndBlockLocalConstWhileKeepingOtherLocalsBound() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_deferred_boundaries.gd", """
                class_name DeferredBoundaries
                extends Node
                
                func ping(value: int):
                    var plain_local := value
                    for item: int in [value, value + 1]:
                        var from_for := item
                    match value:
                        var bound when bound > 0:
                            var from_match := bound
                        0:
                            pass
                    const answer = 42
                    return plain_local
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var pingBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction.body()));
        var plainLocal = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("plain_local")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var forBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(forStatement.body()));
        var matchStatement = findStatement(pingFunction.body().statements(), MatchStatement.class, _ -> true);
        var firstSectionScope = assertInstanceOf(
                BlockScope.class,
                phaseInput.analysisData().scopesByAst().get(matchStatement.sections().getFirst())
        );
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var answerConst = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("answer")
        );

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var forWarning = findDiagnostic(newDiagnostics, FrontendRange.fromAstRange(forStatement.range()));
        var matchWarning = findDiagnostic(newDiagnostics, FrontendRange.fromAstRange(matchStatement.range()));
        var constWarning = findDiagnostic(newDiagnostics, FrontendRange.fromAstRange(answerConst.range()));

        assertEquals(3, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, forWarning.severity());
        assertEquals("sema.unsupported_variable_inventory_subtree", forWarning.category());
        assertTrue(forWarning.message().contains("does not support `for` subtrees"));
        assertTrue(forWarning.message().contains("loop iterator binding"));
        assertEquals(phaseInput.unit().path(), forWarning.sourcePath());
        assertEquals(FrontendDiagnosticSeverity.ERROR, matchWarning.severity());
        assertEquals("sema.unsupported_variable_inventory_subtree", matchWarning.category());
        assertTrue(matchWarning.message().contains("does not support `match` subtrees"));
        assertTrue(matchWarning.message().contains("pattern bindings"));
        assertEquals(phaseInput.unit().path(), matchWarning.sourcePath());
        assertEquals(FrontendDiagnosticSeverity.ERROR, constWarning.severity());
        assertEquals("sema.unsupported_variable_inventory_subtree", constWarning.category());
        assertTrue(constWarning.message().contains("does not support block-local `const` declarations"));
        assertTrue(constWarning.message().contains("constant 'answer'"));
        assertEquals(phaseInput.unit().path(), constWarning.sourcePath());
        var plainLocalBinding = pingBodyScope.resolveValueHere("plain_local");
        assertNotNull(plainLocalBinding);
        assertEquals(GdVariantType.VARIANT, plainLocalBinding.type());
        assertEquals(ScopeValueKind.LOCAL, plainLocalBinding.kind());
        assertSame(plainLocal, plainLocalBinding.declaration());
        assertNull(forBodyScope.resolveValueHere("from_for"));
        assertNull(firstSectionScope.resolveValueHere("from_match"));
        assertNull(pingBodyScope.resolveValueHere("answer"));
    }

    @Test
    void analyzeWarnsForDeferredLambdaSubtreesInsideReturnExpressions() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_return_expression.gd", """
                class_name LambdaReturnExpression
                extends Node
                
                func ping(seed: int):
                    return func():
                        return seed
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var returnStatement = assertInstanceOf(ReturnStatement.class, pingFunction.body().statements().getFirst());
        var returnedLambda = assertInstanceOf(LambdaExpression.class, returnStatement.value());
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var error = newDiagnostics.getFirst();

        assertEquals(1, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("sema.unsupported_variable_inventory_subtree", error.category());
        assertTrue(error.message().contains("does not support lambda subtrees"));
        assertEquals(phaseInput.unit().path(), error.sourcePath());
        assertEquals(FrontendRange.fromAstRange(returnedLambda.range()), error.range());
    }

    @Test
    void analyzeLeavesClassPropertiesAtClassScopeWithoutBindingErrors() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_class_property_boundary.gd", """
                class_name ClassPropertyBoundary
                extends Node
                
                var hp: int = 1
                const MAX_HP = 99
                
                func ping():
                    var local := hp
                """);
        var sourceFile = phaseInput.unit().ast();
        var sourceScope = assertInstanceOf(ClassScope.class, phaseInput.analysisData().scopesByAst().get(sourceFile));
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var pingBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction.body()));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        assertEquals(diagnosticsBefore, phaseInput.diagnostics().snapshot());
        assertNotNull(sourceScope.resolveValue("hp"));
        assertNotNull(pingBodyScope.resolveValueHere("local"));
    }

    @Test
    void analyzeWarnsAndFallsBackForUnknownParameterTypes() throws Exception {
        var phaseInput = publishedPhaseInput("phase3_unknown_parameter_type.gd", """
                class_name UnknownParameterType
                extends Node
                
                func ping(value: MissingType):
                    pass
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var pingScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var warning = newDiagnostics.getFirst();

        assertEquals(1, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.WARNING, warning.severity());
        assertEquals("sema.type_resolution", warning.category());
        assertTrue(warning.message().contains("MissingType"));
        assertEquals(phaseInput.unit().path(), warning.sourcePath());
        assertEquals(
                FrontendRange.fromAstRange(pingFunction.parameters().getFirst().type().range()),
                warning.range()
        );
        var valueBinding = pingScope.resolveValue("value");
        assertNotNull(valueBinding);
        assertEquals(GdVariantType.VARIANT, valueBinding.type());
    }

    @Test
    void analyzeWarnsAndFallsBackForUnknownLocalTypes() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_unknown_local_type.gd", """
                class_name UnknownLocalType
                extends Node
                
                func ping():
                    var missing: MissingType = null
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var pingBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction.body()));
        var missingLocal = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("missing")
        );
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var warning = newDiagnostics.getFirst();

        assertEquals(1, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.WARNING, warning.severity());
        assertEquals("sema.type_resolution", warning.category());
        assertTrue(warning.message().contains("MissingType"));
        assertEquals(phaseInput.unit().path(), warning.sourcePath());
        assertEquals(
                FrontendRange.fromAstRange(missingLocal.type().range()),
                warning.range()
        );
        var localBinding = pingBodyScope.resolveValueHere("missing");
        assertNotNull(localBinding);
        assertEquals(GdVariantType.VARIANT, localBinding.type());
        assertEquals(ScopeValueKind.LOCAL, localBinding.kind());
        assertSame(missingLocal, localBinding.declaration());
    }

    @Test
    void analyzeReportsDuplicateParametersWithoutOverwritingFirstBinding() throws Exception {
        var phaseInput = publishedPhaseInput("phase3_duplicate_parameter.gd", """
                class_name DuplicateParameterBinding
                extends Node
                
                func ping(value: int, value):
                    pass
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var pingScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var error = newDiagnostics.getFirst();
        var binding = pingScope.resolveValue("value");
        assertNotNull(binding);

        assertEquals(1, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("sema.variable_binding", error.category());
        assertTrue(error.message().contains("Duplicate parameter 'value'"));
        assertEquals(GdIntType.INT, binding.type());
        assertSame(pingFunction.parameters().getFirst(), binding.declaration());
    }

    @Test
    void analyzeReportsDuplicateLocalsWithoutOverwritingFirstBinding() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_duplicate_local.gd", """
                class_name DuplicateLocalBinding
                extends Node
                
                func ping():
                    var value: int = 1
                    var value := 2
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var pingBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction.body()));
        var firstLocal = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("value")
        );
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var error = newDiagnostics.getFirst();
        var binding = pingBodyScope.resolveValueHere("value");
        assertNotNull(binding);

        assertEquals(1, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("sema.variable_binding", error.category());
        assertTrue(error.message().contains("Duplicate local variable 'value'"));
        assertEquals(GdIntType.INT, binding.type());
        assertEquals(ScopeValueKind.LOCAL, binding.kind());
        assertSame(firstLocal, binding.declaration());
    }

    @Test
    void analyzeSkipsParameterWithoutScopeRecord() throws Exception {
        var phaseInput = publishedPhaseInput("phase3_missing_parameter_scope.gd", """
                class_name MissingParameterScope
                extends Node
                
                func ping(value: int, alias: int):
                    pass
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var aliasParameter = pingFunction.parameters().getLast();
        var pingScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction));
        phaseInput.analysisData().scopesByAst().remove(aliasParameter);
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        assertEquals(diagnosticsBefore, phaseInput.diagnostics().snapshot());
        assertNotNull(pingScope.resolveValue("value"));
        assertNull(pingScope.resolveValue("alias"));
    }

    @Test
    void analyzeSkipsLocalWithoutScopeRecord() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_missing_local_scope.gd", """
                class_name MissingLocalScope
                extends Node
                
                func ping():
                    var value := 1
                    var alias := 2
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var aliasLocal = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("alias")
        );
        var pingBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction.body()));
        phaseInput.analysisData().scopesByAst().remove(aliasLocal);
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        assertEquals(diagnosticsBefore, phaseInput.diagnostics().snapshot());
        assertNotNull(pingBodyScope.resolveValueHere("value"));
        assertNull(pingBodyScope.resolveValueHere("alias"));
    }

    @Test
    void analyzeReportsCallableScopeMismatchAndContinuesOtherParameters() throws Exception {
        var phaseInput = publishedPhaseInput("phase3_parameter_scope_mismatch.gd", """
                class_name ParameterScopeMismatch
                extends Node
                
                func ping(value: int, alias: int):
                    pass
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var aliasParameter = pingFunction.parameters().getLast();
        var pingScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction));
        var bodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction.body()));
        phaseInput.analysisData().scopesByAst().put(aliasParameter, bodyScope);
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var error = newDiagnostics.getFirst();

        assertEquals(1, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("sema.variable_binding", error.category());
        assertTrue(error.message().contains("expected CallableScope"));
        assertTrue(error.message().contains("BlockScope"));
        assertNotNull(pingScope.resolveValue("value"));
        assertNull(pingScope.resolveValue("alias"));
    }

    @Test
    void analyzeReportsBlockScopeMismatchAndContinuesOtherLocals() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_local_scope_mismatch.gd", """
                class_name LocalScopeMismatch
                extends Node
                
                func ping():
                    var value := 1
                    var alias := 2
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var aliasLocal = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("alias")
        );
        var pingScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction));
        var pingBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction.body()));
        phaseInput.analysisData().scopesByAst().put(aliasLocal, pingScope);
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var error = newDiagnostics.getFirst();

        assertEquals(1, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("sema.variable_binding", error.category());
        assertTrue(error.message().contains("expected BlockScope"));
        assertTrue(error.message().contains("CallableScope"));
        assertNotNull(pingBodyScope.resolveValueHere("value"));
        assertNull(pingBodyScope.resolveValueHere("alias"));
    }

    @Test
    void analyzeReportsLocalShadowingParameterAndSkipsBinding() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_local_shadows_parameter.gd", """
                class_name LocalShadowsParameter
                extends Node
                
                func ping(value: int):
                    if value > 0:
                        var value := 1
                    return value
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var ifStatement = findStatement(pingFunction.body().statements(), IfStatement.class, _ -> true);
        var ifBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(ifStatement.body()));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var error = newDiagnostics.getFirst();

        assertEquals(1, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("sema.variable_binding", error.category());
        assertTrue(error.message().contains("shadows parameter 'value'"));
        assertNull(ifBodyScope.resolveValueHere("value"));
    }

    @Test
    void analyzeReportsLocalShadowingOuterLocalAndSkipsBinding() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_local_shadows_outer_local.gd", """
                class_name LocalShadowsOuterLocal
                extends Node
                
                func ping():
                    var value := 1
                    if value > 0:
                        var value := 2
                    return value
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var pingBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction.body()));
        var ifStatement = findStatement(pingFunction.body().statements(), IfStatement.class, _ -> true);
        var ifBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(ifStatement.body()));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var error = newDiagnostics.getFirst();

        assertEquals(1, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("sema.variable_binding", error.category());
        assertTrue(error.message().contains("shadows outer local 'value'"));
        assertNotNull(pingBodyScope.resolveValueHere("value"));
        assertNull(ifBodyScope.resolveValueHere("value"));
    }

    @Test
    void analyzeSkipsBadInnerClassSubtreeButKeepsSiblingCallableAlive() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "phase3_skipped_inner_class.gd"), """
                class_name SkippedInnerClass
                extends Node
                
                class Broken:
                    func lost(arg: int):
                        pass
                
                func good(value: int):
                    var keep := value
                """, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());

        var analysisData = FrontendAnalysisData.bootstrap();
        var boundaryDiagnostics = diagnostics.snapshot();
        analysisData.updateModuleSkeleton(new FrontendModuleSkeleton(
                "test_module",
                List.of(new FrontendSourceClassRelation(
                        unit,
                        "SkippedInnerClass",
                        new FrontendSuperClassRef("Node", "Node"),
                        new LirClassDef("SkippedInnerClass", "Node"),
                        List.of()
                )),
                boundaryDiagnostics
        ));
        analysisData.updateDiagnostics(boundaryDiagnostics);
        new FrontendScopeAnalyzer().analyze(newRegistry(), analysisData, diagnostics);
        analysisData.updateDiagnostics(diagnostics.snapshot());

        var sourceFile = unit.ast();
        var brokenClass = findStatement(
                sourceFile.statements(),
                ClassDeclaration.class,
                classDeclaration -> classDeclaration.name().equals("Broken")
        );
        var lostFunction = findStatement(
                brokenClass.body().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("lost")
        );
        var goodFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("good")
        );
        var goodBodyScope = assertInstanceOf(BlockScope.class, analysisData.scopesByAst().get(goodFunction.body()));

        new FrontendVariableAnalyzer().analyze(analysisData, diagnostics);

        assertFalse(analysisData.scopesByAst().containsKey(brokenClass));
        assertFalse(analysisData.scopesByAst().containsKey(lostFunction));
        assertEquals(boundaryDiagnostics, diagnostics.snapshot());
        assertNotNull(goodBodyScope.resolveValueHere("keep"));
    }

    @Test
    void analyzeOnlyExposesManagerAwarePublicEntryPoint() {
        var analyzeMethods = Arrays.stream(FrontendVariableAnalyzer.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().equals("analyze"))
                .toList();

        assertEquals(1, analyzeMethods.size());
        assertArrayEquals(
                new Class<?>[]{FrontendAnalysisData.class, DiagnosticManager.class},
                analyzeMethods.getFirst().getParameterTypes()
        );
    }

    private PhaseInput publishedPhaseInput(@NotNull String fileName, @NotNull String source) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());

        var registry = newRegistry();
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
        return new PhaseInput(unit, analysisData, diagnostics);
    }

    private static @NotNull List<FrontendDiagnostic> newDiagnostics(
            @NotNull DiagnosticSnapshot before,
            @NotNull DiagnosticSnapshot after
    ) {
        return after.asList().subList(before.size(), after.size());
    }

    private static @NotNull FrontendDiagnostic findDiagnostic(
            @NotNull List<FrontendDiagnostic> diagnostics,
            @NotNull FrontendRange range
    ) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.range().equals(range))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Diagnostic not found for range: " + range));
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

    private static @NotNull ClassRegistry newRegistry() throws Exception {
        return new ClassRegistry(ExtensionApiLoader.loadDefault());
    }

    private record PhaseInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics
    ) {
    }
}
