package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SourceFile;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Expression-type anchors for recorded vs unrecorded lambdas.
///
/// Recorded lambdas publish `RESOLVED(Callable)`. Unrecorded ones stay fail-closed without
/// poisoning a sibling recorded lambda in the same module.
final class FrontendLambdaExpressionTypeTest {
    @Test
    void recordedLambdaPublishesResolvedCallableType() throws Exception {
        var analyzed = analyze("lambda_expr_type_happy.gd", """
                class_name LambdaExprTypeHappy
                extends RefCounted
                
                func ping():
                    var cb := func(x: int):
                        return x
                """);
        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var lambda = findNode(pingFunction.body(), LambdaExpression.class, _ -> true);
        var expressionType = analyzed.analysisData().expressionTypes().get(lambda);

        assertNotNull(analyzed.analysisData().lambdaPlans().get(lambda));
        assertNotNull(expressionType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, expressionType.status());
        assertEquals("Callable", expressionType.publishedType().getTypeName());
        assertTrue(analyzed.diagnostics().asList().stream().noneMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_expression_route")
        ));
    }

    @Test
    void unrecordedPropertyInitializerStaysUnsupportedWhileSiblingRecordedLambdaPublishes() throws Exception {
        var analyzed = analyze("lambda_expr_type_negative.gd", """
                class_name LambdaExprTypeNegative
                extends RefCounted
                
                var leftover = func():
                    return 1
                
                func ping():
                    var cb := func():
                        return 2
                """);
        var pingFunction = findFunction(analyzed.unit().ast(), "ping");
        var recorded = findNode(pingFunction.body(), LambdaExpression.class, _ -> true);
        var leftover = findNode(
                analyzed.unit().ast(),
                LambdaExpression.class,
                lambda -> lambda != recorded
        );

        assertNull(analyzed.analysisData().lambdaPlans().get(leftover));
        assertNotNull(analyzed.analysisData().lambdaPlans().get(recorded));
        var leftoverType = analyzed.analysisData().expressionTypes().get(leftover);
        var recordedType = analyzed.analysisData().expressionTypes().get(recorded);
        assertTrue(
                leftoverType == null || leftoverType.status() != FrontendExpressionTypeStatus.RESOLVED,
                () -> String.valueOf(leftoverType)
        );
        assertNotNull(recordedType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, recordedType.status());
        assertEquals("Callable", recordedType.publishedType().getTypeName());
        assertTrue(analyzed.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_expression_route")
        ));
    }

    private static @NotNull AnalyzedInput analyze(@NotNull String fileName, @NotNull String source) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit)),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new AnalyzedInput(unit, analysisData, diagnostics.snapshot());
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull SourceFile sourceFile, @NotNull String name) {
        return findNode(sourceFile, FunctionDeclaration.class, functionDeclaration -> functionDeclaration.name().equals(name));
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
        if (nodeType.isInstance(node) && predicate.test(nodeType.cast(node))) {
            matches.add(nodeType.cast(node));
        }
        for (var child : node.getChildren()) {
            collectMatchingNodes(child, nodeType, predicate, matches);
        }
    }

    private record AnalyzedInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
    }
}
