package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendVariableAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
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
import java.util.Map;
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
        analysisData.updateModuleSkeleton(new FrontendModuleSkeleton("test_module", List.of(), Map.of(), diagnostics.snapshot()));

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
    void analyzeAcceptsSourceFunctionDefaultValuesAndStillBindsParameters() throws Exception {
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

        // Source-function parameter defaults are no longer fail-closed at inventory time: the
        // parameter-default metadata owner analyzes the default expression during the suite
        // phase, so this analyzer only registers the parameter bindings.
        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());

        var valueBinding = pingScope.resolveValue("value");
        assertNotNull(valueBinding);
        assertEquals(GdVariantType.VARIANT, valueBinding.type());
        var aliasBinding = pingScope.resolveValue("alias");
        assertNotNull(aliasBinding);
        assertEquals(GdVariantType.VARIANT, aliasBinding.type());
    }

    @Test
    void analyzeKeepsLambdaParameterDefaultValuesFailClosed() throws Exception {
        var phaseInput = publishedPhaseInput("phase3_lambda_parameter_default.gd", """
                class_name LambdaParameterDefaultFailClosed
                extends Node
                
                func ping():
                    var cb = func(item = 1):
                        pass
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var cbDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var cbLambda = assertInstanceOf(LambdaExpression.class, cbDeclaration.value());
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        // Lambda parameter defaults stay fail-closed: exactly one diagnostic anchored at the
        // default expression, and the lambda parameter itself is still registered.
        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        assertEquals(1, newDiagnostics.size());
        var error = newDiagnostics.getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("sema.unsupported_parameter_default_value", error.category());
        assertTrue(error.message().contains("not supported"));
        assertEquals(FrontendDiagnostic.sourcePathText(phaseInput.unit().path()), error.sourcePath());
        assertEquals(
                FrontendRange.fromAstRange(cbLambda.parameters().getFirst().defaultValue().range()),
                error.range()
        );

        var itemBinding = lambdaScope.resolveValue("item");
        assertNotNull(itemBinding);
        assertEquals(GdVariantType.VARIANT, itemBinding.type());
    }

    @Test
    void analyzeBindsLambdaParametersLocalsAndCapturesOuterValues() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_inventory.gd", """
                class_name LambdaVariableBound
                extends Node
                
                func ping(seed: int):
                    var builder := func(item: int):
                        var lambda_local := item
                        return lambda_local + seed
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
        var lambdaLocalDeclaration = findStatement(
                builderLambda.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("lambda_local")
        );
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        // Lambda inventory is fully bound, so no boundary diagnostic may fire.
        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());

        var itemBinding = lambdaScope.resolveValue("item");
        assertNotNull(itemBinding);
        assertEquals(ScopeValueKind.PARAMETER, itemBinding.kind());
        assertEquals(GdIntType.INT, itemBinding.type());
        assertSame(builderLambda.parameters().getFirst(), itemBinding.declaration());

        var lambdaLocalBinding = lambdaBodyScope.resolveValue("lambda_local");
        assertNotNull(lambdaLocalBinding);
        assertEquals(ScopeValueKind.LOCAL, lambdaLocalBinding.kind());
        assertSame(lambdaLocalDeclaration, lambdaLocalBinding.declaration());

        // Inventory registers the capture name with a Variant placeholder type; the declaration-site
        // type replaces the placeholder during nested suite resolution in a later phase.
        var seedCapture = lambdaScope.resolveValueHere("seed");
        assertNotNull(seedCapture);
        assertEquals(ScopeValueKind.CAPTURE, seedCapture.kind());
        assertEquals(GdVariantType.VARIANT, seedCapture.type());
        assertSame(pingFunction.parameters().getFirst(), seedCapture.declaration());

        assertNotNull(pingBodyScope.resolveValue("builder"));
        // Placeholder captures live only on the scope; inventory must not publish a plan.
        assertTrue(phaseInput.analysisData().lambdaPlans().isEmpty());
    }

    @Test
    void analyzeBindsForAndMatchInventoryWhileBlockLocalConstRemainUnsupported() throws Exception {
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
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("from_for")
        );
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
        var constWarning = findDiagnostic(newDiagnostics, FrontendRange.fromAstRange(answerConst.range()));

        assertEquals(1, newDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, constWarning.severity());
        assertEquals("sema.unsupported_variable_inventory_subtree", constWarning.category());
        assertTrue(constWarning.message().contains("does not support block-local `const` declarations"));
        assertTrue(constWarning.message().contains("constant 'answer'"));
        assertEquals(FrontendDiagnostic.sourcePathText(phaseInput.unit().path()), constWarning.sourcePath());
        var plainLocalBinding = pingBodyScope.resolveValueHere("plain_local");
        assertNotNull(plainLocalBinding);
        assertEquals(GdVariantType.VARIANT, plainLocalBinding.type());
        assertEquals(ScopeValueKind.LOCAL, plainLocalBinding.kind());
        assertSame(plainLocal, plainLocalBinding.declaration());
        var iteratorBinding = forBodyScope.resolveValueHere("item");
        assertNotNull(iteratorBinding);
        assertEquals(GdIntType.INT, iteratorBinding.type());
        assertSame(forStatement, iteratorBinding.declaration());
        var fromForBinding = forBodyScope.resolveValueHere("from_for");
        assertNotNull(fromForBinding);
        assertEquals(GdVariantType.VARIANT, fromForBinding.type());
        assertSame(fromFor, fromForBinding.declaration());
        var boundBinding = firstSectionScope.resolveValueHere("bound");
        assertNotNull(boundBinding);
        assertEquals(GdVariantType.VARIANT, boundBinding.type());
        assertEquals(ScopeValueKind.LOCAL, boundBinding.kind());
        var fromMatch = findStatement(
                matchStatement.sections().getFirst().body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("from_match")
        );
        var fromMatchBinding = firstSectionScope.resolveValueHere("from_match");
        assertNotNull(fromMatchBinding);
        assertEquals(GdVariantType.VARIANT, fromMatchBinding.type());
        assertSame(fromMatch, fromMatchBinding.declaration());
        assertNull(pingBodyScope.resolveValueHere("answer"));
    }

    @Test
    void analyzeBindsLambdaInsideReturnExpression() throws Exception {
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
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(returnedLambda));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        // Lambdas nested in arbitrary expressions are reached through the boundary reporter and
        // must be bound exactly like statement-initializers, without any boundary diagnostic.
        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());
        var seedCapture = lambdaScope.resolveValueHere("seed");
        assertNotNull(seedCapture);
        assertEquals(ScopeValueKind.CAPTURE, seedCapture.kind());
        assertSame(pingFunction.parameters().getFirst(), seedCapture.declaration());
    }

    @Test
    void analyzeTransfersNestedLambdaCapturesThroughIntermediateLambda() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_nested_capture.gd", """
                class_name NestedLambdaCapture
                extends Node
                
                func ping(seed: int):
                    var outer := func():
                        var mid := func():
                            return seed
                        return mid
                    return outer
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var outerDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("outer")
        );
        var outerLambda = assertInstanceOf(LambdaExpression.class, outerDeclaration.value());
        var midDeclaration = findStatement(
                outerLambda.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("mid")
        );
        var midLambda = assertInstanceOf(LambdaExpression.class, midDeclaration.value());
        var outerLambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(outerLambda));
        var midLambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(midLambda));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());

        // The innermost lambda captures the outer parameter directly.
        var midCapture = midLambdaScope.resolveValueHere("seed");
        assertNotNull(midCapture);
        assertEquals(ScopeValueKind.CAPTURE, midCapture.kind());
        assertSame(pingFunction.parameters().getFirst(), midCapture.declaration());

        // The intermediate lambda must re-export the same capture (nested transfer,
        // rule 9): same name, same source declaration identity.
        var outerCapture = outerLambdaScope.resolveValueHere("seed");
        assertNotNull(outerCapture);
        assertEquals(ScopeValueKind.CAPTURE, outerCapture.kind());
        assertEquals(GdVariantType.VARIANT, outerCapture.type());
        assertSame(pingFunction.parameters().getFirst(), outerCapture.declaration());
    }

    @Test
    void analyzeDoesNotCaptureWhenLambdaLocalShadowsOuterLocal() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_local_shadow.gd", """
                class_name LambdaLocalShadow
                extends Node
                
                func ping():
                    var x = 1
                    var cb := func():
                        var x = 2
                        return x
                    return x
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var outerXDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );
        var cbDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var cbLambda = assertInstanceOf(LambdaExpression.class, cbDeclaration.value());
        var innerXDeclaration = findStatement(
                cbLambda.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );
        var pingBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(pingFunction.body()));
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda));
        var lambdaBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda.body()));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        // The shadowing local lives behind the lambda callable boundary, so it is legal and the
        // outer `x` must NOT be captured (self-shadowing rule).
        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());
        assertNull(lambdaScope.resolveValueHere("x"));
        var innerXBinding = lambdaBodyScope.resolveValueHere("x");
        assertNotNull(innerXBinding);
        assertEquals(ScopeValueKind.LOCAL, innerXBinding.kind());
        assertSame(innerXDeclaration, innerXBinding.declaration());
        var outerXBinding = pingBodyScope.resolveValueHere("x");
        assertNotNull(outerXBinding);
        assertSame(outerXDeclaration, outerXBinding.declaration());
    }

    @Test
    void analyzeStopsCaptureTransferAtShadowingIntermediateLambda() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_transfer_shadow.gd", """
                class_name LambdaTransferShadow
                extends Node
                
                func ping(seed: int):
                    var outer := func():
                        var seed = 10
                        var mid := func():
                            return seed
                        return mid
                    return outer
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var outerDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("outer")
        );
        var outerLambda = assertInstanceOf(LambdaExpression.class, outerDeclaration.value());
        var middleSeedDeclaration = findStatement(
                outerLambda.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("seed")
        );
        var midDeclaration = findStatement(
                outerLambda.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("mid")
        );
        var midLambda = assertInstanceOf(LambdaExpression.class, midDeclaration.value());
        var outerLambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(outerLambda));
        var midLambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(midLambda));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());

        // The inner lambda captures the INTERMEDIATE lambda's local, not the outer parameter.
        var midCapture = midLambdaScope.resolveValueHere("seed");
        assertNotNull(midCapture);
        assertEquals(ScopeValueKind.CAPTURE, midCapture.kind());
        assertSame(middleSeedDeclaration, midCapture.declaration());

        // The intermediate lambda shadows the name with its own local, so the transfer chain
        // terminates here: no capture may be inserted for the outer parameter.
        assertNull(outerLambdaScope.resolveValueHere("seed"));
    }

    @Test
    void analyzeBindsMatchInsideLambdaBodyWhileBlockLocalConstStayUnsupported() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_deferred_boundaries.gd", """
                class_name LambdaDeferredBoundaries
                extends Node
                
                func ping(seed: int):
                    var cb := func(item: int):
                        match item:
                            1:
                                pass
                        const k = seed
                        return item
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var cbDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var cbLambda = assertInstanceOf(LambdaExpression.class, cbDeclaration.value());
        var matchStatement = findStatement(cbLambda.body().statements(), MatchStatement.class, _ -> true);
        var constDeclaration = findStatement(
                cbLambda.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("k")
        );
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda));
        var lambdaBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda.body()));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        // Match inventory is published inside lambda bodies; block-local `const` stays deferred.
        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        var constError = findDiagnostic(newDiagnostics, FrontendRange.fromAstRange(constDeclaration.range()));
        assertEquals(1, newDiagnostics.size());
        assertEquals("sema.unsupported_variable_inventory_subtree", constError.category());
        assertTrue(constError.message().contains("does not support block-local `const` declarations"));
        var matchSectionScope = assertInstanceOf(
                BlockScope.class,
                phaseInput.analysisData().scopesByAst().get(matchStatement.sections().getFirst())
        );
        assertNotNull(matchSectionScope);

        // Supported lambda inventory is still bound around the deferred fragments.
        var itemBinding = lambdaScope.resolveValue("item");
        assertNotNull(itemBinding);
        assertEquals(ScopeValueKind.PARAMETER, itemBinding.kind());

        // Names inside the deferred `const` subtree must not leak into captures or locals.
        assertNull(lambdaScope.resolveValueHere("seed"));
        assertNull(lambdaScope.resolveValueHere("k"));
        assertNull(lambdaBodyScope.resolveValueHere("k"));
    }

    @Test
    void analyzeLeavesPropertyInitializerLambdaUnbound() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_property_initializer_lambda.gd", """
                class_name PropertyInitializerLambda
                extends Node
                
                var cb = func(item):
                    return item
                
                func ping(seed: int):
                    return seed
                """);
        var propertyDeclaration = findStatement(
                phaseInput.unit().ast().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var propertyLambda = assertInstanceOf(LambdaExpression.class, propertyDeclaration.value());
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        // The variable analyzer only inventories supported callable bodies. A property-initializer
        // lambda is outside that reach: no inventory diagnostic (the property-initializer boundary
        // diagnostic belongs to the binding/chain phases) and no binding may appear on its scope.
        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());
        var lambdaScope = assertInstanceOf(
                CallableScope.class,
                phaseInput.analysisData().scopesByAst().get(propertyLambda)
        );
        assertNull(lambdaScope.resolveValueHere("item"));
        assertTrue(phaseInput.analysisData().lambdaPlans().isEmpty());
    }

    @Test
    void analyzeCapturesSelfForExplicitSelfUseInsideLambda() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_self_capture.gd", """
                class_name LambdaSelfCapture
                extends Node
                
                var hp: int = 0
                
                func ping():
                    var cb := func():
                        return self.hp
                    return cb
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var cbDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var cbLambda = assertInstanceOf(LambdaExpression.class, cbDeclaration.value());
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());

        // §3.5: an explicit `self` use captures the enclosing instance under the name `self`,
        // sourced at the enclosing callable. The scope binding keeps the inventory Variant
        // placeholder like every capture; the enclosing class object type is filled with all
        // declaration-site capture types during nested suite resolution.
        var selfCapture = lambdaScope.resolveValueHere("self");
        assertNotNull(selfCapture);
        assertEquals(ScopeValueKind.CAPTURE, selfCapture.kind());
        assertEquals(GdVariantType.VARIANT, selfCapture.type());
        assertSame(pingFunction, selfCapture.declaration());
        assertTrue(phaseInput.analysisData().lambdaPlans().isEmpty());
    }

    @Test
    void analyzeCapturesSelfForImplicitInstanceMemberUseInsideLambda() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_implicit_self.gd", """
                class_name LambdaImplicitSelf
                extends Node
                
                var hp: int = 0
                
                func ping():
                    var cb := func():
                        return hp
                    return cb
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var cbDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var cbLambda = assertInstanceOf(LambdaExpression.class, cbDeclaration.value());
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());

        // A bare instance-property read needs the instance receiver: `hp` itself is not
        // capturable (class member), so the lambda captures `self` instead (§3.5).
        assertNull(lambdaScope.resolveValueHere("hp"));
        var selfCapture = lambdaScope.resolveValueHere("self");
        assertNotNull(selfCapture);
        assertEquals(ScopeValueKind.CAPTURE, selfCapture.kind());
        assertSame(pingFunction, selfCapture.declaration());
    }

    @Test
    void analyzeDoesNotCaptureSelfInsideStaticFunction() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_static_self.gd", """
                class_name LambdaStaticSelf
                extends Node
                
                var hp: int = 0
                
                static func ping():
                    var cb := func():
                        return self.hp
                    return cb
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var cbDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var cbLambda = assertInstanceOf(LambdaExpression.class, cbDeclaration.value());
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        // §3.5: a static enclosing callable never synthesizes a `self` capture; the restriction
        // diagnostic for the illegal `self` use stays with the body-typing phases.
        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());
        assertNull(lambdaScope.resolveValueHere("self"));
    }

    @Test
    void analyzeCapturesSelfForGetNodeExpressionInsideLambda() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_get_node_capture.gd", """
                class_name LambdaGetNodeCapture
                extends Node
                
                func ping():
                    var cb := func():
                        return $Camera3D
                    return cb
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var cbDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var cbLambda = assertInstanceOf(LambdaExpression.class, cbDeclaration.value());
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());

        // Get-node desugars to `self.get_node(path)`, so it captures the enclosing instance
        // under the name `self` exactly like an explicit `self` expression (§3.5).
        var selfCapture = lambdaScope.resolveValueHere("self");
        assertNotNull(selfCapture);
        assertEquals(ScopeValueKind.CAPTURE, selfCapture.kind());
        assertEquals(GdVariantType.VARIANT, selfCapture.type());
        assertSame(pingFunction, selfCapture.declaration());
        assertTrue(phaseInput.analysisData().lambdaPlans().isEmpty());
    }

    @Test
    void analyzeDoesNotCaptureSelfForGetNodeInsideStaticFunctionLambda() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_get_node_static.gd", """
                class_name LambdaGetNodeStatic
                extends Node
                
                static func ping():
                    var cb := func():
                        return $Camera3D
                    return cb
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var cbDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var cbLambda = assertInstanceOf(LambdaExpression.class, cbDeclaration.value());
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        // A static enclosing callable never synthesizes a `self` capture even for get-node; the
        // source diagnostic stays with the body-typing phases (same split as explicit `self`).
        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());
        assertNull(lambdaScope.resolveValueHere("self"));
    }

    @Test
    void analyzeDoesNotCaptureSelfForUtilityFunctionCallInsideLambda() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_utility_call.gd", """
                class_name LambdaUtilityCall
                extends Node
                
                func ping():
                    var cb := func():
                        print("hi")
                    return cb
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var cbDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var cbLambda = assertInstanceOf(LambdaExpression.class, cbDeclaration.value());
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        // Global utility functions resolve without a receiver; they must not synthesize `self`.
        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());
        assertNull(lambdaScope.resolveValueHere("self"));
        assertNull(lambdaScope.resolveValueHere("print"));
    }

    @Test
    void analyzeDoesNotCaptureSelfForStaticMembersInsideLambda() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_static_members.gd", """
                class_name LambdaStaticMembers
                extends Node
                
                static var counter: int = 0
                
                static func helper() -> int:
                    return 1
                
                func ping():
                    var cb := func():
                        counter += helper()
                        return counter
                    return cb
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var cbDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var cbLambda = assertInstanceOf(LambdaExpression.class, cbDeclaration.value());
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        // Static property reads and static method calls need no instance receiver (§3.5), so no
        // `self` capture may appear even though the names belong to the enclosing class.
        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        assertEquals(0, newDiagnostics(diagnosticsBefore, diagnosticsAfter).size());
        assertNull(lambdaScope.resolveValueHere("self"));
        assertNull(lambdaScope.resolveValueHere("counter"));
        assertNull(lambdaScope.resolveValueHere("helper"));
    }

    @Test
    void analyzeReportsCaptureConflictForSelfNamedParameterWithoutThrowing() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_lambda_self_param_conflict.gd", """
                class_name LambdaSelfParamConflict
                extends Node
                
                var hp: int = 0
                
                func ping():
                    var cb := func(self):
                        return self.hp
                    return cb
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var cbDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var cbLambda = assertInstanceOf(LambdaExpression.class, cbDeclaration.value());
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(cbLambda));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        // Plan §3.4 rule 7: a capture that collides with an existing parameter slot must produce
        // exactly one binding diagnostic and never reach CallableScope's fail-fast duplicate guard.
        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var diagnosticsAfter = phaseInput.diagnostics().snapshot();
        var newDiagnostics = newDiagnostics(diagnosticsBefore, diagnosticsAfter);
        assertEquals(1, newDiagnostics.size());
        var error = newDiagnostics.getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
        assertEquals("sema.variable_binding", error.category());
        assertTrue(error.message().contains("Capture 'self' conflicts with existing parameter 'self'"));
        assertEquals(FrontendRange.fromAstRange(cbLambda.range()), error.range());

        // The parameter binding wins; no capture may overwrite it.
        var selfBinding = lambdaScope.resolveValueHere("self");
        assertNotNull(selfBinding);
        assertEquals(ScopeValueKind.PARAMETER, selfBinding.kind());
    }

    @Test
    void analyzeReportsIteratorConflictsAndKeepsIteratorRecoveryBinding() throws Exception {
        var phaseInput = publishedPhaseInput("phase4_for_iterator_conflicts.gd", """
                class_name ForIteratorConflicts
                extends Node

                func parameter_conflict(item, values):
                    for item in values:
                        pass

                func outer_conflict(values):
                    var item := 0
                    for item in values:
                        pass

                func body_conflict(values):
                    for item in values:
                        var item := 0
                """);
        var parameterFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("parameter_conflict")
        );
        var outerFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("outer_conflict")
        );
        var bodyFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("body_conflict")
        );
        var parameterFor = findStatement(parameterFunction.body().statements(), ForStatement.class, _ -> true);
        var outerFor = findStatement(outerFunction.body().statements(), ForStatement.class, _ -> true);
        var bodyFor = findStatement(bodyFunction.body().statements(), ForStatement.class, _ -> true);
        var duplicateBodyLocal = findStatement(
                bodyFor.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("item")
        );
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        var newDiagnostics = newDiagnostics(diagnosticsBefore, phaseInput.diagnostics().snapshot());
        assertEquals(3, newDiagnostics.size());
        assertTrue(findDiagnostic(newDiagnostics, FrontendRange.fromAstRange(parameterFor.range()))
                .message().contains("shadows parameter"));
        assertTrue(findDiagnostic(newDiagnostics, FrontendRange.fromAstRange(outerFor.range()))
                .message().contains("shadows outer local"));
        assertTrue(findDiagnostic(newDiagnostics, FrontendRange.fromAstRange(duplicateBodyLocal.range()))
                .message().contains("Duplicate local variable"));
        for (var forStatement : List.of(parameterFor, outerFor, bodyFor)) {
            var bodyScope = assertInstanceOf(
                    BlockScope.class,
                    phaseInput.analysisData().scopesByAst().get(forStatement.body())
            );
            var iteratorBinding = bodyScope.resolveValueHere("item");
            assertNotNull(iteratorBinding);
            assertSame(forStatement, iteratorBinding.declaration());
        }
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
        assertEquals(FrontendDiagnostic.sourcePathText(phaseInput.unit().path()), warning.sourcePath());
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
        assertEquals(FrontendDiagnostic.sourcePathText(phaseInput.unit().path()), warning.sourcePath());
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
        assertTrue(error.message().contains("function 'ping'"));
        assertTrue(error.message().contains(phaseInput.unit().path().toString()));
        assertTrue(error.message().contains(formatRange(assertInstanceOf(
                VariableDeclaration.class,
                pingFunction.body().statements().get(1)
        ))));
        assertTrue(error.message().contains(formatRange(firstLocal)));
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
        assertTrue(error.message().contains("if-body of function 'ping'"));
        assertTrue(error.message().contains(phaseInput.unit().path().toString()));
        assertTrue(error.message().contains(formatRange(
                assertInstanceOf(VariableDeclaration.class, ifStatement.body().statements().getFirst())
        )));
        assertTrue(error.message().contains(formatRange(pingFunction.parameters().getFirst())));
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
        assertTrue(error.message().contains("if-body of function 'ping'"));
        assertTrue(error.message().contains(phaseInput.unit().path().toString()));
        assertTrue(error.message().contains(formatRange(
                assertInstanceOf(VariableDeclaration.class, ifStatement.body().statements().getFirst())
        )));
        assertTrue(error.message().contains(formatRange(assertInstanceOf(
                VariableDeclaration.class,
                pingFunction.body().statements().getFirst()
        ))));
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
                        "SkippedInnerClass",
                        new FrontendSuperClassRef("Node", "Node"),
                        new LirClassDef("SkippedInnerClass", "Node"),
                        List.of()
                )),
                Map.of(),
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

    private static @NotNull String formatRange(@NotNull dev.superice.gdparser.frontend.ast.Node node) {
        var range = FrontendRange.fromAstRange(node.range());
        assertNotNull(range);
        return "%d:%d-%d:%d".formatted(
                range.start().line(),
                range.start().column(),
                range.end().line(),
                range.end().column()
        );
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
