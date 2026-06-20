package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendLocalTypeStabilizationAnalyzerTest {
    @Test
    void probeResolvesComplexInitializerAndLeavesPublishedFactsUntouched() throws Exception {
        var prepared = prepareProbeInput(
                "local_type_stabilization_complex_initializer.gd",
                """
                        class_name LocalTypeStabilizationComplexInitializer
                        extends RefCounted

                        class Point:
                            var next: Point = null
                            var marker: int = -1

                        class Factory:
                            var cached: Point = null

                            func make_point(seed: int) -> Point:
                                return cached if cached != null else Point.new()

                        func ping(factory: Factory, seed: int):
                            var point := factory.make_point(seed).next
                            point
                        """
        );

        var pingFunction = findFunction(prepared.unit().ast().statements(), "ping");
        var pointDeclaration = findVariable(pingFunction.body().statements(), "point");
        var pointUse = assertInstanceOf(
                ExpressionStatement.class,
                pingFunction.body().statements().get(1)
        ).expression();

        var snapshot = new FrontendLocalTypeStabilizationAnalyzer().probe(
                prepared.classRegistry(),
                prepared.analysisData(),
                prepared.diagnosticManager()
        );
        var entry = snapshot.findVariable("point");
        assertNotNull(entry);

        var initializerType = entry.initializerType();
        assertAll(
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, initializerType.status()),
                () -> assertNotNull(initializerType.publishedType()),
                () -> assertTypeNameEndsWith(Objects.requireNonNull(initializerType.publishedType()), "Point"),
                () -> assertNull(prepared.analysisData().expressionTypes().get(pointDeclaration.value())),
                () -> assertNull(prepared.analysisData().expressionTypes().get(pointUse)),
                () -> assertTrue(prepared.analysisData().resolvedMembers().isEmpty()),
                () -> assertTrue(prepared.analysisData().resolvedCalls().isEmpty()),
                () -> assertTrue(prepared.analysisData().slotTypes().isEmpty()),
                () -> assertEquals(GdVariantType.VARIANT, currentLocalType(prepared.analysisData(), pingFunction.body(), "point")),
                () -> assertEquals(0, prepared.diagnosticManager().snapshot().asList().size())
        );
    }

    @Test
    void analyzeStabilizesSourceOrderAliasChainInBlockScope() throws Exception {
        var prepared = prepareProbeInput(
                "local_type_stabilization_alias_chain.gd",
                """
                        class_name LocalTypeStabilizationAliasChain
                        extends RefCounted

                        class Point:
                            var marker: int = -1

                        func make_point() -> Point:
                            return Point.new()

                        func ping():
                            var a := make_point()
                            var b := a
                            var c := b
                            c
                        """
        );

        var pingFunction = findFunction(prepared.unit().ast().statements(), "ping");

        new FrontendLocalTypeStabilizationAnalyzer().analyze(
                prepared.classRegistry(),
                prepared.analysisData(),
                prepared.diagnosticManager()
        );

        assertAll(
                () -> assertTypeNameEndsWith(currentLocalType(prepared.analysisData(), pingFunction.body(), "a"), "Point"),
                () -> assertTypeNameEndsWith(currentLocalType(prepared.analysisData(), pingFunction.body(), "b"), "Point"),
                () -> assertTypeNameEndsWith(currentLocalType(prepared.analysisData(), pingFunction.body(), "c"), "Point"),
                () -> assertTrue(prepared.analysisData().resolvedMembers().isEmpty()),
                () -> assertTrue(prepared.analysisData().resolvedCalls().isEmpty()),
                () -> assertTrue(prepared.analysisData().expressionTypes().isEmpty()),
                () -> assertTrue(prepared.analysisData().slotTypes().isEmpty()),
                () -> assertEquals(0, prepared.diagnosticManager().snapshot().asList().size())
        );
    }

    @Test
    void analyzeStabilizesComplexInitializerThenAliasInBlockScope() throws Exception {
        var prepared = prepareProbeInput(
                "local_type_stabilization_complex_alias.gd",
                """
                        class_name LocalTypeStabilizationComplexAlias
                        extends RefCounted

                        class Point:
                            var next: Point = null
                            var marker: int = -1

                        class Factory:
                            var cached: Point = null

                            func make_point(seed: int) -> Point:
                                return cached if cached != null else Point.new()

                        func ping(factory: Factory, seed: int):
                            var p := factory.make_point(seed).next
                            var q := p
                            q
                        """
        );

        var pingFunction = findFunction(prepared.unit().ast().statements(), "ping");

        new FrontendLocalTypeStabilizationAnalyzer().analyze(
                prepared.classRegistry(),
                prepared.analysisData(),
                prepared.diagnosticManager()
        );

        assertAll(
                () -> assertTypeNameEndsWith(currentLocalType(prepared.analysisData(), pingFunction.body(), "p"), "Point"),
                () -> assertTypeNameEndsWith(currentLocalType(prepared.analysisData(), pingFunction.body(), "q"), "Point"),
                () -> assertTrue(prepared.analysisData().resolvedMembers().isEmpty()),
                () -> assertTrue(prepared.analysisData().resolvedCalls().isEmpty()),
                () -> assertTrue(prepared.analysisData().expressionTypes().isEmpty()),
                () -> assertTrue(prepared.analysisData().slotTypes().isEmpty()),
                () -> assertEquals(0, prepared.diagnosticManager().snapshot().asList().size())
        );
    }

    @Test
    void analyzeKeepsTrueDynamicInitializerAsVariantInBlockScope() throws Exception {
        var prepared = prepareProbeInput(
                "local_type_stabilization_dynamic_fail_closed.gd",
                """
                        class_name LocalTypeStabilizationDynamicFailClosed
                        extends RefCounted

                        func ping(dynamic_host):
                            var point := dynamic_host.next
                            var alias := point
                            alias
                        """
        );

        var pingFunction = findFunction(prepared.unit().ast().statements(), "ping");

        new FrontendLocalTypeStabilizationAnalyzer().analyze(
                prepared.classRegistry(),
                prepared.analysisData(),
                prepared.diagnosticManager()
        );

        assertAll(
                () -> assertEquals(GdVariantType.VARIANT, currentLocalType(prepared.analysisData(), pingFunction.body(), "point")),
                () -> assertEquals(GdVariantType.VARIANT, currentLocalType(prepared.analysisData(), pingFunction.body(), "alias")),
                () -> assertTrue(prepared.analysisData().resolvedMembers().isEmpty()),
                () -> assertTrue(prepared.analysisData().resolvedCalls().isEmpty()),
                () -> assertTrue(prepared.analysisData().expressionTypes().isEmpty()),
                () -> assertTrue(prepared.analysisData().slotTypes().isEmpty()),
                () -> assertEquals(0, prepared.diagnosticManager().snapshot().asList().size())
        );
    }

    @Test
    void analyzeKeepsLambdaBodyOutsideSupportedStabilizationSurface() throws Exception {
        var prepared = prepareProbeInput(
                "local_type_stabilization_lambda_boundary.gd",
                """
                        class_name LocalTypeStabilizationLambdaBoundary
                        extends RefCounted

                        class Point:
                            var marker: int = -1

                        func make_point() -> Point:
                            return Point.new()

                        func ping():
                            var before := make_point()
                            var maker := func():
                                var inside_lambda := make_point()
                                return inside_lambda
                            before
                        """
        );

        var pingFunction = findFunction(prepared.unit().ast().statements(), "ping");
        var diagnosticsBeforeAnalyze = prepared.diagnosticManager().snapshot().asList().size();

        var analyzer = new FrontendLocalTypeStabilizationAnalyzer();
        var snapshot = analyzer.probe(
                prepared.classRegistry(),
                prepared.analysisData(),
                prepared.diagnosticManager()
        );
        analyzer.analyze(
                prepared.classRegistry(),
                prepared.analysisData(),
                prepared.diagnosticManager()
        );

        var makerEntry = snapshot.findVariable("maker");
        assertNotNull(makerEntry);
        assertAll(
                () -> assertTypeNameEndsWith(currentLocalType(prepared.analysisData(), pingFunction.body(), "before"), "Point"),
                () -> assertEquals(GdVariantType.VARIANT, currentLocalType(prepared.analysisData(), pingFunction.body(), "maker")),
                () -> assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, makerEntry.initializerType().status()),
                () -> assertNull(snapshot.findVariable("inside_lambda")),
                () -> assertTrue(prepared.analysisData().resolvedMembers().isEmpty()),
                () -> assertTrue(prepared.analysisData().resolvedCalls().isEmpty()),
                () -> assertTrue(prepared.analysisData().expressionTypes().isEmpty()),
                () -> assertTrue(prepared.analysisData().slotTypes().isEmpty()),
                () -> assertEquals(diagnosticsBeforeAnalyze, prepared.diagnosticManager().snapshot().asList().size())
        );
    }

    @Test
    void probeKeepsTrueDynamicInitializerAsDynamicWithoutWritingSharedFacts() throws Exception {
        var prepared = prepareProbeInput(
                "local_type_stabilization_dynamic_initializer.gd",
                """
                        class_name LocalTypeStabilizationDynamicInitializer
                        extends RefCounted

                        func ping(dynamic_host):
                            var point := dynamic_host.next
                            point
                        """
        );

        var snapshot = new FrontendLocalTypeStabilizationAnalyzer().probe(
                prepared.classRegistry(),
                prepared.analysisData(),
                prepared.diagnosticManager()
        );
        var entry = snapshot.findVariable("point");
        assertNotNull(entry);

        assertAll(
                () -> assertEquals(FrontendExpressionTypeStatus.DYNAMIC, entry.initializerType().status()),
                () -> assertEquals(GdVariantType.VARIANT, entry.initializerType().publishedType()),
                () -> assertTrue(prepared.analysisData().resolvedMembers().isEmpty()),
                () -> assertTrue(prepared.analysisData().resolvedCalls().isEmpty()),
                () -> assertTrue(prepared.analysisData().expressionTypes().isEmpty()),
                () -> assertEquals(0, prepared.diagnosticManager().snapshot().asList().size())
        );
    }

    @Test
    void analyzeLeavesUnsupportedControlFlowSubtreeLocalsUnchanged() throws Exception {
        var prepared = prepareProbeInput(
                "local_type_stabilization_unsupported_subtrees_writeback.gd",
                """
                        class_name LocalTypeStabilizationUnsupportedSubtreesWriteback
                        extends RefCounted

                        class Point:
                            var marker: int = -1

                        func make_point() -> Point:
                            return Point.new()

                        func ping(toggle, choice, items):
                            var before_loop := make_point()
                            for item in items:
                                var inside_loop := make_point()
                            match choice:
                                0:
                                    var inside_match := make_point()
                                _:
                                    pass
                            if toggle:
                                var inside_if := make_point()
                            before_loop
                        """
        );

        var pingFunction = findFunction(prepared.unit().ast().statements(), "ping");
        var diagnosticsBeforeAnalyze = prepared.diagnosticManager().snapshot().asList().size();

        var analyzer = new FrontendLocalTypeStabilizationAnalyzer();
        var snapshot = analyzer.probe(
                prepared.classRegistry(),
                prepared.analysisData(),
                prepared.diagnosticManager()
        );
        analyzer.analyze(
                prepared.classRegistry(),
                prepared.analysisData(),
                prepared.diagnosticManager()
        );

        assertAll(
                () -> assertTypeNameEndsWith(
                        currentLocalType(prepared.analysisData(), pingFunction.body(), "before_loop"),
                        "Point"
                ),
                () -> assertTypeNameEndsWith(
                        currentLocalType(prepared.analysisData(), findNode(pingFunction, Block.class, block ->
                                block.statements().stream().anyMatch(statement ->
                                        statement instanceof VariableDeclaration declaration
                                                && declaration.name().equals("inside_if")
                                )
                        ), "inside_if"),
                        "Point"
                ),
                () -> assertNull(snapshot.findVariable("inside_loop")),
                () -> assertNull(snapshot.findVariable("inside_match")),
                () -> assertTrue(prepared.analysisData().resolvedMembers().isEmpty()),
                () -> assertTrue(prepared.analysisData().resolvedCalls().isEmpty()),
                () -> assertTrue(prepared.analysisData().expressionTypes().isEmpty()),
                () -> assertEquals(diagnosticsBeforeAnalyze, prepared.diagnosticManager().snapshot().asList().size())
        );
    }

    @Test
    void probeSkipsUnsupportedControlFlowSubtreesAndDoesNotLeakDiagnostics() throws Exception {
        var prepared = prepareProbeInput(
                "local_type_stabilization_unsupported_subtrees.gd",
                """
                        class_name LocalTypeStabilizationUnsupportedSubtrees
                        extends RefCounted

                        class Point:
                            var marker: int = -1

                        func make_point() -> Point:
                            return Point.new()

                        func ping(toggle, choice, items):
                            var before_loop := make_point()
                            for item in items:
                                var inside_loop := make_point()
                            match choice:
                                0:
                                    var inside_match := make_point()
                                _:
                                    pass
                            if toggle:
                                var inside_if := make_point()
                            before_loop
                        """
        );

        var pingFunction = findFunction(prepared.unit().ast().statements(), "ping");
        var forStatement = findNode(pingFunction, ForStatement.class, _ -> true);
        var matchStatement = findNode(pingFunction, MatchStatement.class, _ -> true);
        var forDeclaration = findNode(forStatement, VariableDeclaration.class, declaration -> declaration.name().equals("inside_loop"));
        var matchDeclaration = findNode(
                matchStatement,
                VariableDeclaration.class,
                declaration -> declaration.name().equals("inside_match")
        );
        var diagnosticsBeforeProbe = prepared.diagnosticManager().snapshot().asList().size();

        var snapshot = new FrontendLocalTypeStabilizationAnalyzer().probe(
                prepared.classRegistry(),
                prepared.analysisData(),
                prepared.diagnosticManager()
        );

        assertAll(
                () -> assertNotNull(snapshot.findVariable("before_loop")),
                () -> assertNotNull(snapshot.findVariable("inside_if")),
                () -> assertNull(snapshot.findVariable("inside_loop")),
                () -> assertNull(snapshot.findVariable("inside_match")),
                () -> assertNull(prepared.analysisData().expressionTypes().get(forDeclaration.value())),
                () -> assertNull(prepared.analysisData().expressionTypes().get(matchDeclaration.value())),
                () -> assertTrue(prepared.analysisData().resolvedMembers().isEmpty()),
                () -> assertTrue(prepared.analysisData().resolvedCalls().isEmpty()),
                () -> assertEquals(diagnosticsBeforeProbe, prepared.diagnosticManager().snapshot().asList().size())
        );
    }

    private static @NotNull PreparedProbeInput prepareProbeInput(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnosticManager = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnosticManager);
        assertTrue(diagnosticManager.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnosticManager.snapshot());

        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                classRegistry,
                diagnosticManager,
                analysisData
        );
        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendScopeAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendVariableAnalyzer().analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendTopBindingAnalyzer().analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        return new PreparedProbeInput(unit, analysisData, diagnosticManager, classRegistry);
    }

    private static @NotNull FunctionDeclaration findFunction(
            @NotNull List<Statement> statements,
            @NotNull String functionName
    ) {
        for (var statement : statements) {
            if (statement instanceof FunctionDeclaration functionDeclaration
                    && functionDeclaration.name().equals(functionName)) {
                return functionDeclaration;
            }
        }
        throw new AssertionError("Function not found: " + functionName);
    }

    private static @NotNull VariableDeclaration findVariable(
            @NotNull List<Statement> statements,
            @NotNull String variableName
    ) {
        for (var statement : statements) {
            if (statement instanceof VariableDeclaration variableDeclaration
                    && variableDeclaration.name().equals(variableName)) {
                return variableDeclaration;
            }
        }
        throw new AssertionError("Variable not found: " + variableName);
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        Objects.requireNonNull(predicate, "predicate must not be null");
        if (nodeType.isInstance(root)) {
            var candidate = nodeType.cast(root);
            if (predicate.test(candidate)) {
                return candidate;
            }
        }
        for (var child : root.getChildren()) {
            try {
                return findNode(child, nodeType, predicate);
            } catch (AssertionError ignored) {
                // Keep scanning remaining subtrees until one matches.
            }
        }
        throw new AssertionError("Node not found: " + nodeType.getSimpleName());
    }

    private static @NotNull GdType currentLocalType(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Block body,
            @NotNull String variableName
    ) {
        var bodyScope = assertInstanceOf(BlockScope.class, analysisData.scopesByAst().get(body));
        var value = bodyScope.resolveValue(variableName);
        assertNotNull(value);
        return value.type();
    }

    private static void assertTypeNameEndsWith(@NotNull GdType type, @NotNull String suffix) {
        assertTrue(
                type.getTypeName().endsWith(suffix),
                () -> "Expected type ending with '" + suffix + "', but was '" + type.getTypeName() + "'"
        );
    }

    private record PreparedProbeInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull ClassRegistry classRegistry
    ) {
    }
}
