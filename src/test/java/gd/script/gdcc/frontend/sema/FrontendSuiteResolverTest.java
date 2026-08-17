package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.sema.analyzer.FrontendInterfacePhase;
import gd.script.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendBodyOwnerProcedures;
import gd.script.gdcc.frontend.sema.analyzer.FrontendStatementResolver;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSuiteContext;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSuiteResolver;
import gd.script.gdcc.frontend.sema.analyzer.FrontendVariableAnalyzer;
import gd.script.gdcc.frontend.sema.patch.FrontendVarTypePostPatch;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

class FrontendSuiteResolverTest {
    private static final @NotNull String STATEMENT_SNAPSHOT_TEST_CATEGORY = "sema.suite_statement_snapshot_probe";

    @Test
    void sourceOrderTraversalMatchesAstOrderAndOwnerOrderIsFixed() throws Exception {
        var phaseInput = phaseInput("suite_source_order.gd", """
                class_name SuiteSourceOrder
                extends Node
                
                func ping(value):
                    var first := value
                    var second := first
                    return second
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var first = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("first")
        );
        var second = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("second")
        );
        var returnStatement = findStatement(pingFunction.body().statements(), ReturnStatement.class, _ -> true);
        var ownerProcedures = new RecordingOwnerProcedures(false);

        resolveWith(phaseInput, ownerProcedures);

        assertOwnerSequence(ownerProcedures.events(), 0, first);
        assertOwnerSequence(ownerProcedures.events(), 5, second);
        assertOwnerSequence(ownerProcedures.events(), 10, returnStatement);
        assertEquals(15, ownerProcedures.events().size());
    }

    @Test
    void statementBoundaryPublishesDiagnosticsSnapshotForLaterStatements() throws Exception {
        var phaseInput = phaseInput("suite_statement_diagnostics_snapshot.gd", """
                class_name SuiteStatementDiagnosticsSnapshot
                extends Node
                
                func ping(value):
                    var first := value
                    var second := first
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var first = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("first")
        );
        var second = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("second")
        );
        var ownerProcedures = new StatementSnapshotOwnerProcedures(first, second);
        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());

        new FrontendSuiteResolver(new FrontendStatementResolver(ownerProcedures)).resolve(
                surface,
                phaseInput.registry(),
                phaseInput.analysisData(),
                phaseInput.diagnostics()
        );

        assertAll(
                () -> assertTrue(ownerProcedures.sameStatementSawLiveDiagnostic()),
                () -> assertFalse(ownerProcedures.sameStatementSawStableSnapshotBeforeFlush()),
                () -> assertTrue(ownerProcedures.nextStatementSawCurrentSuiteSnapshot()),
                () -> assertEquals(
                        1,
                        diagnosticsByCategory(
                                phaseInput.analysisData().diagnostics(),
                                STATEMENT_SNAPSHOT_TEST_CATEGORY
                        ).size()
                )
        );
    }

    @Test
    void branchHeadersResolveBeforeTheirChildSuites() throws Exception {
        var phaseInput = phaseInput("suite_header_before_body.gd", """
                class_name SuiteHeaderBeforeBody
                extends Node
                
                func ping(value, other):
                    if value:
                        var branch := value
                    elif other:
                        var elif_branch := other
                    else:
                        var else_branch := value
                    while other:
                        var loop_local := other
                        break
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var ifStatement = findStatement(pingFunction.body().statements(), IfStatement.class, _ -> true);
        var branch = findStatement(
                ifStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("branch")
        );
        var elifClause = ifStatement.elifClauses().getFirst();
        var elifBranch = findStatement(
                elifClause.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("elif_branch")
        );
        var elseBody = ifStatement.elseBody();
        assertNotNull(elseBody);
        var elseBranch = findStatement(
                elseBody.statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("else_branch")
        );
        var whileStatement = findStatement(pingFunction.body().statements(), WhileStatement.class, _ -> true);
        var loopLocal = findStatement(
                whileStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("loop_local")
        );
        var ownerProcedures = new RecordingOwnerProcedures(false);

        resolveWith(phaseInput, ownerProcedures);

        assertTrue(firstStageIndex(ownerProcedures.events(), ifStatement.condition()) < firstStageIndex(ownerProcedures.events(), branch));
        assertTrue(firstStageIndex(ownerProcedures.events(), elifClause.condition()) < firstStageIndex(ownerProcedures.events(), elifBranch));
        assertTrue(firstStageIndex(ownerProcedures.events(), elseBranch) < firstStageIndex(ownerProcedures.events(), whileStatement.condition()));
        assertTrue(firstStageIndex(ownerProcedures.events(), whileStatement.condition()) < firstStageIndex(ownerProcedures.events(), loopLocal));
    }

    @Test
    void forBodyAndLambdaResolveWhileUnsupportedFeatureOwnedBodiesRemainFailClosed() throws Exception {
        var phaseInput = phaseInput("suite_fail_closed.gd", """
                class_name SuiteFailClosed
                extends Node
                
                func ping(items, choice):
                    for item in items:
                        var from_for := item
                    match choice:
                        0:
                            var from_match := choice
                    var callback = func():
                        var from_lambda := choice
                        return choice
                    const answer = choice
                    var after := choice
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("from_for")
        );
        var matchStatement = findStatement(pingFunction.body().statements(), MatchStatement.class, _ -> true);
        var fromMatch = findStatement(
                matchStatement.sections().getFirst().body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("from_match")
        );
        var callback = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("callback")
        );
        var lambdaExpression = assertInstanceOf(LambdaExpression.class, callback.value());
        var fromLambda = findStatement(
                lambdaExpression.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("from_lambda")
        );
        var answer = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("answer")
        );
        var after = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("after")
        );
        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
        var ownerProcedures = new RecordingOwnerProcedures(false);

        new FrontendSuiteResolver(new FrontendStatementResolver(ownerProcedures)).resolve(
                surface,
                phaseInput.registry(),
                phaseInput.analysisData(),
                phaseInput.diagnostics()
        );

        assertTrue(surface.suiteEntryRoots().containsSupportedBlock(forStatement.body()));
        assertFalse(surface.suiteEntryRoots().containsSupportedBlock(matchStatement.sections().getFirst().body()));
        assertTrue(surface.suiteEntryRoots().containsSupportedBlock(lambdaExpression.body()));
        assertTrue(surface.suiteEntryRoots().containsCallableOwner(lambdaExpression));
        assertFalse(ownerProcedures.unsupportedRoots().contains(forStatement));
        assertTrue(ownerProcedures.unsupportedRoots().contains(matchStatement));
        assertTrue(ownerProcedures.unsupportedRoots().contains(answer));
        assertTrue(hasOwnerEvent(ownerProcedures.events(), fromFor));
        assertFalse(hasOwnerEvent(ownerProcedures.events(), fromMatch));
        // The recorded lambda body resolves as its own suite and produces owner events.
        assertTrue(hasOwnerEvent(ownerProcedures.events(), fromLambda));
        assertFalse(hasOwnerEvent(ownerProcedures.events(), answer));
        assertTrue(hasOwnerEvent(ownerProcedures.events(), callback));
        assertTrue(hasOwnerEvent(ownerProcedures.events(), after));
    }

    @Test
    void forHeaderReadsPrefixOverlayAndBodyEntryDoesNotDependOnTypedClassification() throws Exception {
        var phaseInput = phaseInput("suite_for_header_prefix.gd", """
                class_name SuiteForHeaderPrefix
                extends Node
                
                func ping():
                    var limit := 1
                    for item in limit:
                        var from_for := item
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var limit = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("limit")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("from_for")
        );
        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
        var itemUseSite = findNode(
                fromFor,
                IdentifierExpression.class,
                identifierExpression -> identifierExpression.name().equals("item")
        );

        new FrontendSuiteResolver().resolve(
                surface,
                phaseInput.registry(),
                phaseInput.analysisData(),
                phaseInput.diagnostics()
        );

        var iterableType = phaseInput.analysisData().expressionTypes().get(forStatement.iterable());
        assertNotNull(iterableType);
        assertSame(GdIntType.INT, iterableType.publishedType());
        var itemBinding = phaseInput.analysisData().symbolBindings().get(itemUseSite);
        assertNotNull(itemBinding);
        assertEquals(FrontendBindingKind.LOCAL_VAR, itemBinding.kind());
        assertEquals(GdIntType.INT, phaseInput.analysisData().slotTypes().get(fromFor));
        assertTrue(surface.suiteEntryRoots().containsSupportedBlock(forStatement.body()));
        assertTrue(surface.bodyDeclarationIndex().containsBodyRoot(forStatement.body()));
        assertEquals(GdIntType.INT, phaseInput.analysisData().slotTypes().get(limit));
    }

    @Test
    void structurallySupportedForBodyIsResolvedBySuiteResolver() throws Exception {
        var phaseInput = phaseInput("suite_gate_body_published.gd", """
                class_name SuiteGateBodyPublished
                extends Node
                
                func ping(values, seed: int):
                    for item in values:
                        var from_for := seed
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("from_for")
        );
        var seedUseSite = findNode(fromFor, IdentifierExpression.class, identifier -> identifier.name().equals("seed"));
        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
        var forBodyContext = contextForBlock(phaseInput, surface, pingFunction, forStatement.body());

        new FrontendSuiteResolver().resolveSuite(forBodyContext, forStatement.body());

        var seedBinding = phaseInput.analysisData().symbolBindings().get(seedUseSite);
        assertNotNull(seedBinding);
        assertEquals(FrontendBindingKind.PARAMETER, seedBinding.kind());
        var seedExpressionType = phaseInput.analysisData().expressionTypes().get(seedUseSite);
        assertNotNull(seedExpressionType);
        assertSame(GdIntType.INT, seedExpressionType.publishedType());
    }

    @Test
    void supportedBodyCertificateFailsFastForMissingSuiteSurfaceFacts() throws Exception {
        var phaseInput = phaseInput("suite_body_certificate_missing_fact.gd", """
                class_name SuiteBodyCertificateMissingFact
                extends Node
                
                func ping(values, seed: int):
                    for item in values:
                        var copy := seed
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var originalSurface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
        var originalRoots = originalSurface.suiteEntryRoots();

        var missingSuiteEntry = new FrontendInterfaceSurface(
                originalSurface.bodyDeclarationIndex(),
                originalSurface.typedLexicalBaseline(),
                new FrontendSuiteEntryRoots(
                        originalRoots.callableOwners(),
                        originalRoots.propertyInitializers(),
                        originalRoots.supportedBlocks().stream()
                                .filter(block -> block != forStatement.body())
                                .toList()
                )
        );
        assertCertificateFailure(phaseInput, missingSuiteEntry, pingFunction, forStatement.body(), "suite entry roots");

        var missingBodyIndex = new FrontendInterfaceSurface(
                new FrontendBodyDeclarationIndex(Map.of()),
                originalSurface.typedLexicalBaseline(),
                originalRoots
        );
        assertCertificateFailure(phaseInput, missingBodyIndex, pingFunction, forStatement.body(), "declaration index");

        var declarationsWithoutIterator = new IdentityHashMap<>(
                originalSurface.bodyDeclarationIndex().declarationsByBodyRoot()
        );
        declarationsWithoutIterator.put(
                forStatement.body(),
                originalSurface.bodyDeclarationIndex().declarationsFor(forStatement.body()).stream()
                        .filter(declaration -> declaration.kind() != FrontendBodyLocalDeclaration.Kind.ITERATOR)
                        .map(declaration -> new FrontendBodyLocalDeclaration(
                                declaration.declaration(),
                                declaration.binding(),
                                declaration.kind(),
                                0
                        ))
                        .toList()
        );
        var missingIterator = new FrontendInterfaceSurface(
                new FrontendBodyDeclarationIndex(declarationsWithoutIterator),
                originalSurface.typedLexicalBaseline(),
                originalRoots
        );
        // Scope still publishes the iterator; reverse inventory completeness fails before the
        // FOR-specific iterator-shape check.
        assertCertificateFailure(
                phaseInput,
                missingIterator,
                pingFunction,
                forStatement.body(),
                "scope inventory local is missing from body declaration index"
        );

        var baselineWithoutIterator = FrontendTypedLexicalBaseline.builder();
        for (var entry : originalSurface.typedLexicalBaseline().typesByDeclaration().entrySet()) {
            if (entry.getKey() != forStatement) {
                baselineWithoutIterator.put(entry.getKey(), entry.getValue());
            }
        }
        var missingBaseline = new FrontendInterfaceSurface(
                originalSurface.bodyDeclarationIndex(),
                baselineWithoutIterator.build(),
                originalRoots
        );
        assertCertificateFailure(phaseInput, missingBaseline, pingFunction, forStatement.body(), "typed baseline");

        var publishedScope = requireValue(assertInstanceOf(
                BlockScope.class,
                phaseInput.analysisData().scopesByAst().get(forStatement.body())
        ));
        var foreignScope = new BlockScope(requireValue(publishedScope.getParentScope()), publishedScope.kind());
        var scopeError = assertThrows(
                IllegalStateException.class,
                () -> FrontendBodyStructuralCompleteness.requireStructurallyCompleteBody(
                        phaseInput.analysisData(),
                        originalSurface,
                        forStatement.body(),
                        foreignScope
                )
        );
        assertTrue(scopeError.getMessage().contains("scope identity"));
    }

    @Test
    void supportedBodyCertificateFailsWhenScopeInventoryIsMissingFromIndex() throws Exception {
        var phaseInput = phaseInput("suite_body_certificate_missing_scope_inventory.gd", """
                class_name SuiteBodyCertificateMissingScopeInventory
                extends Node
                
                func ping(values, seed: int):
                    for item in values:
                        var copy := seed
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var originalSurface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
        var declarationsWithoutCopy = new IdentityHashMap<>(
                originalSurface.bodyDeclarationIndex().declarationsByBodyRoot()
        );
        declarationsWithoutCopy.put(
                forStatement.body(),
                originalSurface.bodyDeclarationIndex().declarationsFor(forStatement.body()).stream()
                        .filter(declaration -> declaration.kind() == FrontendBodyLocalDeclaration.Kind.ITERATOR)
                        .toList()
        );
        var missingOrdinaryLocal = new FrontendInterfaceSurface(
                new FrontendBodyDeclarationIndex(declarationsWithoutCopy),
                originalSurface.typedLexicalBaseline(),
                originalSurface.suiteEntryRoots()
        );

        assertCertificateFailure(
                phaseInput,
                missingOrdinaryLocal,
                pingFunction,
                forStatement.body(),
                "scope inventory local is missing from body declaration index"
        );
    }

    @Test
    void supportedBodyCertificateFailsWhenSourceOrderMismatchesAstRanges() throws Exception {
        var phaseInput = phaseInput("suite_body_certificate_source_order.gd", """
                class_name SuiteBodyCertificateSourceOrder
                extends Node
                
                func ping(value):
                    var first := value
                    var second := first
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var originalSurface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
        var bodyDeclarations = originalSurface.bodyDeclarationIndex().declarationsFor(pingFunction.body());
        assertEquals(2, bodyDeclarations.size());
        var reorderedSurface = reorderBodyDeclarations(originalSurface, pingFunction.body(), bodyDeclarations);

        assertCertificateFailure(
                phaseInput,
                reorderedSurface,
                pingFunction,
                pingFunction.body(),
                "declaration source order does not match AST range order"
        );
    }

    @Test
    void supportedBodyCertificateFailsWhenForIteratorIsNotFirstInAstOrder() throws Exception {
        var phaseInput = phaseInput("suite_body_certificate_iterator_order.gd", """
                class_name SuiteBodyCertificateIteratorOrder
                extends Node
                
                func ping(values, seed: int):
                    for item in values:
                        var copy := seed
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var originalSurface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
        var bodyDeclarations = originalSurface.bodyDeclarationIndex().declarationsFor(forStatement.body());
        assertEquals(2, bodyDeclarations.size());
        var iterator = bodyDeclarations.stream()
                .filter(declaration -> declaration.kind() == FrontendBodyLocalDeclaration.Kind.ITERATOR)
                .findFirst()
                .orElseThrow();
        var ordinary = bodyDeclarations.stream()
                .filter(declaration -> declaration.kind() == FrontendBodyLocalDeclaration.Kind.ORDINARY_VAR)
                .findFirst()
                .orElseThrow();
        // Contiguous sourceOrder with ordinary first violates AST range order because the
        // ForStatement always starts before body locals.
        var reordered = List.of(
                new FrontendBodyLocalDeclaration(ordinary.declaration(), ordinary.binding(), ordinary.kind(), 0),
                new FrontendBodyLocalDeclaration(iterator.declaration(), iterator.binding(), iterator.kind(), 1)
        );
        var declarationsByBody = new IdentityHashMap<>(
                originalSurface.bodyDeclarationIndex().declarationsByBodyRoot()
        );
        declarationsByBody.put(forStatement.body(), reordered);
        var reorderedSurface = new FrontendInterfaceSurface(
                new FrontendBodyDeclarationIndex(declarationsByBody),
                originalSurface.typedLexicalBaseline(),
                originalSurface.suiteEntryRoots()
        );

        assertCertificateFailure(
                phaseInput,
                reorderedSurface,
                pingFunction,
                forStatement.body(),
                "declaration source order does not match AST range order"
        );
    }

    @Test
    void suiteExportKeepsStableSideTableUnchangedUntilTransactionApply() throws Exception {
        var phaseInput = phaseInput("suite_export_boundary.gd", """
                class_name SuiteExportBoundary
                extends Node
                
                func ping(value):
                    var first := value
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var first = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("first")
        );
        var ownerProcedures = new RecordingOwnerProcedures(true);

        resolveWith(phaseInput, ownerProcedures);

        assertTrue(ownerProcedures.pendingBindingWasVisibleBeforeStableApply());
        assertTrue(ownerProcedures.stableWasEmptyDuringOwnerProcedure());
        var publishedBinding = phaseInput.analysisData().symbolBindings().get(first);
        assertNotNull(publishedBinding);
        assertEquals("__suite_probe__", publishedBinding.symbolName());
    }

    @Test
    void mainAnalyzerBuildsInterfaceSurfaceBeforeLegacyBodyAnalyzers() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "suite_pipeline_handoff.gd"), """
                class_name SuitePipelineHandoff
                extends Node
                
                func ping(value):
                    var local := value
                    return local
                """, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var interfacePhase = new RecordingInterfacePhase();
        var suiteResolver = new RecordingSuiteResolver();
        var analyzer = new FrontendSemanticAnalyzer(interfacePhase, suiteResolver);

        var analysisData = analyzer.analyze(new FrontendModule("test_module", List.of(unit)), registry, diagnostics);

        assertTrue(interfacePhase.invoked());
        assertTrue(interfacePhase.variableInventoryWasPublished());
        assertTrue(suiteResolver.invoked());
        assertSame(interfacePhase.surface(), suiteResolver.surface());
        assertTrue(suiteResolver.bodySideTablesWereEmptyAtHandoff());
        assertFalse(interfacePhase.surface().bodyDeclarationIndex().declarationsByBodyRoot().isEmpty());
        assertFalse(analysisData.slotTypes().isEmpty());
    }

    @Test
    void bodyOwnerProceduresStabilizeSourceOrderAliasChainBeforeLegacyAnalyzers() throws Exception {
        var phaseInput = phaseInput("suite_stage_e_alias_chain.gd", """
                class_name SuiteStageEAliasChain
                extends RefCounted
                
                class Point:
                    var marker: int = -1
                
                func read_path(point: Point):
                    var a := point
                    var b := a
                    var c := b
                    return c.marker
                """);
        var readPath = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("read_path")
        );
        var a = findStatement(readPath.body().statements(), VariableDeclaration.class, declaration -> declaration.name().equals("a"));
        var b = findStatement(readPath.body().statements(), VariableDeclaration.class, declaration -> declaration.name().equals("b"));
        var c = findStatement(readPath.body().statements(), VariableDeclaration.class, declaration -> declaration.name().equals("c"));
        var markerStep = findNode(readPath.body(), AttributePropertyStep.class, step -> step.name().equals("marker"));

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertPointType(requireType(phaseInput.analysisData().slotTypes().get(a))),
                () -> assertPointType(requireType(phaseInput.analysisData().slotTypes().get(b))),
                () -> assertPointType(requireType(phaseInput.analysisData().slotTypes().get(c))),
                () -> assertExpressionPointType(phaseInput.analysisData(), requireExpression(a.value())),
                () -> assertExpressionPointType(phaseInput.analysisData(), requireExpression(b.value())),
                () -> assertExpressionPointType(phaseInput.analysisData(), requireExpression(c.value()))
        );
        var resolvedMember = phaseInput.analysisData().resolvedMembers().get(markerStep);
        assertNotNull(resolvedMember);
        assertAll(
                () -> assertPointType(requireType(resolvedMember.receiverType())),
                () -> assertEquals("int", requireType(resolvedMember.resultType()).getTypeName())
        );
    }

    @Test
    void childBlockReadsParentPrefixAndRejectedShadowDoesNotPolluteParentSlot() throws Exception {
        var phaseInput = phaseInput("suite_stage_e_child_prefix.gd", """
                class_name SuiteStageEChildPrefix
                extends RefCounted
                
                class Point:
                    var marker: int = -1
                
                func read_path(point: Point, seed: int):
                    var stable := point
                    if seed > 0:
                        var child := stable
                        var stable := seed
                    return stable.marker
                """);
        var readPath = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("read_path")
        );
        var stable = findStatement(
                readPath.body().statements(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("stable")
        );
        var ifStatement = findStatement(readPath.body().statements(), IfStatement.class, _ -> true);
        var child = findStatement(
                ifStatement.body().statements(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("child")
        );
        var rejectedShadow = findStatement(
                ifStatement.body().statements(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("stable")
        );
        var markerStep = findNode(readPath.body(), AttributePropertyStep.class, step -> step.name().equals("marker"));

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertPointType(requireType(phaseInput.analysisData().slotTypes().get(stable))),
                () -> assertPointType(requireType(phaseInput.analysisData().slotTypes().get(child))),
                () -> assertNull(phaseInput.analysisData().slotTypes().get(rejectedShadow))
        );
        var resolvedMember = phaseInput.analysisData().resolvedMembers().get(markerStep);
        assertNotNull(resolvedMember);
        assertPointType(requireType(resolvedMember.receiverType()));
    }

    @Test
    void nestedSuiteFactsStayUnpublishedUntilCallableRootCompletes() throws Exception {
        var phaseInput = phaseInput("suite_nested_export_batch.gd", """
                class_name SuiteNestedExportBatch
                extends RefCounted
                
                func ping(seed):
                    if seed:
                        var child := seed
                    var after := seed
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var ifStatement = findStatement(pingFunction.body().statements(), IfStatement.class, _ -> true);
        var child = findStatement(
                ifStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("child")
        );
        var after = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("after")
        );
        var ownerProcedures = new DeferredChildExportOwnerProcedures(child, after);

        resolveWith(phaseInput, ownerProcedures);

        assertAll(
                () -> assertTrue(ownerProcedures.childBindingWasUnpublishedInParentContinuation()),
                () -> assertNotNull(phaseInput.analysisData().symbolBindings().get(child))
        );
    }

    @Test
    void transientExpressionCacheAndVarPostFactsStayOverlayLocalUntilSuiteExport() throws Exception {
        var phaseInput = phaseInput("suite_stage_e_export_boundary.gd", """
                class_name SuiteStageEExportBoundary
                extends RefCounted
                
                func ping(seed: int):
                    var first := seed
                    var second := first
                    return second
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var first = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("first")
        );
        var second = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("second")
        );
        var ownerProcedures = new ExportBoundaryOwnerProcedures(first, second);

        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
        new FrontendSuiteResolver(new FrontendStatementResolver(ownerProcedures)).resolve(
                surface,
                phaseInput.registry(),
                phaseInput.analysisData(),
                phaseInput.diagnostics()
        );

        assertAll(
                () -> assertTrue(ownerProcedures.localStabilizationKeptInitializerTypeInTransientCache()),
                () -> assertTrue(ownerProcedures.varPostPendingFactWasIsolatedFromStableTable()),
                () -> assertTrue(ownerProcedures.flushedVarPostFactWasVisibleBeforeSuiteExport()),
                () -> assertEquals("int", requireType(phaseInput.analysisData().slotTypes().get(first)).getTypeName()),
                () -> assertEquals("int", requireType(phaseInput.analysisData().slotTypes().get(second)).getTypeName())
        );
    }

    @Test
    void callableEntryVarTypePostFactsStayOverlayLocalUntilSuiteExport() throws Exception {
        var phaseInput = phaseInput("suite_callable_entry_var_post.gd", """
                class_name SuiteCallableEntryVarPost
                extends RefCounted
                
                func ping(value: int):
                    var first := value
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var parameter = pingFunction.parameters().getFirst();
        var first = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("first")
        );
        var ownerProcedures = new CallableEntryVarTypePostOwnerProcedures(parameter, first);

        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
        new FrontendSuiteResolver(new FrontendStatementResolver(ownerProcedures)).resolve(
                surface,
                phaseInput.registry(),
                phaseInput.analysisData(),
                phaseInput.diagnostics()
        );

        assertAll(
                () -> assertTrue(ownerProcedures.parameterWasCommittedBeforeFirstStatement()),
                () -> assertTrue(ownerProcedures.stableSlotTypesWereUnchangedBeforeSuiteExport()),
                () -> assertEquals(GdIntType.INT, phaseInput.analysisData().slotTypes().get(parameter))
        );
    }

    @Test
    void bareRangePreRoutePublishesArgumentFactsWithoutCalleeOrCallRootFacts() throws Exception {
        var phaseInput = phaseInput("suite_d0_range_canonical.gd", """
                class_name SuiteD0RangeCanonical
                extends Node
                
                func ping():
                    for i in range(3):
                        var x := i
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var rangeCall = assertInstanceOf(CallExpression.class, forStatement.iterable());
        var callee = assertInstanceOf(IdentifierExpression.class, rangeCall.callee());
        var argument = rangeCall.arguments().getFirst();
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertNull(phaseInput.analysisData().symbolBindings().get(callee),
                        "range callee must not have ordinary binding"),
                () -> assertNull(phaseInput.analysisData().expressionTypes().get(callee),
                        "range callee must not have expression type"),
                () -> assertNull(phaseInput.analysisData().expressionTypes().get(rangeCall),
                        "range call root must not have expression type"),
                () -> assertNull(phaseInput.analysisData().resolvedCalls().get(rangeCall),
                        "range call root must not have resolved call"),
                () -> assertNotNull(phaseInput.analysisData().expressionTypes().get(argument),
                        "range argument must have expression type"),
                () -> assertEquals(GdIntType.INT,
                        requireType(requireValue(phaseInput.analysisData().expressionTypes().get(argument)).publishedType()),
                        "range argument literal 3 must be int"),
                () -> assertNotNull(phaseInput.analysisData().slotTypes().get(fromFor),
                        "body local x must have slot type"),
                () -> assertEquals(GdIntType.INT, phaseInput.analysisData().slotTypes().get(fromFor),
                        "body local x is int because iterator i is refined to int by D1")
        );
        var bindingErrors = diagnosticsByCategory(phaseInput.analysisData().diagnostics(), "sema.binding");
        assertTrue(bindingErrors.stream().noneMatch(d -> d.message().contains("range")),
                "no unknown range binding diagnostic expected");
    }

    @Test
    void bareRangePreRouteHandlesMultipleArgumentsInSourceOrder() throws Exception {
        var phaseInput = phaseInput("suite_d0_range_multi_args.gd", """
                class_name SuiteD0RangeMultiArgs
                extends Node
                
                func ping():
                    for i in range(1, 5, 2):
                        pass
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var rangeCall = assertInstanceOf(CallExpression.class, forStatement.iterable());

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertEquals(3, rangeCall.arguments().size()),
                () -> assertNotNull(phaseInput.analysisData().expressionTypes().get(rangeCall.arguments().getFirst())),
                () -> assertNotNull(phaseInput.analysisData().expressionTypes().get(rangeCall.arguments().get(1))),
                () -> assertNotNull(phaseInput.analysisData().expressionTypes().get(rangeCall.arguments().get(2))),
                () -> assertNull(phaseInput.analysisData().expressionTypes().get(rangeCall)),
                () -> assertNull(phaseInput.analysisData().resolvedCalls().get(rangeCall))
        );
    }

    @Test
    void bareRangePreRouteHandlesEmptyAndExcessArityWithoutBindingNoise() throws Exception {
        var phaseInput = phaseInput("suite_d0_range_arity.gd", """
                class_name SuiteD0RangeArity
                extends Node
                
                func ping():
                    for i in range():
                        pass
                    for j in range(1, 2, 3, 4):
                        pass
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatements = pingFunction.body().statements().stream()
                .filter(ForStatement.class::isInstance)
                .map(ForStatement.class::cast)
                .toList();
        var emptyRange = assertInstanceOf(CallExpression.class, forStatements.getFirst().iterable());
        var excessRange = assertInstanceOf(CallExpression.class, forStatements.get(1).iterable());

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertNull(phaseInput.analysisData().expressionTypes().get(emptyRange),
                        "empty range call root must not have expression type"),
                () -> assertNull(phaseInput.analysisData().resolvedCalls().get(emptyRange),
                        "empty range call root must not have resolved call"),
                () -> assertNull(phaseInput.analysisData().expressionTypes().get(excessRange),
                        "excess-arity range call root must not have expression type"),
                () -> assertNull(phaseInput.analysisData().resolvedCalls().get(excessRange),
                        "excess-arity range call root must not have resolved call")
        );
        var bindingErrors = diagnosticsByCategory(phaseInput.analysisData().diagnostics(), "sema.binding");
        assertTrue(bindingErrors.stream().noneMatch(d -> d.message().contains("range")),
                "no unknown range binding diagnostic for any arity");
    }

    @Test
    void nonBareRangeFormsContinueThroughOrdinaryIterablePipeline() throws Exception {
        var phaseInput = phaseInput("suite_d0_non_bare_range.gd", """
                class_name SuiteD0NonBareRange
                extends Node
                
                func ping(obj, some_range):
                    for i in obj.range(3):
                        pass
                    for j in some_range(3):
                        pass
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatements = pingFunction.body().statements().stream()
                .filter(ForStatement.class::isInstance)
                .map(ForStatement.class::cast)
                .toList();
        var attributeIterable = forStatements.getFirst().iterable();
        var someRangeIterable = forStatements.get(1).iterable();
        var someRangeCall = assertInstanceOf(CallExpression.class, someRangeIterable);

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertNotNull(phaseInput.analysisData().expressionTypes().get(attributeIterable),
                        "obj.range(3) iterable must have expression type via ordinary pipeline"),
                () -> assertNotNull(phaseInput.analysisData().expressionTypes().get(someRangeCall),
                        "some_range(3) call root must have expression type via ordinary pipeline"),
                () -> assertNotNull(phaseInput.analysisData().resolvedCalls().get(someRangeCall),
                        "some_range(3) must produce resolved call via ordinary pipeline")
        );
    }

    @Test
    void ordinaryIterableReadsPrefixOverlayAndBodyEnters() throws Exception {
        var phaseInput = phaseInput("suite_d0_ordinary_iterable.gd", """
                class_name SuiteD0OrdinaryIterable
                extends Node
                
                func ping():
                    var limit := 3
                    for i in limit:
                        var x := i
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );

        resolveWithDefaultOwnerProcedures(phaseInput);

        var iterableType = requireValue(phaseInput.analysisData().expressionTypes().get(forStatement.iterable()));
        assertAll(
                () -> assertEquals(GdIntType.INT, requireType(iterableType.publishedType()),
                        "limit must be stabilized to int"),
                () -> assertEquals(GdIntType.INT, phaseInput.analysisData().slotTypes().get(fromFor),
                        "body local x is int because iterator i is refined to int by D1")
        );
    }

    @Test
    void unknownIterableBodyEntersOrdinarySharedSemantic() throws Exception {
        var phaseInput = phaseInput("suite_d0_unknown_iterable.gd", """
                class_name SuiteD0UnknownIterable
                extends Node
                
                func ping(values):
                    for item in values:
                        var x := item
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );
        var itemUseSite = findNode(fromFor, IdentifierExpression.class, id -> id.name().equals("item"));

        resolveWithDefaultOwnerProcedures(phaseInput);

        var itemBinding = requireValue(phaseInput.analysisData().symbolBindings().get(itemUseSite));
        assertAll(
                () -> assertEquals(FrontendBindingKind.LOCAL_VAR,
                        itemBinding.kind()),
                () -> assertNotNull(phaseInput.analysisData().slotTypes().get(fromFor),
                        "body local x must have slot type")
        );
        var deferredDiagnostics = diagnosticsByCategory(
                phaseInput.analysisData().diagnostics(), "sema.deferred_expression_resolution");
        assertTrue(deferredDiagnostics.isEmpty(), "no FOR_SUBTREE deferred result expected");
    }

    @Test
    void explicitIteratorTypeProvidesDeclaredBaselineInBody() throws Exception {
        var phaseInput = phaseInput("suite_d0_explicit_iterator_type.gd", """
                class_name SuiteD0ExplicitIteratorType
                extends Node
                
                func ping():
                    for i: float in range(3):
                        var x := i
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );
        var iUseSite = findNode(fromFor, IdentifierExpression.class, id -> id.name().equals("i"));

        resolveWithDefaultOwnerProcedures(phaseInput);

        var iBinding = requireValue(phaseInput.analysisData().symbolBindings().get(iUseSite));
        var iValue = requireValue(iBinding.resolvedValue());
        assertAll(
                () -> assertEquals(GdFloatType.FLOAT, iValue.type(),
                        "iterator baseline must be declared float type")
        );
        var rangeCall = assertInstanceOf(CallExpression.class, forStatement.iterable());
        assertNull(phaseInput.analysisData().expressionTypes().get(rangeCall),
                "range call root must not have expression type even with explicit iterator type");
    }

    @Test
    void shadowedRangeNameStillHitsBareRangePreRoute() throws Exception {
        var phaseInput = phaseInput("suite_d0_shadow_range.gd", """
                class_name SuiteD0ShadowRange
                extends Node
                
                func ping():
                    var range = 42
                    for i in range(3):
                        pass
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var rangeCall = assertInstanceOf(CallExpression.class, forStatement.iterable());
        var callee = assertInstanceOf(IdentifierExpression.class, rangeCall.callee());

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertNull(phaseInput.analysisData().expressionTypes().get(rangeCall),
                        "shadowed range(3) must still hit pre-route, no call-root expression type"),
                () -> assertNull(phaseInput.analysisData().resolvedCalls().get(rangeCall),
                        "shadowed range(3) must still hit pre-route, no resolved call"),
                () -> assertNull(phaseInput.analysisData().symbolBindings().get(callee),
                        "shadowed range callee must not have ordinary binding")
        );
    }

    @Test
    void bareIdentifierRangeDoesNotHitPreRoute() throws Exception {
        var phaseInput = phaseInput("suite_d0_bare_identifier_range.gd", """
                class_name SuiteD0BareIdentifierRange
                extends Node
                
                func ping():
                    var range = 42
                    for i in range:
                        pass
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var iterable = forStatement.iterable();
        assertInstanceOf(IdentifierExpression.class, iterable);

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertNotNull(phaseInput.analysisData().expressionTypes().get(iterable),
                "bare identifier range must go through ordinary pipeline and get expression type");
    }

    @Test
    void nestedForUsesSameD0PreRoutePath() throws Exception {
        var phaseInput = phaseInput("suite_d0_nested_for.gd", """
                class_name SuiteD0NestedFor
                extends Node
                
                func ping():
                    for i in range(3):
                        for j in range(i):
                            var x := j
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var outerFor = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var innerFor = findStatement(outerFor.body().statements(), ForStatement.class, _ -> true);
        var outerRange = assertInstanceOf(CallExpression.class, outerFor.iterable());
        var innerRange = assertInstanceOf(CallExpression.class, innerFor.iterable());
        var fromInner = findStatement(
                innerFor.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertNull(phaseInput.analysisData().expressionTypes().get(outerRange),
                        "outer range call root must not have expression type"),
                () -> assertNull(phaseInput.analysisData().expressionTypes().get(innerRange),
                        "inner range call root must not have expression type"),
                () -> assertNotNull(phaseInput.analysisData().slotTypes().get(fromInner),
                        "inner body local must have slot type"),
                () -> assertNotNull(phaseInput.analysisData().expressionTypes().get(innerRange.arguments().getFirst()),
                        "inner range argument i must have expression type")
        );
    }

    @Test
    void d1PublishesIterationPlanForRangeCall() throws Exception {
        var phaseInput = phaseInput("suite_d1_range_plan.gd", """
                class_name SuiteD1RangePlan
                extends Node
                
                func ping():
                    for i in range(3):
                        var x := i + 1
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);

        resolveWithDefaultOwnerProcedures(phaseInput);

        var plan = requireValue(phaseInput.analysisData().forIterationPlans().get(forStatement));
        assertAll(
                () -> assertEquals(FrontendForIterationRoute.RANGE_CALL, plan.route()),
                () -> assertEquals("i", plan.iteratorName()),
                () -> assertEquals(GdIntType.INT, plan.semanticElementType()),
                () -> assertEquals(GdIntType.INT, plan.exposedIteratorType()),
                () -> assertEquals(1, plan.sourceOperands().size())
        );
        assertEquals(GdIntType.INT, phaseInput.analysisData().slotTypes().get(forStatement),
                "slotTypes()[ForStatement] must be exposed iterator type");
    }

    @Test
    void d1PublishesIntShorthandPlanForIntIterable() throws Exception {
        var phaseInput = phaseInput("suite_d1_int_shorthand_plan.gd", """
                class_name SuiteD1IntShorthandPlan
                extends Node
                
                func ping():
                    var limit := 5
                    for i in limit:
                        var x := i + 1
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );

        resolveWithDefaultOwnerProcedures(phaseInput);

        var plan = requireValue(phaseInput.analysisData().forIterationPlans().get(forStatement));
        assertAll(
                () -> assertEquals(FrontendForIterationRoute.INT_SHORTHAND, plan.route()),
                () -> assertEquals(GdIntType.INT, plan.semanticElementType()),
                () -> assertEquals(GdIntType.INT, plan.exposedIteratorType()),
                () -> assertEquals(GdIntType.INT, phaseInput.analysisData().slotTypes().get(fromFor),
                        "body local x must be int via refined iterator")
        );
    }

    @Test
    void d1UnknownIterablePublishesGenericVariantPlanAndKeepsIteratorVariant() throws Exception {
        var phaseInput = phaseInput("suite_d1_generic_plan.gd", """
                class_name SuiteD1GenericPlan
                extends Node
                
                func ping(values):
                    for item in values:
                        var x := item
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);

        resolveWithDefaultOwnerProcedures(phaseInput);

        var plan = requireValue(phaseInput.analysisData().forIterationPlans().get(forStatement));
        assertAll(
                () -> assertEquals(FrontendForIterationRoute.GENERIC_VARIANT, plan.route()),
                () -> assertEquals(GdVariantType.VARIANT, plan.semanticElementType()),
                () -> assertEquals(GdVariantType.VARIANT, plan.exposedIteratorType()),
                () -> assertEquals(GdVariantType.VARIANT, phaseInput.analysisData().slotTypes().get(forStatement),
                        "iterator slot stays Variant for generic route")
        );
    }

    @Test
    void d1ExplicitIteratorTypePublishesSemanticAndExposedTypes() throws Exception {
        var phaseInput = phaseInput("suite_d1_explicit_type_conversion.gd", """
                class_name SuiteD1ExplicitTypeConversion
                extends Node
                
                func ping():
                    for i: float in range(3):
                        var x := i
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);

        resolveWithDefaultOwnerProcedures(phaseInput);

        var plan = requireValue(phaseInput.analysisData().forIterationPlans().get(forStatement));
        assertAll(
                () -> assertEquals(FrontendForIterationRoute.RANGE_CALL, plan.route()),
                () -> assertEquals(GdIntType.INT, plan.semanticElementType(),
                        "semantic element type is int for range"),
                () -> assertEquals(GdFloatType.FLOAT, plan.exposedIteratorType(),
                        "exposed iterator type is declared float"),
                () -> assertEquals(GdFloatType.FLOAT, phaseInput.analysisData().slotTypes().get(forStatement),
                        "slotTypes()[ForStatement] must be declared float")
        );
    }

    @Test
    void d1NestedForReadsOuterRefinedIteratorType() throws Exception {
        var phaseInput = phaseInput("suite_d1_nested_refined.gd", """
                class_name SuiteD1NestedRefined
                extends Node
                
                func ping():
                    for i in range(3):
                        for j in range(i):
                            pass
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var outerFor = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var innerFor = findStatement(outerFor.body().statements(), ForStatement.class, _ -> true);
        var innerRange = assertInstanceOf(CallExpression.class, innerFor.iterable());
        var innerArg = innerRange.arguments().getFirst();

        resolveWithDefaultOwnerProcedures(phaseInput);

        var innerArgType = requireValue(phaseInput.analysisData().expressionTypes().get(innerArg));
        assertAll(
                () -> assertEquals(GdIntType.INT, requireType(innerArgType.publishedType()),
                        "inner range argument i must be refined to int from outer loop")
        );
        var innerPlan = phaseInput.analysisData().forIterationPlans().get(innerFor);
        assertNotNull(innerPlan, "inner iteration plan must be published");
        assertEquals(FrontendForIterationRoute.RANGE_CALL, innerPlan.route());
    }

    @Test
    void kNestedForBodyIdentifierUsesCarryRefinedIteratorTypes() throws Exception {
        var phaseInput = phaseInput("suite_k_nested_for_body_ids.gd", """
                class_name SuiteKNestedForBodyIds
                extends Node
                
                func ping():
                    for i in range(3):
                        for j in range(2):
                            var x := j + 1
                        var y := i + 1
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var outerFor = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var innerFor = findStatement(outerFor.body().statements(), ForStatement.class, _ -> true);
        var fromInner = findStatement(
                innerFor.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );
        var fromOuter = findStatement(
                outerFor.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("y")
        );
        var jUse = findNode(
                requireExpression(fromInner.value()),
                IdentifierExpression.class,
                identifier -> identifier.name().equals("j")
        );
        var iUse = findNode(
                requireExpression(fromOuter.value()),
                IdentifierExpression.class,
                identifier -> identifier.name().equals("i")
        );

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertEquals(GdIntType.INT, phaseInput.analysisData().slotTypes().get(outerFor)),
                () -> assertEquals(GdIntType.INT, phaseInput.analysisData().slotTypes().get(innerFor)),
                () -> assertEquals(GdIntType.INT, requireType(requireValue(
                        phaseInput.analysisData().expressionTypes().get(jUse)).publishedType()),
                        "inner body use of j must keep FOR_ITERATION_RESOLUTION int, not baseline Variant"),
                () -> assertEquals(GdIntType.INT, requireType(requireValue(
                        phaseInput.analysisData().expressionTypes().get(iUse)).publishedType()),
                        "outer body use of i must keep refined int across nested suite boundary")
        );
    }

    @Test
    void kTripleNestedForBodyIdentifiersAllCarryRefinedInt() throws Exception {
        var phaseInput = phaseInput("suite_k_triple_nested_for.gd", """
                class_name SuiteKTripleNestedFor
                extends Node
                
                func ping():
                    for i in range(3):
                        for j in range(2):
                            for k in range(1):
                                var x := i + j + k
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var outerFor = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var midFor = findStatement(outerFor.body().statements(), ForStatement.class, _ -> true);
        var innerFor = findStatement(midFor.body().statements(), ForStatement.class, _ -> true);
        var fromInner = findStatement(
                innerFor.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );
        var iUse = findNode(requireExpression(fromInner.value()), IdentifierExpression.class,
                identifier -> identifier.name().equals("i"));
        var jUse = findNode(requireExpression(fromInner.value()), IdentifierExpression.class,
                identifier -> identifier.name().equals("j"));
        var kUse = findNode(requireExpression(fromInner.value()), IdentifierExpression.class,
                identifier -> identifier.name().equals("k"));

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertEquals(GdIntType.INT, requireType(requireValue(
                        phaseInput.analysisData().expressionTypes().get(iUse)).publishedType())),
                () -> assertEquals(GdIntType.INT, requireType(requireValue(
                        phaseInput.analysisData().expressionTypes().get(jUse)).publishedType())),
                () -> assertEquals(GdIntType.INT, requireType(requireValue(
                        phaseInput.analysisData().expressionTypes().get(kUse)).publishedType()))
        );
    }

    @Test
    void kWhileNestedForBodyIdentifierKeepsRefinedIteratorType() throws Exception {
        var phaseInput = phaseInput("suite_k_while_for_iterator.gd", """
                class_name SuiteKWhileForIterator
                extends Node
                
                func ping(flag: bool):
                    while flag:
                        for i in range(3):
                            var x := i + 1
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var whileStatement = findStatement(pingFunction.body().statements(), WhileStatement.class, _ -> true);
        var forStatement = findStatement(whileStatement.body().statements(), ForStatement.class, _ -> true);
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );
        var iUse = findNode(
                requireExpression(fromFor.value()),
                IdentifierExpression.class,
                identifier -> identifier.name().equals("i")
        );

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertEquals(
                GdIntType.INT,
                requireType(requireValue(phaseInput.analysisData().expressionTypes().get(iUse)).publishedType()),
                "for iterator under while must still expose refined int in body uses"
        );
    }

    @Test
    void kExplicitVariantIteratorStaysVariantInNestedBody() throws Exception {
        var phaseInput = phaseInput("suite_k_explicit_variant_iterator.gd", """
                class_name SuiteKExplicitVariantIterator
                extends Node
                
                func ping():
                    for i: Variant in range(3):
                        for j in range(2):
                            var x = i
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var outerFor = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var innerFor = findStatement(outerFor.body().statements(), ForStatement.class, _ -> true);
        var fromInner = findStatement(
                innerFor.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );
        var iUse = findNode(
                requireExpression(fromInner.value()),
                IdentifierExpression.class,
                identifier -> identifier.name().equals("i")
        );

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertEquals(GdVariantType.VARIANT, phaseInput.analysisData().slotTypes().get(outerFor),
                        "explicit Variant iterator must not be refined to int"),
                () -> assertEquals(GdVariantType.VARIANT, requireType(requireValue(
                        phaseInput.analysisData().expressionTypes().get(iUse)).publishedType()),
                        "nested body use of explicit Variant iterator must stay Variant"),
                () -> assertEquals(GdIntType.INT, phaseInput.analysisData().slotTypes().get(innerFor))
        );
    }

    @Test
    void kShadowedNestedForIteratorTypesStayBoundToDeclarationIdentity() throws Exception {
        var phaseInput = phaseInput("suite_k_shadowed_for_iterator.gd", """
                class_name SuiteKShadowedForIterator
                extends Node
                
                func ping():
                    for i in range(3):
                        for i in range(2):
                            var inner := i
                        var outer := i
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var outerFor = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var innerFor = findStatement(outerFor.body().statements(), ForStatement.class, _ -> true);
        var innerLocal = findStatement(
                innerFor.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("inner")
        );
        var outerLocal = findStatement(
                outerFor.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("outer")
        );
        var innerUse = findNode(
                requireExpression(innerLocal.value()),
                IdentifierExpression.class,
                identifier -> identifier.name().equals("i")
        );
        var outerUse = findNode(
                requireExpression(outerLocal.value()),
                IdentifierExpression.class,
                identifier -> identifier.name().equals("i")
        );

        resolveWithDefaultOwnerProcedures(phaseInput);

        var innerBinding = requireValue(phaseInput.analysisData().symbolBindings().get(innerUse));
        var outerBinding = requireValue(phaseInput.analysisData().symbolBindings().get(outerUse));
        assertAll(
                () -> assertSame(innerFor, innerBinding.declarationSite(),
                        "inner body i must bind to inner ForStatement"),
                () -> assertSame(outerFor, outerBinding.declarationSite(),
                        "outer body i must bind to outer ForStatement"),
                () -> assertEquals(GdIntType.INT, requireType(requireValue(
                        phaseInput.analysisData().expressionTypes().get(innerUse)).publishedType())),
                () -> assertEquals(GdIntType.INT, requireType(requireValue(
                        phaseInput.analysisData().expressionTypes().get(outerUse)).publishedType()))
        );
    }

    @Test
    void bareRangePreRouteResolvesDynamicVariableArguments() throws Exception {
        var phaseInput = phaseInput("suite_d0_range_dynamic_args.gd", """
                class_name SuiteD0RangeDynamicArgs
                extends Node
                
                func ping(start: int, end: int):
                    for i in range(start, end):
                        var x := i
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var rangeCall = assertInstanceOf(CallExpression.class, forStatement.iterable());
        var callee = assertInstanceOf(IdentifierExpression.class, rangeCall.callee());
        var startArg = rangeCall.arguments().getFirst();
        var endArg = rangeCall.arguments().get(1);
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertNull(phaseInput.analysisData().symbolBindings().get(callee),
                        "range callee must not have ordinary binding"),
                () -> assertNull(phaseInput.analysisData().expressionTypes().get(rangeCall),
                        "range call root must not have expression type"),
                () -> assertNull(phaseInput.analysisData().resolvedCalls().get(rangeCall),
                        "range call root must not have resolved call"),
                () -> assertNotNull(phaseInput.analysisData().expressionTypes().get(startArg),
                        "start argument must have expression type"),
                () -> assertEquals(GdIntType.INT,
                        requireType(requireValue(phaseInput.analysisData().expressionTypes().get(startArg)).publishedType()),
                        "start argument must resolve to int parameter type"),
                () -> assertNotNull(phaseInput.analysisData().expressionTypes().get(endArg),
                        "end argument must have expression type"),
                () -> assertEquals(GdIntType.INT,
                        requireType(requireValue(phaseInput.analysisData().expressionTypes().get(endArg)).publishedType()),
                        "end argument must resolve to int parameter type"),
                () -> assertEquals(GdIntType.INT, phaseInput.analysisData().slotTypes().get(fromFor),
                        "body local x is int via refined iterator")
        );
        var plan = requireValue(phaseInput.analysisData().forIterationPlans().get(forStatement));
        assertAll(
                () -> assertEquals(FrontendForIterationRoute.RANGE_CALL, plan.route()),
                () -> assertEquals(2, plan.sourceOperands().size(),
                        "plan must preserve both source operands")
        );
    }

    @Test
    void bareRangePreRouteResolvesThreeDynamicArguments() throws Exception {
        var phaseInput = phaseInput("suite_d0_range_three_dynamic_args.gd", """
                class_name SuiteD0RangeThreeDynamicArgs
                extends Node
                
                func ping():
                    var start := 2
                    var end := 10
                    var step := 3
                    for i in range(start, end, step):
                        pass
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var rangeCall = assertInstanceOf(CallExpression.class, forStatement.iterable());

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertNull(phaseInput.analysisData().expressionTypes().get(rangeCall),
                        "range call root must not have expression type"),
                () -> assertEquals(3, rangeCall.arguments().size()),
                () -> assertNotNull(phaseInput.analysisData().expressionTypes().get(rangeCall.arguments().getFirst()),
                        "start argument must have expression type"),
                () -> assertEquals(GdIntType.INT,
                        requireType(requireValue(phaseInput.analysisData().expressionTypes().get(rangeCall.arguments().getFirst())).publishedType())),
                () -> assertNotNull(phaseInput.analysisData().expressionTypes().get(rangeCall.arguments().get(1)),
                        "end argument must have expression type"),
                () -> assertEquals(GdIntType.INT,
                        requireType(requireValue(phaseInput.analysisData().expressionTypes().get(rangeCall.arguments().get(1))).publishedType())),
                () -> assertNotNull(phaseInput.analysisData().expressionTypes().get(rangeCall.arguments().get(2)),
                        "step argument must have expression type"),
                () -> assertEquals(GdIntType.INT,
                        requireType(requireValue(phaseInput.analysisData().expressionTypes().get(rangeCall.arguments().get(2))).publishedType()))
        );
        var plan = phaseInput.analysisData().forIterationPlans().get(forStatement);
        assertNotNull(plan);
        assertEquals(3, plan.sourceOperands().size(), "plan must preserve all three source operands");
    }

    @Test
    void bareRangePreRouteResolvesMixedLiteralAndDynamicArguments() throws Exception {
        var phaseInput = phaseInput("suite_d0_range_mixed_args.gd", """
                class_name SuiteD0RangeMixedArgs
                extends Node
                
                func ping(limit: int):
                    for i in range(0, limit, 2):
                        var x := i
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var rangeCall = assertInstanceOf(CallExpression.class, forStatement.iterable());
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("x")
        );

        resolveWithDefaultOwnerProcedures(phaseInput);

        assertAll(
                () -> assertNull(phaseInput.analysisData().expressionTypes().get(rangeCall),
                        "range call root must not have expression type"),
                () -> assertEquals(GdIntType.INT,
                        requireType(requireValue(phaseInput.analysisData().expressionTypes().get(rangeCall.arguments().getFirst())).publishedType()),
                        "literal 0 must be int"),
                () -> assertEquals(GdIntType.INT,
                        requireType(requireValue(phaseInput.analysisData().expressionTypes().get(rangeCall.arguments().get(1))).publishedType()),
                        "dynamic limit must resolve to int"),
                () -> assertEquals(GdIntType.INT,
                        requireType(requireValue(phaseInput.analysisData().expressionTypes().get(rangeCall.arguments().get(2))).publishedType()),
                        "literal 2 must be int"),
                () -> assertEquals(GdIntType.INT, phaseInput.analysisData().slotTypes().get(fromFor),
                        "body local x is int via refined iterator")
        );
    }

    private static void resolveWith(
            @NotNull PhaseInput phaseInput,
            @NotNull FrontendStatementResolver.OwnerProcedures ownerProcedures
    ) {
        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
        new FrontendSuiteResolver(new FrontendStatementResolver(ownerProcedures)).resolve(
                surface,
                phaseInput.registry(),
                phaseInput.analysisData(),
                phaseInput.diagnostics()
        );
    }

    private static void resolveWithDefaultOwnerProcedures(@NotNull PhaseInput phaseInput) {
        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
        new FrontendSuiteResolver().resolve(
                surface,
                phaseInput.registry(),
                phaseInput.analysisData(),
                phaseInput.diagnostics()
        );
    }

    private static @NotNull FrontendInterfaceSurface reorderBodyDeclarations(
            @NotNull FrontendInterfaceSurface originalSurface,
            @NotNull Block body,
            @NotNull List<FrontendBodyLocalDeclaration> bodyDeclarations
    ) {
        var reordered = List.of(
                new FrontendBodyLocalDeclaration(
                        bodyDeclarations.get(1).declaration(),
                        bodyDeclarations.get(1).binding(),
                        bodyDeclarations.get(1).kind(),
                        0
                ),
                new FrontendBodyLocalDeclaration(
                        bodyDeclarations.getFirst().declaration(),
                        bodyDeclarations.getFirst().binding(),
                        bodyDeclarations.getFirst().kind(),
                        1
                )
        );
        var declarationsByBody = new IdentityHashMap<>(
                originalSurface.bodyDeclarationIndex().declarationsByBodyRoot()
        );
        declarationsByBody.put(body, reordered);
        return new FrontendInterfaceSurface(
                new FrontendBodyDeclarationIndex(declarationsByBody),
                originalSurface.typedLexicalBaseline(),
                originalSurface.suiteEntryRoots()
        );
    }

    private static void assertExpressionPointType(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Expression expression
    ) {
        var expressionType = requireValue(analysisData.expressionTypes().get(expression));
        assertPointType(requireType(expressionType.publishedType()));
    }

    private static @NotNull Expression requireExpression(@Nullable Expression expression) {
        return requireValue(expression);
    }

    private static @NotNull GdType requireType(@Nullable GdType type) {
        return requireValue(type);
    }

    private static <T> @NotNull T requireValue(@Nullable T value) {
        assertNotNull(value);
        return Objects.requireNonNull(value);
    }

    private static void assertPointType(@NotNull GdType type) {
        assertTrue(type.getTypeName().endsWith("Point"),
                () -> "Expected type name to end with 'Point' but was " + type.getTypeName());
    }

    private static void assertOwnerSequence(@NotNull List<OwnerEvent> events, int offset, @NotNull Node root) {
        assertSame(root, events.get(offset).root());
        assertEquals("top", events.get(offset).stage());
        assertSame(root, events.get(offset + 1).root());
        assertEquals("local", events.get(offset + 1).stage());
        assertSame(root, events.get(offset + 2).root());
        assertEquals("chain", events.get(offset + 2).stage());
        assertSame(root, events.get(offset + 3).root());
        assertEquals("expr", events.get(offset + 3).stage());
        assertSame(root, events.get(offset + 4).root());
        assertEquals("var_post", events.get(offset + 4).stage());
    }

    private static int firstStageIndex(@NotNull List<OwnerEvent> events, @NotNull Node root) {
        for (var i = 0; i < events.size(); i++) {
            if (events.get(i).root() == root) {
                return i;
            }
        }
        throw new AssertionError("Missing event for root: " + root.getClass().getSimpleName());
    }

    private static boolean hasOwnerEvent(@NotNull List<OwnerEvent> events, @NotNull Node root) {
        return events.stream().anyMatch(event -> event.root() == root);
    }

    private static void assertCertificateFailure(
            @NotNull PhaseInput phaseInput,
            @NotNull FrontendInterfaceSurface surface,
            @NotNull Node callableOwner,
            @NotNull Block body,
            @NotNull String expectedDetail
    ) {
        var context = contextForBlock(phaseInput, surface, callableOwner, body);
        var error = assertThrows(
                IllegalStateException.class,
                () -> new FrontendSuiteResolver().resolveSuite(context, body)
        );
        assertTrue(error.getMessage().contains(expectedDetail), error::getMessage);
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String category
    ) {
        return diagnostics.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private static @NotNull FrontendSuiteContext contextForBlock(
            @NotNull PhaseInput phaseInput,
            @NotNull FrontendInterfaceSurface surface,
            @NotNull Node callableOwner,
            @NotNull Block block
    ) {
        var publishedScope = phaseInput.analysisData().scopesByAst().get(block);
        var blockScope = assertInstanceOf(BlockScope.class, publishedScope);
        return new FrontendSuiteContext(
                Path.of("tmp", "synthetic_gate_body.gd"),
                callableOwner,
                block,
                blockScope,
                blockScope,
                ResolveRestriction.instanceContext(),
                false,
                null,
                surface,
                new FrontendTypedLexicalEnvironment(
                        blockScope,
                        phaseInput.analysisData(),
                        null,
                        surface.typedLexicalBaseline()
                ),
                phaseInput.analysisData(),
                phaseInput.diagnostics(),
                phaseInput.registry(),
                null,
                null,
                null
        );
    }

    private static @NotNull PhaseInput phaseInput(@NotNull String fileName, @NotNull String source) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());

        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var module = new FrontendModule("test_module", List.of(unit));
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(module, registry, diagnostics, analysisData);
        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnostics.snapshot());
        new FrontendScopeAnalyzer().analyze(registry, analysisData, diagnostics);
        analysisData.updateDiagnostics(diagnostics.snapshot());
        new FrontendVariableAnalyzer().analyze(analysisData, diagnostics);
        analysisData.updateDiagnostics(diagnostics.snapshot());
        return new PhaseInput(unit, registry, analysisData, diagnostics);
    }

    private static <T extends Statement> T findStatement(
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

    private static <T extends Node> T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        if (nodeType.isInstance(root)) {
            var candidate = nodeType.cast(root);
            if (predicate.test(candidate)) {
                return candidate;
            }
        }
        for (var child : root.getChildren()) {
            var match = findNodeOrNull(child, nodeType, predicate);
            if (match != null) {
                return match;
            }
        }
        throw new AssertionError("Node not found: " + nodeType.getSimpleName());
    }

    private static <T extends Node> T findNodeOrNull(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        if (nodeType.isInstance(root)) {
            var candidate = nodeType.cast(root);
            if (predicate.test(candidate)) {
                return candidate;
            }
        }
        for (var child : root.getChildren()) {
            var match = findNodeOrNull(child, nodeType, predicate);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private record PhaseInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull ClassRegistry registry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics
    ) {
    }

    private record OwnerEvent(@NotNull String stage, @NotNull Node root) {
    }

    private static final class StatementSnapshotOwnerProcedures implements FrontendStatementResolver.OwnerProcedures {
        private final @NotNull Node first;
        private final @NotNull Node second;
        private boolean sameStatementSawLiveDiagnostic;
        private boolean sameStatementSawStableSnapshotBeforeFlush;
        private boolean nextStatementSawCurrentSuiteSnapshot;

        private StatementSnapshotOwnerProcedures(@NotNull Node first, @NotNull Node second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void runTopBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            if (root == first) {
                context.diagnosticManager().error(
                        STATEMENT_SNAPSHOT_TEST_CATEGORY,
                        "synthetic statement-local upstream diagnostic",
                        context.sourcePath(),
                        FrontendRange.fromAstRange(root.range())
                );
                return;
            }
            if (root == second) {
                nextStatementSawCurrentSuiteSnapshot = hasStatementSnapshotDiagnostic(context.analysisData().diagnostics());
            }
        }

        @Override
        public void runChainBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            if (root != first) {
                return;
            }
            sameStatementSawLiveDiagnostic = hasStatementSnapshotDiagnostic(context.diagnosticManager().snapshot());
            sameStatementSawStableSnapshotBeforeFlush = hasStatementSnapshotDiagnostic(context.analysisData().diagnostics());
        }

        private static boolean hasStatementSnapshotDiagnostic(@NotNull DiagnosticSnapshot diagnostics) {
            return diagnosticsByCategory(diagnostics, STATEMENT_SNAPSHOT_TEST_CATEGORY).size() == 1;
        }

        private boolean sameStatementSawLiveDiagnostic() {
            return sameStatementSawLiveDiagnostic;
        }

        private boolean sameStatementSawStableSnapshotBeforeFlush() {
            return sameStatementSawStableSnapshotBeforeFlush;
        }

        private boolean nextStatementSawCurrentSuiteSnapshot() {
            return nextStatementSawCurrentSuiteSnapshot;
        }
    }

    private static final class RecordingOwnerProcedures implements FrontendStatementResolver.OwnerProcedures {
        private final boolean publishTopBinding;
        private final @NotNull List<OwnerEvent> events = new ArrayList<>();
        private final @NotNull List<Node> unsupportedRoots = new ArrayList<>();
        private boolean pendingBindingWasVisibleBeforeStableApply;
        private boolean stableWasEmptyDuringOwnerProcedure = true;

        private RecordingOwnerProcedures(boolean publishTopBinding) {
            this.publishTopBinding = publishTopBinding;
        }

        @Override
        public void runTopBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            events.add(new OwnerEvent("top", root));
            if (!publishTopBinding) {
                return;
            }
            stableWasEmptyDuringOwnerProcedure &= context.analysisData().symbolBindings().get(root) == null;
            context.typedEnvironment().putSymbolBinding(
                    FrontendSemanticStage.TOP_BINDING,
                    root,
                    new FrontendBinding("__suite_probe__", FrontendBindingKind.UNKNOWN, null)
            );
        }

        @Override
        public void runLocalTypeStabilization(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            events.add(new OwnerEvent("local", root));
            stableWasEmptyDuringOwnerProcedure &= context.analysisData().symbolBindings().get(root) == null;
            pendingBindingWasVisibleBeforeStableApply |= context.typedEnvironment().symbolBinding(root) != null;
        }

        @Override
        public void runChainBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            events.add(new OwnerEvent("chain", root));
            stableWasEmptyDuringOwnerProcedure &= context.analysisData().symbolBindings().get(root) == null;
        }

        @Override
        public void runExprType(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            events.add(new OwnerEvent("expr", root));
            stableWasEmptyDuringOwnerProcedure &= context.analysisData().symbolBindings().get(root) == null;
        }

        @Override
        public void runVarTypePost(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            events.add(new OwnerEvent("var_post", root));
            stableWasEmptyDuringOwnerProcedure &= context.analysisData().symbolBindings().get(root) == null;
        }

        @Override
        public void runUnsupported(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            unsupportedRoots.add(root);
        }

        private @NotNull List<OwnerEvent> events() {
            return events;
        }

        private @NotNull List<Node> unsupportedRoots() {
            return unsupportedRoots;
        }

        private boolean pendingBindingWasVisibleBeforeStableApply() {
            return pendingBindingWasVisibleBeforeStableApply;
        }

        private boolean stableWasEmptyDuringOwnerProcedure() {
            return stableWasEmptyDuringOwnerProcedure;
        }
    }

    private static final class DeferredChildExportOwnerProcedures implements FrontendStatementResolver.OwnerProcedures {
        private final @NotNull Node child;
        private final @NotNull Node parentContinuation;
        private boolean childBindingWasUnpublishedInParentContinuation;

        private DeferredChildExportOwnerProcedures(@NotNull Node child, @NotNull Node parentContinuation) {
            this.child = child;
            this.parentContinuation = parentContinuation;
        }

        @Override
        public void runTopBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            if (root == child) {
                context.typedEnvironment().putSymbolBinding(
                        FrontendSemanticStage.TOP_BINDING,
                        child,
                        new FrontendBinding("__nested_suite_probe__", FrontendBindingKind.UNKNOWN, null)
                );
                return;
            }
            if (root == parentContinuation) {
                childBindingWasUnpublishedInParentContinuation = context.analysisData()
                        .symbolBindings()
                        .get(child) == null;
            }
        }

        private boolean childBindingWasUnpublishedInParentContinuation() {
            return childBindingWasUnpublishedInParentContinuation;
        }
    }

    private static final class ExportBoundaryOwnerProcedures implements FrontendStatementResolver.OwnerProcedures {
        private final @NotNull FrontendBodyOwnerProcedures delegate = new FrontendBodyOwnerProcedures();
        private final @NotNull VariableDeclaration first;
        private final @NotNull VariableDeclaration second;
        private boolean localStabilizationKeptInitializerTypeInTransientCache;
        private boolean varPostPendingFactWasIsolatedFromStableTable;
        private boolean flushedVarPostFactWasVisibleBeforeSuiteExport;

        private ExportBoundaryOwnerProcedures(
                @NotNull VariableDeclaration first,
                @NotNull VariableDeclaration second
        ) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void runTopBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            if (root == second) {
                flushedVarPostFactWasVisibleBeforeSuiteExport = context.analysisData().slotTypes().get(first) == null
                        && context.typedEnvironment().slotType(first) != null;
            }
            delegate.runTopBinding(context, root);
        }

        @Override
        public void runLocalTypeStabilization(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            delegate.runLocalTypeStabilization(context, root);
            if (root == first) {
                var currentBlockScope = context.currentBlockScope();
                localStabilizationKeptInitializerTypeInTransientCache = first.value() != null
                        && currentBlockScope != null
                        && context.typedEnvironment().expressionType(first.value()) == null
                        && context.typedEnvironment().localSlotType(
                        currentBlockScope,
                        first.name(),
                        first
                ) != null;
            }
        }

        @Override
        public void runChainBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            delegate.runChainBinding(context, root);
        }

        @Override
        public void runExprType(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            delegate.runExprType(context, root);
        }

        @Override
        public void runVarTypePost(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            delegate.runVarTypePost(context, root);
            if (root == first) {
                varPostPendingFactWasIsolatedFromStableTable = context.analysisData().slotTypes().get(first) == null
                        && context.typedEnvironment().slotType(first) != null;
            }
        }

        private boolean localStabilizationKeptInitializerTypeInTransientCache() {
            return localStabilizationKeptInitializerTypeInTransientCache;
        }

        private boolean varPostPendingFactWasIsolatedFromStableTable() {
            return varPostPendingFactWasIsolatedFromStableTable;
        }

        private boolean flushedVarPostFactWasVisibleBeforeSuiteExport() {
            return flushedVarPostFactWasVisibleBeforeSuiteExport;
        }
    }

    private static final class CallableEntryVarTypePostOwnerProcedures
            implements FrontendStatementResolver.OwnerProcedures {
        private final @NotNull Parameter parameter;
        private final @NotNull VariableDeclaration firstStatement;
        private boolean parameterWasCommittedBeforeFirstStatement;
        private boolean stableSlotTypesWereUnchangedBeforeSuiteExport;

        private CallableEntryVarTypePostOwnerProcedures(
                @NotNull Parameter parameter,
                @NotNull VariableDeclaration firstStatement
        ) {
            this.parameter = parameter;
            this.firstStatement = firstStatement;
        }

        @Override
        public void runTopBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
            if (root != firstStatement) {
                return;
            }
            var patch = context.typedEnvironment().exportPatchTransaction().patches().stream()
                    .filter(FrontendVarTypePostPatch.class::isInstance)
                    .map(FrontendVarTypePostPatch.class::cast)
                    .findFirst()
                    .orElse(null);
            parameterWasCommittedBeforeFirstStatement = patch != null
                    && patch.slotTypes().get(parameter) == GdIntType.INT
                    && context.typedEnvironment().slotType(parameter) == GdIntType.INT;
            stableSlotTypesWereUnchangedBeforeSuiteExport = context.analysisData().slotTypes().get(parameter) == null;
        }

        private boolean parameterWasCommittedBeforeFirstStatement() {
            return parameterWasCommittedBeforeFirstStatement;
        }

        private boolean stableSlotTypesWereUnchangedBeforeSuiteExport() {
            return stableSlotTypesWereUnchangedBeforeSuiteExport;
        }
    }

    private static final class RecordingInterfacePhase extends FrontendInterfacePhase {
        private boolean invoked;
        private boolean variableInventoryWasPublished;
        private FrontendInterfaceSurface surface;

        @Override
        public @NotNull FrontendInterfaceSurface analyze(
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData
        ) {
            invoked = true;
            variableInventoryWasPublished = analysisData.moduleSkeleton().sourceClassRelations().stream()
                    .allMatch(relation -> analysisData.scopesByAst().containsKey(relation.unit().ast()))
                    && analysisData.symbolBindings().isEmpty()
                    && analysisData.expressionTypes().isEmpty();
            surface = super.analyze(classRegistry, analysisData);
            return surface;
        }

        private boolean invoked() {
            return invoked;
        }

        private boolean variableInventoryWasPublished() {
            return variableInventoryWasPublished;
        }

        private @NotNull FrontendInterfaceSurface surface() {
            if (surface == null) {
                throw new AssertionError("Interface surface was not recorded");
            }
            return surface;
        }
    }

    private static final class RecordingSuiteResolver extends FrontendSuiteResolver {
        private boolean invoked;
        private boolean bodySideTablesWereEmptyAtHandoff;
        private FrontendInterfaceSurface surface;

        @Override
        public void resolve(
                @NotNull FrontendInterfaceSurface interfaceSurface,
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            invoked = true;
            surface = interfaceSurface;
            bodySideTablesWereEmptyAtHandoff = analysisData.symbolBindings().isEmpty()
                    && analysisData.resolvedMembers().isEmpty()
                    && analysisData.resolvedCalls().isEmpty()
                    && analysisData.expressionTypes().isEmpty()
                    && analysisData.slotTypes().isEmpty();
            super.resolve(interfaceSurface, classRegistry, analysisData, diagnosticManager);
        }

        private boolean invoked() {
            return invoked;
        }

        private boolean bodySideTablesWereEmptyAtHandoff() {
            return bodySideTablesWereEmptyAtHandoff;
        }

        private FrontendInterfaceSurface surface() {
            return surface;
        }
    }
}
