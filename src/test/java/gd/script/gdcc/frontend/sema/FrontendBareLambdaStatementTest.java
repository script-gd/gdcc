package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendInterfacePhase;
import gd.script.gdcc.frontend.sema.analyzer.FrontendScopeAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSuiteResolver;
import gd.script.gdcc.frontend.sema.analyzer.FrontendVariableAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression coverage for issue #65: a bare lambda written directly as a function-body statement
/// (`func(): ...`) is parsed as a statement-position `FunctionDeclaration` named `<anonymous>`.
/// Godot parses the same shape as a lambda expression statement and rejects it with a source
/// error ("Standalone lambdas cannot be accessed"), so the frontend must close the shape with one
/// `sema.unsupported_binding_subtree` error anchored at the declaration, skip the subtree, and
/// keep sibling subtrees resolving — never abort the pipeline with an exception.
class FrontendBareLambdaStatementTest {
    @Test
    void bareLambdaStatementEmitsBoundaryDiagnosticAndSkipsSubtree() throws Exception {
        var analyzed = analyze("bare_lambda_statement.gd", """
                class_name BareLambdaStatement
                extends Node

                func outer():
                    func():
                        var state = 1
                """);
        var bareLambda = findBareLambdaStatement(analyzed.unit().ast());

        var diagnostics = analyzed.diagnostics().asList();
        assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
        var diagnostic = diagnostics.getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity());
        assertEquals("sema.unsupported_binding_subtree", diagnostic.category());
        assertTrue(diagnostic.message().contains("Standalone lambda statement"), diagnostic.message());
        // The boundary error anchors at the skipped subtree root (the whole declaration).
        assertEquals(FrontendRange.fromAstRange(bareLambda.range()), diagnostic.range());
        assertTrue(analyzed.analysisData().lambdaPlans().isEmpty());
    }

    @Test
    void bareLambdaStatementKeepsSiblingsResolving() throws Exception {
        var analyzed = analyze("bare_lambda_siblings.gd", """
                class_name BareLambdaSiblings
                extends Node

                func outer():
                    var before := 1
                    func():
                        pass
                    var after := 2

                func sibling():
                    var value := 3
                """);
        var outerFunction = findStatement(
                analyzed.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("outer")
        );
        var siblingFunction = findStatement(
                analyzed.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("sibling")
        );
        var before = findStatement(
                outerFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("before")
        );
        var after = findStatement(
                outerFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("after")
        );
        var value = findStatement(
                siblingFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("value")
        );

        var diagnostics = analyzed.diagnostics().asList();
        assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
        assertEquals("sema.unsupported_binding_subtree", diagnostics.getFirst().category());
        // Sibling statements in the same suite and other callables in the same class still
        // resolve through the ordinary publication path.
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(before));
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(after));
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(value));
    }

    @Test
    void namedNestedFunctionDeclarationGetsSameBoundary() throws Exception {
        var analyzed = analyze("bare_named_lambda_statement.gd", """
                class_name BareNamedLambdaStatement
                extends Node

                func outer():
                    func inner():
                        pass
                """);
        var outerFunction = findStatement(
                analyzed.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("outer")
        );
        var nested = findStatement(
                outerFunction.body().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("inner")
        );

        var diagnostics = analyzed.diagnostics().asList();
        assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
        var diagnostic = diagnostics.getFirst();
        assertEquals("sema.unsupported_binding_subtree", diagnostic.category());
        assertTrue(diagnostic.message().contains("Standalone lambda statement"), diagnostic.message());
        assertEquals(FrontendRange.fromAstRange(nested.range()), diagnostic.range());
    }

    @Test
    void bareLambdaInsideNestedBlockIsRejectedAtThatRoot() throws Exception {
        var analyzed = analyze("bare_lambda_nested_block.gd", """
                class_name BareLambdaNestedBlock
                extends Node

                func outer(flag: bool):
                    if flag:
                        func():
                            pass
                    var after := 1
                """);
        var outerFunction = findStatement(
                analyzed.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("outer")
        );
        var ifStatement = findStatement(outerFunction.body().statements(), IfStatement.class, _ -> true);
        var bareLambda = findStatement(
                ifStatement.body().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("<anonymous>")
        );
        var after = findStatement(
                outerFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("after")
        );

        var diagnostics = analyzed.diagnostics().asList();
        assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
        var diagnostic = diagnostics.getFirst();
        assertEquals("sema.unsupported_binding_subtree", diagnostic.category());
        assertEquals(FrontendRange.fromAstRange(bareLambda.range()), diagnostic.range());
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(after));
    }

    @Test
    void nestedLambdaInsideBareLambdaStaysUnrecorded() throws Exception {
        var analyzed = analyze("bare_lambda_nested_lambda.gd", """
                class_name BareLambdaNestedLambda
                extends Node

                func outer():
                    func():
                        var f = func():
                            pass
                """);

        var diagnostics = analyzed.diagnostics().asList();
        // The whole bare-lambda subtree is skipped at its root: the inner expression-position
        // lambda never becomes a recorded suite entry, so no plan is published and no secondary
        // unrecorded-lambda diagnostics fire inside the rejected subtree.
        assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
        assertEquals("sema.unsupported_binding_subtree", diagnostics.getFirst().category());
        assertTrue(analyzed.analysisData().lambdaPlans().isEmpty());
    }

    @Test
    void interfacePhaseDoesNotRecordStatementPositionFunctions() throws Exception {
        var phaseInput = phaseInput("bare_lambda_interface_surface.gd", """
                class_name BareLambdaInterfaceSurface
                extends Node

                func outer():
                    func():
                        var state = 1
                """);
        var outerFunction = findStatement(
                phaseInput.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("outer")
        );
        var bareLambda = findBareLambdaStatement(phaseInput.unit().ast());

        var surface = new FrontendInterfacePhase().analyze(phaseInput.registry(), phaseInput.analysisData());

        assertTrue(surface.suiteEntryRoots().containsCallableOwner(outerFunction));
        assertTrue(surface.suiteEntryRoots().containsSupportedBlock(outerFunction.body()));
        assertFalse(surface.suiteEntryRoots().containsCallableOwner(bareLambda));
        assertFalse(surface.suiteEntryRoots().containsSupportedBlock(bareLambda.body()));
        assertFalse(surface.bodyDeclarationIndex().containsBodyRoot(bareLambda.body()));
    }

    @Test
    void compileEntryDoesNotReWrapRejectedBareLambda() throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", "bare_lambda_compile_entry.gd"), """
                class_name BareLambdaCompileEntry
                extends Node

                func outer():
                    func():
                        var state = 1
                """, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());

        new FrontendSemanticAnalyzer().analyzeForCompile(
                new FrontendModule("test_module", List.of(unit)),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );

        var snapshot = diagnostics.snapshot();
        var allDiagnostics = snapshot.asList();
        assertEquals(1, allDiagnostics.size(), () -> "Unexpected diagnostics: " + allDiagnostics);
        assertEquals("sema.unsupported_binding_subtree", allDiagnostics.getFirst().category());
        // The rejected subtree never enters the compile surface, so the compile-only gate must
        // not wrap it into a second sema.compile_check error.
        assertFalse(allDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.category().equals("sema.compile_check")
        ));
    }

    @Test
    void nestedInitStatementGetsSameBoundary() throws Exception {
        var analyzed = analyze("bare_init_statement.gd", """
                class_name BareInitStatement
                extends Node

                func outer():
                    func _init():
                        pass
                    var after := 1
                """);
        var outerFunction = findStatement(
                analyzed.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("outer")
        );
        var after = findStatement(
                outerFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("after")
        );

        // A statement-position `_init` is the same standalone-lambda shape (Godot rejects it
        // identically), regardless of whether the parser maps it to a FunctionDeclaration or a
        // ConstructorDeclaration.
        var diagnostics = analyzed.diagnostics().asList();
        assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
        var diagnostic = diagnostics.getFirst();
        assertEquals("sema.unsupported_binding_subtree", diagnostic.category());
        assertTrue(diagnostic.message().contains("Standalone lambda statement"), diagnostic.message());
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(after));
    }

    @Test
    void bareLambdaInStaticFunctionKeepsBoundaryAndSiblings() throws Exception {
        var analyzed = analyze("bare_lambda_static.gd", """
                class_name BareLambdaStatic
                extends Node

                static func outer():
                    func():
                        pass
                    var after := 1
                """);
        var outerFunction = findStatement(
                analyzed.unit().ast().statements(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("outer")
        );
        var after = findStatement(
                outerFunction.body().statements(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("after")
        );

        var diagnostics = analyzed.diagnostics().asList();
        assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
        assertEquals("sema.unsupported_binding_subtree", diagnostics.getFirst().category());
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(after));
    }

    @Test
    void bareLambdaInInnerClassMemberBodyGetsSameBoundary() throws Exception {
        var analyzed = analyze("bare_lambda_inner_class.gd", """
                class_name BareLambdaInnerClass
                extends Node

                class Inner:
                    func member():
                        func():
                            pass
                        var after := 1
                """);

        var diagnostics = analyzed.diagnostics().asList();
        assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
        assertEquals("sema.unsupported_binding_subtree", diagnostics.getFirst().category());
        // The inner-class member itself still resolves normally.
        var after = findNode(
                analyzed.unit().ast(),
                VariableDeclaration.class,
                variableDeclaration -> variableDeclaration.name().equals("after")
        );
        assertEquals(GdIntType.INT, analyzed.analysisData().slotTypes().get(after));
    }

    @Test
    void multipleBareLambdaStatementsEachGetOneDiagnostic() throws Exception {
        var analyzed = analyze("bare_lambda_multiple.gd", """
                class_name BareLambdaMultiple
                extends Node

                func outer():
                    func():
                        pass
                    func():
                        pass
                """);

        // Each statement-position declaration is its own recovery root with its own diagnostic.
        var diagnostics = analyzed.diagnostics().asList();
        assertEquals(2, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
        for (var diagnostic : diagnostics) {
            assertEquals("sema.unsupported_binding_subtree", diagnostic.category());
            assertTrue(diagnostic.message().contains("Standalone lambda statement"), diagnostic.message());
        }
        var bareLambdas = new ArrayList<FunctionDeclaration>();
        collectMatchingNodes(
                analyzed.unit().ast(),
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("<anonymous>"),
                bareLambdas
        );
        assertEquals(2, bareLambdas.size());
        assertEquals(
                FrontendRange.fromAstRange(bareLambdas.get(0).range()),
                diagnostics.get(0).range()
        );
        assertEquals(
                FrontendRange.fromAstRange(bareLambdas.get(1).range()),
                diagnostics.get(1).range()
        );
    }

    @Test
    void suiteEntryGuardRejectsHandBuiltNestedOwner() throws Exception {
        var phaseInput = phaseInput("bare_lambda_entry_guard.gd", """
                class_name BareLambdaEntryGuard
                extends Node

                func outer():
                    func():
                        pass
                """);
        var bareLambda = findBareLambdaStatement(phaseInput.unit().ast());
        assertNotNull(phaseInput.analysisData().scopesByAst().get(bareLambda));
        // A hand-built surface can smuggle a statement-position declaration back into the
        // callable-owner list; the suite entry guard must reject it with a diagnostic at entry
        // time instead of crashing deep inside return-slot resolution.
        var handBuiltSurface = new FrontendInterfaceSurface(
                new FrontendBodyDeclarationIndex(Map.of()),
                FrontendTypedLexicalBaseline.builder().build(),
                new FrontendSuiteEntryRoots(List.of(bareLambda), List.of(), List.of())
        );

        new FrontendSuiteResolver().resolve(
                handBuiltSurface,
                phaseInput.registry(),
                phaseInput.analysisData(),
                phaseInput.diagnostics()
        );

        var diagnostics = phaseInput.diagnostics().snapshot().asList();
        assertEquals(1, diagnostics.size(), () -> "Unexpected diagnostics: " + diagnostics);
        var diagnostic = diagnostics.getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity());
        assertEquals("sema.unsupported_binding_subtree", diagnostic.category());
        assertEquals(FrontendRange.fromAstRange(bareLambda.range()), diagnostic.range());
    }

    private static @NotNull FunctionDeclaration findBareLambdaStatement(@NotNull Node root) {
        return findNode(
                root,
                FunctionDeclaration.class,
                functionDeclaration -> functionDeclaration.name().equals("<anonymous>")
        );
    }

    private static @NotNull AnalyzedSource analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit)),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new AnalyzedSource(unit, analysisData, diagnostics.snapshot());
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

    private static <T extends Statement> @NotNull T findStatement(
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

    private record AnalyzedSource(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticSnapshot diagnostics
    ) {
    }

    private record PhaseInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull ClassRegistry registry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics
    ) {
    }
}
