package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendParseRecovery;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrontendSuiteResolverRecoveryTest {
    @Test
    void skippedBareLambdaUsesActiveParserErrorOrEmitsOneFallbackWithoutOpeningItsBody() throws Exception {
        var parserDiagnostics = new DiagnosticManager();
        var parsed = parse("""
                extends RefCounted
                func outer():
                    func(): pass
                    var sibling: int = 1
                """, parserDiagnostics);
        var function = function(parsed);
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, statement.expression());
        var sibling = assertInstanceOf(VariableDeclaration.class, function.body().statements().getLast());
        var recovery = parsed.parseRecoveries().getFirst();
        assertSame(statement, recovery.owner());
        assertEquals("parse.lambda.bare_statement", recovery.code());
        var withoutRecovery = new FrontendSourceUnit(parsed.path(), parsed.source(), parsed.ast());

        for (var testCase : List.of(
                new BareLambdaCase(parsed, parserDiagnostics, recovery.code()),
                new BareLambdaCase(parsed, new DiagnosticManager(), "sema.unsupported_binding_subtree"),
                new BareLambdaCase(withoutRecovery, new DiagnosticManager(), "sema.unsupported_binding_subtree")
        )) {
            var analysisData = bootstrapWithSkippedLambda(testCase.unit(), statement);
            resolveSuites(testCase.unit(), analysisData, testCase.diagnostics());

            var diagnostics = testCase.diagnostics().snapshot().asList();
            assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
            var diagnostic = diagnostics.getFirst();
            assertEquals(testCase.expectedCategory(), diagnostic.category());
            assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity());
            if (testCase.unit() == parsed) {
                assertEquals(diagnostic, analysisData.claimedParseRecoveries().get(recovery));
            }
            if (!testCase.expectedCategory().equals(recovery.code())) {
                assertEquals(FrontendRange.fromAstRange(lambda.range()), diagnostic.range());
            }
            assertFalse(analysisData.scopesByAst().containsKey(lambda));
            assertFalse(analysisData.lambdaPlans().containsKey(lambda));
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, analysisData.expressionTypes().get(sibling.value()).status());
        }
    }

    @Test
    void skippedBareLambdaClaimsEarliestErrorAcrossBothRangesWithoutConsumingUnrelatedDiagnostics() throws Exception {
        var unit = parse("""
                extends RefCounted
                func outer():
                    (func(): pass)
                """, new DiagnosticManager());
        var statement = assertInstanceOf(ExpressionStatement.class, function(unit).body().statements().getFirst());
        var lambda = assertInstanceOf(LambdaExpression.class, statement.expression());
        var recovery = unit.parseRecoveries().getFirst();
        var sourcePath = FrontendDiagnostic.sourcePathText(unit.path());
        var lambdaRange = FrontendRange.fromAstRange(lambda.range());
        var statementRange = FrontendRange.fromAstRange(statement.range());
        assertNotEquals(lambdaRange, statementRange);

        for (var statementFirst : List.of(false, true)) {
            var manager = new DiagnosticManager();
            var otherFile = FrontendDiagnostic.error("sema.test", "error in another file", "other.gd", lambdaRange);
            var warning = FrontendDiagnostic.warning("sema.test", "not blocking", sourcePath, statementRange);
            var first = FrontendDiagnostic.error("sema.test", "earliest blocking diagnostic", sourcePath,
                    statementFirst ? statementRange : lambdaRange);
            var second = FrontendDiagnostic.error("sema.test", "later blocking diagnostic", sourcePath,
                    statementFirst ? lambdaRange : statementRange);
            var published = List.of(otherFile, warning, first, second);
            manager.reportAll(published);

            var analysisData = bootstrapWithSkippedLambda(unit, statement);
            resolveSuites(unit, analysisData, manager);
            assertEquals(first, analysisData.claimedParseRecoveries().get(recovery));
            assertEquals(published, manager.snapshot().asList());
        }
    }

    @Test
    void skippedBareLambdaSeesExactErrorsAppendedToOneManagerButIgnoresClaimsNotPresentInAnother() throws Exception {
        var parsed = parse("""
                extends RefCounted
                func outer():
                    (func(): pass)
                """, new DiagnosticManager());
        var function = function(parsed);
        var statement = assertInstanceOf(ExpressionStatement.class, function.body().statements().getFirst());
        var recovery = parsed.parseRecoveries().getFirst();
        var secondRecovery = new FrontendParseRecovery(recovery.code(), recovery.severity(),
                "second recovery message", recovery.range(), recovery.owner(), recovery.facet());
        var unit = new FrontendSourceUnit(
                parsed.path(), parsed.source(), parsed.ast(), false, List.of(recovery, secondRecovery)
        );
        var sourcePath = FrontendDiagnostic.sourcePathText(unit.path());
        var independent = FrontendDiagnostic.error("sema.test", "independent error", sourcePath,
                FrontendRange.fromAstRange(function.range()));
        var firstManager = new DiagnosticManager();
        firstManager.report(independent);
        var initial = bootstrapWithSkippedLambda(unit, statement);
        resolveSuites(unit, initial, firstManager);
        var fallback = initial.claimedParseRecoveries().get(recovery);
        assertNotNull(fallback);
        assertEquals("sema.unsupported_binding_subtree", fallback.category());
        assertEquals(fallback, initial.claimedParseRecoveries().get(secondRecovery));
        assertEquals(List.of(independent, fallback), firstManager.snapshot().asList());

        var original = FrontendDiagnostic.error(recovery.code(), recovery.message(), sourcePath, recovery.range());
        var secondOriginal = FrontendDiagnostic.error(
                secondRecovery.code(), secondRecovery.message(), sourcePath, secondRecovery.range()
        );
        firstManager.reportAll(List.of(original, secondOriginal));
        var appended = bootstrapWithSkippedLambda(unit, statement);
        appended.claimParseRecovery(recovery, fallback);
        appended.claimParseRecovery(secondRecovery, fallback);
        resolveSuites(unit, appended, firstManager);
        assertEquals(original, appended.claimedParseRecoveries().get(recovery));
        assertEquals(secondOriginal, appended.claimedParseRecoveries().get(secondRecovery));
        assertEquals(List.of(independent, fallback, original, secondOriginal), firstManager.snapshot().asList());

        var otherManager = new DiagnosticManager();
        otherManager.report(independent);
        var fresh = bootstrapWithSkippedLambda(unit, statement);
        fresh.claimParseRecovery(recovery, original);
        fresh.claimParseRecovery(secondRecovery, secondOriginal);
        resolveSuites(unit, fresh, otherManager);
        assertEquals(fallback, fresh.claimedParseRecoveries().get(recovery));
        assertEquals(fallback, fresh.claimedParseRecoveries().get(secondRecovery));
        assertEquals(List.of(independent, fallback), otherManager.snapshot().asList());
    }

    @Test
    void lambdaReturnRecoveryKeepsBodyFactsAndHealthyReturnSlot() throws Exception {
        for (var returnType : List.of("", "Array[]", "int")) {
            var parserDiagnostics = new DiagnosticManager();
            var unit = parse("""
                    extends RefCounted
                    func outer():
                        var callback = func() -> %s:
                            return 1
                        var sibling: int = 1
                    """.formatted(returnType), parserDiagnostics);
            var function = function(unit);
            var callback = assertInstanceOf(VariableDeclaration.class, function.body().statements().getFirst());
            var lambda = assertInstanceOf(LambdaExpression.class, callback.value());
            var sibling = assertInstanceOf(VariableDeclaration.class, function.body().statements().getLast());
            var valid = returnType.equals("int");
            assertEquals(valid, parserDiagnostics.isEmpty());

            for (var manager : List.of(parserDiagnostics, new DiagnosticManager())) {
                var analysisData = bootstrap(unit);
                resolveSuites(unit, analysisData, manager);

                var plan = analysisData.lambdaPlans().get(lambda);
                assertNotNull(plan);
                assertEquals(valid ? GdIntType.INT : GdVariantType.VARIANT, plan.returnType());
                assertTrue(analysisData.scopesByAst().containsKey(lambda.body()));
                assertEquals(GdIntType.INT, analysisData.expressionTypes().get(sibling.value()).publishedType());
                var errors = manager.snapshot().asList().stream()
                        .filter(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
                        .toList();
                if (valid) {
                    assertTrue(errors.isEmpty());
                } else {
                    assertEquals(1, errors.size(), () -> "Unexpected errors: " + errors);
                    var recovery = unit.parseRecoveries().getFirst();
                    assertEquals(manager == parserDiagnostics ? recovery.code() : "sema.type_resolution",
                            errors.getFirst().category());
                    assertEquals(errors.getFirst(), analysisData.claimedParseRecoveries().get(recovery));
                }
            }
        }
    }

    @Test
    void reusedSemanticAnalyzerRebuildsLambdaScopesAndRestartsNamingForBothPublicEntries() throws Exception {
        for (var returnType : List.of("int", "")) {
            var analyzer = new FrontendSemanticAnalyzer();
            for (var forCompile : List.of(false, true)) {
                var parserDiagnostics = new DiagnosticManager();
                var unit = parse("""
                        class_name ReusedLambdaAnalysis
                        extends RefCounted
                        func _init():
                            var callback = func() -> %s:
                                return 1
                        """.formatted(returnType), parserDiagnostics);
                var constructor = unit.ast().statements().stream()
                        .filter(ConstructorDeclaration.class::isInstance)
                        .map(ConstructorDeclaration.class::cast)
                        .findFirst()
                        .orElseThrow();
                var declaration = assertInstanceOf(VariableDeclaration.class, constructor.body().statements().getFirst());
                var lambda = assertInstanceOf(LambdaExpression.class, declaration.value());
                var healthy = returnType.equals("int");
                assertEquals(healthy, parserDiagnostics.isEmpty());

                var manager = new DiagnosticManager();
                var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
                var module = new FrontendModule("reuse_lambda_analysis", List.of(unit));
                var data = forCompile ? analyzer.analyzeForCompile(module, registry, manager)
                        : analyzer.analyze(module, registry, manager);
                assertEquals(1, data.lambdaPlans().size());
                var plan = data.lambdaPlans().get(lambda);
                assertNotNull(plan);
                assertSame(constructor, plan.enclosingCallable());
                assertEquals("_lambda_0", plan.syntheticName());
                assertEquals(0, plan.identityOrdinal());
                assertEquals(healthy ? GdIntType.INT : GdVariantType.VARIANT, plan.returnType());

                var diagnostics = manager.snapshot().asList();
                if (healthy) {
                    assertTrue(diagnostics.isEmpty(), () -> "Unexpected diagnostics: " + diagnostics);
                } else {
                    assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
                    var error = diagnostics.getFirst();
                    assertEquals(FrontendDiagnosticSeverity.ERROR, error.severity());
                    assertEquals("sema.type_resolution", error.category());
                    assertEquals(error, data.claimedParseRecoveries().get(unit.parseRecoveries().getFirst()));
                }
            }
        }
    }

    private static FrontendSourceUnit parse(String source, DiagnosticManager diagnostics) {
        return new GdScriptParserService().parseUnit(Path.of("tmp", "suite_recovery.gd"), source, diagnostics);
    }

    private static FunctionDeclaration function(FrontendSourceUnit unit) {
        return unit.ast().statements().stream()
                .filter(FunctionDeclaration.class::isInstance)
                .map(FunctionDeclaration.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static FrontendAnalysisData bootstrap(FrontendSourceUnit unit) {
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.updateSourceUnits(List.of(unit));
        for (var recovery : unit.parseRecoveries()) {
            var owner = recovery.owner();
            if (owner != null) {
                var previous = analysisData.parseRecoveriesByOwner().get(owner);
                var bucket = previous == null ? new ArrayList<FrontendParseRecovery>() : new ArrayList<>(previous);
                bucket.add(recovery);
                analysisData.parseRecoveriesByOwner().put(owner, List.copyOf(bucket));
            }
        }
        return analysisData;
    }

    private static FrontendAnalysisData bootstrapWithSkippedLambda(
            FrontendSourceUnit unit,
            ExpressionStatement statement
    ) {
        var analysisData = bootstrap(unit);
        // Exercise the suite's consumer independently from the earlier phase that marks roots.
        analysisData.skippedSubtreeRoots().put(statement, true);
        analysisData.skippedSubtreeRoots().put(statement.expression(), true);
        return analysisData;
    }

    private static void resolveSuites(
            FrontendSourceUnit unit,
            FrontendAnalysisData analysisData,
            DiagnosticManager diagnosticManager
    ) throws Exception {
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var module = new FrontendModule("suite_recovery", List.of(unit));
        analysisData.updateModuleSkeleton(new FrontendClassSkeletonBuilder()
                .build(module, registry, diagnosticManager, analysisData));
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendScopeAnalyzer().analyze(registry, analysisData, diagnosticManager);
        new FrontendVariableAnalyzer().analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        FrontendSuiteResolverStageTestSupport.resolveAllOwners(registry, analysisData, diagnosticManager);
    }

    private record BareLambdaCase(
            FrontendSourceUnit unit,
            DiagnosticManager diagnostics,
            String expectedCategory
    ) {
    }
}
