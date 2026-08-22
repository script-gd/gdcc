package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Shared-semantic and compile-gate anchors for `match`: visibility, inventory, bind refinement,
/// pattern-context dispatch, type-check shape checks, all-six-route compile readiness, and
/// lambda capture.
class FrontendMatchSemanticsTest {
    @Test
    void bindIsVisibleInGuardAndBodyButNotAcrossSectionsOrAfterMatch() {
        var analyzed = analyze("match_bind_visibility.gd", """
                class_name MatchBindVisibility
                extends Node
                
                func ping(value):
                    match value:
                        var bound when bound > 0:
                            print(bound)
                        var other:
                            print(other)
                    print(bound)
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var matchStatement = findNode(ping.body(), MatchStatement.class, _ -> true);
        var firstSection = matchStatement.sections().getFirst();
        var secondSection = matchStatement.sections().get(1);
        var firstScope = analyzed.analysisData().scopesByAst().get(firstSection);
        var secondScope = analyzed.analysisData().scopesByAst().get(secondSection);
        var pingBodyScope = analyzed.analysisData().scopesByAst().get(ping.body());

        var bound = firstScope.resolveValueHere("bound");
        assertNotNull(bound);
        assertEquals(ScopeValueKind.LOCAL, bound.kind());
        assertNull(firstScope.resolveValueHere("other"));
        assertNull(secondScope.resolveValueHere("bound"));
        assertNotNull(secondScope.resolveValueHere("other"));
        assertNull(pingBodyScope.resolveValueHere("bound"));

        var guardUse = findNode(firstSection.guard(), IdentifierExpression.class, id -> id.name().equals("bound"));
        var bodyUse = findNode(firstSection.body(), IdentifierExpression.class, id -> id.name().equals("bound"));
        assertEquals(FrontendBindingKind.LOCAL_VAR, analyzed.analysisData().symbolBindings().get(guardUse).kind());
        assertEquals(FrontendBindingKind.LOCAL_VAR, analyzed.analysisData().symbolBindings().get(bodyUse).kind());
        var afterUse = findNode(ping.body().statements().getLast(), IdentifierExpression.class, id -> id.name().equals("bound"));
        assertEquals(FrontendBindingKind.UNKNOWN, analyzed.analysisData().symbolBindings().get(afterUse).kind());
    }

    @Test
    void duplicateBindAndOuterLocalConflictReportVariableBinding() {
        var analyzed = analyze("match_bind_conflict.gd", """
                class_name MatchBindConflict
                extends Node
                
                func ping(value):
                    var outer := 1
                    match value:
                        [var a, var a]:
                            pass
                    match value:
                        var outer:
                            pass
                    match value:
                        var unique:
                            pass
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var firstMatch = findNode(ping.body(), MatchStatement.class, _ -> true);
        var nestedBinds = findNodes(firstMatch, PatternBindingExpression.class, bind -> bind.name().equals("a"));
        var bindingDiagnostics = diagnosticsByCategory(analyzed.diagnostics(), "sema.variable_binding");
        assertEquals(2, bindingDiagnostics.size(), bindingDiagnostics::toString);
        assertTrue(bindingDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.range().equals(FrontendRange.fromAstRange(nestedBinds.getLast().range()))
                        && diagnostic.message().contains("Duplicate pattern binding")
        ), bindingDiagnostics::toString);
        var shadowBind = findNode(ping.body(), PatternBindingExpression.class, bind -> bind.name().equals("outer"));
        assertTrue(bindingDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.range().equals(FrontendRange.fromAstRange(shadowBind.range()))
                        && diagnostic.message().contains("shadows")
        ), bindingDiagnostics::toString);
        var uniqueBind = findNode(ping.body(), PatternBindingExpression.class, bind -> bind.name().equals("unique"));
        var uniqueSlot = analyzed.analysisData().slotTypes().get(uniqueBind);
        assertEquals(GdVariantType.VARIANT, uniqueSlot);
    }

    @Test
    void topLevelBindRefinesToSubjectTypeWhileNestedBindStaysVariant() {
        var analyzed = analyze("match_bind_refine.gd", """
                class_name MatchBindRefine
                extends Node
                
                func ping(value: int, payload):
                    match value:
                        var exact:
                            pass
                    match payload:
                        var loose:
                            pass
                    match payload:
                        [var nested]:
                            pass
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var exact = findNode(ping.body(), PatternBindingExpression.class, bind -> bind.name().equals("exact"));
        var loose = findNode(ping.body(), PatternBindingExpression.class, bind -> bind.name().equals("loose"));
        var nested = findNode(ping.body(), PatternBindingExpression.class, bind -> bind.name().equals("nested"));
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(exact));
        assertEquals(GdVariantType.VARIANT, analyzed.analysisData().slotTypes().get(loose));
        assertEquals(GdVariantType.VARIANT, analyzed.analysisData().slotTypes().get(nested));
    }

    /// Same-name binds of distinct sections share one name-keyed function variable at lowering.
    /// When one match mixes a nested destructuring bind (always Variant) with a top-level bind
    /// (refinable) under the same name, the whole group keeps the Variant inventory baseline so
    /// published slot types, scope bindings, and frozen lambda capture entries all agree with the
    /// shared storage. Non-divergent names in the same match still refine.
    @Test
    void divergentSameNameBindGroupKeepsVariantBaselineWhileOtherBindsRefine() {
        var analyzed = analyze("match_bind_divergent_group.gd", """
                class_name MatchBindDivergentGroup
                extends Node
                
                func ping(value: Array):
                    match value:
                        [var bound]:
                            pass
                        var bound:
                            pass
                        var whole:
                            pass
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var matchStatement = findNode(ping.body(), MatchStatement.class, _ -> true);
        var nestedBound = findNode(
                matchStatement.sections().get(0),
                PatternBindingExpression.class,
                bind -> bind.name().equals("bound")
        );
        var topLevelBound = findNode(
                matchStatement.sections().get(1),
                PatternBindingExpression.class,
                bind -> bind.name().equals("bound")
        );
        var whole = findNode(
                matchStatement.sections().get(2),
                PatternBindingExpression.class,
                bind -> bind.name().equals("whole")
        );
        assertEquals(GdVariantType.VARIANT, analyzed.analysisData().slotTypes().get(nestedBound));
        assertEquals(GdVariantType.VARIANT, analyzed.analysisData().slotTypes().get(topLevelBound));
        assertInstanceOf(GdArrayType.class, analyzed.analysisData().slotTypes().get(whole));
    }

    @Test
    void bindWithMultiplePatternsReportsTypeCheckWhileLaterSectionStillPublishesFacts() {
        var analyzed = analyze("match_bind_multi_pattern.gd", """
                class_name MatchBindMultiPattern
                extends Node
                
                func ping(value: int):
                    match value:
                        var bound, 1:
                            pass
                        2:
                            var later := value
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var matchStatement = findNode(ping.body(), MatchStatement.class, _ -> true);
        var firstSection = matchStatement.sections().getFirst();
        var later = findNode(ping.body(), VariableDeclaration.class, decl -> decl.name().equals("later"));
        var typeCheck = diagnosticsByCategory(analyzed.diagnostics(), "sema.type_check");
        assertEquals(1, typeCheck.size(), typeCheck::toString);
        assertEquals(FrontendRange.fromAstRange(firstSection.range()), typeCheck.getFirst().range());
        assertTrue(typeCheck.getFirst().message().contains("Pattern binding cannot be used with multiple patterns"));
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(later));
        assertEquals(FrontendExpressionTypeStatus.RESOLVED,
                analyzed.analysisData().expressionTypes().get(later.value()).status());
    }

    @Test
    void nonConstantDictionaryPatternKeyReportsGodotMessageWhileLaterSectionPublishesFacts() {
        var analyzed = analyze("match_dict_key.gd", """
                class_name MatchDictKey
                extends Node
                
                func ping(payload, key):
                    match payload:
                        {key: _}:
                            pass
                        {"ok": _}:
                            var later := 1
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var matchStatement = findNode(ping.body(), MatchStatement.class, _ -> true);
        var key = findNode(matchStatement.sections().getFirst(), IdentifierExpression.class, id -> id.name().equals("key"));
        var later = findNode(ping.body(), VariableDeclaration.class, decl -> decl.name().equals("later"));
        var typeCheck = diagnosticsByCategory(analyzed.diagnostics(), "sema.type_check");
        assertEquals(1, typeCheck.size(), typeCheck::toString);
        assertEquals(FrontendRange.fromAstRange(key.range()), typeCheck.getFirst().range());
        assertEquals("Expression in dictionary pattern key must be a constant.", typeCheck.getFirst().message());
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(later));
    }

    @Test
    void arbitraryExpressionPatternsAreLegalAndPublishOrdinaryFacts() {
        var analyzed = analyze("match_expression_patterns.gd", """
                class_name MatchExpressionPatterns
                extends Node
                
                func helper() -> int:
                    return 1
                
                func ping(value, other: int):
                    match value:
                        other:
                            pass
                        helper():
                            pass
                        other + 1:
                            pass
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var matchStatement = findNode(ping.body(), MatchStatement.class, _ -> true);
        var otherPattern = assertInstanceOf(IdentifierExpression.class, matchStatement.sections().getFirst().patterns().getFirst());
        var callPattern = assertInstanceOf(CallExpression.class, matchStatement.sections().get(1).patterns().getFirst());
        var binaryPattern = assertInstanceOf(BinaryExpression.class, matchStatement.sections().get(2).patterns().getFirst());
        assertTrue(diagnosticsByCategory(analyzed.diagnostics(), "sema.type_check").isEmpty());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED,
                analyzed.analysisData().expressionTypes().get(otherPattern).status());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED,
                analyzed.analysisData().expressionTypes().get(callPattern).status());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED,
                analyzed.analysisData().expressionTypes().get(binaryPattern).status());
        assertNotNull(analyzed.analysisData().symbolBindings().get(otherPattern));
        assertNotNull(analyzed.analysisData().resolvedCalls().get(callPattern));
    }

    @Test
    void patternContextDispatchDoesNotPublishContainerOrWildcardFacts() {
        var analyzed = analyze("match_pattern_dispatch.gd", """
                class_name MatchPatternDispatch
                extends Node
                
                func ping(value):
                    match value:
                        [1, ..]:
                            pass
                        var bound:
                            pass
                        _:
                            pass
                    var ordinary := [1]
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var matchStatement = findNode(ping.body(), MatchStatement.class, _ -> true);
        var arrayPattern = assertInstanceOf(ArrayExpression.class, matchStatement.sections().getFirst().patterns().getFirst());
        var bindPattern = assertInstanceOf(
                PatternBindingExpression.class,
                matchStatement.sections().get(1).patterns().getFirst()
        );
        var wildcard = assertInstanceOf(IdentifierExpression.class, matchStatement.sections().get(2).patterns().getFirst());
        var ordinary = findNode(ping.body(), VariableDeclaration.class, decl -> decl.name().equals("ordinary"));
        assertTrue(arrayPattern.openEnded());
        assertNull(analyzed.analysisData().containerLiteralPlans().get(arrayPattern));
        assertNull(analyzed.analysisData().expressionTypes().get(arrayPattern));
        assertNull(analyzed.analysisData().expressionTypes().get(bindPattern));
        assertNull(analyzed.analysisData().symbolBindings().get(wildcard));
        assertNotNull(analyzed.analysisData().containerLiteralPlans().get(ordinary.value()));
    }

    @Test
    void analyzeForCompileReleasesArrayPatternMatch() {
        var compiled = analyzeForCompile("match_compile_gate.gd", """
                class_name MatchCompileGate
                extends Node
                
                func ping(value):
                    match value:
                        [1]:
                            pass
                        {"k": var v, ..}:
                            pass
                """);
        var ping = findFunction(compiled.unit().ast(), "ping");
        var matchStatement = findNode(ping.body(), MatchStatement.class, _ -> true);
        // ARRAY / DICTIONARY routes are compile-ready: no route-not-ready blocker.
        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics().asList()::toString);
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.unsupported_binding_subtree").isEmpty());
        assertNotNull(compiled.analysisData().matchPlans().get(matchStatement));
    }

    @Test
    void analyzeForCompileReleasesFirstFourMatchRoutes() {
        var compiled = analyzeForCompile("match_compile_gate_ready.gd", """
                class_name MatchCompileGateReady
                extends Node
                
                func ping(value: int, other: int):
                    match value:
                        1:
                            pass
                        other:
                            pass
                        var bound:
                            pass
                        _:
                            pass
                """);
        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics().asList()::toString);
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void matchSectionLambdaRecordsAndCapturesBind() {
        var analyzed = analyze("match_lambda_capture.gd", """
                class_name MatchLambdaCapture
                extends Node
                
                func ping(value: int):
                    match value:
                        var bound:
                            var cb := func():
                                return bound
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var lambda = findNode(ping.body(), LambdaExpression.class, _ -> true);
        var plan = analyzed.analysisData().lambdaPlans().get(lambda);
        assertNotNull(plan);
        assertEquals(1, plan.captures().size());
        assertEquals("bound", plan.captures().getFirst().name());
        assertEquals(GdIntType.INT, plan.captures().getFirst().type());
        assertEquals(ScopeValueKind.LOCAL, plan.captures().getFirst().sourceKind());
    }

    @Test
    void lambdaInsideMatchParsesNormally() {
        var analyzed = analyze("match_inside_lambda.gd", """
                class_name MatchInsideLambda
                extends Node
                
                func ping():
                    var cb := func(choice):
                        match choice:
                            1:
                                pass
                """);
        var ping = findFunction(analyzed.unit().ast(), "ping");
        var lambda = findNode(ping.body(), LambdaExpression.class, _ -> true);
        var matchStatement = findNode(lambda.body(), MatchStatement.class, _ -> true);
        assertNotNull(analyzed.analysisData().lambdaPlans().get(lambda));
        assertNotNull(analyzed.analysisData().matchPlans().get(matchStatement));
        assertTrue(diagnosticsByCategory(analyzed.diagnostics(), "sema.unsupported_binding_subtree").isEmpty());
    }

    private static @NotNull AnalyzedInput analyze(@NotNull String fileName, @NotNull String source) {
        return analyze(fileName, source, false);
    }

    private static @NotNull AnalyzedInput analyzeForCompile(@NotNull String fileName, @NotNull String source) {
        return analyze(fileName, source, true);
    }

    private static @NotNull AnalyzedInput analyze(
            @NotNull String fileName,
            @NotNull String source,
            boolean compileMode
    ) {
        try {
            var parserService = new GdScriptParserService();
            var diagnostics = new DiagnosticManager();
            var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
            assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
            var analyzer = new FrontendSemanticAnalyzer();
            var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
            var analysisData = compileMode
                    ? analyzer.analyzeForCompile(new FrontendModule("test_module", List.of(unit)), registry, diagnostics)
                    : analyzer.analyze(new FrontendModule("test_module", List.of(unit)), registry, diagnostics);
            return new AnalyzedInput(unit, analysisData, diagnostics.snapshot());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String category
    ) {
        return diagnostics.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull SourceFile sourceFile, @NotNull String name) {
        return findNode(sourceFile, FunctionDeclaration.class, function -> function.name().equals(name));
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = findNodes(root, nodeType, predicate);
        if (matches.isEmpty()) {
            throw new AssertionError("Node not found: " + nodeType.getSimpleName());
        }
        return matches.getFirst();
    }

    private static <T extends Node> @NotNull List<T> findNodes(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return matches;
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
