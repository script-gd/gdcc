package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.Block;
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
    void forBodyResolvesWhileUnsupportedFeatureOwnedBodiesRemainFailClosed() throws Exception {
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
        assertFalse(surface.suiteEntryRoots().containsSupportedBlock(lambdaExpression.body()));
        assertFalse(ownerProcedures.unsupportedRoots().contains(forStatement));
        assertTrue(ownerProcedures.unsupportedRoots().contains(matchStatement));
        assertTrue(ownerProcedures.unsupportedRoots().contains(answer));
        assertTrue(hasOwnerEvent(ownerProcedures.events(), fromFor));
        assertFalse(hasOwnerEvent(ownerProcedures.events(), fromMatch));
        assertFalse(hasOwnerEvent(ownerProcedures.events(), fromLambda));
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
        assertEquals(GdVariantType.VARIANT, phaseInput.analysisData().slotTypes().get(fromFor));
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

        var publishedScope = assertInstanceOf(
                BlockScope.class,
                phaseInput.analysisData().scopesByAst().get(forStatement.body())
        );
        var foreignScope = new BlockScope(publishedScope.getParentScope(), publishedScope.kind());
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
        var reordered = List.of(
                new FrontendBodyLocalDeclaration(
                        bodyDeclarations.get(1).declaration(),
                        bodyDeclarations.get(1).binding(),
                        bodyDeclarations.get(1).kind(),
                        0
                ),
                new FrontendBodyLocalDeclaration(
                        bodyDeclarations.get(0).declaration(),
                        bodyDeclarations.get(0).binding(),
                        bodyDeclarations.get(0).kind(),
                        1
                )
        );
        var declarationsByBody = new IdentityHashMap<>(
                originalSurface.bodyDeclarationIndex().declarationsByBodyRoot()
        );
        declarationsByBody.put(pingFunction.body(), reordered);
        var reorderedSurface = new FrontendInterfaceSurface(
                new FrontendBodyDeclarationIndex(declarationsByBody),
                originalSurface.typedLexicalBaseline(),
                originalSurface.suiteEntryRoots()
        );

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
                () -> assertTypeNameEndsWith(requireType(phaseInput.analysisData().slotTypes().get(a)), "Point"),
                () -> assertTypeNameEndsWith(requireType(phaseInput.analysisData().slotTypes().get(b)), "Point"),
                () -> assertTypeNameEndsWith(requireType(phaseInput.analysisData().slotTypes().get(c)), "Point"),
                () -> assertExpressionTypeNameEndsWith(phaseInput.analysisData(), requireExpression(a.value()), "Point"),
                () -> assertExpressionTypeNameEndsWith(phaseInput.analysisData(), requireExpression(b.value()), "Point"),
                () -> assertExpressionTypeNameEndsWith(phaseInput.analysisData(), requireExpression(c.value()), "Point")
        );
        var resolvedMember = phaseInput.analysisData().resolvedMembers().get(markerStep);
        assertNotNull(resolvedMember);
        assertAll(
                () -> assertTypeNameEndsWith(requireType(resolvedMember.receiverType()), "Point"),
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
                () -> assertTypeNameEndsWith(requireType(phaseInput.analysisData().slotTypes().get(stable)), "Point"),
                () -> assertTypeNameEndsWith(requireType(phaseInput.analysisData().slotTypes().get(child)), "Point"),
                () -> assertNull(phaseInput.analysisData().slotTypes().get(rejectedShadow))
        );
        var resolvedMember = phaseInput.analysisData().resolvedMembers().get(markerStep);
        assertNotNull(resolvedMember);
        assertTypeNameEndsWith(requireType(resolvedMember.receiverType()), "Point");
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

    private static void assertExpressionTypeNameEndsWith(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Expression expression,
            @NotNull String suffix
    ) {
        var expressionType = analysisData.expressionTypes().get(expression);
        assertNotNull(expressionType);
        assertNotNull(expressionType.publishedType());
        assertTypeNameEndsWith(requireType(expressionType.publishedType()), suffix);
    }

    private static @NotNull Expression requireExpression(@Nullable Expression expression) {
        assertNotNull(expression);
        return expression;
    }

    private static @NotNull GdType requireType(@Nullable GdType type) {
        assertNotNull(type);
        return type;
    }

    private static void assertTypeNameEndsWith(@NotNull GdType type, @NotNull String suffix) {
        assertTrue(type.getTypeName().endsWith(suffix),
                () -> "Expected type name to end with '" + suffix + "' but was " + type.getTypeName());
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
