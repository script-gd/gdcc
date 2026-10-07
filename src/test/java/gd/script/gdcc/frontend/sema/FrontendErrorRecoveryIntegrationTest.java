package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ErrorStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeValueKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Integration coverage for the fault-tolerant semantic pipeline
/// (`frontend_lsp_foundation_implementation.md` §2.2): parser-damaged subtrees are skipped
/// through `skippedSubtreeRoots()` while healthy sibling subtrees of the same file and clean
/// sibling files keep publishing facts.
///
/// The damaged source shapes below were probed against gdparser 0.6.0 to guarantee they produce
/// real `ErrorStatement`/`ErrorExpression` nodes (not silently dropped structures), so every
/// negative anchor pins the actual recovery contract.
class FrontendErrorRecoveryIntegrationTest {
    private static final @NotNull String HEADER = """
            class_name Recover
            extends RefCounted
            
            """;

    @Test
    void brokenIfConditionSkipsOnlyIfStatement() throws IOException {
        var fixture = analyze("broken_if.gd", HEADER + """
                func f():
                    if :
                        pass
                    var y = 2
                """);

        var function = requireFunction(fixture.unit(), "f");
        var ifStatement = requireChild(function.body(), IfStatement.class);
        var yDeclaration = requireChild(function.body(), VariableDeclaration.class);
        assertAll(
                () -> assertTrue(fixture.hasParseError(), "parse.lowering ERROR expected"),
                () -> assertTrue(fixture.isSkipped(ifStatement), "damaged if statement must be a skipped root"),
                () -> assertFalse(fixture.isSkipped(function), "the callable must survive"),
                () -> assertNull(fixture.analysisData().scopesByAst().get(ifStatement)),
                () -> assertNull(fixture.analysisData().scopesByAst().get(ifStatement.body())),
                () -> assertHealthyLocalBinding(fixture, function, "y")
        );
    }

    @Test
    void brokenForIterableSkipsOnlyForStatement() throws IOException {
        var fixture = analyze("broken_for.gd", HEADER + """
                func f():
                    for i in :
                        pass
                    var y = 2
                """);

        var function = requireFunction(fixture.unit(), "f");
        var forStatement = requireChild(function.body(), ForStatement.class);
        assertAll(
                () -> assertTrue(fixture.hasParseError()),
                () -> assertTrue(fixture.isSkipped(forStatement)),
                () -> assertNull(fixture.analysisData().scopesByAst().get(forStatement)),
                () -> assertNull(fixture.analysisData().scopesByAst().get(forStatement.body())),
                () -> assertHealthyLocalBinding(fixture, function, "y")
        );
    }

    @Test
    void brokenMatchValueSkipsOnlyMatchStatement() throws IOException {
        var fixture = analyze("broken_match.gd", HEADER + """
                func f():
                    match :
                        1:
                            pass
                    var y = 2
                """);

        var function = requireFunction(fixture.unit(), "f");
        var matchStatement = requireChild(function.body(), MatchStatement.class);
        assertAll(
                () -> assertTrue(fixture.hasParseError()),
                () -> assertTrue(fixture.isSkipped(matchStatement)),
                () -> assertNull(fixture.analysisData().scopesByAst().get(matchStatement)),
                () -> assertHealthyLocalBinding(fixture, function, "y")
        );
    }

    @Test
    void brokenReturnStatementSkipsOnlyItself() throws IOException {
        var fixture = analyze("broken_return.gd", HEADER + """
                func f():
                    var y = 2
                    return = 3
                """);

        var function = requireFunction(fixture.unit(), "f");
        var returnStatement = requireChild(function.body(), ReturnStatement.class);
        assertAll(
                () -> assertTrue(fixture.hasParseError()),
                () -> assertTrue(fixture.isSkipped(returnStatement),
                        "error expression in return value must promote to the return statement"),
                () -> assertHealthyLocalBinding(fixture, function, "y")
        );
    }

    @Test
    void errorStatementInFunctionBodySkipsOnlyItself() throws IOException {
        // `true if  else false` lowers to an ErrorStatement node (probed against gdparser 0.6.0).
        var fixture = analyze("broken_statement.gd", HEADER + """
                func f():
                    var y = 2
                    var x = true if  else false
                """);

        var function = requireFunction(fixture.unit(), "f");
        var errorStatement = requireChild(function.body(), ErrorStatement.class);
        assertAll(
                () -> assertTrue(fixture.hasParseError()),
                () -> assertTrue(fixture.isSkipped(errorStatement)),
                () -> assertNull(fixture.analysisData().scopesByAst().get(errorStatement)),
                () -> assertHealthyLocalBinding(fixture, function, "y")
        );
    }

    @Test
    void brokenLambdaBodyStatementKeepsLambdaAndOuterStatementAlive() throws IOException {
        var fixture = analyze("broken_lambda.gd", HEADER + """
                func f():
                    var g = func():
                        if :
                            pass
                    var y = 2
                """);

        var function = requireFunction(fixture.unit(), "f");
        var gDeclaration = requireChild(function.body(), VariableDeclaration.class, variable -> variable.name().equals("g"));
        var lambda = assertInstanceOf(LambdaExpression.class, gDeclaration.value());
        var innerIf = requireChild(lambda.body(), IfStatement.class);
        assertAll(
                () -> assertTrue(fixture.hasParseError()),
                () -> assertTrue(fixture.isSkipped(innerIf)),
                () -> assertFalse(fixture.isSkipped(gDeclaration), "the lambda-owning statement must survive"),
                () -> assertFalse(fixture.isSkipped(lambda), "the lambda itself must survive"),
                () -> assertNotNull(fixture.analysisData().scopesByAst().get(lambda.body()),
                        "lambda body scope must still be published"),
                () -> assertHealthyLocalBinding(fixture, function, "g"),
                () -> assertHealthyLocalBinding(fixture, function, "y")
        );
    }

    @Test
    void classLevelErrorStatementSkipsOnlyItself() throws IOException {
        // `var hp = (1` at end of file lowers to a class-member ErrorStatement (probed).
        var fixture = analyze("broken_member.gd", HEADER + """
                var mp = 2
                var hp = (1
                """);

        var errorStatement = requireChild(fixture.unit().ast(), ErrorStatement.class);
        var topLevelClass = fixture.topLevelClassDef();
        assertAll(
                () -> assertTrue(fixture.hasParseError()),
                () -> assertTrue(fixture.isSkipped(errorStatement)),
                () -> assertNotNull(topLevelClass.getProperties().stream()
                        .filter(property -> property.getName().equals("mp"))
                        .findFirst()
                        .orElse(null), "healthy sibling property must stay registered"),
                () -> assertTrue(topLevelClass.getProperties().stream()
                                .noneMatch(property -> property.getName().equals("hp")),
                        "the damaged member must not be registered")
        );
    }

    @Test
    void damagedParameterDefaultKeepsCallableBodyAlive() throws IOException {
        // gdparser 0.6.0 maps `x = 1 +` to a defaulted `x` plus a phantom parameter rather than an
        // error-expression default, so this anchors the tolerant no-crash outcome of that shape.
        var fixture = analyze("broken_default.gd", HEADER + """
                func f(x = 1 +):
                    var y = 2
                """);

        var function = requireFunction(fixture.unit(), "f");
        assertAll(
                () -> assertTrue(fixture.hasParseError()),
                () -> assertFalse(fixture.isSkipped(function), "the callable must not be skipped"),
                () -> assertHealthyLocalBinding(fixture, function, "y"),
                // Single-owner rule: the phantom parameter must not trigger a second sema-level
                // parameter-order error for the same parser-reported root cause.
                () -> assertTrue(fixture.diagnostics.snapshot().asList().stream()
                                .noneMatch(diagnostic -> diagnostic.category()
                                        .equals("sema.invalid_parameter_default_order")),
                        () -> "phantom parameter must not earn sema.invalid_parameter_default_order: "
                                + fixture.diagnostics.snapshot().asList())
        );
    }

    @Test
    void partialMemberChainIsNotSkippedSoReceiverStaysTyped() throws IOException {
        // `self.` at the end of input maps to a normal AttributeExpression with a trailing
        // MissingAttributeStep (not an error node), keeping the receiver analyzable for completion.
        var fixture = analyze("partial_chain.gd", HEADER + """
                func f():
                    var x = self.
                """);

        var function = requireFunction(fixture.unit(), "f");
        var xDeclaration = requireChild(function.body(), VariableDeclaration.class);
        var chain = assertInstanceOf(AttributeExpression.class, xDeclaration.value());
        assertAll(
                () -> assertTrue(fixture.hasParseError(), "Missing identifier diagnostic expected"),
                () -> assertFalse(fixture.isSkipped(xDeclaration),
                        "partial member chains must not be skipped (frontend_lsp_foundation_implementation.md §2.2.1)"),
                () -> assertFalse(chain.steps().isEmpty(), "the chain keeps its steps"),
                () -> assertHealthyLocalBinding(fixture, function, "x"),
                // Single-owner rule: the parser's `Missing identifier` diagnostic is the only
                // error; sema phases must not re-report the same root cause.
                () -> assertTrue(fixture.diagnostics.snapshot().asList().stream()
                                .noneMatch(diagnostic -> diagnostic.category().startsWith("sema.")
                                        && diagnostic.severity() == FrontendDiagnosticSeverity.ERROR),
                        () -> "unexpected duplicate sema error: " + fixture.diagnostics.snapshot().asList())
        );
    }

    @Test
    void damagedParameterDefaultInOneFileNeverSuppressesOrderErrorInAnother() throws IOException {
        // The parse diagnostic manager is shared module-wide while byte ranges are file-local:
        // A.gd's damaged parameter list must not suppress B.gd's genuine order violation. The
        // fixture is tuned so A.gd's parse diagnostic range actually overlaps the byte range of
        // B.gd's mandatory parameter (both [47,48)) — verified below, otherwise this test could
        // not catch a missing source-path filter.
        var fixture = analyze(
                List.of(
                        new SourceSpec("a.gd", """
                                class_name A
                                extends RefCounted
                                
                                func f(xx = 1 +):
                                    pass
                                """),
                        new SourceSpec("b.gd", """
                                class_name B
                                extends RefCounted
                                
                                func f(a = 1, b):
                                    pass
                                """)
                )
        );

        var parseDiagnosticsInA = fixture.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().startsWith("parse."))
                .filter(diagnostic -> diagnostic.sourcePath().endsWith("a.gd"))
                .toList();
        var orderErrors = fixture.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.invalid_parameter_default_order"))
                .toList();
        assertAll(
                () -> assertTrue(fixture.hasParseError(), "A.gd must carry the parse error"),
                () -> assertEquals(1, orderErrors.size(),
                        () -> "B.gd's genuine order violation must survive: "
                                + fixture.diagnostics().snapshot().asList()),
                () -> assertTrue(orderErrors.getFirst().message().contains("'b'")),
                () -> assertTrue(orderErrors.getFirst().sourcePath().endsWith("b.gd")),
                // The order error is anchored at B.gd's parameter; the fixture only exercises the
                // source-path filter when A.gd's parse diagnostic range truly overlaps it.
                () -> assertTrue(parseDiagnosticsInA.stream().anyMatch(parseDiagnostic -> {
                            var parseRange = parseDiagnostic.range();
                            var orderRange = orderErrors.getFirst().range();
                            return parseRange.startByte() < orderRange.endByte()
                                    && orderRange.startByte() < parseRange.endByte();
                        }),
                        "fixture must keep A.gd's parse range overlapping B.gd's parameter range")
        );
    }

    @Test
    void cleanFileKeepsFactsWhenSiblingFileIsBroken() throws IOException {
        var fixture = analyze(
                List.of(
                        new SourceSpec("broken.gd", HEADER + """
                                func f():
                                    if :
                                        pass
                                """),
                        new SourceSpec("clean.gd", """
                                class_name Clean
                                extends RefCounted
                                
                                func g():
                                    var z = 3
                                """)
                )
        );

        var cleanFunction = requireFunction(fixture.unit("clean.gd"), "g");
        assertAll(
                () -> assertTrue(fixture.hasParseError()),
                () -> assertEquals(2, fixture.analysisData().moduleSkeleton().sourceClassRelations().size()),
                () -> assertHealthyLocalBinding(fixture, cleanFunction, "z")
        );
    }

    @Test
    void parseFailedUnitRegistersNoClassAndReferencesStayUnresolved() throws IOException {
        // Simulate the `parse.internal` recovery path: an explicit failed unit with an empty
        // synthetic AST plus its already-reported parse.internal diagnostic
        // (`frontend_lsp_foundation_implementation.md` §2.2.5).
        var diagnostics = new DiagnosticManager();
        var failedUnit = new FrontendSourceUnit(
                Path.of("tmp", "broken.gd"),
                "class_name Broken\n",
                new SourceFile(List.of(), new Range(0, 0, new Point(0, 0), new Point(0, 0))),
                true
        );
        diagnostics.reportAll(List.of(FrontendDiagnostic.error(
                "parse.internal",
                "Unexpected parser failure: simulated",
                "tmp/broken.gd",
                null
        )));
        var parserService = new GdScriptParserService();
        var consumerUnit = parserService.parseUnit(
                Path.of("tmp", "consumer.gd"),
                """
                        class_name Consumer
                        extends RefCounted
                        
                        var b: Broken
                        func g():
                            var z = 3
                        """,
                diagnostics
        );
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(failedUnit, consumerUnit)),
                registry,
                diagnostics
        );

        assertAll(
                () -> assertEquals(1, analysisData.moduleSkeleton().sourceClassRelations().size(),
                        "the failed unit must not contribute a synthetic top-level class"),
                () -> assertEquals("Consumer",
                        analysisData.moduleSkeleton().sourceClassRelations().getFirst().sourceName()),
                () -> assertNull(registry.findGdccClass("Broken"),
                        "no fictional Broken class may be registered"),
                () -> assertTrue(diagnostics.snapshot().asList().stream().anyMatch(diagnostic ->
                                diagnostic.category().equals("sema.type_resolution")
                                        && diagnostic.message().contains("Broken")),
                        "referencing the failed unit's class must surface the ordinary unresolved-type diagnostic"),
                () -> assertTrue(diagnostics.snapshot().asList().stream().anyMatch(diagnostic ->
                        diagnostic.category().equals("parse.internal")))
        );
        var gFunction = requireFunction(consumerUnit, "g");
        var bodyScope = assertInstanceOf(BlockScope.class, analysisData.scopesByAst().get(gFunction.body()));
        var zBinding = bodyScope.resolveValueHere("z");
        assertAll(
                () -> assertNotNull(zBinding, "the clean unit's body facts must still publish"),
                () -> assertEquals(ScopeValueKind.LOCAL, zBinding.kind())
        );
    }

    /// Asserts that local `name` in `function`'s body block scope got its full published binding.
    private static void assertHealthyLocalBinding(
            @NotNull RecoveryFixture fixture,
            @NotNull FunctionDeclaration function,
            @NotNull String name
    ) {
        var bodyScope = assertInstanceOf(
                BlockScope.class,
                fixture.analysisData().scopesByAst().get(function.body())
        );
        var binding = bodyScope.resolveValueHere(name);
        assertAll(
                () -> assertNotNull(binding, "local '" + name + "' must be bound"),
                () -> assertEquals(ScopeValueKind.LOCAL, binding.kind()),
                () -> assertNotNull(fixture.analysisData().scopesByAst().get(function),
                        "the enclosing callable keeps its scope")
        );
    }

    private static @NotNull FunctionDeclaration requireFunction(@NotNull FrontendSourceUnit unit, @NotNull String name) {
        return unit.ast().statements().stream()
                .filter(FunctionDeclaration.class::isInstance)
                .map(FunctionDeclaration.class::cast)
                .filter(function -> function.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("function '" + name + "' not found"));
    }

    private static <T extends Node> @NotNull T requireChild(@NotNull Block block, @NotNull Class<T> type) {
        return requireChild(block, type, _ -> true);
    }

    private static <T extends Node> @NotNull T requireChild(
            @NotNull Block block,
            @NotNull Class<T> type,
            @NotNull Predicate<T> predicate
    ) {
        return block.statements().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(predicate)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no child of type " + type.getSimpleName()));
    }

    private static <T extends Node> @NotNull T requireChild(@NotNull SourceFile sourceFile, @NotNull Class<T> type) {
        return sourceFile.statements().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no child of type " + type.getSimpleName()));
    }

    @Test
    void damagedAnnotatedPropertyKeepsOnlyItsParserDiagnostic() throws IOException {
        // The damaged initializer marks the whole property declaration as a skipped root; the
        // annotation usage check must not read the resulting missing ClassScope as a placement
        // violation (single-owner recovery rule).
        var fixture = analyze("damaged_annotated_property.gd", """
                class_name Recover
                extends Node
                
                @onready
                var hp: int = [1, = 3]
                """);

        var usageDiagnostics = fixture.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.annotation_usage"))
                .toList();
        assertAll(
                () -> assertTrue(fixture.hasParseError(), "parser must own the damage diagnostic"),
                () -> assertTrue(usageDiagnostics.isEmpty(),
                        () -> "recovery must not invent annotation placement errors: " + usageDiagnostics)
        );
    }

    @Test
    void damagedAnnotationArgumentKeepsOnlyItsParserDiagnostic() throws IOException {
        // The annotation statement is the damaged root here; its semantic projection (attached to
        // the healthy property) must not be validated on top of the parser's diagnostic.
        var fixture = analyze("damaged_annotation_argument.gd", HEADER + """
                @export_range(1, = 3)
                var hp: int = 2
                """);

        var usageDiagnostics = fixture.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.annotation_usage"))
                .toList();
        assertAll(
                () -> assertTrue(fixture.hasParseError(), "parser must own the damage diagnostic"),
                () -> assertTrue(usageDiagnostics.isEmpty(),
                        () -> "a damaged annotation projection must not be re-validated: " + usageDiagnostics)
        );
    }

    private record SourceSpec(@NotNull String fileName, @NotNull String source) {
    }

    private record RecoveryFixture(
            @NotNull List<FrontendSourceUnit> units,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnostics,
            @NotNull ClassRegistry registry
    ) {
        private @NotNull FrontendSourceUnit unit() {
            return units.getFirst();
        }

        private @NotNull FrontendSourceUnit unit(@NotNull String fileName) {
            return units.stream()
                    .filter(unit -> unit.path().getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("unit not found: " + fileName));
        }

        private boolean isSkipped(@NotNull Node node) {
            return analysisData.skippedSubtreeRoots().containsKey(node);
        }

        private boolean hasParseError() {
            return diagnostics.snapshot().asList().stream()
                    .anyMatch(diagnostic -> diagnostic.category().equals("parse.lowering")
                            && diagnostic.severity() == FrontendDiagnosticSeverity.ERROR);
        }

        private @NotNull ClassDef topLevelClassDef() {
            return analysisData.moduleSkeleton().sourceClassRelations().getFirst().topLevelClassDef();
        }
    }

    private static @NotNull RecoveryFixture analyze(@NotNull String fileName, @NotNull String source) throws IOException {
        return analyze(List.of(new SourceSpec(fileName, source)));
    }

    private static @NotNull RecoveryFixture analyze(@NotNull List<SourceSpec> sources) throws IOException {
        var parserService = new GdScriptParserService();
        var diagnostics = new DiagnosticManager();
        var units = sources.stream()
                .map(source -> parserService.parseUnit(Path.of("tmp", source.fileName()), source.source(), diagnostics))
                .toList();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", units),
                registry,
                diagnostics
        );
        return new RecoveryFixture(units, analysisData, diagnostics, registry);
    }
}
