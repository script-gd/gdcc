package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.sema.debug.FrontendAnalysisInspectionTool;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        var report = inspect(
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
        ).report();

        assertTrue(report.contains("FORMAT frontend-analysis-text-v1"));
        assertTrue(report.contains("== func ping(seed) =="));
        assertTrue(report.contains("IdentifierExpression `Worker`"));
        assertTrue(report.contains("type.status = UNPUBLISHED"));
        assertTrue(report.contains("route-head TYPE_META is intentionally not published as ordinary value expression"));
        assertTrue(report.contains("call.source = published"));
        assertTrue(report.contains("callKind = STATIC_METHOD"));
        assertTrue(report.contains("callKind = INSTANCE_METHOD"));
        assertTrue(report.contains("calleeBinding = METHOD"));
        assertTrue(report.contains("call.source = derived"));
        assertTrue(report.contains("callKind = CALL_DERIVED"));
        assertTrue(report.contains("Direct invocation of callable values"));
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
