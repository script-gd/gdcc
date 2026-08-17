package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendInterfacePhase;
import gd.script.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendVariableAnalyzer;
import gd.script.gdcc.frontend.sema.resolver.FrontendFilteredValueHitReason;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolveRequest;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolver;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueStatus;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdccForRangeIterType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class FrontendInterfacePhaseTest {
    @Test
    void buildsSupportedBodyDeclarationIndexTypedBaselineAndSuiteEntryRoots() throws Exception {
        var phaseInput = phaseInput("interface_supported_blocks.gd", """
                class_name InterfaceSupportedBlocks
                extends Node
                
                var property_value = 1
                
                func ping(value: int, alias):
                    var first := second
                    var second: int = value
                    if value > 0:
                        var branch := second
                    while value > 1:
                        var loop_local := second
                        break
                    return second
                """);
        var sourceFile = phaseInput.unit().ast();
        var pingFunction = findStatement(
                sourceFile.statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var property = findStatement(
                sourceFile.statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("property_value")
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
        var ifStatement = findStatement(pingFunction.body().statements(), IfStatement.class, _ -> true);
        var branch = findStatement(
                ifStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("branch")
        );
        var whileStatement = findStatement(pingFunction.body().statements(), WhileStatement.class, _ -> true);

        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());

        var bodyDeclarations = surface.bodyDeclarationIndex().declarationsFor(pingFunction.body());
        assertEquals(List.of("first", "second"), bodyDeclarations.stream()
                .map(localDeclaration -> assertInstanceOf(
                        VariableDeclaration.class,
                        localDeclaration.declaration()
                ).name())
                .toList());
        assertEquals(0, bodyDeclarations.getFirst().sourceOrder());
        assertEquals(1, bodyDeclarations.getLast().sourceOrder());
        assertSame(first, bodyDeclarations.getFirst().declaration());
        assertSame(second, bodyDeclarations.getLast().declaration());
        assertEquals(1, surface.bodyDeclarationIndex().declarationsFor(ifStatement.body()).size());
        assertSame(branch, surface.bodyDeclarationIndex().declarationsFor(ifStatement.body()).getFirst().declaration());

        var baseline = surface.typedLexicalBaseline();
        assertEquals(GdIntType.INT, baseline.typeFor(pingFunction.parameters().getFirst()));
        assertEquals(GdVariantType.VARIANT, baseline.typeFor(pingFunction.parameters().getLast()));
        assertEquals(GdVariantType.VARIANT, baseline.typeFor(first));
        assertEquals(GdIntType.INT, baseline.typeFor(second));
        assertEquals(GdVariantType.VARIANT, baseline.typeFor(branch));

        var suiteEntryRoots = surface.suiteEntryRoots();
        assertTrue(suiteEntryRoots.containsCallableOwner(pingFunction));
        assertTrue(suiteEntryRoots.containsPropertyInitializer(property));
        assertTrue(suiteEntryRoots.containsSupportedBlock(pingFunction.body()));
        assertTrue(suiteEntryRoots.containsSupportedBlock(ifStatement.body()));
        assertTrue(suiteEntryRoots.containsSupportedBlock(whileStatement.body()));
        assertTrue(phaseInput.analysisData().symbolBindings().isEmpty());
        assertTrue(phaseInput.analysisData().expressionTypes().isEmpty());
        assertTrue(phaseInput.analysisData().resolvedMembers().isEmpty());
        assertTrue(phaseInput.analysisData().resolvedCalls().isEmpty());
        assertTrue(phaseInput.analysisData().slotTypes().isEmpty());
    }

    @Test
    void keepsFutureDeclarationVisibleToResolverThroughCompleteBodyIndex() throws Exception {
        var phaseInput = phaseInput("interface_future_declaration.gd", """
                class_name InterfaceFutureDeclaration
                extends Node
                
                func ping():
                    var first := second
                    var second := 1
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
        assertNotNull(first.value());
        var firstInitializer = first.value();
        var secondUseSite = findNode(firstInitializer, IdentifierExpression.class, identifier -> identifier.name().equals("second"));

        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
        var resolver = new FrontendVisibleValueResolver(
                phaseInput.analysisData(),
                surface.bodyDeclarationIndex()
        );
        var resolution = resolver.resolve(new FrontendVisibleValueResolveRequest(
                "second",
                secondUseSite,
                FrontendVisibleValueDomain.EXECUTABLE_BODY
        ));

        assertEquals(List.of("first", "second"), surface.bodyDeclarationIndex()
                .declarationsFor(pingFunction.body())
                .stream()
                .map(localDeclaration -> assertInstanceOf(
                        VariableDeclaration.class,
                        localDeclaration.declaration()
                ).name())
                .toList());
        assertEquals(FrontendVisibleValueStatus.NOT_FOUND, resolution.status());
        assertNull(resolution.visibleValue());
        assertEquals(
                FrontendFilteredValueHitReason.DECLARATION_AFTER_USE_SITE,
                primaryFilteredHitReason(resolution)
        );
    }

    @Test
    void publishesForInventoryWhileUnsupportedFeatureOwnedBodiesStayExcluded() throws Exception {
        var phaseInput = phaseInput("interface_pending_gates.gd", """
                class_name InterfacePendingGates
                extends Node
                
                func ping(items, choice):
                    var outer := choice
                    for item: int in items:
                        var from_for := item
                    match choice:
                        0:
                            var from_match := choice
                    var callback = func():
                        var from_lambda := choice
                        return choice
                    const answer = choice
                    return outer
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var matchStatement = findStatement(pingFunction.body().statements(), MatchStatement.class, _ -> true);
        var callback = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("callback")
        );
        var lambda = assertInstanceOf(LambdaExpression.class, callback.value());
        var answer = findStatement(
                pingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("answer")
        );
        var fromFor = findStatement(
                forStatement.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("from_for")
        );

        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());

        var forDeclarations = surface.bodyDeclarationIndex().declarationsFor(forStatement.body());
        assertEquals(2, forDeclarations.size());
        assertSame(forStatement, forDeclarations.getFirst().declaration());
        assertEquals(FrontendBodyLocalDeclaration.Kind.ITERATOR, forDeclarations.getFirst().kind());
        assertSame(fromFor, forDeclarations.getLast().declaration());
        assertEquals(FrontendBodyLocalDeclaration.Kind.ORDINARY_VAR, forDeclarations.getLast().kind());
        assertEquals(GdIntType.INT, surface.typedLexicalBaseline().typeFor(forStatement));
        assertEquals(GdVariantType.VARIANT, surface.typedLexicalBaseline().typeFor(fromFor));
        assertTrue(surface.suiteEntryRoots().containsSupportedBlock(forStatement.body()));
        assertFalse(surface.suiteEntryRoots().containsSupportedBlock(matchStatement.sections().getFirst().body()));
        // Lambda bodies inside supported executable bodies are now suite entries of their own.
        assertTrue(surface.suiteEntryRoots().containsSupportedBlock(lambda.body()));
        assertTrue(surface.suiteEntryRoots().containsCallableOwner(lambda));
        assertFalse(surface.bodyDeclarationIndex().containsBodyRoot(matchStatement.sections().getFirst().body()));
        var lambdaDeclarations = surface.bodyDeclarationIndex().declarationsFor(lambda.body());
        assertEquals(1, lambdaDeclarations.size());
        assertEquals(
                "from_lambda",
                assertInstanceOf(VariableDeclaration.class, lambdaDeclarations.getFirst().declaration()).name()
        );
        assertEquals(FrontendBodyLocalDeclaration.Kind.ORDINARY_VAR, lambdaDeclarations.getFirst().kind());
        assertFalse(surface.typedLexicalBaseline().containsDeclaration(answer));
        assertTrue(surface.typedLexicalBaseline().containsDeclaration(callback));
    }

    @Test
    void forStructuralInventoryIsInvariantAcrossExactVariantAndErrorIterables() throws Exception {
        var cases = List.of(
                new IterableCase("exact", "items: Array[int]", "items"),
                new IterableCase("variant", "items", "items"),
                new IterableCase("error", "", "missing_items")
        );

        for (var iterableCase : cases) {
            var phaseInput = phaseInput("interface_for_" + iterableCase.name() + ".gd", """
                    class_name InterfaceFor%s
                    extends Node

                    func ping(%s):
                        for item in %s:
                            var copy := item
                    """.formatted(
                    iterableCase.name(),
                    iterableCase.parameterText(),
                    iterableCase.iterableText()
            ));
            var pingFunction = findStatement(
                    phaseInput.unit().ast().statements(),
                    FunctionDeclaration.class,
                    functionDeclaration -> functionDeclaration.name().equals("ping")
            );
            var forStatement = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
            var copy = findStatement(
                    forStatement.body().statements(),
                    VariableDeclaration.class,
                    variableDeclaration -> variableDeclaration.name().equals("copy")
            );

            var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());
            var declarations = surface.bodyDeclarationIndex().declarationsFor(forStatement.body());

            assertEquals(2, declarations.size(), iterableCase.name());
            assertSame(forStatement, declarations.getFirst().declaration(), iterableCase.name());
            assertSame(copy, declarations.getLast().declaration(), iterableCase.name());
            assertEquals(GdVariantType.VARIANT, surface.typedLexicalBaseline().typeFor(forStatement));
            assertEquals(GdVariantType.VARIANT, surface.typedLexicalBaseline().typeFor(copy));
            assertTrue(surface.suiteEntryRoots().containsSupportedBlock(forStatement.body()), iterableCase.name());
        }
    }

    @Test
    void nestedForBodiesPublishInventoryAndLambdaBodiesWhileMatchOrConstSubtreesStayClosed() throws Exception {
        var phaseInput = phaseInput("interface_nested_for_boundaries.gd", """
                class_name InterfaceNestedForBoundaries
                extends Node

                func ping(values):
                    for outer in values:
                        for inner in outer:
                            var deep := inner
                        var callback = func():
                            return outer
                        match outer:
                            _:
                                var matched := outer
                        const blocked = outer
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );
        var outerFor = findStatement(pingFunction.body().statements(), ForStatement.class, _ -> true);
        var innerFor = findStatement(outerFor.body().statements(), ForStatement.class, _ -> true);
        var callback = findStatement(
                outerFor.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("callback")
        );
        var lambda = assertInstanceOf(LambdaExpression.class, callback.value());
        var matchStatement = findStatement(outerFor.body().statements(), MatchStatement.class, _ -> true);
        var blocked = findStatement(
                outerFor.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("blocked")
        );

        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());

        assertTrue(surface.suiteEntryRoots().containsSupportedBlock(outerFor.body()));
        assertTrue(surface.suiteEntryRoots().containsSupportedBlock(innerFor.body()));
        assertTrue(surface.bodyDeclarationIndex().containsBodyRoot(outerFor.body()));
        assertTrue(surface.bodyDeclarationIndex().containsBodyRoot(innerFor.body()));
        // Lambda nested inside a supported for body is recorded as a suite entry as well.
        assertTrue(surface.suiteEntryRoots().containsSupportedBlock(lambda.body()));
        assertTrue(surface.suiteEntryRoots().containsCallableOwner(lambda));
        assertFalse(surface.suiteEntryRoots().containsSupportedBlock(matchStatement.sections().getFirst().body()));
        assertTrue(surface.bodyDeclarationIndex().containsBodyRoot(lambda.body()));
        assertFalse(surface.bodyDeclarationIndex().containsBodyRoot(matchStatement.sections().getFirst().body()));
        assertFalse(surface.typedLexicalBaseline().containsDeclaration(blocked));
    }

    @Test
    void typedLexicalBaselineRejectsCompilerOnlySourceFacingTypes() throws Exception {
        var phaseInput = phaseInput("interface_compiler_only_guard.gd", """
                class_name InterfaceCompilerOnlyGuard
                extends Node
                
                func ping(value: int):
                    pass
                """);
        var pingFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("ping")
        );

        var error = assertThrows(IllegalArgumentException.class, () -> FrontendTypedLexicalBaseline.builder()
                .put(pingFunction.parameters().getFirst(), GdccForRangeIterType.FOR_RANGE_ITER)
                .build());

        assertTrue(error.getMessage().contains("compiler-only type leaked"));
    }

    private static @NotNull FrontendFilteredValueHitReason primaryFilteredHitReason(
            gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolution resolution
    ) {
        var primaryHit = resolution.primaryFilteredHit();
        if (primaryHit == null) {
            fail("Expected primary filtered hit");
        }
        return primaryHit.reason();
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
        var collector = new NodeCollector<>(nodeType, predicate);
        new ASTWalker(collector).walk(root);
        return collector.result();
    }

    private static final class NodeCollector<T extends Node> implements dev.superice.gdparser.frontend.ast.ASTNodeHandler {
        private final @NotNull Class<T> nodeType;
        private final @NotNull Predicate<T> predicate;
        private T result;

        private NodeCollector(@NotNull Class<T> nodeType, @NotNull Predicate<T> predicate) {
            this.nodeType = nodeType;
            this.predicate = predicate;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            if (result == null && nodeType.isInstance(node)) {
                var candidate = nodeType.cast(node);
                if (predicate.test(candidate)) {
                    result = candidate;
                }
            }
            return result == null
                    ? FrontendASTTraversalDirective.CONTINUE
                    : FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private @NotNull T result() {
            if (result == null) {
                throw new AssertionError("Node not found: " + nodeType.getSimpleName());
            }
            return result;
        }
    }

    private record PhaseInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull ClassRegistry registry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics
    ) {
    }

    private record IterableCase(
            @NotNull String name,
            @NotNull String parameterText,
            @NotNull String iterableText
    ) {
    }
}
