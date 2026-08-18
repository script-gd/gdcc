package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendVariableAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Inventory anchors for recorded lambda parameter / local / capture binding.
///
/// The existing `FrontendVariableAnalyzerTest` already covers the inventory walk. This class
/// keeps a smaller happy/negative pair so the recorded-vs-unrecorded surface stays explicit.
final class FrontendLambdaInventoryTest {
    @Test
    void analyzeBindsSupportedLambdaInventoryAndLeavesPlanUnpublished() throws Exception {
        var phaseInput = publishedPhaseInput("lambda_inventory_happy.gd", """
                class_name LambdaInventoryHappy
                extends Node
                
                func ping(seed: int):
                    var builder := func(item: int):
                        var lambda_local := item
                        return lambda_local + seed
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var builderDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("builder")
        );
        var builderLambda = assertInstanceOf(LambdaExpression.class, builderDeclaration.value());
        var lambdaScope = assertInstanceOf(CallableScope.class, phaseInput.analysisData().scopesByAst().get(builderLambda));
        var lambdaBodyScope = assertInstanceOf(BlockScope.class, phaseInput.analysisData().scopesByAst().get(builderLambda.body()));
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        assertEquals(0, newDiagnostics(diagnosticsBefore, phaseInput.diagnostics().snapshot()).size());
        assertEquals(ScopeValueKind.PARAMETER, requireHere(lambdaScope, "item").kind());
        assertEquals(GdIntType.INT, requireHere(lambdaScope, "item").type());
        assertEquals(ScopeValueKind.LOCAL, requireHere(lambdaBodyScope, "lambda_local").kind());
        var seedCapture = requireHere(lambdaScope, "seed");
        assertEquals(ScopeValueKind.CAPTURE, seedCapture.kind());
        assertEquals(GdVariantType.VARIANT, seedCapture.type());
        assertSame(pingFunction.parameters().getFirst(), seedCapture.declaration());
        assertTrue(phaseInput.analysisData().lambdaPlans().isEmpty());
    }

    @Test
    void analyzeSkipsPropertyInitializerLambdaAndStillBindsSiblingFunctionInventory() throws Exception {
        var phaseInput = publishedPhaseInput("lambda_inventory_negative.gd", """
                class_name LambdaInventoryNegative
                extends Node
                
                var cb = func(item):
                    return item
                
                func ping(seed: int):
                    var builder := func():
                        return seed
                    return seed
                """);
        var propertyDeclaration = findStatement(
                phaseInput.unit().ast().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("cb")
        );
        var propertyLambda = assertInstanceOf(LambdaExpression.class, propertyDeclaration.value());
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var builderDeclaration = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("builder")
        );
        var builderLambda = assertInstanceOf(LambdaExpression.class, builderDeclaration.value());
        var diagnosticsBefore = phaseInput.diagnostics().snapshot();

        new FrontendVariableAnalyzer().analyze(phaseInput.analysisData(), phaseInput.diagnostics());

        assertEquals(0, newDiagnostics(diagnosticsBefore, phaseInput.diagnostics().snapshot()).size());
        var propertyScope = assertInstanceOf(
                CallableScope.class,
                phaseInput.analysisData().scopesByAst().get(propertyLambda)
        );
        assertNull(propertyScope.resolveValueHere("item"));
        var builderScope = assertInstanceOf(
                CallableScope.class,
                phaseInput.analysisData().scopesByAst().get(builderLambda)
        );
        assertEquals(ScopeValueKind.CAPTURE, requireHere(builderScope, "seed").kind());
        assertTrue(phaseInput.analysisData().lambdaPlans().isEmpty());
    }

    private static PhaseInput publishedPhaseInput(@NotNull String fileName, @NotNull String source) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
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
        return new PhaseInput(unit, analysisData, diagnostics);
    }

    private static @NotNull gd.script.gdcc.scope.ScopeValue requireHere(
            @NotNull gd.script.gdcc.scope.Scope scope,
            @NotNull String name
    ) {
        var value = scope.resolveValueHere(name);
        assertNotNull(value, name);
        return value;
    }

    private static @NotNull List<FrontendDiagnostic> newDiagnostics(
            @NotNull DiagnosticSnapshot before,
            @NotNull DiagnosticSnapshot after
    ) {
        return after.asList().subList(before.size(), after.size());
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

    private record PhaseInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics
    ) {
    }
}
