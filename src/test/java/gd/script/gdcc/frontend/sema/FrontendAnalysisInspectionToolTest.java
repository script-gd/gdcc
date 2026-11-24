package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.frontend.sema.debug.FrontendAnalysisInspectionTool;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdCallableType;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAnalysisInspectionToolTest {
    @Test
    void inspectSingleScriptMatchesDirectRenderEntry() throws Exception {
        var tool = new FrontendAnalysisInspectionTool(defaultRegistry());
        var result = tool.inspectSingleScript(
                "test_module",
                Path.of("tmp", "inspection_basic.gd"),
                """
                        class_name InspectionBasic
                        extends Node
                        
                        func helper(seed) -> String:
                            return "ok"
                        
                        func ping(seed):
                            helper(seed)
                        """
        );

        assertEquals(
                result.report(),
                tool.renderSingleUnitReport(result.module(), result.unit(), result.analysisData())
        );
    }

    @Test
    void reportShowsPublishedAndDerivedCallsAndRouteHeadUnpublishedExpressions() throws Exception {
        var inspected = inspect(
                "published_and_derived_calls.gd",
                """
                        class_name PublishedAndDerivedCalls
                        extends RefCounted
                        
                        class Worker:
                            static func build(seed) -> Worker:
                                return Worker.new()
                        
                        func helper(seed) -> String:
                            return "ok"

                        func make_cb() -> Callable:
                            return helper
                        
                        func ping(seed):
                            Worker.build(seed)
                            helper(seed)
                            self.make_cb()()
                        """
        );

        var data = inspected.analysisData();
        var ping = assertInstanceOf(FunctionDeclaration.class, inspected.unit().ast().statements().getLast());
        assertEquals("ping", ping.name());
        var statements = ping.body().statements();
        assertEquals(3, statements.size());
        var staticStatement = assertInstanceOf(ExpressionStatement.class, statements.getFirst());
        var staticChain = assertInstanceOf(AttributeExpression.class, staticStatement.expression());
        var worker = assertInstanceOf(IdentifierExpression.class, staticChain.base());
        assertEquals("Worker", worker.name());
        var workerBinding = data.symbolBindings().get(worker);
        assertNotNull(workerBinding);
        assertEquals(FrontendBindingKind.TYPE_META, workerBinding.kind());
        assertNull(data.expressionTypes().get(worker));

        var damagedStatement = assertInstanceOf(ExpressionStatement.class, statements.getLast());
        var outer = assertInstanceOf(CallExpression.class, damagedStatement.expression());
        var callee = assertInstanceOf(AttributeExpression.class, outer.callee());
        var inner = assertInstanceOf(AttributeCallStep.class, callee.steps().getLast());
        assertTrue(data.skippedSubtreeRoots().containsKey(damagedStatement));
        assertFalse(data.skippedSubtreeRoots().containsKey(staticStatement));
        for (var node : List.of(outer, callee, inner)) {
            assertNull(data.expressionTypes().get(node));
            assertNull(data.resolvedCalls().get(node));
            assertNull(data.propagatedFailureOrigins().get(node));
        }

        var recoveries = inspected.unit().parseRecoveries();
        assertEquals(1, recoveries.size());
        var recovery = recoveries.getFirst();
        assertEquals(DiagnosticCodes.CALL_INVALID_CALLEE, recovery.code());
        assertEquals(FrontendDiagnosticSeverity.ERROR, recovery.severity());
        assertSame(damagedStatement, recovery.owner());
        assertEquals("expression", recovery.facet());
        var errors = data.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
                .toList();
        assertEquals(1, errors.size(), errors::toString);
        var error = errors.getFirst();
        assertEquals(DiagnosticCodes.CALL_INVALID_CALLEE, error.category());
        assertEquals(FrontendRange.fromAstRange(callee.range()), error.range());
        assertEquals(error.range(), recovery.range());

        var report = inspected.report();
        assertTrue(report.contains("FORMAT frontend-analysis-text-v1"));
        var pingStart = report.indexOf("== func ping(seed) ==");
        assertTrue(pingStart >= 0, report);
        var pingReport = report.substring(pingStart);
        var workerFields = reportFields(pingReport, "E", "IdentifierExpression `Worker`");
        assertTrue(workerFields.contains("type.source = display"), workerFields::toString);
        assertTrue(workerFields.contains("type.status = UNPUBLISHED"), workerFields::toString);
        assertTrue(workerFields.contains(
                "type.reason = route-head TYPE_META is intentionally not published as ordinary value expression"
        ), workerFields::toString);
        assertCallFields(pingReport, "build(seed)", "published", "RESOLVED", "STATIC_METHOD");
        var helperFields = assertCallFields(pingReport, "helper(seed)", "published", "RESOLVED", "INSTANCE_METHOD");
        assertTrue(helperFields.contains("calleeBinding = METHOD"), helperFields::toString);
        assertCallFields(pingReport, "make_cb()", "display", "UNPUBLISHED", "UNPUBLISHED_CALL_FACT");
        var outerFields = assertCallFields(pingReport, "self.make_cb()()", "derived", "UNPUBLISHED", "CALL_DERIVED");
        assertTrue(outerFields.contains(
                "detailReason = Call expression is rendered from display-only facts because it has no published call fact"
        ), outerFields::toString);
        assertTrue(report.contains("ERROR " + DiagnosticCodes.CALL_INVALID_CALLEE + " "));
        assertFalse(report.contains("Direct invocation of callable values"));
    }

    @Test
    void reportShowsUnsupportedDerivedCallReasonForHandBuiltCallWithoutRecovery() throws Exception {
        var diagnostics = new DiagnosticManager();
        var parsed = new GdScriptParserService().parseUnit(Path.of("tmp", "hand_built_derived_call.gd"), """
                extends RefCounted

                func make_cb() -> Callable:
                    return make_cb

                func ping():
                    self.make_cb()\s\s
                    pass
                """, diagnostics);
        assertTrue(diagnostics.isEmpty(), diagnostics.snapshot().asList()::toString);
        assertTrue(parsed.parseRecoveries().isEmpty());
        var statements = new ArrayList<>(parsed.ast().statements());
        var ping = assertInstanceOf(FunctionDeclaration.class, statements.getLast());
        var callStatement = assertInstanceOf(ExpressionStatement.class, ping.body().statements().getFirst());
        var callee = assertInstanceOf(AttributeExpression.class, callStatement.expression());
        var inner = assertInstanceOf(AttributeCallStep.class, callee.steps().getLast());
        var range = callee.range();
        // Replace two reserved spaces so the surrounding parsed nodes keep their original ranges.
        var source = parsed.source().replace("self.make_cb()  ", "self.make_cb()()");
        var outer = new CallExpression(callee, List.of(), new Range(
                range.startByte(), range.endByte() + 2, range.startPoint(),
                new Point(range.endPoint().row(), range.endPoint().column() + 2)
        ));
        var statement = new ExpressionStatement(outer, outer.range());
        var body = new Block(List.of(statement, ping.body().statements().getLast()), ping.body().range());
        statements.set(statements.size() - 1, new FunctionDeclaration(
                ping.name(), ping.parameters(), ping.returnType(), ping.isStatic(), body, ping.range()
        ));
        var unit = new FrontendSourceUnit(parsed.path(), source, new SourceFile(statements, parsed.ast().range()));
        var module = FrontendModule.singleUnit("test_module", unit);
        var registry = defaultRegistry();
        var data = new FrontendSemanticAnalyzer().analyze(module, registry, diagnostics);

        assertFalse(data.skippedSubtreeRoots().containsKey(statement));
        var innerCall = data.resolvedCalls().get(inner);
        assertNotNull(innerCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, innerCall.status());
        assertInstanceOf(GdCallableType.class, innerCall.returnType());
        var calleeType = data.expressionTypes().get(callee);
        assertNotNull(calleeType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, calleeType.status());
        assertInstanceOf(GdCallableType.class, calleeType.publishedType());
        assertNull(data.resolvedCalls().get(outer));
        var outerType = data.expressionTypes().get(outer);
        assertNotNull(outerType);
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, outerType.status());
        assertNull(outerType.publishedType());
        var reason = outerType.detailReason();
        assertNotNull(reason);
        assertTrue(reason.contains("Direct invocation of callable values"), reason);
        var errors = data.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR)
                .toList();
        assertEquals(1, errors.size(), errors::toString);
        assertEquals("sema.unsupported_expression_route", errors.getFirst().category());
        assertEquals(FrontendRange.fromAstRange(outer.range()), errors.getFirst().range());
        assertEquals(reason, errors.getFirst().message());

        var report = new FrontendAnalysisInspectionTool(registry).renderSingleUnitReport(module, unit, data);
        assertCallFields(report, "make_cb()", "published", "RESOLVED", "INSTANCE_METHOD");
        var outerFields = assertCallFields(report, "self.make_cb()()", "derived", "UNSUPPORTED", "CALL_DERIVED");
        assertTrue(outerFields.contains("detailReason = " + reason), outerFields::toString);
    }

    @Test
    void reportShowsDisplayOnlyUnpublishedAttributeCallFactsInsideConstInitializer() throws Exception {
        var report = inspect(
                "const_initializer_unpublished_call.gd",
                """
                        class_name ConstInitializerUnpublishedCall
                        extends RefCounted
                        
                        class Worker:
                            static func build() -> Worker:
                                return Worker.new()
                        
                        const Alias = Worker.build()
                        """
        ).report();

        assertTrue(report.contains("call.source = display"));
        assertTrue(report.contains("status = UNPUBLISHED"));
        assertTrue(report.contains("callKind = UNPUBLISHED_CALL_FACT"));
    }

    @Test
    void reportShowsDiagnosticsGloballyAndInlineForPropertyInitializerBoundary() throws Exception {
        var report = inspect(
                "property_initializer_diagnostic.gd",
                """
                        class_name PropertyInitializerDiagnostic
                        extends RefCounted
                        
                        var payload: int = 1
                        var copy := self.payload
                        """
        ).report();

        assertTrue(report.contains("DIAGNOSTICS"));
        assertTrue(report.contains("D0001 ERROR sema.unsupported_binding_subtree"));
        assertTrue(report.contains("self"));
        assertTrue(report.contains("diagnostics = [D0001]"));
        assertTrue(report.contains("type.status = BLOCKED"));
    }

    @Test
    void inspectSingleScriptKeepsCompileOnlyGateOutOfSharedInspectionPipeline() throws Exception {
        var inspected = inspect(
                "inspection_compile_gate_split.gd",
                """
                        class_name InspectionCompileGateSplit
                        extends Node
                        
                        func ping():
                            assert(1, "inspection stays on shared semantic facts")
                            [1]
                        """
        );

        assertFalse(inspected.analysisData().diagnostics().hasErrors());
        assertTrue(inspected.analysisData().diagnostics().asList().stream().noneMatch(diagnostic ->
                diagnostic.category().equals("sema.compile_check")
        ));
        assertFalse(inspected.report().contains("sema.compile_check"));
    }

    @Test
    void reportKeepsUtf8ExpressionSnippetsIntact() throws Exception {
        var report = inspect(
                "utf8_expression_snippet.gd",
                """
                        class_name Utf8ExpressionSnippet
                        extends Node
                        
                        func helper(text: String) -> String:
                            return text
                        
                        func ping():
                            helper("中文")
                        """
        ).report();

        assertTrue(report.contains("`helper(\"中文\")`"));
        assertTrue(report.contains("`\"中文\"`"));
    }

    @Test
    void reportShowsPublishedMatchExpressionsAndPatternContextUnpublishedReason() throws Exception {
        var report = inspect(
                "match_inspection.gd",
                """
                        class_name MatchInspection
                        extends Node
                        
                        func ping(value: int):
                            match value:
                                1:
                                    pass
                                var bound:
                                    print(bound)
                                [1, ..]:
                                    pass
                                _:
                                    pass
                        """
        ).report();

        assertTrue(report.contains("type.status = RESOLVED"), report);
        assertTrue(report.contains("pattern-context node is intentionally not published as an ordinary value expression"), report);
        assertFalse(report.contains("expression belongs to a subtree that is intentionally skipped, deferred, or unsupported")
                && report.contains("`_`"));
    }

    @Test
    void reportKeepsPatternContextReasonInsideForBody() throws Exception {
        var report = inspect(
                "match_inspection_in_for.gd",
                """
                        class_name MatchInspectionInFor
                        extends Node
                        
                        func ping(values):
                            for value in values:
                                match value:
                                    var bound:
                                        pass
                                    _:
                                        pass
                        """
        ).report();

        assertTrue(report.contains("pattern-context node is intentionally not published as an ordinary value expression"), report);
        assertFalse(report.contains("expression belongs to a subtree that is intentionally skipped, deferred, or unsupported")
                && report.contains("`bound`"));
    }

    @Test
    void reportKeepsNestedDictionaryPatternReasonInsideForBody() throws Exception {
        var report = inspect(
                "match_inspection_nested_dict.gd",
                """
                        class_name MatchInspectionNestedDict
                        extends Node
                        
                        func ping(values):
                            for value in values:
                                match value:
                                    {"user": {"name": var nested}}:
                                        pass
                                    {"flag": _}:
                                        pass
                        """
        ).report();

        assertTrue(report.contains("pattern-context node is intentionally not published as an ordinary value expression"), report);
        assertFalse(report.contains("expression belongs to a subtree that is intentionally skipped, deferred, or unsupported")
                && report.contains("`nested`"));
        assertFalse(report.contains("expression belongs to a subtree that is intentionally skipped, deferred, or unsupported")
                && report.contains("`_`"));
    }

    private static List<String> assertCallFields(
            String report, String snippet, String source, String status, String kind
    ) {
        var fields = reportFields(report, "C", "`" + snippet + "`");
        assertTrue(fields.contains("call.source = " + source), fields::toString);
        assertTrue(fields.contains("status = " + status), fields::toString);
        assertTrue(fields.contains("callKind = " + kind), fields::toString);
        return fields;
    }

    private static List<String> reportFields(String report, String recordPrefix, String suffix) {
        var lines = report.lines().dropWhile(line ->
                !line.stripLeading().matches(recordPrefix + "\\d+ .*") || !line.endsWith(" " + suffix)
        ).toList();
        assertFalse(lines.isEmpty(), () -> "Missing report entry " + suffix + ":\n" + report);
        var header = lines.getFirst();
        var fieldIndent = " ".repeat(header.length() - header.stripLeading().length() + 2);
        return lines.stream().skip(1).takeWhile(line -> line.startsWith(fieldIndent)).map(String::strip).toList();
    }

    private static @NotNull InspectionOutput inspect(@NotNull String fileName, @NotNull String source) throws Exception {
        var tool = new FrontendAnalysisInspectionTool(defaultRegistry());
        var result = tool.inspectSingleScript("test_module", Path.of("tmp", fileName), source);
        return new InspectionOutput(result.unit(), result.analysisData(), result.report());
    }

    private static @NotNull ClassRegistry defaultRegistry() throws Exception {
        return new ClassRegistry(ExtensionApiLoader.loadDefault());
    }

    private record InspectionOutput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull String report
    ) {
    }
}
