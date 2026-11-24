package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.ErrorExpression;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.MissingAttributeStep;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Integration coverage for the fault-tolerant semantic pipeline
/// (`frontend_lsp_foundation_implementation.md` §2.2): parser-damaged subtrees are skipped
/// through `skippedSubtreeRoots()` while healthy sibling subtrees of the same file and clean
/// sibling files keep publishing facts.
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
        assertParserRecovery(fixture, fixture.unit(), ifStatement, "if.condition",
                DiagnosticCodes.SLOT_IF_CONDITION, "if");
        assertAll(
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
        assertParserRecovery(fixture, fixture.unit(), forStatement, "for.iterable",
                DiagnosticCodes.SLOT_FOR_ITERABLE, "in");
        assertAll(
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
        assertParserRecovery(fixture, fixture.unit(), matchStatement, "match.value",
                DiagnosticCodes.SLOT_MATCH_VALUE, "match");
        assertAll(
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
        assertParserRecovery(fixture, fixture.unit(), returnStatement, "return.value",
                DiagnosticCodes.SLOT_RETURN_VALUE, "= 3");
        assertAll(
                () -> assertTrue(fixture.isSkipped(returnStatement),
                        "error expression in return value must promote to the return statement"),
                () -> assertNull(fixture.analysisData().expressionTypes().get(returnStatement.value())),
                () -> assertHealthyLocalBinding(fixture, function, "y")
        );
    }

    @Test
    void damagedLocalInitializerSkipsOnlyItsDeclaration() throws IOException {
        var fixture = analyze("broken_statement.gd", HEADER + """
                func f():
                    var y = 2
                    var x = true if  else false
                """);

        var function = requireFunction(fixture.unit(), "f");
        var damaged = requireChild(function.body(), VariableDeclaration.class, variable -> variable.name().equals("x"));
        var conditional = assertInstanceOf(ConditionalExpression.class, damaged.value());
        assertInstanceOf(ErrorExpression.class, conditional.condition());
        assertParserRecovery(fixture, fixture.unit(), damaged, "variable.initializer",
                DiagnosticCodes.SLOT_TERNARY_CONDITION, "if");
        var bodyScope = assertInstanceOf(BlockScope.class, fixture.analysisData().scopesByAst().get(function.body()));
        assertAll(
                () -> assertTrue(fixture.isSkipped(damaged)),
                () -> assertNull(fixture.analysisData().scopesByAst().get(damaged)),
                () -> assertNull(fixture.analysisData().expressionTypes().get(conditional)),
                () -> assertNull(fixture.analysisData().slotTypes().get(damaged)),
                () -> assertNull(bodyScope.resolveValueHere("x")),
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
        assertParserRecovery(fixture, fixture.unit(), innerIf, "if.condition", DiagnosticCodes.SLOT_IF_CONDITION, "if");
        assertAll(
                () -> assertTrue(fixture.isSkipped(innerIf)),
                () -> assertNull(fixture.analysisData().scopesByAst().get(innerIf.body())),
                () -> assertFalse(fixture.isSkipped(gDeclaration), "the lambda-owning statement must survive"),
                () -> assertFalse(fixture.isSkipped(lambda), "the lambda itself must survive"),
                () -> assertNotNull(fixture.analysisData().scopesByAst().get(lambda.body()),
                        "lambda body scope must still be published"),
                () -> assertHealthyLocalBinding(fixture, function, "g"),
                () -> assertHealthyLocalBinding(fixture, function, "y")
        );
    }

    @Test
    void classMemberWithMissingInitializerCloserSkipsOnlyItself() throws IOException {
        var fixture = analyze("broken_member.gd", HEADER + """
                var mp: int = 2
                var hp = (1
                """);

        var kept = assertInstanceOf(VariableDeclaration.class, fixture.unit().ast().statements().get(2));
        var damaged = assertInstanceOf(VariableDeclaration.class, fixture.unit().ast().statements().getLast());
        assertEquals("hp", damaged.name());
        assertParserRecovery(fixture, fixture.unit(), damaged, "variable.initializer",
                DiagnosticCodes.MISSING_CLOSER_GROUPING, "");
        assertEquals(damaged.range().endByte(), fixture.unit().parseRecoveries().getFirst().range().startByte());
        var topLevelClass = fixture.topLevelClassDef();
        assertAll(
                () -> assertTrue(fixture.isSkipped(damaged)),
                () -> assertNull(fixture.analysisData().scopesByAst().get(damaged)),
                () -> assertNull(fixture.analysisData().expressionTypes().get(damaged.value())),
                () -> assertEquals(GdIntType.INT, topLevelClass.getProperties().stream()
                        .filter(property -> property.getName().equals("mp"))
                        .findFirst()
                        .orElseThrow().getType(), "healthy sibling property must stay registered"),
                () -> assertEquals(GdIntType.INT, fixture.analysisData().expressionTypes().get(kept.value()).publishedType()),
                () -> assertTrue(topLevelClass.getProperties().stream()
                                .noneMatch(property -> property.getName().equals("hp")),
                        "the damaged member must not be registered")
        );
    }

    @Test
    void damagedParameterDefaultKeepsCallableBodyAlive() throws IOException {
        var fixture = analyze("broken_default.gd", HEADER + """
                func f(x = 1 +):
                    var y = 2
                """);

        var function = requireFunction(fixture.unit(), "f");
        assertEquals(1, function.parameters().size(), "a damaged default must not invent a second parameter");
        var parameter = function.parameters().getFirst();
        assertParserRecovery(fixture, fixture.unit(), parameter, "parameter.default",
                DiagnosticCodes.SLOT_BINARY_RIGHT, "+");
        assertAll(
                () -> assertFalse(fixture.isSkipped(function), "the callable must not be skipped"),
                () -> assertTrue(fixture.isSkipped(parameter.defaultValue())),
                () -> assertNull(fixture.analysisData().expressionTypes().get(parameter.defaultValue())),
                () -> assertNull(requireFunctionDef(fixture, fixture.unit(), "f").getParameter("x").getDefaultValueFunc()),
                () -> assertHealthyLocalBinding(fixture, function, "y")
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
        var receiver = assertInstanceOf(SelfExpression.class, chain.base());
        assertInstanceOf(MissingAttributeStep.class, chain.steps().getLast());
        assertParserRecovery(fixture, fixture.unit(), xDeclaration, "variable.initializer",
                DiagnosticCodes.ATTRIBUTE_MISSING_NAME, ".");
        assertAll(
                () -> assertFalse(fixture.isSkipped(xDeclaration),
                        "partial member chains must not be skipped (frontend_lsp_foundation_implementation.md §2.2.1)"),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED,
                        fixture.analysisData().expressionTypes().get(receiver).status()),
                () -> assertEquals(new GdObjectType("Recover"),
                        fixture.analysisData().expressionTypes().get(receiver).publishedType()),
                () -> assertEquals(FrontendExpressionTypeStatus.FAILED,
                        fixture.analysisData().expressionTypes().get(chain).status()),
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
        // Byte ranges are file-local. A's damaged default and B's mandatory parameter occupy
        // the same byte range but have different diagnostic owners.
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

        var a = fixture.unit("a.gd");
        var b = fixture.unit("b.gd");
        var defaultParameter = requireFunction(a, "f").parameters().getFirst();
        var orderFunction = requireFunction(b, "f");
        assertParserRecovery(fixture, a, defaultParameter, "parameter.default", DiagnosticCodes.SLOT_BINARY_RIGHT, "+");
        assertParserRecovery(fixture, b, orderFunction, "function.signature",
                DiagnosticCodes.SIGNATURE_MANDATORY_AFTER_OPTIONAL, "b");
        assertEquals(a.parseRecoveries().getFirst().range(), b.parseRecoveries().getFirst().range());
        assertEquals("tmp/b.gd", fixture.analysisData().claimedParseRecoveries()
                .get(b.parseRecoveries().getFirst()).sourcePath());
        assertOrderMetadata(fixture, b, orderFunction);

        // Hand-build B's violating parameter and owner without its parse recovery. A's active
        // parser diagnostic still overlaps, but may not silence the semantic fallback in B.
        var parsedOffender = orderFunction.parameters().getLast();
        var mandatory = new Parameter("b", null, null, false, parsedOffender.range());
        var manualFunction = new FunctionDeclaration(orderFunction.name(),
                List.of(orderFunction.parameters().getFirst(), mandatory),
                orderFunction.returnType(), orderFunction.isStatic(), orderFunction.body(), orderFunction.range());
        var statements = b.ast().statements().stream()
                .map(statement -> statement == orderFunction ? manualFunction : statement)
                .toList();
        var manual = new FrontendSourceUnit(b.path(), b.source(), new SourceFile(statements, b.ast().range()));
        assertTrue(manual.parseRecoveries().isEmpty());
        var manager = new DiagnosticManager();
        var onlyA = fixture.parserDiagnostics().stream()
                .filter(diagnostic -> diagnostic.sourcePath().equals("tmp/a.gd"))
                .toList();
        manager.reportAll(onlyA);
        var fallback = analyzeUnits(List.of(a, manual), manager);
        var errors = fallback.diagnostics().snapshot().asList();
        assertEquals(2, errors.size(), errors::toString);
        assertEquals(onlyA.getFirst(), errors.getFirst());
        var orderError = errors.getLast();
        assertEquals("sema.invalid_parameter_default_order", orderError.category());
        assertEquals(FrontendDiagnosticSeverity.ERROR, orderError.severity());
        assertEquals("tmp/b.gd", orderError.sourcePath());
        assertEquals(FrontendRange.fromAstRange(mandatory.range()), orderError.range());
        assertEquals(onlyA.getFirst().range(), orderError.range());
        assertTrue(orderError.message().contains("'b'"));
        assertFalse(fallback.analysisData().parseRecoveriesByOwner().containsKey(manualFunction));
        assertOrderMetadata(fallback, manual, manualFunction);
    }

    private static void assertOrderMetadata(RecoveryFixture fixture, FrontendSourceUnit unit, FunctionDeclaration function) {
        var definition = requireFunctionDef(fixture, unit, function.name());
        assertAll(
                () -> assertEquals("_default_f$a", definition.getParameter("a").getDefaultValueFunc()),
                () -> assertNull(definition.getParameter("b").getDefaultValueFunc()),
                () -> assertTrue(fixture.analysisData().scopesByAst().containsKey(function.body()))
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

        var broken = fixture.unit("broken.gd");
        var damaged = requireChild(requireFunction(broken, "f").body(), IfStatement.class);
        assertParserRecovery(fixture, broken, damaged, "if.condition", DiagnosticCodes.SLOT_IF_CONDITION, "if");
        var cleanUnit = fixture.unit("clean.gd");
        var cleanFunction = requireFunction(cleanUnit, "g");
        assertAll(
                () -> assertTrue(fixture.isSkipped(damaged)),
                () -> assertTrue(cleanUnit.parseRecoveries().isEmpty()),
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

    private static LirFunctionDef requireFunctionDef(RecoveryFixture fixture, FrontendSourceUnit unit, String name) {
        return fixture.analysisData().moduleSkeleton().sourceClassRelations().stream()
                .filter(relation -> relation.unit() == unit)
                .flatMap(relation -> relation.topLevelClassDef().getFunctions().stream())
                .filter(function -> function.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("function definition '" + name + "' not found in " + unit.path()));
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
        var property = requireChild(fixture.unit().ast(), VariableDeclaration.class);
        assertParserRecovery(fixture, fixture.unit(), property, "variable.initializer",
                DiagnosticCodes.SLOT_ARRAY_ELEMENT, ",");
        assertAll(
                () -> assertTrue(fixture.isSkipped(property)),
                () -> assertNull(fixture.analysisData().scopesByAst().get(property)),
                () -> assertNull(fixture.analysisData().expressionTypes().get(property.value())),
                () -> assertTrue(fixture.topLevelClassDef().getProperties().isEmpty())
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

        var unit = fixture.unit();
        var annotation = requireChild(unit.ast(), AnnotationStatement.class);
        var property = requireChild(unit.ast(), VariableDeclaration.class);
        assertParserRecoveries(fixture, unit, annotation, "annotation.arguments", List.of(
                new ExpectedRecovery(DiagnosticCodes.SLOT_ANNOTATION_ARGUMENT, ","),
                new ExpectedRecovery(DiagnosticCodes.MISSING_CLOSER_ANNOTATION, "")
        ));
        assertEquals(annotation.range().endByte(), unit.parseRecoveries().getLast().range().startByte());
        assertAll(
                () -> assertTrue(fixture.isSkipped(annotation)),
                () -> assertFalse(fixture.isSkipped(property)),
                () -> assertNotNull(fixture.analysisData().scopesByAst().get(property)),
                () -> assertEquals(GdIntType.INT, fixture.analysisData().expressionTypes().get(property.value()).publishedType()),
                () -> assertEquals(List.of("hp"),
                        fixture.topLevelClassDef().getProperties().stream().map(PropertyDef::getName).toList())
        );
    }

    private static void assertParserRecovery(
            RecoveryFixture fixture, FrontendSourceUnit unit, Node owner, String facet, String code, String anchor
    ) {
        assertParserRecoveries(fixture, unit, owner, facet, List.of(new ExpectedRecovery(code, anchor)));
    }

    private static void assertParserRecoveries(
            RecoveryFixture fixture, FrontendSourceUnit unit, Node owner, String facet, List<ExpectedRecovery> expected
    ) {
        var sourcePath = FrontendDiagnostic.sourcePathText(unit.path());
        var original = fixture.parserDiagnostics().stream()
                .filter(diagnostic -> sourcePath.equals(diagnostic.sourcePath()))
                .toList();
        var recoveries = unit.parseRecoveries();
        assertEquals(expected.size(), original.size(), original::toString);
        assertEquals(expected.size(), recoveries.size());
        for (var index = 0; index < expected.size(); index++) {
            var diagnostic = original.get(index);
            var recovery = recoveries.get(index);
            var expectation = expected.get(index);
            assertEquals(expectation.code(), diagnostic.category());
            assertEquals(FrontendDiagnosticSeverity.ERROR, diagnostic.severity());
            var range = diagnostic.range();
            assertEquals(expectation.anchor(), unit.source().substring(range.startByte(), range.endByte()));
            assertEquals(expectation.code(), recovery.code());
            assertEquals(range, recovery.range());
            assertSame(owner, recovery.owner());
            assertEquals(facet, recovery.facet());
        }
        var activeErrors = fixture.diagnostics().snapshot().asList().stream()
                .filter(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
                .toList();
        assertEquals(fixture.parserDiagnostics(), activeErrors, "recovering a parser error must not produce a second error");
    }

    private record ExpectedRecovery(String code, String anchor) {
    }

    private record SourceSpec(@NotNull String fileName, @NotNull String source) {
    }

    private record RecoveryFixture(
            @NotNull List<FrontendSourceUnit> units,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull List<FrontendDiagnostic> parserDiagnostics,
            @NotNull DiagnosticManager diagnostics
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
        return analyzeUnits(units, diagnostics);
    }

    private static RecoveryFixture analyzeUnits(List<FrontendSourceUnit> units, DiagnosticManager diagnostics) throws IOException {
        var parserDiagnostics = diagnostics.snapshot().asList();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", units),
                registry,
                diagnostics
        );
        return new RecoveryFixture(units, analysisData, parserDiagnostics, diagnostics);
    }
}
